; SPDX-License-Identifier: EPL-2.0
(ns nous.analysis.counterpoint
  "Inter-voice interval analysis for JI voice-leading calibration.

  Extracts empirical statistics from polyphonic corpus data (Palestrina,
  Josquin, Bach) that calibrate `defensemble`'s tension thresholds and
  parallel-motion rules. Input comes from `m21/load-work` in `:intervals`
  mode.

  ## Quick start

    ;; Find Palestrina works
    (m21/search-corpus {:composer \"palestrina\"})

    ;; Load a single work
    (def kyrie (m21/load-work \"palestrina/kyrie.mxl\" :intervals))

    ;; Analyze one voice pair
    (def analysis (analyze-pair kyrie :soprano :alto))
    (print-summary analysis)

    ;; Batch over all found Palestrina works
    (def corpus-analysis
      (analyze-corpus {:composer \"palestrina\"} [:soprano :alto]))
    (print-summary (:aggregate corpus-analysis))

  ## Input format (from m21/load-work :intervals)

    {:soprano [{:from 67 :to 65 :semitones -2 :dur/beats 1.0} ...]
     :alto    [{:from 62 :to 64 :semitones  2 :dur/beats 1.0} ...]}

  Each map is one note-to-note transition within a single voice.
  Rests are skipped; only pitched note events are recorded.

  ## Analysis outputs

    :histogram     — {H-band count} frequency distribution of from-position
                     inter-voice Tenney H, bucketed at 0.5-H intervals
    :parallel-rate — {H-band {:total N :parallel N :rate 0..1}} fraction of
                     arrivals at each H-band that came via parallel motion
    :transitions   — sorted seq of most common {from-band to-band count}
    :resolution    — distribution of moves following dissonance (H > 5.5)

  ## Alignment note

  Voice pairing is index-based: step i of voice-a pairs with step i of
  voice-b. This is exact for note-against-note (first species) counterpoint
  and approximate for florid styles. The statistics are still meaningful in
  aggregate because alignment errors average out across a large corpus.

  ## Connection to defensemble calibration

  The `defensemble` defaults (fusion-threshold 3.5, consonance-horizon 6.5,
  parallel motion penalty 0.15) were set from the theoretical Tenney
  analysis in the design seed. Run this analysis on the full Palestrina
  corpus and compare the empirical `:parallel-rate` at H < 3.5 to verify
  those thresholds. The `:resolution` profile tells you how large the
  dissonance-resolving steps actually are."
  (:require [nomos.maths.harmonic :as h]
            [nous.m21             :as m21]
            [clojure.string       :as str]))

;; ---------------------------------------------------------------------------
;; H-band utilities
;; ---------------------------------------------------------------------------

(def ^:private BAND-WIDTH 0.5)

(defn- h-band
  "Round Tenney H down to the nearest BAND-WIDTH boundary."
  ^double [^double tenney]
  (/ (Math/floor (* tenney (/ 1.0 BAND-WIDTH))) (/ 1.0 BAND-WIDTH)))

(defn imperfect-consonance?
  "True if Tenney H is in the imperfect consonance band [3.5, 5.5]."
  [tenney]
  (<= 3.5 (double tenney) 5.5))

(defn fusion-risk?
  "True if Tenney H is below the fusion threshold (< 3.5)."
  [tenney]
  (< (double tenney) 3.5))

(defn active-dissonance?
  "True if Tenney H is above the active dissonance threshold (> 5.5)."
  [tenney]
  (> (double tenney) 5.5))

;; ---------------------------------------------------------------------------
;; Inter-voice interval
;; ---------------------------------------------------------------------------

(defn interval-h
  "Tenney H of the interval between two MIDI pitches.
  Uses the 5-limit JI semitone mapping from nomos.maths.harmonic."
  ^double [midi-a midi-b]
  (h/midi->tenney-h (Math/abs (- (long midi-a) (long midi-b)))))

;; ---------------------------------------------------------------------------
;; Voice pairing — index-based alignment
;; ---------------------------------------------------------------------------

(defn pair-voices
  "Align two per-voice intervals sequences by step index and compute
  inter-voice Tenney H at each paired transition.

  `seq-a`, `seq-b` — sequences of `{:from M :to N :semitones S :dur/beats D}`
  as returned by m21/load-work in :intervals mode.

  Returns a seq of paired-step maps:
    :from-H    — inter-voice Tenney H at the start of the step
    :to-H      — inter-voice Tenney H at the end of the step
    :v1-dir    — voice-1 motion direction: -1 down, 0 static, +1 up
    :v2-dir    — voice-2 motion direction: -1 down, 0 static, +1 up
    :parallel? — true if both voices moved in the same direction (and not 0)
    :from-band — H-band of :from-H (nearest 0.5 below)
    :to-band   — H-band of :to-H"
  [seq-a seq-b]
  (map (fn [{from-a :from to-a :to semi-a :semitones}
            {from-b :from to-b :to semi-b :semitones}]
         (let [from-h  (interval-h from-a from-b)
               to-h    (interval-h to-a to-b)
               dir-a   (cond (pos? (long semi-a)) 1
                             (neg? (long semi-a)) -1
                             :else 0)
               dir-b   (cond (pos? (long semi-b)) 1
                             (neg? (long semi-b)) -1
                             :else 0)
               par?    (and (= dir-a dir-b) (not (zero? dir-a)))]
           {:from-H    from-h
            :to-H      to-h
            :v1-dir    dir-a
            :v2-dir    dir-b
            :parallel? par?
            :from-band (h-band from-h)
            :to-band   (h-band to-h)}))
       seq-a seq-b))

;; ---------------------------------------------------------------------------
;; Analysis: interval histogram
;; ---------------------------------------------------------------------------

(defn interval-histogram
  "Frequency distribution of inter-voice Tenney H at the start of each step.

  Returns a sorted map of {H-band count}, where H-band is the nearest 0.5
  below the actual Tenney H value."
  [paired-steps]
  (into (sorted-map) (frequencies (map :from-band paired-steps))))

;; ---------------------------------------------------------------------------
;; Analysis: parallel motion rate
;; ---------------------------------------------------------------------------

(defn parallel-motion-rate
  "For each arrival H-band, compute the fraction of arrivals that came via
  parallel motion.

  Parallel motion here means: both voices moved in the same direction AND the
  arrival interval is below `fusion-threshold` (default 3.5).

  Returns: {H-band {:total N :parallel N :rate 0..1}}
  Only H-bands actually reached in the data are included."
  ([paired-steps] (parallel-motion-rate paired-steps 3.5))
  ([paired-steps ^double fusion-threshold]
   (let [grouped (group-by :to-band paired-steps)]
     (into (sorted-map)
           (map (fn [[band steps]]
                  (let [total    (count steps)
                        parallel (count (filter #(and (:parallel? %)
                                                      (< (double (:to-H %)) fusion-threshold))
                                                steps))]
                    [band {:total    total
                           :parallel parallel
                           :rate     (/ (double parallel) (max 1 total))}]))
                grouped)))))

;; ---------------------------------------------------------------------------
;; Analysis: transition summary
;; ---------------------------------------------------------------------------

(defn transition-summary
  "Most common from-band → to-band transitions, sorted by frequency.

  Returns a seq of `{:from-band F :to-band T :count N}` maps, up to `n`
  most frequent. Useful for seeing which interval movements dominate."
  ([paired-steps] (transition-summary paired-steps 20))
  ([paired-steps n]
   (->> (map (fn [s] [(:from-band s) (:to-band s)]) paired-steps)
        frequencies
        (sort-by (comp - val))
        (take n)
        (mapv (fn [[[fb tb] cnt]] {:from-band fb :to-band tb :count cnt})))))

;; ---------------------------------------------------------------------------
;; Analysis: resolution profile
;; ---------------------------------------------------------------------------

(defn resolution-profile
  "Distribution of voice-direction combinations and arrival H-bands
  immediately following dissonance (from-H > dissonance-threshold).

  Answers: when voices are in active dissonance, how do they move, and
  where do they land?

  Returns sorted seq of `{:v1-dir D :v2-dir E :to-band B :count N}`."
  ([paired-steps] (resolution-profile paired-steps 5.5))
  ([paired-steps ^double dissonance-threshold]
   (->> paired-steps
        (filter #(> (double (:from-H %)) dissonance-threshold))
        (map (fn [{:keys [v1-dir v2-dir to-band]}]
               {:v1-dir v1-dir :v2-dir v2-dir :to-band to-band}))
        frequencies
        (sort-by (comp - val))
        (mapv (fn [[k cnt]] (assoc k :count cnt))))))

;; ---------------------------------------------------------------------------
;; Analyze a single voice pair
;; ---------------------------------------------------------------------------

(defn analyze-pair
  "Run the full counterpoint analysis on two voices from a work.

  `work`   — map returned by (m21/load-work path :intervals)
  `v1`,`v2` — voice keywords, e.g. :soprano :alto

  Returns:
    :voices        — [v1 v2]
    :n-steps       — number of paired steps analyzed
    :histogram     — inter-voice H distribution at step start
    :parallel-rate — parallel motion rate by arrival H-band
    :transitions   — top-20 from→to band transitions
    :resolution    — direction combos following dissonance
    :paired-steps  — raw paired data (for further analysis)"
  [work v1 v2]
  (let [seq-a  (get work v1 [])
        seq-b  (get work v2 [])
        paired (vec (pair-voices seq-a seq-b))]
    {:voices        [v1 v2]
     :n-steps       (count paired)
     :histogram     (interval-histogram paired)
     :parallel-rate (parallel-motion-rate paired)
     :transitions   (transition-summary paired)
     :resolution    (resolution-profile paired)
     :paired-steps  paired}))

;; ---------------------------------------------------------------------------
;; Aggregate across analyses
;; ---------------------------------------------------------------------------

(defn- merge-histograms
  "Merge a seq of {H-band count} maps by summing counts."
  [hists]
  (apply merge-with + hists))

(defn- merge-parallel-rates
  "Merge a seq of parallel-rate maps by summing :total and :parallel counts."
  [rates]
  (let [merged (apply merge-with
                      (fn [a b] {:total    (+ (:total a 0)    (:total b 0))
                                 :parallel (+ (:parallel a 0) (:parallel b 0))})
                      rates)]
    (into (sorted-map)
          (map (fn [[band {:keys [total parallel]}]]
                 [band {:total    total
                        :parallel parallel
                        :rate     (/ (double parallel) (max 1 total))}])
               merged))))

(defn aggregate-analyses
  "Aggregate a seq of per-work analysis maps into a single combined result.
  Histograms and transition tables are accumulated across all works."
  [analyses]
  (let [valid  (filterv #(pos? (:n-steps % 0)) analyses)
        paired (mapcat :paired-steps valid)
        n      (count paired)]
    {:n-steps       n
     :n-works       (count valid)
     :histogram     (interval-histogram paired)
     :parallel-rate (parallel-motion-rate paired)
     :transitions   (transition-summary paired 30)
     :resolution    (resolution-profile paired)}))

;; ---------------------------------------------------------------------------
;; Batch corpus analysis
;; ---------------------------------------------------------------------------

(defn analyze-corpus
  "Analyze all corpus works matching `search-opts` for the given voice pair.

  `search-opts` — map passed to m21/search-corpus, e.g. {:composer \"palestrina\"}
  `voices`      — pair of voice keywords, e.g. [:soprano :alto]

  Skips works where either voice is absent (e.g. two-voice works searched with
  a four-voice pair). Errors on individual works are caught and logged to stderr.

  Returns:
    :works-found    — total works returned by search
    :works-analyzed — works that had both voices present
    :per-work       — vec of per-work analysis maps (see analyze-pair)
    :aggregate      — aggregated analysis across all works"
  [search-opts voices]
  (let [[v1 v2] voices
        works   (m21/search-corpus search-opts)]
    (println (str "[counterpoint] found " (count works) " works, loading..."))
    (let [results (reduce
                   (fn [acc {:keys [path]}]
                     (try
                       (let [work (m21/load-work path :intervals)]
                         (if (and (seq (get work v1)) (seq (get work v2)))
                           (do
                             (print ".")
                             (flush)
                             (conj acc (assoc (analyze-pair work v1 v2) :path path)))
                           acc))
                       (catch Exception e
                         (binding [*out* *err*]
                           (println (str "\n[counterpoint] skipped " path ": " (.getMessage e))))
                         acc)))
                   []
                   works)]
      (println (str "\n[counterpoint] analyzed " (count results) "/" (count works) " works"))
      {:works-found    (count works)
       :works-analyzed (count results)
       :per-work       results
       :aggregate      (aggregate-analyses results)})))

;; ---------------------------------------------------------------------------
;; REPL display
;; ---------------------------------------------------------------------------

(defn- bar [n max-n width]
  (let [filled (int (Math/round (* width (/ (double n) (max 1.0 (double max-n))))))]
    (str (apply str (repeat filled "#"))
         (apply str (repeat (- width filled) " ")))))

(defn- h-band-label [band]
  (format "H %4.1f–%-4.1f" (double band) (+ (double band) BAND-WIDTH)))

(defn print-summary
  "Print a formatted summary of an analysis result to stdout.

  Works on output from `analyze-pair` or `aggregate-analyses`."
  [analysis]
  (let [{:keys [voices n-steps n-works histogram parallel-rate
                transitions resolution]} analysis
        voice-str (if voices (str (first voices) "/" (second voices)) "aggregate")
        works-str (when n-works (str n-works " works, "))]
    (println)
    (println (str "=== Counterpoint Analysis: " voice-str " ==="))
    (when n-steps
      (println (str "    " works-str n-steps " paired steps")))

    (println "\n-- Inter-voice interval distribution --")
    (let [max-count (apply max 1 (vals histogram))]
      (doseq [[band cnt] (sort histogram)]
        (let [pct (/ (* 100.0 cnt) (max 1 n-steps))]
          (println (format "  %s  %s  %4d  (%4.1f%%)"
                           (h-band-label band)
                           (bar cnt max-count 20)
                           cnt pct)))))

    (println "\n-- Parallel motion rate (arrivals below fusion threshold H<3.5) --")
    (doseq [[band {:keys [total parallel rate]}]
            (filter #(< (double (key %)) 4.0) (sort parallel-rate))]
      (println (format "  %s  parallel: %3d / %4d  (%5.1f%%)"
                       (h-band-label band)
                       parallel total (* 100.0 rate))))

    (println "\n-- Top-10 from → to band transitions --")
    (doseq [{:keys [from-band to-band count]} (take 10 transitions)]
      (println (format "  H%-4.1f → H%-4.1f  %d" (double from-band) (double to-band) count)))

    (println "\n-- Dissonance resolution profile (from H>5.5) --")
    (if (empty? resolution)
      (println "  (no dissonant steps in data)")
      (doseq [{:keys [v1-dir v2-dir to-band count]} (take 10 resolution)]
        (let [dir-str (fn [d] (case (long d) 1 "↑" -1 "↓" "—"))]
          (println (format "  v1%s v2%s → H%-4.1f  %d"
                           (dir-str v1-dir) (dir-str v2-dir)
                           (double to-band) count)))))
    (println)))

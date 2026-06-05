; SPDX-License-Identifier: EPL-2.0
(ns nous.analysis.counterpoint-test
  "Unit tests for nous.analysis.counterpoint.

  All tests use synthetic voice data — music21 and the m21 server are not
  required. The analyze-corpus function is not tested here; use the REPL
  with (m21/search-corpus {:composer \"palestrina\"}) to run live analysis."
  (:require [clojure.test      :refer [deftest is testing]]
            [nomos.maths.harmonic :as h]
            [nous.analysis.counterpoint :as cp]))

;; ---------------------------------------------------------------------------
;; Synthetic voice data helpers
;; ---------------------------------------------------------------------------

(defn- step
  "Build a single interval step map."
  [from to]
  {:from from :to to :semitones (- to from) :dur/beats 1.0})

(defn- voice
  "Build a voice intervals sequence from a seq of MIDI pitches."
  [pitches]
  (mapv (fn [[a b]] (step a b))
        (partition 2 1 pitches)))

;; Simple two-voice sequences for testing
;; Soprano: C5(72) D5(74) E5(76) D5(74) C5(72)
;; Alto:    E4(64) F4(65) G4(67) F4(65) E4(64)

(def ^:private soprano-5note (voice [72 74 76 74 72]))
(def ^:private alto-5note    (voice [64 65 67 65 64]))

;; Unison motion (parallel fifths-like): both voices move C-D
(def ^:private unison-v1 (voice [60 62 64 65]))
(def ^:private unison-v2 (voice [60 62 64 65]))

;; Contrary motion: v1 goes up, v2 goes down
(def ^:private up-v1   (voice [60 62 64]))
(def ^:private down-v2 (voice [67 65 64]))

;; ---------------------------------------------------------------------------
;; interval-h
;; ---------------------------------------------------------------------------

(deftest interval-h-unison
  (testing "unison has H = 0"
    (let [h (cp/interval-h 60 60)]
      (is (= 0.0 h) "same pitch → Tenney H = 0"))))

(deftest interval-h-octave
  (testing "octave (12 semitones) has H = 1.0"
    (let [h (cp/interval-h 60 72)]
      (is (= 1.0 h) "octave → Tenney H = 1"))))

(deftest interval-h-fifth
  (testing "perfect fifth (7 semitones) H ≈ 2.58"
    (let [h (cp/interval-h 60 67)]
      (is (> h 2.5))
      (is (< h 2.7)))))

(deftest interval-h-third
  (testing "major third (4 semitones) H ≈ 4.32"
    (let [h (cp/interval-h 60 64)]
      (is (> h 4.0))
      (is (< h 4.5)))))

(deftest interval-h-symmetry
  (testing "interval-h is symmetric"
    (is (= (cp/interval-h 60 67) (cp/interval-h 67 60)))))

;; ---------------------------------------------------------------------------
;; H classification predicates
;; ---------------------------------------------------------------------------

(deftest fusion-risk-classification
  (testing "fifth (H≈2.58) is fusion risk"
    (is (cp/fusion-risk? (cp/interval-h 60 67))))
  (testing "unison is fusion risk"
    (is (cp/fusion-risk? 0.0)))
  (testing "major third (H≈4.32) is NOT fusion risk"
    (is (not (cp/fusion-risk? (cp/interval-h 60 64))))))

(deftest imperfect-consonance-classification
  (testing "major third (H≈4.32) is imperfect consonance"
    (is (cp/imperfect-consonance? (cp/interval-h 60 64))))
  (testing "minor third (H≈4.91) is imperfect consonance"
    (is (cp/imperfect-consonance? (cp/interval-h 60 63))))
  (testing "unison is NOT imperfect consonance"
    (is (not (cp/imperfect-consonance? 0.0)))))

(deftest active-dissonance-classification
  (testing "tritone (H≈10.49) is active dissonance"
    (is (cp/active-dissonance? (cp/interval-h 60 66))))
  (testing "major third is NOT active dissonance"
    (is (not (cp/active-dissonance? (cp/interval-h 60 64))))))

;; ---------------------------------------------------------------------------
;; pair-voices
;; ---------------------------------------------------------------------------

(deftest pair-voices-count
  (testing "pair-voices produces min(n-a, n-b) steps"
    (let [paired (cp/pair-voices soprano-5note alto-5note)]
      (is (= (min (count soprano-5note) (count alto-5note))
             (count paired))))))

(deftest pair-voices-keys
  (testing "each paired step has all required keys"
    (let [paired (cp/pair-voices soprano-5note alto-5note)
          step   (first paired)]
      (is (contains? step :from-H))
      (is (contains? step :to-H))
      (is (contains? step :v1-dir))
      (is (contains? step :v2-dir))
      (is (contains? step :parallel?))
      (is (contains? step :from-band))
      (is (contains? step :to-band)))))

(deftest pair-voices-direction-detection
  (testing "ascending voice has v1-dir = 1"
    (let [paired (cp/pair-voices (voice [60 62]) (voice [64 65]))]
      (is (= 1 (:v1-dir (first paired))))))
  (testing "descending voice has v1-dir = -1"
    (let [paired (cp/pair-voices (voice [62 60]) (voice [65 64]))]
      (is (= -1 (:v1-dir (first paired))))))
  (testing "stationary voice has v1-dir = 0"
    (let [paired (cp/pair-voices (voice [60 60]) (voice [64 65]))]
      (is (= 0 (:v1-dir (first paired)))))))

(deftest pair-voices-parallel-detection
  (testing "same-direction motion is parallel"
    (let [paired (cp/pair-voices (voice [60 62]) (voice [64 66]))]
      (is (true? (:parallel? (first paired))))))
  (testing "contrary motion is not parallel"
    (let [paired (cp/pair-voices (voice [60 62]) (voice [66 64]))]
      (is (false? (:parallel? (first paired))))))
  (testing "both stationary is not parallel (dir=0)"
    (let [paired (cp/pair-voices (voice [60 60]) (voice [64 64]))]
      (is (false? (:parallel? (first paired)))))))

(deftest pair-voices-h-values
  (testing "from-H and to-H match expected intervals"
    ;; v1: 60→62, v2: 60→62 (unison start and end)
    (let [paired (vec (cp/pair-voices (voice [60 62]) (voice [60 62])))]
      (is (= 0.0 (:from-H (first paired))) "unison start")
      (is (= 0.0 (:to-H (first paired))) "unison end"))))

;; ---------------------------------------------------------------------------
;; interval-histogram
;; ---------------------------------------------------------------------------

(deftest interval-histogram-returns-sorted-map
  (let [paired (vec (cp/pair-voices soprano-5note alto-5note))
        hist   (cp/interval-histogram paired)]
    (is (map? hist))
    (is (every? number? (keys hist)))
    (is (every? pos-int? (vals hist)))))

(deftest interval-histogram-counts-sum-to-n-steps
  (let [paired (vec (cp/pair-voices soprano-5note alto-5note))
        hist   (cp/interval-histogram paired)]
    (is (= (count paired) (reduce + (vals hist))))))

(deftest interval-histogram-unison-at-band-0
  (testing "unison interval appears in band 0.0"
    (let [paired (vec (cp/pair-voices (voice [60 62]) (voice [60 62])))
          hist   (cp/interval-histogram paired)]
      (is (pos? (get hist 0.0 0))))))

;; ---------------------------------------------------------------------------
;; parallel-motion-rate
;; ---------------------------------------------------------------------------

(deftest parallel-motion-rate-keys
  (let [paired (vec (cp/pair-voices soprano-5note alto-5note))
        rates  (cp/parallel-motion-rate paired)]
    (is (map? rates))
    (doseq [[_ v] rates]
      (is (contains? v :total))
      (is (contains? v :parallel))
      (is (contains? v :rate))
      (is (<= 0.0 (:rate v) 1.0)))))

(deftest parallel-motion-at-unison-detected
  (testing "parallel motion to unison is counted"
    ;; Both voices move C→D: unison throughout, parallel motion
    (let [paired (vec (cp/pair-voices (voice [60 62]) (voice [60 62])))
          rates  (cp/parallel-motion-rate paired 3.5)]
      ;; Arrival band 0.0 (unison, H=0 < 3.5)
      (let [band-data (get rates 0.0)]
        (is (some? band-data))
        (is (pos? (:parallel band-data 0)))
        (is (= 1.0 (:rate band-data)))))))

(deftest contrary-motion-not-parallel
  (testing "contrary motion does not count as parallel"
    ;; v1 goes up, v2 goes down — contrary motion
    (let [paired (vec (cp/pair-voices (voice [60 62]) (voice [64 62])))
          rates  (cp/parallel-motion-rate paired 3.5)
          ;; to-H: 62 vs 62 = unison = 0.0
          band   (get rates 0.0)]
      (is (zero? (:parallel band 0))))))

;; ---------------------------------------------------------------------------
;; transition-summary
;; ---------------------------------------------------------------------------

(deftest transition-summary-structure
  (let [paired  (vec (cp/pair-voices soprano-5note alto-5note))
        summary (cp/transition-summary paired)]
    (is (sequential? summary))
    (is (every? #(contains? % :from-band) summary))
    (is (every? #(contains? % :to-band)   summary))
    (is (every? #(contains? % :count)     summary))))

(deftest transition-summary-sorted-descending
  (let [paired  (vec (cp/pair-voices soprano-5note alto-5note))
        summary (cp/transition-summary paired)
        counts  (map :count summary)]
    (is (= counts (sort > counts)) "transitions are sorted most-frequent first")))

(deftest transition-summary-respects-limit
  (let [paired  (vec (cp/pair-voices soprano-5note alto-5note))
        summary (cp/transition-summary paired 3)]
    (is (<= (count summary) 3))))

;; ---------------------------------------------------------------------------
;; resolution-profile
;; ---------------------------------------------------------------------------

(deftest resolution-profile-empty-when-no-dissonance
  (testing "no dissonant steps → empty profile"
    ;; Voice pair always at small interval (major third, H≈4.32 < 5.5)
    (let [paired  (vec (cp/pair-voices (voice [60 62 64]) (voice [64 66 68])))
          profile (cp/resolution-profile paired 5.5)]
      ;; Check if all from-H values are below 5.5
      (let [from-hs (map :from-H paired)]
        (if (every? #(< % 5.5) from-hs)
          (is (empty? profile))
          (is (sequential? profile)))))))

(deftest resolution-profile-structure
  (let [;; Tritone interval (H≈10.49) → clearly dissonant → resolution steps
        paired  (vec (cp/pair-voices (voice [60 62]) (voice [66 62])))
        profile (cp/resolution-profile paired 5.5)]
    (is (sequential? profile))
    (when (seq profile)
      (let [entry (first profile)]
        (is (contains? entry :v1-dir))
        (is (contains? entry :v2-dir))
        (is (contains? entry :to-band))
        (is (contains? entry :count))))))

;; ---------------------------------------------------------------------------
;; analyze-pair
;; ---------------------------------------------------------------------------

(deftest analyze-pair-structure
  (let [work    {:soprano soprano-5note :alto alto-5note}
        result  (cp/analyze-pair work :soprano :alto)]
    (is (= [:soprano :alto] (:voices result)))
    (is (= (min (count soprano-5note) (count alto-5note))
           (:n-steps result)))
    (is (map? (:histogram result)))
    (is (map? (:parallel-rate result)))
    (is (sequential? (:transitions result)))
    (is (sequential? (:resolution result)))
    (is (sequential? (:paired-steps result)))))

(deftest analyze-pair-missing-voice-returns-empty
  (let [work   {:soprano soprano-5note}
        result (cp/analyze-pair work :soprano :alto)]
    (is (= 0 (:n-steps result)))))

;; ---------------------------------------------------------------------------
;; aggregate-analyses
;; ---------------------------------------------------------------------------

(deftest aggregate-analyses-combines-counts
  (let [work1   {:soprano soprano-5note :alto alto-5note}
        work2   {:soprano (voice [67 69 71]) :alto (voice [64 65 67])}
        a1      (cp/analyze-pair work1 :soprano :alto)
        a2      (cp/analyze-pair work2 :soprano :alto)
        agg     (cp/aggregate-analyses [a1 a2])]
    (is (= (+ (:n-steps a1) (:n-steps a2)) (:n-steps agg)))
    (is (= 2 (:n-works agg)))
    (is (map? (:histogram agg)))
    (is (map? (:parallel-rate agg)))))

(deftest aggregate-analyses-skips-empty
  (let [empty-result {:n-steps 0 :paired-steps [] :histogram {} :parallel-rate {}
                      :transitions [] :resolution []}
        work         {:soprano soprano-5note :alto alto-5note}
        valid        (cp/analyze-pair work :soprano :alto)
        agg          (cp/aggregate-analyses [empty-result valid empty-result])]
    (is (= 1 (:n-works agg)) "empty results excluded")))

;; ---------------------------------------------------------------------------
;; pair-voices — beat-position alignment
;; ---------------------------------------------------------------------------

(defn- step-dur
  "Build a step map with explicit duration."
  [from to dur]
  {:from from :to to :semitones (- to from) :dur/beats (double dur)})

(deftest pair-voices-equal-durations-matches-by-index
  (testing "equal-duration voices: beat alignment == index alignment"
    (let [sa  [(step 60 62) (step 62 64)]
          sb  [(step 67 65) (step 65 64)]
          idx (cp/pair-voices-by-index sa sb)
          bt  (cp/pair-voices sa sb)]
      (is (= (count idx) (count bt)))
      (is (= (map :from-H idx) (map :from-H bt)))
      (is (= (map :to-H idx)   (map :to-H bt))))))

(deftest pair-voices-unequal-durations-more-events
  (testing "voice-b has twice as many short notes: beat alignment emits more events"
    ;; Voice A: one whole note C→D (dur 2)
    ;; Voice B: two half-note steps G→A→B (dur 1 each)
    ;; Index alignment: 1 pair (truncates to min length)
    ;; Beat alignment: 2 pairs (G onset at 0.0, A onset at 1.0, both < end-of-A at 2.0)
    (let [sa [(step-dur 60 62 2.0)]
          sb [(step-dur 67 69 1.0) (step-dur 69 71 1.0)]
          bt (cp/pair-voices sa sb)]
      (is (= 2 (count bt))
          "beat-aligned pairing emits one event per onset within shared range"))))

(deftest pair-voices-onset-zero-captured
  (testing "event at beat 0 is always captured"
    (let [sa [(step 60 62)]
          sb [(step 67 65)]
          bt (cp/pair-voices sa sb)]
      (is (pos? (count bt)))
      (is (= (cp/interval-h 60 67) (:from-H (first bt)))))))

(deftest pair-voices-direction-correctly-assigned
  (testing "motion directions reflect each voice's semitone movement"
    ;; Voice A moves up (+4), Voice B moves down (-2)
    (let [sa [(step 60 64)]   ; +4 semitones
          sb [(step 67 65)]   ; -2 semitones
          bt (cp/pair-voices sa sb)
          r  (first bt)]
      (is (= 1  (:v1-dir r)) "voice A moves up")
      (is (= -1 (:v2-dir r)) "voice B moves down")
      (is (false? (:parallel? r)) "contrary motion is not parallel"))))

(deftest pair-voices-empty-inputs-returns-empty
  (testing "empty inputs produce no pairs"
    (is (empty? (cp/pair-voices [] [])))
    (is (empty? (cp/pair-voices [(step 60 62)] [])))
    (is (empty? (cp/pair-voices [] [(step 60 62)])))))

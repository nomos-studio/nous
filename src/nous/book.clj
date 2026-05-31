; SPDX-License-Identifier: EPL-2.0
(ns nous.book
  "nous Book of Sounds sequencer — harmonic series as JI pitch space.

  Inspired by Hans Otte's Das Buch der Klänge (Book of Sounds, 1979–82).
  Treats the harmonic series above a fundamental as the pitch vocabulary.
  Each `defbook` defines named pages (harmonic regions) and navigates between them
  under gravity-weighted selection.

  ## Quick start

    (defbook resonant-space
      :fundamental :C2
      :pages [{:name :ground   :harmonics [1 2 3 4]    :gravity 1.0}
              {:name :outer    :harmonics [7 9 11 13]  :gravity 0.3
               :selection {:mode :proximate}}]
      :navigation {:initial :ground :mode :manual})

    (next-step! resonant-space)
    ;=> {:pitch/voct 0.585 :pitch/midi 55 :dur/beats 4.0
    ;    :book/harmonic 3   :book/gravity 0.12 :gate/on? true}

    (go-page! resonant-space :outer)

    ;; In a live-loop:
    (run-step! (make-book-seq resonant-space))

  ## Cell mode (committed motif with gravity-directed drift)

    (defbook patient-cell
      :fundamental :F#2
      :output-mode :cell
      :cell-len 4 :drift-prob 0.08 :drift-rate :per-pass
      :pages [{:name :ground :harmonics [1 2 3 4]   :gravity 1.0}
              {:name :outer  :harmonics [7 11 13 15] :gravity 0.2}])

  See design-seed-book-of-sounds.md for full vocabulary and open questions."
  (:require [nomos.maths.harmonic :as h]
            [nous.ctrl            :as ctrl]
            [nous.seq             :as sq]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register a book context atom under the given name keyword. Used by defbook."
  [k ctx-atom]
  (swap! registry assoc k ctx-atom))

;; ---------------------------------------------------------------------------
;; Fundamental parsing
;; ---------------------------------------------------------------------------

(def ^:private NOTE-SEMITONES
  {"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11})

(defn- note-keyword->midi
  "Parse note keyword like :C2 or :F#2 to MIDI note number, or nil on failure.
  Uses the convention C-1=0, C0=12, C4=60 (standard MIDI)."
  [kw]
  (let [s (name kw)
        m (re-matches #"([A-G])([#b]?)(-?\d+)" s)]
    (when m
      (let [[_ note acc oct-str] m
            base (get NOTE-SEMITONES note 0)
            semi (cond (= "#" acc) (inc base)
                       (= "b" acc) (dec base)
                       :else       base)
            oct  (Integer/parseInt oct-str)]
        (+ (* (+ oct 1) 12) semi)))))

(defn- parse-fundamental
  "Parse fundamental to Hz. Accepts numeric Hz or note keyword (:C2, :F#2)."
  ^double [x]
  (cond
    (number? x) (double x)
    (keyword? x) (if-let [midi (note-keyword->midi x)]
                   (* 440.0 (Math/pow 2.0 (/ (double (- (long midi) 69)) 12.0)))
                   261.626)
    :else 261.626))

;; ---------------------------------------------------------------------------
;; Gravity-weighted harmonic selection
;; ---------------------------------------------------------------------------

(defn- gravity-weight-h
  "Gravity weight for harmonic N at gravity strength g ∈ [0,1].
  weight(N, g) = (1-g) + g × (1/N)"
  ^double [^long n ^double g]
  (+ (- 1.0 g) (* g (/ 1.0 (double n)))))

(defn- weighted-sample-h
  "Sample one harmonic from `harmonics` using gravity-weighted probabilities."
  [harmonics ^double gravity]
  (if (empty? harmonics)
    1
    (let [ws    (mapv #(gravity-weight-h (long %) gravity) harmonics)
          total (double (reduce + ws))
          r     (* (rand) total)]
      (loop [hs harmonics ws ws acc 0.0]
        (if (empty? hs)
          (peek harmonics)
          (let [acc' (+ acc (double (first ws)))]
            (if (< r acc')
              (first hs)
              (recur (next hs) (next ws) acc'))))))))

(defn- proximate-sample-h
  "Sample one harmonic from `harmonics` weighted by proximity to `current-h`
  and gravity pull."
  [harmonics current-h ^double gravity]
  (if (nil? current-h)
    (weighted-sample-h harmonics gravity)
    (let [ch (long current-h)
          ws (mapv (fn [h]
                     (let [d (Math/abs (- (long h) ch))]
                       (* (/ 1.0 (+ 1.0 (double d)))
                          (gravity-weight-h (long h) gravity))))
                   harmonics)
          total (double (reduce + ws))
          r     (* (rand) total)]
      (loop [hs harmonics ws ws acc 0.0]
        (if (empty? hs)
          (peek harmonics)
          (let [acc' (+ acc (double (first ws)))]
            (if (< r acc')
              (first hs)
              (recur (next hs) (next ws) acc'))))))))

;; ---------------------------------------------------------------------------
;; Harmonic selection
;; ---------------------------------------------------------------------------

(defn- select-harmonic
  "Select the next harmonic N from page harmonics given sequencer state.
  Returns [selected-h new-h-idx new-h-dir]."
  [harmonics selection current-h h-idx h-dir page-gravity]
  (let [n (count harmonics)]
    (if (zero? n)
      [1 0 1]
      (case (:mode selection :weighted)
        :sequential
        (let [dir     (get selection :dir :up)
              idx     (mod (long h-idx) n)
              cur-h   (nth harmonics idx)
              new-idx (if (= dir :up)
                        (mod (inc (long idx)) n)
                        (mod (+ n (dec (long idx))) n))]
          [cur-h new-idx h-dir])

        :pendulum
        (let [idx      (max 0 (min (dec n) (long h-idx)))
              dir      (long h-dir)
              cur-h    (nth harmonics idx)
              new-raw  (+ idx dir)
              [new-idx new-dir]
              (cond
                (>= new-raw n) [(max 0 (- n 2)) -1]
                (< new-raw 0)  [(min 1 (dec n)) 1]
                :else          [new-raw dir])]
          [cur-h new-idx (long new-dir)])

        :proximate
        [(proximate-sample-h harmonics current-h page-gravity) h-idx h-dir]

        ;; :weighted (default)
        [(weighted-sample-h harmonics page-gravity) h-idx h-dir]))))

;; ---------------------------------------------------------------------------
;; Duration computation
;; ---------------------------------------------------------------------------

(defn- compute-beats
  "Compute step duration in beats from duration spec and harmonic N."
  ^double [duration ^long n]
  (cond
    (number? duration) (double duration)
    (map? duration)
    (case (:mode duration :beats)
      :probability
      (let [p     (double (:p duration 0.1))
            min-b (double (:min-beats duration 1.0))]
        (loop [b 0.0]
          (if (< (rand) p) (+ min-b b) (recur (+ b 1.0)))))
      :harmonic-modulated
      (let [base (double (:base-beats duration 8.0))]
        (/ base (Math/log (+ 1.0 (double n)))))
      ;; :beats (default)
      (double (:beats duration 4.0)))
    :else 4.0))

;; ---------------------------------------------------------------------------
;; Cell operations
;; ---------------------------------------------------------------------------

(defn- draw-cell
  "Draw `cell-len` harmonic numbers from `harmonics` using selection mode."
  [harmonics ^long cell-len selection ^double page-gravity]
  (let [n (count harmonics)]
    (if (zero? n)
      (vec (repeat cell-len 1))
      (case (:mode selection :weighted)
        :sequential
        (vec (take cell-len (cycle harmonics)))
        :pendulum
        (let [up   (vec harmonics)
              down (vec (reverse (butlast (rest up))))
              full (if (seq down) (concat up down) up)]
          (vec (take cell-len (cycle full))))
        ;; :weighted, :proximate — independent draws
        (vec (repeatedly cell-len #(weighted-sample-h harmonics page-gravity)))))))

(defn- drift-cell
  "Apply gravity-directed drift to cell: each position may drift to a simpler harmonic."
  [cell harmonics ^double drift-prob ^double page-gravity]
  (mapv (fn [cur-h]
          (if (< (rand) drift-prob)
            (let [cands (filterv #(< (long %) (long cur-h)) harmonics)]
              (if (seq cands)
                (weighted-sample-h cands page-gravity)
                cur-h))
            cur-h))
        cell))

;; ---------------------------------------------------------------------------
;; Pitch and gravity output
;; ---------------------------------------------------------------------------

(defn- harmonic->voct
  "V/oct offset from fundamental for harmonic number N.
  With octave-fold true, collapses all octave-equivalent harmonics to [0,1)."
  ^double [^long n octave-fold?]
  (if octave-fold?
    (h/ratio->voct (h/ratio-normalize [n 1]))
    (h/ratio->voct [n 1])))

(defn- voct->nearest-midi
  "Convert fundamental Hz + V/oct offset (relative to fundamental) to nearest MIDI note."
  ^long [^double fundamental-hz ^double voct-offset]
  (let [hz   (* fundamental-hz (Math/pow 2.0 voct-offset))
        midi (+ 69.0 (* 12.0 (/ (Math/log (/ hz 440.0)) (Math/log 2.0))))]
    (max 0 (min 127 (long (Math/round midi))))))

;; ---------------------------------------------------------------------------
;; Page navigation
;; ---------------------------------------------------------------------------

(defn- page-index-by-name
  "Return the index of the named page in pages, or nil."
  [pages page-name]
  (first (keep-indexed #(when (= (:name %2) page-name) %1) pages)))

(defn- auto-navigate
  "Return new page-idx based on navigation mode and current harmonic N."
  [page-idx pages navigation ^long current-n]
  (let [n-pages (count pages)]
    (case (:mode navigation :manual)
      :harmonic-threshold
      (let [up-at   (long (:up-at navigation 9))
            down-at (long (:down-at navigation 3))]
        (cond
          (and (>= current-n up-at)   (< (inc (long page-idx)) n-pages)) (inc (long page-idx))
          (and (<= current-n down-at) (pos? (long page-idx)))             (dec (long page-idx))
          :else (long page-idx)))
      (long page-idx))))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-book-context
  "Build a book context map from keyword options. Used by defbook."
  [opts]
  (let [{:keys [fundamental pages navigation output-mode
                octave-fold cell-len drift-prob drift-rate]
         :or   {fundamental 261.626
                pages       []
                octave-fold true
                output-mode :free
                cell-len    4
                drift-prob  0.08
                drift-rate  :per-pass}} opts
        fund-hz    (parse-fundamental fundamental)
        nav        (or navigation {:mode :manual})
        init-name  (:initial nav)
        init-idx   (or (when init-name (page-index-by-name pages init-name)) 0)
        first-page (nth pages (long init-idx) nil)
        init-cell  (when (= output-mode :cell)
                     (draw-cell (vec (:harmonics first-page [1 2 3 4]))
                                (long cell-len)
                                (:selection first-page {:mode :weighted})
                                (double (:gravity first-page 1.0))))]
    {:fundamental-hz (double fund-hz)
     :pages          (vec pages)
     :page-idx       (long init-idx)
     :output-mode    output-mode
     :octave-fold    (boolean octave-fold)
     :current-h      nil
     :h-idx          0
     :h-dir          1
     :cell           (or init-cell [])
     :cell-pos       0
     :cell-passes    0
     :drift-prob     (double drift-prob)
     :drift-rate     drift-rate
     :cell-len       (long cell-len)
     :navigation     nav
     :last-step      nil
     :opts           opts}))

;; ---------------------------------------------------------------------------
;; defbook macro
;; ---------------------------------------------------------------------------

(defmacro defbook
  "Define a named harmonic-series book sequencer and register in the ctrl tree.

  Creates a var bound to an atom holding the context, registered at [:book <name>].

  Parameters:
    :fundamental  — Hz (double) or note keyword e.g. :C2 :F#2 :A3 (default middle-C)
    :pages        — vector of page maps:
                    :name       — keyword page identifier
                    :harmonics  — vector of integer harmonic numbers e.g. [1 2 3 4]
                    :gravity    — gravity strength [0.0=uniform 1.0=full] (default 1.0)
                    :selection  — {:mode :weighted}   — gravity-weighted (default)
                                  {:mode :sequential :dir :up/:down}
                                  {:mode :proximate}  — prefer small harmonic steps
                                  {:mode :pendulum}   — sweep up then back down
                    :duration   — {:mode :beats :beats 4.0}         (default)
                                  {:mode :probability :p 0.1 :min-beats 1.0}
                                  {:mode :harmonic-modulated :base-beats 8.0}
    :navigation   — {:mode :manual}   (default)
                    {:initial :page-name :mode :manual}
                    {:mode :harmonic-threshold :up-at N :down-at N}
    :output-mode  — :free (default) or :cell
    :octave-fold  — fold harmonics into single octave (default true)
    :cell-len     — cell length for :cell mode (default 4)
    :drift-prob   — drift probability per evaluation (default 0.08)
    :drift-rate   — :per-pass (default) or :per-advance

  Operations:
    (next-step!  ctx-atom)         — advance and return step map
    (go-page!    ctx-atom :name)   — navigate to named page
    (reset-cell! ctx-atom)         — reseed cell from current page

  Example:
    (defbook resonant-space
      :fundamental :C2
      :pages [{:name :ground   :harmonics [1 2 3 4]   :gravity 1.0}
              {:name :fifth    :harmonics [3 5 6 9]   :gravity 0.7
               :selection {:mode :proximate}}
              {:name :outer    :harmonics [7 9 11 13] :gravity 0.3}]
      :navigation {:initial :ground :mode :manual})"
  [book-name & opts]
  (let [opts-map (apply hash-map opts)]
    `(do
       (def ~book-name (atom (make-book-context ~opts-map)))
       (register! ~(keyword (name book-name)) ~book-name)
       (ctrl/defnode! [:book ~(keyword (name book-name))]
                      :type :data :value @~book-name)
       ~book-name)))

;; ---------------------------------------------------------------------------
;; next-step! — advance state and return step map
;; ---------------------------------------------------------------------------

(defn next-step!
  "Advance the book context and return the next step map.

  The step map contains:
    :pitch/voct    — V/oct offset from fundamental (just intonation, not equal-tempered)
    :pitch/midi    — nearest equal-tempered MIDI note number
    :dur/beats     — duration in beats
    :gate/on?      — true (defbook does not generate rests)
    :book/harmonic — integer harmonic number N
    :book/gravity  — [0,1] field value (0=at fundamental, 1=maximally distant)"
  [ctx-atom]
  (:last-step
   (swap! ctx-atom
          (fn [{:keys [pages page-idx output-mode octave-fold fundamental-hz
                       current-h h-idx h-dir
                       cell cell-pos cell-passes
                       drift-prob drift-rate cell-len navigation] :as state}]
            (let [page       (nth pages (long page-idx) nil)
                  harmonics  (vec (:harmonics page [1]))
                  sel        (:selection page {:mode :weighted})
                  page-g     (double (:gravity page 1.0))
                  duration   (:duration page {:mode :beats :beats 4.0})

                  ;; Select harmonic N
                  [selected-h new-h-idx new-h-dir]
                  (if (= output-mode :cell)
                    (let [c-len (max 1 (count cell))
                          c-pos (mod (long cell-pos) c-len)]
                      [(nth cell c-pos (or (first harmonics) 1)) h-idx h-dir])
                    (select-harmonic harmonics sel current-h h-idx h-dir page-g))

                  n     (long selected-h)
                  voct  (harmonic->voct n octave-fold)
                  midi  (voct->nearest-midi fundamental-hz voct)
                  g-out (h/gravity-field (h/ratio-normalize [n 1]))
                  beats (compute-beats duration n)

                  ;; Cell: advance position, maybe drift at end-of-pass
                  c-len         (max 1 (count cell))
                  new-cell-pos  (if (= output-mode :cell)
                                  (mod (inc (long cell-pos)) c-len)
                                  cell-pos)
                  end-of-pass?  (and (= output-mode :cell) (zero? (long new-cell-pos)))
                  new-passes    (if end-of-pass? (inc (long cell-passes)) cell-passes)
                  drift?        (and (= output-mode :cell)
                                     (case drift-rate
                                       :per-pass    end-of-pass?
                                       :per-advance true
                                       false))
                  drifted-cell  (if drift?
                                  (drift-cell cell harmonics drift-prob page-g)
                                  cell)

                  ;; Page navigation
                  new-page-idx (auto-navigate page-idx pages navigation n)

                  ;; Reseed cell on page change
                  [final-cell final-cell-pos]
                  (if (and (= output-mode :cell) (not= new-page-idx (long page-idx)))
                    (let [np (nth pages (long new-page-idx) nil)
                          nh (vec (:harmonics np [1]))
                          ns (:selection np {:mode :weighted})
                          ng (double (:gravity np 1.0))]
                      [(draw-cell nh cell-len ns ng) 0])
                    [drifted-cell new-cell-pos])

                  step {:pitch/voct    voct
                        :pitch/midi    midi
                        :dur/beats     beats
                        :gate/on?      true
                        :book/harmonic n
                        :book/gravity  g-out}]
              (assoc state
                     :current-h   n
                     :h-idx       new-h-idx
                     :h-dir       new-h-dir
                     :cell        final-cell
                     :cell-pos    final-cell-pos
                     :cell-passes new-passes
                     :page-idx    new-page-idx
                     :last-step   step))))))

;; ---------------------------------------------------------------------------
;; Navigation and mutation
;; ---------------------------------------------------------------------------

(defn go-page!
  "Navigate to the named page immediately, resetting position.
  In :cell mode, reseeds the cell from the new page's harmonics."
  [ctx-atom page-name]
  (swap! ctx-atom
         (fn [{:keys [pages output-mode cell-len] :as state}]
           (if-let [idx (page-index-by-name pages page-name)]
             (let [page      (nth pages (long idx) nil)
                   harmonics (vec (:harmonics page [1]))
                   sel       (:selection page {:mode :weighted})
                   g         (double (:gravity page 1.0))
                   new-cell  (when (= output-mode :cell)
                               (draw-cell harmonics cell-len sel g))]
               (cond-> (assoc state
                               :page-idx  (long idx)
                               :h-idx     0
                               :h-dir     1
                               :current-h nil)
                 new-cell (assoc :cell new-cell :cell-pos 0 :cell-passes 0)))
             state)))
  nil)

(defn reset-cell!
  "Reseed the current cell from the current page's harmonics."
  [ctx-atom]
  (swap! ctx-atom
         (fn [{:keys [pages page-idx cell-len] :as state}]
           (let [page      (nth pages (long page-idx) nil)
                 harmonics (vec (:harmonics page [1]))
                 sel       (:selection page {:mode :weighted})
                 g         (double (:gravity page 1.0))]
             (assoc state
                    :cell        (draw-cell harmonics cell-len sel g)
                    :cell-pos    0
                    :cell-passes 0))))
  nil)

;; ---------------------------------------------------------------------------
;; IStepSequencer wrapper
;; ---------------------------------------------------------------------------

(defrecord BookSeq [ctx-atom vel])

(defn make-book-seq
  "Wrap a book context atom as an IStepSequencer for use with run-step! / deflive-loop.

  Options:
    :vel — default velocity 0–127 (default 100)

  Example:
    (defbook resonant-space :fundamental :C2 :pages [...])
    (deflive-loop :book-voice {}
      (run-step! (make-book-seq resonant-space)))"
  [ctx-atom & {:keys [vel] :or {vel 100}}]
  (->BookSeq ctx-atom (long vel)))

(extend-protocol sq/IStepSequencer
  BookSeq
  (next-event [bs]
    (let [step  (next-step! (:ctx-atom bs))
          beats (double (:dur/beats step 4.0))]
      {:event (when (:gate/on? step)
                (assoc step :mod/velocity (:vel bs)))
       :beats beats}))
  (seq-cycle-length [_] nil))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn book-names
  "Return a seq of registered book context names."
  []
  (or (keys @registry) '()))

(defn current-page
  "Return the current page map for `ctx-atom`."
  [ctx-atom]
  (let [{:keys [pages page-idx]} @ctx-atom]
    (nth pages (long page-idx) nil)))

(defn current-harmonic
  "Return the last drawn harmonic number N, or nil before the first step."
  [ctx-atom]
  (:current-h @ctx-atom))

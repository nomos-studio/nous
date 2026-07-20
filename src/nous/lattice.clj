; SPDX-License-Identifier: EPL-2.0
(ns nous.lattice
  "deflattice — 2D JI harmonic lattice sequencer.

  Generalizes defbook from the 1D overtone series to the full 2D JI pitch space.
  A lattice point [m n] represents the interval m/n above the fundamental.
  Navigation moves through the lattice under harmonic gravity toward a movable
  tonal center (attractor).

  ## Quick start

    (deflattice f-sharp-space
      :fundamental :F#2
      :region {:otonal-limit 11 :utonal-limit 7 :tenney-limit 6.5}
      :position [1 1]
      :nav-mode :gravity
      :step-bias :proximate
      :duration {:mode :beats :beats 4.0})

    (next-step! f-sharp-space)
    ;=> {:pitch/voct 0.0 :pitch/midi 42 :dur/beats 4.0
    ;    :lattice/point [1 1] :lattice/tenney 0.0
    ;    :lattice/otonality 0.5 :gate/on? true}

  ## Navigation modes

    :gravity     — step toward the attractor (lowest Tenney H closer to attractor)
    :expand      — step away from the attractor (highest Tenney H farther from attractor)
    :random-walk — proximity + otonality-biased walk through the lattice
    :otonal-step — step through the otonal chord (same denominator), wrapping
    :utonal-step — step through the utonal chord (same numerator), wrapping

  ## Moving the tonal center

    (set-attractor! f-sharp-space [3 2])   ; gravity now pulls toward the fifth
    (set-nav-mode!  f-sharp-space :expand) ; switch to outward movement
    (jump!          f-sharp-space [11 8])  ; teleport to the 11th harmonic

  See design-seed-harmonic-lattice.md for full vocabulary and musical context."
  (:require [ctrl-tree.core       :as ct]
            [nomos.maths.harmonic :as h]
            [nomos.maths.lattice  :as l]
            [nous.ctrl            :as ctrl]
            [nous.modulator       :as modulator]
            [nous.seq             :as sq]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register a lattice context atom under the given name keyword. Used by deflattice."
  [k ctx-atom]
  (swap! registry assoc k ctx-atom))

;; ---------------------------------------------------------------------------
;; Fundamental parsing
;; ---------------------------------------------------------------------------

(def ^:private NOTE-SEMITONES
  {"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11})

(defn- note-keyword->midi
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
  ^double [x]
  (cond
    (number? x)  (double x)
    (keyword? x) (if-let [midi (note-keyword->midi x)]
                   (* 440.0 (Math/pow 2.0 (/ (double (- (long midi) 69)) 12.0)))
                   261.626)
    :else 261.626))

;; ---------------------------------------------------------------------------
;; Otonality metric
;; ---------------------------------------------------------------------------

(defn- otonality-of
  "Otonality of lattice point [m n] ∈ [0,1].
  0.0 = fully utonal (n=1, all undertone), 1.0 = fully otonal (m=1 trivially...
  actually: 1.0 when m entirely dominates log(m×n), 0.0 when n dominates).
  Returns 0.5 at the origin [1 1] where log(m×n) = 0."
  [[m n]]
  (let [log-mn (Math/log (* (double m) (double n)))]
    (if (< log-mn 1.0e-10)
      0.5
      (/ (Math/log (double m)) log-mn))))

;; ---------------------------------------------------------------------------
;; Weighted sampling
;; ---------------------------------------------------------------------------

(defn- weighted-select
  "Sample one element from `coll` using `weight-fn` for non-negative weights."
  [coll weight-fn]
  (when (seq coll)
    (let [ws    (mapv weight-fn coll)
          total (double (reduce + ws))]
      (if (<= total 0.0)
        (first coll)
        (let [r (* (rand) total)]
          (loop [cs coll ws ws acc 0.0]
            (if (empty? cs)
              (last coll)
              (let [acc' (+ acc (double (first ws)))]
                (if (< r acc')
                  (first cs)
                  (recur (next cs) (next ws) acc'))))))))))

;; ---------------------------------------------------------------------------
;; Chord-directed navigation
;; ---------------------------------------------------------------------------

(defn- next-chord-step
  "Step to the next member of `chord` (sorted by Tenney H) after `position`,
  wrapping at the end. Returns `position` if not found or chord has < 2 members."
  [position chord]
  (let [n-chord (count chord)]
    (if (< n-chord 2)
      position
      (let [idx (some #(when (= position (nth chord %)) %) (range n-chord))]
        (if idx
          (nth chord (mod (inc (long idx)) n-chord))
          position)))))

(defn- next-otonal-step
  "Step to the next member of the otonal chord sharing `position`'s denominator."
  [[_ n :as position] region]
  (next-chord-step position (l/otonal-chord n region)))

(defn- next-utonal-step
  "Step to the next member of the utonal chord sharing `position`'s numerator."
  [[m _ :as position] region]
  (next-chord-step position (l/utonal-chord m region)))

;; ---------------------------------------------------------------------------
;; Step selection from candidates
;; ---------------------------------------------------------------------------

(defn- select-step
  "Select one candidate from a seq sorted nearest-first using `step-bias`."
  [candidates step-bias ^double otonality]
  (when (seq candidates)
    (case step-bias
      :proximate
      (first candidates)

      :otonal-prefer
      (weighted-select candidates #(max 0.01 (otonality-of %)))

      :utonal-prefer
      (weighted-select candidates #(max 0.01 (- 1.0 (otonality-of %))))

      ;; :random (default)
      (nth candidates (rand-int (count candidates))))))

;; ---------------------------------------------------------------------------
;; Navigation
;; ---------------------------------------------------------------------------

(defn- navigate
  "Compute the next lattice position from `position`."
  [position attractor nav-mode step-bias otonality region]
  (case nav-mode
    :gravity
    (let [cands (l/gravity-steps position attractor region)]
      (or (select-step cands step-bias otonality) position))

    :expand
    (let [cands (l/expansion-steps position attractor region)]
      (or (select-step cands step-bias otonality) position))

    :otonal-step
    (next-otonal-step position region)

    :utonal-step
    (next-utonal-step position region)

    ;; :random-walk (default)
    (let [all (remove #{position} (l/region-points region))]
      (or (weighted-select
            all
            (fn [p]
              (let [prox  (/ 1.0 (+ 1.0 (h/tenney-h p position)))
                    oto   (otonality-of p)
                    oto-w (+ (* otonality oto)
                             (* (- 1.0 otonality) (- 1.0 oto)))]
                (* prox (max 0.01 oto-w)))))
          position))))

;; ---------------------------------------------------------------------------
;; Duration
;; ---------------------------------------------------------------------------

(defn- compute-beats
  "Compute step duration in beats from duration spec and current Tenney H."
  ^double [duration ^double tenney]
  (cond
    (number? duration) (double duration)
    (map? duration)
    (case (:mode duration :beats)
      :probability
      (let [p     (double (:p duration 0.1))
            min-b (double (:min-beats duration 1.0))]
        (loop [b 0.0]
          (if (< (rand) p) (+ min-b b) (recur (+ b 1.0)))))
      :tenney-modulated
      (let [base  (double (:base-beats duration 8.0))
            scale (get duration :scale :inverse)]
        (case scale
          :inverse (/ base (max 0.5 tenney))
          :direct  (* base (/ tenney 6.5))
          base))
      ;; :beats (default)
      (double (:beats duration 4.0)))
    :else 4.0))

;; ---------------------------------------------------------------------------
;; Pitch output
;; ---------------------------------------------------------------------------

(defn- voct->nearest-midi
  ^long [^double fundamental-hz ^double voct-offset]
  (let [hz   (* fundamental-hz (Math/pow 2.0 voct-offset))
        midi (+ 69.0 (* 12.0 (/ (Math/log (/ hz 440.0)) (Math/log 2.0))))]
    (max 0 (min 127 (long (Math/round midi))))))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-lattice-context
  "Build a lattice context map from keyword options. Used by deflattice."
  [opts]
  (let [{:keys [fundamental region position attractor
                nav-mode step-bias otonality gravity-tau
                duration octave-fold]
         :or   {fundamental 261.626
                region      {}
                nav-mode    :gravity
                step-bias   :proximate
                otonality   0.5
                gravity-tau 8.0
                duration    {:mode :beats :beats 4.0}
                octave-fold true}} opts
        fund-hz   (parse-fundamental fundamental)
        lr        (l/lattice-region region)
        pts       (l/region-points lr)
        init-pos  (cond
                    (and (vector? position) (l/in-region? position lr)) position
                    (seq pts)                                            (first pts)
                    :else                                               [1 1])
        init-attr (or attractor [1 1])]
    {:fundamental-hz (double fund-hz)
     :region         lr
     :position       init-pos
     :attractor      init-attr
     :nav-mode       nav-mode
     :step-bias      step-bias
     :otonality      (double otonality)
     :gravity-tau    (double gravity-tau)
     :duration       duration
     :octave-fold    (boolean octave-fold)
     :voice-name     (:voice-name opts)
     :last-step      nil
     :opts           opts}))

;; ---------------------------------------------------------------------------
;; deflattice macro
;; ---------------------------------------------------------------------------

(defmacro deflattice
  "Define a named 2D JI lattice sequencer and register in the ctrl tree.

  Creates a var bound to an atom holding the context, registered at [:lattice <name>].

  Parameters:
    :fundamental  — Hz or note keyword e.g. :F#2 (default middle-C ~261 Hz)
    :region       — map passed to lattice-region:
                    :otonal-limit   max numerator m (default 11)
                    :utonal-limit   max denominator n (default 7)
                    :tenney-limit   max log₂(m×n) (default 6.5)
                    :octave-fold    reduce to [1,2) (default true)
                    :prime-lim      optional prime-limit filter
    :position     — starting [m n] (default: origin or first region point)
    :attractor    — tonal center [m n] for gravity (default [1 1])
    :nav-mode     — :gravity (default) | :expand | :random-walk
                    :otonal-step    | :utonal-step
    :step-bias    — :proximate (default) | :random
                    :otonal-prefer  | :utonal-prefer
    :otonality    — [0,1] bias for :random-walk (0=utonal, 1=otonal, default 0.5)
    :gravity-tau  — gravity time constant in beats (default 8.0)
    :duration     — number (beats) or map:
                    {:mode :beats :beats 4.0}               (default)
                    {:mode :probability :p 0.1 :min-beats 1.0}
                    {:mode :tenney-modulated :base-beats 8.0 :scale :inverse/:direct}
    :octave-fold  — fold ratio into single octave (default true)
    :voice-name   — keyword; if set, publishes pitch and motion direction to
                    [:harmony :voice-pitch voice-name] and
                    [:harmony :voice-motion voice-name] on each step.
                    Required for defensemble inter-voice monitoring.

  Step map keys:
    :pitch/voct        — V/oct from fundamental (JI, not ET)
    :pitch/midi        — nearest MIDI note
    :dur/beats         — duration in beats
    :gate/on?          — always true
    :lattice/point     — [m n] current position
    :lattice/tenney    — Tenney H of position (log₂(m×n))
    :lattice/otonality — [0,1] otonal character
    :lattice/m         — numerator (overtone index)
    :lattice/n         — denominator (undertone index)

  Operations:
    (next-step!      ctx-atom)       — advance, return step map
    (set-attractor!  ctx-atom [m n]) — move the tonal center
    (set-nav-mode!   ctx-atom mode)  — change navigation mode live
    (jump!           ctx-atom [m n]) — teleport to a specific lattice point"
  [lattice-name & opts]
  (let [opts-map (apply hash-map opts)]
    `(do
       (def ~lattice-name (atom (make-lattice-context ~opts-map)))
       (register! ~(keyword (name lattice-name)) ~lattice-name)
       (ctrl/defnode! [:lattice ~(keyword (name lattice-name))]
                      :type :data :value @~lattice-name)
       ~lattice-name)))

;; ---------------------------------------------------------------------------
;; next-step! — advance state and return step map
;; ---------------------------------------------------------------------------

(defn next-step!
  "Advance the lattice context and return the next step map.

  Outputs the current position, then navigates to the next position for the
  following call. The starting position from deflattice is thus the first output.

  Step map:
    :pitch/voct        — V/oct offset from fundamental (JI)
    :pitch/midi        — nearest MIDI note number
    :dur/beats         — duration in beats
    :gate/on?          — always true
    :lattice/point     — [m n] position output this step
    :lattice/tenney    — Tenney H of position (log₂(m×n))
    :lattice/otonality — [0,1]: 0=fully utonal, 1=fully otonal
    :lattice/m         — overtone index m
    :lattice/n         — undertone index n"
  [ctx-atom]
  (let [prev-midi  (get-in @ctx-atom [:last-step :pitch/midi])
        step       (:last-step
                    (swap! ctx-atom
                           (fn [{:keys [fundamental-hz region position attractor
                                        nav-mode step-bias otonality
                                        duration octave-fold] :as state}]
                             (let [[m n]      position
                                   normalized (if octave-fold
                                                (h/ratio-normalize position)
                                                position)
                                   voct       (h/ratio->voct normalized)
                                   midi       (voct->nearest-midi fundamental-hz voct)
                                   tenney     (h/tenney-h position)
                                   oto        (otonality-of position)
                                   beats      (compute-beats duration tenney)

                                   step {:pitch/voct        voct
                                         :pitch/midi        midi
                                         :dur/beats         beats
                                         :gate/on?          true
                                         :lattice/point     position
                                         :lattice/tenney    tenney
                                         :lattice/otonality oto
                                         :lattice/m         m
                                         :lattice/n         n}

                                   next-pos (navigate position attractor nav-mode step-bias
                                                       otonality region)]
                               (assoc state
                                      :position  next-pos
                                      :last-step step)))))]
    (when-let [vn (:voice-name @ctx-atom)]
      (let [curr-midi (:pitch/midi step)
            dir       (cond (nil? prev-midi)                      0
                            (> (long curr-midi) (long prev-midi)) 1
                            (< (long curr-midi) (long prev-midi)) -1
                            :else                                  0)]
        (try
          (ct/ctrl-write! [:harmony :voice-pitch    vn] (long curr-midi))
          (ct/ctrl-write! [:harmony :voice-motion   vn] (long dir))
          (ct/ctrl-write! [:harmony :voice-duration vn] (double (:dur/beats step)))
          (catch Exception _ nil))))
    step))

;; ---------------------------------------------------------------------------
;; Navigation API
;; ---------------------------------------------------------------------------

(defn set-attractor!
  "Move the tonal center to `point`. Future gravity steps pull toward `point`."
  [ctx-atom point]
  (swap! ctx-atom assoc :attractor point)
  nil)

(defn set-nav-mode!
  "Change the navigation mode. Valid modes: :gravity :expand :random-walk
  :otonal-step :utonal-step. Takes effect on the next call to next-step!."
  [ctx-atom mode]
  (swap! ctx-atom assoc :nav-mode mode)
  nil)

(defn jump!
  "Jump immediately to `point` if it is in the region; otherwise no-op."
  [ctx-atom point]
  (swap! ctx-atom
         (fn [{:keys [region] :as state}]
           (if (l/in-region? point region)
             (assoc state :position point)
             state)))
  nil)

;; ---------------------------------------------------------------------------
;; IStepSequencer wrapper
;; ---------------------------------------------------------------------------

(defrecord LatticeSeq [ctx-atom vel mods-compiled tenney-limit])

(defn make-lattice-seq
  "Wrap a lattice context atom as an IStepSequencer for use with run-step! / deflive-loop.

  Options:
    :vel          — default velocity 0–127 (default 100)
    :mods         — portable modulator map (same vocabulary as make-motif-state :mods).
                    Phase is derived from Tenney H normalized to the region's tenney-limit:
                    [1 1] (tonic) → 0.0, maximum harmonic complexity → 1.0.
                    Use to modulate velocity, CC, or any parameter over harmonic tension.

  Example:
    (deflattice f-sharp-space :fundamental :F#2 :region {:tenney-limit 6.5})
    (deflive-loop :lattice-voice {}
      (run-step! (make-lattice-seq f-sharp-space
                                   :mods {:mod/velocity {:modulator/type :lfo/sine}})))"
  [ctx-atom & {:keys [vel mods] :or {vel 100}}]
  (let [tlimit (get-in @ctx-atom [:opts :region :tenney-limit] 6.5)]
    (->LatticeSeq ctx-atom (long vel) (modulator/compile-mods mods) (double tlimit))))

(extend-protocol sq/IStepSequencer
  LatticeSeq
  (next-event [ls]
    (let [step      (next-step! (:ctx-atom ls))
          beats     (double (:dur/beats step 4.0))
          mod-phase (min 1.0 (/ (double (:lattice/tenney step 0.0))
                                (:tenney-limit ls)))
          event     (when (:gate/on? step)
                      (cond-> (assoc step :mod/velocity (:vel ls))
                        (:mods-compiled ls)
                        (merge (modulator/sample-mods (:mods-compiled ls) mod-phase))))]
      {:event event :beats beats}))
  (seq-cycle-length [_] nil))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn lattice-names
  "Return a seq of registered lattice context names."
  []
  (or (keys @registry) '()))

(defn current-position
  "Return the current lattice position [m n] for `ctx-atom`."
  [ctx-atom]
  (:position @ctx-atom))

(defn current-attractor
  "Return the current attractor [m n] for `ctx-atom`."
  [ctx-atom]
  (:attractor @ctx-atom))

(defn lattice-region
  "Return the LatticeRegion for `ctx-atom`."
  [ctx-atom]
  (:region @ctx-atom))

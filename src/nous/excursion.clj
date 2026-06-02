; SPDX-License-Identifier: EPL-2.0
(ns nous.excursion
  "defexcursion — five-phase harmonic lattice excursion arc.

  The excursion arc is the most characteristic Partch compositional shape:
  a five-phase structure that gives complex intervals their weight by framing
  them with established consonance.

    Phase 1: GROUND     — establish the tonic region (low Tenney distance)
    Phase 2: DEPARTURE  — begin moving outward, increasing Tenney distance
    Phase 3: EXCURSION  — reach the far territory (alien frontier)
    Phase 4: RETURN     — follow gravity back toward origin
    Phase 5: RESOLUTION — arrive at the tonic with perceptible weight

  ## Quick start

    (defexcursion f-sharp-arc
      :fundamental :F#2
      :region {:otonal-limit 11 :utonal-limit 7 :tenney-limit 6.5}
      :arc {:ground     {:steps 8}
            :departure  {:steps 6  :target-tenney 5.0}
            :excursion  {:steps 4}
            :return     {:steps 8  :target-tenney 1.5}
            :resolution {:steps 2  :approach :septimal :to [1 1]}}
      :repeat true)

    (next-step! f-sharp-arc)
    ;=> {:pitch/voct ...     :pitch/midi 42
    ;    :lattice/point [1 1] :lattice/tenney 0.0
    ;    :excursion/phase :ground :excursion/step 0}

  ## Output keys

    :pitch/voct          — V/oct from fundamental (JI, not ET)
    :pitch/midi          — nearest MIDI note
    :dur/beats           — duration in beats
    :gate/on?            — always true
    :lattice/point       — [m n] current position
    :lattice/tenney      — Tenney H of position
    :lattice/otonality   — [0,1] otonal character
    :lattice/m           — overtone index
    :lattice/n           — undertone index
    :excursion/phase     — :ground :departure :excursion :return :resolution
    :excursion/step      — step within current phase (0-indexed)

  ## Phase arc config

    :ground     {:steps N    :positions [[m n]...]   ; optional fixed cycle
                             :mode :gravity}          ; default
    :departure  {:steps N    :target-tenney X         ; exit early if H ≥ X
                             :mode :expand}
    :excursion  {:steps N    :mode :random-walk
                             :otonality 0.5}
    :return     {:steps N    :target-tenney X         ; exit early if H ≤ X (default 1.5)
                             :mode :gravity}
    :resolution {:steps N    :approach :direct        ; or :supertonic :septimal :overtone
                             :to [m n]}               ; target (default [1 1])

  See design-seed-partch-navigation.md for the full musical grammar."
  (:require [nomos.maths.harmonic :as h]
            [nomos.maths.lattice  :as l]
            [nous.ctrl            :as ctrl]
            [nous.seq             :as sq]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register an excursion context atom under the given name keyword."
  [k ctx-atom]
  (swap! registry assoc k ctx-atom))

;; ---------------------------------------------------------------------------
;; Fundamental parsing
;; ---------------------------------------------------------------------------

(def ^:private NOTE-SEMITONES
  {"C" 0 "D" 2 "E" 4 "F" 5 "G" 7 "A" 9 "B" 11})

(defn- note-keyword->midi [kw]
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

(defn- parse-fundamental ^double [x]
  (cond
    (number? x)  (double x)
    (keyword? x) (if-let [midi (note-keyword->midi x)]
                   (* 440.0 (Math/pow 2.0 (/ (double (- (long midi) 69)) 12.0)))
                   261.626)
    :else 261.626))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- otonality-of [[m n]]
  (let [log-mn (Math/log (* (double m) (double n)))]
    (if (< log-mn 1.0e-10) 0.5
        (/ (Math/log (double m)) log-mn))))

(defn- weighted-select [coll weight-fn]
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

(defn- voct->nearest-midi ^long [^double fund-hz ^double voct]
  (let [hz   (* fund-hz (Math/pow 2.0 voct))
        midi (+ 69.0 (* 12.0 (/ (Math/log (/ hz 440.0)) (Math/log 2.0))))]
    (max 0 (min 127 (long (Math/round midi))))))

;; ---------------------------------------------------------------------------
;; Phase sequence
;; ---------------------------------------------------------------------------

(def ^:private PHASE-ORDER
  [:ground :departure :excursion :return :resolution])

(def ^:private NEXT-PHASE
  {:ground    :departure
   :departure :excursion
   :excursion :return
   :return    :resolution
   :resolution nil})

;; ---------------------------------------------------------------------------
;; Cadential approach points
;; ---------------------------------------------------------------------------

(def ^:private APPROACH-RATIOS
  {:supertonic [9 8]   ; just major second → unison
   :septimal   [7 6]   ; septimal minor third → unison
   :overtone   [7 4]   ; harmonic seventh → unison (dramatic)
   :diatonic   [16 15]}) ; just diatonic semitone → unison

(defn- approach-point-for
  "Return the approach ratio if it is in `region`, else nil."
  [approach region]
  (when-let [pt (get APPROACH-RATIOS approach)]
    (when (l/in-region? pt region) pt)))

;; ---------------------------------------------------------------------------
;; Phase-level navigation
;; ---------------------------------------------------------------------------

(defn- gravity-step
  "Move one step toward `attractor` (proximate gravity)."
  [position attractor region]
  (or (first (l/gravity-steps position attractor region)) position))

(defn- expand-step
  "Move one step away from `attractor`, weighted by otonality."
  [position attractor region otonality]
  (let [cands (l/expansion-steps position attractor region)]
    (or (weighted-select cands #(max 0.01 (otonality-of %)))
        position)))

(defn- random-walk-step
  "Proximity- and otonality-biased random step."
  [position region otonality]
  (let [all (remove #{position} (l/region-points region))]
    (or (weighted-select all
                         (fn [p]
                           (let [prox  (/ 1.0 (+ 1.0 (h/tenney-h p position)))
                                 oto   (otonality-of p)
                                 oto-w (+ (* (double otonality) oto)
                                          (* (- 1.0 (double otonality)) (- 1.0 oto)))]
                             (* prox (max 0.01 oto-w)))))
        position)))

(defn- ground-cycle-step
  "For ground phase with a :positions list, advance to the next in the cycle."
  [current positions]
  (let [n (count positions)]
    (if (zero? n)
      current
      (let [idx (some #(when (= current (nth positions %)) %) (range n))]
        (if idx
          (nth positions (mod (inc (long idx)) n))
          (first positions))))))

(defn- resolution-next-pos
  "Navigation logic for the resolution phase.
  On all but the last step: move to the approach point (if any).
  On the last step: jump to :to."
  [position resolution-cfg region phase-step]
  (let [approach (:approach resolution-cfg :direct)
        to       (get resolution-cfg :to [1 1])
        steps    (long (:steps resolution-cfg 1))
        ap       (when (not= approach :direct)
                   (approach-point-for approach region))]
    (cond
      ;; Last step or direct: jump to target
      (or (= approach :direct) (>= (inc phase-step) steps)) to
      ;; Already at approach point: jump to target
      (= position ap) to
      ;; Move toward approach point
      :else (or ap to))))

(defn- phase-navigate
  "Compute the next position given the current phase config."
  [position attractor phase arc otonality region phase-step]
  (let [cfg (get arc phase {})]
    (case phase
      :ground
      (let [positions (seq (:positions cfg))]
        (if positions
          (ground-cycle-step position (vec positions))
          (gravity-step position attractor region)))

      :departure
      (expand-step position attractor region
                   (double (:otonality cfg otonality)))

      :excursion
      (let [mode (get cfg :mode :random-walk)
            oto  (double (:otonality cfg otonality))]
        (case mode
          :gravity     (gravity-step position attractor region)
          :expand      (expand-step position attractor region oto)
          :otonal-step (let [chord (l/otonal-chord (second position) region)]
                         (or (let [n (count chord)]
                               (when (>= n 2)
                                 (let [idx (some #(when (= position (nth chord %)) %) (range n))]
                                   (when idx (nth chord (mod (inc (long idx)) n))))))
                             position))
          :utonal-step (let [chord (l/utonal-chord (first position) region)]
                         (or (let [n (count chord)]
                               (when (>= n 2)
                                 (let [idx (some #(when (= position (nth chord %)) %) (range n))]
                                   (when idx (nth chord (mod (inc (long idx)) n))))))
                             position))
          ;; :random-walk (default)
          (random-walk-step position region oto)))

      :return
      (gravity-step position attractor region)

      :resolution
      (resolution-next-pos position cfg region phase-step)

      position)))

;; ---------------------------------------------------------------------------
;; Phase transitions
;; ---------------------------------------------------------------------------

(defn- should-advance?
  "Return true if the current phase should transition to the next."
  [phase phase-step position arc]
  (let [cfg   (get arc phase {})
        steps (long (:steps cfg 8))]
    (case phase
      :departure
      (let [target-h (double (:target-tenney cfg Double/MAX_VALUE))]
        (or (>= phase-step (dec steps))
            (>= (h/tenney-h position) target-h)))
      :return
      (let [target-h (double (:target-tenney cfg 1.5))]
        (or (>= phase-step (dec steps))
            (<= (h/tenney-h position) target-h)))
      ;; ground, excursion, resolution: step count only
      (>= phase-step (dec steps)))))

(defn- advance-phase
  "Return the next phase keyword, or nil if arc is complete."
  [phase repeat?]
  (if (and (= phase :resolution) repeat?)
    :ground
    (get NEXT-PHASE phase)))

;; ---------------------------------------------------------------------------
;; Duration
;; ---------------------------------------------------------------------------

(defn- compute-beats ^double [duration ^double tenney]
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
      (double (:beats duration 4.0)))
    :else 4.0))

;; ---------------------------------------------------------------------------
;; Arc defaults
;; ---------------------------------------------------------------------------

(defn- normalize-arc
  "Merge user arc config over defaults. Converts :duration → :steps for
  backwards compatibility with the design seed vocabulary."
  [user-arc]
  (let [defaults {:ground     {:steps 8  :mode :gravity}
                  :departure  {:steps 6  :mode :expand   :target-tenney 5.0}
                  :excursion  {:steps 4  :mode :random-walk}
                  :return     {:steps 8  :mode :gravity   :target-tenney 1.5}
                  :resolution {:steps 1  :approach :direct :to [1 1]}}
        rename   (fn [m] (if (and (contains? m :duration) (not (contains? m :steps)))
                           (-> m (assoc :steps (:duration m)) (dissoc :duration))
                           m))]
    (reduce-kv (fn [acc phase default-cfg]
                 (assoc acc phase (merge default-cfg (rename (get user-arc phase {})))))
               {}
               defaults)))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-excursion-context
  "Build an excursion context map from keyword options. Used by defexcursion."
  [opts]
  (let [{:keys [fundamental region position attractor arc
                octave-fold duration otonality repeat]
         :or   {fundamental 261.626
                region      {}
                octave-fold true
                duration    {:mode :beats :beats 4.0}
                otonality   0.5
                repeat      true}} opts
        fund-hz   (parse-fundamental fundamental)
        lr        (l/lattice-region region)
        pts       (l/region-points lr)
        init-pos  (cond
                    (and (vector? position) (l/in-region? position lr)) position
                    (seq pts)                                            (first pts)
                    :else                                               [1 1])
        init-attr (or attractor [1 1])
        norm-arc  (normalize-arc (or arc {}))]
    {:fundamental-hz (double fund-hz)
     :region         lr
     :position       init-pos
     :attractor      init-attr
     :octave-fold    (boolean octave-fold)
     :duration       duration
     :otonality      (double otonality)
     :repeat         (boolean repeat)
     :arc            norm-arc
     :phase          :ground
     :phase-step     0
     :last-step      nil
     :voice-name     (:voice-name opts)
     :opts           opts}))

;; ---------------------------------------------------------------------------
;; defexcursion macro
;; ---------------------------------------------------------------------------

(defmacro defexcursion
  "Define a named five-phase harmonic lattice excursion arc.

  Creates a var bound to an atom holding the context, registered at
  [:excursion <name>].

  Parameters:
    :fundamental  — Hz or note keyword e.g. :F#2 (default middle-C)
    :region       — passed to lattice-region: :otonal-limit :utonal-limit
                    :tenney-limit :octave-fold :prime-lim
    :position     — starting [m n] (default: first region point)
    :attractor    — gravity target [m n] (default [1 1])
    :octave-fold  — fold into single octave (default true)
    :duration     — per-step duration: number, {:mode :beats :beats 4.0},
                    {:mode :probability ...}, {:mode :tenney-modulated ...}
    :otonality    — [0,1] default otonality bias for random-walk (default 0.5)
    :repeat       — cycle back to :ground after resolution (default true)
    :voice-name   — keyword; if set, publishes pitch and motion to
                    [:harmony :voice-pitch voice-name] and
                    [:harmony :voice-motion voice-name] on each step.
                    Required for defensemble inter-voice monitoring.
    :arc          — five-phase config map (keys: :ground :departure :excursion
                    :return :resolution). Each phase:
                      :steps          — steps in phase (alias :duration accepted)
                      :mode           — nav mode (phase-specific default)
                      :target-tenney  — H threshold for early phase exit
                                        (:departure exits high, :return exits low)
                    Ground extras:
                      :positions      — [[m n]...] cycle in order (skips nav)
                    Resolution extras:
                      :approach       — :direct (default) :supertonic :septimal
                                        :overtone :diatonic
                      :to             — target [m n] (default [1 1])

  Operations:
    (next-step!       ctx-atom) — advance and return step map
    (skip-to-phase!   ctx-atom phase) — jump to a named phase immediately
    (restart!         ctx-atom) — reset to ground phase, step 0"
  [excursion-name & opts]
  (let [opts-map (apply hash-map opts)]
    `(do
       (def ~excursion-name (atom (make-excursion-context ~opts-map)))
       (register! ~(keyword (name excursion-name)) ~excursion-name)
       (ctrl/defnode! [:excursion ~(keyword (name excursion-name))]
                      :type :data :value @~excursion-name)
       ~excursion-name)))

;; ---------------------------------------------------------------------------
;; next-step!
;; ---------------------------------------------------------------------------

(defn next-step!
  "Advance the excursion context and return the next step map.

  Outputs current position (which starts at the configured :position), then
  navigates to the next position according to the current phase's navigation
  rules. Phase transitions happen automatically when step counts or Tenney
  thresholds are reached.

  Step map keys in addition to the standard lattice set:
    :excursion/phase  — current phase keyword
    :excursion/step   — step index within the phase (0-indexed)"
  [ctx-atom]
  (let [prev-midi (get-in @ctx-atom [:last-step :pitch/midi])
        step      (:last-step
                   (swap! ctx-atom
                          (fn [{:keys [fundamental-hz region position attractor octave-fold
                                       duration otonality arc phase phase-step repeat] :as state}]
                            (let [;; Output current position
                                  [m n]      position
                                  normalized (if octave-fold (h/ratio-normalize position) position)
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
                                        :lattice/n         n
                                        :excursion/phase   phase
                                        :excursion/step    phase-step}

                                  ;; Navigate to next position
                                  next-pos (phase-navigate position attractor phase arc
                                                           otonality region phase-step)

                                  ;; Check phase transition (based on current position before step)
                                  advance?   (should-advance? phase phase-step position arc)
                                  next-phase (if advance? (advance-phase phase repeat) phase)
                                  next-pstep (if advance? 0 (inc phase-step))]
                              (assoc state
                                     :position   next-pos
                                     :phase      (or next-phase phase)
                                     :phase-step (long next-pstep)
                                     :last-step  step)))))]
    (when-let [vn (:voice-name @ctx-atom)]
      (let [curr-midi (:pitch/midi step)
            dir       (cond (nil? prev-midi)                      0
                            (> (long curr-midi) (long prev-midi)) 1
                            (< (long curr-midi) (long prev-midi)) -1
                            :else                                  0)]
        (try
          (ctrl/set! [:harmony :voice-pitch vn] (long curr-midi))
          (ctrl/set! [:harmony :voice-motion vn] (long dir))
          (catch Exception _ nil))))
    step))

;; ---------------------------------------------------------------------------
;; Live control
;; ---------------------------------------------------------------------------

(defn skip-to-phase!
  "Jump to `phase` immediately, resetting phase-step to 0.
  Takes effect on the next call to next-step!.
  Valid phases: :ground :departure :excursion :return :resolution"
  [ctx-atom phase]
  (swap! ctx-atom assoc :phase phase :phase-step 0)
  nil)

(defn restart!
  "Reset to :ground phase, step 0. Position is not reset."
  [ctx-atom]
  (swap! ctx-atom assoc :phase :ground :phase-step 0)
  nil)

;; ---------------------------------------------------------------------------
;; IStepSequencer wrapper
;; ---------------------------------------------------------------------------

(defrecord ExcursionSeq [ctx-atom vel])

(defn make-excursion-seq
  "Wrap an excursion context atom as an IStepSequencer for use with run-step!.

  Options:
    :vel — default velocity 0–127 (default 100)

  Example:
    (defexcursion arc :fundamental :F#2 :region {...} :arc {...})
    (deflive-loop :arc-voice {}
      (run-step! (make-excursion-seq arc)))"
  [ctx-atom & {:keys [vel] :or {vel 100}}]
  (->ExcursionSeq ctx-atom (long vel)))

(extend-protocol sq/IStepSequencer
  ExcursionSeq
  (next-event [es]
    (let [step  (next-step! (:ctx-atom es))
          beats (double (:dur/beats step 4.0))]
      {:event (when (:gate/on? step)
                (assoc step :mod/velocity (:vel es)))
       :beats beats}))
  (seq-cycle-length [_] nil))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn excursion-names
  "Return a seq of registered excursion context names."
  []
  (or (keys @registry) '()))

(defn current-phase
  "Return the current phase keyword for `ctx-atom`."
  [ctx-atom]
  (:phase @ctx-atom))

(defn phase-progress
  "Return a map describing progress within the current phase.
    :phase  — current phase keyword
    :step   — current step index (0-indexed)
    :steps  — total steps configured for this phase
    :ratio  — [0,1] fraction of the phase completed"
  [ctx-atom]
  (let [{:keys [phase phase-step arc]} @ctx-atom
        cfg   (get arc phase {})
        steps (long (:steps cfg 8))]
    {:phase phase
     :step  phase-step
     :steps steps
     :ratio (/ (double phase-step) (max 1.0 (double steps)))}))

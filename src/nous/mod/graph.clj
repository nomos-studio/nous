; SPDX-License-Identifier: EPL-2.0
(ns nous.mod.graph
  "Control-rate graph modulator DSL for nomos-rt.

  Builds EDN s-expression graphs dispatched to the RT modulator engine via
  the kairos IPC channel.  All node constructor functions are pure — they
  return EDN-serialisable Clojure vectors with no side effects.

  Intended usage pattern — require with an alias:

    (require '[nous.mod.graph :as g])

  ## Composing a graph

  The Clojure threading macro composes naturally with unary operators:

    (g/start! :filter-lfo
      (-> (g/param :rate)
          g/phasor
          g/sin
          (g/scale -1.0 1.0))
      {:rate 0.5})

  Cross-modulator reference — read another modulator's output:

    (g/start! :stepped
      (g/sample-hold (g/phasor 0.1) (g/mod-out :clock :gate)))

  Multi-output — {:cv expr :gate expr} map:

    (g/start! :lfo+gate
      {:cv   (-> (g/param :rate) g/phasor g/sin)
       :gate (-> (g/param :rate) g/phasor (g/threshold 0.5))}
      {:rate 2.0})

  Self-referential feedback (one-tick delay):

    (g/start! :accumulator
      (g/clamp (g/add (g/mod-out :accumulator :cv) 0.01) 0.0 1.0))

  ## Updating params

    (g/update! :filter-lfo :rate 2.0)

  ## Promotion path

  When a graph pattern becomes canonical enough to warrant a C++ primitive,
  it is implemented in nomos-rt as a named modulator type (e.g. :slope,
  :fractal) and removed from user-space graphs.  The DSL vocabulary
  intentionally mirrors the C++ node kinds so that promotion is transparent
  to callers."
  (:refer-clojure :exclude [abs comparator])
  (:require [nous.kairos :as kairos]))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(defn phasor
  "Phase accumulator [0, 1) advancing at `rate` Hz.
  `rate` may be a literal number or any node expression."
  ([rate] [:phasor rate])
  ([rate initial-phase] [:phasor rate initial-phase]))

(defn param
  "Named parameter — reads from the modulator's live :params map.
  `k` — keyword or string matching a key in the :params map passed to start!.

  Example: (param :rate)"
  [k]
  [:param k])

(defn mod-out
  "Read a field from another named modulator's most-recently-recorded output.
  Source modulator must be registered in the same engine.  If the source has
  not yet produced output (first tick), returns 0.

  `id`    — modulator id keyword (e.g. :clock)
  `field` — output field keyword: :cv (default), :aux, :gate, :gate2

  Example: (mod-out :clock :gate)"
  ([id]       [:mod-out id :cv])
  ([id field] [:mod-out id field]))

(defn beat
  "Current Link beat position as a float."
  []
  [:beat])

(defn beat-phase
  "Phase derived from Link beat position: fmod(beat / period-beats, 1.0).
  Stateless — no accumulator, zero drift under BPM changes.
  Prefer over (phasor hz-rate) when the period should track musical time.

  `period-beats` — cycle length in beats (literal or any node expression).

  Examples:
    (beat-phase 4)              ; one cycle per bar in 4/4
    (beat-phase (param :period)) ; voltage-controlled period in beats"
  [period-beats]
  [:beat-phase period-beats])

;; ---------------------------------------------------------------------------
;; Periodic shapes — expect a [0, 1) phase input from phasor
;; ---------------------------------------------------------------------------

(defn sin
  "Sine wave from phase input.  Output range [-1, 1]."
  [phase] [:sin phase])

(defn cos
  "Cosine wave from phase input.  Output range [-1, 1]."
  [phase] [:cos phase])

(defn tri
  "Bipolar triangle wave from phase input.  Output range [-1, 1]:
  -1 at phase 0, +1 at phase 0.5, -1 at phase 1."
  [phase] [:tri phase])

(defn saw
  "Upward sawtooth from phase input mapped to [-1, 1]."
  [phase] [:saw phase])

(defn square
  "Pulse wave from phase input.  Output +1 when phase < width, else -1.
  `width` defaults to 0.5 (50% duty cycle); may be a node expression."
  ([phase]       [:square phase 0.5])
  ([phase width] [:square phase width]))

;; ---------------------------------------------------------------------------
;; Math
;; ---------------------------------------------------------------------------

(defn scale
  "Linear remap: lo + x*(hi - lo).  Maps [0,1] input to [lo, hi].
  `lo` and `hi` may be literal numbers or node expressions."
  [x lo hi] [:scale x lo hi])

(defn clamp
  "Clamp x to [lo, hi].  lo and hi may be node expressions."
  [x lo hi] [:clamp x lo hi])

(defn add
  "Sum of two node expressions."
  [a b] [:add a b])

(defn mul
  "Product of two node expressions."
  [a b] [:mul a b])

(defn neg
  "Negate: -x."
  [x] [:neg x])

(defn abs
  "Absolute value: |x|."
  [x] [:abs x])

(defn mix
  "Linear interpolation: a*(1-t) + b*t.
  `t` in [0, 1]; may be a node expression."
  [a b t] [:mix a b t])

;; ---------------------------------------------------------------------------
;; Dynamics
;; ---------------------------------------------------------------------------

(defn slew
  "Slew-rate limiter.  Output follows `signal` at most `rise` seconds per unit
  rising and `fall` seconds per unit falling.  Time constants may be node
  expressions, enabling voltage-controlled slew.

  Examples:
    (slew (param :target) 0.1 0.2)           ; asymmetric
    (slew (param :target) (param :time))      ; symmetric, voltage-controlled"
  ([signal time]       [:slew signal time time])
  ([signal rise fall]  [:slew signal rise fall]))

(defn sample-hold
  "Sample and hold.  Captures `signal` on the rising edge of `gate` (transition
  from ≤ 0.5 to > 0.5).  Holds the captured value until the next trigger.

  Example:
    (sample-hold noise-source clock-gate)"
  [signal gate] [:sample-hold signal gate])

;; ---------------------------------------------------------------------------
;; Gate / logic
;; ---------------------------------------------------------------------------

(defn threshold
  "Gate output: 1.0 when x > level, else 0.0.
  `level` defaults to 0.5; may be a node expression.

  Example: (threshold (phasor 1.0) 0.5)  ; 50% duty-cycle gate from phasor"
  ([x]       [:threshold x 0.5])
  ([x level] [:threshold x level]))

(defn comparator
  "Gate output: 1.0 when a > b, else 0.0.

  Example: (comparator (mod-out :lfo-a :cv) (mod-out :lfo-b :cv))"
  [a b] [:comparator a b])

;; ---------------------------------------------------------------------------
;; Graph lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Start a graph modulator in the connected kairos/aion process.

  id     — keyword naming this modulator (e.g. :filter-lfo)
  graph  — graph expression (a node constructor call) or a multi-output map
           {:cv expr :gate expr :aux expr :gate2 expr}
  params — optional map of named params {:keyword value ...}.
           All values are live-updatable via update!.

  Returns nil.

  Examples:

    ;; Single-output: sin LFO at a parameterised rate
    (g/start! :filter-lfo
      (-> (g/param :rate)
          g/phasor
          g/sin
          (g/scale -1.0 1.0))
      {:rate 0.5})

    ;; Multi-output: CV + gate from the same phasor
    (g/start! :clock
      {:cv   (-> (g/param :rate) g/phasor g/saw)
       :gate (-> (g/param :rate) g/phasor (g/threshold 0.6))}
      {:rate 2.0})

    ;; Cross-modulator: stepped random driven by an external clock gate
    (g/start! :stepped
      (g/sample-hold (g/mul (g/phasor 0.03) 2.0)
                     (g/mod-out :clock :gate)))

    ;; Self-referential feedback accumulator
    (g/start! :ramp-acc
      (g/clamp (g/add (g/mod-out :ramp-acc :cv) 0.005) 0.0 1.0))"
  ([id graph]
   (start! id graph nil))
  ([id graph params]
   (kairos/start-modulator! id :graph
     (cond-> {:graph graph}
       (seq params) (assoc :params params)))))

(defn stop!
  "Stop and remove the named graph modulator.

  Example: (g/stop! :filter-lfo)"
  [id]
  (kairos/stop-modulator! id))

(defn update!
  "Update a named parameter on a running graph modulator.

  id    — modulator id keyword
  key   — parameter name keyword or string (must match a key in :params)
  value — new numeric value

  Example: (g/update! :filter-lfo :rate 2.0)"
  [id key value]
  (kairos/update-modulator! id key value))

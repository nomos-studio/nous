; SPDX-License-Identifier: EPL-2.0
(ns nous.modulator
  "Portable EDN data model for modulators across nous / nomos-rt / alembic tiers.

  A modulator is a plain map with a required `:modulator/type` dispatch key and
  type-specific keys namespaced under the type.  All stateless modulators operate
  on normalized coordinates: domain [0,1], range [0,1].  Scaling to physical
  parameter ranges is the caller's responsibility at each tier.

  ## Canonical forms

  `:spline/catmull-rom`
    {:modulator/type  :spline/catmull-rom
     :modulator/loop  :loop
     :spline/knots    [[0.0 0.0] [0.33 1.0] [0.66 0.2] [1.0 0.0]]
     :spline/alpha    0.5}

  `:spline/bezier`
    {:modulator/type   :spline/bezier
     :modulator/loop   :clamp
     :spline/anchors   [[0.0 0.0] [0.5 1.0] [1.0 0.0]]
     :spline/handles   [[0.10  0.40]    ; Δ out-handle from anchor 0
                        [-0.10 -0.30]   ; Δ in-handle  to   anchor 1
                        [0.10  -0.30]   ; Δ out-handle from anchor 1
                        [-0.10  0.40]]} ; Δ in-handle  to   anchor 2

  `:lfo/sine`
    {:modulator/type   :lfo/sine
     :modulator/loop   :loop
     :lfo/phase-offset 0.0}

  `:step/hold` / `:step/linear` / `:step/smooth`
    {:modulator/type  :step/hold     ; :step/linear = lerp, :step/smooth = cosine
     :modulator/loop  :loop
     :step/values     [0.0 0.5 1.0 0.75 0.25]}

  `:env/ar`
    {:modulator/type      :env/ar
     :modulator/time-unit :seconds
     :env/attack          0.01
     :env/release         0.30}

  `:env/adsr`
    {:modulator/type      :env/adsr
     :modulator/time-unit :seconds
     :env/attack          0.01
     :env/decay           0.10
     :env/sustain         0.70
     :env/release         0.20}

  `:env/ahdsr`
    {:modulator/type      :env/ahdsr
     :modulator/time-unit :seconds
     :env/attack          0.01
     :env/hold            0.05      ; defaults to 0.0 if omitted
     :env/decay           0.10
     :env/sustain         0.70
     :env/release         0.20}

  `:env/multi-stage`
    {:modulator/type      :env/multi-stage
     :modulator/time-unit :seconds
     :env/breakpoints     [[0.00 0.0]   ; [time level] pairs; times in :modulator/time-unit
                           [0.01 1.0]
                           [0.10 0.7]
                           [0.80 0.7]
                           [1.00 0.0]]
     :env/curves          :linear}      ; keyword (all segments) or per-segment vector

  ## Authoring convenience

  `:spline/bezier` also accepts a flat `:spline/path` vector in SVG cubic-bezier
  layout: [anchor₀, out₀, in₁, anchor₁, out₁, in₂, anchor₂, ...].  Control
  points are absolute [phase value] coordinates.  `normalize-modulator` converts
  this to the canonical anchors + Δ-handles form.

  ## Cross-tier use

  Pass any modulator map through `normalize-modulator` before handing it to a
  tier evaluator or sending it over IPC.  Each tier's evaluator expects canonical
  form only."
  (:require [nous.clock :as clock]))

;; ---------------------------------------------------------------------------
;; Type-specific normalization — private multimethod
;; ---------------------------------------------------------------------------

(defmulti ^:private normalize-type :modulator/type)

(defn- path->bezier
  "Convert SVG-style flat path to canonical anchors + Δ-handles."
  [path]
  (let [n-segs  (/ (dec (count path)) 3)
        anchors (mapv #(nth path (* % 3)) (range (inc n-segs)))
        handles (into []
                  (mapcat
                    (fn [k]
                      (let [[ao0 ao1] (nth anchors k)
                            [ai0 ai1] (nth anchors (inc k))
                            [co0 co1] (nth path (+ (* k 3) 1))
                            [ci0 ci1] (nth path (+ (* k 3) 2))]
                        [[(- co0 ao0) (- co1 ao1)]
                         [(- ci0 ai0) (- ci1 ai1)]]))
                    (range n-segs)))]
    {:spline/anchors anchors
     :spline/handles handles}))

(defmethod normalize-type :spline/bezier [m]
  (if-let [path (:spline/path m)]
    (merge (dissoc m :spline/path) (path->bezier path))
    m))

(defmethod normalize-type :spline/catmull-rom [m]
  (update m :spline/alpha #(or % 0.5)))

(defmethod normalize-type :lfo/sine [m]
  (update m :lfo/phase-offset #(or % 0.0)))

(defmethod normalize-type :step/hold    [m] m)
(defmethod normalize-type :step/linear  [m] m)
(defmethod normalize-type :step/smooth  [m] m)

(defn- env-time-unit-default [m]
  (update m :modulator/time-unit #(or % :seconds)))

(defmethod normalize-type :env/ar    [m] (env-time-unit-default m))
(defmethod normalize-type :env/adsr  [m] (env-time-unit-default m))

(defmethod normalize-type :env/ahdsr [m]
  (-> m
      env-time-unit-default
      (update :env/hold #(or % 0.0))))

(defmethod normalize-type :env/multi-stage [m]
  (let [bp     (:env/breakpoints m)
        sorted (vec (sort-by first bp))
        n-segs (dec (count sorted))
        curves (:env/curves m :linear)]
    (-> m
        env-time-unit-default
        (assoc :env/breakpoints sorted)
        (assoc :env/curves (if (keyword? curves)
                             (vec (repeat n-segs curves))
                             (vec curves))))))

(defmethod normalize-type :default [m] m)

;; ---------------------------------------------------------------------------
;; Evaluation helpers
;; ---------------------------------------------------------------------------

(defn- apply-loop
  "Transform phase according to :modulator/loop boundary mode."
  [loop-mode phase]
  (let [p (double phase)]
    (case loop-mode
      :loop      (let [r (mod p 1.0)] (if (neg? r) (+ r 1.0) r))
      :ping-pong (let [r (mod p 2.0)
                       r (if (neg? r) (+ r 2.0) r)]
                   (if (< r 1.0) r (- 2.0 r)))
      (max 0.0 (min 1.0 p)))))

(defn- find-segment
  "Return index k s.t. pts[k].coord ≤ p < pts[k+1].coord.
  Callers must handle out-of-range p before calling."
  [pts coord-fn p]
  (let [n (count pts)]
    (or (some (fn [i]
                (when (< (double p) (double (coord-fn (nth pts (inc i))))) i))
              (range (dec n)))
        (- n 2))))

(defn- apply-segment-curve
  "Interpolate v0→v1 using named curve over normalised t ∈ [0,1]."
  [curve t v0 v1]
  (let [t      (double t)
        shaped (case curve
                 :linear      t
                 :exponential (* t t)
                 :cosine      (* 0.5 (- 1.0 (Math/cos (* Math/PI t))))
                 t)]
    (+ (double v0) (* shaped (- (double v1) (double v0))))))

;; ---------------------------------------------------------------------------
;; Step shape functions
;; ---------------------------------------------------------------------------

(defn- step-hold-fn [values lp]
  (let [n (count values)
        v (vec values)]
    (fn [phase]
      (let [p (apply-loop lp phase)]
        (nth v (min (int (* p n)) (dec n)))))))

(defn- step-linear-fn [values lp]
  (let [n (count values)
        v (vec values)]
    (fn [phase]
      (let [p      (apply-loop lp phase)
            scaled (* (double p) n)
            k      (min (int scaled) (dec n))]
        (if (= k (dec n))
          (double (nth v k))
          (let [t (- scaled (double k))]
            (+ (double (nth v k)) (* t (- (double (nth v (inc k))) (double (nth v k)))))))))))

(defn- step-smooth-fn [values lp]
  (let [n (count values)
        v (vec values)]
    (fn [phase]
      (let [p      (apply-loop lp phase)
            scaled (* (double p) n)
            k      (min (int scaled) (dec n))]
        (if (= k (dec n))
          (double (nth v k))
          (let [t     (- scaled (double k))
                t-cos (* 0.5 (- 1.0 (Math/cos (* Math/PI t))))]
            (+ (double (nth v k)) (* t-cos (- (double (nth v (inc k))) (double (nth v k)))))))))))

;; ---------------------------------------------------------------------------
;; Catmull-Rom shape function
;; ---------------------------------------------------------------------------

(defn- catmull-rom
  "Uniform Catmull-Rom interpolation between v1 and v2 at t, given neighbours v0 and v3."
  [v0 v1 v2 v3 t]
  (let [v0 (double v0) v1 (double v1) v2 (double v2) v3 (double v3) t (double t)
        t2 (* t t)
        t3 (* t2 t)]
    (* 0.5 (+ (* 2.0 v1)
              (* (+ (- v0) v2) t)
              (* (+ (* 2.0 v0) (* -5.0 v1) (* 4.0 v2) (- v3)) t2)
              (* (+ (- v0) (* 3.0 v1) (* -3.0 v2) v3) t3)))))

(defn- catmull-rom-fn [knots lp]
  (let [n    (count knots)
        vals (mapv second knots)
        phs  (mapv first  knots)]
    (fn [phase]
      (let [p (apply-loop lp phase)]
        (cond
          (<= p (double (first phs))) (double (first vals))
          (>= p (double (last  phs))) (double (last  vals))
          :else
          (let [k  (find-segment knots first p)
                t  (/ (- (double p) (double (nth phs k)))
                      (- (double (nth phs (inc k))) (double (nth phs k))))
                v1 (double (nth vals k))
                v2 (double (nth vals (inc k)))
                v0 (double (nth vals (max 0 (dec k))))
                v3 (double (nth vals (min (dec n) (+ k 2))))]
            (catmull-rom v0 v1 v2 v3 t)))))))

;; ---------------------------------------------------------------------------
;; Bezier shape function
;; ---------------------------------------------------------------------------

(defn- bezier-x [p0 p1 p2 p3 t]
  (let [u (- 1.0 (double t))]
    (+ (* u u u (double p0)) (* 3.0 u u t (double p1))
       (* 3.0 u t t (double p2)) (* t t t (double p3)))))

(defn- bezier-y [v0 v1 v2 v3 t]
  (let [u (- 1.0 (double t))]
    (+ (* u u u (double v0)) (* 3.0 u u t (double v1))
       (* 3.0 u t t (double v2)) (* t t t (double v3)))))

(defn- bezier-solve-t
  "Binary-search for t ∈ [0,1] such that bezier-x(t) ≈ target-x."
  [p0x p1x p2x p3x target-x]
  (loop [lo 0.0 hi 1.0 i 0]
    (let [mid (* 0.5 (+ lo hi))]
      (if (> i 32)
        mid
        (let [x (bezier-x p0x p1x p2x p3x mid)]
          (if (< (Math/abs (- x (double target-x))) 1e-8)
            mid
            (if (< x (double target-x))
              (recur mid hi (inc i))
              (recur lo mid (inc i)))))))))

(defn- bezier-fn [anchors handles lp]
  (let [anchors (vec anchors)
        handles (vec handles)]
    (fn [phase]
      (let [p (apply-loop lp phase)]
        (cond
          (<= p (double (first (first anchors)))) (double (second (first anchors)))
          (>= p (double (first (last  anchors)))) (double (second (last  anchors)))
          :else
          (let [k           (find-segment anchors first p)
                [a0p a0v]   (nth anchors k)
                [a1p a1v]   (nth anchors (inc k))
                [dop dov]   (nth handles (* k 2))
                [dip div]   (nth handles (inc (* k 2)))
                p1p (+ (double a0p) (double dop))
                p1v (+ (double a0v) (double dov))
                p2p (+ (double a1p) (double dip))
                p2v (+ (double a1v) (double div))
                t   (bezier-solve-t a0p p1p p2p a1p p)]
            (bezier-y a0v p1v p2v a1v t)))))))

;; ---------------------------------------------------------------------------
;; LFO sine shape function
;; ---------------------------------------------------------------------------

(defn- lfo-sine-fn [phase-offset lp]
  (fn [phase]
    (let [p (apply-loop lp phase)]
      (* 0.5 (+ 1.0 (Math/sin (* 2.0 Math/PI (+ (double p) (double phase-offset)))))))))

;; ---------------------------------------------------------------------------
;; Env multi-stage shape function (time-indexed)
;; ---------------------------------------------------------------------------

(defn- multi-stage-fn [breakpoints curves]
  (let [bps (vec breakpoints)
        cvs curves]
    (fn [t]
      (let [t (double t)]
        (cond
          (<= t (double (first (first bps)))) (double (second (first bps)))
          (>= t (double (first (last  bps)))) (double (second (last  bps)))
          :else
          (let [k         (find-segment bps first t)
                [t0 v0]   (nth bps k)
                [t1 v1]   (nth bps (inc k))
                frac      (/ (- t (double t0)) (- (double t1) (double t0)))]
            (apply-segment-curve (nth cvs k) frac v0 v1)))))))

;; ---------------------------------------------------------------------------
;; Env breakpoints from gated types
;; ---------------------------------------------------------------------------

(defmulti ^:private env->breakpoints
  "Convert a gated env type to breakpoints [[t level] ...] given gate-duration."
  (fn [m _] (:modulator/type m)))

(defmethod env->breakpoints :env/ar [{:env/keys [attack release]} gate-duration]
  [[0.0                               0.0]
   [(double attack)                   1.0]
   [(double gate-duration)            1.0]
   [(+ gate-duration release)         0.0]])

(defmethod env->breakpoints :env/adsr [{:env/keys [attack decay sustain release]} gate-duration]
  [[0.0                               0.0]
   [(double attack)                   1.0]
   [(+ attack decay)                  (double sustain)]
   [(double gate-duration)            (double sustain)]
   [(+ gate-duration release)         0.0]])

(defmethod env->breakpoints :env/ahdsr [{:env/keys [attack hold decay sustain release]}
                                         gate-duration]
  (let [hold (or hold 0.0)]
    [[0.0                             0.0]
     [(double attack)                 1.0]
     [(+ attack hold)                 1.0]
     [(+ attack hold decay)           (double sustain)]
     [(double gate-duration)          (double sustain)]
     [(+ gate-duration release)       0.0]]))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn normalize-modulator
  "Return m in canonical form.  Applies shared defaults then dispatches to
  type-specific normalization.  Safe to call on already-canonical maps."
  [m]
  (-> m
      (update :modulator/loop #(or % :clamp))
      normalize-type))

(defn bezier->path
  "Convert a canonical `:spline/bezier` map to the flat SVG-style path vector.
  Inverse of the path→anchors conversion in `normalize-modulator`.
  Useful for display and editor tooling."
  [{:spline/keys [anchors handles]}]
  (let [n-segs (dec (count anchors))]
    (into [(first anchors)]
      (mapcat
        (fn [k]
          (let [[ao0 ao1] (nth anchors k)
                [ai0 ai1] (nth anchors (inc k))
                [do0 do1] (nth handles (* k 2))
                [di0 di1] (nth handles (inc (* k 2)))]
            [[(+ ao0 do0) (+ ao1 do1)]
             [(+ ai0 di0) (+ ai1 di1)]
             (nth anchors (inc k))]))
        (range n-segs)))))

(defmulti modulator->shape-fn
  "Return a shape function for the modulator.

  Phase-based types (:step/*, :spline/*, :lfo/*):
    Returns (fn [phase] → value), phase ∈ [0,1], value ∈ [0,1].
    :modulator/loop controls out-of-range phase behaviour.

  :env/multi-stage:
    Returns (fn [t] → value), t in :modulator/time-unit.
    :modulator/loop is not applied (time coordinate has no loop semantics).

  :env/adsr, :env/ar, :env/ahdsr:
    Use env->shape-fn — these types require a gate duration."
  :modulator/type)

(defmethod modulator->shape-fn :step/hold [m]
  (step-hold-fn (:step/values m) (:modulator/loop m :clamp)))

(defmethod modulator->shape-fn :step/linear [m]
  (step-linear-fn (:step/values m) (:modulator/loop m :clamp)))

(defmethod modulator->shape-fn :step/smooth [m]
  (step-smooth-fn (:step/values m) (:modulator/loop m :clamp)))

(defmethod modulator->shape-fn :spline/catmull-rom [m]
  (catmull-rom-fn (:spline/knots m) (:modulator/loop m :clamp)))

(defmethod modulator->shape-fn :spline/bezier [m]
  (bezier-fn (:spline/anchors m) (:spline/handles m) (:modulator/loop m :clamp)))

(defmethod modulator->shape-fn :lfo/sine [m]
  (lfo-sine-fn (:lfo/phase-offset m 0.0) (:modulator/loop m :loop)))

(defmethod modulator->shape-fn :env/multi-stage [m]
  (multi-stage-fn (:env/breakpoints m) (:env/curves m)))

(defn env->shape-fn
  "Return a (fn [t] → value) for a gated envelope modulator.
  gate-duration: time from gate-on to gate-off, in :modulator/time-unit."
  [m gate-duration]
  (let [bps (env->breakpoints m gate-duration)
        n   (dec (count bps))]
    (multi-stage-fn bps (vec (repeat n :linear)))))

;; ---------------------------------------------------------------------------
;; Step-synchronous mod compilation — shared by MotifState, ArpState, etc.
;; ---------------------------------------------------------------------------

(defn- default-mod-range
  "Default [lo hi] output range for a step-key.
  :mod/velocity → [0.0 127.0]; all others → [0.0 1.0]."
  [k]
  (if (= k :mod/velocity) [0.0 127.0] [0.0 1.0]))

(defn compile-mods
  "Compile a {step-key modulator-map-or-shape-fn} map to
  {step-key {:shape fn :lo double :hi double}}.

  Modulator maps (with :modulator/type) are normalized and compiled to a
  shape function.  :modulator/range [lo hi] overrides the default range.
  :mod/velocity defaults to [0, 127]; all other keys default to [0, 1].
  Pre-compiled shape functions are accepted as-is (default range applies).
  Returns nil when `mods` is nil or empty."
  [mods]
  (when (seq mods)
    (reduce-kv
      (fn [m k raw]
        (let [[lo hi] (default-mod-range k)]
          (if (and (map? raw) (:modulator/type raw))
            (let [[rlo rhi] (get raw :modulator/range [lo hi])
                  m*        (normalize-modulator (dissoc raw :modulator/range))
                  shape     (modulator->shape-fn m*)]
              (assoc m k {:shape shape :lo (double rlo) :hi (double rhi)}))
            (assoc m k {:shape raw :lo (double lo) :hi (double hi)}))))
      {}
      mods)))

(defn sample-mods
  "Sample each compiled mod entry at `phase` → {step-key value}.
  Linearly maps shape output [0,1] to [lo, hi].
  :mod/velocity values are cast to long."
  [mods-compiled phase]
  (reduce-kv
    (fn [m k {:keys [shape lo hi]}]
      (let [v (+ lo (* (double (shape phase)) (- hi lo)))]
        (assoc m k (if (= k :mod/velocity) (long (Math/round v)) v))))
    {}
    mods-compiled))

;; ---------------------------------------------------------------------------
;; deflive-loop :step-mods auto-compilation
;; ---------------------------------------------------------------------------

(defn step-mods-compile
  "Auto-compile raw modulator maps in a *step-mod-ctx* map to ITemporalValue.

  Called automatically by deflive-loop on the :step-mods option — users do
  not need to call this directly.  Any entry whose value is a map with
  :modulator/type is compiled against master-clock (1 cycle/beat); ITemporalValue
  values and constants pass through unchanged.

  For beat-time modulation at a rate other than master-clock, or when you need
  the source map preserved on the ITemporalValue for inspection, construct
  explicitly with nous.mod/modulator-lfo and pass the result in :step-mods.

  Returns nil when `ctx` is nil."
  [ctx]
  (when ctx
    (reduce-kv
      (fn [m k v]
        (assoc m k
               (if (and (map? v) (:modulator/type v))
                 (let [m*    (normalize-modulator v)
                       shape (modulator->shape-fn m*)
                       ph    clock/master-clock]
                   (reify clock/ITemporalValue
                     (sample    [_ beat] (shape (clock/sample ph beat)))
                     (next-edge [_ beat] (clock/next-edge ph beat))))
                 v)))
      {}
      ctx)))

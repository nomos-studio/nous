; SPDX-License-Identifier: EPL-2.0
(ns nous.mod
  "nous modulation subsystem — LFOs, envelopes, and one-shot signals.

  All modulators are `ITemporalValue` instances produced by composing a
  `Phasor` with a shape function from `nomos.maths.phasor` or a portable
  modulator map from `nous.modulator`.

  ## LFO from phasor shape
    (lfo (->Phasor 1/16 0) phasor/sine-uni)    ; slow sine, unipolar [0,1]
    (lfo (->Phasor 1/8  0) phasor/triangle)    ; triangle, same rate family
    (lfo (->Phasor 1/4  0) (phasor/square 0.3)) ; 30% duty-cycle square

  ## LFO from portable modulator map
    (modulator-lfo (->Phasor 1/4 0)
                   {:modulator/type :spline/catmull-rom
                    :spline/knots   [[0.0 0.0] [0.33 1.0] [0.66 0.2] [1.0 0.0]]})

  ## Envelope (looping)
    (def adsr (envelope [[0.0 0.0] [0.1 1.0] [0.3 0.7] [0.8 0.7] [1.0 0.0]]))
    (lfo (->Phasor 1/4 0) adsr)   ; 4-beat looping envelope

  ## One-shot from portable env map
    ;; Phasor period must equal gate-duration + release in :modulator/time-unit.
    ;; For time-unit :beats, period in beats = gate-duration + release directly.
    (modulator-one-shot (->Phasor 3 0)
                        {:modulator/type :env/ar :modulator/time-unit :beats
                         :env/attack 1.0 :env/release 1.0}
                        2.0   ; gate-duration (beats)
                        (now))

  ## One-shot envelope (phasor shape)
    (one-shot (->Phasor 1/4 0) adsr start-beat)

  ## Routing to ctrl
    (mod-route! [:filter/cutoff] (lfo (->Phasor 1/4 0) phasor/sine-uni))
    (mod-unroute! [:filter/cutoff])

  The runner samples the modulator at its natural `next-edge` rate and
  dispatches via `ctrl/send!`. The ctrl binding's :range handles scaling
  to the physical MIDI range — so the modulator's raw output (typically
  [0.0, 1.0] for unipolar shapes) should match the binding's :range.

  When a one-shot's `next-edge` returns ##Inf the runner auto-stops.

  Key design decisions: R&R §28.7, Q30 (LFO rate — empirical during impl)."
  (:require [ctrl-tree.core  :as ct]
            [nous.clock      :as clock]
            [nous.loop       :as loop-ns]
            [nous.modulator  :as modulator]
            [nomos.maths.phasor :as phasor])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state reference (injected by nous.core/start!)
;; ---------------------------------------------------------------------------

(defonce ^:private system-ref (atom nil))

(defn -register-system!
  "Called by nous.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

(defn- current-beat
  "Return the current beat position from the system timeline, or 0.0."
  ^double []
  (if-let [s @system-ref]
    (if-let [tl (:timeline @s)]
      (clock/epoch-ms->beat (System/currentTimeMillis) tl)
      0.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Active routes: {path {:mod mod :running? atom :thread Thread}}
;; ---------------------------------------------------------------------------

(defonce ^:private routes (atom {}))

;; ---------------------------------------------------------------------------
;; Lfo — Phasor + shape fn -> ITemporalValue
;; ---------------------------------------------------------------------------

(defrecord Lfo [ph shape-fn]
  clock/ITemporalValue
  (sample    [_ beat] (shape-fn (clock/sample ph beat)))
  (next-edge [_ beat] (clock/next-edge ph beat)))

(defn lfo
  "Construct an LFO from a Phasor and a shape function.

  `ph`       — a Phasor (from nous.clock) controlling rate and phase offset
  `shape-fn` — a function [0.0, 1.0) -> number (from nomos.maths.phasor or user-defined)

  The same Phasor instance can drive multiple LFOs; they will be phase-locked.

  Examples:
    (lfo (->Phasor 1/16 0) phasor/sine-uni)        ; unipolar sine
    (lfo (->Phasor 1/16 0) phasor/triangle)        ; triangle, phase-locked to above
    (lfo (->Phasor 1/8  0) (phasor/square 0.5))    ; 50% square at half the rate"
  [ph shape-fn]
  (->Lfo ph shape-fn))

;; ---------------------------------------------------------------------------
;; envelope — piecewise-linear shape function from breakpoints
;; ---------------------------------------------------------------------------

(defn envelope
  "Build a piecewise-linear shape function from breakpoints.

  `breakpoints` — sequence of [phase value] pairs, phases in [0.0, 1.0],
                  strictly ascending. First phase must be 0.0; last must be 1.0.

  Returns a function (fn [p] -> number) suitable for use as an `lfo` shape.

  Example (ADSR):
    (def adsr
      (envelope [[0.00 0.0]    ; silence at start
                 [0.10 1.0]    ; attack peak at 10% of cycle
                 [0.30 0.7]    ; decay to sustain level
                 [0.80 0.7]    ; sustain hold
                 [1.00 0.0]])) ; release to silence

  Use with lfo:
    (lfo (->Phasor 1/4 0) adsr)   ; 4-beat looping envelope"
  [breakpoints]
  (let [pts (vec (sort-by first breakpoints))]
    (fn [p]
      (let [p    (double p)
            pair (->> (partition 2 1 pts)
                      (filter (fn [[[p0] [p1]]]
                                (<= (double p0) p (double p1))))
                      first)]
        (if pair
          (let [[[p0 v0] [p1 v1]] pair
                p0 (double p0) v0 (double v0)
                p1 (double p1) v1 (double v1)]
            (+ v0 (* (/ (- p p0) (- p1 p0)) (- v1 v0))))
          (double (second (last pts))))))))

;; ---------------------------------------------------------------------------
;; one-shot — ITemporalValue that clamps after the first phasor cycle
;; ---------------------------------------------------------------------------

(defrecord OneShot [ph shape-fn start-beat]
  clock/ITemporalValue
  (sample [_ beat]
    (let [end-beat (clock/next-edge ph (double start-beat))]
      (if (>= (double beat) end-beat)
        (shape-fn 1.0)
        (shape-fn (clock/sample ph beat)))))
  (next-edge [_ beat]
    (let [end-beat (clock/next-edge ph (double start-beat))]
      (if (>= (double beat) end-beat)
        ##Inf
        (clock/next-edge ph beat)))))

(defn one-shot
  "Wrap an LFO so that it runs once and then clamps at its final value.

  `ph`         — a Phasor controlling the envelope duration and rate
  `shape-fn`   — shape function (e.g. from envelope or phasor/*)
  `start-beat` — the beat at which the one-shot starts (typically (now))

  After the first complete phasor cycle, `next-edge` returns ##Inf and
  `sample` returns the clamped end value (shape-fn applied to 1.0).
  When routed via mod-route!, the runner auto-stops on ##Inf.

  Example:
    (one-shot (->Phasor 1/4 0) adsr (now))   ; 4-beat one-shot attack"
  [ph shape-fn start-beat]
  (->OneShot ph shape-fn (double start-beat)))

;; ---------------------------------------------------------------------------
;; ModulatorLfo — Phasor + portable modulator map → ITemporalValue
;; ---------------------------------------------------------------------------

(defrecord ModulatorLfo [ph modulator shape-fn]
  clock/ITemporalValue
  (sample    [_ beat] (shape-fn (clock/sample ph beat)))
  (next-edge [_ beat] (clock/next-edge ph beat)))

(defn modulator-lfo
  "Construct a phase-based LFO from a Phasor and a portable modulator map.

  `ph` — a Phasor (from nous.clock) controlling rate and phase offset
  `m`  — a modulator map (:step/*, :spline/*, :lfo/*)

  The modulator is normalized and compiled to a shape function at construction
  time.  The original map is retained in `:modulator` for inspection and
  serialization.  Compatible with mod-route!, one-shot, and anywhere a plain
  `lfo` is accepted.

  Examples:
    (modulator-lfo (->Phasor 1/4 0)
                   {:modulator/type :spline/catmull-rom
                    :spline/knots   [[0.0 0.0] [0.33 1.0] [0.66 0.2] [1.0 0.0]]})

    (modulator-lfo (->Phasor 1/8 0)
                   {:modulator/type :step/smooth
                    :modulator/loop :loop
                    :step/values    [0.0 0.25 1.0 0.5 0.75]})"
  [ph m]
  (let [m* (modulator/normalize-modulator m)]
    (->ModulatorLfo ph m* (modulator/modulator->shape-fn m*))))

(defn- env-total-time
  "Total time span of a normalized env modulator in :modulator/time-unit.
  For gated types: gate-duration + release (attack/decay/hold are within the gate)."
  [m gate-duration]
  (if (= :env/multi-stage (:modulator/type m))
    (double (first (last (:env/breakpoints m))))
    (+ (double gate-duration) (double (get m :env/release 0.0)))))

(defn modulator-one-shot
  "Construct a one-shot envelope from a Phasor and a portable env modulator map.

  `ph`           — a Phasor whose period should equal gate-duration + :env/release
                   (in :modulator/time-unit); for :env/multi-stage, equal to the
                   final breakpoint time
  `m`            — an env modulator map (:env/ar, :env/adsr, :env/ahdsr,
                   :env/multi-stage)
  `gate-duration` — time from t=0 to gate-off, in :modulator/time-unit
                    (ignored for :env/multi-stage)
  `start-beat`   — the beat at which the envelope starts

  Phase [0,1] from the Phasor maps linearly to the envelope's time span.
  When :modulator/time-unit is :beats the Phasor period = gate-duration + release
  directly.  For :seconds, convert total seconds to beats before constructing
  the Phasor.

  Example (AR in beats, total = gate 2 + release 1 = 3 beats):
    (modulator-one-shot (->Phasor 3 0)
                        {:modulator/type :env/ar :modulator/time-unit :beats
                         :env/attack 1.0 :env/release 1.0}
                        2.0 (now))"
  [ph m gate-duration start-beat]
  (let [m*         (modulator/normalize-modulator m)
        total-time (env-total-time m* gate-duration)
        shape-t    (if (= :env/multi-stage (:modulator/type m*))
                     (modulator/modulator->shape-fn m*)
                     (modulator/env->shape-fn m* gate-duration))
        shape-ph   (fn [phase] (shape-t (* (double phase) total-time)))]
    (one-shot ph shape-ph start-beat)))

(defn compile-step-mods
  "Compile raw modulator maps in a step-mods context map to ModulatorLfo.

  Any entry whose value is a map with :modulator/type is compiled to a
  ModulatorLfo driven by master-clock (rate=1, period=1 beat).  For slower
  beat-time modulation, wrap the map explicitly with modulator-lfo instead.

  ITemporalValue values and constant numbers are passed through unchanged.

  Returns a new map suitable for use-step-mods! or explicit construction.
  Returns nil when `ctx` is nil.

  Note: deflive-loop :step-mods already auto-compiles raw modulator maps via
  nous.modulator/step-mods-compile, so you only need compile-step-mods when
  you want the full ModulatorLfo type (which preserves the source map at
  :modulator for introspection) rather than the lightweight auto-compiled form.

  Examples:
    ;; preserve source map for inspection
    (def vel-mod (compile-step-mods {:mod/velocity {:modulator/type :lfo/sine}}))
    (get-in vel-mod [:mod/velocity :modulator])  ;=> normalized map

    ;; mix of raw map + explicit ITemporalValue
    (compile-step-mods
      {:mod/velocity {:modulator/type :step/hold :step/values [80 100 90 120]}
       :gate/len     (modulator-lfo (clock-div 4) {:modulator/type :lfo/sine})})"
  [ctx]
  (when ctx
    (reduce-kv (fn [m k v]
                 (assoc m k (if (and (map? v) (:modulator/type v))
                              (modulator-lfo clock/master-clock v)
                              v)))
               {}
               ctx)))

;; ---------------------------------------------------------------------------
;; Runner thread
;; ---------------------------------------------------------------------------

(defn- runner-loop
  "Main body of a mod runner thread for `path`."
  [path running?]
  (loop []
    (when @running?
      (let [beat (current-beat)
            mod  (:mod (get @routes path))
            next (when mod (clock/next-edge mod beat))]
        (cond
          (nil? next)
          nil                       ; route removed — exit

          (= ##Inf next)
          (do
            (reset! running? false)
            (swap! routes dissoc path))   ; one-shot finished — auto-stop and clean up

          :else
          (do
            (loop-ns/-park-until-beat! next)
            (when @running?
              (let [beat' (current-beat)
                    mod'  (:mod (get @routes path))]
                (when mod'
                  (ct/ctrl-write! path (clock/sample mod' beat')))))
            (recur)))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn mod-route!
  "Connect a modulator to a ctrl path. Starts a runner thread that samples
  the modulator at its natural `next-edge` rate and dispatches via `ctrl/send!`.

  Two arities:

    (mod-route! path mod)
      `path` — ctrl tree path vector, e.g. [:filter/cutoff]
      `mod`  — any ITemporalValue (lfo, one-shot, modulator-lfo, etc.)

    (mod-route! path modulator-map phasor)
      `modulator-map` — a portable modulator map (with :modulator/type)
      `phasor`        — a Phasor controlling rate and phase offset
      Equivalent to (mod-route! path (modulator-lfo phasor modulator-map)).

  The raw sampled value is passed directly to `ctrl/send!`. Set the ctrl
  binding's `:range` to match the modulator's output domain — typically
  [0.0 1.0] for unipolar shapes (sine-uni, triangle, saw-up).

  Re-evaluating with the same path hot-swaps the modulator without restarting
  the runner thread. When a one-shot's `next-edge` returns ##Inf the runner
  auto-stops.

  Examples:
    (mod-route! [:filter/cutoff] (modulator-lfo (clock-div 4) {:modulator/type :lfo/sine}))

    ;; shorthand — map + phasor directly:
    (mod-route! [:filter/cutoff]
                {:modulator/type :spline/catmull-rom
                 :spline/knots   [[0.0 0.1] [0.5 0.9] [1.0 0.1]]}
                (clock-div 8))"
  ([path mod]
   (if (get @routes path)
     (swap! routes assoc-in [path :mod] mod)
     (let [running? (atom true)]
       (swap! routes assoc path {:mod mod :running? running? :thread nil})
       (let [t (Thread.
                (fn [] (runner-loop path running?)))]
         (.setDaemon t true)
         (.setName t (str "nous-mod-" (clojure.string/join "/" (map name path))))
         (swap! routes assoc-in [path :thread] t)
         (.start t))))
   path)
  ([path modulator-map phasor]
   (mod-route! path (modulator-lfo phasor modulator-map))))

(defn mod-unroute!
  "Stop the mod runner for `path` and remove the route.

  The runner finishes its current sleep then exits. Calling mod-unroute! on
  an unknown path is a no-op."
  [path]
  (when-let [route (get @routes path)]
    (reset! (:running? route) false)
    (when-let [^Thread t (:thread route)]
      (LockSupport/unpark t)))
  (swap! routes dissoc path)
  nil)

(defn mod-unroute-all!
  "Stop all active mod runners. Called by nous.core/stop!."
  []
  (doseq [path (keys @routes)]
    (mod-unroute! path))
  nil)

(defn mod-routes
  "Return a map of currently active mod routes: {path mod}.
  Useful for inspection at the REPL."
  []
  (into {} (map (fn [[path r]] [path (:mod r)]) @routes)))

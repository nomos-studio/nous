; SPDX-License-Identifier: EPL-2.0
(ns nous.modulator-test
  (:require [clojure.test :refer [deftest is testing]]
            [nous.modulator :as modulator]))

(def ^:private eps   1e-9)
(def ^:private eps6  1e-6)
(defn- ≈  [a b] (< (Math/abs (- (double a) (double b))) eps))
(defn- ≈6 [a b] (< (Math/abs (- (double a) (double b))) eps6))

;; ---------------------------------------------------------------------------
;; Shared defaults
;; ---------------------------------------------------------------------------

(deftest shared-default-loop-test
  (testing "missing :modulator/loop defaults to :clamp"
    (is (= :clamp (:modulator/loop
                   (modulator/normalize-modulator
                    {:modulator/type :lfo/sine}))))
    (is (= :loop  (:modulator/loop
                   (modulator/normalize-modulator
                    {:modulator/type :lfo/sine :modulator/loop :loop}))))))

;; ---------------------------------------------------------------------------
;; :spline/catmull-rom
;; ---------------------------------------------------------------------------

(deftest catmull-rom-alpha-default-test
  (testing "missing :spline/alpha defaults to 0.5"
    (let [m (modulator/normalize-modulator
             {:modulator/type :spline/catmull-rom
              :spline/knots   [[0.0 0.0] [1.0 1.0]]})]
      (is (≈ 0.5 (:spline/alpha m))))))

(deftest catmull-rom-alpha-preserved-test
  (testing "explicit :spline/alpha is not overwritten"
    (let [m (modulator/normalize-modulator
             {:modulator/type :spline/catmull-rom
              :spline/knots   [[0.0 0.0] [1.0 1.0]]
              :spline/alpha   1.0})]
      (is (≈ 1.0 (:spline/alpha m))))))

(deftest catmull-rom-idempotent-test
  (testing "normalize-modulator is idempotent for catmull-rom"
    (let [m  {:modulator/type :spline/catmull-rom
              :modulator/loop :loop
              :spline/knots   [[0.0 0.0] [0.5 1.0] [1.0 0.0]]
              :spline/alpha   0.5}]
      (is (= m (modulator/normalize-modulator m))))))

;; ---------------------------------------------------------------------------
;; :spline/bezier — path → canonical
;; ---------------------------------------------------------------------------

;; Reference shape used across bezier tests:
;;   anchor0 = [0.0 0.0]
;;   out0-abs = [0.1 0.4]  → Δ = [ 0.10  0.40]
;;   in1-abs  = [0.4 0.7]  → Δ = [-0.10 -0.30]  (relative to anchor1 [0.5 1.0])
;;   anchor1  = [0.5 1.0]
;;   out1-abs = [0.6 0.7]  → Δ = [ 0.10 -0.30]
;;   in2-abs  = [0.9 0.4]  → Δ = [-0.10  0.40]  (relative to anchor2 [1.0 0.0])
;;   anchor2  = [1.0 0.0]

(def ^:private test-path
  [[0.0 0.0] [0.1 0.4] [0.4 0.7] [0.5 1.0] [0.6 0.7] [0.9 0.4] [1.0 0.0]])

(def ^:private test-anchors [[0.0 0.0] [0.5 1.0] [1.0 0.0]])
(def ^:private test-handles [[ 0.10  0.40]
                              [-0.10 -0.30]
                              [ 0.10 -0.30]
                              [-0.10  0.40]])

(deftest bezier-path-extracts-anchors-test
  (testing "path form: anchors are every 3rd point"
    (let [m (modulator/normalize-modulator
             {:modulator/type :spline/bezier
              :spline/path    test-path})]
      (is (= test-anchors (:spline/anchors m))))))

(deftest bezier-path-computes-delta-handles-test
  (testing "path form: handles are Δ-offsets from adjacent anchors"
    (let [m       (modulator/normalize-modulator
                   {:modulator/type :spline/bezier
                    :spline/path    test-path})
          handles (:spline/handles m)]
      (doseq [i (range (count test-handles))]
        (let [[ex ey] (nth test-handles i)
              [ax ay] (nth handles i)]
          (is (≈ ex ax) (str "handle[" i "] phase Δ"))
          (is (≈ ey ay) (str "handle[" i "] value Δ")))))))

(deftest bezier-path-key-removed-test
  (testing "path form: :spline/path is removed after normalization"
    (let [m (modulator/normalize-modulator
             {:modulator/type :spline/bezier
              :spline/path    test-path})]
      (is (not (contains? m :spline/path))))))

(deftest bezier-canonical-unchanged-test
  (testing "canonical form (anchors + handles) passes through unchanged"
    (let [m {:modulator/type :spline/bezier
             :modulator/loop :clamp
             :spline/anchors test-anchors
             :spline/handles test-handles}]
      (is (= m (modulator/normalize-modulator m))))))

;; ---------------------------------------------------------------------------
;; bezier->path / roundtrip
;; ---------------------------------------------------------------------------

(deftest bezier->path-roundtrip-test
  (testing "path → canonical → path roundtrips within floating-point tolerance"
    (let [canonical (modulator/normalize-modulator
                     {:modulator/type :spline/bezier
                      :spline/path    test-path})
          recovered (modulator/bezier->path canonical)]
      (is (= (count test-path) (count recovered)))
      (doseq [i (range (count test-path))]
        (let [[ex ey] (nth test-path i)
              [ax ay] (nth recovered i)]
          (is (≈ ex ax) (str "path[" i "] phase"))
          (is (≈ ey ay) (str "path[" i "] value")))))))

;; ---------------------------------------------------------------------------
;; :lfo/sine
;; ---------------------------------------------------------------------------

(deftest lfo-sine-phase-offset-default-test
  (testing "missing :lfo/phase-offset defaults to 0.0"
    (let [m (modulator/normalize-modulator {:modulator/type :lfo/sine})]
      (is (≈ 0.0 (:lfo/phase-offset m))))))

(deftest lfo-sine-phase-offset-preserved-test
  (testing "explicit :lfo/phase-offset is not overwritten"
    (let [m (modulator/normalize-modulator
             {:modulator/type :lfo/sine :lfo/phase-offset 0.25})]
      (is (≈ 0.25 (:lfo/phase-offset m))))))

;; ---------------------------------------------------------------------------
;; :env/adsr
;; ---------------------------------------------------------------------------

(deftest adsr-time-unit-default-test
  (testing "missing :modulator/time-unit defaults to :seconds"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/adsr
              :env/attack 0.01 :env/decay 0.1 :env/sustain 0.7 :env/release 0.2})]
      (is (= :seconds (:modulator/time-unit m))))))

(deftest adsr-time-unit-preserved-test
  (testing "explicit :modulator/time-unit is not overwritten"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/adsr
              :modulator/time-unit :beats
              :env/attack 0.5 :env/decay 0.5 :env/sustain 0.7 :env/release 1.0})]
      (is (= :beats (:modulator/time-unit m))))))

;; ---------------------------------------------------------------------------
;; Step types
;; ---------------------------------------------------------------------------

(deftest step-hold-passthrough-test
  (testing ":step/hold passes through with loop default applied"
    (let [m (modulator/normalize-modulator
             {:modulator/type :step/hold
              :step/values    [0.0 0.5 1.0]})]
      (is (= :clamp        (:modulator/loop m)))
      (is (= [0.0 0.5 1.0] (:step/values m))))))

(deftest step-linear-passthrough-test
  (testing ":step/linear passes through unchanged"
    (let [m {:modulator/type :step/linear
             :modulator/loop :loop
             :step/values    [0.0 1.0 0.5]}]
      (is (= m (modulator/normalize-modulator m))))))

(deftest step-smooth-passthrough-test
  (testing ":step/smooth passes through unchanged"
    (let [m {:modulator/type :step/smooth
             :modulator/loop :ping-pong
             :step/values    [0.0 0.5 1.0 0.5]}]
      (is (= m (modulator/normalize-modulator m))))))

;; ---------------------------------------------------------------------------
;; :env/ar
;; ---------------------------------------------------------------------------

(deftest env-ar-time-unit-default-test
  (testing ":env/ar defaults :modulator/time-unit to :seconds"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/ar
              :env/attack 0.01 :env/release 0.3})]
      (is (= :seconds (:modulator/time-unit m))))))

(deftest env-ar-time-unit-preserved-test
  (testing ":env/ar preserves explicit :modulator/time-unit"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/ar
              :modulator/time-unit :beats
              :env/attack 0.5 :env/release 1.0})]
      (is (= :beats (:modulator/time-unit m))))))

;; ---------------------------------------------------------------------------
;; :env/ahdsr
;; ---------------------------------------------------------------------------

(deftest env-ahdsr-hold-default-test
  (testing ":env/ahdsr defaults :env/hold to 0.0"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/ahdsr
              :env/attack 0.01 :env/decay 0.1 :env/sustain 0.7 :env/release 0.2})]
      (is (≈ 0.0 (:env/hold m))))))

(deftest env-ahdsr-hold-preserved-test
  (testing ":env/ahdsr preserves explicit :env/hold"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/ahdsr
              :env/attack 0.01 :env/hold 0.05
              :env/decay 0.1  :env/sustain 0.7 :env/release 0.2})]
      (is (≈ 0.05 (:env/hold m))))))

(deftest env-ahdsr-time-unit-default-test
  (testing ":env/ahdsr defaults :modulator/time-unit to :seconds"
    (let [m (modulator/normalize-modulator
             {:modulator/type :env/ahdsr
              :env/attack 0.01 :env/decay 0.1 :env/sustain 0.7 :env/release 0.2})]
      (is (= :seconds (:modulator/time-unit m))))))

;; ---------------------------------------------------------------------------
;; :env/multi-stage
;; ---------------------------------------------------------------------------

(deftest multi-stage-curves-scalar-expanded-test
  (testing "scalar :env/curves keyword is expanded to per-segment vector"
    (let [m (modulator/normalize-modulator
             {:modulator/type  :env/multi-stage
              :env/breakpoints [[0.0 0.0] [0.1 1.0] [0.5 0.7] [1.0 0.0]]
              :env/curves      :linear})]
      (is (= [:linear :linear :linear] (:env/curves m))))))

(deftest multi-stage-curves-vector-preserved-test
  (testing "per-segment :env/curves vector is preserved"
    (let [m (modulator/normalize-modulator
             {:modulator/type  :env/multi-stage
              :env/breakpoints [[0.0 0.0] [0.1 1.0] [0.5 0.7] [1.0 0.0]]
              :env/curves      [:exponential :linear :cosine]})]
      (is (= [:exponential :linear :cosine] (:env/curves m))))))

(deftest multi-stage-breakpoints-sorted-test
  (testing "breakpoints are sorted by time after normalization"
    (let [m (modulator/normalize-modulator
             {:modulator/type  :env/multi-stage
              :env/breakpoints [[1.0 0.0] [0.0 0.0] [0.5 0.7] [0.1 1.0]]
              :env/curves      :linear})]
      (is (= [[0.0 0.0] [0.1 1.0] [0.5 0.7] [1.0 0.0]]
             (:env/breakpoints m))))))

(deftest multi-stage-time-unit-default-test
  (testing ":env/multi-stage defaults :modulator/time-unit to :seconds"
    (let [m (modulator/normalize-modulator
             {:modulator/type  :env/multi-stage
              :env/breakpoints [[0.0 0.0] [1.0 1.0]]
              :env/curves      :linear})]
      (is (= :seconds (:modulator/time-unit m))))))

(deftest multi-stage-curves-default-test
  (testing ":env/multi-stage defaults :env/curves to :linear when absent"
    (let [m (modulator/normalize-modulator
             {:modulator/type  :env/multi-stage
              :env/breakpoints [[0.0 0.0] [0.5 1.0] [1.0 0.0]]})]
      (is (= [:linear :linear] (:env/curves m))))))

;; ---------------------------------------------------------------------------
;; Unknown type — pass-through
;; ---------------------------------------------------------------------------

(deftest unknown-type-passthrough-test
  (testing "unknown type is returned with shared defaults applied, keys preserved"
    (let [m (modulator/normalize-modulator
             {:modulator/type :future/thing :some/key 42})]
      (is (= :clamp    (:modulator/loop m)))
      (is (= 42        (:some/key m)))
      (is (= :future/thing (:modulator/type m))))))

;; ===========================================================================
;; Evaluator tests
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; :step/hold
;; ---------------------------------------------------------------------------

(deftest step-hold-boundary-test
  (testing "phase 0.0 → first value, phase 1.0 → last value"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/hold :modulator/loop :clamp
              :step/values    [0.1 0.5 0.9]})]
      (is (≈ 0.1 (f 0.0)))
      (is (≈ 0.9 (f 1.0))))))

(deftest step-hold-segment-test
  (testing "holds value within each segment"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/hold :modulator/loop :clamp
              :step/values    [0.0 0.25 0.5 0.75 1.0]})]
      (is (≈ 0.0  (f 0.10)))   ; segment 0: [0, 0.2)
      (is (≈ 0.25 (f 0.25)))   ; segment 1: [0.2, 0.4)
      (is (≈ 0.75 (f 0.75)))   ; segment 3: [0.6, 0.8)
      (is (≈ 1.0  (f 0.99))))))

;; ---------------------------------------------------------------------------
;; :step/linear
;; ---------------------------------------------------------------------------

(deftest step-linear-endpoints-test
  (testing "phase 0.0 → first value, phase 1.0 → last value"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/linear :modulator/loop :clamp
              :step/values    [0.0 1.0]})]
      (is (≈ 0.0 (f 0.0)))
      (is (≈ 1.0 (f 1.0))))))

(deftest step-linear-midpoint-test
  (testing "phase 0.25 = t=0.5 in segment 0 [0,0.5) → value 0.5"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/linear :modulator/loop :clamp
              :step/values    [0.0 1.0]})]
      (is (≈ 0.5 (f 0.25))))))

(deftest step-linear-multi-segment-test
  (testing "interpolates at midpoint of each segment"
    ;; 4 values → 4 segments each 0.25 wide: [0,0.25) [0.25,0.5) [0.5,0.75) [0.75,1]
    ;; Midpoints are at phase 0.125, 0.375, 0.625
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/linear :modulator/loop :clamp
              :step/values    [0.0 0.4 0.6 1.0]})]
      (is (≈ 0.2  (f 0.125)))   ; seg 0 midpoint: 0 + 0.5*0.4 = 0.2
      (is (≈ 0.5  (f 0.375)))   ; seg 1 midpoint: 0.4 + 0.5*0.2 = 0.5
      (is (≈ 0.8  (f 0.625))))))  ; seg 2 midpoint: 0.6 + 0.5*0.4 = 0.8

;; ---------------------------------------------------------------------------
;; :step/smooth
;; ---------------------------------------------------------------------------

(deftest step-smooth-endpoints-match-linear-test
  (testing "smooth endpoints equal linear endpoints"
    (let [flin (modulator/modulator->shape-fn
                {:modulator/type :step/linear :modulator/loop :clamp :step/values [0.0 1.0]})
          fsmo (modulator/modulator->shape-fn
                {:modulator/type :step/smooth :modulator/loop :clamp :step/values [0.0 1.0]})]
      (is (≈ (flin 0.0) (fsmo 0.0)))
      (is (≈ (flin 1.0) (fsmo 1.0))))))

(deftest step-smooth-midpoint-test
  (testing "phase 0.25 = t=0.5 in segment 0 → cosine gives same midpoint as linear"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/smooth :modulator/loop :clamp
              :step/values    [0.0 1.0]})]
      (is (≈ 0.5 (f 0.25))))))

(deftest step-smooth-flatter-than-linear-near-endpoints-test
  (testing "cosine is below linear at t=0.2 (slow start), above at t=0.8 (catching up)"
    ;; Segment 0 spans [0, 0.5) so phase 0.1 → t=0.2, phase 0.4 → t=0.8
    (let [flin (modulator/modulator->shape-fn
                {:modulator/type :step/linear :modulator/loop :clamp :step/values [0.0 1.0]})
          fsmo (modulator/modulator->shape-fn
                {:modulator/type :step/smooth :modulator/loop :clamp :step/values [0.0 1.0]})]
      (is (< (fsmo 0.1) (flin 0.1)) "smooth rises slower near start of segment")
      (is (> (fsmo 0.4) (flin 0.4)) "smooth is higher near end of segment (catching up)"))))

;; ---------------------------------------------------------------------------
;; :spline/catmull-rom
;; ---------------------------------------------------------------------------

(deftest catmull-rom-passes-through-knots-test
  (testing "catmull-rom evaluates to exact knot values at knot phases"
    (let [knots [[0.0 0.2] [0.33 0.8] [0.66 0.3] [1.0 0.6]]
          f     (modulator/modulator->shape-fn
                 {:modulator/type :spline/catmull-rom
                  :modulator/loop :clamp
                  :spline/knots   knots
                  :spline/alpha   0.5})]
      (doseq [[p v] knots]
        (is (≈6 v (f p)) (str "at phase " p))))))

(deftest catmull-rom-boundary-test
  (testing "below first knot returns first value; above last returns last"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :spline/catmull-rom :modulator/loop :clamp
              :spline/knots   [[0.2 0.3] [0.8 0.7]] :spline/alpha 0.5})]
      (is (≈ 0.3 (f 0.0)))
      (is (≈ 0.7 (f 1.0))))))

;; ---------------------------------------------------------------------------
;; :spline/bezier
;; ---------------------------------------------------------------------------

(deftest bezier-passes-through-anchors-test
  (testing "bezier evaluates to exact anchor values at anchor phases"
    (let [anchors [[0.0 0.0] [0.5 1.0] [1.0 0.0]]
          handles [[ 0.10  0.40] [-0.10 -0.30]
                   [ 0.10 -0.30] [-0.10  0.40]]
          f       (modulator/modulator->shape-fn
                   {:modulator/type :spline/bezier :modulator/loop :clamp
                    :spline/anchors anchors :spline/handles handles})]
      (doseq [[p v] anchors]
        (is (≈6 v (f p)) (str "at phase " p))))))

(deftest bezier-between-anchors-test
  (testing "bezier value at midpoint phase is strictly between anchor values"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :spline/bezier :modulator/loop :clamp
              :spline/anchors [[0.0 0.0] [1.0 1.0]]
              :spline/handles [[0.1 0.5] [-0.1 0.5]]})]
      (let [v (f 0.5)]
        (is (< 0.0 v 1.0) (str "midpoint value " v " is between 0 and 1"))))))

;; ---------------------------------------------------------------------------
;; :lfo/sine
;; ---------------------------------------------------------------------------

(deftest lfo-sine-known-phases-test
  (testing "lfo/sine at quarter-cycle phases"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :lfo/sine :modulator/loop :loop :lfo/phase-offset 0.0})]
      (is (≈6 0.5 (f 0.0)))    ; sin(0)  = 0 → 0.5
      (is (≈6 1.0 (f 0.25)))   ; sin(π/2) = 1 → 1.0
      (is (≈6 0.5 (f 0.5)))    ; sin(π)  = 0 → 0.5
      (is (≈6 0.0 (f 0.75))))))  ; sin(3π/2) = -1 → 0.0

(deftest lfo-sine-phase-offset-test
  (testing "phase-offset shifts the waveform"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :lfo/sine :modulator/loop :loop :lfo/phase-offset 0.25})]
      (is (≈6 1.0 (f 0.0))))))   ; offset 0.25 puts peak at phase 0

;; ---------------------------------------------------------------------------
;; :env/multi-stage
;; ---------------------------------------------------------------------------

(deftest multi-stage-exact-at-breakpoints-test
  (testing "multi-stage returns exact values at breakpoint times"
    (let [bps [[0.0 0.0] [0.1 1.0] [0.5 0.7] [1.0 0.0]]
          f   (modulator/modulator->shape-fn
               {:modulator/type  :env/multi-stage
                :modulator/time-unit :seconds
                :env/breakpoints bps
                :env/curves      [:linear :linear :linear]})]
      (doseq [[t v] bps]
        (is (≈6 v (f t)) (str "at t=" t))))))

(deftest multi-stage-linear-midpoint-test
  (testing ":linear segment midpoint is the average of the endpoints"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type  :env/multi-stage
              :modulator/time-unit :seconds
              :env/breakpoints [[0.0 0.0] [1.0 1.0]]
              :env/curves      [:linear]})]
      (is (≈ 0.5 (f 0.5))))))

(deftest multi-stage-cosine-midpoint-test
  (testing ":cosine segment midpoint is 0.5 (symmetric)"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type  :env/multi-stage
              :modulator/time-unit :seconds
              :env/breakpoints [[0.0 0.0] [1.0 1.0]]
              :env/curves      [:cosine]})]
      (is (≈6 0.5 (f 0.5))))))

(deftest multi-stage-exponential-below-linear-test
  (testing ":exponential midpoint is below :linear midpoint (slow start)"
    (let [flin (modulator/modulator->shape-fn
                {:modulator/type :env/multi-stage :modulator/time-unit :seconds
                 :env/breakpoints [[0.0 0.0] [1.0 1.0]] :env/curves [:linear]})
          fexp (modulator/modulator->shape-fn
                {:modulator/type :env/multi-stage :modulator/time-unit :seconds
                 :env/breakpoints [[0.0 0.0] [1.0 1.0]] :env/curves [:exponential]})]
      (is (< (fexp 0.5) (flin 0.5))))))

;; ---------------------------------------------------------------------------
;; env->shape-fn (:env/adsr, :env/ar, :env/ahdsr)
;; ---------------------------------------------------------------------------

(deftest env-adsr-shape-test
  (testing "ADSR: attack peak → decay to sustain → hold → release to 0"
    (let [f (modulator/env->shape-fn
             {:modulator/type      :env/adsr
              :modulator/time-unit :seconds
              :env/attack          0.1
              :env/decay           0.2
              :env/sustain         0.6
              :env/release         0.3}
             0.5)]  ; gate-duration
      (is (≈6 0.0 (f 0.0))   "starts at 0")
      (is (≈6 1.0 (f 0.1))   "peak at end of attack")
      (is (≈6 0.6 (f 0.3))   "sustain level after decay")
      (is (≈6 0.6 (f 0.5))   "sustain held at gate-off")
      (is (≈6 0.0 (f 0.8)))))) ; silence after release

(deftest env-ar-shape-test
  (testing "AR: attack to 1.0, holds at 1.0 through gate, releases to 0"
    (let [f (modulator/env->shape-fn
             {:modulator/type      :env/ar
              :modulator/time-unit :seconds
              :env/attack          0.1
              :env/release         0.2}
             0.4)]  ; gate-duration
      (is (≈6 0.0 (f 0.0))   "starts at 0")
      (is (≈6 1.0 (f 0.1))   "peak at end of attack")
      (is (≈6 1.0 (f 0.3))   "held at 1.0 during gate")
      (is (≈6 0.0 (f 0.6)))))) ; silence after release

(deftest env-ahdsr-hold-extends-peak-test
  (testing "AHDSR: hold stage keeps level at 1.0 before decay begins"
    (let [f (modulator/env->shape-fn
             {:modulator/type      :env/ahdsr
              :modulator/time-unit :seconds
              :env/attack          0.1
              :env/hold            0.1
              :env/decay           0.2
              :env/sustain         0.5
              :env/release         0.2}
             0.6)]  ; gate-duration
      (is (≈6 1.0 (f 0.1))   "peak at end of attack")
      (is (≈6 1.0 (f 0.2))   "still at peak during hold")
      (is (≈6 0.5 (f 0.4)))))) ; sustain level after decay

;; ---------------------------------------------------------------------------
;; Loop mode
;; ---------------------------------------------------------------------------

(deftest loop-mode-loop-wraps-test
  (testing ":loop wraps phase — phase 1.5 evaluates same as phase 0.5"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/hold :modulator/loop :loop
              :step/values    [0.0 0.5 1.0]})]
      (is (≈ (f 0.5) (f 1.5))))))

(deftest loop-mode-ping-pong-test
  (testing ":ping-pong mirrors at 1.0 — phase 1.5 same as phase 0.5"
    (let [f (modulator/modulator->shape-fn
             {:modulator/type :step/linear :modulator/loop :ping-pong
              :step/values    [0.0 1.0]})]
      (is (≈6 (f 0.3) (f 1.7)))   ; 1.7 mirrors to 0.3
      (is (≈6 (f 0.5) (f 1.5))))))  ; 1.5 mirrors to 0.5

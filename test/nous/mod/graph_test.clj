; SPDX-License-Identifier: EPL-2.0
(ns nous.mod.graph-test
  "Unit tests for nous.mod.graph — all tests are pure data, no IPC required."
  (:require [clojure.test :refer [deftest is testing]]
            [nous.mod.graph :as g]))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(deftest phasor-test
  (testing "phasor with literal rate"
    (is (= [:phasor 0.5] (g/phasor 0.5))))
  (testing "phasor with sub-expression rate"
    (is (= [:phasor [:param :rate]] (g/phasor (g/param :rate))))))

(deftest param-test
  (testing "keyword param"
    (is (= [:param :rate] (g/param :rate))))
  (testing "string param"
    (is (= [:param "depth"] (g/param "depth")))))

(deftest mod-out-test
  (testing "default field is :cv"
    (is (= [:mod-out :clock :cv] (g/mod-out :clock))))
  (testing "explicit field"
    (is (= [:mod-out :clock :gate] (g/mod-out :clock :gate))))
  (testing "aux field"
    (is (= [:mod-out :lfo :aux] (g/mod-out :lfo :aux)))))

(deftest beat-test
  (is (= [:beat] (g/beat))))

(deftest beat-phase-test
  (testing "literal period"
    (is (= [:beat-phase 4] (g/beat-phase 4))))
  (testing "fractional period"
    (is (= [:beat-phase 0.5] (g/beat-phase 0.5))))
  (testing "param-controlled period"
    (is (= [:beat-phase [:param :period]] (g/beat-phase (g/param :period)))))
  (testing "composes with sin for musically-locked LFO"
    (is (= [:sin [:beat-phase 4]] (g/sin (g/beat-phase 4))))))

;; ---------------------------------------------------------------------------
;; Periodic shapes
;; ---------------------------------------------------------------------------

(deftest sin-test
  (is (= [:sin [:phasor 1.0]] (g/sin (g/phasor 1.0)))))

(deftest cos-test
  (is (= [:cos [:phasor 1.0]] (g/cos (g/phasor 1.0)))))

(deftest tri-test
  (is (= [:tri [:phasor 1.0]] (g/tri (g/phasor 1.0)))))

(deftest saw-test
  (is (= [:saw [:phasor 1.0]] (g/saw (g/phasor 1.0)))))

(deftest square-test
  (testing "default 50% duty cycle"
    (is (= [:square [:phasor 1.0] 0.5] (g/square (g/phasor 1.0)))))
  (testing "explicit width"
    (is (= [:square [:phasor 1.0] 0.3] (g/square (g/phasor 1.0) 0.3))))
  (testing "voltage-controlled width"
    (is (= [:square [:phasor 1.0] [:param :width]]
           (g/square (g/phasor 1.0) (g/param :width))))))

;; ---------------------------------------------------------------------------
;; Math
;; ---------------------------------------------------------------------------

(deftest scale-test
  (is (= [:scale [:sin [:phasor 1.0]] -1.0 1.0]
         (g/scale (g/sin (g/phasor 1.0)) -1.0 1.0))))

(deftest clamp-test
  (is (= [:clamp [:add [:param :x] 0.1] 0.0 1.0]
         (g/clamp (g/add (g/param :x) 0.1) 0.0 1.0))))

(deftest add-test
  (is (= [:add [:param :a] [:param :b]]
         (g/add (g/param :a) (g/param :b)))))

(deftest mul-test
  (is (= [:mul 0.5 [:param :x]]
         (g/mul 0.5 (g/param :x)))))

(deftest neg-test
  (is (= [:neg [:param :x]] (g/neg (g/param :x)))))

(deftest abs-test
  (is (= [:abs [:sin [:phasor 1.0]]]
         (g/abs (g/sin (g/phasor 1.0))))))

(deftest mix-test
  (is (= [:mix [:param :a] [:param :b] 0.5]
         (g/mix (g/param :a) (g/param :b) 0.5)))
  (testing "voltage-controlled mix amount"
    (is (= [:mix [:param :a] [:param :b] [:param :t]]
           (g/mix (g/param :a) (g/param :b) (g/param :t))))))

;; ---------------------------------------------------------------------------
;; Dynamics
;; ---------------------------------------------------------------------------

(deftest slew-test
  (testing "symmetric slew"
    (is (= [:slew [:param :target] 0.1 0.1]
           (g/slew (g/param :target) 0.1))))
  (testing "asymmetric slew"
    (is (= [:slew [:param :target] 0.05 0.2]
           (g/slew (g/param :target) 0.05 0.2))))
  (testing "voltage-controlled slew"
    (is (= [:slew [:param :target] [:param :rise] [:param :fall]]
           (g/slew (g/param :target) (g/param :rise) (g/param :fall))))))

(deftest sample-hold-test
  (is (= [:sample-hold [:phasor 0.1] [:mod-out :clock :gate]]
         (g/sample-hold (g/phasor 0.1) (g/mod-out :clock :gate)))))

;; ---------------------------------------------------------------------------
;; Gate / logic
;; ---------------------------------------------------------------------------

(deftest threshold-test
  (testing "default level 0.5"
    (is (= [:threshold [:phasor 1.0] 0.5]
           (g/threshold (g/phasor 1.0)))))
  (testing "explicit level"
    (is (= [:threshold [:phasor 1.0] 0.7]
           (g/threshold (g/phasor 1.0) 0.7))))
  (testing "voltage-controlled level"
    (is (= [:threshold [:phasor 1.0] [:param :level]]
           (g/threshold (g/phasor 1.0) (g/param :level))))))

(deftest comparator-test
  (is (= [:comparator [:mod-out :a :cv] [:mod-out :b :cv]]
         (g/comparator (g/mod-out :a :cv) (g/mod-out :b :cv)))))

;; ---------------------------------------------------------------------------
;; Composition via threading
;; ---------------------------------------------------------------------------

(deftest threading-composition-test
  (testing "-> composes to correct nested vector"
    (is (= [:scale [:sin [:phasor [:param :rate]]] -1.0 1.0]
           (-> (g/param :rate)
               g/phasor
               g/sin
               (g/scale -1.0 1.0)))))

  (testing "FM: rate modulated by another LFO"
    (is (= [:phasor [:add [:param :carrier] [:mul [:mod-out :mod-lfo :cv] [:param :depth]]]]
           (g/phasor
             (g/add (g/param :carrier)
                    (g/mul (g/mod-out :mod-lfo :cv)
                           (g/param :depth)))))))

  (testing "stepped random: S&H driven by cross-modulator gate"
    (is (= [:sample-hold [:mul [:phasor 0.03] 2.0] [:mod-out :clock :gate]]
           (g/sample-hold (g/mul (g/phasor 0.03) 2.0)
                          (g/mod-out :clock :gate)))))

  (testing "self-referential feedback accumulator"
    (is (= [:clamp [:add [:mod-out :acc :cv] 0.005] 0.0 1.0]
           (g/clamp (g/add (g/mod-out :acc :cv) 0.005) 0.0 1.0)))))

;; ---------------------------------------------------------------------------
;; Multi-output map form
;; ---------------------------------------------------------------------------

(deftest multi-output-map-test
  (testing "map form passes through unchanged"
    (let [graph {:cv   (-> (g/param :rate) g/phasor g/sin)
                 :gate (-> (g/param :rate) g/phasor (g/threshold 0.5))}]
      (is (= [:sin [:phasor [:param :rate]]]   (:cv graph)))
      (is (= [:threshold [:phasor [:param :rate]] 0.5] (:gate graph)))))

  (testing "all four output fields"
    (let [ph (g/phasor 1.0)]
      (is (map? {:cv ph :aux ph :gate (g/threshold ph) :gate2 (g/threshold ph 0.3)})))))

;; ---------------------------------------------------------------------------
;; Graphs are plain Clojure data
;; ---------------------------------------------------------------------------

(deftest graphs-are-data-test
  (testing "graph is a vector — seq-able, countable, associative"
    (let [g (g/scale (g/sin (g/phasor 1.0)) -1.0 1.0)]
      (is (vector? g))
      (is (= :scale (first g)))
      (is (= 4 (count g)))))

  (testing "graph can be stored in a map and retrieved"
    (let [patch {:filter-lfo (-> (g/param :rate) g/phasor g/sin (g/scale -1.0 1.0))}]
      (is (= [:scale [:sin [:phasor [:param :rate]]] -1.0 1.0]
             (:filter-lfo patch)))))

  (testing "graph can be transformed — e.g. change rate"
    (let [template (fn [rate] (-> (g/phasor rate) g/sin (g/scale -1.0 1.0)))
          slow (template 0.5)
          fast (template 4.0)]
      (is (= 0.5 (get-in slow [1 1 1])))
      (is (= 4.0 (get-in fast [1 1 1])))))

  (testing "graph round-trips through pr-str / read-string"
    (let [expr (-> (g/param :rate) g/phasor g/sin (g/scale -1.0 1.0))]
      (is (= expr (read-string (pr-str expr)))))))

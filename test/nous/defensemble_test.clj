; SPDX-License-Identifier: EPL-2.0
(ns nous.defensemble-test
  "Unit tests for nous.defensemble — inter-voice tension monitor."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [nomos.maths.harmonic :as h]
            [nous.defensemble  :as de]
            [nous.core         :as core]
            [nous.ctrl         :as ctrl]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-ctx
  "Build an ensemble context atom directly from options."
  [opts]
  (atom (de/make-ensemble-context opts)))

(defn- set-voice!
  "Set pitch and motion in the ctrl tree for a voice."
  [voice midi motion]
  (ctrl/set! [:harmony :voice-pitch voice] (long midi))
  (ctrl/set! [:harmony :voice-motion voice] (long motion)))

;; ---------------------------------------------------------------------------
;; make-ensemble-context
;; ---------------------------------------------------------------------------

(deftest make-ensemble-context-defaults
  (testing "default thresholds"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :test})]
      (is (= [:bass :soprano] (:voices @ctx)))
      (is (= 6.5 (:consonance-horizon @ctx)))
      (is (= 3.5 (:fusion-threshold @ctx)))
      (is (= 5.5 (:dissonance-threshold @ctx)))
      (is (= false (:monitoring? @ctx)))
      (is (= 0.0 (:last-tension @ctx)))
      (is (= {} (:last-consonance @ctx)))
      (is (= #{} (:last-parallel-pairs @ctx))))))

(deftest make-ensemble-context-custom
  (testing "custom thresholds are stored"
    (let [ctx (make-ctx {:voices              [:a :b :c]
                         :ens-name            :trio
                         :consonance-horizon  8.0
                         :fusion-threshold    2.5
                         :dissonance-threshold 6.0})]
      (is (= [:a :b :c] (:voices @ctx)))
      (is (= 8.0 (:consonance-horizon @ctx)))
      (is (= 2.5 (:fusion-threshold @ctx)))
      (is (= 6.0 (:dissonance-threshold @ctx))))))

;; ---------------------------------------------------------------------------
;; run-update! — two-voice consonance
;; ---------------------------------------------------------------------------

(deftest run-update-unison
  (testing "two voices at unison → tension near 0"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      (let [t (de/ensemble-tension ctx)]
        (is (number? t))
        (is (<= 0.0 t 0.1) "unison gives near-zero tension")))))

(deftest run-update-consonant-interval
  (testing "voices a major sixth apart → moderate tension"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 69 0)  ; major sixth = 9 semitones
      (de/run-update! ctx)
      (let [t (de/ensemble-tension ctx)]
        (is (number? t))
        (is (< 0.0 t 1.0) "non-unison interval produces non-zero tension")))))

(deftest run-update-tension-increases-with-distance
  (testing "wider intervals produce higher tension than narrower"
    (let [duo-narrow (make-ctx {:voices [:a :b] :ens-name :narrow})
          duo-wide   (make-ctx {:voices [:a :b] :ens-name :wide})]
      (set-voice! :a 60 0)
      (set-voice! :b 64 0)   ; major third — narrower
      (de/run-update! duo-narrow)
      (set-voice! :a 60 0)
      (set-voice! :b 71 0)   ; major seventh — wider
      (de/run-update! duo-wide)
      (is (< (de/ensemble-tension duo-narrow)
             (de/ensemble-tension duo-wide))
          "major seventh should be more tense than major third"))))

(deftest run-update-consonance-map-keys
  (testing "consonance map has correct pair key"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 67 0)
      (de/run-update! ctx)
      (let [cons (de/ensemble-consonance ctx)]
        (is (map? cons))
        (is (contains? cons [:bass :soprano]))
        (is (number? (get cons [:bass :soprano])))))))

(deftest run-update-three-voices
  (testing "three voices produce three pair entries"
    (let [ctx (make-ctx {:voices [:a :b :c] :ens-name :trio})]
      (set-voice! :a 60 0)
      (set-voice! :b 64 0)
      (set-voice! :c 67 0)
      (de/run-update! ctx)
      (let [cons (de/ensemble-consonance ctx)]
        (is (= 3 (count cons)) "three pairs for three voices")
        (is (contains? cons [:a :b]))
        (is (contains? cons [:a :c]))
        (is (contains? cons [:b :c]))))))

;; ---------------------------------------------------------------------------
;; run-update! — parallel motion detection
;; ---------------------------------------------------------------------------

(deftest parallel-motion-at-unison-flagged
  (testing "both voices at unison, same direction → flagged"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo
                         :fusion-threshold 3.5})]
      ;; Unison: H = 0 < 3.5, both moving up
      (set-voice! :bass    60 1)
      (set-voice! :soprano 60 1)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (set? pars))
        (is (contains? pars [:bass :soprano]) "unison with parallel motion should be flagged")))))

(deftest contrary-motion-at-unison-not-flagged
  (testing "voices at unison but contrary motion → not flagged"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo
                         :fusion-threshold 3.5})]
      ;; Unison: H = 0 < 3.5, but contrary motion (bass up, soprano down)
      (set-voice! :bass    60  1)
      (set-voice! :soprano 60 -1)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (not (contains? pars [:bass :soprano]))
            "contrary motion should not be flagged even at unison")))))

(deftest stationary-motion-at-unison-not-flagged
  (testing "voices at unison but stationary → not flagged"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo
                         :fusion-threshold 3.5})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (not (contains? pars [:bass :soprano]))
            "stationary motion (dir=0) is not parallel motion")))))

(deftest parallel-motion-penalty-increases-tension
  (testing "parallel motion to fusion zone raises tension"
    (let [ctx-no-par  (make-ctx {:voices [:bass :soprano] :ens-name :no-par})
          ctx-par     (make-ctx {:voices [:bass :soprano] :ens-name :par
                                 :fusion-threshold 3.5})]
      ;; Same pitches but contrary vs parallel motion
      (set-voice! :bass    60  1)
      (set-voice! :soprano 60 -1)  ; contrary
      (de/run-update! ctx-no-par)

      (set-voice! :bass    60 1)
      (set-voice! :soprano 60 1)   ; parallel
      (de/run-update! ctx-par)

      (is (<= (de/ensemble-tension ctx-no-par)
              (de/ensemble-tension ctx-par))
          "parallel motion should not decrease tension vs contrary motion"))))

;; ---------------------------------------------------------------------------
;; defensemble macro
;; ---------------------------------------------------------------------------

(deftest defensemble-macro-creates-atom
  (testing "defensemble creates a var holding an atom"
    (de/defensemble test-ensemble
      :voices [:v1 :v2]
      :ens-name :test-ensemble)
    (is (instance? clojure.lang.Atom test-ensemble))
    (is (= [:v1 :v2] (:voices @test-ensemble)))))

(deftest defensemble-macro-registers
  (testing "defensemble registers in the registry"
    (de/defensemble reg-ensemble
      :voices [:v1 :v2]
      :ens-name :reg-ensemble)
    (is (contains? (set (de/ensemble-names)) :reg-ensemble))))

(deftest defensemble-macro-starts-monitoring
  (testing "defensemble sets monitoring? true"
    (de/defensemble mon-ensemble
      :voices [:v1 :v2]
      :ens-name :mon-ensemble)
    (is (true? (:monitoring? @mon-ensemble)))))

;; ---------------------------------------------------------------------------
;; Monitor lifecycle
;; ---------------------------------------------------------------------------

(deftest stop-monitor-sets-flag
  (testing "stop-monitor! sets monitoring? to false"
    (let [ctx (make-ctx {:voices [:a :b] :ens-name :lifecycle-test})]
      (de/start-monitor! ctx)
      (is (true? (:monitoring? @ctx)))
      (de/stop-monitor! ctx)
      (is (false? (:monitoring? @ctx))))))

(deftest start-monitor-sets-flag
  (testing "start-monitor! sets monitoring? to true"
    (let [ctx (make-ctx {:voices [:a :b] :ens-name :start-test})]
      (is (false? (:monitoring? @ctx)))
      (de/start-monitor! ctx)
      (is (true? (:monitoring? @ctx)))
      (de/stop-monitor! ctx))))

;; ---------------------------------------------------------------------------
;; Inspection functions
;; ---------------------------------------------------------------------------

(deftest ensemble-names-includes-registered
  (testing "ensemble-names returns registered names"
    (de/defensemble inspect-ensemble
      :voices [:x :y]
      :ens-name :inspect-ensemble)
    (let [names (de/ensemble-names)]
      (is (seq? names))
      (is (some #{:inspect-ensemble} names)))))

(deftest inspection-functions-return-cached-state
  (testing "tension/consonance/parallel-pairs return cached values"
    (let [ctx (make-ctx {:voices [:p :q] :ens-name :cache-test})]
      (set-voice! :p 60 0)
      (set-voice! :q 67 0)
      (de/run-update! ctx)
      (is (number? (de/ensemble-tension ctx)))
      (is (map? (de/ensemble-consonance ctx)))
      (is (set? (de/ensemble-parallel-pairs ctx))))))

(deftest tension-bounded
  (testing "tension is always in [0,1]"
    (let [ctx (make-ctx {:voices [:a :b] :ens-name :bound-test})]
      (doseq [[m1 m2] [[0 127] [60 60] [48 72] [55 73]]]
        (set-voice! :a m1 1)
        (set-voice! :b m2 1)
        (de/run-update! ctx)
        (let [t (de/ensemble-tension ctx)]
          (is (<= 0.0 t 1.0)
              (str "tension out of [0,1] for midi pair " m1 "/" m2)))))))

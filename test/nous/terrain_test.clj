; SPDX-License-Identifier: EPL-2.0
(ns nous.terrain-test
  (:require [clojure.test :refer [deftest is testing]]
            [ctrl-tree.core :as ct]
            [nous.seq     :as sq]
            [nous.terrain :as terrain]))

(def ^:private trunk
  [{:pitch/midi 60 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
   {:pitch/midi 64 :dur/beats 1/4 :gate/on? true :gate/len 0.5}
   {:pitch/midi 67 :dur/beats 1/2 :gate/on? true :gate/len 0.8}
   {:pitch/midi 65 :dur/beats 1/4 :gate/on? true :gate/len 0.5}])

;; Top-level so the def'd gen var resolves at compile time. defterrain seeds
;; [:terrain :tw-gen :y|:z] to 0.0 and registers ctrl-tree watches.
(terrain/defterrain tw-gen :trunk trunk :max-depth 2)

;; ---------------------------------------------------------------------------
;; z->path
;; ---------------------------------------------------------------------------

(deftest z->path-test
  (testing "z=0 → all-zeros path"
    (is (= [0 0 0] (terrain/z->path 0.0 3 3))))
  (testing "depth=0 → empty path"
    (is (= [] (terrain/z->path 0.7 3 0))))
  (testing "z=0.5 with 2 branches → first digit 1"
    (let [[d0 & _] (terrain/z->path 0.5 2 3)]
      (is (= 1 d0))))
  (testing "z=0.2 with 3 branches → first digit 0"
    (let [[d0 & _] (terrain/z->path 0.2 3 3)]
      (is (= 0 d0))))
  (testing "z=0.4 with 3 branches → first digit 1"
    (let [[d0 & _] (terrain/z->path 0.4 3 3)]
      (is (= 1 d0))))
  (testing "z=0.7 with 3 branches → first digit 2"
    (let [[d0 & _] (terrain/z->path 0.7 3 3)]
      (is (= 2 d0))))
  (testing "no digit exceeds n-1"
    (doseq [z (range 0 1 0.07)]
      (let [path (terrain/z->path z 3 4)]
        (is (every? #(< % 3) path))))))

;; ---------------------------------------------------------------------------
;; terrain-seq
;; ---------------------------------------------------------------------------

(deftest terrain-seq-test
  (testing "y=0 → trunk returned unchanged"
    (is (= trunk (terrain/terrain-seq trunk [:reverse] 4 0.0 0.0 {}))))
  (testing "sequence length preserved"
    (doseq [y (range 0 1 0.25)
            z (range 0 1 0.25)]
      (is (= (count trunk)
             (count (terrain/terrain-seq trunk [:reverse :inverse] 4 y z {}))))))
  (testing "y≈1 with single :reverse transform → reversed trunk"
    (is (= (vec (reverse trunk))
           (terrain/terrain-seq trunk [:reverse] 1 0.99 0.0 {}))))
  (testing "no transforms → trunk regardless of y/z"
    (is (= trunk (terrain/terrain-seq trunk [] 4 0.7 0.5 {})))))

;; ---------------------------------------------------------------------------
;; terrain-step
;; ---------------------------------------------------------------------------

(deftest terrain-step-test
  (testing "x=0, y=0, z=0 → first trunk step (no transform applied)"
    (let [result (terrain/terrain-step trunk [] 4 0.0 0.0 0.0 {})]
      (is (= 60 (:pitch/midi result)))))
  (testing "pitch is always a valid MIDI integer"
    (doseq [x (range 0 1 0.25)
            y (range 0 1 0.25)
            z (range 0 1 0.25)]
      (let [r (terrain/terrain-step trunk [:reverse :inverse] 4 x y z {})]
        (is (integer? (:pitch/midi r))
            (str "x=" x " y=" y " z=" z " → " (:pitch/midi r)))
        (is (<= 0 (:pitch/midi r) 127)))))
  (testing "dur/beats is positive"
    (doseq [x (range 0 1 0.25)
            y (range 0 1 0.25)
            z (range 0 1 0.25)]
      (let [r (terrain/terrain-step trunk [:reverse :inverse] 4 x y z {})]
        (is (pos? (:dur/beats r))))))
  (testing "x steps address different positions"
    (let [step0 (terrain/terrain-step trunk [] 4 0.0   0.0 0.0 {})
          step1 (terrain/terrain-step trunk [] 4 0.25  0.0 0.0 {})
          step2 (terrain/terrain-step trunk [] 4 0.5   0.0 0.0 {})
          step3 (terrain/terrain-step trunk [] 4 0.75  0.0 0.0 {})]
      (is (= [60 64 67 65]
             (mapv :pitch/midi [step0 step1 step2 step3]))))))

;; ---------------------------------------------------------------------------
;; next-terrain-step! and X advancement
;; ---------------------------------------------------------------------------

(deftest next-terrain-step!-test
  (testing "returns a non-nil step map with :pitch/midi"
    (let [ctx (atom (terrain/make-terrain-context :t-test-a
                                                   {:trunk trunk :transforms [:reverse] :max-depth 2}))
          step (terrain/next-terrain-step! ctx)]
      (is (some? step))
      (is (contains? step :pitch/midi))))
  (testing "x advances by 1/N after each call"
    (let [ctx (atom (terrain/make-terrain-context :t-test-b
                                                   {:trunk trunk :transforms [:reverse] :max-depth 2}))
          n   (count trunk)
          _   (terrain/next-terrain-step! ctx)
          x0  (:x @ctx)
          _   (terrain/next-terrain-step! ctx)
          x1  (:x @ctx)]
      (is (< (Math/abs (- x1 (+ x0 (/ 1.0 n)))) 1e-9)
          (str "expected x0+" (/ 1.0 n) " got " x1))))
  (testing "x wraps after N calls from x=0"
    (let [ctx (atom (terrain/make-terrain-context :t-test-c
                                                   {:trunk trunk :transforms [:reverse] :max-depth 2}))
          n   (count trunk)]
      (dotimes [_ n] (terrain/next-terrain-step! ctx))
      (is (< (:x @ctx) 1e-9)))))

;; ---------------------------------------------------------------------------
;; IStepSequencer contract
;; ---------------------------------------------------------------------------

(deftest terrain-seq-protocol-test
  (let [ctx-atom (atom (terrain/make-terrain-context :tseq-test
                                                      {:trunk      trunk
                                                       :transforms [:reverse]
                                                       :max-depth  2}))
        ts       (terrain/make-terrain-seq ctx-atom)]
    (testing "seq-cycle-length is nil (infinite source)"
      (is (nil? (sq/seq-cycle-length ts))))
    (testing "next-event returns :event and :beats"
      (let [ev (sq/next-event ts)]
        (is (contains? ev :event))
        (is (contains? ev :beats))
        (is (pos? (:beats ev)))))
    (testing "next-event :event includes :mod/velocity"
      (let [{:keys [event]} (sq/next-event ts)]
        (when event
          (is (= 100 (:mod/velocity event))))))
    (testing "custom velocity propagates"
      (let [ts2 (terrain/make-terrain-seq ctx-atom :vel 80)
            {:keys [event]} (sq/next-event ts2)]
        (when event
          (is (= 80 (:mod/velocity event))))))))

;; ---------------------------------------------------------------------------
;; defterrain ctrl-tree watch wiring (regression: this path used to throw an
;; ArityException — defterrain called the 3-arg nous.ctrl/watch! with 2 args)
;; ---------------------------------------------------------------------------

(deftest defterrain-watch-updates-context-test
  (testing "a ct/ctrl-write! to [:terrain <name> :y|:z] updates the gen context atom"
    (ct/ctrl-write! [:terrain :tw-gen :y] 0.6)
    (ct/ctrl-write! [:terrain :tw-gen :z] 0.35)
    (is (= 0.6  (:y @tw-gen)) "y phasor tracked from ctrl-tree")
    (is (= 0.35 (:z @tw-gen)) "z phasor tracked from ctrl-tree")))

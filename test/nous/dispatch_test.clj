; SPDX-License-Identifier: EPL-2.0
(ns nous.dispatch-test
  "Tests for nous.dispatch/dispatch-binding! — the shared scale-and-emit core."
  (:require [clojure.test  :refer [deftest is testing]]
            [nous.dispatch :as dispatch]
            [nous.kairos   :as kairos]))

(defn- capture-cc
  "Run `f` with kairos/send-cc! spied; return the [channel controller value]
  tuples it received."
  [f]
  (let [calls (atom [])]
    (with-redefs [kairos/send-cc! (fn [ch cc v & _] (swap! calls conj [ch cc v]))]
      (f))
    @calls))

(deftest midi-cc-scales-and-emits-test
  (testing ":midi-cc scales (value−lo)/(hi−lo)·127 and sends one CC"
    (let [calls (capture-cc
                  #(dispatch/dispatch-binding!
                     {:type :midi-cc :channel 1 :cc-num 74 :range [0.0 1.0]} 0.7))]
      (is (= [[1 74 89]] calls) "0.7 over [0,1] → round(0.7·127)=89 on ch1 cc74"))))

(deftest midi-cc-clamps-out-of-range-test
  (testing ":midi-cc clamps above/below the 0–127 wire range"
    (is (= [[1 74 127]] (capture-cc
                          #(dispatch/dispatch-binding!
                             {:type :midi-cc :channel 1 :cc-num 74 :range [0.0 1.0]} 2.0))))
    (is (= [[1 74 0]] (capture-cc
                        #(dispatch/dispatch-binding!
                           {:type :midi-cc :channel 1 :cc-num 74 :range [0.0 1.0]} -1.0))))))

(deftest midi-cc-default-range-test
  (testing ":midi-cc with no :range treats the value as already 0–127"
    (is (= [[1 74 64]] (capture-cc
                         #(dispatch/dispatch-binding!
                            {:type :midi-cc :channel 1 :cc-num 74} 64))))))

(deftest midi-nrpn-decomposes-to-four-cc-test
  (testing ":midi-nrpn sends CC 99/98 (param) then 6/38 (data), 14-bit encoding"
    (let [calls (capture-cc
                  #(dispatch/dispatch-binding!
                     {:type :midi-nrpn :channel 2 :nrpn 1000 :bits 14 :raw true} 5000))]
      ;; nrpn 1000 → param-msb (1000>>7)=7, param-lsb (1000&127)=104
      ;; value 5000 (raw) → data-msb (5000>>7)=39, data-lsb (5000&127)=8
      (is (= [[2 99 7] [2 98 104] [2 6 39] [2 38 8]] calls)))))

(deftest midi-nrpn-raw-bypasses-range-scaling-test
  (testing ":raw true uses the value directly (no range scaling)"
    (let [raw    (capture-cc
                   #(dispatch/dispatch-binding!
                      {:type :midi-nrpn :channel 1 :nrpn 0 :raw true} 8192))
          scaled (capture-cc
                   #(dispatch/dispatch-binding!
                      {:type :midi-nrpn :channel 1 :nrpn 0 :range [0.0 1.0]} 0.5))]
      ;; raw 8192 → data-msb 64, data-lsb 0
      (is (= [1 6 64] (nth raw 2)))
      (is (= [1 38 0] (nth raw 3)))
      ;; scaled 0.5 over [0,1] → round(0.5·16383)=8192 → same wire, but via scaling
      (is (= [1 6 64] (nth scaled 2))))))

(deftest unknown-binding-type-is-noop-test
  (testing "an unhandled binding type emits nothing (caller logs/skips)"
    (is (= [] (capture-cc
                #(dispatch/dispatch-binding! {:type :osc :addr "/x"} 1.0))))))

; SPDX-License-Identifier: EPL-2.0
(ns nous.binding-registry-test
  "Tests for nous.binding-registry — the mount's Clojure-side binding store."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.binding-registry :as breg]))

(defn- clean [f] (breg/clear!) (try (f) (finally (breg/clear!))))
(use-fixtures :each clean)

(deftest register-node-and-node-info-test
  (testing "register-node! stores type + node-meta; node-info reads it back"
    (breg/register-node! [:dev :cutoff] :type :enum :node-meta {:range [0 127] :values [:a :b]})
    (let [n (breg/node-info [:dev :cutoff])]
      (is (= :enum (:type n)))
      (is (= {:range [0 127] :values [:a :b]} (:node-meta n)))
      (is (= [] (:bindings n)) "no bindings until bind!"))
    (is (nil? (breg/node-info [:dev :absent])))))

(deftest bind-and-bindings-for-test
  (testing "bind! adds a binding; bindings-for returns it (sorted by priority)"
    (breg/bind! [:dev :cutoff] {:type :midi-cc :channel 1 :cc-num 74} :priority 20)
    (breg/bind! [:dev :cutoff] {:type :midi-nrpn :channel 1 :nrpn 5} :priority 10)
    (let [bs (breg/bindings-for [:dev :cutoff])]
      (is (= 2 (count bs)))
      (is (= 10 (:priority (first bs))) "sorted by priority ascending")
      (is (= :midi-cc (:type (second bs))))
      (is (= 20 (:priority (second bs)))))
    (is (= [] (breg/bindings-for [:dev :absent])) "empty for unknown path")))

(deftest bind-same-priority-conflict-raises-test
  (testing "bind! raises on a same-priority conflict (parity with nous.ctrl/bind!)"
    (breg/bind! [:dev :x] {:type :midi-cc :channel 1 :cc-num 1} :priority 20)
    (is (thrown? clojure.lang.ExceptionInfo
                 (breg/bind! [:dev :x] {:type :midi-cc :channel 1 :cc-num 2} :priority 20)))))

(deftest unbind-by-priority-and-all-test
  (testing "unbind! removes by priority or all"
    (breg/bind! [:dev :y] {:type :midi-cc :cc-num 1} :priority 20)
    (breg/bind! [:dev :y] {:type :midi-nrpn :nrpn 1} :priority 21)
    (breg/unbind! [:dev :y] 20)
    (is (= [21] (mapv :priority (breg/bindings-for [:dev :y]))) "priority-20 removed")
    (breg/unbind! [:dev :y] :all)
    (is (= [] (breg/bindings-for [:dev :y])) ":all clears the rest")))

(deftest unregister-path-drops-entry-test
  (testing "unregister-path! removes the whole node entry"
    (breg/register-node! [:dev :z] :type :int)
    (breg/bind! [:dev :z] {:type :midi-cc :cc-num 1} :priority 20)
    (breg/unregister-path! [:dev :z])
    (is (nil? (breg/node-info [:dev :z])))
    (is (= [] (breg/bindings-for [:dev :z])))))

(deftest register-node-preserves-existing-bindings-test
  (testing "re-register-node! updates type/meta but keeps bindings"
    (breg/bind! [:dev :w] {:type :midi-cc :cc-num 1} :priority 20)
    (breg/register-node! [:dev :w] :type :float :node-meta {:range [0.0 1.0]})
    (is (= :float (:type (breg/node-info [:dev :w]))))
    (is (= 1 (count (breg/bindings-for [:dev :w]))) "binding preserved")))

(deftest bindings-by-type-test
  (testing "bindings-by-type returns [path binding] for every matching binding"
    (breg/bind! [:kbd :pitch]    {:type :midi-device-input :device :ks :source :notes} :priority 20)
    (breg/bind! [:kbd :pressure] {:type :midi-device-input :device :ks :source :strip} :priority 20)
    (breg/bind! [:synth :cutoff] {:type :midi-cc :cc-num 74} :priority 20)
    (let [found (breg/bindings-by-type :midi-device-input)]
      (is (= #{[:kbd :pitch] [:kbd :pressure]} (set (map first found)))
          "exactly the two input paths, not the :midi-cc one"))
    (is (empty? (breg/bindings-by-type :no-such-type)) "empty for an absent type")))

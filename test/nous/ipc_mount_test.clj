; SPDX-License-Identifier: EPL-2.0
(ns nous.ipc-mount-test
  "End-to-end test for nous.ipc-mount — a ctrl-tree write to a mounted path
  dispatches the path's bindings to nomos-rt."
  (:require [clojure.test    :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core  :as ct]
            [ctrl-tree.refs  :as refs]
            [nous.core       :as core]
            [nous.ctrl       :as ctrl]
            [nous.ipc-mount  :as ipc-mount]
            [nous.kairos     :as kairos]))

;; Distinct paths per test — nous.ctrl's system-state (where bindings live)
;; persists across core/start!/stop!, so reusing a path would collide on the
;; priority-20 binding and leak dispatch across tests.
(defn- with-system [f]
  (core/start! :no-log true)
  (dosync (alter refs/mount-table assoc [:ipc-test] (ipc-mount/ipc-mount)))
  (try (f)
       (finally
         (dosync (alter refs/mount-table dissoc [:ipc-test]))
         (dosync (alter refs/tree-state
                        #(apply dissoc % [[:ipc-test :cc1] [:ipc-test :cc2] [:ipc-test :cc3]])))
         (core/stop!))))

(use-fixtures :each with-system)

(deftest ctrl-tree-write-dispatches-binding-test
  (testing "a ctrl-tree write to a mounted path emits the bound CC to nomos-rt"
    (ctrl/defnode! [:ipc-test :cc1] :type :float :node-meta {:range [0.0 1.0]} :value 0.0)
    (ctrl/bind! [:ipc-test :cc1] {:type :midi-cc :channel 1 :cc-num 74 :range [0.0 1.0]})
    (let [calls (atom [])]
      (with-redefs [kairos/connected? (constantly true)
                    kairos/send-cc!   (fn [ch cc v & _] (swap! calls conj [ch cc v]))]
        (ct/ctrl-write! [:ipc-test :cc1] 0.5))
      (is (= [[1 74 64]] @calls)
          "ct/ctrl-write! → IpcMount → dispatch-binding! → send-cc! (0.5→64)"))))

(deftest no-dispatch-when-disconnected-test
  (testing "the mount does not emit when the transport is disconnected"
    (ctrl/defnode! [:ipc-test :cc2] :type :float :value 0.0)
    (ctrl/bind! [:ipc-test :cc2] {:type :midi-cc :channel 1 :cc-num 74})
    (let [calls (atom [])]
      (with-redefs [kairos/connected? (constantly false)
                    kairos/send-cc!   (fn [& _] (swap! calls conj :sent))]
        (ct/ctrl-write! [:ipc-test :cc2] 100))
      (is (= [] @calls) "no send when kairos disconnected"))))

(deftest no-binding-no-dispatch-test
  (testing "a mounted path with no binding dispatches nothing (no error)"
    (let [calls (atom [])]
      (with-redefs [kairos/connected? (constantly true)
                    kairos/send-cc!   (fn [& _] (swap! calls conj :sent))]
        (ct/ctrl-write! [:ipc-test :cc3] 0.5))
      (is (= [] @calls)))))

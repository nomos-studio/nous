; SPDX-License-Identifier: EPL-2.0
(ns nous.txlog-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.txlog-store :as tx]
            [ctrl-tree.core  :as ct]
            [ctrl-tree.refs  :as refs]))

(defn- with-txlog [f]
  (tx/start!)
  (try (f) (finally (tx/stop!))))

(defn- clean-mount-table [f]
  (dosync (ref-set refs/mount-table {}))
  (f)
  (dosync (ref-set refs/mount-table {})))

(use-fixtures :each clean-mount-table with-txlog)

(deftest start-stop-lifecycle
  (testing "running? reflects start!/stop! state"
    (is (tx/running?))
    (tx/stop!)
    (is (not (tx/running?)))
    (tx/start!)
    (is (tx/running?))))

(deftest current-beat-increases-over-time
  (testing "current-beat advances monotonically"
    (let [b1 (tx/current-beat)
          _  (Thread/sleep 50)
          b2 (tx/current-beat)]
      (is (> b2 b1)))))

(deftest recent-entries-empty-before-writes
  (testing "no entries before any ctrl-write!"
    (is (= [] (tx/recent-entries)))))

(deftest recent-entries-populated-after-ctrl-write
  (testing "ctrl-write! with a mounted txlog produces a recent entry"
    ;; ctrl-tree.txlog uses the sink installed by tx/start!
    ;; We need at least one mount entry for ctrl-write! to trigger the sink.
    ;; The txlog sink fires regardless of mount — it uses core/ctrl-write!'s result.
    (ct/ctrl-write! [:test :key] "hello")
    (let [entries (tx/recent-entries)]
      (is (seq entries))
      (let [e (first entries)]
        (is (= [:test :key] (:path e)))
        (is (= "hello" (:after e)))))))

(deftest recent-entries-respects-limit
  (testing "recent-entries returns at most n entries"
    (dotimes [i 10] (ct/ctrl-write! [:test (keyword (str "k" i))] i))
    (is (<= (count (tx/recent-entries 3)) 3))))

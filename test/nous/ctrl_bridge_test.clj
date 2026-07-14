; SPDX-License-Identifier: EPL-2.0
(ns nous.ctrl-bridge-test
  "Tests for nous.ctrl-bridge — store-agnostic access over nous.ctrl + ctrl-tree."
  (:require [clojure.test    :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core  :as ct]
            [ctrl-tree.refs  :as refs]
            [nous.core       :as core]
            [nous.ctrl       :as ctrl]
            [nous.ctrl-bridge :as bridge]))

(defn- with-system [f]
  (core/start! :no-log true)
  (try (f)
       (finally
         (core/stop!)
         ;; tree-state is a global STM ref; drop the keys these tests write.
         (dosync (alter refs/tree-state
                        #(apply dissoc % [[:bridge-test/ct]
                                          [:bridge-test/routed]]))))))

(use-fixtures :each with-system)

(deftest read-node-prefers-nous-ctrl-typed-node-test
  (testing "read-node returns the nous.ctrl typed node (value + type + meta)"
    (ctrl/defnode! [:bridge-test/nc] :type :float
                   :node-meta {:range [0.0 1.0]} :value 0.5)
    (let [n (bridge/read-node [:bridge-test/nc])]
      (is (= 0.5 (:value n)))
      (is (= :float (:type n)))
      (is (= {:range [0.0 1.0]} (:node-meta n))))))

(deftest read-node-falls-back-to-ctrl-tree-test
  (testing "read-node serves a ctrl-tree-only path with nil type / empty meta"
    (ct/ctrl-write! [:bridge-test/ct] 0.9)
    (let [n (bridge/read-node [:bridge-test/ct])]
      (is (= 0.9 (:value n)))
      (is (nil? (:type n)))
      (is (= {} (:node-meta n))))))

(deftest read-node-nil-when-absent-test
  (testing "read-node returns nil for a path in neither store"
    (is (nil? (bridge/read-node [:bridge-test/nope])))))

(deftest read-value-returns-value-only-test
  (testing "read-value returns just the value from either store"
    (ct/ctrl-write! [:bridge-test/ct] 3)
    (is (= 3 (bridge/read-value [:bridge-test/ct])))
    (is (nil? (bridge/read-value [:bridge-test/nope])))))

(deftest all-entries-and-snapshot-union-both-stores-test
  (testing "all-entries + snapshot include nodes from both stores"
    ;; Distinct nous.ctrl path — system-state persists across core/start!/stop!,
    ;; and defnode! preserves an existing value, so reusing a path would collide.
    (ctrl/defnode! [:bridge-test/union-nc] :type :int :value 7)
    (ct/ctrl-write! [:bridge-test/ct] 9)
    (let [paths (into #{} (map :path) (bridge/all-entries))
          snap  (bridge/snapshot)]
      (is (contains? paths [:bridge-test/union-nc]))
      (is (contains? paths [:bridge-test/ct]))
      (is (= 7 (get snap [:bridge-test/union-nc])))
      (is (= 9 (get snap [:bridge-test/ct]))))))

(deftest write-any-routes-by-ownership-test
  (testing "write-any writes ctrl-tree when the path lives there, else nous.ctrl"
    ;; Path already on ctrl-tree → write ctrl-tree.
    (ct/ctrl-write! [:bridge-test/ct] 1)
    (bridge/write-any [:bridge-test/ct] 2)
    (is (= 2 (ct/ctrl-read [:bridge-test/ct])) "ctrl-tree path routed to ctrl-tree")
    (is (nil? (ctrl/get [:bridge-test/ct])) "not written to nous.ctrl")
    ;; Brand-new path → nous.ctrl (legacy default).
    (bridge/write-any [:bridge-test/routed] 42)
    (is (= 42 (ctrl/get [:bridge-test/routed])) "new path routed to nous.ctrl")
    (is (nil? (ct/ctrl-read [:bridge-test/routed])) "not written to ctrl-tree")))

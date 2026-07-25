; SPDX-License-Identifier: EPL-2.0
(ns nous.ctrl-bridge-test
  "Tests for nous.ctrl-bridge — store-agnostic access over nous.ctrl + ctrl-tree."
  (:require [clojure.test    :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core  :as ct]
            [ctrl-tree.refs  :as refs]
            [nous.binding-registry :as breg]
            [nous.core       :as core]
            [nous.ctrl       :as ctrl]
            [nous.ctrl-bridge :as bridge]))

(defn- with-system [f]
  (core/start! :no-log true)
  (try (f)
       (finally
         (core/stop!)
         (breg/clear!)   ; binding-registry is global; clear leaked entries
         ;; tree-state is a global STM ref; drop the keys these tests write.
         (dosync (alter refs/tree-state
                        #(apply dissoc % [[:bridge-test/ct]
                                          [:bridge-test/routed]
                                          [:bridge-test/mrg]]))))))

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

(deftest read-node-surfaces-registry-node-test
  (testing "read-node serves a registry-declared node with no value yet (Finding 2)"
    (breg/register-node! [:bridge-test/reg] :type :float :node-meta {:range [0 1]})
    (let [n (bridge/read-node [:bridge-test/reg])]
      (is (some? n) "declared-but-unwritten registry node is visible, not nil (was a 404)")
      (is (nil? (:value n)) "no value written yet")
      (is (= :float (:type n)))
      (is (= {:range [0 1]} (:node-meta n))))))

(deftest read-node-merges-value-and-registry-meta-test
  (testing "read-node merges ctrl-tree value with binding-registry type/meta"
    (breg/register-node! [:bridge-test/mrg] :type :int :node-meta {:range [0 127]})
    (ct/ctrl-write! [:bridge-test/mrg] 64)
    (let [n (bridge/read-node [:bridge-test/mrg])]
      (is (= 64 (:value n)) "value from ctrl-tree")
      (is (= :int (:type n)) "type from binding-registry")
      (is (= {:range [0 127]} (:node-meta n))))))

(deftest read-node-nil-when-absent-test
  (testing "read-node returns nil for a path in no store"
    (is (nil? (bridge/read-node [:bridge-test/nope])))))

(deftest read-value-returns-value-only-test
  (testing "read-value returns just the value from either store"
    (ct/ctrl-write! [:bridge-test/ct] 3)
    (is (= 3 (bridge/read-value [:bridge-test/ct])))
    (is (nil? (bridge/read-value [:bridge-test/nope])))))

(deftest all-entries-and-snapshot-union-all-stores-test
  (testing "all-entries + snapshot include nodes from nous.ctrl, ctrl-tree, and registry"
    ;; Distinct nous.ctrl path — system-state persists across core/start!/stop!,
    ;; and defnode! preserves an existing value, so reusing a path would collide.
    (ctrl/defnode! [:bridge-test/union-nc] :type :int :value 7)
    (ct/ctrl-write! [:bridge-test/ct] 9)
    (breg/register-node! [:bridge-test/reg] :type :float)   ; registry-only, no value
    (let [entries (bridge/all-entries)
          paths   (into #{} (map :path) entries)
          snap    (bridge/snapshot)]
      (is (contains? paths [:bridge-test/union-nc]) "nous.ctrl node present")
      (is (contains? paths [:bridge-test/ct])       "ctrl-tree path present")
      (is (contains? paths [:bridge-test/reg])      "registry-only node present (Finding 2)")
      (is (= 7 (get snap [:bridge-test/union-nc])))
      (is (= 9 (get snap [:bridge-test/ct])))
      (is (= :float (:type (first (filter #(= [:bridge-test/reg] (:path %)) entries))))
          "registry node carries its type in all-entries"))))

(deftest write-any-routes-by-ownership-test
  (testing "write-any routes to the owning store; NEW paths default to ctrl-tree"
    ;; Path already on ctrl-tree → write ctrl-tree.
    (ct/ctrl-write! [:bridge-test/ct] 1)
    (bridge/write-any [:bridge-test/ct] 2)
    (is (= 2 (ct/ctrl-read [:bridge-test/ct])) "ctrl-tree path routed to ctrl-tree")
    (is (nil? (ctrl/get [:bridge-test/ct])) "not written to nous.ctrl")
    ;; Existing nous.ctrl node → updated in place, not migrated to ctrl-tree.
    (ctrl/set! [:bridge-test/legacy] 10)
    (bridge/write-any [:bridge-test/legacy] 11)
    (is (= 11 (ctrl/get [:bridge-test/legacy])) "existing legacy path stays on nous.ctrl")
    (is (nil? (ct/ctrl-read [:bridge-test/legacy])) "not copied to ctrl-tree")
    ;; Brand-new path → ctrl-tree (authority rule 1), NOT nous.ctrl.
    (bridge/write-any [:bridge-test/routed] 42)
    (is (= 42 (ct/ctrl-read [:bridge-test/routed])) "new path routed to ctrl-tree")
    (is (nil? (ctrl/get [:bridge-test/routed])) "not written to nous.ctrl")))

; SPDX-License-Identifier: EPL-2.0
(ns nous.ctrl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.core  :as core]
            [nous.ctrl  :as ctrl]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh system state around each test
;; ---------------------------------------------------------------------------

(defn- with-system [f]
  (core/start! :bpm 120)
  (try
    (f)
    (finally
      (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; set! / get
;; ---------------------------------------------------------------------------

(deftest set-get-test
  (testing "set! and get round-trip"
    (ctrl/set! [:filter/cutoff] 0.7)
    (is (= 0.7 (ctrl/get [:filter/cutoff]))))

  (testing "get returns nil for unknown path"
    (is (nil? (ctrl/get [:does/not :exist]))))

  (testing "set! on a nested path"
    (ctrl/set! [:loops :bass :velocity] 80)
    (is (= 80 (ctrl/get [:loops :bass :velocity]))))

  (testing "set! overwrites existing value"
    (ctrl/set! [:my/param] :a)
    (ctrl/set! [:my/param] :b)
    (is (= :b (ctrl/get [:my/param]))))

  (testing "set! preserves existing type and bindings"
    (ctrl/defnode! [:typed/param] :type :int :value 10)
    (ctrl/set! [:typed/param] 20)
    (is (= 20 (ctrl/get [:typed/param])))
    (is (= :int (:type (ctrl/node-info [:typed/param]))))))

;; ---------------------------------------------------------------------------
;; defnode!
;; ---------------------------------------------------------------------------

(deftest all-nodes-includes-node-meta-test
  (testing "all-nodes returns :node-meta for each node"
    (ctrl/defnode! [:all-nodes-test/param] :type :float
                   :node-meta {:range [0.0 1.0]} :value 0.5)
    (let [nodes (ctrl/all-nodes)
          entry (first (filter #(= [:all-nodes-test/param] (:path %)) nodes))]
      (is (some? entry) "node present in all-nodes output")
      (is (= {:range [0.0 1.0]} (:node-meta entry)))
      (is (= :float (:type entry)))
      (is (= 0.5 (:value entry)))))

  (testing "all-nodes returns empty :node-meta map for nodes without declared meta"
    (ctrl/set! [:all-nodes-test/plain] 42)
    (let [nodes (ctrl/all-nodes)
          entry (first (filter #(= [:all-nodes-test/plain] (:path %)) nodes))]
      (is (some? entry))
      (is (= {} (:node-meta entry)) "no-meta node returns empty map"))))

(deftest defnode-test
  (testing "defnode! sets type and meta"
    (ctrl/defnode! [:my/cutoff] :type :float :node-meta {:range [0.0 1.0]} :value 0.5)
    (let [n (ctrl/node-info [:my/cutoff])]
      (is (= 0.5 (:value n)))
      (is (= :float (:type n)))
      (is (= {:range [0.0 1.0]} (:node-meta n)))))

  (testing "defnode! does not overwrite existing value when node already has one"
    (ctrl/set! [:existing/node] 42)
    (ctrl/defnode! [:existing/node] :type :int :value 0)
    (is (= 42 (ctrl/get [:existing/node])) "existing value preserved"))

  (testing "defnode! sets initial value when node is new"
    (ctrl/defnode! [:new/node] :type :keyword :value :start)
    (is (= :start (ctrl/get [:new/node]))))

  (testing "defnode! increments serial"
    (let [s0 (core/ctrl-get [:serial])]
      (ctrl/defnode! [:serial/test] :type :bool)
      (is (= (inc (or s0 0)) (core/ctrl-get [:serial]))))))

;; ---------------------------------------------------------------------------
;; bind! / unbind!
;; ---------------------------------------------------------------------------

(deftest bind-test
  (testing "bind! registers a binding"
    (ctrl/bind! [:filter/cutoff] {:type :midi-cc :channel 1 :cc-num 74})
    (let [n (ctrl/node-info [:filter/cutoff])]
      (is (= 1 (count (:bindings n))))
      (is (= :midi-cc (-> n :bindings first :type)))
      (is (= 74 (-> n :bindings first :cc-num)))))

  (testing "bind! uses priority 20 by default"
    (ctrl/bind! [:a/param] {:type :midi-cc :channel 1 :cc-num 10})
    (is (= 20 (-> (ctrl/node-info [:a/param]) :bindings first :priority))))

  (testing "bind! with explicit priority"
    (ctrl/bind! [:b/param] {:type :midi-cc :channel 1 :cc-num 11} :priority 10)
    (is (= 10 (-> (ctrl/node-info [:b/param]) :bindings first :priority))))

  (testing "bind! sorts bindings by priority"
    (ctrl/bind! [:multi/param] {:type :midi-cc :channel 1 :cc-num 20} :priority 30)
    (ctrl/bind! [:multi/param] {:type :midi-cc :channel 1 :cc-num 10} :priority 10)
    (let [bindings (:bindings (ctrl/node-info [:multi/param]))]
      (is (= 2 (count bindings)))
      (is (= 10 (:priority (first bindings))))
      (is (= 30 (:priority (second bindings))))))

  (testing "bind! raises on priority conflict"
    (ctrl/bind! [:conflict/node] {:type :midi-cc :channel 1 :cc-num 74} :priority 20)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"already has a binding at priority 20"
         (ctrl/bind! [:conflict/node] {:type :midi-cc :channel 1 :cc-num 75} :priority 20))))

  (testing "bind! succeeds with different priorities on same node"
    (ctrl/bind! [:dual/node] {:type :midi-cc :channel 1 :cc-num 10} :priority 10)
    (ctrl/bind! [:dual/node] {:type :midi-cc :channel 1 :cc-num 20} :priority 20)
    (is (= 2 (count (:bindings (ctrl/node-info [:dual/node]))))))

  (testing "bind! increments serial"
    (let [s0 (core/ctrl-get [:serial])]
      (ctrl/bind! [:serial/bind] {:type :midi-cc :channel 1 :cc-num 1})
      (is (= (inc s0) (core/ctrl-get [:serial]))))))

(deftest unbind-test
  (testing "unbind! by priority removes that binding"
    (ctrl/bind! [:u/node] {:type :midi-cc :channel 1 :cc-num 1} :priority 20)
    (ctrl/bind! [:u/node] {:type :midi-cc :channel 1 :cc-num 2} :priority 30)
    (ctrl/unbind! [:u/node] 20)
    (let [bindings (:bindings (ctrl/node-info [:u/node]))]
      (is (= 1 (count bindings)))
      (is (= 30 (:priority (first bindings))))))

  (testing "unbind! :all removes all bindings"
    (ctrl/bind! [:u2/node] {:type :midi-cc :channel 1 :cc-num 1} :priority 20)
    (ctrl/bind! [:u2/node] {:type :midi-cc :channel 1 :cc-num 2} :priority 30)
    (ctrl/unbind! [:u2/node] :all)
    (is (empty? (:bindings (ctrl/node-info [:u2/node]))))))

;; ---------------------------------------------------------------------------
;; checkpoint! / panic!
;; ---------------------------------------------------------------------------

(deftest checkpoint-panic-test
  (testing "checkpoint! saves current tree; panic! restores it"
    (ctrl/set! [:cp/param] :before)
    (ctrl/checkpoint! :snap)
    (ctrl/set! [:cp/param] :after)
    (is (= :after (ctrl/get [:cp/param])))
    (ctrl/panic! :snap)
    (is (= :before (ctrl/get [:cp/param])) "panic! restored pre-checkpoint value"))

  (testing "panic! without args uses most recent checkpoint"
    (ctrl/set! [:cp2/param] :v1)
    (ctrl/checkpoint! :last)
    (ctrl/set! [:cp2/param] :v2)
    (ctrl/panic!)
    (is (= :v1 (ctrl/get [:cp2/param]))))

  (testing "panic! with unknown name logs and does nothing"
    (ctrl/set! [:cp3/param] :keep)
    (ctrl/panic! :nonexistent)
    (is (= :keep (ctrl/get [:cp3/param])) "unknown checkpoint: no change"))

  (testing "panic! pushes to undo stack; undo! after panic! restores pre-panic state"
    (ctrl/set! [:undo-cp/param] :a)
    (ctrl/checkpoint! :undo-snap)
    (ctrl/set! [:undo-cp/param] :b)
    (ctrl/panic! :undo-snap)
    (is (= :a (ctrl/get [:undo-cp/param])))
    (ctrl/undo!)
    (is (= :b (ctrl/get [:undo-cp/param])) "undo! after panic! restored pre-panic value")))

;; ---------------------------------------------------------------------------
;; undo!
;; ---------------------------------------------------------------------------

(deftest undo-test
  (testing "undo! returns false on empty stack"
    (is (false? (ctrl/undo!))))

  (testing "undo! reverts an undoable set!"
    (ctrl/set! [:undo/param] :original)
    (ctrl/set! [:undo/param] :changed :undoable true)
    (is (= :changed (ctrl/get [:undo/param])))
    (ctrl/undo!)
    (is (= :original (ctrl/get [:undo/param]))))

  (testing "undo! returns true when a revert occurs"
    (ctrl/set! [:undo2/param] :v :undoable true)
    (is (true? (ctrl/undo!))))

  (testing "undo! stacks — multiple undoable changes"
    (ctrl/set! [:stack/p] :v1)
    (ctrl/set! [:stack/p] :v2 :undoable true)
    (ctrl/set! [:stack/p] :v3 :undoable true)
    (ctrl/undo!)
    (is (= :v2 (ctrl/get [:stack/p])))
    (ctrl/undo!)
    (is (= :v1 (ctrl/get [:stack/p])))
    (is (false? (ctrl/undo!)) "stack now empty"))

  (testing "undo! :now is the same as undo! in Phase 1"
    ;; Undo restores the full tree snapshot; subsequent non-undoable writes are also reverted
    (ctrl/set! [:timing/p] :a :undoable true)
    (ctrl/set! [:timing/p] :b)  ; not undoable — but the snapshot below :a captured nil
    (ctrl/undo! :now)
    ;; After undo!, tree is restored to the state before :a — which is nil
    (is (nil? (ctrl/get [:timing/p])) "undo! :now restores the full tree snapshot")))

;; ---------------------------------------------------------------------------
;; send-raw-nrpn!
;; ---------------------------------------------------------------------------

(deftest send-raw-nrpn-14bit-test
  (testing "send-raw-nrpn! fires 4 CCs with correct encoding"
    ;; NRPN 8320 = 65*128+0; value 768 = 0x300: data-msb=6, data-lsb=0
    (let [calls (atom [])]
      (with-redefs [nous.kairos/connected? (constantly true)
                    nous.kairos/send-cc!   (fn [ch cc val & _]
                                                (swap! calls conj {:ch ch :cc cc :val val}))]
        (ctrl/send-raw-nrpn! 1 8320 768))
      (is (= 4 (count @calls)))
      (let [[m99 m98 m6 m38] @calls]
        (is (= {:ch 1 :cc 99 :val 65} m99) "CC99 = 8320 >> 7 = 65")
        (is (= {:ch 1 :cc 98 :val 0}  m98) "CC98 = 8320 & 0x7F = 0")
        (is (= {:ch 1 :cc  6 :val 6}  m6)  "CC6 = 768 >> 7 = 6")
        (is (= {:ch 1 :cc 38 :val 0}  m38) "CC38 = 768 & 0x7F = 0")))))

(deftest send-raw-nrpn-7bit-test
  (testing "send-raw-nrpn! with :bits 7 uses 14-bit wire encoding"
    ;; value 99: wire = 0*128+99 → CC6=0, CC38=99
    (let [calls (atom [])]
      (with-redefs [nous.kairos/connected? (constantly true)
                    nous.kairos/send-cc!   (fn [ch cc val & _]
                                                (swap! calls conj {:ch ch :cc cc :val val}))]
        (ctrl/send-raw-nrpn! 2 10 99 7))
      (is (= 4 (count @calls)) "7-bit: 4 CCs (14-bit wire encoding)")
      (let [[_ _ m6 m38] @calls]
        (is (= 0  (:val m6))  "CC6 = data MSB (99 >> 7 = 0)")
        (is (= 99 (:val m38)) "CC38 = data LSB (99 & 0x7F = 99)")))))

(deftest send-raw-nrpn-no-sidecar-test
  (testing "send-raw-nrpn! does nothing when sidecar is not connected"
    (let [calls (atom [])]
      (with-redefs [nous.kairos/connected? (constantly false)
                    nous.kairos/send-cc!   (fn [ch cc val & _]
                                                (swap! calls conj {:ch ch :cc cc :val val}))]
        (ctrl/send-raw-nrpn! 1 1 500))
      (is (empty? @calls) "no CCs sent when disconnected"))))

;; ---------------------------------------------------------------------------
;; watch-global! / unwatch-global!
;; ---------------------------------------------------------------------------

(deftest watch-global-fires-on-set-test
  (testing "watch-global! fires on every set! regardless of path"
    (let [calls (atom [])]
      (ctrl/watch-global! ::test-watcher
                          (fn [tx _]
                            (swap! calls conj {:path (-> tx :tx/changes first :path)
                                               :value (-> tx :tx/changes first :after)})))
      (ctrl/set! [:watch-global/a] 1)
      (ctrl/set! [:watch-global/b] 2)
      (ctrl/unwatch-global! ::test-watcher)
      (is (= 2 (count @calls)) "fires once per set!")
      (is (= {:path [:watch-global/a] :value 1} (first @calls)))
      (is (= {:path [:watch-global/b] :value 2} (second @calls))))))

(deftest unwatch-global-removes-watcher-test
  (testing "unwatch-global! stops the watcher from firing"
    (let [calls (atom [])]
      (ctrl/watch-global! ::test-removable (fn [tx _] (swap! calls conj (-> tx :tx/changes first :after))))
      (ctrl/set! [:ug/before] 1)
      (ctrl/unwatch-global! ::test-removable)
      (ctrl/set! [:ug/after] 2)
      (is (= [1] @calls) "only the pre-removal set! was captured"))))

(deftest watch-global-re-register-replaces-test
  (testing "re-registering the same key replaces the callback"
    (let [calls-a (atom [])
          calls-b (atom [])]
      (ctrl/watch-global! ::test-replace (fn [tx _] (swap! calls-a conj (-> tx :tx/changes first :after))))
      (ctrl/set! [:replace/x] :first)
      (ctrl/watch-global! ::test-replace (fn [tx _] (swap! calls-b conj (-> tx :tx/changes first :after))))
      (ctrl/set! [:replace/x] :second)
      (ctrl/unwatch-global! ::test-replace)
      (is (= [:first] @calls-a) "first callback fired before replacement")
      (is (= [:second] @calls-b) "second callback fired after replacement"))))

(deftest watch-global-exception-is-swallowed-test
  (testing "exceptions in watch-global! callbacks do not propagate to the writer"
    (ctrl/watch-global! ::test-throwing (fn [_ _s] (throw (ex-info "boom" {}))))
    (is (nil? (ctrl/set! [:throw/test] 42)) "set! completes despite watcher exception")
    (ctrl/unwatch-global! ::test-throwing)))

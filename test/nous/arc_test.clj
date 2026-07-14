; SPDX-License-Identifier: EPL-2.0
(ns nous.arc-test
  "Tests for ctrl watcher API and nous.arc fan-out routing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core :as ct]
            [nous.arc  :as arc]
            [nous.core :as core]
            [nous.ctrl :as ctrl]))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  ;; arc-bind! adds a watch on the global tree-state ref — guarantee cleanup
  ;; even if a test throws, so watches never leak across tests.
  (try (f) (finally (arc/arc-unbind-all!) (core/stop!))))

(use-fixtures :each with-system)

(defn- tx-val
  "Extract the :after value from the first change in a transaction."
  [tx]
  (-> tx :tx/changes first :after))

(defn- tx-path
  "Extract the :path from the first change in a transaction."
  [tx]
  (-> tx :tx/changes first :path))

;; ---------------------------------------------------------------------------
;; ctrl/watch! — basic registration and firing
;; ---------------------------------------------------------------------------

(deftest watch-fires-on-send-test
  (testing "watch! callback fires when send! writes to path"
    (ctrl/defnode! [:watch/test-send] :type :float :node-meta {:range [0.0 1.0]})
    (ctrl/bind! [:watch/test-send] {:type :midi-cc :channel 1 :cc-num 1})
    (let [seen (atom nil)]
      (ctrl/watch! [:watch/test-send] ::test-watcher
                   (fn [tx _] (reset! seen {:path (tx-path tx) :value (tx-val tx)})))
      (with-redefs [nous.kairos/connected? (constantly false)]
        (ctrl/send! [:watch/test-send] 0.5))
      (is (= {:path [:watch/test-send] :value 0.5} @seen))
      (ctrl/unwatch-all! [:watch/test-send]))))

(deftest watch-fires-on-set-test
  (testing "watch! callback fires when set! writes to path"
    (ctrl/defnode! [:watch/test-set] :type :float)
    (let [seen (atom nil)]
      (ctrl/watch! [:watch/test-set] ::test-watcher
                   (fn [tx _] (reset! seen (tx-val tx))))
      (ctrl/set! [:watch/test-set] 0.9)
      (is (= 0.9 @seen))
      (ctrl/unwatch-all! [:watch/test-set]))))

(deftest watch-multiple-keys-test
  (testing "multiple watch keys on same path each fire independently"
    (ctrl/defnode! [:watch/multi] :type :float)
    (let [a (atom nil) b (atom nil)]
      (ctrl/watch! [:watch/multi] ::key-a (fn [tx _] (reset! a (tx-val tx))))
      (ctrl/watch! [:watch/multi] ::key-b (fn [tx _] (reset! b (tx-val tx))))
      (ctrl/set! [:watch/multi] 0.3)
      (is (= 0.3 @a))
      (is (= 0.3 @b))
      (ctrl/unwatch-all! [:watch/multi]))))

(deftest unwatch-removes-specific-key-test
  (testing "unwatch! removes only the specified key, others still fire"
    (ctrl/defnode! [:watch/unwatch-one] :type :float)
    (let [a (atom 0) b (atom 0)]
      (ctrl/watch! [:watch/unwatch-one] ::key-a (fn [tx _] (reset! a (tx-val tx))))
      (ctrl/watch! [:watch/unwatch-one] ::key-b (fn [tx _] (reset! b (tx-val tx))))
      (ctrl/unwatch! [:watch/unwatch-one] ::key-a)
      (ctrl/set! [:watch/unwatch-one] 0.7)
      (is (= 0 @a) "key-a was removed — should not fire")
      (is (= 0.7 @b) "key-b still fires")
      (ctrl/unwatch-all! [:watch/unwatch-one]))))

(deftest unwatch-all-removes-all-test
  (testing "unwatch-all! prevents any further callbacks"
    (ctrl/defnode! [:watch/unwatch-all] :type :float)
    (let [seen (atom 0)]
      (ctrl/watch! [:watch/unwatch-all] ::k1 (fn [_ _s] (swap! seen inc)))
      (ctrl/watch! [:watch/unwatch-all] ::k2 (fn [_ _s] (swap! seen inc)))
      (ctrl/unwatch-all! [:watch/unwatch-all])
      (ctrl/set! [:watch/unwatch-all] 0.5)
      (is (= 0 @seen) "no callbacks after unwatch-all!"))))

(deftest watcher-exception-does-not-propagate-test
  (testing "exception in watcher is swallowed; caller is not affected"
    (ctrl/defnode! [:watch/throws] :type :float)
    (ctrl/watch! [:watch/throws] ::thrower
                 (fn [_ _s] (throw (ex-info "deliberate" {}))))
    (is (nil? (ctrl/set! [:watch/throws] 0.5))
        "set! returns nil even when watcher throws")
    (ctrl/unwatch-all! [:watch/throws])))

(deftest watcher-receives-correct-value-test
  (testing "watcher receives the exact value passed to send!/set!"
    (ctrl/defnode! [:watch/value-check] :type :float)
    (let [received (atom [])]
      (ctrl/watch! [:watch/value-check] ::collector
                   (fn [tx _] (swap! received conj (tx-val tx))))
      (ctrl/set! [:watch/value-check] 0.1)
      (ctrl/set! [:watch/value-check] 0.5)
      (ctrl/set! [:watch/value-check] 0.9)
      (is (= [0.1 0.5 0.9] @received))
      (ctrl/unwatch-all! [:watch/value-check]))))

;; ---------------------------------------------------------------------------
;; arc-bind! — fan-out routing
;; ---------------------------------------------------------------------------

(deftest arc-bind-fans-out-test
  (testing "arc-bind! fans out to all downstream paths on each arc-send!"
    (arc/arc-bind! [:arc/test-tension]
                   {[:arc/out-cutoff] {:range [0.3 1.0]}
                    [:arc/out-res]    {:range [0.0 0.6]}})
    ;; Fan-out is synchronous (tree-state watch fires post-commit); read targets back.
    (arc/arc-send! [:arc/test-tension] 0.0)
    (is (= 0.3 (ct/ctrl-read [:arc/out-cutoff])) "cutoff 0 → 0.3")
    (is (= 0.0 (ct/ctrl-read [:arc/out-res]))    "resonance 0 → 0.0")
    (arc/arc-send! [:arc/test-tension] 1.0)
    (is (= 1.0 (ct/ctrl-read [:arc/out-cutoff])) "cutoff 1 → 1.0")
    (is (= 0.6 (ct/ctrl-read [:arc/out-res]))    "resonance 1 → 0.6")
    (arc/arc-unbind! [:arc/test-tension])))

(deftest arc-bind-midpoint-scaling-test
  (testing "arc-bind! scales midpoint value correctly"
    (arc/arc-bind! [:arc/mid-src] {[:arc/mid-dst] {:range [0.0 100.0]}})
    (arc/arc-send! [:arc/mid-src] 0.5)
    (is (= 50.0 (ct/ctrl-read [:arc/mid-dst])) "0.5 maps to midpoint of [0, 100]")
    (arc/arc-unbind! [:arc/mid-src])))

(deftest arc-bind-default-range-test
  (testing "arc-bind! with no :range passes value through unchanged"
    (arc/arc-bind! [:arc/passthru-src] {[:arc/passthru-dst] {}})
    (arc/arc-send! [:arc/passthru-src] 0.73)
    (is (= 0.73 (ct/ctrl-read [:arc/passthru-dst])) "no :range → identity over [0, 1]")
    (arc/arc-unbind! [:arc/passthru-src])))

(deftest arc-rebind-replaces-routes-test
  (testing "re-calling arc-bind! replaces downstream routes without duplicating watchers"
    ;; First bind: only dst-a
    (arc/arc-bind! [:arc/rebind-src] {[:arc/rebind-dst-a] {}})
    (arc/arc-send! [:arc/rebind-src] 0.5)
    (is (= 0.5 (ct/ctrl-read [:arc/rebind-dst-a])) "dst-a receives first send")
    (is (nil? (ct/ctrl-read [:arc/rebind-dst-b])) "dst-b not yet bound")
    ;; Rebind: only dst-b (routes replaced; the single watch persists)
    (arc/arc-bind! [:arc/rebind-src] {[:arc/rebind-dst-b] {}})
    (arc/arc-send! [:arc/rebind-src] 0.8)
    (is (= 0.5 (ct/ctrl-read [:arc/rebind-dst-a])) "dst-a unchanged after rebind (no longer routed)")
    (is (= 0.8 (ct/ctrl-read [:arc/rebind-dst-b])) "dst-b now receives")
    (arc/arc-unbind! [:arc/rebind-src])))

(deftest arc-unbind-stops-fan-out-test
  (testing "arc-unbind! stops further fan-out to downstream paths"
    (arc/arc-bind! [:arc/stop-src] {[:arc/stop-dst] {}})
    (arc/arc-send! [:arc/stop-src] 0.5)
    (is (= 0.5 (ct/ctrl-read [:arc/stop-dst])) "fires before unbind")
    (arc/arc-unbind! [:arc/stop-src])
    (arc/arc-send! [:arc/stop-src] 0.8)
    (is (= 0.5 (ct/ctrl-read [:arc/stop-dst])) "target unchanged after unbind — fan-out stopped")))

(deftest arc-routes-reflects-current-state-test
  (testing "arc-routes returns current binding table"
    (arc/arc-bind! [:arc/inspect-src]
                   {[:arc/inspect-dst] {:range [0.0 1.0]}})
    (is (= {[:arc/inspect-dst] {:range [0.0 1.0]}}
           (get (arc/arc-routes) [:arc/inspect-src])))
    (arc/arc-unbind! [:arc/inspect-src])
    (is (nil? (get (arc/arc-routes) [:arc/inspect-src])))))

(deftest arc-unbind-all-clears-all-routes-test
  (testing "arc-unbind-all! removes all routes"
    (arc/arc-bind! [:arc/all-a] {[:arc/dummy] {}})
    (arc/arc-bind! [:arc/all-b] {[:arc/dummy] {}})
    (arc/arc-unbind-all!)
    (is (empty? (arc/arc-routes)))))

(deftest arc-send-updates-source-value-test
  (testing "arc-send! writes the arc node value to the ctrl-tree"
    (arc/arc-bind! [:arc/val-check] {})
    (arc/arc-send! [:arc/val-check] 0.42)
    (is (= 0.42 (ct/ctrl-read [:arc/val-check])) "ctrl-tree reflects the sent value")
    (arc/arc-unbind! [:arc/val-check])))

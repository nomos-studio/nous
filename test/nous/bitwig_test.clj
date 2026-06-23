; SPDX-License-Identifier: EPL-2.0
(ns nous.bitwig-test
  "Tests for nous.bitwig — OSC-based Bitwig ctrl tree adapter."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.bitwig :as bitwig]
            [nous.ctrl   :as ctrl]
            [nous.core   :as core]
            [nous.osc    :as osc]))

;; ---------------------------------------------------------------------------
;; Fixture
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (core/start! :bpm 120)
    (try
      (f)
      (finally
        (bitwig/disconnect!)
        (core/stop!)))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- capturing-send
  "Return a vector atom and a send! fn that conjoins [host port address args]."
  []
  (let [calls (atom [])]
    [calls (fn [host port address & args]
             (swap! calls conj {:host host :port port
                                :address address :args (vec args)}))]))

;; ---------------------------------------------------------------------------
;; connect! / disconnect! / connected?
;; ---------------------------------------------------------------------------

(deftest connect-sets-state-test
  (testing "connect! sets conn atom and registers listeners"
    (let [[calls send] (capturing-send)]
      (binding [bitwig/*send-fn*  send
                bitwig/*http-get* (constantly nil)]
        (bitwig/connect! "127.0.0.1" 7179 7178)
        (is (bitwig/connected?))
        ;; Should have sent /nous/bitwig/sub
        (is (= 1 (count @calls)))
        (is (= "/nous/bitwig/sub" (:address (first @calls))))
        (is (= ["[:bitwig]"] (:args (first @calls))))))))

(deftest disconnect-clears-state-test
  (testing "disconnect! clears conn and sends /unsub"
    (let [[calls send] (capturing-send)]
      (binding [bitwig/*send-fn*  send
                bitwig/*http-get* (constantly nil)]
        (bitwig/connect! "127.0.0.1" 7179 7178)
        (reset! calls [])
        (bitwig/disconnect!)
        (is (not (bitwig/connected?)))
        ;; Should have sent /nous/bitwig/unsub
        (is (= 1 (count @calls)))
        (is (= "/nous/bitwig/unsub" (:address (first @calls))))))))

(deftest double-connect-disconnects-first-test
  (testing "second connect! disconnects previous before reconnecting"
    (let [[calls send] (capturing-send)]
      (binding [bitwig/*send-fn*  send
                bitwig/*http-get* (constantly nil)]
        (bitwig/connect! "127.0.0.1" 7179 7178)
        (reset! calls [])
        (bitwig/connect! "127.0.0.1" 7179 7178)
        ;; First call should be /unsub (from implicit disconnect), second /sub
        (is (= 2 (count @calls)))
        (is (= "/nous/bitwig/unsub" (:address (first @calls))))
        (is (= "/nous/bitwig/sub"   (:address (second @calls))))))))

;; ---------------------------------------------------------------------------
;; Outbound: ctrl set → OSC dispatch
;; ---------------------------------------------------------------------------

(deftest ctrl-change-dispatched-to-bwosc-test
  (testing "[:bitwig ...] ctrl set dispatches OSC val to bwosc"
    (let [[calls send] (capturing-send)]
      (binding [bitwig/*send-fn*  send
                bitwig/*http-get* (constantly nil)]
        (bitwig/connect! "127.0.0.1" 7179 7178)
        (reset! calls [])
        (ctrl/set! [:bitwig :track :lead-synth :volume] 0.73)
        (is (= 1 (count @calls)))
        (let [{:keys [address args]} (first @calls)]
          (is (= "/nous/bitwig/val" address))
          (is (= "[:bitwig :track :lead-synth :volume]" (first args)))
          (is (= 0.73 (second args))))))))

(deftest non-bitwig-ctrl-change-not-dispatched-test
  (testing "non-[:bitwig] ctrl changes are NOT dispatched to bwosc"
    (let [[calls send] (capturing-send)]
      (binding [bitwig/*send-fn*  send
                bitwig/*http-get* (constantly nil)]
        (bitwig/connect! "127.0.0.1" 7179 7178)
        (reset! calls [])
        (ctrl/set! [:journey :current-bar] 12)
        (is (= 0 (count @calls)))))))

;; ---------------------------------------------------------------------------
;; Inbound: OSC val → ctrl tree
;; ---------------------------------------------------------------------------

(deftest inbound-val-updates-ctrl-test
  (testing "on-val! args write to ctrl tree"
    (binding [bitwig/*send-fn*  (fn [& _] nil)
              bitwig/*http-get* (constantly nil)]
      (bitwig/connect! "127.0.0.1" 7179 7178)
      ;; Simulate bwosc pushing a value by calling the registered on-msg! handler
      (let [handler (get @(deref #'nous.osc/persistent-handlers) "/nous/bitwig/val")]
        (is (fn? handler))
        (handler ["[:bitwig :track :drums :volume]" 0.9])
        (is (= 0.9 (ctrl/get [:bitwig :track :drums :volume])))))))

;; ---------------------------------------------------------------------------
;; Echo suppression
;; ---------------------------------------------------------------------------

(deftest echo-suppression-test
  (testing "inbound value does NOT loop back as outbound dispatch"
    (let [[calls send] (capturing-send)]
      (binding [bitwig/*send-fn*  send
                bitwig/*http-get* (constantly nil)]
        (bitwig/connect! "127.0.0.1" 7179 7178)
        (reset! calls [])
        ;; Simulate inbound from bwosc
        (let [handler (get @(deref #'nous.osc/persistent-handlers) "/nous/bitwig/val")]
          (handler ["[:bitwig :track :lead-synth :volume]" 0.55]))
        ;; The ctrl/set! fires synchronously; global watch should be suppressed
        (is (= 0 (count @calls)))))))

;; ---------------------------------------------------------------------------
;; HTTP snapshot load
;; ---------------------------------------------------------------------------

(deftest snapshot-loads-into-ctrl-test
  (testing "HTTP snapshot tree is flattened into ctrl tree on connect"
    (binding [bitwig/*send-fn*  (fn [& _] nil)
              bitwig/*http-get* (constantly
                                  (pr-str {:track {:synth-pad {:volume 0.65
                                                               :pan    0.1}}}))]
      (bitwig/connect! "127.0.0.1" 7179 7178)
      (is (= 0.65 (ctrl/get [:bitwig :track :synth-pad :volume])))
      (is (= 0.1  (ctrl/get [:bitwig :track :synth-pad :pan]))))))

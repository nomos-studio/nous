; SPDX-License-Identifier: EPL-2.0
(ns nous.topology-test
  "Tests for nous.topology -- Layer 1: load, query, port resolution."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [nous.topology :as topology]))

;; ---------------------------------------------------------------------------
;; Test topology EDN (written to a temp file for each test)
;; ---------------------------------------------------------------------------

(def ^:private test-topology-edn
  {:topology/id          :test-studio
   :topology/version     "0.1.0"
   :topology/description "Test topology"
   :devices
   {:synth-a {:device-id    :test/synth-a
              :port-pattern "Synth A"
              :midi/channel  1
              :role          :synth}
    :synth-b {:device-id    :test/synth-b
              :port-pattern "Synth B Out"
              :in-pattern   "Synth B In"
              :midi/channel  3
              :role          :synth}
    :ctrl-1  {:device-id    :test/ctrl-1
              :port-pattern "Controller One"
              :midi/channel  1
              :role          :controller}}
   :interfaces
   {:iface-1 {:manufacturer "Test" :model "TestMio" :port-pattern "TestMio"
              :host :host-a}}
   :hosts
   {:host-a {:role :nous-host :os :macos}}})

(def ^:private bad-topology-edn
  {:topology/id :bad
   :devices     "not-a-map"})

(defn- write-temp-edn [m]
  (let [f (java.io.File/createTempFile "topo-test-" ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str m))
    (.getAbsolutePath f)))

;; Reset topology atom before each test
(use-fixtures :each
  (fn [t]
    (reset! @#'topology/topology nil)
    (t)
    (reset! @#'topology/topology nil)))

;; ---------------------------------------------------------------------------
;; load-topology!
;; ---------------------------------------------------------------------------

(deftest load-topology-valid
  (testing "loads a valid topology and returns the map"
    (let [path (write-temp-edn test-topology-edn)
          result (topology/load-topology! path)]
      (is (map? result))
      (is (= :test-studio (:topology/id result)))
      (is (map? (:devices result)))
      (is (topology/loaded?)))))

(deftest load-topology-sets-state
  (testing "loaded topology is accessible via query fns after load"
    (let [path (write-temp-edn test-topology-edn)]
      (topology/load-topology! path)
      (is (= "0.1.0" (:topology/version (topology/topology-meta)))))))

(deftest load-topology-missing-file
  (testing "throws ex-info when file does not exist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                          (topology/load-topology! "/no/such/file.edn")))))

(deftest load-topology-bad-devices
  (testing "throws ex-info when :devices is not a map"
    (let [path (write-temp-edn bad-topology-edn)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid topology"
                            (topology/load-topology! path))))))

(deftest load-topology-example-doc-exists
  (testing "doc/topology-example.edn schema reference is present in the repo"
    (is (.exists (io/file "doc/topology-example.edn"))
        "doc/topology-example.edn should exist as the schema reference")))

(deftest load-topology-example-doc-loadable
  (testing "doc/topology-example.edn parses without error"
    (let [result (topology/load-topology! "doc/topology-example.edn")]
      (is (map? result))
      (is (seq (:devices result))))))

(deftest load-topology-replaces-prior
  (testing "reloading with a new file replaces the prior topology"
    (let [path1 (write-temp-edn test-topology-edn)
          path2 (write-temp-edn (assoc test-topology-edn :topology/version "9.9.9"))]
      (topology/load-topology! path1)
      (is (= "0.1.0" (:topology/version (topology/topology-meta))))
      (topology/load-topology! path2)
      (is (= "9.9.9" (:topology/version (topology/topology-meta)))))))

;; ---------------------------------------------------------------------------
;; loaded?
;; ---------------------------------------------------------------------------

(deftest loaded-false-before-load
  (testing "loaded? returns false when no topology is loaded"
    (is (false? (topology/loaded?)))))

(deftest loaded-true-after-load
  (testing "loaded? returns true after load-topology!"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (true? (topology/loaded?)))))

;; ---------------------------------------------------------------------------
;; device-ids
;; ---------------------------------------------------------------------------

(deftest device-ids-returns-sorted-aliases
  (testing "device-ids returns sorted alias keywords"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (= [:ctrl-1 :synth-a :synth-b] (topology/device-ids)))))

(deftest device-ids-nil-before-load
  (testing "device-ids returns nil when nothing is loaded"
    (is (nil? (topology/device-ids)))))

;; ---------------------------------------------------------------------------
;; device-info
;; ---------------------------------------------------------------------------

(deftest device-info-known-alias
  (testing "device-info returns the descriptor for a known alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [d (topology/device-info :synth-a)]
      (is (= "Synth A" (:port-pattern d)))
      (is (= 1 (:midi/channel d)))
      (is (= :synth (:role d)))
      (is (= :test/synth-a (:device-id d))))))

(deftest device-info-unknown-alias-returns-nil
  (testing "device-info returns nil for an unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (nil? (topology/device-info :no-such-device)))))

(deftest device-info-nil-before-load
  (testing "device-info returns nil when nothing is loaded"
    (is (nil? (topology/device-info :synth-a)))))

;; ---------------------------------------------------------------------------
;; interface-info
;; ---------------------------------------------------------------------------

(deftest interface-info-known
  (testing "interface-info returns the interface descriptor"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [i (topology/interface-info :iface-1)]
      (is (= "Test" (:manufacturer i)))
      (is (= :host-a (:host i))))))

(deftest interface-info-unknown-returns-nil
  (testing "interface-info returns nil for unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (nil? (topology/interface-info :no-such-iface)))))

;; ---------------------------------------------------------------------------
;; topology-meta
;; ---------------------------------------------------------------------------

(deftest topology-meta-contents
  (testing "topology-meta returns id/version/description keys"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [m (topology/topology-meta)]
      (is (= :test-studio (:topology/id m)))
      (is (= "0.1.0" (:topology/version m)))
      (is (= "Test topology" (:topology/description m))))))

(deftest topology-meta-nil-before-load
  (testing "topology-meta returns nil when nothing is loaded"
    (is (nil? (topology/topology-meta)))))

;; ---------------------------------------------------------------------------
;; resolve-output-port / resolve-input-port (mocked)
;; ---------------------------------------------------------------------------

(deftest resolve-output-port-returns-pattern
  (testing "resolve-output-port returns the port-pattern string"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (= "Synth A" (topology/resolve-output-port :synth-a)))))

(deftest resolve-input-port-uses-in-pattern
  (testing "resolve-input-port returns :in-pattern when present"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (= "Synth B In" (topology/resolve-input-port :synth-b)))))

(deftest resolve-input-port-falls-back-to-port-pattern
  (testing "resolve-input-port falls back to :port-pattern when :in-pattern absent"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (= "Synth A" (topology/resolve-input-port :synth-a)))))

(deftest resolve-output-port-unknown-throws
  (testing "resolve-output-port throws for unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown topology device"
                          (topology/resolve-output-port :no-such)))))

(deftest resolve-input-port-unknown-throws
  (testing "resolve-input-port throws for unknown alias"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown topology device"
                          (topology/resolve-input-port :no-such)))))

;; ---------------------------------------------------------------------------
;; start-kairos!
;; ---------------------------------------------------------------------------

(deftest start-kairos-output-only
  (testing "start-kairos! passes --midi-port pattern to kairos"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [kairos-calls (atom nil)]
      (with-redefs [nous.kairos/start-kairos! (fn [& args] (reset! kairos-calls args) nil)]
        (topology/start-kairos! :synth-a :binary "/fake/kairos")
        (let [args @kairos-calls
              arg-map (apply hash-map args)]
          (is (some? args))
          (is (= ["--midi-port" "Synth A"] (:args arg-map))))))))

(deftest start-kairos-with-input
  (testing "start-kairos! with :input adds --midi-in-port arg"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [kairos-calls (atom nil)]
      (with-redefs [nous.kairos/start-kairos! (fn [& args] (reset! kairos-calls args) nil)]
        (topology/start-kairos! :synth-a :input :ctrl-1 :binary "/fake/kairos")
        (let [args @kairos-calls
              arg-map (apply hash-map args)
              cli-args (:args arg-map)]
          (is (= "Controller One" (second (drop-while #(not= "--midi-in-port" %) cli-args)))))))))

(deftest start-kairos-without-loaded-topology-throws
  (testing "start-kairos! throws when no topology is loaded"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No topology loaded"
                          (topology/start-kairos! :synth-a)))))

(deftest start-sidecar-delegates-to-start-kairos
  (testing "start-sidecar! (deprecated) delegates to start-kairos!"
    (topology/load-topology! (write-temp-edn test-topology-edn))
    (let [kairos-calls (atom nil)]
      (with-redefs [nous.kairos/start-kairos! (fn [& args] (reset! kairos-calls args) nil)]
        (topology/start-sidecar! :synth-a :binary "/fake/kairos")
        (is (some? @kairos-calls))))))

; SPDX-License-Identifier: EPL-2.0
(ns nous.session-test
  "Tests for nous.session — nomos-topology Session → kairos graph translation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.core    :as core]
            [nous.ctrl    :as ctrl]
            [nous.kairos  :as kairos]
            [nous.session :as session]))

;; ---------------------------------------------------------------------------
;; Test data
;; ---------------------------------------------------------------------------

(def ^:private simple-session
  {:topology
   {:nodes  [{:id :voice :type :kairos-grid
              :patch {:modules [{:id :osc :type "plaits"
                                 :params {:harmonics 0.5}}
                                {:id :out :type "audio-out"}]
                      :cables  [[:osc 0 :out 0]
                                [:osc 0 :out 1]]}}
             {:id :space :type "clap/com.fabfilter/pro-r-3"
              :params {:decay 1.2}}]
    :routes [{:from [:voice 0] :to [:space 0]}
             {:from [:space 0] :to :master/left}
             {:from [:space 1] :to :master/right}]
    :modulations [{:source [:voice "signal/envelope"]
                   :target [:space "decay"]
                   :amount 0.4
                   :curve  :exp}]}
   :control-tree
   {:controls {:pitch  {:target [:voice "osc/note"]
                         :range  [0 127]
                         :type   :midi-note}
                :timbre {:target [:voice "osc/harmonics"]
                          :range  [0 1]
                          :cc     74}}}})

(def ^:private bare-topo
  {:nodes  [{:id :synth :type "com.example.MySynth"}]
   :routes [{:from [:synth 0] :to :master/left}]})

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- active-session-atom [] @#'session/active-session-atom)

(defn- with-system [f]
  (core/start! :bpm 120)
  (try
    (reset! (active-session-atom) nil)
    (f)
    (finally
      (reset! (active-session-atom) nil)
      (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; session->graph — node translation
;; ---------------------------------------------------------------------------

(deftest translate-kairos-grid-node
  (testing ":kairos-grid type → plugin = *kairos-grid-plugin-id*, patch inline"
    (let [g (session/session->graph simple-session)
          n (first (:graph/nodes g))]
      (is (= :voice (:id n)))
      (is (= session/*kairos-grid-plugin-id* (:plugin n)))
      (is (some? (:patch n)) "patch is present inline"))))

(deftest translate-clap-plugin-node
  (testing "string type → plugin = type string"
    (let [g (session/session->graph simple-session)
          n (second (:graph/nodes g))]
      (is (= :space (:id n)))
      (is (= "clap/com.fabfilter/pro-r-3" (:plugin n)))
      (is (= {:decay 1.2} (:params n))))))

(deftest translate-bare-topology
  (testing "session->graph accepts a bare Topology (no :control-tree wrapper)"
    (let [g (session/session->graph bare-topo)]
      (is (= 1 (count (:graph/nodes g))))
      (is (= :synth (:id (first (:graph/nodes g)))))
      (is (= "com.example.MySynth" (:plugin (first (:graph/nodes g))))))))

;; ---------------------------------------------------------------------------
;; session->graph — route translation
;; ---------------------------------------------------------------------------

(deftest translate-node-to-node-route
  (testing "[:voice 0] → [:space 0] becomes [:voice :out-0 :space :in-0]"
    (let [g (session/session->graph simple-session)
          e (first (:graph/edges g))]
      (is (= [:voice :out-0 :space :in-0] e)))))

(deftest translate-route-to-master-left
  (testing "[:space 0] → :master/left becomes [:space :out-0 :host :master/left]"
    (let [g (session/session->graph simple-session)
          e (second (:graph/edges g))]
      (is (= [:space :out-0 :host :master/left] e)))))

(deftest translate-route-to-master-right
  (testing "[:space 1] → :master/right becomes [:space :out-1 :host :master/right]"
    (let [g (session/session->graph simple-session)
          e (nth (:graph/edges g) 2)]
      (is (= [:space :out-1 :host :master/right] e)))))

(deftest edge-count-matches-routes
  (testing "graph has one edge per route"
    (let [g (session/session->graph simple-session)]
      (is (= 3 (count (:graph/edges g)))))))

;; ---------------------------------------------------------------------------
;; session->graph — modulations
;; ---------------------------------------------------------------------------

(deftest modulations-passed-through
  (testing ":graph/modulations present when topology has :modulations"
    (let [g (session/session->graph simple-session)]
      (is (= 1 (count (:graph/modulations g))))
      (is (= {:source [:voice "signal/envelope"]
              :target [:space "decay"]
              :amount 0.4
              :curve  :exp}
             (first (:graph/modulations g)))))))

(deftest no-modulations-key-when-empty
  (testing ":graph/modulations absent when topology has no modulations"
    (let [g (session/session->graph bare-topo)]
      (is (nil? (:graph/modulations g))))))

;; ---------------------------------------------------------------------------
;; session->graph — keyword ports
;; ---------------------------------------------------------------------------

(deftest keyword-port-ref-preserved
  (testing "keyword port refs are passed through unchanged (not converted to :out-N)"
    (let [s {:topology
             {:nodes  [{:id :a :type "foo"} {:id :b :type "bar"}]
              :routes [{:from [:a :voice-out] :to [:b :voice-in]}]}}
          g (session/session->graph s)
          e (first (:graph/edges g))]
      (is (= [:a :voice-out :b :voice-in] e)))))

;; ---------------------------------------------------------------------------
;; load-session! — sends graph to kairos
;; ---------------------------------------------------------------------------

(deftest load-session-sends-graph-load
  (testing "load-session! calls kairos/send-graph-load! with translated graph"
    (let [sent (atom nil)]
      (with-redefs [kairos/connected?       (constantly true)
                    kairos/send-graph-load! (fn [g] (reset! sent g) nil)]
        (session/load-session! simple-session :apply-ctrl-tree? false))
      (is (some? @sent) "send-graph-load! was called")
      (is (= 2 (count (:graph/nodes @sent)))))))

(deftest load-session-stores-active-session
  (testing "load-session! persists session for active-session and reload-session!"
    (with-redefs [kairos/connected?       (constantly true)
                  kairos/send-graph-load! (fn [_] nil)]
      (session/load-session! simple-session :apply-ctrl-tree? false))
    (is (= simple-session (session/active-session)))))

(deftest load-session-replaces-prior-session
  (testing "loading a new session replaces the prior active session"
    (with-redefs [kairos/connected?       (constantly true)
                  kairos/send-graph-load! (fn [_] nil)]
      (session/load-session! simple-session :apply-ctrl-tree? false)
      (session/load-session! bare-topo      :apply-ctrl-tree? false))
    (is (= bare-topo (session/active-session)))))

;; ---------------------------------------------------------------------------
;; reload-session!
;; ---------------------------------------------------------------------------

(deftest reload-sends-graph-again
  (testing "reload-session! re-sends the active graph"
    (let [call-count (atom 0)]
      (with-redefs [kairos/connected?       (constantly true)
                    kairos/send-graph-load! (fn [_] (swap! call-count inc) nil)]
        (session/load-session! simple-session :apply-ctrl-tree? false)
        (session/reload-session!))
      (is (= 2 @call-count) "send-graph-load! called twice (load + reload)"))))

(deftest reload-no-op-when-nothing-loaded
  (testing "reload-session! returns nil when no session is loaded"
    (is (nil? (session/reload-session!)))))

;; ---------------------------------------------------------------------------
;; clear-session!
;; ---------------------------------------------------------------------------

(deftest clear-session-resets-active
  (testing "clear-session! clears active-session and sends graph-reset!"
    (let [reset-called (atom false)]
      (with-redefs [kairos/connected?        (constantly true)
                    kairos/send-graph-load!  (fn [_] nil)
                    kairos/send-graph-reset! (fn [] (reset! reset-called true) nil)]
        (session/load-session! simple-session :apply-ctrl-tree? false)
        (session/clear-session!))
      (is (nil? (session/active-session)))
      (is (true? @reset-called)))))

;; ---------------------------------------------------------------------------
;; control tree wiring
;; ---------------------------------------------------------------------------

(deftest load-session-wires-control-tree
  (testing "load-session! registers ctrl nodes for each named control"
    (with-redefs [kairos/connected?       (constantly true)
                  kairos/send-graph-load! (fn [_] nil)]
      (session/load-session! simple-session :path-root :test-session))
    ;; :pitch should be registered as :int (midi-note type)
    (is (some? (ctrl/node-info [:test-session :pitch])))
    ;; :timbre should be registered
    (is (some? (ctrl/node-info [:test-session :timbre])))))

(deftest ctrl-node-has-topology-target-meta
  (testing "ctrl node meta includes :topology/target pointing to the param ref"
    (with-redefs [kairos/connected?       (constantly true)
                  kairos/send-graph-load! (fn [_] nil)]
      (session/load-session! simple-session :path-root :test-session2))
    (let [node (ctrl/node-info [:test-session2 :pitch])]
      (is (= [:voice "osc/note"] (get-in node [:node-meta :topology/target]))))))

;; ---------------------------------------------------------------------------
;; kairos-grid-plugin-id override
;; ---------------------------------------------------------------------------

(deftest plugin-id-is-configurable
  (testing "binding *kairos-grid-plugin-id* overrides the plugin ID"
    (binding [session/*kairos-grid-plugin-id* "org.custom.grid"]
      (let [g (session/session->graph simple-session)
            n (first (:graph/nodes g))]
        (is (= "org.custom.grid" (:plugin n)))))))

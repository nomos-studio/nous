; SPDX-License-Identifier: EPL-2.0
(ns nous.session
  "nomos-topology Session → kairos graph translation and lifecycle management.

  A nomos-topology Session is a plain Clojure map describing:
    - the kairos synthesis graph (CLAP plugin nodes + audio routes + modulations)
    - the control tree (named handles connecting nous ctrl paths to the synthesis)

  This namespace bridges that data model to the live kairos process via IPC.

  ## Quick start

    (require '[nous.session :as session])

    ;; Define a session using nomos.topology.core helpers (or plain maps)
    (def my-session
      {:topology
       {:nodes [{:id    :voice
                 :type  :kairos-grid
                 :patch {:modules [{:id :osc  :type \"plaits\"
                                    :params {:harmonics 0.5 :timbre 0.5}}
                                   {:id :out  :type \"audio-out\"}]
                          :cables  [[:osc 0 :out 0]
                                    [:osc 0 :out 1]]}}
                {:id :space :type \"clap/com.fabfilter/pro-r-3\"
                 :params {:decay 1.2}}]
        :routes [{:from [:voice 0] :to [:space 0]}
                 {:from [:space 0] :to :master/left}
                 {:from [:space 1] :to :master/right}]}
       :control-tree
       {:controls {:pitch  {:target [:voice \"osc/note\"]
                             :range  [0 127]
                             :type   :midi-note}
                   :timbre {:target [:voice \"osc/harmonics\"]
                             :range  [0 1]
                             :cc     74}}}})

    ;; Load to a connected kairos instance
    (session/load-session! my-session)

    ;; Inspect the translated kairos graph
    (session/session->graph my-session)

    ;; Re-send after kairos restarts (wire into supervisor/register-rt!)
    (session/reload-session!)

  ## Supervisor integration

    (supervisor/register-rt!
      :restore-fn #(session/reload-session!))

  ## Control tree

  When :apply-ctrl-tree? is true (default), load-session! registers each named
  control as a ctrl path and wires any :cc binding. The ctrl node path is:
    [node-id control-name]   e.g. [:session :timbre] or [:voice :timbre]

  ## Notes on kairos-grid nodes

  The :kairos-grid node type is the built-in sample-rate DSP container in
  kairos. Its internal module graph is delivered inline via the :patch key.
  The CLAP plugin ID is [[kairos-grid-plugin-id]] (configurable)."
  (:require [nous.binding-registry :as breg]
            [nous.kairos :as kairos]
            [nous.synth  :as synth-ns]))

;; ---------------------------------------------------------------------------
;; kairos-grid plugin ID
;; ---------------------------------------------------------------------------

(def ^:dynamic *kairos-grid-plugin-id*
  "CLAP plugin ID for the kairos-grid node type.
  Override if your kairos build uses a non-default registration string."
  "org.nomos.kairos-grid")

;; ---------------------------------------------------------------------------
;; Active session state
;; ---------------------------------------------------------------------------

(defonce ^:private active-session-atom (atom nil))

;; Nodes placed individually via place-synth! — keyed by node-id keyword.
;; Independent of active-session-atom; cleared by clear-session!.
(defonce ^:private placed-nodes (atom {}))

;; ---------------------------------------------------------------------------
;; Translation helpers
;; ---------------------------------------------------------------------------

(defn- port-num->kw
  "Convert integer port number n to a direction-prefixed keyword.
  (port-num->kw 0 :out) → :out-0
  (port-num->kw 2 :in)  → :in-2"
  [n direction]
  (keyword (str (name direction) "-" n)))

(defn- endpoint->pair
  "Translate a RouteEndpoint to [node-kw port-kw].

  direction is :out (from-side) or :in (to-side).

  Examples:
    (endpoint->pair [:voice 0] :out)  → [:voice :out-0]
    (endpoint->pair [:space 1] :in)   → [:space :in-1]
    (endpoint->pair :master/left :in) → [:host :master/left]"
  [endpoint direction]
  (cond
    (and (vector? endpoint) (integer? (second endpoint)))
    [(first endpoint) (port-num->kw (second endpoint) direction)]

    (and (vector? endpoint) (keyword? (second endpoint)))
    [(first endpoint) (second endpoint)]

    (keyword? endpoint)
    [:host endpoint]

    :else
    (throw (ex-info "Invalid route endpoint" {:endpoint endpoint}))))

(defn- route->edge
  "Translate a nomos-topology Route to a kairos edge tuple
  [from-node from-port to-node to-port]."
  [{:keys [from to]}]
  (let [[fn fp] (endpoint->pair from :out)
        [tn tp] (endpoint->pair to   :in)]
    [fn fp tn tp]))

(defn- node->graph-node
  "Translate a nomos-topology Node to a kairos :graph/nodes entry."
  [{:keys [id type params patch]}]
  (cond-> {:id id}
    ;; kairos-grid: built-in DSP container; patch delivered inline
    (= :kairos-grid type)
    (-> (assoc :plugin *kairos-grid-plugin-id*)
        (cond-> (some? patch) (assoc :patch patch)))

    ;; CLAP plugins: type string is the plugin ID
    (string? type)
    (assoc :plugin type)

    ;; Initial parameter values
    (seq params)
    (assoc :params params)))

;; ---------------------------------------------------------------------------
;; Public translation
;; ---------------------------------------------------------------------------

(defn session->graph
  "Translate a nomos-topology Session (or bare Topology) into a kairos graph map.

  The result is suitable for (kairos/send-graph-load! ...).

  Keys in the returned map:
    :graph/nodes       — vector of {:id kw :plugin str :params {} :patch ...}
    :graph/edges       — vector of [from-node from-port to-node to-port]
    :graph/modulations — vector of modulation bindings (when present)

  Routes are translated using the convention:
    integer port n → :out-N on the from-side, :in-N on the to-side
    keyword port   → used as-is
    keyword endpoint (:master/left etc.) → [:host :master/left]

  Example:
    (session->graph my-session)
    ;=> {:graph/nodes [{:id :voice :plugin \"org.nomos.kairos-grid\" :patch {...}}
    ;                  {:id :space :plugin \"clap/com.fabfilter/pro-r-3\" :params {:decay 1.2}}]
    ;    :graph/edges [[:voice :out-0 :space :in-0]
    ;                  [:space :out-0 :host :master/left]
    ;                  [:space :out-1 :host :master/right]]}"
  [session]
  (let [topo  (or (:topology session) session)
        nodes (mapv node->graph-node (:nodes topo []))
        edges (mapv route->edge      (:routes topo []))
        mods  (:modulations topo [])]
    (cond-> {:graph/nodes nodes
             :graph/edges edges}
      (seq mods) (assoc :graph/modulations mods))))

;; ---------------------------------------------------------------------------
;; Control tree
;; ---------------------------------------------------------------------------

(defn- wire-control-tree!
  "Register each named control from the control tree as a ctrl path.
  Wires :cc bindings when present.

  path-root — keyword used as the first element of every ctrl path,
              e.g. :session → [:session :timbre]"
  [control-tree path-root]
  (doseq [[ctrl-name {:keys [target range type cc]}]
          (:controls control-tree {})]
    (let [path  [path-root ctrl-name]
          vtype (case type :midi-note :int :gate :bool :float)]
      (breg/register-node! path :type vtype
                           :node-meta {:range           (or range [0 1])
                                       :topology/target target})
      (when cc
        (breg/bind! path {:type    :midi-cc
                          :cc-num  cc
                          :range   (or range [0 1])
                          :channel 1})))))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn load-session!
  "Translate and load a nomos-topology Session into the connected kairos instance.

  Stores the session for reload-session! after kairos restarts.
  Returns the translated kairos graph map.

  Options:
    :path-root        — ctrl-tree path root keyword (default :session).
                        Controls become [path-root ctrl-name] paths in the ctrl tree.
    :apply-ctrl-tree? — when true (default), wire :control-tree into the ctrl system.

  Example:
    (session/load-session! my-session)
    (session/load-session! my-session :path-root :voice :apply-ctrl-tree? false)"
  [session & {:keys [path-root apply-ctrl-tree?]
               :or   {path-root :session apply-ctrl-tree? true}}]
  (let [graph (session->graph session)]
    (reset! active-session-atom {:session     session
                                 :graph       graph
                                 :path-root   path-root})
    (kairos/send-graph-load! graph)
    (when (and apply-ctrl-tree? (:control-tree session))
      (wire-control-tree! (:control-tree session) path-root))
    graph))

(defn active-session
  "Return the currently loaded Session map, or nil."
  []
  (:session @active-session-atom))

(defn reload-session!
  "Re-send the active session graph to kairos. No-op when nothing is loaded.

  Call this from a supervisor :restore-fn to recover after a kairos restart:

    (supervisor/register-rt!
      :restore-fn #(session/reload-session!))"
  []
  (when-let [{:keys [graph]} @active-session-atom]
    (kairos/send-graph-load! graph)
    graph))

(defn clear-session!
  "Unload the active session and send graph-reset! to kairos.
  Also clears any nodes placed via place-synth!."
  []
  (reset! active-session-atom nil)
  (reset! placed-nodes {})
  (kairos/send-graph-reset!)
  nil)

;; ---------------------------------------------------------------------------
;; place-synth! dispatch — CLAP backend
;; ---------------------------------------------------------------------------

(defn clear-placed-nodes!
  "Remove all nodes placed via place-synth! and send graph-reset! to kairos.
  Does not affect the active load-session! graph."
  []
  (reset! placed-nodes {})
  (kairos/send-graph-reset!)
  nil)

(defmethod synth-ns/place-synth! :clap
  [node-id synth-name & {:keys [params]}]
  (let [s         (synth-ns/get-synth synth-name)
        plugin-id (:clap/plugin-id s)
        merged    (merge (:args s) params)]
    (swap! placed-nodes assoc node-id {:plugin plugin-id :params merged})
    (let [nodes (mapv (fn [[id {:keys [plugin params]}]]
                        (cond-> {:id id :plugin plugin}
                          (seq params) (assoc :params params)))
                      @placed-nodes)]
      (kairos/send-graph-load! {:graph/nodes nodes :graph/edges []}))
    node-id))

; SPDX-License-Identifier: EPL-2.0
(ns nous.bitwig
  "OSC-based Bitwig Studio adapter — ctrl tree ↔ bwosc peer.

  Translates between the OSC peer subscription protocol
  (/nous/bitwig/val) and the nous ctrl tree [:bitwig ...] subtree.

  ## Peer protocol

  bwosc beacons on the nous discovery multicast with:
    :osc-port  — port bwosc listens on for nous→bitwig commands (default 7179)
    :http-port — port bwosc HTTP snapshot server listens on (default 7178)

  On connect nous:
    1. GETs http://<host>:<http-port>/tree to bulk-load the initial ctrl tree snapshot.
    2. Sends /nous/bitwig/sub [:bitwig] to subscribe to the full [:bitwig] subtree.
    3. Registers a global ctrl watcher to push [:bitwig ...] writes to bwosc.
    4. Registers an inbound handler for /nous/bitwig/val from bwosc.

  Both directions use the same message format:
    /nous/bitwig/val <path-edn-str> <value>
  where <path-edn-str> is a printed EDN path vector, e.g. \"[:bitwig :track :lead-synth :volume]\".

  ## Echo suppression

  When bwosc pushes a value to nous, nous writes it to the ctrl tree. That write
  would normally fire the global watch and push the value back to bwosc. Echo
  suppression: the inbound path is added to `inbound-paths` before ctrl/set!, and the
  global watch skips outbound dispatch for any path currently in `inbound-paths`. This
  works because ctrl watches fire synchronously on the ctrl/set! calling thread.

  ## Quick start

    (peer/start-discovery!)
    (bitwig/connect!)           ; auto-connects to discovered bwosc peer
    (ctrl/get [:bitwig :track :lead-synth :volume])  ;=> 0.73
    (ctrl/set! [:bitwig :track :lead-synth :volume] 0.8) ; pushes to Bitwig

  Or with explicit coordinates:
    (bitwig/connect! \"192.168.1.42\" 7179 7178)"
  (:require [clojure.edn    :as edn]
            [nous.ctrl      :as ctrl]
            [nous.osc       :as osc]
            [nous.peer      :as peer]))

;; ---------------------------------------------------------------------------
;; Private state
;; ---------------------------------------------------------------------------

;; Current bwosc connection — {:host s :port i} or nil.
(defonce ^:private conn-atom (atom nil))

;; Paths currently being processed as inbound from bwosc.
;; Used for echo suppression — see namespace docstring.
(defonce ^:private inbound-paths (atom #{}))

(declare disconnect!)

(def ^:private watch-key    ::bitwig-global)
(def ^:private val-address  "/nous/bitwig/val")
(def ^:private sub-address  "/nous/bitwig/sub")
(def ^:private unsub-address "/nous/bitwig/unsub")

;; ---------------------------------------------------------------------------
;; Injectable send fn — rebind in tests to capture outbound calls
;; ---------------------------------------------------------------------------

(def ^:dynamic *send-fn*
  "OSC send implementation: (fn [host port address & args]).
  Defaults to osc/osc-send!. Rebind in tests to capture outbound calls."
  nil)   ; forward-declared nil; resolved to osc/osc-send! at call time

(defn- send! [host port address & args]
  (apply (or *send-fn* osc/osc-send!) host port address args))

;; ---------------------------------------------------------------------------
;; Path ↔ EDN string encoding
;; ---------------------------------------------------------------------------

(defn- encode-path [path]
  (pr-str path))

(defn- decode-path [s]
  (try
    (let [v (edn/read-string s)]
      (when (vector? v) v))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; HTTP snapshot — initial bulk load
;; ---------------------------------------------------------------------------

(def ^:dynamic *http-get*
  "HTTP GET implementation: (fn [url-str]) → body-string or nil.
  Rebind in tests to inject mock responses."
  (fn [url-str]
    (try (slurp url-str)
         (catch Exception _ nil))))

(defn- flatten-tree
  "Flatten nested EDN map to {path leaf-value} pairs.
  Only non-map values generate path entries."
  [prefix m]
  (reduce-kv
    (fn [acc k v]
      (let [path (conj prefix k)]
        (if (map? v)
          (merge acc (flatten-tree path v))
          (assoc acc path v))))
    {}
    m))

(defn- load-snapshot! [host http-port]
  (when-let [body (*http-get* (str "http://" host ":" http-port "/tree"))]
    (try
      (let [tree (edn/read-string body)]
        (when (map? tree)
          (doseq [[path value] (flatten-tree [:bitwig] tree)]
            (ctrl/set! path value))))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Outbound: ctrl tree → bwosc
;; ---------------------------------------------------------------------------

(defn- dispatch! [path value]
  (when-let [{:keys [host port]} @conn-atom]
    (send! host port val-address (encode-path path) (osc/coerce-for-osc value))))

(defn- register-watch! []
  (ctrl/watch-global!
    watch-key
    (fn [tx _state]
      (let [{:keys [path after]} (first (:tx/changes tx))]
        (when (and (= :bitwig (first path))
                   (not (contains? @inbound-paths path)))
          (dispatch! path after))))))

;; ---------------------------------------------------------------------------
;; Inbound: bwosc → ctrl tree
;; ---------------------------------------------------------------------------

(defn- on-val! [args]
  (let [[path-str value] args]
    (when-let [path (decode-path path-str)]
      (when (= :bitwig (first path))
        (swap! inbound-paths conj path)
        (ctrl/set! path value)
        (swap! inbound-paths disj path)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect to a bwosc peer and subscribe to the [:bitwig] ctrl subtree.

  Arity 0: reads connection coordinates from the :bitwig entry in the peer
           discovery registry. Requires peer/start-discovery! to have run.
  Arity 3: explicit host, osc-port, http-port.

  Idempotent: disconnects any existing connection before reconnecting.

  Example:
    (bitwig/connect!)
    (bitwig/connect! \"192.168.1.42\" 7179 7178)"
  ([]
   (let [{:keys [host osc-port http-port]} (peer/peer-info :bitwig)]
     (when-not host
       (throw (ex-info "bwosc not discovered; start peer discovery first" {})))
     (connect! host osc-port http-port)))
  ([host osc-port http-port]
   (when @conn-atom (disconnect!))
   (let [nous-port (osc/osc-port)]
     (reset! conn-atom {:host host :port osc-port :nous-port nous-port})
     (load-snapshot! host http-port)
     (send! host osc-port sub-address (encode-path [:bitwig]) nous-port)
     (osc/on-msg! val-address on-val!)
     (register-watch!)
     nil)))

(defn disconnect!
  "Disconnect from bwosc, send /unsub, and unregister all listeners.

  Example:
    (bitwig/disconnect!)"
  []
  (when-let [{:keys [host port nous-port]} @conn-atom]
    (try (send! host port unsub-address (encode-path [:bitwig]) nous-port)
         (catch Exception _ nil)))
  (ctrl/unwatch-global! watch-key)
  (osc/off-msg! val-address)
  (reset! conn-atom nil)
  (reset! inbound-paths #{})
  nil)

(defn connected?
  "Return true if currently connected to a bwosc peer."
  []
  (boolean @conn-atom))

; SPDX-License-Identifier: EPL-2.0
(ns nous.kairos
  "kairos IPC client — CLAP plugin host operations and process management.

  The connection layer is provided by nous.rt.  This namespace adds:
  - kairos-specific message types and framing helpers
  - CLAP graph management (send-graph-load!, send-graph-reset!)
  - Plugin discovery (list-plugins!, plugin-registry)
  - WASM hot-swap (send-wasm-hot-swap!)
  - Process lifecycle with Ableton Link control
  - Runtime status integration ([:kairos :status] in the ctrl-tree)

  Lifecycle:
    (kairos/start-kairos! :binary \"/usr/local/bin/kairos\")
    (kairos/send-graph-load! my-graph)
    (kairos/send-param-set! [:synth/a :freq] 440.0)
    (kairos/stop-kairos!)

  Or, attaching to an already-running kairos:
    (kairos/connect!)
    (kairos/disconnect!)

  Graph EDN shape:
    {:graph/nodes [{:id :kw :plugin \"plugin.id\" :params {}}]
     :graph/edges [[:from-node :from-port :to-node :to-port]]}"
  (:require [clojure.edn    :as edn]
            [nous.rt        :as rt]
            [nous.runtime   :as runtime])
  (:import [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; Delegate connection and tick primitives to nous.rt
;; ---------------------------------------------------------------------------

(defn connected?              [] (rt/connected?))
(defn on-tick!                [h] (rt/on-tick! h))
(defn off-tick!               [k] (rt/off-tick! k))
(defn estimated-beat          [] (rt/estimated-beat))
(def  ^:dynamic *bar-beats*   rt/*bar-beats*)
(def  midi-in-messages        rt/midi-in-messages)
(defn await-midi-message      [pred & opts] (apply rt/await-midi-message pred opts))
(defn register-push-handler!  [msg-type handler] (rt/register-push-handler! msg-type handler))

;; ---------------------------------------------------------------------------
;; Message type constants (must match kairos/ipc.hpp)
;; ---------------------------------------------------------------------------

(def ^:private MSG-TX-LOG               (unchecked-byte 0x30))
(def ^:private MSG-SESSION-OPEN         (unchecked-byte 0x31))
(def ^:private MSG-SESSION-CLOSE        (unchecked-byte 0x32))
(def ^:private MSG-REGISTER-SOURCE      (unchecked-byte 0x33))
(def ^:private MSG-GRAPH-LOAD           (unchecked-byte 0x34))
(def ^:private MSG-GRAPH-RESET          (unchecked-byte 0x35))
(def ^:private MSG-PLUGIN-LIST-REQ      (unchecked-byte 0x36))
(def ^:private MSG-PLUGIN-LIST-RESP     (unchecked-byte 0x37))
(def ^:private MSG-LINK-SET-TEMPO       (unchecked-byte 0x38))
(def ^:private MSG-LINK-START-TRANSPORT (unchecked-byte 0x39))
(def ^:private MSG-LINK-STOP-TRANSPORT  (unchecked-byte 0x3A))
(def ^:private MSG-PARAM-SET            (unchecked-byte 0x40))
(def ^:private MSG-NOTE-ON              (unchecked-byte 0x41))
(def ^:private MSG-NOTE-OFF             (unchecked-byte 0x42))
(def ^:private MSG-MIDI-IN              (unchecked-byte 0x43))
(def ^:private MSG-WASM-HOT-SWAP        (unchecked-byte 0x44))
(def ^:private MSG-SCHEDULE-BUNDLE      (unchecked-byte 0x45))
(def ^:private MSG-MODULATOR-START      (unchecked-byte 0x46))
(def ^:private MSG-MODULATOR-STOP       (unchecked-byte 0x47))
(def ^:private MSG-MODULATOR-UPDATE     (unchecked-byte 0x48))
(def ^:private MSG-CC                   (unchecked-byte 0x49))
(def ^:private MSG-PITCH-BEND           (unchecked-byte 0x4A))
(def ^:private MSG-CHAN-PRESSURE         (unchecked-byte 0x4B))
(def ^:private MSG-SYSEX                (unchecked-byte 0x4C))
(def ^:private MSG-MTS                  (unchecked-byte 0x4D))
(def ^:private MSG-TICK                 (unchecked-byte 0x50))
(def ^:private MSG-MIDI-EVENT           (unchecked-byte 0x51))
(def ^:private MSG-ROUTE-SET            (unchecked-byte 0x52))
(def ^:private MSG-GRAPH-LOAD-ACK       (unchecked-byte 0x53))

;; ---------------------------------------------------------------------------
;; Frame serialization — kept here so @#'kairos/make-frame still works in tests
;; ---------------------------------------------------------------------------

(defn- edn-bytes ^bytes [v] (.getBytes ^String (pr-str v) "UTF-8"))

(defn- make-frame
  ^bytes [msg-type ^bytes payload]
  (let [plen (alength payload)
        buf  (ByteBuffer/allocate (+ 8 plen))]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.putInt buf plen)
    (.put    buf ^byte msg-type)
    (.put    buf (byte 0))
    (.put    buf (byte 0))
    (.put    buf (byte 0))
    (.put    buf payload)
    (.array buf)))

;; ---------------------------------------------------------------------------
;; Graph-load ack and plugin list response push handlers
;; ---------------------------------------------------------------------------

(def ^:private _graph-load-ack-handler
  (register-push-handler!
   0x53
   (fn [^bytes payload]
     (let [info (edn/read-string (String. payload "UTF-8"))]
       (binding [*out* *err*]
         (println "[kairos] graph loaded:" info))))))

(defonce ^:private plugin-registry-state
  (atom {:version 0 :plugins nil}))

(def ^:private _plugin-list-resp-handler
  (register-push-handler!
   0x37
   (fn [^bytes payload]
     (let [plugins (edn/read-string (String. payload "UTF-8"))]
       (swap! plugin-registry-state
              (fn [s] {:version (inc (:version s)) :plugins plugins}))))))

;; ---------------------------------------------------------------------------
;; Connection management
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect to a running kairos process.

  Options:
    :socket-path — Unix domain socket path (default \"/tmp/kairos.sock\")
    :retry       — connection attempts before throwing (default 5)

  Returns true on success."
  [& {:keys [socket-path retry]
      :or   {socket-path "/tmp/kairos.sock" retry 5}}]
  (when (rt/connected?)
    (throw (ex-info "Already connected to kairos; call disconnect! first" {})))
  (rt/connect! socket-path {:clap true :audio true} :retry retry)
  (runtime/set! [:kairos :status] :connected)
  (println (str "[nous.kairos] connected to " socket-path))
  true)

(defn disconnect!
  "Close the kairos connection. Does not kill a managed subprocess."
  []
  (rt/disconnect!)
  (runtime/set! [:kairos :status] :disconnected)
  (println "[nous.kairos] disconnected")
  nil)

(defn connect-at-next-bar!
  "Reconnect to kairos at the next bar boundary.
  bar-beats defaults to *bar-beats*.  Spawns a daemon thread and returns immediately."
  ([] (connect-at-next-bar! *bar-beats*))
  ([bar-beats] (rt/connect-at-next-bar! bar-beats)))

;; ---------------------------------------------------------------------------
;; Process management
;; ---------------------------------------------------------------------------

(def ^:dynamic *process-launcher*
  "Delegate to nous.rt/*process-launcher*.  Bind at the rt level to override
  process spawning for both kairos and aion."
  rt/*process-launcher*)

(defn start-kairos!
  "Launch kairos as a managed subprocess and connect to it.

  Options:
    :binary      — path to the kairos binary (required on first call)
    :socket-path — Unix domain socket path (default \"/tmp/kairos.sock\")
    :args        — extra CLI arguments (default [])
    :retry       — connection attempts after launch (default 10)
    :wait-ms     — ms to wait after launch before first connect attempt (default 200)

  Publishes [:kairos :status] :starting → :connected on success, :error on failure."
  [& {:keys [binary socket-path args retry wait-ms]
      :or   {socket-path "/tmp/kairos.sock" args [] retry 10 wait-ms 200}}]
  (let [bin (or binary (get-in (rt/last-start-opts) [:binary]))]
    (when-not bin
      (throw (ex-info "start-kairos!: :binary is required" {})))
    (runtime/set! [:kairos :status] :starting)
    (try
      (let [proc (rt/start-process! bin socket-path {:clap true :audio true}
                                    :args args :retry retry :wait-ms wait-ms)]
        (runtime/set! [:kairos :status] :connected)
        proc)
      (catch Exception e
        (runtime/set! [:kairos :status] :error)
        (throw e)))))

(defn stop-kairos!
  "Kill the managed kairos subprocess (if running) and disconnect."
  []
  (rt/stop-process!)
  (runtime/set! [:kairos :status] :disconnected)
  nil)

(defn restart-kairos!
  "Kill and relaunch kairos using the opts from the last start-kairos! call."
  []
  (runtime/set! [:kairos :status] :starting)
  (try
    (rt/restart-process!)
    (runtime/set! [:kairos :status] :connected)
    (catch Exception e
      (runtime/set! [:kairos :status] :error)
      (throw e))))

;; ---------------------------------------------------------------------------
;; Session
;; ---------------------------------------------------------------------------

(defn send-session-open! [db-path]
  (rt/send-frame! (make-frame MSG-SESSION-OPEN (.getBytes ^String (str db-path) "UTF-8"))))

(defn send-session-close! []
  (rt/send-frame! (make-frame MSG-SESSION-CLOSE (byte-array 0))))

(defn send-register-source! [id name & [description]]
  (rt/send-frame! (make-frame MSG-REGISTER-SOURCE
                              (edn-bytes {:id id :name (str name) :description (str description)}))))

;; ---------------------------------------------------------------------------
;; Graph management
;; ---------------------------------------------------------------------------

(def midi-passthrough-plugin-id
  "CLAP plugin ID for the kairos built-in MIDI passthrough."
  "org.nomos-studio.kairos.midi-passthrough")

(def midi-passthrough-graph
  "Minimal plugin graph that routes IPC note events to hardware MIDI output."
  {:graph/nodes [{:id :pt :plugin "org.nomos-studio.kairos.midi-passthrough"}]
   :graph/edges []})

(def surge-xt-plugin-id
  "CLAP plugin ID for Surge XT."
  "Surge Synth Team:Surge XT")

(def surge-xt-graph
  "Minimal plugin graph that drives SurgeXT audio via kairos."
  {:graph/nodes [{:id :surge-1 :plugin "Surge Synth Team:Surge XT"}]
   :graph/edges []})

(defn send-graph-load! [graph]
  (rt/send-frame! (make-frame MSG-GRAPH-LOAD (edn-bytes graph))))

(defn send-graph-reset! []
  (rt/send-frame! (make-frame MSG-GRAPH-RESET (byte-array 0))))

;; ---------------------------------------------------------------------------
;; Plugin listing
;; ---------------------------------------------------------------------------

(defn plugin-registry
  "Return the most recently received plugin registry snapshot, or nil."
  []
  (:plugins @plugin-registry-state))

(defn list-plugins!
  "Request the list of installed CLAP plugins from kairos and block until the
  response arrives.  Returns a vector of plugin info maps, or nil on timeout.

  Options:
    :extra-paths — extra directories to scan beyond platform defaults
    :timeout-ms  — give up after this many ms (default 5000)"
  [& {:keys [extra-paths timeout-ms] :or {extra-paths [] timeout-ms 5000}}]
  (let [v0       (:version @plugin-registry-state)
        payload  (if (seq extra-paths)
                   (edn-bytes {:extra-paths (vec extra-paths)})
                   (byte-array 0))
        deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (rt/send-frame! (make-frame MSG-PLUGIN-LIST-REQ payload))
    (loop []
      (let [{:keys [version plugins]} @plugin-registry-state]
        (cond
          (> version v0)                          plugins
          (> (System/currentTimeMillis) deadline) nil
          :else                                   (do (Thread/sleep 10) (recur)))))))

;; ---------------------------------------------------------------------------
;; Ableton Link control
;; ---------------------------------------------------------------------------

(defn send-link-set-tempo! [bpm]
  (rt/send-frame! (make-frame MSG-LINK-SET-TEMPO (edn-bytes {:bpm (double bpm)}))))

(defn send-link-start-transport! []
  (rt/send-frame! (make-frame MSG-LINK-START-TRANSPORT (byte-array 0))))

(defn send-link-stop-transport! []
  (rt/send-frame! (make-frame MSG-LINK-STOP-TRANSPORT (byte-array 0))))

;; ---------------------------------------------------------------------------
;; Parameter control
;; ---------------------------------------------------------------------------

(defn send-param-set! [path value]
  (rt/send-frame! (make-frame MSG-PARAM-SET
                              (edn-bytes {:path path :value value :time {}}))))

;; ---------------------------------------------------------------------------
;; Note / MIDI events
;; ---------------------------------------------------------------------------

(defn send-note-on!
  "Send a note-on event to kairos.

  key      — MIDI note number 0–127
  velocity — 0.0–1.0

  Options: :channel (default 0), :port (default 0), :note-id (default -1),
           :beat — Link beat position (nil = immediate)"
  [key velocity & {:keys [channel port note-id beat]
                   :or   {channel 0 port 0 note-id -1}}]
  (let [base {:port port :channel channel :key key
              :velocity (double velocity) :note-id note-id}]
    (rt/send-frame! (make-frame MSG-NOTE-ON
                                (edn-bytes (cond-> base beat (assoc :beat (double beat))))))))

(defn send-note-off!
  "Send a note-off event to kairos.

  key — MIDI note number 0–127

  Options: :channel (default 0), :port (default 0), :note-id (default -1),
           :beat — Link beat position (nil = immediate)"
  [key & {:keys [channel port note-id beat]
          :or   {channel 0 port 0 note-id -1}}]
  (let [base {:port port :channel channel :key key
              :velocity 0.0 :note-id note-id}]
    (rt/send-frame! (make-frame MSG-NOTE-OFF
                                (edn-bytes (cond-> base beat (assoc :beat (double beat))))))))

(defn send-cc!
  [channel controller value & {:keys [port] :or {port 0}}]
  (rt/send-frame! (make-frame MSG-CC
                              (edn-bytes {:port port :channel channel
                                          :controller controller :value value}))))

(defn send-pitch-bend!
  [channel bend & {:keys [port] :or {port 0}}]
  (rt/send-frame! (make-frame MSG-PITCH-BEND
                              (edn-bytes {:port port :channel channel
                                          :bend (long bend)}))))

(defn send-channel-pressure!
  [channel pressure & {:keys [port] :or {port 0}}]
  (rt/send-frame! (make-frame MSG-CHAN-PRESSURE
                              (edn-bytes {:port port :channel channel
                                          :pressure pressure}))))

(defn send-sysex!
  [data & {:keys [port] :or {port 0}}]
  (rt/send-frame! (make-frame MSG-SYSEX
                              (edn-bytes {:port port :data (vec data)}))))

(defn send-mts!
  [tuning & {:keys [port tuning-prog device-id]
             :or   {port 0 tuning-prog 0 device-id :all}}]
  (if (bytes? tuning)
    (send-sysex! tuning :port port)
    (rt/send-frame! (make-frame MSG-MTS
                                (edn-bytes {:port        port
                                             :tuning      tuning
                                             :tuning-prog (long tuning-prog)
                                             :device-id   device-id})))))

(defn send-midi-in!
  [data & {:keys [port] :or {port 0}}]
  (rt/send-frame! (make-frame MSG-MIDI-IN
                              (edn-bytes {:port port
                                          :data (vec (take 3 data))}))))

;; ---------------------------------------------------------------------------
;; Beat-accurate bundle scheduling
;; ---------------------------------------------------------------------------

(defn schedule-bundle!
  "Schedule a bundle of beat-accurate events for delivery by kairos.

  at-beat — Link beat at which tick-0 fires.
  events  — vector of event maps with :at-tick, :type, :key, :velocity, etc."
  [at-beat events]
  (rt/send-frame! (make-frame MSG-SCHEDULE-BUNDLE
                              (edn-bytes
                               {:at-beat (double at-beat)
                                :events  (mapv (fn [{:keys [at-tick type key velocity
                                                             channel port note-id]
                                                     :or   {at-tick 0 type :note-on key 60
                                                            velocity 0.8 channel 0 port 0
                                                            note-id -1}}]
                                                 {:at-tick  (long at-tick)
                                                  :type     type
                                                  :key      (long key)
                                                  :velocity (double velocity)
                                                  :channel  (long channel)
                                                  :port     (long port)
                                                  :note-id  (long note-id)})
                                               events)}))))

;; ---------------------------------------------------------------------------
;; WASM DSP hot-swap
;; ---------------------------------------------------------------------------

(defn send-wasm-hot-swap!
  [node-id wasm-path & {:keys [replace-path]}]
  (let [payload (cond-> {:node-id   node-id
                          :wasm-path (str wasm-path)}
                  replace-path (assoc :old-wasm-path (str replace-path)))]
    (rt/send-frame! (make-frame MSG-WASM-HOT-SWAP (edn-bytes payload)))))

;; ---------------------------------------------------------------------------
;; Routing matrix (aion)
;; ---------------------------------------------------------------------------

(defn send-route-set! [routes]
  (rt/send-frame! (make-frame MSG-ROUTE-SET (edn-bytes routes))))

;; ---------------------------------------------------------------------------
;; RT modulator engine
;; ---------------------------------------------------------------------------

(defn start-modulator!
  [id type & [opts]]
  (rt/send-frame! (make-frame MSG-MODULATOR-START
                              (edn-bytes (merge {:id id :type type} opts)))))

(defn stop-modulator! [id]
  (rt/send-frame! (make-frame MSG-MODULATOR-STOP (edn-bytes {:id id}))))

(defn update-modulator! [id key value]
  (rt/send-frame! (make-frame MSG-MODULATOR-UPDATE
                              (edn-bytes {:id id :key (name key) :value (double value)}))))

;; ---------------------------------------------------------------------------
;; TX log
;; ---------------------------------------------------------------------------

(defn send-tx-log! [entry]
  (rt/send-frame! (make-frame MSG-TX-LOG (edn-bytes entry))))

; SPDX-License-Identifier: EPL-2.0
(ns cljseq.kairos
  "kairos IPC client.

  Connects to a running kairos process via Unix domain socket and sends
  EDN-payload control frames.  kairos must be running before connect! is called;
  this namespace does not spawn the process.

  Wire format (network byte order / big-endian uint32 length prefix):
    [uint32 payload_len][uint8 msg_type][uint8 reserved ×3][UTF-8 EDN payload]

  Typical REPL session:
    (kairos/connect!)                          ; default /tmp/kairos.sock
    (kairos/send-graph-load! my-graph)
    (kairos/send-param-set! [:synth/a :freq] 440.0)
    (kairos/disconnect!)

  Graph EDN shape:
    {:graph/nodes [{:id :kw :plugin \"plugin.id\" :params {}}]
     :graph/edges [[:from-node :from-port :to-node :to-port]]}"
  (:require [clojure.string :as str])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels SocketChannel Channels]
           [java.io InputStream OutputStream]))

;; ---------------------------------------------------------------------------
;; Connection state
;; ---------------------------------------------------------------------------

(def ^:private kairos-state
  (atom {:channel       nil   ; java.nio.channels.SocketChannel
         :out           nil   ; java.io.OutputStream (synchronized on send)
         :in            nil   ; java.io.InputStream  (reader thread)
         :socket-path   nil   ; String — path this connection was opened on
         :push-handlers {}})) ; msg-type → (fn [^bytes payload])

(defn connected?
  "Return true if a kairos connection is active."
  []
  (boolean (:out @kairos-state)))

;; ---------------------------------------------------------------------------
;; Message type constants (must match kairos/ipc.hpp)
;; ---------------------------------------------------------------------------

(def ^:private MSG-TX-LOG          (unchecked-byte 0x30))
(def ^:private MSG-SESSION-OPEN    (unchecked-byte 0x31))
(def ^:private MSG-SESSION-CLOSE   (unchecked-byte 0x32))
(def ^:private MSG-REGISTER-SOURCE (unchecked-byte 0x33))
(def ^:private MSG-GRAPH-LOAD      (unchecked-byte 0x34))
(def ^:private MSG-GRAPH-RESET     (unchecked-byte 0x35))
(def ^:private MSG-PARAM-SET       (unchecked-byte 0x40))
(def ^:private MSG-NOTE-ON         (unchecked-byte 0x41))
(def ^:private MSG-NOTE-OFF        (unchecked-byte 0x42))
(def ^:private MSG-MIDI-IN         (unchecked-byte 0x43))
(def ^:private MSG-WASM-HOT-SWAP   (unchecked-byte 0x44))

;; ---------------------------------------------------------------------------
;; Frame serialization
;; ---------------------------------------------------------------------------

(defn- edn-bytes
  "Encode v as a UTF-8 EDN string. pr-str produces valid EDN for all types
  cljseq passes over this channel (maps, keywords, strings, numbers, UUIDs,
  vectors)."
  ^bytes [v]
  (.getBytes ^String (pr-str v) "UTF-8"))

(defn- make-frame
  "Build a complete IPC frame: [uint32 len (big-endian)][uint8 type][3 reserved][payload]."
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

(defn- send-frame!
  "Write a frame to the kairos socket. Synchronized on the output stream."
  [^bytes frame]
  (when-let [^OutputStream out (:out @kairos-state)]
    (locking out
      (.write out frame)
      (.flush out))))

;; ---------------------------------------------------------------------------
;; Inbound frame reader
;; ---------------------------------------------------------------------------

(defn register-push-handler!
  "Register a handler for inbound push frames from kairos.
  msg-type — raw uint8 message type byte.
  handler  — (fn [^bytes payload]) called on the reader thread.

  Registrations survive disconnect!/connect! cycles."
  [msg-type handler]
  (swap! kairos-state assoc-in [:push-handlers msg-type] handler))

(defn- read-fully!
  "Read exactly n bytes from in into buf starting at offset. Returns false on EOF."
  [^InputStream in ^bytes buf ^long offset ^long n]
  (loop [remaining n pos offset]
    (if (zero? remaining)
      true
      (let [read (.read in buf (int pos) (int remaining))]
        (if (neg? read)
          false
          (recur (- remaining read) (+ pos read)))))))

(defn- start-reader!
  "Start a daemon thread reading inbound frames and dispatching to push handlers."
  [^InputStream in]
  (let [t (Thread.
            (fn []
              (let [header (byte-array 8)]
                (try
                  (loop []
                    (when (read-fully! in header 0 8)
                      (let [buf      (doto (ByteBuffer/wrap header)
                                       (.order ByteOrder/BIG_ENDIAN))
                            plen     (.getInt buf)
                            msg-type (bit-and (.get buf) 0xFF)
                            payload  (byte-array plen)]
                        (when (or (zero? plen) (read-fully! in payload 0 plen))
                          (when-let [h (get (:push-handlers @kairos-state) msg-type)]
                            (try (h payload)
                                 (catch Exception e
                                   (binding [*out* *err*]
                                     (println "[cljseq.kairos] push handler error:" e))))))
                        (recur))))
                  (catch Exception _)))))]
    (.setDaemon t true)
    (.setName  t "cljseq-kairos-reader")
    (.start t)))

;; ---------------------------------------------------------------------------
;; Connection management
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect to a running kairos process.

  Options:
    :socket-path — Unix domain socket path (default \"/tmp/kairos.sock\")
    :retry       — connection attempts before throwing (default 5)

  Returns true on success.

  Example:
    (kairos/connect!)
    (kairos/connect! :socket-path \"/tmp/my-kairos.sock\")"
  [& {:keys [socket-path retry]
      :or   {socket-path "/tmp/kairos.sock" retry 5}}]
  (when (connected?)
    (throw (ex-info "Already connected to kairos; call disconnect! first" {})))
  (let [ch (loop [attempt 0 delay-ms 50]
              (when (> attempt retry)
                (throw (ex-info "Could not connect to kairos"
                                {:socket-path socket-path :attempts attempt})))
              (or (try
                    (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                      (.connect (UnixDomainSocketAddress/of socket-path)))
                    (catch java.net.ConnectException _ nil)
                    (catch java.nio.file.NoSuchFileException _ nil))
                  (do (Thread/sleep delay-ms)
                      (recur (inc attempt) (min 500 (* 2 delay-ms))))))]
    (let [out (Channels/newOutputStream ch)
          in  (Channels/newInputStream  ch)]
      (swap! kairos-state assoc
             :channel     ch
             :out         out
             :in          in
             :socket-path socket-path)
      (start-reader! in)
      (println (str "[cljseq.kairos] connected to " socket-path))
      true)))

(defn disconnect!
  "Close the kairos connection."
  []
  (when-let [^SocketChannel ch (:channel @kairos-state)]
    (try (.close ch) (catch Exception _)))
  (swap! kairos-state assoc :channel nil :out nil :in nil :socket-path nil)
  (println "[cljseq.kairos] disconnected")
  nil)

;; ---------------------------------------------------------------------------
;; Session
;; ---------------------------------------------------------------------------

(defn send-session-open!
  "Open a txlog session at db-path on the kairos side.

  Example:
    (kairos/send-session-open! \"/path/to/session.db\")"
  [db-path]
  (send-frame! (make-frame MSG-SESSION-OPEN
                           (.getBytes ^String (str db-path) "UTF-8"))))

(defn send-session-close!
  "Close the active txlog session."
  []
  (send-frame! (make-frame MSG-SESSION-CLOSE (byte-array 0))))

(defn send-register-source!
  "Register a txlog source.

  id          — keyword identifying the source (e.g. :cljseq/repl)
  name        — human-readable name string
  description — optional description string

  Example:
    (kairos/send-register-source! :cljseq/repl \"cljseq REPL\" \"live session\")"
  [id name & [description]]
  (send-frame! (make-frame MSG-REGISTER-SOURCE
                           (edn-bytes {:id          id
                                       :name        (str name)
                                       :description (str description)}))))

;; ---------------------------------------------------------------------------
;; Graph management
;; ---------------------------------------------------------------------------

(defn send-graph-load!
  "Load a plugin graph into kairos.

  graph — map with:
    :graph/nodes — vector of {:id kw :plugin \"plugin.id\" :params {}}
    :graph/edges — vector of [from-node-kw from-port-kw to-node-kw to-port-kw]

  Ports follow the convention :out-N / :in-N (e.g. :out-0, :in-0).
  Use :host as to-node to mark a node's output as the hardware output.

  Example:
    (kairos/send-graph-load!
      {:graph/nodes [{:id :synth/a :plugin \"com.example.MySynth\"}]
       :graph/edges [[:synth/a :out-0 :host :audio-out]]})"
  [graph]
  (send-frame! (make-frame MSG-GRAPH-LOAD (edn-bytes graph))))

(defn send-graph-reset!
  "Tear down the current plugin graph."
  []
  (send-frame! (make-frame MSG-GRAPH-RESET (byte-array 0))))

;; ---------------------------------------------------------------------------
;; Parameter control
;; ---------------------------------------------------------------------------

(defn send-param-set!
  "Set a parameter on a kairos plugin node.

  path  — EDN path to the parameter, e.g. [:synth/a :freq] or a keyword
  value — new parameter value (number, keyword, string, etc.)

  Example:
    (kairos/send-param-set! [:synth/a :freq] 440.0)
    (kairos/send-param-set! [:filter/lpf :cutoff] 0.3)"
  [path value]
  (send-frame! (make-frame MSG-PARAM-SET
                           (edn-bytes {:path path :value value :time {}}))))

;; ---------------------------------------------------------------------------
;; Note / MIDI events
;; ---------------------------------------------------------------------------

(defn send-note-on!
  "Send a note-on event to kairos.

  key      — MIDI note number 0–127
  velocity — 0.0–1.0

  Options:
    :channel — MIDI channel 0–15 (default 0)
    :port    — MIDI port index (default 0)
    :note-id — note identifier for per-note param access (default -1)

  Example:
    (kairos/send-note-on! 60 0.8)
    (kairos/send-note-on! 60 0.8 :channel 1 :note-id 42)"
  [key velocity & {:keys [channel port note-id]
                   :or   {channel 0 port 0 note-id -1}}]
  (send-frame! (make-frame MSG-NOTE-ON
                           (edn-bytes {:port     port
                                       :channel  channel
                                       :key      key
                                       :velocity (double velocity)
                                       :note-id  note-id}))))

(defn send-note-off!
  "Send a note-off event to kairos.

  key — MIDI note number 0–127

  Options:
    :channel — MIDI channel 0–15 (default 0)
    :port    — MIDI port index (default 0)
    :note-id — note identifier (default -1)

  Example:
    (kairos/send-note-off! 60)
    (kairos/send-note-off! 60 :note-id 42)"
  [key & {:keys [channel port note-id]
          :or   {channel 0 port 0 note-id -1}}]
  (send-frame! (make-frame MSG-NOTE-OFF
                           (edn-bytes {:port    port
                                       :channel channel
                                       :key     key
                                       :velocity 0.0
                                       :note-id note-id}))))

(defn send-midi-in!
  "Inject raw MIDI bytes into the kairos input event queue.

  data — sequence of 1–3 byte values (status, b1, b2)

  Options:
    :port — MIDI port index (default 0)

  Example:
    (kairos/send-midi-in! [0x90 60 100])       ; note-on ch1 C4 vel=100
    (kairos/send-midi-in! [0xB0 7 64] :port 1) ; CC vol on port 1"
  [data & {:keys [port] :or {port 0}}]
  (send-frame! (make-frame MSG-MIDI-IN
                           (edn-bytes {:port port
                                       :data (vec (take 3 data))}))))

;; ---------------------------------------------------------------------------
;; WASM DSP hot-swap
;; ---------------------------------------------------------------------------

(defn send-wasm-hot-swap!
  "Gapless hot-swap of the WASM DSP module for a graph node.

  node-id   — keyword identifying the target node (e.g. :synth/a)
  wasm-path — absolute path to the new .wasm file

  The swap is atomic (RCU): audio continues without glitch or gap.

  Example:
    (kairos/send-wasm-hot-swap! :synth/a \"/tmp/new-phasor.wasm\")"
  [node-id wasm-path]
  (send-frame! (make-frame MSG-WASM-HOT-SWAP
                           (edn-bytes {:node-id   node-id
                                       :wasm-path (str wasm-path)}))))

;; ---------------------------------------------------------------------------
;; TX log
;; ---------------------------------------------------------------------------

(defn send-tx-log!
  "Emit a txlog entry to the kairos session.

  entry — map with any subset of:
    :id      — java.util.UUID (pr-str produces #uuid \"…\" EDN literal)
    :beat    — double beat position
    :wall-ns — long epoch nanoseconds
    :source  — keyword identifying the source
    :path    — EDN value describing what changed
    :before  — prior value (nil = absent)
    :after   — new value

  Example:
    (kairos/send-tx-log!
      {:id      (java.util.UUID/randomUUID)
       :beat    8.0
       :wall-ns (System/nanoTime)
       :source  :cljseq/repl
       :path    [:synth/a :freq]
       :after   440.0})"
  [entry]
  (send-frame! (make-frame MSG-TX-LOG (edn-bytes entry))))

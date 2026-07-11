; SPDX-FileCopyrightText: 2025-2026 nomos-studio contributors
;
; SPDX-License-Identifier: EPL-2.0
(ns nous.rt
  "Unified IPC connection layer for all nomos-rt processes (aion, kairos, …).

  There is always at most one nomos-rt backend connected at a time.  Which backend
  is running is described by a capabilities map set at connect time:

    {:clap  true/false   — CLAP plugin host (kairos only)
     :audio true/false   — audio output (kairos only)}

  The wire protocol is identical regardless of backend: big-endian framed EDN over
  a Unix domain socket.

  Key API:
    (connect! \"/tmp/kairos.sock\" {:clap true :audio true})
    (disconnect!)
    (connected?)           ; true iff socket open and reader thread alive
    (capabilities)         ; the map passed to connect!
    (has-capability? :clap)
    (on-tick! handler)     ; register 24 PPQN tick callback
    (estimated-beat)

  Runtime status: connect! publishes [:rt :status] :connected;
  disconnect! publishes [:rt :status] :disconnected.
  nous.supervisor/register-rt! watches this path."
  (:require [clojure.edn    :as edn]
            [ctrl-tree.core :as ct]
            [nous.runtime   :as runtime])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels SocketChannel Channels]
           [java.io InputStream OutputStream]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private state
  (atom {:channel       nil   ; SocketChannel
         :out           nil   ; OutputStream (synchronized on send)
         :in            nil   ; InputStream (reader thread reads this)
         :reader-thread nil   ; Thread — nil or dead means not connected
         :socket-path   nil   ; preserved across disconnect! for reconnect
         :push-handlers {}    ; uint8 → (fn [^bytes payload])
         :capabilities  {}    ; e.g. {:clap true :audio true}
         :process       nil   ; java.lang.Process (managed subprocess)
         :last-start-opts nil}))

;; ---------------------------------------------------------------------------
;; Connection status
;; ---------------------------------------------------------------------------

(defn connected?
  "Return true only when the socket is open and the reader thread is alive.
  A non-nil stream with a dead reader thread means the connection broke silently."
  []
  (boolean
   (let [{:keys [out reader-thread]} @state]
     (and out
          reader-thread
          (.isAlive ^Thread reader-thread)))))

(defn capabilities
  "Return the capabilities map set when the current connection was established.
  Returns {} when not connected."
  []
  (or (:capabilities @state) {}))

(defn has-capability?
  "Return true if the connected backend advertises capability `cap`.
  e.g. (has-capability? :clap)"
  [cap]
  (get (capabilities) cap))

(defn last-start-opts
  "Return the opts map from the last start-process! call, or nil."
  []
  (:last-start-opts @state))

;; ---------------------------------------------------------------------------
;; Frame I/O
;; ---------------------------------------------------------------------------

(defn edn-bytes
  "Encode v as a UTF-8 EDN byte array."
  ^bytes [v]
  (.getBytes ^String (pr-str v) "UTF-8"))

(defn make-frame
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

(defn send-frame!
  "Write a frame to the nomos-rt socket. Synchronized on the output stream."
  [^bytes frame]
  (when-let [^OutputStream out (:out @state)]
    (locking out
      (.write out frame)
      (.flush out))))

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

;; ---------------------------------------------------------------------------
;; Push handler registry — survives connect/disconnect cycles
;; ---------------------------------------------------------------------------

(defn register-push-handler!
  "Register a handler for inbound push frames of type msg-type (raw uint8 int).
  handler — (fn [^bytes payload]) called on the reader thread.
  Registrations survive disconnect!/connect! cycles."
  [msg-type handler]
  (swap! state assoc-in [:push-handlers msg-type] handler))

;; ---------------------------------------------------------------------------
;; Tick system — MSG-TICK 0x50
;; ---------------------------------------------------------------------------

(def ^:private tick-handlers (atom {}))
(def ^:private tick-handler-counter (atom 0))

(def ^:private _tick-handler-registered
  (register-push-handler!
   0x50
   (fn [^bytes payload]
     (let [tick-ev (edn/read-string (String. payload "UTF-8"))]
       (doseq [[_ h] @tick-handlers]
         (try (h tick-ev)
              (catch Exception e
                (binding [*out* *err*]
                  (println "[nous.rt] on-tick! handler error:" e)))))))))

(defn on-tick!
  "Register a handler called on every 24 PPQN MSG-TICK push from the nomos-rt backend.
  handler — (fn [{:keys [beat tick-n]}])
  Returns a registration key for off-tick!."
  [handler]
  (let [k (swap! tick-handler-counter inc)]
    (swap! tick-handlers assoc k handler)
    k))

(defn off-tick!
  "Remove a tick handler registered with on-tick!. Returns nil."
  [k]
  (swap! tick-handlers dissoc k)
  nil)

;; ---------------------------------------------------------------------------
;; Beat tracking — updated by MSG-TICK frames
;; ---------------------------------------------------------------------------

(defonce ^:private beat-state
  (atom {:beat 0.0 :bpm 120.0 :wall-ns (System/nanoTime)}))

(def ^:private _beat-tracker
  (on-tick! (fn [{:keys [beat]}]
              (when (number? beat)
                (let [now  (System/nanoTime)
                      prev @beat-state
                      dt-s (/ (- now (:wall-ns prev)) 1.0e9)]
                  (when (> dt-s 0.01)
                    (let [bpm (-> (/ (- beat (:beat prev)) dt-s) (* 60.0)
                                  (max 20.0) (min 400.0))]
                      (reset! beat-state {:beat beat :bpm bpm :wall-ns now}))))))))

(defn estimated-beat
  "Return the estimated current beat, extrapolating from the last tick using wall time.
  Works when the backend is down — uses the last known BPM to project forward."
  []
  (let [{:keys [beat bpm wall-ns]} @beat-state
        elapsed-s (/ (- (System/nanoTime) wall-ns) 1.0e9)]
    (+ beat (* bpm (/ elapsed-s 60.0)))))

(def ^:dynamic *bar-beats*
  "Bar length in beats for connect-at-next-bar! (default 4)."
  4)

;; ---------------------------------------------------------------------------
;; MIDI input event buffer — MSG-MIDI-EVENT 0x51
;; ---------------------------------------------------------------------------

(def midi-in-messages
  "Ring-buffer (atom vector) of recent MIDI messages pushed by the backend.
  Each entry: {:port N :channel N :data [status b1 b2]}.  Capped at 256 entries."
  (atom []))

(def ^:private midi-in-max-buffer 256)

(def ^:private _midi-event-handler-registered
  (register-push-handler!
   0x51
   (fn [^bytes payload]
     (let [msg (edn/read-string (String. payload "UTF-8"))]
       (swap! midi-in-messages
              (fn [buf]
                (let [buf' (conj buf msg)]
                  (if (> (count buf') midi-in-max-buffer)
                    (subvec buf' (- (count buf') midi-in-max-buffer))
                    buf'))))))))

(defn await-midi-message
  "Block until a MIDI input message matching pred arrives from the backend.
  Returns the matching message or nil after timeout-ms.

  pred       — (fn [msg]) → truthy; msg is {:port N :channel N :data [status b1 b2]}
  timeout-ms — give up after this many ms (default 5000)"
  [pred & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [hit (first (filter pred @midi-in-messages))]
        (cond
          hit hit
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 10) (recur)))))))

;; ---------------------------------------------------------------------------
;; Diagnostic tap — MSG-MIDI-DIAG 0x54 (aion MIDI send confirmation)
;; ---------------------------------------------------------------------------

(def ^:dynamic *dispatch-diag*
  "Called with the parsed EDN data map from each MSG-MIDI-DIAG frame.
  Default writes to [:diagnostic :aion :midi_sent].
  Rebind in tests to capture without a running ctrl-tree."
  (fn [data] (ct/ctrl-write! [:diagnostic :aion :midi_sent] data)))

(defn- handle-diag-frame! [^String payload]
  (try
    (*dispatch-diag* (edn/read-string payload))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "[nous.rt] diag parse error: " (.getMessage e)))))))

(def ^:private _diag-handler-registered
  (register-push-handler!
   0x54
   (fn [^bytes payload]
     (handle-diag-frame! (String. payload "UTF-8")))))

;; ---------------------------------------------------------------------------
;; Keyboard layout — physical key → MIDI note number
;; ---------------------------------------------------------------------------

(def key->note
  "Physical key letter → MIDI note number (chromatic layout, middle C = 60)."
  {"a" 60  "w" 61  "s" 62  "e" 63  "d" 64
   "f" 65  "t" 66  "g" 67  "y" 68  "h" 69
   "u" 70  "j" 71})

(def ^:private MSG-NOTE-ON  (unchecked-byte 0x41))
(def ^:private MSG-NOTE-OFF (unchecked-byte 0x42))

(defn note-on!
  "Send a note-on for physical key character (e.g. \"a\"). velocity is 0.0–1.0."
  ([key] (note-on! key 0.8))
  ([key velocity]
   (when-let [note (key->note key)]
     (send-frame! (make-frame MSG-NOTE-ON
                              (edn-bytes {:key note :channel 0 :port 0
                                          :velocity (double velocity) :note-id -1}))))))

(defn note-off!
  "Send a note-off for physical key character."
  [key]
  (when-let [note (key->note key)]
    (send-frame! (make-frame MSG-NOTE-OFF
                             (edn-bytes {:key note :channel 0 :port 0
                                         :velocity 0.0 :note-id -1})))))

;; ---------------------------------------------------------------------------
;; Reader thread
;; ---------------------------------------------------------------------------

(defn- read-frame
  "Read one IPC frame from in. Returns {:type uint8 :payload raw-bytes} or nil on EOF."
  [^InputStream in]
  (let [header (byte-array 8)]
    (when (read-fully! in header 0 8)
      (let [buf      (doto (ByteBuffer/wrap header)
                       (.order ByteOrder/BIG_ENDIAN))
            plen     (.getInt buf)
            msg-type (bit-and (.get buf) 0xFF)
            payload  (byte-array plen)]
        (when (or (zero? plen) (read-fully! in payload 0 plen))
          {:type msg-type :payload payload})))))

(defn- start-reader! [^InputStream in]
  (let [t (Thread.
            (fn []
              (try
                (loop []
                  (when-let [{:keys [type payload]} (read-frame in)]
                    (when-let [h (get (:push-handlers @state) type)]
                      (try (h payload)
                           (catch Exception e
                             (binding [*out* *err*]
                               (println "[nous.rt] push handler error:" e)))))
                    (recur)))
                (catch Exception _)
                (finally
                  (swap! state assoc :out nil :reader-thread nil)))))]
    (.setDaemon t true)
    (.setName  t "nous-rt-reader")
    (.start t)
    t))

;; ---------------------------------------------------------------------------
;; Connection lifecycle
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect to a nomos-rt process at socket-path.

  caps — map describing what this backend supports, e.g.:
    {:clap true :audio true}   for kairos
    {:clap false :audio false} for aion (or simply {})

  Options:
    :retry — connection attempts before throwing (default 5)

  Returns true on success."
  [socket-path caps & {:keys [retry] :or {retry 5}}]
  (when (connected?)
    (throw (ex-info "Already connected to a nomos-rt backend; call disconnect! first" {})))
  (let [ch (loop [attempt 0 delay-ms 50]
              (when (> attempt retry)
                (throw (ex-info "Could not connect to nomos-rt backend"
                                {:socket-path socket-path :attempts attempt})))
              (or (try
                    (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                      (.connect (UnixDomainSocketAddress/of socket-path)))
                    (catch java.net.ConnectException _ nil)
                    (catch java.nio.file.NoSuchFileException _ nil)
                    (catch java.net.SocketException _ nil))
                  (do (Thread/sleep delay-ms)
                      (recur (inc attempt) (min 500 (* 2 delay-ms))))))]
    (let [out    (Channels/newOutputStream ch)
          in     (Channels/newInputStream  ch)
          reader (start-reader! in)]
      (swap! state assoc
             :channel       ch
             :out           out
             :in            in
             :reader-thread reader
             :socket-path   socket-path
             :capabilities  caps)
      (runtime/set! [:rt :status] :connected)
      true)))

(defn disconnect!
  "Close the connection to nomos-rt.  Preserves :socket-path and :capabilities
  so connect-at-next-bar! can reconnect without being told them again.
  Does not kill a managed subprocess — use stop-process! for that.
  Publishes [:rt :status] :disconnected."
  []
  (when-let [^SocketChannel ch (:channel @state)]
    (try (.close ch) (catch Exception _)))
  (swap! state assoc :channel nil :out nil :in nil :reader-thread nil)
  (runtime/set! [:rt :status] :disconnected)
  nil)

(defn connect-at-next-bar!
  "Reconnect at the next bar boundary using the last known socket-path and capabilities.
  bar-beats defaults to *bar-beats*.  Spawns a daemon thread and returns immediately."
  ([] (connect-at-next-bar! *bar-beats*))
  ([bar-beats]
   (let [now    (estimated-beat)
         target (* (Math/ceil (/ (+ now 1.0) (double bar-beats)))
                   (double bar-beats))
         {:keys [socket-path capabilities last-start-opts]} @state
         sp     (or socket-path (:socket-path last-start-opts))
         caps   (or (when (seq capabilities) capabilities)
                    (:capabilities last-start-opts) {})]
     (doto (Thread.
            (fn []
              (loop []
                (when (< (estimated-beat) target)
                  (Thread/sleep 20)
                  (recur)))
              (disconnect!)
              (try (connect! sp caps)
                   (catch Exception e
                     (binding [*out* *err*]
                       (println (str "[nous.rt] reconnect failed: " (.getMessage e)))))))
            "nous-rt-reconnect")
       (.setDaemon true)
       (.start)))))

;; ---------------------------------------------------------------------------
;; Process lifecycle
;; ---------------------------------------------------------------------------

(def ^:dynamic *process-launcher*
  "Launch a subprocess. Called by start-process! with [binary & args].
  Returns a java.lang.Process.
  Override in tests to inject a fake process."
  (fn [& cmd]
    (-> (ProcessBuilder. ^java.util.List (vec cmd))
        (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
        .start)))

(defn start-process!
  "Launch a nomos-rt subprocess and connect to it.

  binary      — path to the executable
  socket-path — Unix domain socket path
  caps        — capabilities map e.g. {:clap true :audio true}

  Options:
    :args    — extra CLI arguments (default [])
    :retry   — connection attempts after launch (default 10)
    :wait-ms — ms to wait after launch before first connect attempt (default 200)

  Returns the java.lang.Process."
  [binary socket-path caps & {:keys [args retry wait-ms]
                               :or   {args [] retry 10 wait-ms 200}}]
  (let [old-proc ^java.lang.Process (:process @state)]
    (when (and old-proc (not (.isAlive old-proc)))
      (swap! state assoc :process nil)))
  (when (connected?) (disconnect!))
  (let [proc (apply *process-launcher* binary args)]
    (swap! state assoc
           :process         proc
           :last-start-opts {:binary       binary
                              :socket-path  socket-path
                              :capabilities caps
                              :args         args
                              :retry        retry
                              :wait-ms      wait-ms})
    (Thread/sleep wait-ms)
    (try
      (connect! socket-path caps :retry retry)
      proc
      (catch Exception e
        (.destroyForcibly ^java.lang.Process proc)
        (swap! state assoc :process nil)
        (throw e)))))

(defn stop-process!
  "Kill the managed subprocess (if running) and disconnect."
  []
  (when-let [proc ^java.lang.Process (:process @state)]
    (.destroyForcibly proc)
    (swap! state assoc :process nil))
  (disconnect!)
  nil)

(defn restart-process!
  "Kill and relaunch using the opts from the last start-process! call.
  Throws if start-process! has never been called."
  []
  (let [{:keys [binary socket-path capabilities args retry wait-ms]}
        (:last-start-opts @state)]
    (when-not binary
      (throw (ex-info "restart-process!: no stored start opts; call start-process! first" {})))
    (stop-process!)
    (Thread/sleep 100)
    (start-process! binary socket-path capabilities
                    :args args :retry retry :wait-ms wait-ms)))

(defn connection-opts
  "Return the socket-path and capabilities for the current or most recent connection.
  Returns {:socket-path \"...\" :capabilities {...}}.
  Used by nous.supervisor/schedule-recovery! to reconnect without being told the path again."
  []
  (let [{:keys [socket-path capabilities last-start-opts]} @state]
    {:socket-path  (or socket-path (:socket-path last-start-opts))
     :capabilities (or (when (seq capabilities) capabilities)
                       (:capabilities last-start-opts) {})}))

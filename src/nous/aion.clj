; SPDX-FileCopyrightText: 2025-2026 nomos-studio contributors
;
; SPDX-License-Identifier: EPL-2.0
(ns nous.aion
  "Unix socket IPC client for the aion session substrate.

  Connects to aion's Unix domain socket and sends note-on/note-off events
  using the nomos-rt wire protocol.  A background read thread receives
  diagnostic frames that aion pushes back on every MIDI send; these are
  written to the ctrl-tree at [:diagnostic :aion :midi_sent] and can be
  asserted in CI tests without hardware.

  Wire format: [uint32 payload-len BE][uint8 type][uint8 reserved*3][EDN payload]
  Note-on/off payload: {:key <midi-note> :channel 0 :port 0 :velocity <0.0-1.0>}
  Diagnostic frame: {:bytes [status-byte note vel]}"
  (:require [clojure.edn      :as edn]
            [ctrl-tree.core   :as ct])
  (:import [java.net UnixDomainSocketAddress]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels SocketChannel]))

;; Physical key letter → MIDI note number (chromatic layout, middle C = 60)
(def key->note
  {"a" 60  "w" 61  "s" 62  "e" 63  "d" 64
   "f" 65  "t" 66  "g" 67  "y" 68  "h" 69
   "u" 70  "j" 71})

(def ^:private MSG-NOTE-ON   (byte 0x41))
(def ^:private MSG-NOTE-OFF  (byte 0x42))
(def ^:private MSG-MIDI-DIAG (int 0x54)) ; nomos::rt::ipc::msg_midi_diag
(def ^:private MSG-TICK      (int 0x50)) ; nomos::rt::ipc::msg_tick

;; ---------------------------------------------------------------------------
;; Diagnostic dispatch — rebind in tests to capture without touching ctrl-tree
;; ---------------------------------------------------------------------------

(def ^:dynamic *dispatch-diag*
  "Called with the parsed EDN data map from each MSG-MIDI-DIAG frame.
  Default writes to [:diagnostic :aion :midi_sent].
  Rebind in tests to capture without a running ctrl-tree."
  (fn [data] (ct/ctrl-write! [:diagnostic :aion :midi_sent] data)))

;; ---------------------------------------------------------------------------
;; Beat tracking
;;
;; Updated on every MSG-TICK frame received from aion.  When aion is down,
;; estimated-beat extrapolates using BPM + wall time so connect-at-next-bar!
;; can compute a sensible bar boundary even with no live connection.
;; ---------------------------------------------------------------------------

(defonce ^:private beat-state
  (atom {:beat 0.0 :bpm 120.0 :wall-ns (System/nanoTime)}))

(defn- update-beat! [beat]
  (let [now  (System/nanoTime)
        prev @beat-state
        dt-s (/ (- now (:wall-ns prev)) 1.0e9)]
    (when (> dt-s 0.01)
      (let [bpm (-> (/ (- beat (:beat prev)) dt-s) (* 60.0)
                    (max 20.0) (min 400.0))]
        (reset! beat-state {:beat beat :bpm bpm :wall-ns now})))))

(defn estimated-beat
  "Return the estimated current beat.  Uses wall-time extrapolation when aion is down."
  []
  (let [{:keys [beat bpm wall-ns]} @beat-state
        elapsed-s (/ (- (System/nanoTime) wall-ns) 1.0e9)]
    (+ beat (* bpm (/ elapsed-s 60.0)))))

(def ^:dynamic *bar-beats*
  "Bar length in beats for connect-at-next-bar! (default 4)."
  4)

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private state (atom {:channel nil :running-a nil :read-thread nil}))

;; ---------------------------------------------------------------------------
;; IPC write path
;; ---------------------------------------------------------------------------

(defn- send-frame! [msg-type ^String edn]
  (when-let [^SocketChannel ch (:channel @state)]
    (try
      (let [payload (.getBytes edn "UTF-8")
            len     (alength payload)
            buf     (ByteBuffer/allocate (+ 8 len))]
        (.order buf ByteOrder/BIG_ENDIAN)
        (.putInt buf len)
        (.put buf msg-type)
        (.put buf (byte 0)) (.put buf (byte 0)) (.put buf (byte 0))
        (.put buf payload)
        (.flip buf)
        (locking ch (.write ch buf)))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[nous.aion] send error: " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; IPC read path (diagnostic frames from aion)
;; ---------------------------------------------------------------------------

(defn- read-exact! [^java.nio.channels.ReadableByteChannel ch ^ByteBuffer buf]
  (loop []
    (when (.hasRemaining buf)
      (let [n (.read ch buf)]
        (when (neg? n)
          (throw (java.io.EOFException. "aion channel closed")))
        (recur)))))

(defn- read-frame
  "Read one IPC frame from ch.  Returns {:type int :payload String}.
  Blocks until a complete frame is available.  Throws EOFException on close."
  [^java.nio.channels.ReadableByteChannel ch]
  (let [hdr (doto (ByteBuffer/allocate 8)
               (.order ByteOrder/BIG_ENDIAN))]
    (read-exact! ch hdr)
    (.flip hdr)
    (let [payload-len (.getInt hdr)
          msg-type    (bit-and 0xFF (int (.get hdr)))]
      (if (pos? payload-len)
        (let [buf (ByteBuffer/allocate payload-len)]
          (read-exact! ch buf)
          {:type msg-type
           :payload (String. (.array buf) 0 payload-len "UTF-8")})
        {:type msg-type :payload ""}))))

(defn- handle-diag-frame! [^String payload]
  (try
    (*dispatch-diag* (edn/read-string payload))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "[nous.aion] diag parse error: " (.getMessage e)))))))

(defn- run-read-loop [^SocketChannel ch running-a]
  (try
    (loop []
      (when @running-a
        (let [{:keys [type payload]} (read-frame ch)]
          (cond
            (= type MSG-MIDI-DIAG) (handle-diag-frame! payload)
            (= type MSG-TICK)      (try
                                     (when-let [b (:beat (edn/read-string payload))]
                                       (update-beat! b))
                                     (catch Exception _))))
        (recur)))
    (catch java.io.EOFException _
      ;; Unexpected disconnect: clear channel so connected? returns false,
      ;; but only when we weren't stopped intentionally.
      (when @running-a
        (swap! state assoc :channel nil :running-a nil :read-thread nil)))
    (catch Exception e
      (when @running-a
        (binding [*out* *err*]
          (println (str "[nous.aion] read-loop error: " (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Note I/O (write-only, existing API unchanged)
;; ---------------------------------------------------------------------------

(defn note-on!
  "Send a note-on for physical key character (e.g. \"a\"). velocity is 0.0–1.0."
  ([key] (note-on! key 0.8))
  ([key velocity]
   (when-let [note (key->note key)]
     (send-frame! MSG-NOTE-ON
                  (str "{:key " note
                       " :channel 0 :port 0"
                       " :velocity " (double velocity) "}")))))

(defn note-off!
  "Send a note-off for physical key character."
  [key]
  (when-let [note (key->note key)]
    (send-frame! MSG-NOTE-OFF
                 (str "{:key " note
                      " :channel 0 :port 0"
                      " :velocity 0.0}"))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Connect to aion's Unix domain socket and start the diagnostic read loop."
  ([] (start! "/tmp/aion.sock"))
  ([socket-path]
   (when (:channel @state)
     (throw (ex-info "nous.aion already connected — call stop! first" {})))
   (let [addr      (UnixDomainSocketAddress/of ^String socket-path)
         ch        (SocketChannel/open addr)
         running-a (atom true)
         read-thr  (doto (Thread. ^Runnable #(run-read-loop ch running-a))
                     (.setDaemon true)
                     (.setName "nous-aion-read")
                     (.start))]
     (swap! state assoc :channel ch :running-a running-a :read-thread read-thr)
     :connected)))

(defn stop! []
  (let [{:keys [channel running-a]} @state]
    (when running-a (reset! running-a false))
    (when channel
      (try (.close ^SocketChannel channel) (catch Exception _)))
    (swap! state assoc :channel nil :running-a nil :read-thread nil)
    :disconnected))

(defn connected?
  "Returns true only when the channel is open AND the read-thread is alive.
  A non-nil channel with a dead thread means the connection broke silently."
  []
  (boolean
   (when-let [{:keys [channel read-thread]} @state]
     (and channel
          read-thread
          (.isAlive ^Thread read-thread)))))

(defn connect-at-next-bar!
  "Reconnect to aion at the next bar boundary.  bar-beats defaults to *bar-beats*.

  Spawns a daemon thread that polls estimated-beat until the target boundary,
  then calls stop! (clean slate) followed by start!.  Returns immediately."
  ([] (connect-at-next-bar! *bar-beats*))
  ([bar-beats]
   (let [now    (estimated-beat)
         ;; Require at least 1 beat of look-ahead so a call right on a boundary
         ;; advances to the next bar rather than reconnecting immediately.
         target (* (Math/ceil (/ (+ now 1.0) (double bar-beats)))
                   (double bar-beats))]
     (doto (Thread.
            (fn []
              (loop []
                (when (< (estimated-beat) target)
                  (Thread/sleep 20)
                  (recur)))
              (stop!)
              (try (start!)
                   (catch Exception e
                     (binding [*out* *err*]
                       (println (str "[nous.aion] reconnect failed: " (.getMessage e)))))))
            "nous-aion-reconnect")
       (.setDaemon true)
       (.start)))))

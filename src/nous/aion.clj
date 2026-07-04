; SPDX-License-Identifier: EPL-2.0
(ns nous.aion
  "Unix socket IPC client for the aion session substrate.

  Connects to aion's Unix domain socket and sends note-on/note-off events
  using the nomos-rt wire protocol.

  Wire format: [uint32 payload-len BE][uint8 type][uint8 reserved*3][EDN payload]
  Note-on/off payload: {:key <midi-note> :channel 0 :port 0 :velocity <0.0-1.0>}"
  (:import [java.net UnixDomainSocketAddress]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels SocketChannel]))

;; Physical key letter → MIDI note number (chromatic layout, middle C = 60)
(def key->note
  {"a" 60  "w" 61  "s" 62  "e" 63  "d" 64
   "f" 65  "t" 66  "g" 67  "y" 68  "h" 69
   "u" 70  "j" 71})

(def ^:private MSG-NOTE-ON  (byte 0x41))
(def ^:private MSG-NOTE-OFF (byte 0x42))

(defonce ^:private state (atom {:channel nil}))

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

(defn start!
  "Connect to aion's Unix domain socket."
  ([] (start! "/tmp/aion.sock"))
  ([socket-path]
   (when (:channel @state)
     (throw (ex-info "nous.aion already connected — call stop! first" {})))
   (let [addr (UnixDomainSocketAddress/of ^String socket-path)
         ch   (SocketChannel/open addr)]
     (swap! state assoc :channel ch)
     :connected)))

(defn stop! []
  (when-let [ch (:channel @state)]
    (try (.close ^SocketChannel ch) (catch Exception _))
    (swap! state assoc :channel nil)
    :disconnected))

(defn connected? [] (boolean (:channel @state)))

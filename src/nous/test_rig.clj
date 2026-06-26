; SPDX-License-Identifier: EPL-2.0
(ns nous.test-rig
  "MIDI loopback capture rig for end-to-end pipeline verification.

  Attaches a JVM javax.sound.midi listener to a named virtual MIDI port,
  accumulating events with wall-clock timestamps.  Provides predicates and
  wait helpers for making timing-aware assertions about what the pipeline
  actually sent to hardware.

  Catches problems invisible to IPC-level tests:
    - Note-off arriving before note-on (ordering bug)
    - Note duration near zero (scheduler fires both events in same audio block)
    - Wrong note number or channel (routing bug)
    - No events at all (pipeline not connected to MIDI output)

  Typical use in integration tests:
    (with-midi-capture [cap \"my-virtual-port\"]
      (kairos/send-note-on! 60 0.8)
      (Thread/sleep 200)
      (kairos/send-note-off! 60)
      (let [evs (wait-for-events! cap 2)]
        (is (note-on?  (first evs) :note 60))
        (is (note-off? (second evs) :note 60))
        (is (> (note-duration-ms (first evs) (second evs)) 150))))"
  (:import [javax.sound.midi MidiSystem MidiDevice MidiDevice$Info
            MidiMessage ShortMessage Receiver Transmitter]))

;; ---------------------------------------------------------------------------
;; Event predicates
;; ---------------------------------------------------------------------------

(defn note-on?
  "True when `event` is a MIDI NOTE_ON with velocity > 0.
  Pass :note N and/or :channel C to additionally require those fields."
  [event & {:keys [note channel]}]
  (and (= 0x90 (:command event))
       (pos? (:data2 event))
       (or (nil? note)    (= note    (:data1 event)))
       (or (nil? channel) (= channel (:channel event)))))

(defn note-off?
  "True when `event` is a MIDI NOTE_OFF, or a NOTE_ON with velocity 0.
  Pass :note N and/or :channel C to additionally require those fields."
  [event & {:keys [note channel]}]
  (and (or (= 0x80 (:command event))
           (and (= 0x90 (:command event)) (zero? (:data2 event))))
       (or (nil? note)    (= note    (:data1 event)))
       (or (nil? channel) (= channel (:channel event)))))

(defn note-duration-ms
  "Wall-clock milliseconds between a note-on and note-off event."
  [note-on note-off]
  (- (:received-ms note-off) (:received-ms note-on)))

(defn note-pairs
  "Pair up NOTE_ON / NOTE_OFF events in `events` by note number.
  Returns a seq of {:note-on E :note-off E :duration-ms N} maps.
  Unpaired events are dropped."
  [events]
  (let [ons  (filter note-on?  events)
        offs (filter note-off? events)]
    (for [on ons
          :let [off (first (filter #(and (note-off? %)
                                         (= (:data1 on) (:data1 %))
                                         (>= (:received-ms %) (:received-ms on)))
                                   offs))]
          :when off]
      {:note-on    on
       :note-off   off
       :duration-ms (note-duration-ms on off)})))

;; ---------------------------------------------------------------------------
;; Port discovery
;; ---------------------------------------------------------------------------

(defn- find-capture-device
  "Return the first javax.sound.midi MidiDevice whose name contains `port-name`
  and which can transmit MIDI (i.e. we can listen to it), or nil."
  [port-name]
  (let [infos (MidiSystem/getMidiDeviceInfo)]
    (->> infos
         (filter #(.contains (.getName ^MidiDevice$Info %) port-name))
         (map    (fn [^MidiDevice$Info info]
                   (try (MidiSystem/getMidiDevice info)
                        (catch Exception _ nil))))
         (filter (fn [^MidiDevice dev]
                   (and dev (not= 0 (.getMaxTransmitters dev)))))
         first)))

;; ---------------------------------------------------------------------------
;; Capture lifecycle
;; ---------------------------------------------------------------------------

(defn open-capture!
  "Find the named MIDI port and attach a listener.

  Retries up to `max-retries` times with `wait-ms` between attempts —
  a freshly started kairos process may take a moment to register its
  virtual port with CoreMIDI.

  Returns a map:
    {:events <atom>  — accumulates {:command :channel :data1 :data2 :received-ms}
     :close  <fn>}  — call to detach the listener and close the device

  Throws ex-info if the port is not found within the retry window."
  [port-name & {:keys [max-retries wait-ms]
                :or   {max-retries 30 wait-ms 100}}]
  (let [device (loop [tries 0]
                 (when (> tries max-retries)
                   (throw (ex-info
                           (str "[test-rig] MIDI port not found after "
                                tries " attempts: " port-name)
                           {:port port-name :tries tries})))
                 (or (find-capture-device port-name)
                     (do (Thread/sleep wait-ms) (recur (inc tries)))))]
    (.open ^MidiDevice device)
    (let [events (atom [])
          rx     (reify Receiver
                   (send [_ msg _ts]
                     (when (instance? ShortMessage msg)
                       (let [^ShortMessage sm msg]
                         (swap! events conj
                                {:status      (.getStatus sm)
                                 :command     (.getCommand sm)
                                 :channel     (.getChannel sm)
                                 :data1       (.getData1 sm)
                                 :data2       (.getData2 sm)
                                 :received-ms (/ (double (System/nanoTime)) 1e6)}))))
                   (close [_]))
          tx     (.getTransmitter ^MidiDevice device)]
      (.setReceiver ^Transmitter tx rx)
      {:events events
       :close  (fn []
                 (try (.close ^Transmitter tx) (catch Exception _))
                 (try (.close ^Receiver   rx) (catch Exception _))
                 (try (.close ^MidiDevice device) (catch Exception _)))})))

(defn close-capture!
  "Close a capture returned by open-capture!."
  [{:keys [close]}]
  (close))

(defmacro with-midi-capture
  "Execute `body` with `binding` bound to a MIDI capture on `port-name`.
  Closes the capture on exit.

  Example:
    (with-midi-capture [cap \"kairos-test\"]
      (kairos/send-note-on! 60 0.8)
      (wait-for-events! cap 1))"
  [[binding port-name] & body]
  `(let [cap# (open-capture! ~port-name)]
     (try
       (let [~binding cap#]
         ~@body)
       (finally
         (close-capture! cap#)))))

;; ---------------------------------------------------------------------------
;; Wait / polling
;; ---------------------------------------------------------------------------

(defn wait-for-events!
  "Block until at least `n` events have been captured or `timeout-ms` elapses.
  Returns the full event seq accumulated so far."
  [capture n & {:keys [timeout-ms poll-ms]
                :or   {timeout-ms 2000 poll-ms 10}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [evs @(:events capture)]
        (if (or (>= (count evs) n)
                (> (System/currentTimeMillis) deadline))
          evs
          (do (Thread/sleep poll-ms) (recur)))))))

(defn events-so-far
  "Return all events captured so far without blocking."
  [capture]
  @(:events capture))

(defn clear-events!
  "Discard all events accumulated so far."
  [capture]
  (reset! (:events capture) []))

; SPDX-License-Identifier: EPL-2.0
(ns nous.midi-pipeline-test
  "End-to-end integration tests for the MIDI output pipeline.

  These tests start kairos with self-loopback (--virtual-midi-port N
  --midi-in-port N).  The kairos IPC echo (msg_midi_event 0x51) fires
  whenever MIDI arrives on the input port, so the Clojure side can observe
  bytes that actually reached the driver.  No javax.sound.midi is used.

  Tests skip gracefully when the kairos binary is absent so they run safely
  in CI without a built binary."
  (:require [clojure.test :refer [deftest is testing]]
            [nous.kairos  :as kairos]
            [nous.rt      :as rt])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Infrastructure helpers
;; ---------------------------------------------------------------------------

(def ^:private kairos-binary
  (-> (File. (System/getProperty "user.dir") "../kairos/build/kairos")
      .getCanonicalPath))

(defn- kairos-binary-exists? []
  (.exists (File. kairos-binary)))

(defn- temp-socket-path []
  (let [f (File/createTempFile "kairos-midi-test-" ".sock")]
    (.delete f)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

;; ---------------------------------------------------------------------------
;; Predicates for IPC echo events
;; Each event: {:port N :channel N :data [status b1 b2]}
;; ---------------------------------------------------------------------------

(defn- note-on?  [msg] (= 0x90 (bit-and 0xF0 (first (:data msg)))))
(defn- note-off? [msg] (= 0x80 (bit-and 0xF0 (first (:data msg)))))

(defn- await-n-events
  "Wait until kairos/midi-in-messages contains >= n events matching pred.
  Returns the matching events as a vector, or nil on timeout."
  [pred n & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [hits (filterv pred @kairos/midi-in-messages)]
        (cond
          (>= (count hits) n) hits
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 10) (recur)))))))

;; ---------------------------------------------------------------------------
;; Test rig — self-loopback via IPC echo
;; ---------------------------------------------------------------------------

(defmacro ^:private with-virtual-midi-rig
  "Start kairos with a virtual MIDI output port and a loopback input using the
  same port name.  MIDI sent through the pipeline echoes back as IPC
  msg_midi_event (0x51) which populates kairos/midi-in-messages.

  Stops kairos on exit regardless of test outcome (outer try/finally ensures
  stop-kairos! runs even if the body throws)."
  [[sock-binding port-name] & body]
  `(let [sock# (temp-socket-path)]
     (kairos/start-kairos!
       :binary      kairos-binary
       :socket-path sock#
       :args        ["--no-audio"
                     "--socket"            sock#
                     "--virtual-midi-port" ~port-name
                     "--midi-in-port"      ~port-name]
       :wait-ms     400
       :retry       20)
     (try
       ;; Allow CoreMIDI loopback connection to fully stabilise before
       ;; sending notes and before clearing any startup noise.
       (Thread/sleep 400)
       (reset! kairos/midi-in-messages [])
       (let [~sock-binding sock#]
         ~@body)
       (finally
         (kairos/stop-kairos!)))))

(defmacro ^:private skip-without-binary [& body]
  `(if (kairos-binary-exists?)
     (do ~@body)
     (println (str "  [skip] kairos binary not found: " kairos-binary))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest note-bytes-are-correct-test
  (testing "send-note-on! produces correct note number and channel on the wire"
    (skip-without-binary
      (with-virtual-midi-rig [_sock "kairos-note-bytes-test"]
        (kairos/send-note-on! 60 0.8)
        (let [msg (kairos/await-midi-message note-on? :timeout-ms 3000)]
          (is (some? msg) "note-on event arrived via IPC echo")
          (when msg
            (let [[status b1 b2] (:data msg)]
              (is (= 60 b1)                          "note number is 60 (middle C)")
              (is (= 0  (bit-and 0x0F status))       "channel is 0 (default)")
              (is (pos? b2)                           "velocity > 0"))))))))

(deftest note-on-before-note-off-test
  (testing "note-on arrives before note-off in the IPC echo stream"
    (skip-without-binary
      (with-virtual-midi-rig [_sock "kairos-ordering-test"]
        (kairos/send-note-on!  60 0.8)
        (Thread/sleep 150)
        (kairos/send-note-off! 60)
        (let [on  (kairos/await-midi-message note-on?  :timeout-ms 3000)
              off (kairos/await-midi-message note-off? :timeout-ms 3000)]
          (is (some? on)  "note-on arrived")
          (is (some? off) "note-off arrived")
          (when (and on off)
            (let [buf @kairos/midi-in-messages]
              (is (< (.indexOf buf on) (.indexOf buf off))
                  "note-on precedes note-off in message buffer"))))))))

(deftest note-duration-regression-test
  (testing "note-on and note-off arrive as distinct sequential events"
    ;; Catches the regression where *virtual-time*=0.0 caused the scheduler
    ;; to fire note-on + note-off in the same audio block: both events would
    ;; collapse to a single block and produce a ~0ms note with no audible sound.
    (skip-without-binary
      (with-virtual-midi-rig [_sock "kairos-duration-test"]
        (kairos/send-note-on!  60 0.8)
        (Thread/sleep 125)
        (kairos/send-note-off! 60)
        (let [evs (await-n-events #(or (note-on? %) (note-off? %)) 2 :timeout-ms 3000)
              on  (when evs (first (filter note-on?  evs)))
              off (when evs (first (filter note-off? evs)))]
          (is (some? on)  "note-on arrived")
          (is (some? off) "note-off arrived")
          (when (and on off)
            (let [buf     @kairos/midi-in-messages
                  on-idx  (.indexOf buf on)
                  off-idx (.indexOf buf off)]
              (is (< on-idx off-idx)
                  "note-on precedes note-off (regression: both collapsed to same block)"))))))))

(deftest multiple-notes-pair-correctly-test
  (testing "two sequential notes each produce note-on and note-off echoes"
    (skip-without-binary
      (with-virtual-midi-rig [_sock "kairos-pairs-test"]
        (kairos/send-note-on!  60 0.8)
        (Thread/sleep 80)
        (kairos/send-note-off! 60)
        (Thread/sleep 20)
        (kairos/send-note-on!  64 0.6)
        (Thread/sleep 80)
        (kairos/send-note-off! 64)
        (let [evs (await-n-events #(or (note-on? %) (note-off? %)) 4 :timeout-ms 3000)]
          (is (some? evs) "four MIDI events arrived via IPC echo")
          (when evs
            (let [ons  (filter note-on?  evs)
                  offs (filter note-off? evs)]
              (is (= 2 (count ons))  "two note-on events")
              (is (= 2 (count offs)) "two note-off events")
              (is (= #{60 64}
                     (set (map #(second (:data %)) ons)))
                  "note-on events cover both note numbers"))))))))

(deftest no-audio-process-stays-alive-test
  (testing "kairos process stays alive after MIDI activity (smoke test)"
    (skip-without-binary
      (with-virtual-midi-rig [_sock "kairos-alive-test"]
        (kairos/send-note-on!  60 0.75)
        (Thread/sleep 50)
        (kairos/send-note-off! 60)
        (let [msg (kairos/await-midi-message note-on? :timeout-ms 3000)]
          (is (some? msg) "kairos echoed the note (pipeline is live)")
          (let [^java.lang.Process proc (:process @@#'rt/state)]
            (is (some? proc)    "rt/state holds a Process")
            (is (.isAlive proc) "kairos is still alive after MIDI events")))))))

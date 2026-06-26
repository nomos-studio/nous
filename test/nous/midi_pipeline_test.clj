; SPDX-License-Identifier: EPL-2.0
(ns nous.midi-pipeline-test
  "End-to-end integration tests for the MIDI output pipeline.

  These tests launch a real kairos process with --virtual-midi-port so the
  JVM can listen on the same virtual CoreMIDI port via javax.sound.midi.
  They catch bugs that IPC-level tests cannot: wrong bytes reaching the
  driver, ordering inversions, and the specific timing defect where the
  scheduler fires note-on + note-off in the same audio block (< 1ms gap).

  Tests skip gracefully when the kairos binary is absent so they can live
  in the normal suite without breaking CI."
  (:require [clojure.test  :refer [deftest is testing]]
            [nous.kairos    :as kairos]
            [nous.test-rig  :as rig])
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

(defmacro ^:private with-virtual-midi-rig
  "Start kairos with a virtual MIDI port and a capture listener attached to
  it.  `sock-binding` is bound to the temp socket path, `cap-binding` to the
  capture map from test-rig/open-capture!.

  Stops kairos and closes the capture on exit regardless of test outcome."
  [[sock-binding cap-binding port-name] & body]
  `(let [sock# (temp-socket-path)]
     (kairos/start-kairos!
       :binary      kairos-binary
       :socket-path sock#
       :args        ["--no-audio"
                     "--socket"            sock#
                     "--virtual-midi-port" ~port-name]
       :wait-ms     400
       :retry       20)
     (let [cap# (rig/open-capture! ~port-name :max-retries 50 :wait-ms 100)]
       (try
         (let [~sock-binding sock#
               ~cap-binding  cap#]
           ~@body)
         (finally
           (rig/close-capture! cap#)
           (kairos/stop-kairos!))))))

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
      (with-virtual-midi-rig [_sock cap "kairos-note-bytes-test"]
        (kairos/send-note-on! 60 0.8)
        (let [evs (rig/wait-for-events! cap 1 :timeout-ms 3000)
              on  (first (filter rig/note-on? evs))]
          (is (some? on)         "note-on event arrived")
          (is (= 60 (:data1 on)) "note number is 60 (middle C)")
          (is (= 0  (:channel on)) "channel is MIDI channel 0 (default)")
          (is (pos? (:data2 on))  "velocity > 0"))))))

(deftest note-on-before-note-off-test
  (testing "note-on arrives before note-off"
    (skip-without-binary
      (with-virtual-midi-rig [_sock cap "kairos-ordering-test"]
        (kairos/send-note-on!  60 0.8)
        (Thread/sleep 150)
        (kairos/send-note-off! 60)
        (let [evs (rig/wait-for-events! cap 2 :timeout-ms 3000)
              on  (first (filter rig/note-on?  evs))
              off (first (filter rig/note-off? evs))]
          (is (some? on)  "note-on arrived")
          (is (some? off) "note-off arrived")
          (is (< (:received-ms on) (:received-ms off))
              "note-on must precede note-off"))))))

(deftest note-duration-regression-test
  (testing "REPL note sustains >= 50ms (regression: scheduler fired both events in same block)"
    ;; This test would have caught the original bug: *virtual-time*=0.0 caused
    ;; the scheduler to fire note-on and note-off in the same audio block, so
    ;; the synth heard a ~0ms note and produced no sound.
    (skip-without-binary
      (with-virtual-midi-rig [_sock cap "kairos-duration-test"]
        (kairos/send-note-on!  60 0.8)
        (Thread/sleep 125)
        (kairos/send-note-off! 60)
        (let [evs (rig/wait-for-events! cap 2 :timeout-ms 3000)
              on  (first (filter rig/note-on?  evs))
              off (first (filter rig/note-off? evs))]
          (is (some? on)  "note-on arrived")
          (is (some? off) "note-off arrived")
          (when (and on off)
            (is (>= (rig/note-duration-ms on off) 50)
                (str "note must sustain >= 50ms; got "
                     (rig/note-duration-ms on off) "ms"))))))))

(deftest multiple-notes-pair-correctly-test
  (testing "note-pairs correctly matches on/off events for two sequential notes"
    (skip-without-binary
      (with-virtual-midi-rig [_sock cap "kairos-pairs-test"]
        (kairos/send-note-on!  60 0.8)
        (Thread/sleep 80)
        (kairos/send-note-off! 60)
        (Thread/sleep 20)
        (kairos/send-note-on!  64 0.6)
        (Thread/sleep 80)
        (kairos/send-note-off! 64)
        (let [evs   (rig/wait-for-events! cap 4 :timeout-ms 3000)
              pairs (rig/note-pairs evs)]
          (is (= 2 (count pairs)) "two note pairs found")
          (is (every? #(>= (:duration-ms %) 50) pairs)
              "both notes sustain >= 50ms")
          (is (= #{60 64}
                 (set (map #(get-in % [:note-on :data1]) pairs)))
              "pairs cover both note numbers"))))))

(deftest no-audio-process-stays-alive-test
  (testing "kairos process stays alive after MIDI activity (smoke test)"
    (skip-without-binary
      (with-virtual-midi-rig [_sock cap "kairos-alive-test"]
        (kairos/send-note-on!  60 0.75)
        (Thread/sleep 50)
        (kairos/send-note-off! 60)
        (Thread/sleep 50)
        (let [^java.lang.Process proc (:process @#'kairos/kairos-state)]
          (is (some? proc)    "kairos-state holds a Process")
          (is (.isAlive proc) "kairos is still alive after MIDI events"))))))

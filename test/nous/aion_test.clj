; SPDX-FileCopyrightText: 2025-2026 nomos-studio contributors
;
; SPDX-License-Identifier: EPL-2.0
(ns nous.aion-test
  (:require [clojure.test :refer [deftest is testing]]
            [nous.aion :as aion]
            [nous.rt   :as rt])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.channels Channels Pipe]))

(deftest key->note-mapping
  (testing "chromatic layout starting from middle C"
    (is (= 60 (aion/key->note "a")))  ; C4
    (is (= 61 (aion/key->note "w")))  ; C#4
    (is (= 62 (aion/key->note "s")))  ; D4
    (is (= 63 (aion/key->note "e")))  ; D#4
    (is (= 64 (aion/key->note "d")))  ; E4
    (is (= 65 (aion/key->note "f")))  ; F4
    (is (= 66 (aion/key->note "t")))  ; F#4
    (is (= 67 (aion/key->note "g")))  ; G4
    (is (= 68 (aion/key->note "y")))  ; G#4
    (is (= 69 (aion/key->note "h")))  ; A4
    (is (= 70 (aion/key->note "u")))  ; A#4
    (is (= 71 (aion/key->note "j")))) ; B4

  (testing "unknown key returns nil"
    (is (nil? (aion/key->note "z")))
    (is (nil? (aion/key->note "")))))

(deftest note-on-off-noop-when-disconnected
  (testing "note-on! and note-off! are silent when not connected"
    ;; No exception should be thrown; returns nil when key→note maps
    ;; but channel is nil (nothing to send to).
    (is (nil? (aion/note-on! "a")))
    (is (nil? (aion/note-off! "a"))))

  (testing "unknown key is a no-op regardless of connection"
    (is (nil? (aion/note-on! "z")))
    (is (nil? (aion/note-off! "z")))))

(deftest connected?-reflects-state
  (testing "connected? is false when no socket open"
    (is (false? (aion/connected?)))))

;; ---------------------------------------------------------------------------
;; Diagnostic tap — read-frame and dispatch
;; ---------------------------------------------------------------------------

(defn- write-diag-frame
  "Write a well-formed MSG-MIDI-DIAG IPC frame to a Pipe.SinkChannel."
  [^java.nio.channels.WritableByteChannel sink payload-str]
  (let [payload-bytes (.getBytes payload-str "UTF-8")
        payload-len   (alength payload-bytes)
        hdr (doto (ByteBuffer/allocate 8)
              (.order ByteOrder/BIG_ENDIAN)
              (.putInt payload-len)
              (.put (byte 0x54)) ; MSG-MIDI-DIAG
              (.put (byte 0))
              (.put (byte 0))
              (.put (byte 0))
              (.flip))]
    (.write sink hdr)
    (.write sink (ByteBuffer/wrap payload-bytes))))

(deftest read-frame-parses-diag-header
  (testing "read-frame decodes msg type and payload from a pipe"
    (let [pipe   (Pipe/open)
          sink   (.sink pipe)
          source (.source pipe)
          payload "{:bytes [144 60 101]}"]
      (write-diag-frame sink payload)
      (let [frame (#'rt/read-frame (Channels/newInputStream source))]
        (is (= 0x54 (:type frame)))
        (is (= payload (String. ^bytes (:payload frame) "UTF-8")))))))

(deftest dispatch-diag-calls-dynamic-var
  (testing "*dispatch-diag* is called with parsed EDN on MSG-MIDI-DIAG"
    (let [captured (atom nil)]
      (binding [rt/*dispatch-diag* #(reset! captured %)]
        (#'rt/handle-diag-frame! "{:bytes [144 60 101]}"))
      (is (= {:bytes [144 60 101]} @captured))))

  (testing "note-on C4 vel=0.8 status byte is 0x90, note=60, vel=101"
    ;; 0.8*127 truncated = 101; routing_matrix ch=(0+1)→ note_on(1,60,101)
    ;; midi_io::note_on ch=(1-1)&0xF=0 → {0x90|0, 60, 101}
    (let [captured (atom nil)]
      (binding [rt/*dispatch-diag* #(reset! captured %)]
        (#'rt/handle-diag-frame! "{:bytes [144 60 101]}"))
      (is (= 0x90 (nth (:bytes @captured) 0)))
      (is (= 60   (nth (:bytes @captured) 1)))
      (is (= 101  (nth (:bytes @captured) 2)))))

  (testing "malformed payload is caught without throwing"
    ;; "[1 2 3" is an unclosed vector — edn/read-string throws, dispatch is not called
    (let [captured (atom :sentinel)]
      (binding [rt/*dispatch-diag* #(reset! captured %)]
        (#'rt/handle-diag-frame! "[1 2 3"))
      (is (= :sentinel @captured)))))

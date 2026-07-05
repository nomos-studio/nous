; SPDX-License-Identifier: EPL-2.0
(ns nous.kairos-voice-test
  (:require [clojure.test        :refer [deftest is testing use-fixtures]]
            [nous.kairos-voice   :as kairos-voice]))

(deftest key->note-mapping
  (testing "chromatic layout starting from middle C"
    (is (= 60 (kairos-voice/key->note "a")))  ; C4
    (is (= 61 (kairos-voice/key->note "w")))  ; C#4
    (is (= 62 (kairos-voice/key->note "s")))  ; D4
    (is (= 63 (kairos-voice/key->note "e")))  ; D#4
    (is (= 64 (kairos-voice/key->note "d")))  ; E4
    (is (= 65 (kairos-voice/key->note "f")))  ; F4
    (is (= 66 (kairos-voice/key->note "t")))  ; F#4
    (is (= 67 (kairos-voice/key->note "g")))  ; G4
    (is (= 68 (kairos-voice/key->note "y")))  ; G#4
    (is (= 69 (kairos-voice/key->note "h")))  ; A4
    (is (= 70 (kairos-voice/key->note "u")))  ; A#4
    (is (= 71 (kairos-voice/key->note "j")))) ; B4

  (testing "unknown key returns nil"
    (is (nil? (kairos-voice/key->note "z")))
    (is (nil? (kairos-voice/key->note "")))))

(deftest note->hz-tuning
  (testing "A4 = 440 Hz"
    (is (= 440.0 (kairos-voice/note->hz 69))))
  (testing "C4 = ~261.63 Hz"
    (is (< (Math/abs (- (kairos-voice/note->hz 60) 261.626)) 0.01)))
  (testing "each semitone is a 2^(1/12) ratio"
    (let [ratio (/ (kairos-voice/note->hz 61) (kairos-voice/note->hz 60))]
      (is (< (Math/abs (- ratio (Math/pow 2.0 (/ 1 12)))) 1e-10)))))

(deftest note-on-off-noop-when-disconnected
  (testing "note-on! is silent when kairos not connected"
    (is (nil? (kairos-voice/note-on! "a"))))
  (testing "note-off! is silent when kairos not connected"
    (is (nil? (kairos-voice/note-off! "a"))))
  (testing "unknown key is a no-op"
    (is (nil? (kairos-voice/note-on! "z")))
    (is (nil? (kairos-voice/note-off! "z")))))

(deftest start-stop-lifecycle
  (testing "start! sets started? to true"
    (kairos-voice/start!)
    (is (true? (kairos-voice/started?))))
  (testing "stop! clears started? and is idempotent"
    (kairos-voice/stop!)
    (is (false? (kairos-voice/started?)))
    (kairos-voice/stop!)
    (is (false? (kairos-voice/started?)))))

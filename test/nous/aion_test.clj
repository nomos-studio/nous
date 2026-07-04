; SPDX-License-Identifier: EPL-2.0
(ns nous.aion-test
  (:require [clojure.test :refer [deftest is testing]]
            [nous.aion :as aion]))

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

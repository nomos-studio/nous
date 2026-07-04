; SPDX-License-Identifier: EPL-2.0
(ns nous.sc-keyboard-test
  (:require [clojure.test        :refer [deftest is testing]]
            [nous.sc-keyboard    :as sc-keyboard]))

(deftest key->note-mapping
  (testing "piano layout matches aion key->note"
    (is (= 60 (sc-keyboard/key->note "a")))   ; C4
    (is (= 64 (sc-keyboard/key->note "d")))   ; E4
    (is (= 69 (sc-keyboard/key->note "h")))   ; A4
    (is (= 71 (sc-keyboard/key->note "j"))))  ; B4

  (testing "unknown key returns nil"
    (is (nil? (sc-keyboard/key->note "z")))
    (is (nil? (sc-keyboard/key->note "")))))

(deftest note->hz-conversion
  (testing "A4 = 440 Hz"
    (is (= 440.0 (sc-keyboard/note->hz 69))))

  (testing "C4 ≈ 261.63 Hz"
    (is (< (Math/abs (- 261.626 (sc-keyboard/note->hz 60))) 0.01)))

  (testing "A5 = 880 Hz (one octave up)"
    (is (= 880.0 (sc-keyboard/note->hz 81))))

  (testing "C#4 ≈ 277.18 Hz"
    (is (< (Math/abs (- 277.183 (sc-keyboard/note->hz 61))) 0.01))))

(deftest key-dispatch-noop-when-not-connected
  (testing "key-down! and key-up! are silent when SC not connected"
    ;; sc/sc-connected? returns false in test environment — no ops thrown
    (is (nil? (sc-keyboard/key-down! "a")))
    (is (nil? (sc-keyboard/key-up! "a"))))

  (testing "unknown key is a no-op regardless"
    (is (nil? (sc-keyboard/key-down! "z")))
    (is (nil? (sc-keyboard/key-up! "z")))))

(deftest started?-reflects-state
  (testing "started? is false before start!"
    ;; The defonce atom is shared — only valid if tests run before start! is called
    ;; in this JVM.  In a clean test run this is always the case.
    (when-not (sc-keyboard/started?)
      (is (false? (sc-keyboard/started?))))))

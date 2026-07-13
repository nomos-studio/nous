; SPDX-License-Identifier: EPL-2.0
(ns nous.notation-test
  "Tests for nous.notation — focused on the store-crossing save-session! path
  (Gate 4 finding #1). export-corpus!/export-session! themselves depend on the
  music21 sidecar and are not exercised here."
  (:require [clojure.test    :refer [deftest is testing use-fixtures]]
            [clojure.string  :as str]
            [ctrl-tree.core  :as ct]
            [nous.core       :as core]
            [nous.notation   :as notation])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-system [f]
  (core/start! :no-log true)
  (ct/ctrl-write! [:notation :session :lilypond] nil)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

(defn- temp-dir []
  (str (Files/createTempDirectory "nous-notation-test" (make-array FileAttribute 0))))

(deftest save-session-reads-lilypond-from-tree-state-test
  (testing "save-session! reads the LilyPond source export-session! wrote (same store)"
    (let [dir "\\version \"2.24\"\n{ c'4 d'4 e'4 f'4 }"
          out (temp-dir)]
      (ct/ctrl-write! [:session :dir] out)
      ;; Simulate a successful export-session!: it writes via ct/ctrl-write!.
      (ct/ctrl-write! [:notation :session :lilypond] dir)
      (let [ly-path (notation/save-session!)]
        (is (some? ly-path) "save-session! returned a path (did not silently no-op)")
        (is (= (str out "/notation.ly") ly-path))
        (is (= dir (slurp ly-path)) "the .ly file contains the LilyPond source")))))

(deftest save-session-nil-when-no-content-test
  (testing "save-session! returns nil when no LilyPond content is present"
    (ct/ctrl-write! [:notation :session :lilypond] nil)
    (is (nil? (notation/save-session!)))))

(deftest save-session-distinguishes-bug-from-empty-test
  (testing "content written to the ctrl-tree store is reachable (not the wrong store)"
    ;; Regression guard for finding #1: writing via ct/ctrl-write! must be
    ;; visible to save-session!. If save-session! read nous.ctrl/get instead,
    ;; this would find nil and return nil even though content exists.
    (ct/ctrl-write! [:notation :session :lilypond] "{ c'1 }")
    (ct/ctrl-write! [:session :dir] (temp-dir))
    (is (some? (notation/save-session!))
        "ct/ctrl-write! content is reachable by save-session!")))

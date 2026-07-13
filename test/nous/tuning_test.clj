; SPDX-License-Identifier: EPL-2.0
(ns nous.tuning-test
  "Unit tests for nous.tuning — live tuning path and maqam preset navigation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core :as ct]
            [ctrl-tree.refs :as refs]
            [nous.core   :as core]
            [nous.loop   :as loop-ns]
            [nous.scala  :as scala]
            [nous.tuning :as tuning]))

;; ---------------------------------------------------------------------------
;; Fixtures + helpers
;; ---------------------------------------------------------------------------

(defn- with-system [f]
  (core/start! :no-log true)
  (tuning/install-tuning-watch!)
  ;; Clear tuning-related ctrl paths and the *tuning-ctx* root binding.
  (ct/ctrl-write! [:theory :tuning] nil)
  (ct/ctrl-write! [:theory :maqam_presets] nil)
  (ct/ctrl-write! [:theory :maqam_index] nil)
  (ct/ctrl-write! [:theory :maqam_name] nil)
  (alter-var-root #'loop-ns/*tuning-ctx* (constantly nil))
  (try (f)
       (finally
         (tuning/remove-tuning-watch!)
         ;; Reset the *tuning-ctx* ROOT binding so an activated tuning cannot
         ;; leak into a later namespace's play! calls (these tests set it via
         ;; use-tuning!/alter-var-root, which outlives the dynamic scope).
         (alter-var-root #'loop-ns/*tuning-ctx* (constantly nil))
         (core/stop!))))

(use-fixtures :each with-system)

;; A minimal 3-note test scale: unison, 100¢, 200¢, period 1200¢.
(def ^:private scl-text
  "! test.scl\ntest scale\n3\n100.0\n200.0\n1200.0\n")

(defn- test-scale [] (scala/parse-scl scl-text))

;; A quarter-tone-ish second scale for preset switching.
(def ^:private scl-text-2
  "! quarter.scl\nquarter tone\n2\n150.0\n1200.0\n")

(defn- wait-for
  "Poll `pred` up to ~1s (async watch install). Returns pred's value or nil."
  [pred]
  (loop [tries 100]
    (let [v (pred)]
      (if (or v (zero? tries))
        v
        (do (Thread/sleep 10) (recur (dec tries)))))))

;; ---------------------------------------------------------------------------
;; set-tuning! — descriptor + synchronous install
;; ---------------------------------------------------------------------------

(deftest set-tuning-writes-descriptor-test
  (testing "set-tuning! reflects a serialisable descriptor at [:theory :tuning]"
    (tuning/set-tuning! "test" (test-scale))
    (let [d (tuning/current-tuning)]
      (is (= "test" (:name d)))
      (is (= 1200.0 (:period_cents d)))
      (is (= 3 (:degree_count d)))
      (is (string? (:file d))))))

(deftest set-tuning-installs-synchronously-test
  (testing "set-tuning! installs the scale into *tuning-ctx* without waiting"
    (tuning/set-tuning! "test" (test-scale))
    (is (some? loop-ns/*tuning-ctx*))
    (is (= (test-scale) (:scale loop-ns/*tuning-ctx*)))))

(deftest clear-tuning-disables-retuning-test
  (testing "clear-tuning! returns to 12-TET and clears the path"
    (tuning/set-tuning! "test" (test-scale))
    (tuning/clear-tuning!)
    (is (nil? loop-ns/*tuning-ctx*))
    (is (nil? (tuning/current-tuning)))))

;; ---------------------------------------------------------------------------
;; External path write — watch resolves + installs
;; ---------------------------------------------------------------------------

(deftest external-write-installs-via-watch-test
  (testing "an external [:theory :tuning] write (registered scale) installs live"
    (tuning/register-scale! "cable-scale" (test-scale))
    ;; Simulate a cable/surface-patch write: descriptor only, no direct install.
    (ct/ctrl-write! [:theory :tuning]
                    {:name "cable-scale" :file "" :period_cents 1200.0 :degree_count 3})
    (is (some? (wait-for #(some? loop-ns/*tuning-ctx*))))
    (is (= (test-scale) (:scale loop-ns/*tuning-ctx*)))))

;; ---------------------------------------------------------------------------
;; Maqam presets
;; ---------------------------------------------------------------------------

(deftest set-maqam-presets-writes-list-test
  (testing "set-maqam-presets! writes a serialisable list and resets the index"
    (tuning/set-maqam-presets!
      [{:name "rast" :scale (test-scale)}
       {:name "bayati" :scale (scala/parse-scl scl-text-2)}])
    (is (= 2 (count (tuning/maqam-presets))))
    (is (= "rast" (:name (first (tuning/maqam-presets)))))
    (is (= 0 (tuning/maqam-index)))))

(deftest maqam-goto-selects-and-activates-test
  (testing "maqam-goto! sets index, echoes name, and activates the tuning"
    (tuning/set-maqam-presets!
      [{:name "rast" :scale (test-scale)}
       {:name "bayati" :scale (scala/parse-scl scl-text-2)}])
    (tuning/maqam-goto! 1)
    (is (= 1 (tuning/maqam-index)))
    (is (= "bayati" (get @refs/tree-state [:theory :maqam_name])))
    (is (= (scala/parse-scl scl-text-2) (:scale loop-ns/*tuning-ctx*)))))

(deftest maqam-next-wraps-test
  (testing "maqam-next! advances and wraps past the last preset"
    (tuning/set-maqam-presets!
      [{:name "a" :scale (test-scale)}
       {:name "b" :scale (scala/parse-scl scl-text-2)}])
    (tuning/maqam-next!)          ; 0 → 1
    (is (= 1 (tuning/maqam-index)))
    (tuning/maqam-next!)          ; 1 → 0 (wrap)
    (is (= 0 (tuning/maqam-index)))
    (is (= "a" (get @refs/tree-state [:theory :maqam_name])))))

(deftest maqam-prev-wraps-test
  (testing "maqam-prev! steps backward and wraps below zero"
    (tuning/set-maqam-presets!
      [{:name "a" :scale (test-scale)}
       {:name "b" :scale (scala/parse-scl scl-text-2)}])
    (tuning/maqam-prev!)          ; 0 → 1 (wrap)
    (is (= 1 (tuning/maqam-index)))
    (is (= "b" (get @refs/tree-state [:theory :maqam_name])))))

(deftest maqam-goto-empty-list-noop-test
  (testing "maqam-goto! is a no-op when no presets are installed"
    (is (nil? (tuning/maqam-goto! 3)))
    (is (= 0 (tuning/maqam-index)))))

;; ---------------------------------------------------------------------------
;; maqam-nav! — direct, synchronous, loss-free (Gate 4 finding #6)
;; ---------------------------------------------------------------------------

(deftest maqam-nav-steps-directly-test
  (testing "maqam-nav! steps synchronously and does not drop same-direction presses"
    (tuning/set-maqam-presets!
      [{:name "a" :scale (test-scale)}
       {:name "b" :scale (scala/parse-scl scl-text-2)}])
    (tuning/maqam-nav! :next)
    (is (= 1 (tuning/maqam-index)) "first :next steps")
    ;; A second same-direction press must NOT be dropped — this is the exact
    ;; interleaving the old level-keyword endpoint lost.
    (tuning/maqam-nav! :next)
    (is (= 0 (tuning/maqam-index)) "second :next steps (wraps), not dropped")
    (tuning/maqam-nav! :prev)
    (is (= 1 (tuning/maqam-index)) ":prev steps back")
    (is (= "b" (get @refs/tree-state [:theory :maqam_name])))))

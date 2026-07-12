; SPDX-License-Identifier: EPL-2.0
(ns nous.keyboard-test
  "Unit tests for nous.keyboard — mode dispatch and interval navigation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.core     :as core]
            [nous.ctrl     :as ctrl]
            [nous.keyboard :as keyboard]
            [nous.live     :as live]
            [nous.loop     :as loop-ns]
            [nous.scale    :as scale]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- with-system [f]
  (core/start! :no-log true)
  ;; Reset keyboard and recording state so tests are independent of execution order.
  (ctrl/set! [:keyboard :mode] nil)
  (ctrl/set! [:seq :tone_row_in_progress] nil)
  (ctrl/set! [:seq :tone_row] nil)
  (keyboard/reset-position!)
  (when (keyboard/recording?) (keyboard/stop-recording!))
  (try (f)
       (finally (core/stop!))))

(use-fixtures :each with-system)

(defn- capture-play!
  "Spy on live/play! and return the step maps fired during `f`."
  [f]
  (let [calls (atom [])]
    (with-redefs [live/play! (fn [step] (swap! calls conj step))]
      (f))
    @calls))

;; ---------------------------------------------------------------------------
;; keyboard-mode — reads [:keyboard :mode]
;; ---------------------------------------------------------------------------

(deftest keyboard-mode-default-test
  (testing "returns :pitch when no mode has been set"
    (is (= :pitch (keyboard/keyboard-mode)))))

(deftest keyboard-mode-set-via-ctrl-test
  (testing "reads :interval after ctrl/set!"
    (ctrl/set! [:keyboard :mode] :interval)
    (is (= :interval (keyboard/keyboard-mode)))))

;; ---------------------------------------------------------------------------
;; set-mode! — validation guard
;; ---------------------------------------------------------------------------

(deftest set-mode-valid-test
  (testing "set-mode! accepts all three valid modes"
    (is (nil? (keyboard/set-mode! :pitch)))
    (is (nil? (keyboard/set-mode! :interval)))
    (is (nil? (keyboard/set-mode! :interval-last-note)))))

(deftest set-mode-invalid-test
  (testing "set-mode! throws on unknown mode"
    (is (thrown? AssertionError (keyboard/set-mode! :chromatic)))))

;; ---------------------------------------------------------------------------
;; interval-note-on! — navigation and play! dispatch
;; ---------------------------------------------------------------------------

(deftest interval-note-on-advances-position-test
  (testing "pressing 's' (+1) from root advances to degree 2"
    (keyboard/reset-position!)
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [calls (capture-play! #(keyboard/interval-note-on! "s"))]
          (is (= 1 (count calls)))
          (is (= 2 (:pitch/degree (first calls))) "degree 2 after +1 step"))
        (is (= 2 (keyboard/current-position)))))))

(deftest interval-note-on-wraps-around-test
  (testing "stepping past the last degree wraps to the start"
    (keyboard/reset-position!)
    (let [hctx (scale/scale :C 4 :major)     ; 7 steps
          n    7]
      (binding [loop-ns/*harmony-ctx* hctx]
        ;; Manually jump to last degree by pressing 'j' (+4) then 'h' (+3) = 7 → pos 6 (deg 7)
        (capture-play! #(keyboard/interval-note-on! "j"))  ; pos 4, deg 5
        (capture-play! #(keyboard/interval-note-on! "f"))  ; pos 6, deg 7
        (is (= 7 (keyboard/current-position)) "at degree 7 before wrap")
        ;; Now +1 should wrap back to degree 1
        (let [calls (capture-play! #(keyboard/interval-note-on! "s"))]
          (is (= 1 (:pitch/degree (first calls))) "wrapped back to degree 1")
          (is (= 1 (keyboard/current-position))))))))

(deftest interval-note-on-down-wraps-test
  (testing "-1 from root (degree 1) wraps to the last degree"
    (keyboard/reset-position!)
    (let [hctx (scale/scale :C 4 :major)]   ; 7 steps
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [calls (capture-play! #(keyboard/interval-note-on! "a"))]
          (is (= 7 (:pitch/degree (first calls))) "wrapped down to degree 7")
          (is (= 7 (keyboard/current-position))))))))

(deftest interval-note-on-unknown-key-no-op-test
  (testing "unknown key does not advance position or fire play!"
    (keyboard/reset-position!)
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [calls (capture-play! #(keyboard/interval-note-on! "z"))]
          (is (empty? calls) "no play! on unmapped key")
          (is (= 1 (keyboard/current-position)) "position unchanged"))))))

;; ---------------------------------------------------------------------------
;; reset-position! — back to root
;; ---------------------------------------------------------------------------

(deftest reset-position-test
  (testing "reset-position! brings the wheel back to degree 1"
    (keyboard/reset-position!)
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (capture-play! #(keyboard/interval-note-on! "f"))   ; +2
        (is (= 3 (keyboard/current-position)))
        (keyboard/reset-position!)
        (is (= 1 (keyboard/current-position)))))))

;; ---------------------------------------------------------------------------
;; default-interval-map completeness
;; ---------------------------------------------------------------------------

(deftest default-interval-map-contains-keys-test
  (testing "default map covers the expected home-row keys"
    (let [dm keyboard/default-interval-map]
      (is (= -1 (dm "a")))
      (is (= +1 (dm "s")))
      (is (= -2 (dm "d")))
      (is (= +2 (dm "f")))
      (is (= -3 (dm "g")))
      (is (= +3 (dm "h")))
      (is (= +4 (dm "j")))
      (is (= -4 (dm "w"))))))

;; ---------------------------------------------------------------------------
;; Recording (M17)
;; ---------------------------------------------------------------------------

(deftest start-recording-clears-buffer-test
  (testing "start-recording! clears any existing in-progress row"
    (ctrl/set! [:seq :tone_row_in_progress] [{:interval 1 :vel 100}])
    (keyboard/start-recording!)
    (is (= [] (ctrl/get [:seq :tone_row_in_progress])))
    (is (true? (keyboard/recording?)))))

(deftest interval-note-on-appends-when-recording-test
  (testing "interval keypress appends {:interval n :vel 100} while recording"
    (keyboard/start-recording!)
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (capture-play! #(keyboard/interval-note-on! "s"))  ; +1
        (capture-play! #(keyboard/interval-note-on! "f"))  ; +2
        (capture-play! #(keyboard/interval-note-on! "a")))) ; -1
    (let [row (ctrl/get [:seq :tone_row_in_progress])]
      (is (= 3 (count row)))
      (is (= {:interval 1 :vel 100} (nth row 0)))
      (is (= {:interval 2 :vel 100} (nth row 1)))
      (is (= {:interval -1 :vel 100} (nth row 2))))))

(deftest stop-recording-commits-row-test
  (testing "stop-recording! commits in-progress row to [:seq :tone_row]"
    (keyboard/start-recording!)
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (capture-play! #(keyboard/interval-note-on! "s"))
        (capture-play! #(keyboard/interval-note-on! "f"))))
    (keyboard/stop-recording!)
    (is (false? (keyboard/recording?)))
    (is (= 2 (count (ctrl/get [:seq :tone_row]))))
    (is (= {:interval 1 :vel 100} (first (ctrl/get [:seq :tone_row]))))))

(deftest no-recording-without-start-test
  (testing "interval keypresses do NOT append when not recording"
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (capture-play! #(keyboard/interval-note-on! "s"))))
    (is (nil? (ctrl/get [:seq :tone_row_in_progress])))))

(deftest clear-row-resets-buffer-test
  (testing "clear-row! empties in-progress buffer, stays in record mode"
    (keyboard/start-recording!)
    (let [hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (capture-play! #(keyboard/interval-note-on! "s"))))
    (is (= 1 (count (ctrl/get [:seq :tone_row_in_progress]))))
    (keyboard/clear-row!)
    (is (= [] (ctrl/get [:seq :tone_row_in_progress])))
    (is (true? (keyboard/recording?)))))

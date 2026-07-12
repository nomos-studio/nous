; SPDX-License-Identifier: EPL-2.0
(ns nous.interval-seq-test
  "Unit tests for nous.seq/make-interval-seq — tone row traversal and pitch resolution."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.core  :as core]
            [nous.ctrl  :as ctrl]
            [nous.live  :as live]
            [nous.loop  :as loop-ns]
            [nous.scale :as scale]
            [nous.seq   :as sq]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- with-system [f]
  (core/start! :no-log true)
  (ctrl/set! [:seq :play_option] nil)
  (try (f)
       (finally (core/stop!))))

(use-fixtures :each with-system)

(defn- capture-play!
  "Spy on live/play! and return step maps fired during `f`."
  [f]
  (let [calls (atom [])]
    (with-redefs [live/play! (fn [step] (swap! calls conj step))]
      (f))
    @calls))

(defn- run-n-steps [sq n]
  "Call next-event n times, collect :event maps (non-nil only)."
  (keep :event (repeatedly n #(sq/next-event sq))))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(deftest empty-steps-throws-test
  (testing "empty step row is rejected at construction"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sq/make-interval-seq [] 1/4)))))

;; ---------------------------------------------------------------------------
;; Prime traversal
;; ---------------------------------------------------------------------------

(deftest prime-traversal-test
  (testing ":prime plays steps in order 0, 1, 2, 3"
    (let [row [(vec [{:interval +1} {:interval +2} {:interval -1} {:interval +2}])
               1/4]
          sq  (apply sq/make-interval-seq row)
          hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [events (run-n-steps sq 4)
              degrees (mapv :pitch/degree events)]
          ;; Starting at degree 1 (wheel pos 0):
          ;; +1 → pos 1 → deg 2
          ;; +2 → pos 3 → deg 4
          ;; -1 → pos 2 → deg 3
          ;; +2 → pos 4 → deg 5
          (is (= [2 4 3 5] degrees)))))))

(deftest prime-wraps-test
  (testing ":prime cycles back to step 0 after n steps"
    (let [sq   (sq/make-interval-seq [{:interval +1} {:interval -1}] 1/4)
          hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [events (run-n-steps sq 4)
              degrees (mapv :pitch/degree events)]
          ;; +1, -1, +1, -1 → positions 1, 0, 1, 0 → degrees 2, 1, 2, 1
          (is (= [2 1 2 1] degrees)))))))

;; ---------------------------------------------------------------------------
;; Retro traversal
;; ---------------------------------------------------------------------------

(deftest retro-traversal-test
  (testing ":retro plays steps in reverse order n-1, n-2, …, 0"
    (let [sq   (sq/make-interval-seq
                 [{:interval +1} {:interval +2} {:interval +1}] 1/4)
          hctx (scale/scale :C 4 :major)]
      (ctrl/set! [:seq :play_option] :retro)
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [events (run-n-steps sq 3)
              ;; step index order for :retro with n=3: 2, 1, 0
              ;; intervals: +1, +2, +1
              ;; :retro reads step[2]=+1, step[1]=+2, step[0]=+1
              ;; Starting at wheel 0:
              ;; step[2]=+1 → pos 1 → deg 2
              ;; step[1]=+2 → pos 3 → deg 4
              ;; step[0]=+1 → pos 4 → deg 5
              degrees (mapv :pitch/degree events)]
          (is (= [2 4 5] degrees)))))))

;; ---------------------------------------------------------------------------
;; Pendulum traversal
;; ---------------------------------------------------------------------------

(deftest pendulum-traversal-test
  (testing ":pendulum bounces forward then backward"
    (let [sq   (sq/make-interval-seq
                 [{:interval +1} {:interval +1} {:interval +1}] 1/4)
          hctx (scale/scale :C 4 :major)]
      (ctrl/set! [:seq :play_option] :pendulum)
      (binding [loop-ns/*harmony-ctx* hctx]
        ;; n=3, period=4: step indices 0,1,2,1 → intervals +1,+1,+1,+1
        ;; Starting at wheel 0:
        ;; step[0]=+1 → pos 1 → deg 2
        ;; step[1]=+1 → pos 2 → deg 3
        ;; step[2]=+1 → pos 3 → deg 4
        ;; step[1]=+1 → pos 4 → deg 5
        (let [events  (run-n-steps sq 4)
              degrees (mapv :pitch/degree events)]
          (is (= [2 3 4 5] degrees)))))))

;; ---------------------------------------------------------------------------
;; Wheel wrap-around
;; ---------------------------------------------------------------------------

(deftest wheel-wraps-past-scale-end-test
  (testing "wheel position wraps when delta pushes past scale length"
    (let [sq   (sq/make-interval-seq [{:interval +3}] 1/4 :start-degree 6)
          hctx (scale/scale :C 4 :major)]    ; 7 steps
      (binding [loop-ns/*harmony-ctx* hctx]
        ;; start-degree 6 → wheel pos 5
        ;; +3 → (5+3) mod 7 = 1 → deg 2
        (let [ev (sq/next-event sq)]
          (is (= 2 (:pitch/degree (:event ev)))))))))

;; ---------------------------------------------------------------------------
;; seq-cycle-length
;; ---------------------------------------------------------------------------

(deftest cycle-length-test
  (testing "seq-cycle-length equals the number of steps"
    (let [row [{:interval +1} {:interval -1} {:interval +2}]]
      (is (= 3 (sq/seq-cycle-length (sq/make-interval-seq row 1/4)))))))

;; ---------------------------------------------------------------------------
;; Probability gate
;; ---------------------------------------------------------------------------

(deftest prob-zero-always-rests-test
  (testing ":prob 0.0 produces only rests"
    (let [sq   (sq/make-interval-seq [{:interval +1 :prob 0.0}] 1/4)
          hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [events (run-n-steps sq 8)]
          (is (empty? events) "all steps are rests when prob=0.0"))))))

;; ---------------------------------------------------------------------------
;; Velocity and gate passthrough
;; ---------------------------------------------------------------------------

(deftest velocity-and-gate-passthrough-test
  (testing "vel and gate values from step map reach the emitted event"
    (let [sq   (sq/make-interval-seq [{:interval +1 :vel 80 :gate 1/8}] 1/4)
          hctx (scale/scale :C 4 :major)]
      (binding [loop-ns/*harmony-ctx* hctx]
        (let [{:keys [event]} (sq/next-event sq)]
          (is (= 80 (:mod/velocity event)))
          (is (= 1/8 (:dur/beats event))))))))

;; ---------------------------------------------------------------------------
;; Wheel position reflected in returned degree
;; ---------------------------------------------------------------------------

(deftest wheel-position-echoed-as-degree-test
  (testing "next-event :pitch/degree reflects wheel advance (display echo is a side-effect)"
    (let [sq   (sq/make-interval-seq [{:interval +2}] 1/4)
          hctx (scale/scale :C 4 :major)]   ; 7 steps, start wheel pos 0
      (binding [loop-ns/*harmony-ctx* hctx]
        ;; +2 from pos 0 → pos 2 → degree 3
        (let [{:keys [event]} (sq/next-event sq)]
          (is (= 3 (:pitch/degree event))))))))

;; ---------------------------------------------------------------------------
;; start-degree option
;; ---------------------------------------------------------------------------

(deftest start-degree-option-test
  (testing ":start-degree seeds the wheel at a non-root position"
    (let [sq   (sq/make-interval-seq [{:interval +1}] 1/4 :start-degree 4)
          hctx (scale/scale :C 4 :major)]   ; start at degree 4 (pos 3)
      (binding [loop-ns/*harmony-ctx* hctx]
        ;; pos 3 + 1 = pos 4 → degree 5
        (let [{:keys [event]} (sq/next-event sq)]
          (is (= 5 (:pitch/degree event))))))))

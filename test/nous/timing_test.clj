; SPDX-License-Identifier: EPL-2.0
(ns nous.timing-test
  "Unit tests for nous.timing — swing, humanize, combine, and play! integration."
  (:require [clojure.test  :refer [deftest is testing]]
            [nous.clock  :as clock]
            [nous.core   :as core]
            [nous.live  :as live]
            [nous.loop   :as loop-ns]
            [nous.timing :as timing]))

;; ---------------------------------------------------------------------------
;; Swing — sample values
;; ---------------------------------------------------------------------------

(deftest swing-downbeat-test
  (testing "swing returns 0.0 at even 8th-note positions (downbeats)"
    (let [sw (timing/swing :amount 0.6)]
      (is (= 0.0 (clock/sample sw 0.0))   "beat 0 — downbeat")
      (is (= 0.0 (clock/sample sw 1.0))   "beat 1 — downbeat")
      (is (= 0.0 (clock/sample sw 2.0))   "beat 2 — downbeat")
      (is (= 0.0 (clock/sample sw 0.25))  "beat 0.25 — still in downbeat region")
      (is (= 0.0 (clock/sample sw 1.49))  "beat 1.49 — just before upbeat"))))

(deftest swing-upbeat-test
  (testing "swing returns (amount - 0.5) at odd 8th-note positions (upbeats)"
    (let [sw     (timing/swing :amount 0.6)
          offset (- 0.6 0.5)]
      (is (= offset (clock/sample sw 0.5))  "beat 0.5 — upbeat")
      (is (= offset (clock/sample sw 1.5))  "beat 1.5 — upbeat")
      (is (= offset (clock/sample sw 0.75)) "beat 0.75 — still in upbeat region")
      (is (= offset (clock/sample sw 1.99)) "beat 1.99 — just before downbeat"))))

(deftest swing-straight-test
  (testing "swing with amount 0.5 returns 0.0 everywhere (no swing)"
    (let [sw (timing/swing :amount 0.5)]
      (is (= 0.0 (clock/sample sw 0.0)))
      (is (= 0.0 (clock/sample sw 0.5)))
      (is (= 0.0 (clock/sample sw 1.5))))))

(deftest swing-next-edge-test
  (testing "next-edge returns the next 8th-note boundary"
    (let [sw (timing/swing)]
      (is (= 0.5 (clock/next-edge sw 0.0))  "from beat 0 → next edge 0.5")
      (is (= 0.5 (clock/next-edge sw 0.3))  "from beat 0.3 → next edge 0.5")
      (is (= 1.0 (clock/next-edge sw 0.5))  "from beat 0.5 → next edge 1.0")
      (is (= 1.0 (clock/next-edge sw 0.8))  "from beat 0.8 → next edge 1.0")
      (is (= 1.5 (clock/next-edge sw 1.0))  "from beat 1.0 → next edge 1.5"))))

;; ---------------------------------------------------------------------------
;; Humanize — sample values
;; ---------------------------------------------------------------------------

(deftest humanize-range-test
  (testing "humanize samples fall within [-amount, +amount]"
    (let [h      (timing/humanize :amount 0.02)
          samples (repeatedly 200 #(clock/sample h 0.0))]
      (is (every? #(<= -0.02 % 0.02) samples)
          "all samples within declared range")))
  (testing "humanize produces both positive and negative values over many samples"
    (let [h       (timing/humanize :amount 0.05)
          samples (repeatedly 200 #(clock/sample h 0.0))]
      (is (some pos? samples)  "some positive offsets")
      (is (some neg? samples)  "some negative offsets"))))

;; ---------------------------------------------------------------------------
;; Combine — additive composition
;; ---------------------------------------------------------------------------

(deftest combine-sums-offsets-test
  (testing "combine returns sum of constituent offsets"
    ;; Use swing (deterministic) only; humanize would add noise
    (let [sw1    (timing/swing :amount 0.6)   ; upbeat offset = 0.1
          sw2    (timing/swing :amount 0.55)  ; upbeat offset = 0.05
          combo  (timing/combine sw1 sw2)]
      ;; At beat 0.0 (downbeat): 0.0 + 0.0 = 0.0
      (is (= 0.0 (clock/sample combo 0.0)))
      ;; At beat 0.5 (upbeat): 0.1 + 0.05 = 0.15
      ;; Use tolerance for FP addition: 0.1 + 0.05 = 0.15000000000000002
      (is (< (Math/abs (- (clock/sample combo 0.5) 0.15)) 1e-10)
          "upbeat offset sums to ~0.15"))))

(deftest combine-next-edge-test
  (testing "combine next-edge returns minimum of constituent next-edges"
    (let [sw     (timing/swing)
          h      (timing/humanize)
          combo  (timing/combine sw h)]
      ;; swing next-edge from 0.0 = 0.5; humanize next-edge = 1.0
      (is (= 0.5 (clock/next-edge combo 0.0))
          "minimum of swing(0.5) and humanize(1.0)"))))

;; ---------------------------------------------------------------------------
;; Integration — play! applies timing offset to kairos beat positions
;; ---------------------------------------------------------------------------

(deftest play-swing-shifts-upbeat-beat-test
  (testing "play! with swing context shifts off-beat note-on beat position"
    (core/start! :bpm 120)
    (try
      (let [captured (atom [])]
        (with-redefs [nous.kairos/connected?    (constantly true)
                      nous.kairos/send-note-on! (fn [_n _v & {:keys [beat]}]
                                                   (swap! captured conj beat))
                      nous.kairos/send-note-off! (fn [& _] nil)]
          ;; Downbeat note — no swing
          (binding [loop-ns/*virtual-time* 0.0
                    loop-ns/*timing-ctx*   nil]
            (core/play! :C4 1/4))
          ;; Upbeat note — should be shifted by swing amount
          (binding [loop-ns/*virtual-time* 0.5
                    loop-ns/*timing-ctx*   (timing/swing :amount 0.6)]
            (core/play! :E4 1/4)))
        (let [sw                  (timing/swing :amount 0.6)
              [beat-down beat-up] @captured
              expected-down       0.0
              expected-up         (+ 0.5 (clock/sample sw 0.5))]
          (is (= expected-down beat-down) "downbeat beat unchanged")
          (is (= expected-up beat-up)     "upbeat beat shifted by swing offset")))
      (finally (core/stop!)))))

(deftest play-downbeat-unaffected-by-swing-test
  (testing "play! with swing does not shift downbeat notes"
    (core/start! :bpm 120)
    (try
      (let [captured (atom nil)]
        (with-redefs [nous.kairos/connected?    (constantly true)
                      nous.kairos/send-note-on! (fn [_n _v & {:keys [beat]}]
                                                   (reset! captured beat))
                      nous.kairos/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 100.0
                    loop-ns/*timing-ctx*   (timing/swing :amount 0.6)]
            (core/play! :C4 1/4)))
        (is (= 100.0 @captured) "downbeat beat unmodified by swing"))
      (finally (core/stop!)))))

(deftest play-no-timing-ctx-unaffected-test
  (testing "play! with nil *timing-ctx* produces unmodified beat position"
    (core/start! :bpm 120)
    (try
      (let [captured (atom nil)]
        (with-redefs [nous.kairos/connected?    (constantly true)
                      nous.kairos/send-note-on! (fn [_n _v & {:keys [beat]}]
                                                   (reset! captured beat))
                      nous.kairos/send-note-off! (fn [& _] nil)]
          (binding [loop-ns/*virtual-time* 0.5
                    loop-ns/*timing-ctx*   nil]
            (core/play! :E4 1/4)))
        (is (= 0.5 @captured) "no offset applied with nil context"))
      (finally (core/stop!)))))

;; ---------------------------------------------------------------------------
;; with-timing macro
;; ---------------------------------------------------------------------------

(deftest with-timing-binds-ctx-test
  (testing "with-timing binds *timing-ctx* for the scope"
    (let [sw       (timing/swing)
          captured (atom nil)]
      ;; Capture *timing-ctx* directly from inside the body rather than
      ;; redeffing clock/sample (protocol dispatch doesn't reliably intercept).
      (live/with-timing sw
        (reset! captured loop-ns/*timing-ctx*))
      (is (= sw @captured) "*timing-ctx* was bound inside with-timing scope")))
  (testing "with-timing restores nil after the block"
    (binding [loop-ns/*timing-ctx* nil]
      (live/with-timing (timing/swing)
        (is (some? loop-ns/*timing-ctx*)))
      (is (nil? loop-ns/*timing-ctx*) "restored after block"))))

;; ---------------------------------------------------------------------------
;; deflive-loop :timing opts integration
;; ---------------------------------------------------------------------------

(deftest deflive-loop-timing-opts-test
  (testing ":timing in deflive-loop opts binds *timing-ctx* for the loop body"
    (core/start! :bpm 6000)
    (try
      (let [sw       (timing/swing :amount 0.6)
            observed (promise)]
        ;; Capture *timing-ctx* directly from inside the loop body —
        ;; with-redefs on a protocol fn doesn't reliably intercept daemon threads.
        (with-redefs [nous.kairos/connected?    (constantly true)
                      nous.kairos/send-note-on!  (fn [& _] nil)
                      nous.kairos/send-note-off! (fn [& _] nil)]
          (core/deflive-loop :timing-test {:timing sw}
            (deliver observed loop-ns/*timing-ctx*)
            (core/stop-loop! :timing-test))
          (let [result (deref observed 500 ::timeout)]
            (is (= sw result) "*timing-ctx* bound to swing inside loop"))))
      (finally (core/stop!)))))

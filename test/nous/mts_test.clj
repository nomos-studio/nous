; SPDX-License-Identifier: EPL-2.0
(ns nous.mts-test
  "Unit tests for nous.mts — beat-accurate MTS retune arc scheduling.

  Pure functions (lerp-freq-maps, arc-beats) are tested exhaustively.
  retune-arc! is tested structurally via a captured send-mts! mock that
  records calls without blocking on real timing."
  (:require [clojure.test :refer [deftest is testing]]
            [nous.mts :as mts]))

;; ---------------------------------------------------------------------------
;; Test fixtures — simple freq-maps (not Scala-derived, just for math)
;; ---------------------------------------------------------------------------

;; 12-TET freq-map: A4 = 440 Hz, each semitone × 2^(1/12)
(defn- tet-map []
  (into {}
    (map (fn [k] [k (* 440.0 (Math/pow 2.0 (/ (- k 69.0) 12.0)))])
         (range 128))))

;; Uniform 880 Hz map (degenerate: all keys at the same Hz)
(defn- flat-map [hz]
  (into {} (map (fn [k] [k (double hz)]) (range 128))))

;; ---------------------------------------------------------------------------
;; lerp-freq-maps
;; ---------------------------------------------------------------------------

(deftest lerp-completeness-test
  (testing "result always has 128 entries"
    (let [result (mts/lerp-freq-maps (tet-map) (flat-map 880.0) 0.5)]
      (is (= 128 (count result)))
      (is (every? #(contains? result %) (range 128))))))

(deftest lerp-endpoints-test
  (let [from (tet-map)
        to   (flat-map 880.0)]
    (testing "t=0 returns from-map"
      (let [r (mts/lerp-freq-maps from to 0.0)]
        (doseq [k (range 128)]
          (is (< (Math/abs (- (double (get r k)) (double (get from k)))) 1e-9)
              (str "key " k " mismatch at t=0")))))
    (testing "t=1 returns to-map"
      (let [r (mts/lerp-freq-maps from to 1.0)]
        (doseq [k (range 128)]
          (is (< (Math/abs (- (double (get r k)) (double (get to k)))) 1e-9)
              (str "key " k " mismatch at t=1")))))))

(deftest lerp-midpoint-test
  (testing "t=0.5 is geometric mean (log-linear midpoint)"
    ;; All keys at 440 Hz → all at 880 Hz: midpoint is 2^0.5 × 440 ≈ 622.25 Hz
    (let [from (flat-map 440.0)
          to   (flat-map 880.0)
          r    (mts/lerp-freq-maps from to 0.5)]
      (is (< (Math/abs (- (double (get r 60)) (* 440.0 (Math/sqrt 2.0)))) 1e-9))))
  (testing "t=0.5 is NOT the arithmetic mean for wide intervals"
    (let [from (flat-map 440.0)
          to   (flat-map 880.0)
          r    (mts/lerp-freq-maps from to 0.5)]
      ;; arithmetic mean would be 660 Hz; geometric midpoint is ~622.25 Hz
      (is (< (double (get r 60)) 660.0)))))

(deftest lerp-monotone-test
  (testing "interpolated Hz increases monotonically with t for ascending from→to"
    (let [from (flat-map 220.0)
          to   (flat-map 880.0)
          ts   [0.0 0.25 0.5 0.75 1.0]
          vals (map #(double (get (mts/lerp-freq-maps from to %) 60)) ts)]
      (is (apply < vals)))))

(deftest lerp-positive-hz-test
  (testing "all output Hz values are positive"
    (let [from (tet-map)
          to   (flat-map 880.0)]
      (doseq [t [0.0 0.25 0.5 0.75 1.0]]
        (let [r (mts/lerp-freq-maps from to t)]
          (is (every? pos? (vals r))
              (str "found non-positive Hz at t=" t)))))))

;; ---------------------------------------------------------------------------
;; arc-beats
;; ---------------------------------------------------------------------------

(deftest arc-beats-count-test
  (testing "returns exactly `steps` beat positions"
    (is (= 8  (count (mts/arc-beats 0.0 32.0 8))))
    (is (= 1  (count (mts/arc-beats 0.0 32.0 1))))
    (is (= 16 (count (mts/arc-beats 10.0 8.0 16))))))

(deftest arc-beats-endpoints-test
  (testing "first beat is start-beat"
    (is (= 4.0  (first (mts/arc-beats 4.0 16.0 8))))
    (is (= 16.0 (first (mts/arc-beats 16.0 0.0 4)))))
  (testing "last beat is start-beat + beats"
    (is (< (Math/abs (- (last (mts/arc-beats 0.0 32.0 8)) 32.0)) 1e-9))
    (is (< (Math/abs (- (last (mts/arc-beats 4.0 16.0 4)) 20.0)) 1e-9))))

(deftest arc-beats-monotone-test
  (testing "beat positions increase strictly"
    (let [bs (mts/arc-beats 0.0 32.0 8)]
      (is (apply < bs)))))

(deftest arc-beats-spacing-test
  (testing "steps are evenly spaced"
    (let [bs (mts/arc-beats 0.0 28.0 8)
          gaps (map - (rest bs) bs)]
      (doseq [g gaps]
        (is (< (Math/abs (- g (/ 28.0 7.0))) 1e-9))))))

;; ---------------------------------------------------------------------------
;; retune-arc! — structural tests via mock
;; ---------------------------------------------------------------------------

(deftest retune-arc-returns-future-test
  (testing "retune-arc! returns a future"
    (with-redefs [nous.loop/-park-until-beat! (fn [_] nil)
                  nous.kairos/send-mts!       (fn [& _] nil)]
      (let [from (flat-map 440.0)
            to   (flat-map 880.0)
            f    (mts/retune-arc! from to 0.0 32.0)]
        (is (future? f))
        @f))))

(deftest retune-arc-step-count-test
  (testing "sends exactly `steps` MTS messages"
    (let [calls (atom [])]
      (with-redefs [nous.loop/-park-until-beat! (fn [_] nil)
                    nous.kairos/send-mts!       (fn [m & opts]
                                                  (swap! calls conj m)
                                                  nil)]
        (let [from (flat-map 440.0)
              to   (flat-map 880.0)]
          @(mts/retune-arc! from to 0.0 32.0 :steps 5)
          (is (= 5 (count @calls))))))))

(deftest retune-arc-beat-positions-test
  (testing "parks at the correct beat positions"
    (let [parked (atom [])]
      (with-redefs [nous.loop/-park-until-beat! (fn [b] (swap! parked conj b) nil)
                    nous.kairos/send-mts!       (fn [& _] nil)]
        (let [from (flat-map 440.0)
              to   (flat-map 880.0)]
          @(mts/retune-arc! from to 4.0 8.0 :steps 3)
          ;; 3 steps over 8 beats from beat 4: [4.0, 8.0, 12.0]
          (is (= 3 (count @parked)))
          (is (< (Math/abs (- (double (nth @parked 0)) 4.0))  1e-9))
          (is (< (Math/abs (- (double (nth @parked 1)) 8.0))  1e-9))
          (is (< (Math/abs (- (double (nth @parked 2)) 12.0)) 1e-9)))))))

(deftest retune-arc-interpolation-test
  (testing "first send uses from-map, last send uses to-map"
    (let [sends (atom [])]
      (with-redefs [nous.loop/-park-until-beat! (fn [_] nil)
                    nous.kairos/send-mts!       (fn [m & _]
                                                  (swap! sends conj m)
                                                  nil)]
        (let [from (flat-map 440.0)
              to   (flat-map 880.0)]
          @(mts/retune-arc! from to 0.0 16.0 :steps 4)
          (let [first-send (first @sends)
                last-send  (last @sends)]
            (is (< (Math/abs (- (double (get first-send 60)) 440.0)) 1e-9)
                "first send should be from-map (t=0)")
            (is (< (Math/abs (- (double (get last-send 60)) 880.0)) 1e-9)
                "last send should be to-map (t=1)")))))))

(deftest retune-arc-min-steps-test
  (testing "steps < 2 clamps to 2"
    (let [calls (atom 0)]
      (with-redefs [nous.loop/-park-until-beat! (fn [_] nil)
                    nous.kairos/send-mts!       (fn [& _] (swap! calls inc) nil)]
        @(mts/retune-arc! (flat-map 440.0) (flat-map 880.0) 0.0 8.0 :steps 1)
        (is (= 2 @calls))))))

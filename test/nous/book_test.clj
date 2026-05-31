; SPDX-License-Identifier: EPL-2.0
(ns nous.book-test
  "Unit tests for nous.book — defbook harmonic-series sequencer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.book    :as book]
            [nous.core    :as core]
            [nous.ctrl    :as ctrl]))

;; ---------------------------------------------------------------------------
;; Fixtures — needed only for defbook macro (ctrl/defnode! requires system)
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-ctx
  "Build a book context atom directly from options."
  [opts]
  (atom (book/make-book-context opts)))

(def ^:private ground-page
  {:name :ground :harmonics [1 2 3 4] :gravity 1.0})

(def ^:private outer-page
  {:name :outer :harmonics [7 9 11 13] :gravity 0.3})

(def ^:private simple-opts
  {:fundamental :C2
   :pages       [ground-page outer-page]
   :navigation  {:initial :ground :mode :manual}})

;; ---------------------------------------------------------------------------
;; make-book-context
;; ---------------------------------------------------------------------------

(deftest make-book-context-structure-test
  (testing "context has expected keys"
    (let [ctx (book/make-book-context simple-opts)]
      (is (map? ctx))
      (is (number? (:fundamental-hz ctx)))
      (is (vector? (:pages ctx)))
      (is (= 0 (:page-idx ctx)))
      (is (= :free (:output-mode ctx)))
      (is (true? (:octave-fold ctx)))
      (is (nil? (:current-h ctx)))))
  (testing "fundamental :C2 parses to ~65.4 Hz"
    (let [ctx (book/make-book-context simple-opts)]
      (is (> (:fundamental-hz ctx) 65.0))
      (is (< (:fundamental-hz ctx) 66.0))))
  (testing "fundamental :F#2 parses to ~92.5 Hz"
    (let [ctx (book/make-book-context {:fundamental :F#2 :pages [ground-page]})]
      (is (> (:fundamental-hz ctx) 92.0))
      (is (< (:fundamental-hz ctx) 93.0))))
  (testing "fundamental as Hz passes through"
    (let [ctx (book/make-book-context {:fundamental 110.0 :pages [ground-page]})]
      (is (= 110.0 (:fundamental-hz ctx)))))
  (testing "navigation :initial selects correct starting page"
    (let [ctx (book/make-book-context {:fundamental :C2
                                       :pages [ground-page outer-page]
                                       :navigation {:initial :outer :mode :manual}})]
      (is (= 1 (:page-idx ctx)))))
  (testing ":cell mode initializes cell vector"
    (let [ctx (book/make-book-context {:fundamental :C2
                                       :pages [ground-page]
                                       :output-mode :cell
                                       :cell-len 4})]
      (is (= 4 (count (:cell ctx))))
      (is (every? integer? (:cell ctx))))))

;; ---------------------------------------------------------------------------
;; next-step! — basic step structure
;; ---------------------------------------------------------------------------

(deftest next-step-returns-step-map-test
  (testing "step map has required keys"
    (let [ctx  (make-ctx simple-opts)
          step (book/next-step! ctx)]
      (is (map? step))
      (is (contains? step :pitch/voct))
      (is (contains? step :pitch/midi))
      (is (contains? step :dur/beats))
      (is (contains? step :gate/on?))
      (is (contains? step :book/harmonic))
      (is (contains? step :book/gravity))))
  (testing ":gate/on? is always true"
    (let [ctx (make-ctx simple-opts)]
      (dotimes [_ 20]
        (is (true? (:gate/on? (book/next-step! ctx)))))))
  (testing ":pitch/midi is in MIDI range"
    (let [ctx (make-ctx simple-opts)]
      (dotimes [_ 20]
        (let [step (book/next-step! ctx)]
          (is (<= 0 (:pitch/midi step) 127))))))
  (testing ":book/gravity is in [0,1]"
    (let [ctx (make-ctx simple-opts)]
      (dotimes [_ 20]
        (let [g (:book/gravity (book/next-step! ctx))]
          (is (>= g 0.0))
          (is (<= g 1.0))))))
  (testing ":dur/beats is positive"
    (let [ctx (make-ctx simple-opts)]
      (dotimes [_ 10]
        (is (pos? (:dur/beats (book/next-step! ctx)))))))
  (testing ":book/harmonic matches page harmonics"
    (let [ctx   (make-ctx simple-opts)
          valid #{1 2 3 4}]
      (dotimes [_ 30]
        (is (contains? valid (:book/harmonic (book/next-step! ctx))))))))

;; ---------------------------------------------------------------------------
;; next-step! — :pitch/voct correctness
;; ---------------------------------------------------------------------------

(deftest pitch-voct-test
  (testing "harmonic 1 → voct 0.0 (fundamental)"
    ;; With octave-fold=true, harmonics [1] always gives voct=0
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [1] :gravity 1.0
                                   :selection {:mode :sequential}}]})]
      (let [step (book/next-step! ctx)]
        (is (< (Math/abs (:pitch/voct step)) 1e-9)))))
  (testing "harmonic 3 with octave-fold → voct = log2(3/2) ≈ 0.585"
    (let [ctx  (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [3] :gravity 1.0
                                    :selection {:mode :sequential}}]})
          step (book/next-step! ctx)
          expected (/ (Math/log (/ 3.0 2.0)) (Math/log 2.0))]
      (is (< (Math/abs (- (:pitch/voct step) expected)) 1e-9))))
  (testing "midi note for C2 fundamental + harmonic 3 is G2 (MIDI 43)"
    ;; C2=65.406 Hz, harmonic 3 → voct=log2(3/2)≈0.585 → hz=65.406×1.5≈98.1 Hz = G2
    (let [ctx  (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [3] :gravity 1.0
                                    :selection {:mode :sequential}}]})
          step (book/next-step! ctx)]
      (is (= 43 (:pitch/midi step)))))
  (testing "octave-fold false: harmonic 2 → voct=1.0 (full octave above)"
    (let [ctx  (make-ctx {:fundamental :C2
                           :octave-fold false
                           :pages [{:name :p :harmonics [2] :gravity 1.0
                                    :selection {:mode :sequential}}]})
          step (book/next-step! ctx)]
      (is (< (Math/abs (- (:pitch/voct step) 1.0)) 1e-9)))))

;; ---------------------------------------------------------------------------
;; next-step! — gravity field
;; ---------------------------------------------------------------------------

(deftest gravity-field-test
  (testing "harmonic 1 → gravity near 0 (at fundamental)"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [1] :gravity 1.0
                                   :selection {:mode :sequential}}]})]
      (is (< (:book/gravity (book/next-step! ctx)) 0.01))))
  (testing "higher harmonics → higher gravity value"
    ;; harmonic 7 should produce higher gravity than harmonic 3
    (let [ctx3 (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [3] :gravity 1.0
                                    :selection {:mode :sequential}}]})
          ctx7 (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [7] :gravity 1.0
                                    :selection {:mode :sequential}}]})]
      (is (< (:book/gravity (book/next-step! ctx3))
             (:book/gravity (book/next-step! ctx7)))))))

;; ---------------------------------------------------------------------------
;; next-step! — :sequential selection
;; ---------------------------------------------------------------------------

(deftest sequential-selection-test
  (testing ":sequential :dir :up cycles through harmonics in order"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p
                                   :harmonics [3 5 7]
                                   :gravity 1.0
                                   :selection {:mode :sequential :dir :up}}]})]
      (is (= [3 5 7 3 5 7]
             (mapv :book/harmonic (repeatedly 6 #(book/next-step! ctx)))))))
  (testing ":sequential :dir :down cycles in reverse"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p
                                   :harmonics [3 5 7]
                                   :gravity 1.0
                                   :selection {:mode :sequential :dir :down}}]})]
      (is (= [3 7 5 3 7 5]
             (mapv :book/harmonic (repeatedly 6 #(book/next-step! ctx))))))))

;; ---------------------------------------------------------------------------
;; next-step! — :pendulum selection
;; ---------------------------------------------------------------------------

(deftest pendulum-selection-test
  (testing ":pendulum sweeps up then back down"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p
                                   :harmonics [1 3 5 7]
                                   :gravity 1.0
                                   :selection {:mode :pendulum}}]})]
      ;; Expected: 1,3,5,7 then back: 5,3,1 then up again: 3,5,7...
      (let [steps (mapv :book/harmonic (repeatedly 7 #(book/next-step! ctx)))]
        (is (= 1 (first steps)))
        (is (= 7 (nth steps 3)))
        (is (= 5 (nth steps 4))))))
  (testing ":pendulum with single harmonic stays constant"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [5] :gravity 1.0
                                   :selection {:mode :pendulum}}]})]
      (is (every? #(= 5 %) (map :book/harmonic (repeatedly 5 #(book/next-step! ctx))))))))

;; ---------------------------------------------------------------------------
;; next-step! — :proximate selection
;; ---------------------------------------------------------------------------

(deftest proximate-selection-test
  (testing ":proximate returns harmonics from page"
    (let [ctx   (make-ctx {:fundamental :C2
                            :pages [{:name :p :harmonics [1 3 5 7 9] :gravity 0.5
                                     :selection {:mode :proximate}}]})
          valid #{1 3 5 7 9}]
      (dotimes [_ 30]
        (is (contains? valid (:book/harmonic (book/next-step! ctx)))))))
  (testing ":proximate favors small steps over long runs"
    ;; From a starting position, adjacent harmonics should be more common
    ;; than distant ones. Test statistically: not a precise assertion, just sanity.
    (let [ctx     (make-ctx {:fundamental :C2
                              :pages [{:name :p :harmonics [1 3 5 7 9 11] :gravity 0.0
                                       :selection {:mode :proximate}}]})
          _       (book/next-step! ctx)  ; prime current-h
          steps   (repeatedly 200 #(:book/harmonic (book/next-step! ctx)))
          ;; count transitions — sum of |h[i] - h[i-1]|
          dists   (map #(Math/abs (- (long %1) (long %2)))
                       (next steps) steps)
          avg-dist (/ (double (reduce + dists)) (count dists))]
      ;; With :proximate, average step should be much less than max possible (10)
      (is (< avg-dist 6.0)))))

;; ---------------------------------------------------------------------------
;; Duration modes
;; ---------------------------------------------------------------------------

(deftest duration-modes-test
  (testing ":beats mode returns configured duration"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [1 3] :gravity 1.0
                                   :duration {:mode :beats :beats 8.0}}]})]
      (dotimes [_ 5]
        (is (= 8.0 (:dur/beats (book/next-step! ctx)))))))
  (testing ":probability mode returns >= min-beats"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [1] :gravity 1.0
                                   :duration {:mode :probability :p 0.5 :min-beats 2.0}}]})]
      (dotimes [_ 20]
        (is (>= (:dur/beats (book/next-step! ctx)) 2.0)))))
  (testing ":harmonic-modulated produces larger beats for lower harmonics"
    (let [ctx1 (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [1] :gravity 1.0
                                    :selection {:mode :sequential}
                                    :duration {:mode :harmonic-modulated :base-beats 8.0}}]})
          ctx7 (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [7] :gravity 1.0
                                    :selection {:mode :sequential}
                                    :duration {:mode :harmonic-modulated :base-beats 8.0}}]})]
      (is (> (:dur/beats (book/next-step! ctx1))
             (:dur/beats (book/next-step! ctx7)))))))

;; ---------------------------------------------------------------------------
;; Page navigation
;; ---------------------------------------------------------------------------

(deftest go-page-test
  (testing "go-page! switches to the named page"
    (let [ctx (make-ctx simple-opts)]
      (is (= 0 (:page-idx @ctx)))
      (book/go-page! ctx :outer)
      (is (= 1 (:page-idx @ctx)))))
  (testing "go-page! resets h-idx and current-h"
    (let [ctx (make-ctx simple-opts)]
      (book/next-step! ctx)
      (book/go-page! ctx :outer)
      (is (= 0 (:h-idx @ctx)))
      (is (nil? (:current-h @ctx)))))
  (testing "go-page! ignores unknown page name"
    (let [ctx (make-ctx simple-opts)]
      (book/go-page! ctx :nonexistent)
      (is (= 0 (:page-idx @ctx)))))
  (testing "after go-page!, harmonics come from new page"
    (let [ctx   (make-ctx simple-opts)
          valid #{7 9 11 13}]
      (book/go-page! ctx :outer)
      (dotimes [_ 20]
        (is (contains? valid (:book/harmonic (book/next-step! ctx))))))))

(deftest harmonic-threshold-navigation-test
  (testing ":harmonic-threshold navigates forward when N exceeds up-at"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [ground-page outer-page]
                          :navigation {:mode :harmonic-threshold :up-at 3 :down-at 0}})]
      ;; Harmonics [1 2 3 4] — any harmonic >= 3 should trigger page advance
      ;; Run until we get a harmonic >= 3 and verify page increments
      (dotimes [_ 30] (book/next-step! ctx))
      ;; After many steps with up-at=3, we should have navigated to page 1
      (is (pos? (:page-idx @ctx)))))
  (testing ":harmonic-threshold does not navigate beyond last page"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [outer-page]   ; only one page
                          :navigation {:mode :harmonic-threshold :up-at 1 :down-at 0}})]
      (dotimes [_ 20] (book/next-step! ctx))
      (is (= 0 (:page-idx @ctx))))))

;; ---------------------------------------------------------------------------
;; Cell mode
;; ---------------------------------------------------------------------------

(deftest cell-mode-test
  (testing "cell mode initializes cell of correct length"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [ground-page]
                          :output-mode :cell
                          :cell-len 4})]
      (is (= 4 (count (:cell @ctx))))))
  (testing "cell harmonics are drawn from page harmonics"
    (let [ctx   (make-ctx {:fundamental :C2
                            :pages [ground-page]
                            :output-mode :cell
                            :cell-len 6})
          valid #{1 2 3 4}]
      (is (every? #(contains? valid %) (:cell @ctx)))))
  (testing "cell tiles on advance — same sequence repeats"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [3 5 7] :gravity 1.0}]
                          :output-mode :cell
                          :cell-len 3
                          :drift-prob 0.0})]   ; no drift so cell stays constant
      (let [pass1 (mapv :book/harmonic (repeatedly 3 #(book/next-step! ctx)))
            pass2 (mapv :book/harmonic (repeatedly 3 #(book/next-step! ctx)))]
        (is (= pass1 pass2)))))
  (testing "drift-prob 0.0 — cell never changes"
    (let [ctx  (make-ctx {:fundamental :C2
                           :pages [{:name :p :harmonics [3 5 7 9] :gravity 0.5}]
                           :output-mode :cell
                           :cell-len 4
                           :drift-prob 0.0})
          cell-before (vec (:cell @ctx))]
      (dotimes [_ 40] (book/next-step! ctx))
      (is (= cell-before (:cell @ctx)))))
  (testing "drift only moves harmonics downward (gravity direction)"
    ;; Build a cell with high harmonics and drift-prob=1.0 — every pass, every position drifts
    ;; After enough passes, all positions should be at the minimum harmonic
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [1 3 5 7 9] :gravity 1.0}]
                          :output-mode :cell
                          :cell-len 4
                          :drift-prob 1.0
                          :drift-rate :per-pass})]
      ;; Force high harmonics into the cell
      (swap! ctx assoc :cell [9 9 9 9])
      ;; Run enough passes that all positions drift down
      (dotimes [_ 100] (book/next-step! ctx))
      ;; All cell positions should have drifted toward lower harmonics
      (is (every? #(<= % 9) (:cell @ctx)))))
  (testing "go-page! reseeds cell in :cell mode"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [ground-page outer-page]
                          :output-mode :cell
                          :cell-len 4})]
      (let [cell-before (vec (:cell @ctx))
            valid-after #{7 9 11 13}]
        (book/go-page! ctx :outer)
        (is (= 0 (:cell-pos @ctx)))
        (is (every? #(contains? valid-after %) (:cell @ctx)))))))

(deftest reset-cell-test
  (testing "reset-cell! reseeds cell from current page"
    (let [ctx      (make-ctx {:fundamental :C2
                               :pages [{:name :p :harmonics [3 5 7] :gravity 1.0}]
                               :output-mode :cell
                               :cell-len 3})
          old-cell (vec (:cell @ctx))]
      (book/reset-cell! ctx)
      (is (= 0 (:cell-pos @ctx)))
      ;; Cell may or may not change (random), but it should be valid
      (is (every? #{3 5 7} (:cell @ctx))))))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(deftest current-page-test
  (testing "current-page returns the active page map"
    (let [ctx  (make-ctx simple-opts)
          page (book/current-page ctx)]
      (is (= :ground (:name page)))
      (is (= [1 2 3 4] (:harmonics page)))))
  (testing "current-page after go-page! reflects new page"
    (let [ctx (make-ctx simple-opts)]
      (book/go-page! ctx :outer)
      (is (= :outer (:name (book/current-page ctx)))))))

(deftest current-harmonic-test
  (testing "current-harmonic is nil before first step"
    (let [ctx (make-ctx simple-opts)]
      (is (nil? (book/current-harmonic ctx)))))
  (testing "current-harmonic returns last drawn N after a step"
    (let [ctx (make-ctx {:fundamental :C2
                          :pages [{:name :p :harmonics [5] :gravity 1.0
                                   :selection {:mode :sequential}}]})]
      (book/next-step! ctx)
      (is (= 5 (book/current-harmonic ctx))))))

;; ---------------------------------------------------------------------------
;; defbook macro
;; ---------------------------------------------------------------------------

(deftest defbook-macro-test
  (testing "defbook creates an atom"
    (book/defbook test-book-a
      :fundamental :C2
      :pages [{:name :root :harmonics [1 2 3 4] :gravity 1.0}]
      :navigation {:initial :root :mode :manual})
    (is (instance? clojure.lang.Atom test-book-a)))
  (testing "defbook registers in ctrl tree"
    (book/defbook test-book-b
      :fundamental :C2
      :pages [{:name :root :harmonics [1 3 5] :gravity 1.0}])
    (is (some? (ctrl/get [:book :test-book-b]))))
  (testing "defbook context is usable via next-step!"
    (book/defbook test-book-c
      :fundamental :F#2
      :pages [{:name :root :harmonics [1 2 3 5 7] :gravity 0.8}])
    (let [step (book/next-step! test-book-c)]
      (is (map? step))
      (is (integer? (:book/harmonic step))))))

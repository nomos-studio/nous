; SPDX-License-Identifier: EPL-2.0
(ns nous.lattice-test
  "Unit tests for nous.lattice — deflattice 2D JI lattice sequencer."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [nomos.maths.harmonic :as h]
            [nomos.maths.lattice  :as l]
            [nous.lattice      :as lat]
            [nous.core         :as core]
            [nous.ctrl         :as ctrl]
            [nous.seq          :as sq]))

;; ---------------------------------------------------------------------------
;; Fixtures — needed for deflattice macro (ctrl/defnode! requires system)
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-ctx
  "Build a lattice context atom directly from options."
  [opts]
  (atom (lat/make-lattice-context opts)))

(def ^:private r5
  "Standard test region: 5-limit with tenney ≤ 5.5."
  {:otonal-limit 9 :utonal-limit 9 :tenney-limit 5.5})

(def ^:private r7
  "7-limit region for chord tests."
  {:otonal-limit 11 :utonal-limit 7 :tenney-limit 6.5})

;; ---------------------------------------------------------------------------
;; make-lattice-context — construction and defaults
;; ---------------------------------------------------------------------------

(deftest make-lattice-context-structure-test
  (testing "context has expected keys"
    (let [ctx (lat/make-lattice-context {:region r5})]
      (is (map? ctx))
      (is (number? (:fundamental-hz ctx)))
      (is (some? (:region ctx)))
      (is (vector? (:position ctx)))
      (is (vector? (:attractor ctx)))
      (is (keyword? (:nav-mode ctx)))
      (is (keyword? (:step-bias ctx)))
      (is (number? (:otonality ctx)))
      (is (true? (:octave-fold ctx)))
      (is (nil? (:last-step ctx)))))
  (testing "defaults: nav-mode :gravity, step-bias :proximate, otonality 0.5"
    (let [ctx (lat/make-lattice-context {})]
      (is (= :gravity   (:nav-mode ctx)))
      (is (= :proximate (:step-bias ctx)))
      (is (= 0.5 (:otonality ctx)))))
  (testing "fundamental :F#2 parses to ~92.5 Hz"
    (let [ctx (lat/make-lattice-context {:fundamental :F#2 :region r5})]
      (is (> (:fundamental-hz ctx) 92.0))
      (is (< (:fundamental-hz ctx) 93.0))))
  (testing "explicit :position [3 2] is used when in region"
    (let [ctx (lat/make-lattice-context {:region r5 :position [3 2]})]
      (is (= [3 2] (:position ctx)))))
  (testing "invalid :position falls back to first region point"
    (let [ctx (lat/make-lattice-context {:region r5 :position [99 99]})]
      (is (some? (:position ctx)))
      (is (l/in-region? (:position ctx) (:region ctx)))))
  (testing "explicit :attractor is stored"
    (let [ctx (lat/make-lattice-context {:region r5 :attractor [3 2]})]
      (is (= [3 2] (:attractor ctx)))))
  (testing "default :attractor is [1 1]"
    (let [ctx (lat/make-lattice-context {:region r5})]
      (is (= [1 1] (:attractor ctx))))))

;; ---------------------------------------------------------------------------
;; next-step! — basic advance and output
;; ---------------------------------------------------------------------------

(deftest next-step-returns-step-map-test
  (testing "step map has all required keys"
    (let [ctx  (make-ctx {:region r5 :position [3 2]})
          step (lat/next-step! ctx)]
      (is (map? step))
      (is (number? (:pitch/voct step)))
      (is (integer? (:pitch/midi step)))
      (is (number? (:dur/beats step)))
      (is (true? (:gate/on? step)))
      (is (vector? (:lattice/point step)))
      (is (number? (:lattice/tenney step)))
      (is (number? (:lattice/otonality step)))
      (is (integer? (:lattice/m step)))
      (is (integer? (:lattice/n step)))))
  (testing "first step outputs the starting position"
    (let [ctx  (make-ctx {:region r5 :position [3 2]})
          step (lat/next-step! ctx)]
      (is (= [3 2] (:lattice/point step)))
      (is (= 3 (:lattice/m step)))
      (is (= 2 (:lattice/n step)))))
  (testing "second step reflects navigated position"
    (let [ctx  (make-ctx {:region r5 :position [3 2] :nav-mode :gravity})
          _    (lat/next-step! ctx)
          step (lat/next-step! ctx)]
      ;; After one gravity step from [3 2] toward [1 1], position should have moved
      (is (some? (:lattice/point step)))))
  (testing "position updates after each call"
    (let [ctx    (make-ctx {:region r5 :position [3 2] :nav-mode :gravity})
          before (:position @ctx)
          _      (lat/next-step! ctx)
          after  (:position @ctx)]
      ;; gravity from [3 2] with default attractor [1 1] should move to [1 1]
      (is (not= before after))))
  (testing "Tenney H of [1 1] is 0.0"
    (let [ctx  (make-ctx {:region r5 :position [1 1]})
          step (lat/next-step! ctx)]
      (is (< (:lattice/tenney step) 0.001))))
  (testing "Tenney H of [3 2] is approximately 2.58"
    (let [ctx  (make-ctx {:region r5 :position [3 2]})
          step (lat/next-step! ctx)]
      (is (> (:lattice/tenney step) 2.5))
      (is (< (:lattice/tenney step) 2.7)))))

;; ---------------------------------------------------------------------------
;; Pitch output
;; ---------------------------------------------------------------------------

(deftest pitch-output-test
  (testing "[1 1] → voct 0.0 (fundamental)"
    (let [ctx  (make-ctx {:fundamental :F#2 :region r5 :position [1 1]})
          step (lat/next-step! ctx)]
      (is (< (Math/abs (:pitch/voct step)) 0.001))))
  (testing "[3 2] above C2 → approximately MIDI 55 (G3)"
    ;; C2 ≈ 65.4 Hz, just fifth above C2 ≈ 98.1 Hz ≈ G2 (MIDI 43)
    ;; With octave-fold [3 2] ratio = 3/2 = 1.5, voct = log2(1.5) ≈ 0.585
    ;; C2 MIDI = 36, + 12*0.585 ≈ 36 + 7 = 43 → G2
    (let [ctx  (make-ctx {:fundamental :C2 :region r5 :position [3 2]})
          step (lat/next-step! ctx)]
      (is (= 43 (:pitch/midi step)))))
  (testing "MIDI is within [0, 127]"
    (doseq [pos [[1 1] [3 2] [5 4] [4 3]]]
      (let [ctx  (make-ctx {:fundamental :C4 :region r5 :position pos})
            step (lat/next-step! ctx)]
        (is (<= 0 (:pitch/midi step) 127)))))
  (testing "otonality is in [0, 1]"
    (let [ctx  (make-ctx {:region r5 :position [5 4]})
          step (lat/next-step! ctx)]
      (is (<= 0.0 (:lattice/otonality step) 1.0))))
  (testing "[3 1] has otonality 1.0 (fully otonal)"
    ;; [3 1] has log(3)/log(3) = 1.0
    ;; But [3 1] with octave-fold is normalized to [3 2]... need octave-fold false
    (let [ctx  (make-ctx {:region    {:otonal-limit 3 :utonal-limit 1
                                      :tenney-limit 4.0 :octave-fold false}
                          :position  [3 1]
                          :octave-fold false})
          step (lat/next-step! ctx)]
      (is (> (:lattice/otonality step) 0.99))))
  (testing "origin [1 1] has otonality 0.5 (neutral)"
    (let [ctx  (make-ctx {:region r5 :position [1 1]})
          step (lat/next-step! ctx)]
      (is (< (Math/abs (- 0.5 (:lattice/otonality step))) 0.001)))))

;; ---------------------------------------------------------------------------
;; Navigation — :gravity mode
;; ---------------------------------------------------------------------------

(deftest gravity-navigation-test
  (testing "gravity from [3 2] moves toward [1 1]"
    ;; gravity-steps([3 2], [1 1], r) = {[1 1]} (only [1 1] has lower H)
    (let [ctx (make-ctx {:region r5 :position [3 2] :nav-mode :gravity})]
      (lat/next-step! ctx) ; outputs [3 2], moves to next
      (is (= [1 1] (:position @ctx)))))
  (testing "gravity at attractor stays put"
    ;; From [1 1] with attractor [1 1], gravity-steps returns empty → stay
    (let [ctx (make-ctx {:region r5 :position [1 1] :nav-mode :gravity})]
      (lat/next-step! ctx) ; outputs [1 1], no gravity steps → stays at [1 1]
      (is (= [1 1] (:position @ctx)))))
  (testing "gravity from [5 4] toward [3 2] attractor moves to closer point"
    ;; With attractor [3 2]: gravity-steps([5 4], [3 2], r) = points with lower H from [3 2]
    ;; H([5 4], [3 2]) = H([5 3]) ≈ 3.91; H([3 2], [3 2]) = 0 → included
    (let [ctx (make-ctx {:region   r5 :position [5 4]
                         :attractor [3 2] :nav-mode :gravity
                         :step-bias :proximate})]
      (lat/next-step! ctx) ; outputs [5 4]
      ;; next position should be among gravity candidates toward [3 2]
      (let [pos (:position @ctx)
            h-from-attr (h/tenney-h pos [3 2])]
        (is (< h-from-attr (h/tenney-h [5 4] [3 2])))))))

;; ---------------------------------------------------------------------------
;; Navigation — :expand mode
;; ---------------------------------------------------------------------------

(deftest expand-navigation-test
  (testing "expand from [1 1] moves away from origin"
    (let [ctx (make-ctx {:region r5 :position [1 1] :nav-mode :expand})]
      (lat/next-step! ctx) ; outputs [1 1]
      (let [pos (:position @ctx)]
        (is (> (h/tenney-h pos) 0.0)))))
  (testing "expand from most complex point stays put"
    (let [region (l/lattice-region r5)
          pts    (l/region-points region)
          most-complex (last pts)
          ctx  (make-ctx {:region r5 :position most-complex :nav-mode :expand})]
      (lat/next-step! ctx) ; outputs most-complex, expansion-steps empty → stay
      (is (= most-complex (:position @ctx)))))
  (testing "each expand step has higher H than previous"
    (let [ctx   (make-ctx {:region r5 :position [1 1] :nav-mode :expand
                           :step-bias :proximate})
          steps (repeatedly 5 #(lat/next-step! ctx))
          hs    (map :lattice/tenney steps)]
      ;; H values should be non-decreasing (proximate takes nearest, which is lowest H first)
      (is (= hs (sort hs))))))

;; ---------------------------------------------------------------------------
;; Navigation — :otonal-step and :utonal-step
;; ---------------------------------------------------------------------------

(deftest otonal-step-navigation-test
  (testing "otonal-step cycles through members of the same denominator"
    ;; For n=4 in r7: chord = [[5 4] [7 4]] (otonal-limit=11, utonal-limit=7)
    (let [ctx (make-ctx {:region r7 :position [5 4] :nav-mode :otonal-step})]
      (lat/next-step! ctx) ; outputs [5 4], navigates to next in chord
      (let [pos1 (:position @ctx)]
        (is (= 4 (second pos1))) ; same denominator
        (lat/next-step! ctx)    ; outputs [7 4], wraps back
        (let [pos2 (:position @ctx)]
          (is (= 4 (second pos2))))))) ; still same denominator (wraps to [5 4])
  (testing "otonal-step from single-member chord stays put"
    ;; [3 2] is the only n=2 point in r5 with octave-fold
    (let [ctx (make-ctx {:region r5 :position [3 2] :nav-mode :otonal-step})]
      (lat/next-step! ctx) ; outputs [3 2]
      (is (= [3 2] (:position @ctx))))))

(deftest utonal-step-navigation-test
  (testing "utonal-step cycles through members of the same numerator"
    ;; For m=5 in r5: chord may include [5 3] and [5 4]
    (let [region  (l/lattice-region r5)
          chord-5 (l/utonal-chord 5 region)]
      (when (>= (count chord-5) 2)
        (let [start (first chord-5)
              ctx   (make-ctx {:region r5 :position start :nav-mode :utonal-step})]
          (lat/next-step! ctx) ; outputs start
          (is (= 5 (first (:position @ctx)))))))) ; same numerator
  (testing "utonal-step from single-member chord stays put"
    ;; [4 3] — m=4, the utonal chord on 4 in r5 may be just [[4 3]]
    (let [region  (l/lattice-region r5)
          chord-4 (l/utonal-chord 4 region)]
      (when (= 1 (count chord-4))
        (let [ctx (make-ctx {:region r5 :position [4 3] :nav-mode :utonal-step})]
          (lat/next-step! ctx)
          (is (= [4 3] (:position @ctx))))))))

;; ---------------------------------------------------------------------------
;; Navigation — :random-walk mode
;; ---------------------------------------------------------------------------

(deftest random-walk-navigation-test
  (testing "random-walk always lands in the region"
    (let [ctx    (make-ctx {:region r5 :position [3 2] :nav-mode :random-walk})
          region (:region @ctx)]
      (dotimes [_ 10]
        (lat/next-step! ctx)
        (is (l/in-region? (:position @ctx) region)))))
  (testing "random-walk with otonality=1.0 tends toward otonal positions"
    ;; Run many steps and check average otonality > 0.5
    (let [ctx   (make-ctx {:region r5 :position [1 1]
                           :nav-mode :random-walk :otonality 1.0})
          steps (repeatedly 50 #(lat/next-step! ctx))
          otos  (map :lattice/otonality steps)
          avg   (/ (reduce + otos) (count otos))]
      (is (> avg 0.5)))))

;; ---------------------------------------------------------------------------
;; Duration modes
;; ---------------------------------------------------------------------------

(deftest duration-modes-test
  (testing "numeric duration returns that value in beats"
    (let [ctx  (make-ctx {:region r5 :position [3 2] :duration 8.0})
          step (lat/next-step! ctx)]
      (is (= 8.0 (:dur/beats step)))))
  (testing ":beats mode with :beats value"
    (let [ctx  (make-ctx {:region r5 :position [3 2]
                          :duration {:mode :beats :beats 6.0}})
          step (lat/next-step! ctx)]
      (is (= 6.0 (:dur/beats step)))))
  (testing ":probability mode yields positive duration"
    (let [ctx  (make-ctx {:region r5 :position [3 2]
                          :duration {:mode :probability :p 0.5 :min-beats 1.0}})
          step (lat/next-step! ctx)]
      (is (>= (:dur/beats step) 1.0))))
  (testing ":tenney-modulated :inverse: shorter duration at higher Tenney H"
    ;; Higher H → smaller beats (base / tenney)
    ;; [7 6] has H ≈ 5.39; [3 2] has H ≈ 2.58 → [7 6] should get shorter duration
    (let [dur  {:mode :tenney-modulated :base-beats 8.0 :scale :inverse}
          ctx1 (make-ctx {:region r5 :position [3 2] :duration dur})
          ctx2 (make-ctx {:region r5 :position [7 6] :duration dur})
          b1   (:dur/beats (lat/next-step! ctx1))
          b2   (:dur/beats (lat/next-step! ctx2))]
      (is (> b1 b2)))))

;; ---------------------------------------------------------------------------
;; set-attractor! set-nav-mode! jump!
;; ---------------------------------------------------------------------------

(deftest set-attractor-test
  (testing "set-attractor! changes the stored attractor"
    (let [ctx (make-ctx {:region r5 :position [5 4]})]
      (lat/set-attractor! ctx [3 2])
      (is (= [3 2] (lat/current-attractor ctx)))))
  (testing "set-attractor! returns nil"
    (let [ctx (make-ctx {:region r5 :position [5 4]})]
      (is (nil? (lat/set-attractor! ctx [3 2])))))
  (testing "gravity toward new attractor pulls in that direction"
    (let [ctx (make-ctx {:region r5 :position [5 4]
                         :nav-mode :gravity :step-bias :proximate})]
      (lat/set-attractor! ctx [3 2])
      (lat/next-step! ctx) ; outputs [5 4], navigates toward [3 2]
      (let [pos      (:position @ctx)
            h-new    (h/tenney-h pos [3 2])
            h-before (h/tenney-h [5 4] [3 2])]
        (is (< h-new h-before))))))

(deftest set-nav-mode-test
  (testing "set-nav-mode! changes the stored mode"
    (let [ctx (make-ctx {:region r5 :position [1 1]})]
      (lat/set-nav-mode! ctx :expand)
      (is (= :expand (:nav-mode @ctx)))))
  (testing "set-nav-mode! returns nil"
    (let [ctx (make-ctx {:region r5 :position [1 1]})]
      (is (nil? (lat/set-nav-mode! ctx :random-walk))))))

(deftest jump-test
  (testing "jump! to a valid region point changes position immediately"
    (let [ctx (make-ctx {:region r5 :position [1 1]})]
      (lat/jump! ctx [3 2])
      (is (= [3 2] (lat/current-position ctx)))))
  (testing "jump! to a point not in region is a no-op"
    (let [ctx (make-ctx {:region r5 :position [1 1]})]
      (lat/jump! ctx [99 99])
      (is (= [1 1] (lat/current-position ctx)))))
  (testing "jump! returns nil"
    (let [ctx (make-ctx {:region r5 :position [1 1]})]
      (is (nil? (lat/jump! ctx [3 2]))))))

;; ---------------------------------------------------------------------------
;; deflattice macro
;; ---------------------------------------------------------------------------

(deftest deflattice-macro-test
  (testing "deflattice creates a var bound to an atom"
    (lat/deflattice test-space
      :fundamental :F#2
      :region r5
      :position [3 2])
    (is (instance? clojure.lang.IAtom test-space))
    (is (map? @test-space)))
  (testing "deflattice registers in registry"
    (lat/deflattice registry-space
      :fundamental :C4
      :region r5)
    (is (some #{:registry-space} (lat/lattice-names))))
  (testing "deflattice registers in ctrl tree"
    (lat/deflattice ctrl-space
      :fundamental :C4
      :region r5)
    (is (some? (ctrl/node-info [:lattice :ctrl-space])))))

;; ---------------------------------------------------------------------------
;; IStepSequencer wrapping
;; ---------------------------------------------------------------------------

(deftest make-lattice-seq-test
  (testing "make-lattice-seq returns a LatticeSeq"
    (let [ctx (make-ctx {:region r5 :position [3 2]})
          lq  (lat/make-lattice-seq ctx)]
      (is (instance? nous.lattice.LatticeSeq lq))))
  (testing "next-event returns {:event map :beats n}"
    (let [ctx (make-ctx {:region r5 :position [3 2]})
          lq  (lat/make-lattice-seq ctx)
          ev  (sq/next-event lq)]
      (is (map? ev))
      (is (contains? ev :event))
      (is (number? (:beats ev)))))
  (testing "next-event :event contains :mod/velocity"
    (let [ctx (make-ctx {:region r5 :position [3 2]})
          lq  (lat/make-lattice-seq ctx :vel 80)
          ev  (sq/next-event lq)]
      (is (= 80 (get-in ev [:event :mod/velocity])))))
  (testing "seq-cycle-length returns nil (infinite)"
    (let [ctx (make-ctx {:region r5 :position [3 2]})
          lq  (lat/make-lattice-seq ctx)]
      (is (nil? (sq/seq-cycle-length lq)))))
  (testing "next-event :gate/on? is always true"
    (let [ctx   (make-ctx {:region r5 :position [3 2]})
          lq    (lat/make-lattice-seq ctx)
          steps (repeatedly 10 #(sq/next-event lq))]
      (is (every? #(true? (get-in % [:event :gate/on?])) steps)))))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(deftest inspection-test
  (testing "current-position returns the current [m n]"
    (let [ctx (make-ctx {:region r5 :position [5 4]})]
      (is (= [5 4] (lat/current-position ctx)))))
  (testing "current-attractor returns the current attractor"
    (let [ctx (make-ctx {:region r5 :attractor [3 2]})]
      (is (= [3 2] (lat/current-attractor ctx)))))
  (testing "lattice-region returns a LatticeRegion"
    (let [ctx (make-ctx {:region r5})]
      (is (instance? nomos.maths.lattice.LatticeRegion (lat/lattice-region ctx)))))
  (testing "lattice-names returns a seq"
    (is (seqable? (lat/lattice-names)))))

;; ---------------------------------------------------------------------------
;; LatticeSeq :mods — Tenney-H-normalized modulation
;; ---------------------------------------------------------------------------

(deftest lattice-seq-mods-no-mods-uses-default-vel-test
  (testing "without :mods the default vel is used unchanged"
    (let [ctx (make-ctx {:region r5 :position [1 1]})
          ls  (lat/make-lattice-seq ctx :vel 88)]
      (is (= 88 (get-in (sq/next-event ls) [:event :mod/velocity]))))))

(deftest lattice-seq-mods-tenney-limit-extracted-from-opts-test
  (testing "tenney-limit is read from region opts at construction"
    (let [ctx (make-ctx {:region {:otonal-limit 11 :utonal-limit 7 :tenney-limit 8.0}
                         :position [1 1]})
          ls  (lat/make-lattice-seq ctx)]
      (is (= 8.0 (:tenney-limit ls))))))

(deftest lattice-seq-mods-tonic-phase-zero-test
  (testing ":mods applied at Tenney-phase=0.0 for tonic position [1,1]"
    ;; [1,1] has Tenney H = log2(1) = 0.0 → mod-phase = 0.0.
    ;; step/hold [0.25 0.75]: 2 segments × 0.5 wide; phase 0.0 → segment 0 → value 0.25.
    ;; velocity = round(0.25 × 127) = 32.
    (let [ctx (make-ctx {:region r5 :position [1 1]})
          ls  (lat/make-lattice-seq ctx
                                    :vel 100
                                    :mods {:mod/velocity {:modulator/type :step/hold
                                                          :step/values [0.25 0.75]}})]
      (is (= 32 (get-in (sq/next-event ls) [:event :mod/velocity]))))))

(deftest lattice-seq-mods-higher-tenney-higher-phase-test
  (testing "higher Tenney H → higher mod-phase → higher step/hold step"
    ;; [1,1]: H=0, phase=0.0 → segment 0 of [0.0 0.33 0.67 1.0] → vel round(0×127)=0
    ;; [3,2]: H=log2(6)≈2.58, r5 tenney-limit=5.5, phase≈0.47 → segment 1 → vel round(0.33×127)=42
    ;; So vel([3,2]) > vel([1,1]).
    (let [mods {:mod/velocity {:modulator/type :step/hold :step/values [0.0 0.33 0.67 1.0]}}
          ls-tonic (lat/make-lattice-seq (make-ctx {:region r5 :position [1 1]}) :mods mods)
          ls-fifth (lat/make-lattice-seq (make-ctx {:region r5 :position [3 2]}) :mods mods)]
      (let [vel-tonic (get-in (sq/next-event ls-tonic) [:event :mod/velocity])
            vel-fifth (get-in (sq/next-event ls-fifth) [:event :mod/velocity])]
        (is (< vel-tonic vel-fifth)
            "velocity at [3,2] (H≈2.58) > velocity at [1,1] (H=0)")))))

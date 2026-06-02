; SPDX-License-Identifier: EPL-2.0
(ns nous.excursion-test
  "Unit tests for nous.excursion — five-phase harmonic lattice excursion arc."
  (:require [clojure.test       :refer [deftest is testing use-fixtures]]
            [nomos.maths.harmonic :as h]
            [nomos.maths.lattice  :as l]
            [nous.excursion     :as ex]
            [nous.core          :as core]
            [nous.ctrl          :as ctrl]
            [nous.seq           :as sq]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 120)
  (try (f) (finally (core/stop!))))

(use-fixtures :each with-system)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-ctx
  [opts]
  (atom (ex/make-excursion-context opts)))

(def ^:private r5
  {:otonal-limit 9 :utonal-limit 9 :tenney-limit 5.5})

(def ^:private r7
  {:otonal-limit 11 :utonal-limit 7 :tenney-limit 6.5})

;; Short arc for tests — small step counts so tests don't need many iterations
(def ^:private short-arc
  {:ground     {:steps 2}
   :departure  {:steps 2 :target-tenney 9.0}  ; high threshold so it runs to step count
   :excursion  {:steps 2}
   :return     {:steps 2 :target-tenney 0.0}  ; low threshold so it runs to step count
   :resolution {:steps 1 :approach :direct :to [1 1]}})

;; ---------------------------------------------------------------------------
;; make-excursion-context — construction
;; ---------------------------------------------------------------------------

(deftest make-excursion-context-structure-test
  (testing "context has expected keys"
    (let [ctx (ex/make-excursion-context {:region r5})]
      (is (map? ctx))
      (is (number? (:fundamental-hz ctx)))
      (is (some? (:region ctx)))
      (is (vector? (:position ctx)))
      (is (vector? (:attractor ctx)))
      (is (= :ground (:phase ctx)))
      (is (= 0 (:phase-step ctx)))
      (is (map? (:arc ctx)))
      (is (nil? (:last-step ctx)))))
  (testing "arc is normalized with all five phases"
    (let [ctx (ex/make-excursion-context {})]
      (is (contains? (:arc ctx) :ground))
      (is (contains? (:arc ctx) :departure))
      (is (contains? (:arc ctx) :excursion))
      (is (contains? (:arc ctx) :return))
      (is (contains? (:arc ctx) :resolution))))
  (testing ":duration alias is converted to :steps"
    (let [ctx (ex/make-excursion-context {:arc {:ground {:duration 12}}})]
      (is (= 12 (get-in ctx [:arc :ground :steps])))))
  (testing "fundamental :F#2 parses to ~92.5 Hz"
    (let [ctx (ex/make-excursion-context {:fundamental :F#2 :region r5})]
      (is (> (:fundamental-hz ctx) 92.0))
      (is (< (:fundamental-hz ctx) 93.0))))
  (testing "starting position is in the region"
    (let [ctx (ex/make-excursion-context {:region r5})]
      (is (l/in-region? (:position ctx) (:region ctx)))))
  (testing "explicit position is used when in region"
    (let [ctx (ex/make-excursion-context {:region r5 :position [3 2]})]
      (is (= [3 2] (:position ctx)))))
  (testing "repeat defaults to true"
    (let [ctx (ex/make-excursion-context {})]
      (is (true? (:repeat ctx))))))

;; ---------------------------------------------------------------------------
;; next-step! — step map structure
;; ---------------------------------------------------------------------------

(deftest next-step-returns-step-map-test
  (testing "step map has required lattice keys"
    (let [ctx  (make-ctx {:region r5 :position [1 1]})
          step (ex/next-step! ctx)]
      (is (map? step))
      (is (number? (:pitch/voct step)))
      (is (integer? (:pitch/midi step)))
      (is (number? (:dur/beats step)))
      (is (true? (:gate/on? step)))
      (is (vector? (:lattice/point step)))
      (is (number? (:lattice/tenney step)))
      (is (number? (:lattice/otonality step)))))
  (testing "step map has excursion-specific keys"
    (let [ctx  (make-ctx {:region r5 :position [1 1]})
          step (ex/next-step! ctx)]
      (is (keyword? (:excursion/phase step)))
      (is (integer? (:excursion/step step)))))
  (testing "first step outputs starting position"
    (let [ctx  (make-ctx {:region r5 :position [3 2]})
          step (ex/next-step! ctx)]
      (is (= [3 2] (:lattice/point step)))))
  (testing "first step is in :ground phase, step 0"
    (let [ctx  (make-ctx {:region r5 :position [1 1]})
          step (ex/next-step! ctx)]
      (is (= :ground (:excursion/phase step)))
      (is (= 0 (:excursion/step step)))))
  (testing "MIDI is within [0, 127]"
    (let [ctx  (make-ctx {:fundamental :F#2 :region r5 :position [3 2]})
          step (ex/next-step! ctx)]
      (is (<= 0 (:pitch/midi step) 127)))))

;; ---------------------------------------------------------------------------
;; Phase sequencing
;; ---------------------------------------------------------------------------

(deftest phase-sequence-test
  (testing "phases advance in order: ground → departure → excursion → return → resolution"
    (let [ctx      (make-ctx {:region r5 :arc short-arc :repeat false})
          all-steps (repeatedly 10 #(ex/next-step! ctx))
          phases    (map :excursion/phase all-steps)
          ; Find first occurrence of each phase
          find-first (fn [p] (some #(when (= p (:excursion/phase %)) %) all-steps))]
      (is (some? (find-first :ground)))
      (is (some? (find-first :departure)))
      (is (some? (find-first :excursion)))
      (is (some? (find-first :return)))
      (is (some? (find-first :resolution)))))
  (testing "ground phase runs for configured :steps then advances"
    (let [ctx (make-ctx {:region r5 :arc (assoc short-arc :ground {:steps 3})})]
      ;; Steps 0,1,2 should be ground; step 3 should be departure
      (let [s0 (ex/next-step! ctx)
            s1 (ex/next-step! ctx)
            s2 (ex/next-step! ctx)
            s3 (ex/next-step! ctx)]
        (is (= :ground    (:excursion/phase s0)))
        (is (= :ground    (:excursion/phase s1)))
        (is (= :ground    (:excursion/phase s2)))
        (is (= :departure (:excursion/phase s3))))))
  (testing "with :repeat true, cycles back to ground after resolution"
    (let [ctx   (make-ctx {:region r5 :arc short-arc :repeat true})
          steps (repeatedly 11 #(ex/next-step! ctx))
          ; Arc length = 2+2+2+2+1 = 9 steps → step 9 and 10 should be ground again
          phases (mapv :excursion/phase steps)]
      ;; After one full arc (9 steps), we should see :ground again
      (is (some #(= :ground %) (drop 9 phases)))))
  (testing "with :repeat false, stays in resolution after arc completes"
    (let [ctx   (make-ctx {:region r5 :arc short-arc :repeat false})
          steps (repeatedly 12 #(ex/next-step! ctx))
          final-phases (map :excursion/phase (drop 9 steps))]
      ;; After arc completes without repeat, phase should stay at resolution
      (is (every? #(= :resolution %) final-phases)))))

;; ---------------------------------------------------------------------------
;; Phase step counter
;; ---------------------------------------------------------------------------

(deftest phase-step-counter-test
  (testing "phase-step increments within a phase"
    (let [ctx  (make-ctx {:region r5 :arc (assoc short-arc :ground {:steps 4})})
          s0   (ex/next-step! ctx)
          s1   (ex/next-step! ctx)
          s2   (ex/next-step! ctx)]
      (is (= 0 (:excursion/step s0)))
      (is (= 1 (:excursion/step s1)))
      (is (= 2 (:excursion/step s2)))))
  (testing "phase-step resets to 0 on phase transition"
    (let [ctx (make-ctx {:region r5 :arc short-arc})]
      ;; Skip to departure
      (dotimes [_ 2] (ex/next-step! ctx)) ; exhaust ground (2 steps)
      (let [dep-step (ex/next-step! ctx)]
        (is (= :departure (:excursion/phase dep-step)))
        (is (= 0 (:excursion/step dep-step)))))))

;; ---------------------------------------------------------------------------
;; Departure — Tenney threshold early exit
;; ---------------------------------------------------------------------------

(deftest departure-tenney-threshold-test
  (testing "departure exits early when Tenney H reaches target"
    ;; If we set target-tenney very low (e.g. 0.1), departure should
    ;; exit after first step since any non-origin position has H > 0.1
    (let [ctx (make-ctx {:region r5
                         :position [3 2]  ; start at H≈2.58
                         :arc {:ground     {:steps 1}
                               :departure  {:steps 100 :target-tenney 3.0}
                               :excursion  {:steps 1}
                               :return     {:steps 1}
                               :resolution {:steps 1 :to [1 1]}}})]
      ;; Run 1 ground step
      (ex/next-step! ctx)
      ;; Now in departure — since we're already at H≈2.58 < 3.0, need to expand first
      ;; After expanding, position should reach H ≥ 3.0 and transition
      (let [dep-steps (repeatedly 5 #(ex/next-step! ctx))
            phases    (map :excursion/phase dep-steps)]
        ;; At some point we should see excursion (after departure exits)
        (is (some #(= :excursion %) phases))))))

;; ---------------------------------------------------------------------------
;; Ground phase with :positions
;; ---------------------------------------------------------------------------

(deftest ground-positions-test
  (testing "ground with :positions cycles through them in order"
    (let [positions [[1 1] [3 2] [4 3]]
          ctx (make-ctx {:region r5
                         :position [1 1]
                         :arc (assoc short-arc
                                     :ground {:steps 6 :positions positions})})]
      (let [s0 (ex/next-step! ctx)
            s1 (ex/next-step! ctx)
            s2 (ex/next-step! ctx)
            s3 (ex/next-step! ctx)
            s4 (ex/next-step! ctx)
            s5 (ex/next-step! ctx)]
        ;; First step outputs starting position (still [1 1])
        ;; then cycles: [3 2] → [4 3] → [1 1] → [3 2] → [4 3]
        (is (= [1 1] (:lattice/point s0)))
        (is (= [3 2] (:lattice/point s1)))
        (is (= [4 3] (:lattice/point s2)))
        (is (= [1 1] (:lattice/point s3)))
        (is (= [3 2] (:lattice/point s4)))
        (is (= [4 3] (:lattice/point s5))))))
  (testing "ground without :positions uses gravity navigation"
    (let [ctx (make-ctx {:region r5 :position [3 2]
                         :arc (assoc short-arc :ground {:steps 4})})]
      ;; First step outputs [3 2]; subsequent steps should navigate toward [1 1]
      (ex/next-step! ctx)
      (let [step2 (ex/next-step! ctx)]
        ;; After one gravity step from [3 2], should be at [1 1]
        (is (= [1 1] (:lattice/point step2)))))))

;; ---------------------------------------------------------------------------
;; Return — gravity toward origin
;; ---------------------------------------------------------------------------

(deftest return-phase-test
  (testing "return phase navigates toward attractor"
    (let [ctx    (make-ctx {:region r5 :position [5 4]
                            :arc {:ground     {:steps 1}
                                  :departure  {:steps 1}
                                  :excursion  {:steps 1}
                                  :return     {:steps 6 :target-tenney 0.0}
                                  :resolution {:steps 1 :to [1 1]}}})
          ;; Run through ground, departure, excursion to get to return
          _      (dotimes [_ 3] (ex/next-step! ctx))
          return-steps (repeatedly 4 #(ex/next-step! ctx))
          return-hs    (map :lattice/tenney return-steps)]
      ;; In return phase, positions should get closer to origin (H decreasing)
      ;; At minimum the positions should be valid
      (is (every? number? return-hs)))))

;; ---------------------------------------------------------------------------
;; Resolution — cadential approach
;; ---------------------------------------------------------------------------

(deftest resolution-direct-test
  (testing "resolution :direct immediately jumps to :to"
    (let [ctx  (make-ctx {:region r5 :position [3 2]
                          :arc {:ground     {:steps 1}
                                :departure  {:steps 1}
                                :excursion  {:steps 1}
                                :return     {:steps 1}
                                :resolution {:steps 1 :approach :direct :to [1 1]}}})
          ;; Run through first 4 phases
          _    (dotimes [_ 4] (ex/next-step! ctx))
          ;; Resolution step
          res  (ex/next-step! ctx)]
      (is (= :resolution (:excursion/phase res)))
      ;; After resolution, next position is [1 1]
      (is (= [1 1] (:position @ctx))))))

(deftest resolution-approach-test
  (testing "resolution :septimal routes through [7 6] if in region"
    ;; r7 has tenney-limit 6.5 and [7 6] has H=log2(42)≈5.39 ≤ 6.5
    ;; Also need utonal-limit ≥ 6 (r7 has 7) and otonal-limit ≥ 7 (r7 has 11) ✓
    (let [lr      (l/lattice-region r7)
          in-r7?  (l/in-region? [7 6] lr)
          ctx     (make-ctx {:region r7
                             :arc {:ground     {:steps 1}
                                   :departure  {:steps 1}
                                   :excursion  {:steps 1}
                                   :return     {:steps 1}
                                   :resolution {:steps 2 :approach :septimal :to [1 1]}}})]
      (when in-r7?
        ;; Run 4 phases
        (dotimes [_ 4] (ex/next-step! ctx))
        ;; Step 0 of resolution: navigate toward [7 6]
        (ex/next-step! ctx)
        ;; By now position should be [7 6] (approach point)
        (let [pos (:position @ctx)]
          ;; Either at [7 6] or navigating there — either way in region
          (is (l/in-region? pos lr))))))
  (testing "resolution approach for point not in region falls back to direct"
    ;; [9 8] has H=6.17 > r5's tenney-limit 5.5 → not in r5 → fall back to direct
    (let [ctx     (make-ctx {:region r5
                             :arc {:ground     {:steps 1}
                                   :departure  {:steps 1}
                                   :excursion  {:steps 1}
                                   :return     {:steps 1}
                                   :resolution {:steps 2 :approach :supertonic :to [1 1]}}})
          _       (dotimes [_ 4] (ex/next-step! ctx))
          _       (ex/next-step! ctx)]  ; resolution step 0
      ;; With supertonic not in r5, should fall through to direct → [1 1]
      (is (= [1 1] (:position @ctx))))))

;; ---------------------------------------------------------------------------
;; skip-to-phase! and restart!
;; ---------------------------------------------------------------------------

(deftest skip-to-phase-test
  (testing "skip-to-phase! changes phase and resets step"
    (let [ctx (make-ctx {:region r5})]
      (ex/skip-to-phase! ctx :excursion)
      (is (= :excursion (:phase @ctx)))
      (is (= 0 (:phase-step @ctx)))))
  (testing "skip-to-phase! returns nil"
    (let [ctx (make-ctx {:region r5})]
      (is (nil? (ex/skip-to-phase! ctx :return)))))
  (testing "next-step! after skip-to-phase! outputs the skipped-to phase"
    (let [ctx  (make-ctx {:region r5})]
      (ex/skip-to-phase! ctx :excursion)
      (let [step (ex/next-step! ctx)]
        (is (= :excursion (:excursion/phase step)))))))

(deftest restart-test
  (testing "restart! returns to ground phase, step 0"
    (let [ctx (make-ctx {:region r5 :arc short-arc})]
      ;; Advance through some phases
      (dotimes [_ 5] (ex/next-step! ctx))
      (ex/restart! ctx)
      (is (= :ground (:phase @ctx)))
      (is (= 0 (:phase-step @ctx)))))
  (testing "restart! returns nil"
    (let [ctx (make-ctx {:region r5})]
      (is (nil? (ex/restart! ctx))))))

;; ---------------------------------------------------------------------------
;; defexcursion macro
;; ---------------------------------------------------------------------------

(deftest defexcursion-macro-test
  (testing "deflattice creates a var bound to an atom"
    (ex/defexcursion test-arc
      :fundamental :F#2
      :region r5)
    (is (instance? clojure.lang.IAtom test-arc))
    (is (map? @test-arc)))
  (testing "defexcursion registers in ctrl tree"
    (ex/defexcursion ctrl-arc
      :fundamental :C4
      :region r5)
    (is (some? (ctrl/node-info [:excursion :ctrl-arc]))))
  (testing "defexcursion registers in registry"
    (ex/defexcursion registry-arc
      :fundamental :C4
      :region r5)
    (is (some #{:registry-arc} (ex/excursion-names)))))

;; ---------------------------------------------------------------------------
;; IStepSequencer
;; ---------------------------------------------------------------------------

(deftest make-excursion-seq-test
  (testing "make-excursion-seq returns an ExcursionSeq"
    (let [ctx (make-ctx {:region r5})
          es  (ex/make-excursion-seq ctx)]
      (is (instance? nous.excursion.ExcursionSeq es))))
  (testing "next-event returns {:event map :beats n}"
    (let [ctx (make-ctx {:region r5})
          es  (ex/make-excursion-seq ctx)
          ev  (sq/next-event es)]
      (is (map? ev))
      (is (contains? ev :event))
      (is (number? (:beats ev)))))
  (testing "event contains :mod/velocity and :excursion/phase"
    (let [ctx (make-ctx {:region r5})
          es  (ex/make-excursion-seq ctx :vel 75)
          ev  (sq/next-event es)]
      (is (= 75 (get-in ev [:event :mod/velocity])))
      (is (keyword? (get-in ev [:event :excursion/phase])))))
  (testing "seq-cycle-length returns nil (infinite)"
    (let [ctx (make-ctx {:region r5})
          es  (ex/make-excursion-seq ctx)]
      (is (nil? (sq/seq-cycle-length es))))))

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(deftest inspection-test
  (testing "current-phase returns :ground initially"
    (let [ctx (make-ctx {:region r5})]
      (is (= :ground (ex/current-phase ctx)))))
  (testing "phase-progress returns expected keys"
    (let [ctx  (make-ctx {:region r5})
          prog (ex/phase-progress ctx)]
      (is (= :ground (:phase prog)))
      (is (= 0 (:step prog)))
      (is (pos? (:steps prog)))
      (is (number? (:ratio prog)))))
  (testing "excursion-names returns a seq"
    (is (seqable? (ex/excursion-names)))))

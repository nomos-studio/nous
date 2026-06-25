; SPDX-License-Identifier: EPL-2.0
(ns nous.arp-test
  "Unit tests for nous.arp — pattern registry, playback, and step engine."
  (:require [clojure.test  :refer [deftest is testing]]
            [nous.arp    :as arp]
            [nous.seq    :as sq]
            [nous.core   :as core]))

;; ---------------------------------------------------------------------------
;; Pattern registry
;; ---------------------------------------------------------------------------

(deftest built-in-patterns-loaded-test
  (testing "built-in chord patterns are registered at load time"
    (doseq [kw [:up :down :bounce :alberti :waltz-bass
                :broken-triad :guitar-pick :jazz-stride
                :montuno :raga-alap :euclid-5-8]]
      (is (some? (arp/get-pattern kw))
          (str "expected pattern " kw " to be registered"))))
  (testing ":alberti has correct :order and :type"
    (let [p (arp/get-pattern :alberti)]
      (is (= :chord (:type p)))
      (is (= [0 2 1 2] (:order p))))))

(deftest hydrasynth-phrases-loaded-test
  (testing "all 64 Hydrasynth phrases are registered"
    (doseq [n (range 1 65)]
      (let [kw (keyword (format "phrase-%02d" n))]
        (is (some? (arp/get-pattern kw))
            (str "expected " kw " to be registered")))))
  (testing ":phrase-01 has :steps with semitone data"
    (let [p (arp/get-pattern :phrase-01)]
      (is (= :phrase (:type p)))
      (is (seq (:steps p)))
      (is (every? #(or (:semi %) (:rest %)) (:steps p))))))

(deftest register-custom-pattern-test
  (testing "register! adds a pattern retrievable by get-pattern"
    (let [kw :test-custom-pattern]
      (arp/register! kw {:type :chord :order [0 1 2] :rhythm [1 1 1] :dur 3/4})
      (is (= [0 1 2] (:order (arp/get-pattern kw)))))))

(deftest ls-returns-sorted-list-test
  (testing "ls returns a sorted sequence of [keyword description] pairs"
    (let [result (arp/ls)]
      (is (seq result))
      (is (every? (fn [[k d]] (and (keyword? k) (string? d))) result))
      ;; should be sorted by keyword
      (is (= result (sort-by first result))))))

;; ---------------------------------------------------------------------------
;; Playback — chord patterns
;; ---------------------------------------------------------------------------

(deftest play-chord-pattern-note-count-test
  (testing "play! :alberti against a triad plays 4 notes"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj note) nil)
                    nous.loop/sleep! (fn [_] nil)]
        (arp/play! :alberti [60 64 67]))
      (is (= 4 (count @notes))))))

(deftest play-chord-pattern-pitch-mapping-test
  (testing "play! :alberti [0 2 1 2] on C major gives root-fifth-third-fifth"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    nous.loop/sleep! (fn [_] nil)]
        (arp/play! :alberti [60 64 67]))
      ;; order [0 2 1 2] → chord[0]=60, chord[2]=67, chord[1]=64, chord[2]=67
      (is (= [60 67 64 67] @notes)))))

(deftest play-chord-wraps-octave-test
  (testing "order index beyond chord size wraps up an octave"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    nous.loop/sleep! (fn [_] nil)]
        ;; :up with 4-note chord in pattern with order [0 1 2 3 4]
        (arp/play! {:type :chord :order [0 1 2 3 4] :rhythm [1 1 1 1 1] :dur 3/4}
                   [60 64 67]))
      ;; chord has 3 notes; index 3 wraps → chord[0]+12=72; index 4 → chord[1]+12=76
      (is (= [60 64 67 72 76] @notes)))))

;; ---------------------------------------------------------------------------
;; Playback — phrase patterns
;; ---------------------------------------------------------------------------

(deftest play-phrase-root-offset-test
  (testing "play! :phrase-01 with root=60 transposes semitones from 60"
    (let [notes (atom [])]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    nous.loop/sleep! (fn [_] nil)]
        (arp/play! :phrase-01 {:root 60}))
      (let [p     (arp/get-pattern :phrase-01)
            steps (filter #(not (:rest %)) (:steps p))
            expected (map #(+ 60 (:semi %)) steps)]
        (is (= (vec expected) @notes))))))

(deftest play-phrase-rest-steps-skipped-test
  (testing "rest steps are not played (no pitch/midi in play! call)"
    (let [notes (atom [])
          test-pattern {:type  :phrase
                        :steps [{:semi 0 :beats 1}
                                {:rest true :beats 1}
                                {:semi 7 :beats 1}]}]
      (with-redefs [core/play!  (fn [note & _] (swap! notes conj (:pitch/midi note)) nil)
                    nous.loop/sleep! (fn [_] nil)]
        (arp/play! test-pattern {:root 60}))
      ;; Only notes at offset 0 and 7 from root should have been played
      (is (= [60 67] @notes)))))

(deftest play-phrase-rate-test
  (testing ":rate 2.0 doubles all step durations"
    (let [sleeps (atom [])]
      (with-redefs [core/play!  (fn [_ & _] nil)
                    nous.loop/sleep! (fn [b] (swap! sleeps conj b) nil)]
        (arp/play! {:type  :phrase
                    :steps [{:semi 0 :beats 1}
                            {:semi 4 :beats 1}]}
                   {:root 60}
                   :rate 2.0))
      (is (= [2.0 2.0] @sleeps)))))

(deftest play-phrase-dur-gate-test
  (testing ":dur on a phrase pattern controls the gate fraction"
    (let [gates (atom [])]
      (with-redefs [core/play!         (fn [note & _] (swap! gates conj (:dur/beats note)) nil)
                    nous.loop/sleep! (fn [_] nil)]
        (arp/play! {:type  :phrase
                    :dur   1/2
                    :steps [{:semi 0 :beats 1}
                            {:semi 4 :beats 2}]}
                   {:root 60}))
      ;; gate = beats × :dur (1/2): 1×1/2=0.5, 2×1/2=1.0
      (is (= [0.5 1.0] (mapv double @gates))))))

;; ---------------------------------------------------------------------------
;; seq-loop! / stop-seq! via ArpState
;; ---------------------------------------------------------------------------

(deftest seq-loop-and-stop-test
  (testing "seq-loop! returns a stoppable handle; stop-seq! sets :running? false"
    (with-redefs [core/play!         (fn [_ & _] nil)
                  nous.loop/sleep! (fn [_] (Thread/sleep 5) nil)]
      (let [state  (arp/make-arp-state :alberti [60 64 67])
            handle (sq/seq-loop! state)]
        (is (map? handle))
        (is (true? @(:running? handle)))
        (is (future? (:future handle)))
        (sq/stop-seq! handle)
        (is (false? @(:running? handle)))
        (is (future-cancelled? (:future handle)))))))

;; ---------------------------------------------------------------------------
;; next-event stateful engine (IStepSequencer)
;; ---------------------------------------------------------------------------

(deftest next-event-chord-advances-test
  (testing "next-event cycles through :alberti order"
    (let [state (arp/make-arp-state :alberti [60 64 67])]
      ;; [0 2 1 2] → 60, 67, 64, 67, then wraps
      (is (= 60 (get-in (sq/next-event state) [:event :pitch/midi])))
      (is (= 67 (get-in (sq/next-event state) [:event :pitch/midi])))
      (is (= 64 (get-in (sq/next-event state) [:event :pitch/midi])))
      (is (= 67 (get-in (sq/next-event state) [:event :pitch/midi])))
      ;; wraps back
      (is (= 60 (get-in (sq/next-event state) [:event :pitch/midi]))))))

(deftest next-event-uses-mod-velocity-test
  (testing "next-event returns :mod/velocity not :vel"
    (let [state (arp/make-arp-state :alberti [60 64 67] :vel 90)
          ev    (:event (sq/next-event state))]
      (is (= 90 (:mod/velocity ev)))
      (is (nil? (:vel ev))))))

(deftest next-event-phrase-advances-test
  (testing "next-event cycles through phrase steps"
    (let [state (arp/make-arp-state :phrase-01 {:root 60})]
      (let [p     (arp/get-pattern :phrase-01)
            steps (:steps p)
            n     (count steps)]
        ;; Advance through all steps; verify no exceptions and wrap-around
        (dotimes [_ (* 2 n)]
          (let [result (sq/next-event state)]
            (is (map? result))
            (is (number? (:beats result)))))))))

(deftest next-event-rest-returns-nil-event-test
  (testing "next-event on a rest step returns {:event nil :beats N}"
    (let [state (arp/make-arp-state
                  {:type  :phrase
                   :steps [{:rest true :beats 1}
                            {:semi 7 :beats 1}]}
                  {:root 60})]
      (let [rest-result (sq/next-event state)]
        (is (nil? (:event rest-result)))
        (is (= 1.0 (double (:beats rest-result)))))
      (let [note-result (sq/next-event state)]
        (is (= 67 (get-in note-result [:event :pitch/midi])))))))

(deftest next-event-phrase-parameter-locks-test
  (testing "extra keys in phrase step are merged into the event map"
    (let [state (arp/make-arp-state
                  {:type  :phrase
                   :steps [{:semi 0 :beats 1 :mod/cutoff 127}
                            {:semi 4 :beats 1}]}
                  {:root 60})]
      (let [locked-ev (:event (sq/next-event state))
            plain-ev  (:event (sq/next-event state))]
        (is (= 127 (:mod/cutoff locked-ev)))
        (is (nil? (:mod/cutoff plain-ev)))))))

(deftest next-event-chord-parameter-locks-test
  (testing ":params vector on chord pattern merges locks per step"
    (let [state (arp/make-arp-state
                  {:type   :chord
                   :order  [0 1 2]
                   :rhythm [1 1 1]
                   :dur    3/4
                   :params [{} {:mod/cutoff 64} {}]}
                  [60 64 67])]
      (let [ev0 (:event (sq/next-event state))
            ev1 (:event (sq/next-event state))
            ev2 (:event (sq/next-event state))]
        (is (nil? (:mod/cutoff ev0)))
        (is (= 64 (:mod/cutoff ev1)))
        (is (nil? (:mod/cutoff ev2)))))))

(deftest seq-cycle-length-test
  (testing "seq-cycle-length for chord pattern equals order length"
    (let [state (arp/make-arp-state :alberti [60 64 67])]
      ;; :alberti order is [0 2 1 2] — 4 steps
      (is (= 4 (sq/seq-cycle-length state)))))
  (testing "seq-cycle-length for phrase pattern equals step count"
    (let [state (arp/make-arp-state
                  {:type  :phrase
                   :steps [{:semi 0 :beats 1}
                            {:semi 4 :beats 1}
                            {:rest true :beats 1}]}
                  {:root 60})]
      (is (= 3 (sq/seq-cycle-length state))))))

;; ---------------------------------------------------------------------------
;; reset-chord!
;; ---------------------------------------------------------------------------

(deftest reset-chord-test
  (testing "reset-chord! changes the chord voicing for subsequent steps"
    (let [state  (arp/make-arp-state :up [60 64 67])
          state2 (arp/reset-chord! state [62 65 69])]
      ;; new state plays from updated chord
      (is (= 62 (get-in (sq/next-event state2) [:event :pitch/midi]))))))

;; ---------------------------------------------------------------------------
;; Pattern total-beats sanity
;; ---------------------------------------------------------------------------

(deftest phrase-total-beats-test
  (testing "all 64 Hydrasynth phrases have steps that sum to positive beats"
    (doseq [n (range 1 65)]
      (let [kw    (keyword (format "phrase-%02d" n))
            p     (arp/get-pattern kw)
            total (reduce + (map #(double (:beats % 0)) (:steps p)))]
        (is (pos? total) (str kw " has zero total beats"))))))

;; ---------------------------------------------------------------------------
;; ArpState :mods — step-synchronous modulator integration
;; ---------------------------------------------------------------------------

(deftest make-arp-state-mods-nil-test
  (testing "make-arp-state with no :mods has nil mods-compiled"
    (let [state (arp/make-arp-state :up [60 64 67])]
      (is (nil? (:mods-compiled state))))))

(deftest arp-chord-mods-velocity-test
  (testing ":mods :mod/velocity shapes velocity across chord cycle"
    ;; :up pattern = [0 1 2] (3 notes), step/hold [0.0 0.5 1.0]
    ;; phase: 0/3=0.0→vel 0, 1/3=0.333→vel 64, 2/3=0.667→vel 127 (step/hold steps at boundaries)
    (let [state (arp/make-arp-state :up [60 64 67]
                  :mods {:mod/velocity {:modulator/type :step/hold
                                        :step/values    [0.0 0.5 1.0]}})
          v0 (get-in (sq/next-event state) [:event :mod/velocity])
          v1 (get-in (sq/next-event state) [:event :mod/velocity])
          v2 (get-in (sq/next-event state) [:event :mod/velocity])]
      (is (= 0   v0) "step 0: phase=0.00 → 0.0 → vel 0")
      (is (= 64  v1) "step 1: phase=0.33 → 0.5 → vel 64")
      (is (= 127 v2) "step 2: phase=0.67 → 1.0 → vel 127"))))

(deftest arp-chord-mods-lock-priority-test
  (testing "chord :params lock takes priority over :mods"
    ;; pattern with explicit params lock at idx 0 → vel 42
    (arp/register! ::lock-test
      {:type   :chord
       :order  [0 1]
       :rhythm [1 1]
       :dur    1.0
       :params [{:mod/velocity 42} {}]})
    (let [state (arp/make-arp-state ::lock-test [60 64]
                  :mods {:mod/velocity {:modulator/type :step/hold
                                        :step/values    [1.0 0.0]}})
          v0 (get-in (sq/next-event state) [:event :mod/velocity])
          v1 (get-in (sq/next-event state) [:event :mod/velocity])]
      (is (= 42  v0) "lock at step 0 overrides mod")
      (is (= 0   v1) "mod value at step 1 (no lock): 0.0 → vel 0"))))

(deftest arp-phrase-mods-velocity-test
  (testing ":mods :mod/velocity shapes velocity across phrase cycle"
    (arp/register! ::phrase-mod-test
      {:type  :phrase
       :steps [{:semi 0 :beats 1}
               {:semi 4 :beats 1}
               {:semi 7 :beats 1}
               {:semi 12 :beats 1}]})
    ;; 4 steps, step/hold with 4 values → one segment per step
    ;; phase 0/4=0.0→seg0→0.0→vel 0, 1/4=0.25→seg1→0.33→vel 42,
    ;;       2/4=0.5→seg2→0.67→vel 85, 3/4=0.75→seg3→1.0→vel 127
    (let [state (arp/make-arp-state ::phrase-mod-test {:root 60}
                  :mods {:mod/velocity {:modulator/type :step/hold
                                        :step/values    [0.0 0.33 0.67 1.0]}})
          vs (mapv (fn [_] (get-in (sq/next-event state) [:event :mod/velocity]))
                   (range 4))]
      (is (= 0   (vs 0)) "step 0: phase=0.00 → 0.0 → vel 0")
      (is (< (vs 0) (vs 1)) "step 1 > step 0")
      (is (< (vs 1) (vs 2)) "step 2 > step 1")
      (is (< (vs 2) (vs 3)) "step 3 > step 2")
      (is (= 127 (vs 3)) "step 3: phase=0.75 → 1.0 → vel 127"))))

(deftest arp-phrase-mods-lock-priority-test
  (testing "phrase parameter locks take priority over :mods"
    (arp/register! ::phrase-lock-test
      {:type  :phrase
       :steps [{:semi 0 :beats 1 :mod/velocity 99}
               {:semi 4 :beats 1}]})
    (let [state (arp/make-arp-state ::phrase-lock-test {:root 60}
                  :mods {:mod/velocity {:modulator/type :step/hold
                                        :step/values    [0.0 1.0]}})
          v0 (get-in (sq/next-event state) [:event :mod/velocity])
          v1 (get-in (sq/next-event state) [:event :mod/velocity])]
      (is (= 99  v0) "phrase lock at step 0 overrides mod")
      (is (= 127 v1) "mod value at step 1 (no lock): 1.0 → vel 127"))))

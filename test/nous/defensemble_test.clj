; SPDX-License-Identifier: EPL-2.0
(ns nous.defensemble-test
  "Unit tests for nous.defensemble — inter-voice tension monitor."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [ctrl-tree.core    :as ct]
            [nomos.maths.harmonic :as h]
            [nous.defensemble  :as de]
            [nous.seq          :as sq]
            [nous.core         :as core]))

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
  "Build an ensemble context atom directly from options."
  [opts]
  (atom (de/make-ensemble-context opts)))

(defn- set-voice!
  "Set pitch and motion in the ctrl tree for a voice."
  [voice midi motion]
  (ct/ctrl-write! [:harmony :voice-pitch voice] (long midi))
  (ct/ctrl-write! [:harmony :voice-motion voice] (long motion)))

;; ---------------------------------------------------------------------------
;; make-ensemble-context
;; ---------------------------------------------------------------------------

(deftest make-ensemble-context-defaults
  (testing "default thresholds"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :test})]
      (is (= [:bass :soprano] (:voices @ctx)))
      (is (= 6.5 (:consonance-horizon @ctx)))
      (is (= 3.5 (:fusion-threshold @ctx)))
      (is (= 5.5 (:dissonance-threshold @ctx)))
      (is (= false (:monitoring? @ctx)))
      (is (= 0.0 (:last-tension @ctx)))
      (is (= {} (:last-consonance @ctx)))
      (is (= #{} (:last-parallel-pairs @ctx))))))

(deftest make-ensemble-context-custom
  (testing "custom thresholds are stored"
    (let [ctx (make-ctx {:voices              [:a :b :c]
                         :ens-name            :trio
                         :consonance-horizon  8.0
                         :fusion-threshold    2.5
                         :dissonance-threshold 6.0})]
      (is (= [:a :b :c] (:voices @ctx)))
      (is (= 8.0 (:consonance-horizon @ctx)))
      (is (= 2.5 (:fusion-threshold @ctx)))
      (is (= 6.0 (:dissonance-threshold @ctx))))))

;; ---------------------------------------------------------------------------
;; run-update! — two-voice consonance
;; ---------------------------------------------------------------------------

(deftest run-update-unison
  (testing "two voices at unison → tension near 0"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      (let [t (de/ensemble-tension ctx)]
        (is (number? t))
        (is (<= 0.0 t 0.1) "unison gives near-zero tension")))))

(deftest run-update-consonant-interval
  (testing "voices a major sixth apart → moderate tension"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 69 0)  ; major sixth = 9 semitones
      (de/run-update! ctx)
      (let [t (de/ensemble-tension ctx)]
        (is (number? t))
        (is (< 0.0 t 1.0) "non-unison interval produces non-zero tension")))))

;; ---------------------------------------------------------------------------
;; start-monitor! — the watch actually fires run-update! (regression: the old
;; 4-arg nous.ctrl/watch! callback threw ArityException on every fire)
;; ---------------------------------------------------------------------------

(deftest start-monitor-fires-run-update-via-watch
  (testing "a voice-pitch ct/ctrl-write! triggers run-update! through the watch"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :watch-duo})]
      (try
        (de/start-monitor! ctx)
        ;; These writes fire the ctrl-watch! on [:harmony :voice-pitch …],
        ;; which calls run-update! — no direct run-update! call here.
        (set-voice! :bass    60 0)
        (set-voice! :soprano 67 0)   ; a fifth apart → non-zero tension
        (is (true? (:monitoring? @ctx)))
        (is (pos? (de/ensemble-tension ctx))
            "watch-driven run-update! updated tension (0.0 would mean the watch never fired)")
        (finally
          (de/stop-monitor! ctx))))))

(deftest run-update-tension-increases-with-distance
  (testing "wider intervals produce higher tension than narrower"
    (let [duo-narrow (make-ctx {:voices [:a :b] :ens-name :narrow})
          duo-wide   (make-ctx {:voices [:a :b] :ens-name :wide})]
      (set-voice! :a 60 0)
      (set-voice! :b 64 0)   ; major third — narrower
      (de/run-update! duo-narrow)
      (set-voice! :a 60 0)
      (set-voice! :b 71 0)   ; major seventh — wider
      (de/run-update! duo-wide)
      (is (< (de/ensemble-tension duo-narrow)
             (de/ensemble-tension duo-wide))
          "major seventh should be more tense than major third"))))

(deftest run-update-consonance-map-keys
  (testing "consonance map has correct pair key"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo})]
      (set-voice! :bass    60 0)
      (set-voice! :soprano 67 0)
      (de/run-update! ctx)
      (let [cons (de/ensemble-consonance ctx)]
        (is (map? cons))
        (is (contains? cons [:bass :soprano]))
        (is (number? (get cons [:bass :soprano])))))))

(deftest run-update-three-voices
  (testing "three voices produce three pair entries"
    (let [ctx (make-ctx {:voices [:a :b :c] :ens-name :trio})]
      (set-voice! :a 60 0)
      (set-voice! :b 64 0)
      (set-voice! :c 67 0)
      (de/run-update! ctx)
      (let [cons (de/ensemble-consonance ctx)]
        (is (= 3 (count cons)) "three pairs for three voices")
        (is (contains? cons [:a :b]))
        (is (contains? cons [:a :c]))
        (is (contains? cons [:b :c]))))))

;; ---------------------------------------------------------------------------
;; run-update! — parallel motion detection
;; ---------------------------------------------------------------------------

(deftest parallel-motion-at-unison-flagged
  (testing "successive parallel motion through fusion zone is flagged"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo-par
                         :fusion-threshold 3.5})]
      ;; Call 1: establish from-H at unison (H=0 < 3.5)
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      ;; Call 2: both move to a new unison, same direction
      (set-voice! :bass    62 1)
      (set-voice! :soprano 62 1)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (set? pars))
        (is (contains? pars [:bass :soprano])
            "unison→unison with parallel motion should be flagged")))))

(deftest contrary-motion-at-unison-not-flagged
  (testing "contrary motion through fusion zone is not flagged"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo-con
                         :fusion-threshold 3.5})]
      ;; Call 1: establish from-H at unison
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      ;; Call 2: contrary motion — voices diverge
      (set-voice! :bass    62  1)
      (set-voice! :soprano 58 -1)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (not (contains? pars [:bass :soprano]))
            "contrary motion should not be flagged")))))

(deftest no-flag-without-fusion-zone-origin
  (testing "parallel motion NOT flagged when starting outside fusion zone"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo-nofuse
                         :fusion-threshold 3.5})]
      ;; Call 1: from-H at major third (H≈4.32 — NOT in fusion zone)
      (set-voice! :bass    60 0)
      (set-voice! :soprano 64 0)
      (de/run-update! ctx)
      ;; Call 2: both move up to unison — arrives in fusion zone, but didn't start there
      (set-voice! :bass    62 1)
      (set-voice! :soprano 62 1)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (not (contains? pars [:bass :soprano]))
            "parallel arrival in fusion zone from outside should not be flagged")))))

(deftest stationary-motion-at-unison-not-flagged
  (testing "stationary voices are not flagged regardless of interval"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :duo-stat
                         :fusion-threshold 3.5})]
      ;; Establish from-H at unison
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      ;; Stationary (dir=0)
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      (let [pars (de/ensemble-parallel-pairs ctx)]
        (is (not (contains? pars [:bass :soprano]))
            "stationary motion (dir=0) is not parallel motion")))))

(deftest parallel-motion-penalty-adds-to-tension
  (testing "each detected parallel pair adds 0.15 to tension"
    (let [ctx (make-ctx {:voices [:bass :soprano] :ens-name :par-penalty
                         :fusion-threshold 3.5
                         :consonance-horizon 6.5})]
      ;; Call 1: establish from-H at unison (H=0 < 3.5)
      (set-voice! :bass    60 0)
      (set-voice! :soprano 60 0)
      (de/run-update! ctx)
      ;; Call 2: both move to a new unison via same direction
      (set-voice! :bass    62 1)
      (set-voice! :soprano 62 1)
      (de/run-update! ctx)
      ;; Base tension = 0/6.5 = 0.0; penalty = 0.15 × 1 pair
      (is (= 1 (count (de/ensemble-parallel-pairs ctx))) "one parallel pair flagged")
      (is (= 0.15 (de/ensemble-tension ctx))
          "tension = 0.0 (unison) + 0.15 (parallel penalty)"))))

;; ---------------------------------------------------------------------------
;; defensemble macro
;; ---------------------------------------------------------------------------

(deftest defensemble-macro-creates-atom
  (testing "defensemble creates a var holding an atom"
    (de/defensemble test-ensemble
      :voices [:v1 :v2]
      :ens-name :test-ensemble)
    (is (instance? clojure.lang.Atom test-ensemble))
    (is (= [:v1 :v2] (:voices @test-ensemble)))))

(deftest defensemble-macro-registers
  (testing "defensemble registers in the registry"
    (de/defensemble reg-ensemble
      :voices [:v1 :v2]
      :ens-name :reg-ensemble)
    (is (contains? (set (de/ensemble-names)) :reg-ensemble))))

(deftest defensemble-macro-starts-monitoring
  (testing "defensemble sets monitoring? true"
    (de/defensemble mon-ensemble
      :voices [:v1 :v2]
      :ens-name :mon-ensemble)
    (is (true? (:monitoring? @mon-ensemble)))))

;; ---------------------------------------------------------------------------
;; Monitor lifecycle
;; ---------------------------------------------------------------------------

(deftest stop-monitor-sets-flag
  (testing "stop-monitor! sets monitoring? to false"
    (let [ctx (make-ctx {:voices [:a :b] :ens-name :lifecycle-test})]
      (de/start-monitor! ctx)
      (is (true? (:monitoring? @ctx)))
      (de/stop-monitor! ctx)
      (is (false? (:monitoring? @ctx))))))

(deftest start-monitor-sets-flag
  (testing "start-monitor! sets monitoring? to true"
    (let [ctx (make-ctx {:voices [:a :b] :ens-name :start-test})]
      (is (false? (:monitoring? @ctx)))
      (de/start-monitor! ctx)
      (is (true? (:monitoring? @ctx)))
      (de/stop-monitor! ctx))))

;; ---------------------------------------------------------------------------
;; Inspection functions
;; ---------------------------------------------------------------------------

(deftest ensemble-names-includes-registered
  (testing "ensemble-names returns registered names"
    (de/defensemble inspect-ensemble
      :voices [:x :y]
      :ens-name :inspect-ensemble)
    (let [names (de/ensemble-names)]
      (is (seq? names))
      (is (some #{:inspect-ensemble} names)))))

(deftest inspection-functions-return-cached-state
  (testing "tension/consonance/parallel-pairs return cached values"
    (let [ctx (make-ctx {:voices [:p :q] :ens-name :cache-test})]
      (set-voice! :p 60 0)
      (set-voice! :q 67 0)
      (de/run-update! ctx)
      (is (number? (de/ensemble-tension ctx)))
      (is (map? (de/ensemble-consonance ctx)))
      (is (set? (de/ensemble-parallel-pairs ctx))))))

(deftest tension-bounded
  (testing "tension is always in [0,1]"
    (let [ctx (make-ctx {:voices [:a :b] :ens-name :bound-test})]
      (doseq [[m1 m2] [[0 127] [60 60] [48 72] [55 73]]]
        (set-voice! :a m1 1)
        (set-voice! :b m2 1)
        (de/run-update! ctx)
        (let [t (de/ensemble-tension ctx)]
          (is (<= 0.0 t 1.0)
              (str "tension out of [0,1] for midi pair " m1 "/" m2)))))))

;; ---------------------------------------------------------------------------
;; Imitation buffer
;; ---------------------------------------------------------------------------

(deftest imitation-buffer-starts-empty
  (testing "imitation buffer is empty on construction"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:v1 :v2]
                      :ens-name  :imit-empty
                      :imitation {:v2 {:follows :v1 :interval 7 :delay-steps 0}}}))]
      (is (= 0 (de/imitation-buffer-size ctx :v2))))))

(deftest imitation-buffer-grows-when-leader-pushed
  (testing "buffer grows when entries are added to the context directly"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:v1 :v2]
                      :ens-name  :imit-grow
                      :imitation {:v2 {:follows :v1 :interval 7 :delay-steps 0}}}))]
      (swap! ctx update-in [:imitation-buffers :v2] conj {:midi 60 :dur/beats 1.0})
      (swap! ctx update-in [:imitation-buffers :v2] conj {:midi 62 :dur/beats 1.0})
      (is (= 2 (de/imitation-buffer-size ctx :v2))))))

(deftest clear-imitation-buffer-empties-queue
  (testing "clear-imitation-buffer! empties the queue"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:v1 :v2]
                      :ens-name  :imit-clear
                      :imitation {:v2 {:follows :v1 :interval 7 :delay-steps 0}}}))]
      (swap! ctx update-in [:imitation-buffers :v2] conj {:midi 60 :dur/beats 1.0})
      (is (= 1 (de/imitation-buffer-size ctx :v2)))
      (de/clear-imitation-buffer! ctx :v2)
      (is (= 0 (de/imitation-buffer-size ctx :v2))))))

;; ---------------------------------------------------------------------------
;; make-imitation-seq / IStepSequencer
;; ---------------------------------------------------------------------------

(deftest imitation-seq-rests-during-delay
  (testing "sequencer returns rest while buffer is smaller than delay-steps"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:leader :follower]
                      :ens-name  :imit-delay
                      :imitation {:follower {:follows :leader :interval 0 :delay-steps 3}}}))]
      ;; Only one entry buffered, delay requires 3
      (swap! ctx update-in [:imitation-buffers :follower] conj {:midi 60 :dur/beats 1.0})
      (let [seq (de/make-imitation-seq ctx :follower)
            {:keys [event beats]} (sq/next-event seq)]
        (is (nil? event) "should rest while in delay hold")
        (is (= 1.0 beats))))))

(deftest imitation-seq-plays-after-delay-threshold
  (testing "sequencer plays once buffer reaches delay-steps"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:leader :follower]
                      :ens-name  :imit-play
                      :imitation {:follower {:follows :leader :interval 7 :delay-steps 2}}}))]
      ;; Buffer two entries — meets delay threshold
      (swap! ctx update-in [:imitation-buffers :follower] conj {:midi 60 :dur/beats 1.0})
      (swap! ctx update-in [:imitation-buffers :follower] conj {:midi 62 :dur/beats 1.0})
      (let [seq    (de/make-imitation-seq ctx :follower)
            result (sq/next-event seq)]
        (is (some? (:event result)) "should produce an event")
        (is (= true (get-in result [:event :gate/on?])))))))

(deftest imitation-seq-transposes-by-interval
  (testing "imitation seq applies semitone interval to leader pitch"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:l :f]
                      :ens-name  :imit-interval
                      :imitation {:f {:follows :l :interval 7 :delay-steps 0}}}))]
      (swap! ctx update-in [:imitation-buffers :f] conj {:midi 60 :dur/beats 1.0})
      (let [seq    (de/make-imitation-seq ctx :f)
            result (sq/next-event seq)]
        (is (= 67 (get-in result [:event :pitch/midi]))
            "MIDI 60 + 7 semitones = 67")))))

(deftest imitation-seq-rests-when-buffer-empty-after-start
  (testing "sequencer rests (not stuck) when buffer drains after delay"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:l :f]
                      :ens-name  :imit-drain
                      :imitation {:f {:follows :l :interval 0 :delay-steps 0}}}))]
      ;; One entry — consume it, then check empty rest
      (swap! ctx update-in [:imitation-buffers :f] conj {:midi 60 :dur/beats 1.0})
      (let [seq (de/make-imitation-seq ctx :f)]
        (sq/next-event seq)    ; consume the entry
        (let [{:keys [event beats]} (sq/next-event seq)]
          (is (nil? event) "empty buffer → rest event")
          (is (= 1.0 beats)))))))

(deftest imitation-seq-clamps-midi-range
  (testing "transposed MIDI is clamped to [0, 127]"
    (let [ctx (atom (de/make-ensemble-context
                     {:voices    [:l :f]
                      :ens-name  :imit-clamp
                      :imitation {:f {:follows :l :interval 100 :delay-steps 0}}}))]
      ;; MIDI 60 + 100 would exceed 127
      (swap! ctx update-in [:imitation-buffers :f] conj {:midi 60 :dur/beats 1.0})
      (let [seq    (de/make-imitation-seq ctx :f)
            result (sq/next-event seq)]
        (is (<= 0 (get-in result [:event :pitch/midi]) 127)
            "pitch clamped to valid MIDI range")))))

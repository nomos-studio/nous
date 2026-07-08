; SPDX-License-Identifier: EPL-2.0
(ns nous.link-test
  "Tests for nous.link — kairos/aion 24 PPQN tick-driven beat clock.

  The sidecar 0x80 binary push is gone; drive the system by calling the private
  on-tick handler directly, as kairos would."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.core  :as core]
            [nous.kairos :as kairos]
            [nous.link  :as link]))

;; ---------------------------------------------------------------------------
;; Helpers — private atom access
;; ---------------------------------------------------------------------------

(defn- system-ref       [] @#'link/system-ref)
(defn- hooks-atom       [] @#'link/transport-hook-registry)
(defn- tick-window-atom [] @#'link/tick-window)

(defn- simulate-ticks!
  "Push n ticks to the on-tick handler with ~16ms gaps so BPM estimation works.
  start-beat — beat position of the first tick.
  Returns after all ticks are processed."
  [n start-beat]
  (let [on-tick  #'link/on-tick
        beat-inc (/ 1.0 24.0)]
    (dotimes [i n]
      (on-tick {:beat (+ (double start-beat) (* i beat-inc)) :tick-n i})
      (when (< i (dec n))
        (Thread/sleep 16)))))

(defn- seed-tick-window!
  "Seed the tick window so estimate-bpm will return approximately bpm.
  Uses two entries (oldest, newest) spanning 7 tick increments."
  [bpm]
  (let [beat-span   (* 7 (/ 1.0 24.0))
        ms-span     (long (* beat-span (/ 60000.0 bpm)))
        now         (System/currentTimeMillis)]
    (reset! (tick-window-atom) clojure.lang.PersistentQueue/EMPTY)
    (swap! (tick-window-atom) conj {:beat 0.0            :epoch-ms (- now ms-span)})
    (swap! (tick-window-atom) conj {:beat (double beat-span) :epoch-ms now})))

(defmacro with-bpm
  "Temporarily replace the private estimate-bpm fn so on-tick receives a
  known BPM value. Uses alter-var-root because with-redefs does not accept
  #'namespace/private-var syntax."
  [bpm & body]
  `(let [orig# @#'link/estimate-bpm]
     (alter-var-root #'link/estimate-bpm (fn [_#] (constantly ~bpm)))
     (try ~@body
          (finally (alter-var-root #'link/estimate-bpm (fn [_#] orig#))))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (core/start! :bpm 120)
    (when-let [s @(system-ref)]
      (swap! s dissoc :link-state :link-time-id))
    (reset! (tick-window-atom) clojure.lang.PersistentQueue/EMPTY)
    (reset! (hooks-atom) {})
    (t)
    (reset! (hooks-atom) {})
    (reset! (tick-window-atom) clojure.lang.PersistentQueue/EMPTY)
    (core/stop!)))

;; ---------------------------------------------------------------------------
;; BPM estimation
;; ---------------------------------------------------------------------------

(deftest bpm-nil-before-ticks
  (testing "bpm returns nil when no ticks have arrived"
    (is (nil? (link/bpm)))))

(deftest bpm-estimated-from-ticks
  (testing "BPM is estimated (non-nil) after multiple ticks arrive"
    (with-redefs [kairos/connected? (constantly true)]
      (simulate-ticks! 10 0.0)
      (is (some? (link/bpm)) "BPM should be non-nil after sufficient ticks"))))

;; ---------------------------------------------------------------------------
;; playing? / active?
;; ---------------------------------------------------------------------------

(deftest playing-false-before-ticks
  (testing "playing? returns false when no ticks have been received"
    (is (false? (link/playing?)))))

(deftest playing-true-after-single-tick
  (testing "playing? returns true immediately after a tick arrives"
    (with-redefs [kairos/connected? (constantly true)]
      (#'link/on-tick {:beat 0.0 :tick-n 0})
      (is (true? (link/playing?))))))

(deftest active-false-when-kairos-disconnected
  (testing "active? is false when kairos is not connected even with recent ticks"
    (with-redefs [kairos/connected? (constantly false)]
      (#'link/on-tick {:beat 0.0 :tick-n 0})
      (is (false? (link/active?))))))

(deftest active-true-when-connected-and-ticking
  (testing "active? is true when kairos is connected and ticks are recent"
    (with-redefs [kairos/connected? (constantly true)]
      (#'link/on-tick {:beat 0.0 :tick-n 0})
      (is (true? (link/active?))))))

;; ---------------------------------------------------------------------------
;; link-timeline
;; ---------------------------------------------------------------------------

(deftest link-timeline-nil-before-ticks
  (testing "link-timeline returns nil when inactive"
    (is (nil? (link/link-timeline)))))

(deftest link-timeline-populated-after-ticks
  (testing "link-timeline has :bpm, :beat0-beat, :beat0-epoch-ms after ticks"
    (with-redefs [kairos/connected? (constantly true)]
      (simulate-ticks! 10 0.0)
      (let [tl (link/link-timeline)]
        (is (some? tl)                     "timeline should be non-nil")
        (is (number? (:bpm tl))            ":bpm should be a number")
        (is (number? (:beat0-beat tl))     ":beat0-beat should be a number")
        (is (number? (:beat0-epoch-ms tl)) ":beat0-epoch-ms should be a number")))))

;; ---------------------------------------------------------------------------
;; Transport-change hooks — fire on first BPM-estimated tick sequence
;; ---------------------------------------------------------------------------

(deftest transport-hook-fires-on-start
  (testing "on-transport-change! hook fires (playing?=true) when ticks first establish BPM"
    (let [received (atom :not-fired)]
      (link/on-transport-change! ::test-hook (fn [p] (reset! received p)))
      (with-redefs [kairos/connected? (constantly true)]
        (simulate-ticks! 10 0.0))
      (is (true? @received) "hook fires with playing?=true"))))

(deftest transport-hook-does-not-fire-when-already-playing
  (testing "hook does NOT fire on subsequent ticks once playing is established"
    (let [call-count (atom 0)]
      (with-redefs [kairos/connected? (constantly true)]
        (simulate-ticks! 10 0.0)
        (link/on-transport-change! ::test-hook (fn [_] (swap! call-count inc)))
        (simulate-ticks! 5 (/ 10.0 24.0)))
      (is (zero? @call-count) "no extra hook fires after playing is established"))))

(deftest multiple-hooks-all-fire
  (testing "all registered hooks fire on transport start"
    (let [r1 (atom nil) r2 (atom nil)]
      (link/on-transport-change! ::hook-1 (fn [p] (reset! r1 p)))
      (link/on-transport-change! ::hook-2 (fn [p] (reset! r2 p)))
      (with-redefs [kairos/connected? (constantly true)]
        (simulate-ticks! 10 0.0))
      (is (true? @r1))
      (is (true? @r2)))))

(deftest remove-transport-hook-stops-firing
  (testing "remove-transport-hook! prevents the hook from firing"
    (let [r (atom :not-fired)]
      (link/on-transport-change! ::removable (fn [p] (reset! r p)))
      (link/remove-transport-hook! ::removable)
      (with-redefs [kairos/connected? (constantly true)]
        (simulate-ticks! 10 0.0))
      (is (= :not-fired @r)))))

(deftest hook-exception-does-not-crash-tick
  (testing "a throwing hook does not prevent other hooks from firing"
    (let [r (atom :not-fired)]
      (link/on-transport-change! ::bad-hook  (fn [_] (throw (ex-info "boom" {}))))
      (link/on-transport-change! ::good-hook (fn [p] (reset! r p)))
      (with-redefs [kairos/connected? (constantly true)]
        (simulate-ticks! 10 0.0))
      (is (true? @r)))))

;; ---------------------------------------------------------------------------
;; Hook registry management
;; ---------------------------------------------------------------------------

(deftest register-hook-adds-to-registry
  (link/on-transport-change! ::my-hook (fn [_] nil))
  (is (contains? (link/transport-hooks) ::my-hook)))

(deftest remove-hook-removes-from-registry
  (link/on-transport-change! ::rm-hook (fn [_] nil))
  (link/remove-transport-hook! ::rm-hook)
  (is (not (contains? (link/transport-hooks) ::rm-hook))))

(deftest reregister-hook-replaces-previous
  (let [v (atom 0)]
    (link/on-transport-change! ::shared (fn [_] (swap! v + 1)))
    (link/on-transport-change! ::shared (fn [_] (swap! v + 10)))
    (with-redefs [kairos/connected? (constantly true)]
      (simulate-ticks! 10 0.0))
    (is (= 10 @v) "second registration replaces first — only +10 fires")))

;; ---------------------------------------------------------------------------
;; Vestigial calls — must not throw
;; ---------------------------------------------------------------------------

(deftest vestigial-calls-do-not-throw
  (testing "enable!, disable!, set-bpm!, start-transport!, stop-transport! are safe"
    (with-out-str
      (link/enable!)
      (link/disable!)
      (link/set-bpm! 130.0)
      (link/start-transport!)
      (link/stop-transport!))
    (is true "no exception thrown")))

;; ---------------------------------------------------------------------------
;; core/set-bpm! is safe when link is inactive
;; ---------------------------------------------------------------------------

(deftest set-bpm-does-not-crash-when-link-inactive
  (testing "core/set-bpm! is safe when Link is not active"
    (is (nil? (core/set-bpm! 130.0)))
    (is (= 130.0 (core/get-bpm)))))

;; ---------------------------------------------------------------------------
;; next-quantum-beat
;; ---------------------------------------------------------------------------

(deftest next-quantum-beat-test
  (testing "next-quantum-beat returns the next multiple of quantum at or after beat"
    (is (= 0.0 (link/next-quantum-beat 0.0 4))  "beat 0 at quantum 4 → 0")
    (is (= 4.0 (link/next-quantum-beat 1.0 4))  "beat 1 at quantum 4 → 4")
    (is (= 4.0 (link/next-quantum-beat 4.0 4))  "beat 4 at quantum 4 → 4 (exact)")
    (is (= 8.0 (link/next-quantum-beat 5.5 4))  "beat 5.5 at quantum 4 → 8")
    (is (= 3.0 (link/next-quantum-beat 2.0 3))  "beat 2 at quantum 3 → 3")))

;; ---------------------------------------------------------------------------
;; Time identity — :link-time-id pending-state pattern
;;
;; Tests use with-redefs on the private estimate-bpm fn to inject specific BPM
;; values without depending on tick-window timing, then drive on-tick directly.
;; ---------------------------------------------------------------------------

(defn- state-map
  "Return the current system state map."
  []
  @@(system-ref))

(deftest time-id-first-tick-always-snaps
  (testing "first tick always snaps regardless of policy: current set, pending nil"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0
        (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (let [tid (get-in (state-map) [:link-time-id])]
        (is (some?   (:current tid))        "current should be set after first tick")
        (is (nil?    (:pending tid))        "pending should be nil after first tick")
        (is (number? (:bpm (:current tid))) ":bpm should be a number")))))

(deftest time-id-snap-policy-no-pending
  (testing "snap policy: significant BPM change applies to :current immediately, pending stays nil"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (link/enable! :policy :snap)
      (with-bpm 160.0 (#'link/on-tick {:beat 1.042 :tick-n 1}))
      (let [tid (get-in (state-map) [:link-time-id])]
        (is (nil? (:pending tid))             "snap policy: pending should be nil")
        (is (> (:bpm (:current tid)) 140.0)   "current BPM should reflect the new estimate")))))

(deftest time-id-bar-quantize-populates-pending
  (testing "bar-quantize policy: significant BPM change goes to pending with correct apply-at"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (link/enable! :policy :bar-quantize)
      (with-bpm 160.0 (#'link/on-tick {:beat 1.5 :tick-n 1}))
      (let [tid     (get-in (state-map) [:link-time-id])
            pending (:pending tid)]
        (is (some? pending)                     "pending should be populated")
        (is (= :bar-quantize (:policy pending)) ":policy should be :bar-quantize")
        (is (number? (:apply-at pending))       ":apply-at should be a number")
        (is (> (:apply-at pending) 1.5)         ":apply-at should be after current beat")
        ;; Current BPM stays at the old value; new 160 BPM is deferred
        (is (< (:bpm (:current tid)) 140.0)    ":current BPM should not have jumped to 160")))))

(deftest time-id-pending-promotion-at-bar-boundary
  (testing "pending is promoted to current when tick beat crosses apply-at"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (link/enable! :policy :bar-quantize)
      (with-bpm 160.0 (#'link/on-tick {:beat 1.5 :tick-n 1}))
      (let [apply-at (get-in (state-map) [:link-time-id :pending :apply-at])]
        (is (some? apply-at) "pending should exist before promotion")
        (with-bpm 160.0 (#'link/on-tick {:beat (+ apply-at 0.5) :tick-n 2}))
        (let [tid (get-in (state-map) [:link-time-id])]
          (is (nil? (:pending tid))           "pending should be nil after promotion")
          (is (> (:bpm (:current tid)) 140.0) "current BPM should now reflect new tempo"))))))

(deftest time-id-multiple-changes-latest-wins
  (testing "two BPM changes before bar boundary: later pending replaces earlier"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (link/enable! :policy :bar-quantize)
      (with-bpm 160.0 (#'link/on-tick {:beat 1.5 :tick-n 1}))
      (let [apply-at1 (get-in (state-map) [:link-time-id :pending :apply-at])]
        (with-bpm 170.0 (#'link/on-tick {:beat 2.0 :tick-n 2}))
        (let [apply-at2 (get-in (state-map) [:link-time-id :pending :apply-at])]
          (is (some? apply-at1) "first pending had an apply-at")
          (is (some? apply-at2) "second pending has an apply-at")
          (is (> apply-at1 1.5) "first apply-at should be after trigger beat 1.5")
          (is (> apply-at2 2.0) "second apply-at should be after trigger beat 2.0"))))))

(deftest time-id-disable-clears-time-id
  (testing "disable! clears :link-time-id from system state"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (is (some? (get-in (state-map) [:link-time-id]))
          ":link-time-id should exist before disable!")
      (with-out-str (link/disable!))
      (is (nil? (get-in (state-map) [:link-time-id]))
          ":link-time-id should be nil after disable!"))))

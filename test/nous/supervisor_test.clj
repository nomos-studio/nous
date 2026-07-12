; SPDX-License-Identifier: EPL-2.0
(ns nous.supervisor-test
  "Tests for nous.supervisor — event bus, service registry, watchdog,
  loop pause/resume integration, and RT tick-silence detection."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [nous.core       :as core]
            [nous.loop       :as loop-ns]
            [nous.rt         :as rt]
            [nous.runtime    :as runtime]
            [nous.supervisor :as supervisor]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn with-system [f]
  (core/start! :bpm 60000)
  (try (f) (finally (core/stop!))))

(defn- cleanup-rt-tick! []
  (when-let [k @@#'supervisor/rt-tick-key]
    (rt/off-tick! k)
    (reset! @#'supervisor/rt-tick-key nil)))

(defn with-clean-supervisor [f]
  ;; Reset supervisor state between tests
  (cleanup-rt-tick!)
  (reset! @#'supervisor/services {})
  (reset! @#'supervisor/service-state {})
  (reset! @#'supervisor/event-handlers {})
  (when (supervisor/running?) (supervisor/stop-watchdog!))
  (try (f)
       (finally
         (when (supervisor/running?) (supervisor/stop-watchdog!))
         (cleanup-rt-tick!)
         (reset! @#'supervisor/services {})
         (reset! @#'supervisor/service-state {})
         (reset! @#'supervisor/event-handlers {}))))

(use-fixtures :each with-system with-clean-supervisor)

;; ---------------------------------------------------------------------------
;; Timing helper
;; ---------------------------------------------------------------------------

(defn- poll-until
  "Poll `pred` every 10 ms until it returns truthy or `timeout-ms` elapses.
  Returns pred's final value (truthy on success, nil/false on timeout).

  Replaces fixed Thread/sleep coordination with the background watchdog: the
  test waits only as long as the transition actually takes, up to a generous
  ceiling, so it cannot race under CPU load or a GC pause."
  ([pred] (poll-until pred 2000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
     (loop []
       (let [v (pred)]
         (if (or v (> (System/currentTimeMillis) deadline))
           v
           (do (Thread/sleep 10) (recur))))))))

;; ---------------------------------------------------------------------------
;; Service registry
;; ---------------------------------------------------------------------------

(deftest register-returns-service-name-test
  (testing "register! returns the service name"
    (is (= :my-svc (supervisor/register! :my-svc :check-fn (constantly true))))))

(deftest initial-status-is-unknown-test
  (testing "newly registered service has :unknown status"
    (supervisor/register! :svc-a :check-fn (constantly true))
    (is (= :unknown (supervisor/service-status :svc-a)))))

(deftest deregister-removes-service-test
  (testing "deregister! removes the service"
    (supervisor/register! :svc-b :check-fn (constantly true))
    (supervisor/deregister! :svc-b)
    (is (= :unknown (supervisor/service-status :svc-b)))))

(deftest all-statuses-returns-all-test
  (testing "all-statuses returns a map with all registered services"
    (supervisor/register! :svc-c :check-fn (constantly true))
    (supervisor/register! :svc-d :check-fn (constantly false))
    (let [s (supervisor/all-statuses)]
      (is (contains? s :svc-c))
      (is (contains? s :svc-d)))))

;; ---------------------------------------------------------------------------
;; any-down?
;; ---------------------------------------------------------------------------

(deftest any-down-unknown-is-not-down-test
  (testing "any-down? returns nil when services are :unknown (not yet checked)"
    (supervisor/register! :svc-e :check-fn (constantly true))
    (is (nil? (supervisor/any-down? [:svc-e])))))

(deftest any-down-detects-down-service-test
  (testing "any-down? returns truthy when a service is :down"
    (supervisor/register! :svc-f :check-fn (constantly false))
    (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
    (is (poll-until #(supervisor/any-down? [:svc-f])) "service marked :down within timeout")
    (supervisor/stop-watchdog!)))

(deftest any-down-false-when-all-up-test
  (testing "any-down? returns nil when all services are :up"
    (supervisor/register! :svc-g :check-fn (constantly true))
    (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
    (is (poll-until #(= :up (supervisor/service-status :svc-g))) "service came :up")
    (is (nil? (supervisor/any-down? [:svc-g])))
    (supervisor/stop-watchdog!)))

;; ---------------------------------------------------------------------------
;; Event bus
;; ---------------------------------------------------------------------------

(deftest on-event-registers-and-fires-test
  (testing "on-event! registers a handler that fires when emit! is called"
    (let [received (atom nil)]
      (supervisor/on-event! :test-svc :down ::h1
                            (fn [payload] (reset! received payload)))
      (@#'supervisor/emit! :test-svc :down {:foo :bar})
      (is (= {:foo :bar} @received)))))

(deftest off-event-removes-handler-test
  (testing "off-event! prevents the handler from firing"
    (let [called (atom false)]
      (supervisor/on-event! :test-svc :up ::h2
                            (fn [_] (reset! called true)))
      (supervisor/off-event! :test-svc :up ::h2)
      (@#'supervisor/emit! :test-svc :up {})
      (is (false? @called)))))

(deftest handler-exception-does-not-propagate-test
  (testing "a handler that throws does not prevent other handlers from firing"
    (let [second-called (atom false)]
      (supervisor/on-event! :test-svc :down ::throw-h
                            (fn [_] (throw (ex-info "handler boom" {}))))
      (supervisor/on-event! :test-svc :down ::ok-h
                            (fn [_] (reset! second-called true)))
      (@#'supervisor/emit! :test-svc :down {})
      (is (true? @second-called)
          "second handler fired despite first one throwing"))))

;; ---------------------------------------------------------------------------
;; Watchdog: service status transitions
;; ---------------------------------------------------------------------------

(deftest watchdog-transitions-up-to-down-test
  (testing "watchdog emits :down when a healthy service becomes unhealthy"
    (let [healthy?  (atom true)
          events    (atom [])]
      (supervisor/register! :flaky :check-fn #(deref healthy?))
      (supervisor/on-event! :flaky :down ::track
                            (fn [p] (swap! events conj p)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= :up (supervisor/service-status :flaky))) "service came :up first")
      (reset! healthy? false)
      (is (poll-until #(seq @events)) "at least one :down event emitted")
      (supervisor/stop-watchdog!)
      (is (every? #(= :flaky (:service %)) @events)))))

(deftest watchdog-emits-up-on-recovery-test
  (testing "watchdog emits :up when a down service recovers"
    (let [healthy? (atom false)
          up-events (atom [])]
      (supervisor/register! :recoverable :check-fn #(deref healthy?))
      (supervisor/on-event! :recoverable :up ::track
                            (fn [p] (swap! up-events conj p)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= :down (supervisor/service-status :recoverable))) "service starts :down")
      (reset! healthy? true)  ; service recovers
      (is (poll-until #(seq @up-events)) ":up event emitted on recovery")
      (supervisor/stop-watchdog!)
      (is (= :up (supervisor/service-status :recoverable))))))

(deftest watchdog-calls-restart-fn-on-down-test
  (testing "watchdog calls restart-fn when service goes down"
    (let [healthy?    (atom true)
          restarted?  (atom false)]
      (supervisor/register! :restartable
                            :check-fn    #(deref healthy?)
                            :restart-fn  #(reset! restarted? true))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= :up (supervisor/service-status :restartable))) "service came :up first")
      (reset! healthy? false)
      (is (poll-until #(deref restarted?)) "restart-fn was called")
      (supervisor/stop-watchdog!))))

(deftest watchdog-calls-restore-fn-on-recovery-test
  (testing "watchdog calls restore-fn after successful recovery"
    (let [healthy?   (atom false)
          restored?  (atom false)]
      (supervisor/register! :restorable
                            :check-fn    #(deref healthy?)
                            :restore-fn  #(reset! restored? true))
      (supervisor/on-event! :restorable :up ::flip
                            (fn [_] (reset! healthy? true)))  ; stay up once up
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= :down (supervisor/service-status :restorable))) "service starts :down")
      (reset! healthy? true)   ; service recovers
      (is (poll-until #(deref restored?)) "restore-fn was called after recovery")
      (supervisor/stop-watchdog!))))

(deftest watchdog-does-not-re-emit-down-test
  (testing "watchdog emits :down only once per outage, not every tick"
    (let [down-count (atom 0)]
      (supervisor/register! :once-down :check-fn (constantly false))
      (supervisor/on-event! :once-down :down ::count
                            (fn [_] (swap! down-count inc)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= 1 @down-count)) ":down emitted at least once")
      ;; Let several more ticks pass to prove it does not re-emit. A re-emit
      ;; could only *raise* the count, so a slow machine makes this weaker, not flaky.
      (Thread/sleep 300)
      (supervisor/stop-watchdog!)
      (is (= 1 @down-count) ":down emitted exactly once"))))

;; ---------------------------------------------------------------------------
;; Loop pause / resume integration
;; ---------------------------------------------------------------------------

(deftest pause-on-down-pauses-loop-body-test
  (testing ":pause-on-down pauses loop body while service is :down"
    (let [body-ran  (atom false)]
      (supervisor/register! :test-gate :check-fn (constantly false))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= :down (supervisor/service-status :test-gate))) "service forced :down")
      ;; Now start a loop that should NOT execute its body
      (loop-ns/deflive-loop :test-paused {:pause-on-down [:test-gate]}
        (reset! body-ran true)
        (loop-ns/sleep! 1000))
      (Thread/sleep 100)  ; give a wrongly-unpaused body time to flip the atom
      (is (false? @body-ran) "loop body did not run while service was :down")
      (loop-ns/stop-loop! :test-paused)
      (supervisor/stop-watchdog!))))

(deftest pause-on-down-resumes-when-service-recovers-test
  (testing ":pause-on-down loop resumes when service comes back :up"
    (let [healthy?  (atom false)
          body-ran  (promise)]
      (supervisor/register! :test-gate2 :check-fn #(deref healthy?))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
      (is (poll-until #(= :down (supervisor/service-status :test-gate2))) "service starts :down")
      (loop-ns/deflive-loop :test-resume {:pause-on-down [:test-gate2]}
        (deliver body-ran :ran)
        (loop-ns/sleep! 1000))
      (Thread/sleep 50)   ; confirm body hasn't run
      (reset! healthy? true)   ; service recovers
      (let [result (deref body-ran 2000 :timeout)]
        (loop-ns/stop-loop! :test-resume)
        (supervisor/stop-watchdog!)
        (is (= :ran result) "loop body ran after service recovered")))))

;; ---------------------------------------------------------------------------
;; restart-loop!
;; ---------------------------------------------------------------------------

(deftest restart-loop-restarts-dead-thread-test
  (testing "restart-loop! starts a fresh thread for a dead loop"
    (let [v        (atom :initial)
          dead-t   (doto (Thread. (fn [])) (.start))
          running? (atom true)
          sref     @(loop-ns/-system-ref)]
      (.join dead-t 200)
      ;; Inject a stale entry (dead thread, running?=true)
      (swap! sref assoc-in [:loops :test-rl]
             {:fn         (fn [] (reset! v :restarted) (loop-ns/sleep! 1000))
              :tick-count 0
              :running?   running?
              :sleep-interrupted? (atom false)
              :thread     dead-t})
      (loop-ns/restart-loop! :test-rl)
      (is (poll-until #(= :restarted @v)) "restarted loop body ran")
      (loop-ns/stop-loop! :test-rl))))

(deftest restart-loop-returns-nil-for-live-loop-test
  (testing "restart-loop! returns nil and leaves a live loop untouched"
    (let [v (atom :original)]
      (loop-ns/deflive-loop :test-rl-live {}
        (reset! v :live)
        (loop-ns/sleep! 1000))
      (Thread/sleep 30)
      (is (nil? (loop-ns/restart-loop! :test-rl-live))
          "restart-loop! returns nil for a live loop")
      (loop-ns/stop-loop! :test-rl-live))))

;; ---------------------------------------------------------------------------
;; Watchdog: loop monitoring
;; ---------------------------------------------------------------------------

(deftest watchdog-emits-down-for-dead-loop-test
  (testing "watchdog emits :loops/:down when a loop thread dies"
    (let [dead-events (atom [])
          dead-t      (doto (Thread. (fn [])) (.start))
          running?    (atom true)
          sref        @(loop-ns/-system-ref)]
      (.join dead-t 200)
      (swap! sref assoc-in [:loops :test-dead-loop]
             {:fn (fn []) :tick-count 0
              :running? running?
              :sleep-interrupted? (atom false)
              :thread dead-t})
      (supervisor/on-event! :loops :down ::track-dead
                            (fn [p] (swap! dead-events conj p)))
      (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? true)
      (is (poll-until #(some (fn [e] (= :test-dead-loop (:loop e))) @dead-events))
          "dead loop event emitted by watchdog")
      (supervisor/stop-watchdog!)
      (swap! sref update :loops dissoc :test-dead-loop))))

;; ---------------------------------------------------------------------------
;; Watchdog lifecycle
;; ---------------------------------------------------------------------------

(deftest start-watchdog-running-test
  (testing "start-watchdog! sets running? to true"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (is (supervisor/running?))
    (supervisor/stop-watchdog!)))

(deftest stop-watchdog-not-running-test
  (testing "stop-watchdog! sets running? to false"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (supervisor/stop-watchdog!)
    (is (not (supervisor/running?)))))

(deftest double-start-throws-test
  (testing "starting the watchdog twice throws"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)))
      (finally
        (supervisor/stop-watchdog!)))))

(deftest stop-wires-out-pause-check-test
  (testing "stop-watchdog! removes the pause-check hook from deflive-loop"
    (supervisor/start-watchdog! :interval-ms 1000 :monitor-loops? false)
    (is (some? @loop-ns/-pause-check-fn) "pause-check wired in after start")
    (supervisor/stop-watchdog!)
    (is (nil? @loop-ns/-pause-check-fn) "pause-check cleared after stop")))

;; ---------------------------------------------------------------------------
;; BPM-derived watchdog interval
;; ---------------------------------------------------------------------------

(deftest watchdog-sleep-ms-explicit-interval-test
  (testing "watchdog-sleep-ms returns interval-ms unchanged when explicitly provided"
    ;; fixture starts at bpm 60000, so BPM-derived default would be tiny;
    ;; explicit 5000 must be returned verbatim
    (is (= 5000 (@#'supervisor/watchdog-sleep-ms 5000 1 4)))))

(deftest watchdog-sleep-ms-derives-from-bpm-test
  (testing "watchdog-sleep-ms computes ms from BPM when interval-ms is nil"
    ;; fixture sets BPM=60000 → 1 beat = 1 ms
    ;; 1 bar × 4 beats/bar × (60000 / 60000) = 4 ms
    (is (= 100 (@#'supervisor/watchdog-sleep-ms nil 1 4))
        "result clamped to 100 ms minimum")
    ;; At bpm=60: 1 bar × 4 beats × (60000/60) = 4000 ms
    (core/set-bpm! 60)
    (is (= 4000 (@#'supervisor/watchdog-sleep-ms nil 1 4)))))

(deftest watchdog-sleep-ms-bars-parameter-test
  (testing "watchdog-sleep-ms scales by :bars"
    ;; At bpm=60: 2 bars × 4 beats × 1000 ms/beat = 8000 ms
    (core/set-bpm! 60)
    (is (= 8000 (@#'supervisor/watchdog-sleep-ms nil 2 4)))))

(deftest watchdog-sleep-ms-beats-per-bar-test
  (testing "watchdog-sleep-ms scales by :beats-per-bar for odd meters"
    ;; At bpm=60: 1 bar × 3 beats × 1000 ms/beat = 3000 ms
    (core/set-bpm! 60)
    (is (= 3000 (@#'supervisor/watchdog-sleep-ms nil 1 3)))))

(deftest watchdog-bpm-derived-interval-fires-test
  (testing "watchdog with no :interval-ms fires at approximately one bar's worth of ms"
    ;; Set a very high BPM so one bar is just a few ms — let the watchdog tick fast
    (core/set-bpm! 60000)  ; 1 bar = 4 ms → clamped to 100 ms
    (let [tick-count (atom 0)]
      (supervisor/register! :bpm-svc :check-fn (fn [] (swap! tick-count inc) true))
      ;; no :interval-ms — uses BPM-derived sleep (clamped to 100 ms)
      (supervisor/start-watchdog! :monitor-loops? false)
      (is (poll-until #(>= @tick-count 2)) "watchdog ticked at least twice")
      (supervisor/stop-watchdog!))))

;; ---------------------------------------------------------------------------
;; register-rt! — tick-silence aware RT backend supervision
;; ---------------------------------------------------------------------------

(deftest register-rt-initialises-service-state-test
  (testing "register-rt! creates :rt in service-state with :unknown status"
    (supervisor/register-rt!)
    (is (= :unknown (supervisor/service-status :rt)))
    (let [st (get @@#'supervisor/service-state :rt)]
      (is (some? (:stale-threshold-ns st)))
      (is (some? (:resume-bar-beats st)))
      (is (nil? (:last-tick-ns st))))))

(deftest register-rt-registers-tick-handler-test
  (testing "register-rt! registers an on-tick! handler that updates last-tick-ns"
    (supervisor/register-rt!)
    (is (some? @@#'supervisor/rt-tick-key) "tick handler key was stored")
    ;; Fire the tick handler manually
    (let [tick-handlers @@#'rt/tick-handlers
          k             @@#'supervisor/rt-tick-key]
      (is (contains? tick-handlers k) "handler is registered in rt/tick-handlers")
      ((get tick-handlers k) {:beat 1.0 :tick-n 24})
      (is (some? (get-in @@#'supervisor/service-state [:rt :last-tick-ns]))
          "last-tick-ns updated after tick"))))

(deftest register-rt-idempotent-test
  (testing "register-rt! called twice replaces the old tick handler (no leak)"
    (supervisor/register-rt!)
    (let [k1 @@#'supervisor/rt-tick-key]
      (supervisor/register-rt!)
      (let [k2 @@#'supervisor/rt-tick-key]
        (is (not= k1 k2) "new handler registered on second call")
        (is (not (contains? @@#'rt/tick-handlers k1))
            "old handler removed from rt/tick-handlers")))))

;; ---------------------------------------------------------------------------
;; Stale-tick detection
;; ---------------------------------------------------------------------------

(deftest any-down-includes-stale-test
  (testing "any-down? treats :stale as pausing (same as :down)"
    (supervisor/register-rt!)
    (swap! @#'supervisor/service-state assoc-in [:rt :status] :stale)
    (is (supervisor/any-down? [:rt]) ":stale status triggers any-down?")))

(deftest check-stale-ticks-marks-stale-test
  (testing "check-stale-ticks! transitions :up service to :stale when ticks are silent"
    (supervisor/register-rt! :stale-threshold-ns 1)  ; 1 ns threshold — always stale
    ;; Manually set the service :up with a very old last-tick-ns
    (swap! @#'supervisor/service-state assoc :rt
           {:status :up :last-check-ms 0 :consecutive-failures 0
            :last-tick-ns (- (System/nanoTime) 1000000000)
            :stale-threshold-ns 1
            :resume-bar-beats 4
            :restore-fn nil})
    (@#'supervisor/check-stale-ticks!)
    (is (= :stale (supervisor/service-status :rt)) "service marked :stale")))

(deftest check-stale-ticks-emits-stale-event-test
  (testing "check-stale-ticks! emits :stale event on the service"
    (let [events (atom [])]
      (supervisor/register-rt! :stale-threshold-ns 1)
      (supervisor/on-event! :rt :stale ::stale-track
                            (fn [p] (swap! events conj p)))
      (swap! @#'supervisor/service-state assoc :rt
             {:status :up :last-check-ms 0 :consecutive-failures 0
              :last-tick-ns (- (System/nanoTime) 1000000000)
              :stale-threshold-ns 1
              :resume-bar-beats 4
              :restore-fn nil})
      (@#'supervisor/check-stale-ticks!)
      (is (seq @events) ":stale event emitted")
      (is (= :rt (:service (first @events)))))))

(deftest check-stale-ticks-no-op-when-down-test
  (testing "check-stale-ticks! does not re-emit when service is already :down"
    (let [events (atom [])]
      (supervisor/register-rt! :stale-threshold-ns 1)
      (supervisor/on-event! :rt :stale ::stale-track2
                            (fn [p] (swap! events conj p)))
      (swap! @#'supervisor/service-state assoc :rt
             {:status :down :last-check-ms 0 :consecutive-failures 1
              :last-tick-ns (- (System/nanoTime) 1000000000)
              :stale-threshold-ns 1
              :resume-bar-beats 4
              :restore-fn nil})
      (@#'supervisor/check-stale-ticks!)
      (is (empty? @events) "no :stale event when already :down"))))

(deftest watchdog-detects-stale-ticks-test
  (testing "watchdog loop calls check-stale-ticks! and transitions to :stale"
    (supervisor/register-rt! :stale-threshold-ns 1)
    ;; Manually force the service to :up with a stale tick timestamp
    (swap! @#'supervisor/service-state assoc :rt
           {:status :up :last-check-ms 0 :consecutive-failures 0
            :last-tick-ns (- (System/nanoTime) 1000000000)
            :stale-threshold-ns 1
            :resume-bar-beats 4
            :restore-fn nil})
    (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
    (is (poll-until #(= :stale (supervisor/service-status :rt))) "watchdog transitioned service to :stale")
    (supervisor/stop-watchdog!)))

;; ---------------------------------------------------------------------------
;; Stale self-recovery (ticks resume without a crash)
;; ---------------------------------------------------------------------------

(deftest check-stale-ticks-self-recovery-transitions-up-test
  (testing "check-stale-ticks! transitions :stale → :up when ticks resume within threshold"
    (let [restored (atom false)]
      (supervisor/register-rt! :stale-threshold-ns 1000000000
                                :restore-fn #(reset! restored true))
      ;; Service is stale but last-tick-ns is fresh (age << threshold)
      (swap! @#'supervisor/service-state assoc :rt
             {:status              :stale
              :last-check-ms       0
              :consecutive-failures 0
              :last-tick-ns        (System/nanoTime)
              :stale-threshold-ns  1000000000
              :resume-bar-beats    4
              :restore-fn          #(reset! restored true)})
      (@#'supervisor/check-stale-ticks!)
      (is (= :up (supervisor/service-status :rt)) "transitioned :stale → :up on self-recovery")
      (is (true? @restored) "restore-fn called on self-recovery"))))

(deftest check-stale-ticks-self-recovery-emits-up-test
  (testing "check-stale-ticks! emits :up event on self-recovery with :self-recovered marker"
    (let [up-events (atom [])]
      (supervisor/register-rt!)
      (supervisor/on-event! :rt :up ::self-recovery-track
                            (fn [p] (swap! up-events conj p)))
      (swap! @#'supervisor/service-state assoc :rt
             {:status              :stale
              :last-check-ms       0
              :consecutive-failures 0
              :last-tick-ns        (System/nanoTime)
              :stale-threshold-ns  1000000000
              :resume-bar-beats    4
              :restore-fn          nil})
      (@#'supervisor/check-stale-ticks!)
      (is (seq @up-events) ":up event emitted on self-recovery")
      (is (:self-recovered (first @up-events)) "event carries :self-recovered marker"))))

(deftest check-stale-ticks-no-up-from-stale-when-ticks-old-test
  (testing "check-stale-ticks! does not recover from :stale when ticks are still silent"
    (supervisor/register-rt! :stale-threshold-ns 1)
    (swap! @#'supervisor/service-state assoc :rt
           {:status              :stale
            :last-check-ms       0
            :consecutive-failures 0
            :last-tick-ns        (- (System/nanoTime) 1000000000)  ; 1 second old > 1 ns threshold
            :stale-threshold-ns  1
            :resume-bar-beats    4
            :restore-fn          nil})
    (@#'supervisor/check-stale-ticks!)
    (is (= :stale (supervisor/service-status :rt)) "still :stale when ticks remain old")))

(deftest any-down-clears-after-self-recovery-test
  (testing "any-down? returns nil after self-recovery transitions :stale → :up"
    (supervisor/register-rt!)
    (swap! @#'supervisor/service-state assoc :rt
           {:status              :stale
            :last-check-ms       0
            :consecutive-failures 0
            :last-tick-ns        (System/nanoTime)
            :stale-threshold-ns  1000000000
            :resume-bar-beats    4
            :restore-fn          nil})
    (@#'supervisor/check-stale-ticks!)
    (is (nil? (supervisor/any-down? [:rt])) "any-down? is nil after self-recovery")))

(deftest watchdog-self-recovery-test
  (testing "watchdog transitions :stale → :up when ticks resume"
    (supervisor/register-rt! :stale-threshold-ns 1000000000)
    (swap! @#'supervisor/service-state assoc :rt
           {:status              :stale
            :last-check-ms       0
            :consecutive-failures 0
            :last-tick-ns        (System/nanoTime)
            :stale-threshold-ns  1000000000
            :resume-bar-beats    4
            :restore-fn          nil})
    (supervisor/start-watchdog! :interval-ms 50 :monitor-loops? false)
    (is (poll-until #(= :up (supervisor/service-status :rt))) "watchdog detected self-recovery")
    (supervisor/stop-watchdog!)))

;; ---------------------------------------------------------------------------
;; schedule-recovery!
;; ---------------------------------------------------------------------------

(deftest schedule-recovery-uses-stored-bar-beats-test
  (testing "schedule-recovery! uses resume-bar-beats from register-rt! when not supplied"
    ;; We test this indirectly: the function must not throw when called with a
    ;; :rt service that has no stored socket-path (logs a warning and exits cleanly).
    (supervisor/register-rt! :resume-bar-beats 2)
    (is (= 2 (get-in @@#'supervisor/service-state [:rt :resume-bar-beats])))
    ;; schedule-recovery! spawns a daemon thread; with no socket-path it logs + returns
    (is (some? (supervisor/schedule-recovery! :rt)))))

(deftest register-kairos-delegates-to-register-rt-test
  (testing "register-kairos! delegates to register-rt! (backward compat)"
    (supervisor/register-kairos!)
    ;; register-rt! puts the :rt key in service-state, not :kairos
    (is (= :unknown (supervisor/service-status :rt)))
    (is (nil? (get @#'supervisor/service-state :kairos))
        "register-kairos! no longer creates :kairos state directly")))

;; ---------------------------------------------------------------------------
;; schedule-recovery! — retry robustness
;; ---------------------------------------------------------------------------

(deftest schedule-recovery-passes-retry-20-test
  (testing "schedule-recovery! passes :retry 20 to rt/connect! for slow-starting backends"
    (supervisor/register-rt! :resume-bar-beats 4)
    (let [done       (promise)
          retry-seen (atom nil)
          beat-n     (atom 0)]
      (with-redefs [rt/estimated-beat  (fn [] (if (= 1 (swap! beat-n inc)) 100.0 200.0))
                    rt/connection-opts (fn [] {:socket-path "/tmp/fake.sock" :capabilities {}})
                    rt/connect!        (fn [_sp _caps & {:keys [retry]}]
                                         (reset! retry-seen retry)
                                         (deliver done :connected)
                                         true)]
        (supervisor/schedule-recovery! :rt)
        (deref done 2000 :timeout))
      (is (= 20 @retry-seen) "connect! receives :retry 20"))))

(deftest schedule-recovery-sets-error-on-connect-failure-test
  (testing "schedule-recovery! sets [:rt :status] :error when all connect retries fail"
    (supervisor/register-rt! :resume-bar-beats 4)
    (let [done   (promise)
          beat-n (atom 0)]
      (with-redefs [rt/estimated-beat  (fn [] (if (= 1 (swap! beat-n inc)) 100.0 200.0))
                    rt/connection-opts (fn [] {:socket-path "/tmp/fake.sock" :capabilities {}})
                    rt/connect!        (fn [& _]
                                         (deliver done :failed)
                                         (throw (ex-info "connection refused" {})))]
        (supervisor/schedule-recovery! :rt)
        (deref done 2000 :timeout))
      ;; The catch block (deliver + throw + runtime/set!) runs just after `done`
      ;; is delivered; poll for the :error transition rather than a fixed sleep.
      (is (poll-until #(= :error (runtime/get [:rt :status])))
          "[:rt :status] set to :error after exhausted retries"))))

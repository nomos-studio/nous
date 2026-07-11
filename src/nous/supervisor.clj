; SPDX-License-Identifier: EPL-2.0
(ns nous.supervisor
  "Lightweight process supervisor for live performance reliability.

  Watches registered services (SC, sidecar, loop threads), emits lifecycle
  events on status changes, optionally auto-restarts failed services, and
  restores last-known state on recovery.

  Live loops can declare service dependencies via `:pause-on-down` in their
  opts map.  When a dependency is `:down`, the loop sleeps for one bar and
  retries — maintaining virtual time continuity without playing into silence.
  On recovery the loop re-enters aligned to the next `:resume-on-bar` boundary
  (default 4 beats), so it snaps back cleanly at a musically sensible point.

  ## Lifecycle events

    :up             — service transitioned from :down/:unknown/:stale to :up
    :down           — service transitioned from :up/:unknown to :down
    :stale          — RT backend tick silence exceeded threshold (connection open but hung)
    :restore-failed — restore-fn threw after a recovery

  ## Quick start

    ;; Register SC (health check + synthdef restore on reconnect)
    (supervisor/register-sc!)

    ;; Register sidecar (health check + auto-restart with saved opts)
    (supervisor/register-sidecar!)

    ;; Watch loops for dead threads and emit events
    (supervisor/start-watchdog! :monitor-loops? true)

    ;; Pause :voice-a while SC is down, resume on 4-beat boundary
    (deflive-loop :voice-a {:pause-on-down [:sc]}
      (play! ...)
      (sleep! 3))

    ;; React to a service going down
    (supervisor/on-event! :sc :down ::my-handler
      (fn [_] (println \"SC went down — pausing arc\")))

  ## Service registration

    (supervisor/register! :my-service
      :check-fn    #(thing/connected?)
      :restart-fn  #(thing/reconnect!)
      :restore-fn  #(thing/reload-state!))"
  (:require [nous.loop    :as loop-ns]
            [nous.core    :as core]
            [nous.kairos  :as kairos]
            [nous.rt      :as rt]
            [nous.runtime :as runtime]
            [nous.sc      :as sc]))

;; ---------------------------------------------------------------------------
;; Event bus
;; ---------------------------------------------------------------------------

(defonce event-handlers
  ;; Shape: {service-name {event-type {handler-key handler-fn}}}
  (atom {}))

(defn on-event!
  "Register handler-fn to be called when service-name emits event-type.
  handler-key identifies the registration for later removal.
  Returns handler-key.

  event-type: :up, :down, :restore-failed"
  [service-name event-type handler-key handler-fn]
  (swap! event-handlers assoc-in [service-name event-type handler-key] handler-fn)
  handler-key)

(defn off-event!
  "Deregister a handler previously registered with on-event!."
  [service-name event-type handler-key]
  (swap! event-handlers update-in [service-name event-type] dissoc handler-key)
  nil)

(defn- emit!
  "Emit an event to all registered handlers. Swallows handler exceptions."
  [service-name event-type payload]
  (doseq [[_ f] (get-in @event-handlers [service-name event-type] {})]
    (try (f payload)
         (catch Throwable e
           (binding [*out* *err*]
             (println (str "[supervisor] handler error for "
                           (name service-name) "/" (name event-type)
                           " (" (.getSimpleName (class e)) "): "
                           (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Service registry
;; ---------------------------------------------------------------------------

;; Key returned by rt/on-tick! for the RT tick-heartbeat tracker.
;; nil means no handler registered yet.
(defonce ^:private rt-tick-key (atom nil))

(defonce services
  ;; Shape: {service-name {:check-fn :restart-fn :restore-fn}}
  (atom {}))

(defonce service-state
  ;; Shape: {service-name {:status :up/:down/:unknown/:stale
  ;;                       :last-check-ms 0
  ;;                       :consecutive-failures 0
  ;;                       ;; RT services also carry:
  ;;                       :last-tick-ns nil          ; nanoTime of last tick received
  ;;                       :stale-threshold-ns nil    ; silence before marking :stale
  ;;                       :resume-bar-beats nil      ; bar granularity for reconnect
  ;;                       :restore-fn nil}}          ; called on recovery (crash or self)
  (atom {}))

(defn register!
  "Register a supervised service.

  Options:
    :check-fn   — (fn []) → truthy if healthy.  Called every watchdog tick.
    :restart-fn — (fn []) → called when service goes :down.  Should attempt
                  reconnect.  May throw — the watchdog catches and logs.
    :restore-fn — (fn []) → called after a successful recovery to restore
                  last-known state (e.g. re-send synthdefs to SC).

  Call register! before start-watchdog! so the first check has a clean baseline."
  [service-name & {:keys [check-fn restart-fn restore-fn]}]
  (swap! services assoc service-name
         {:check-fn check-fn :restart-fn restart-fn :restore-fn restore-fn})
  (swap! service-state assoc service-name
         {:status :unknown :last-check-ms 0 :consecutive-failures 0})
  service-name)

(defn deregister!
  "Remove a service from supervision."
  [service-name]
  (swap! services dissoc service-name)
  (swap! service-state dissoc service-name)
  nil)

(defn service-status
  "Return the current known status of a service: :up, :down, or :unknown."
  [service-name]
  (get-in @service-state [service-name :status] :unknown))

(defn any-down?
  "Return truthy if any of the named services are :down or :stale.
  :stale means the RT backend has gone quiet (ticks stopped) without crashing.
  Used by the deflive-loop :pause-on-down machinery."
  [service-names]
  (some #(#{:down :stale} (service-status %)) service-names))

(defn all-statuses
  "Return a map of service-name → status for all registered services."
  []
  (reduce-kv (fn [m k v] (assoc m k (:status v))) {} @service-state))

;; ---------------------------------------------------------------------------
;; Health-check and state-transition logic
;; ---------------------------------------------------------------------------

(defn- log [& args]
  (binding [*out* *err*]
    (println (apply str "[supervisor] " args))))

(defn- try-restart! [service-name restart-fn]
  (try
    (restart-fn)
    (log (name service-name) " restart attempted")
    true
    (catch Throwable e
      (log (name service-name) " restart failed ("
           (.getSimpleName (class e)) "): " (.getMessage e))
      false)))

(defn- try-restore! [service-name restore-fn]
  (try
    (restore-fn)
    (log (name service-name) " state restored")
    true
    (catch Throwable e
      (log (name service-name) " restore failed ("
           (.getSimpleName (class e)) "): " (.getMessage e))
      (emit! service-name :restore-failed
             {:service service-name :error (.getMessage e)})
      false)))

(defn- transition-up! [service-name restore-fn now-ms extra-payload]
  (swap! service-state update service-name
         assoc :status :up :consecutive-failures 0)
  (when restore-fn (try-restore! service-name restore-fn))
  (emit! service-name :up (merge {:service service-name :at now-ms} extra-payload))
  (log (name service-name) " UP"))

(defn- transition-down! [service-name now-ms]
  (swap! service-state update service-name
         assoc :status :down :consecutive-failures 1)
  (emit! service-name :down {:service service-name :at now-ms})
  (log (name service-name) " DOWN"))

(defn- check-stale-ticks!
  "Called from the watchdog loop to detect tick silence and self-recovery.

  :up + silence > threshold  → transition to :stale; loops pause.
  :stale + fresh ticks       → transition back to :up; calls restore-fn.
                               Handles self-recovery when the backend resumes
                               ticking without crashing (BEAM never fires)."
  []
  (doseq [[sname st] @service-state]
    (let [{:keys [status last-tick-ns stale-threshold-ns restore-fn]} st]
      (when (and last-tick-ns stale-threshold-ns)
        (let [age-ns (- (System/nanoTime) last-tick-ns)
              now-ms (System/currentTimeMillis)]
          (cond
            (and (= :up status) (> age-ns stale-threshold-ns))
            (do (log (name sname) " tick silence " (long (/ age-ns 1000000)) "ms — :stale")
                (swap! service-state assoc-in [sname :status] :stale)
                (emit! sname :stale {:service sname :at now-ms
                                     :silence-ms (long (/ age-ns 1000000))}))

            (and (= :stale status) (<= age-ns stale-threshold-ns))
            (do (log (name sname) " ticks resumed — recovering from :stale")
                (transition-up! sname restore-fn now-ms {:self-recovered true}))))))))

;; ---------------------------------------------------------------------------
;; Convenience registrations for built-in services
;; ---------------------------------------------------------------------------

(defn register-sc!
  "Register the SC server as a supervised service.

  Reactive: watches [:sc :status] in the runtime tree — no poll thread.
  sc.clj publishes :running / :stopped / :error / :starting to that path on
  every connection state change.

  On :running  — transitions :sc to :up, re-sends all loaded SynthDefs.
  On :stopped/:error — transitions :sc to :down, calls sc-restart! (or
                       :restart-fn if supplied).

  Pass :restart-fn to override the restart behaviour (e.g. when SC is managed
  externally and sc-restart! is not appropriate)."
  [& {:keys [restart-fn]}]
  (swap! service-state assoc :sc {:status :unknown :last-check-ms 0 :consecutive-failures 0})
  ;; Idempotent — remove any prior registration before re-watching.
  (runtime/unwatch! ::sc-status-watcher)
  (runtime/watch! [:sc :status] ::sc-status-watcher
    (fn [_path _old new-status]
      (let [now-ms   (System/currentTimeMillis)
            prev     (get-in @service-state [:sc :status])
            rfn      (or restart-fn sc/sc-restart!)]
        (case new-status
          :running
          (when (not= :up prev)
            (transition-up! :sc sc/resend-sent-synthdefs! now-ms {}))
          (:stopped :error)
          (when (not= :down prev)
            (transition-down! :sc now-ms)
            (try-restart! :sc rfn))
          nil)))))

(defn register-rt!
  "Register the nomos-rt backend (aion or kairos) as a supervised service.

  There is always at most one nomos-rt backend active at a time.  This single
  registration covers both aion and kairos — which is running is described by
  capabilities, not the service name.

  Reactive: watches [:rt :status] in the runtime tree — no poll thread.
  nous.rt/connect! publishes :connected; nous.rt/disconnect! publishes
  :disconnected.  jinterface calls rt/disconnect! on any service_down, which
  fires this watcher regardless of whether the crashed backend was aion or kairos.
  Tick-silence aware: registers an on-tick! handler tracking the last received
  clock tick.  If tick silence exceeds stale-threshold-ns the service is marked
  :stale and live loops with {:pause-on-down [:rt]} pause at their next sleep
  boundary.  Reconnect is NOT triggered by stale detection — the BEAM heartbeat
  timeout kills and restarts the native process, which then signals recovery
  via jinterface → schedule-recovery! :rt.

  Options:
    :restore-fn          — called after recovery to reload plugin graph etc.
    :stale-threshold-ns  — tick silence in nanoseconds before :stale
                           (default 10000000000 = 10 s)
    :resume-bar-beats    — bar length in beats for bar-aligned reconnect
                           (default rt/*bar-beats*)

  Example:
    (supervisor/register-rt!)
    ;; or with graph restore:
    (supervisor/register-rt!
      :restore-fn #(kairos/send-graph-load! @my-graph-atom))"
  [& {:keys [restore-fn stale-threshold-ns resume-bar-beats]}]
  (let [threshold (or stale-threshold-ns 10000000000)]
    ;; Remove any existing tick-tracking handler before re-registering.
    (when-let [k @rt-tick-key]
      (rt/off-tick! k)
      (reset! rt-tick-key nil))
    ;; Initialise service-state with tick-tracking fields.
    ;; restore-fn is stored here so check-stale-ticks! can call it on self-recovery.
    (swap! service-state assoc :rt
           {:status               :unknown
            :last-check-ms        0
            :consecutive-failures 0
            :last-tick-ns         nil
            :stale-threshold-ns   threshold
            :resume-bar-beats     (or resume-bar-beats rt/*bar-beats*)
            :restore-fn           restore-fn})
    ;; Track the last tick wall time so check-stale-ticks! can detect silence.
    (reset! rt-tick-key
            (rt/on-tick! (fn [_]
                           (swap! service-state assoc-in [:rt :last-tick-ns]
                                  (System/nanoTime)))))
    ;; React to [:rt :status] transitions published by nous.rt/connect! and disconnect!.
    ;; :connected   → :up (also handles :stale → :up after recovery)
    ;; :disconnected/:error/:stopped → :down
    (runtime/unwatch! ::rt-status-watcher)
    (runtime/watch! [:rt :status] ::rt-status-watcher
      (fn [_path _old new-status]
        (let [now-ms (System/currentTimeMillis)
              prev   (get-in @service-state [:rt :status])]
          (case new-status
            :connected
            (when (not= :up prev)
              (transition-up! :rt restore-fn now-ms {}))
            (:disconnected :error :stopped)
            (when (not= :down prev)
              (transition-down! :rt now-ms))
            nil))))
    :rt))

(defn schedule-recovery!
  "Called from jinterface when BEAM notifies that the nomos-rt backend has restarted.
  Waits for the next musically appropriate bar boundary, then reconnects using the
  last known socket-path and capabilities.  rt/connect! publishes
  [:rt :status] :connected on success, which fires the register-rt! watcher
  → transition-up! → restore-fn.

  bar-beats defaults to the value stored by register-rt!, then rt/*bar-beats*.

  Always returns immediately — the wait + connect runs on a daemon thread."
  ([service-name] (schedule-recovery! service-name nil))
  ([service-name bar-beats]
   (let [bb     (or bar-beats
                    (get-in @service-state [service-name :resume-bar-beats])
                    rt/*bar-beats*)
         now    (rt/estimated-beat)
         target (* (Math/ceil (/ (+ now 1.0) (double bb))) (double bb))]
     (doto (Thread.
            (fn []
              (loop []
                (when (< (rt/estimated-beat) target)
                  (Thread/sleep 20)
                  (recur)))
              (rt/disconnect!)
              (let [{:keys [socket-path capabilities]} (rt/connection-opts)]
                (if socket-path
                  (try
                    ;; :retry 20 gives ~9 s of backoff at 500 ms/attempt — enough for
                    ;; slow CLAP plugin loading after a kairos restart.
                    (rt/connect! socket-path capabilities :retry 20)
                    (catch Exception e
                      (log "rt recovery: connect failed after retries — manual restart needed: "
                           (.getMessage e))
                      (runtime/set! [:rt :status] :error)))
                  (log "rt recovery: no stored socket-path — cannot reconnect"))))
            "nous-supervisor-rt-recovery")
       (.setDaemon true)
       (.start)))))

(defn register-kairos!
  "Deprecated. Use register-rt! for unified nomos-rt backend supervision.
  The :restart-fn option is ignored — BEAM owns the native process lifecycle."
  [& {:keys [restore-fn]}]
  (register-rt! :restore-fn restore-fn))

(defn register-sidecar!
  "Deprecated. Delegates to register-rt! — the sidecar is retired."
  []
  (register-rt!))

(defn- check-service! [service-name]
  (when-let [{:keys [check-fn restart-fn restore-fn]} (get @services service-name)]
    ;; Services without check-fn are reactive (e.g. :sc via runtime/watch!) — skip poll.
    (when check-fn
      (let [now-ms   (System/currentTimeMillis)
            prev     (get @service-state service-name)
            healthy? (try (boolean (check-fn)) (catch Throwable _ false))]
        (swap! service-state update service-name assoc :last-check-ms now-ms)
        (cond
          ;; Healthy — was not :up
          (and healthy? (not= :up (:status prev)))
          (transition-up! service-name restore-fn now-ms {})

          ;; Unhealthy — was not :down
          (and (not healthy?) (not= :down (:status prev)))
          (do (transition-down! service-name now-ms)
              (when restart-fn
                (when (try-restart! service-name restart-fn)
                  ;; Re-check immediately after restart attempt
                  (let [recovered? (try (boolean (check-fn)) (catch Throwable _ false))]
                    (when recovered?
                      (transition-up! service-name restore-fn
                                      (System/currentTimeMillis) {:restarted true}))))))

          ;; Unhealthy — already :down
          (and (not healthy?) (= :down (:status prev)))
          (swap! service-state update service-name update :consecutive-failures inc)

          ;; Otherwise: status unchanged — no transition
          :else nil)))))

;; ---------------------------------------------------------------------------
;; Loop thread monitoring
;; ---------------------------------------------------------------------------

(defn- check-loops! []
  (when-let [sref (loop-ns/-system-ref)]
    (when-let [sys @sref]
      (doseq [[loop-name entry] (:loops @sys)]
        (when (and @(:running? entry)
                   (when-let [t ^Thread (:thread entry)]
                     (not (.isAlive t))))
          (emit! :loops :down {:loop loop-name :at (System/currentTimeMillis)})
          (log "loop " (name loop-name) " thread DIED"))))))

;; ---------------------------------------------------------------------------
;; Watchdog thread
;; ---------------------------------------------------------------------------

(defonce watchdog-atom (atom nil))

(defn running?
  "Return true if the supervisor watchdog is running."
  []
  (boolean (some-> @watchdog-atom :running? deref)))

(defn- watchdog-sleep-ms
  "Compute the watchdog sleep duration in ms.
  When interval-ms is explicit, use it directly.
  Otherwise derive from BPM: bars × beats-per-bar × (60000 / bpm), clamped to
  at least 100 ms so a zero/nil BPM (before start!) is safe.
  beats-per-bar nil means: read from core/get-beats-per-bar each tick."
  [interval-ms bars beats-per-bar]
  (if interval-ms
    interval-ms
    (let [bpm  (or (core/get-bpm) 120)
          bpb  (or beats-per-bar (core/get-beats-per-bar))]
      (max 100 (long (* bars bpb (/ 60000.0 bpm)))))))

(defn start-watchdog!
  "Start the supervisor watchdog thread.

  Options:
    :interval-ms    — fixed check frequency in ms.  When omitted the interval
                      is re-derived from BPM each tick: bars × beats-per-bar ×
                      (60 000 / bpm).  This means a tempo change (set-bpm!) is
                      automatically reflected in the next watchdog sleep.
    :bars           — how many bars constitute one scan period (default 1).
    :beats-per-bar  — beats per bar for the BPM→ms conversion.  When omitted,
                      reads core/get-beats-per-bar on each tick, so
                      (core/set-beats-per-bar! 3) at runtime is automatically
                      reflected without restarting the watchdog.
    :monitor-loops? — whether to check loop thread liveness (default true).

  Wires the pause-check into deflive-loop so that :pause-on-down opts work.
  Call after register! so services have a baseline state before first check."
  [& {:keys [interval-ms bars beats-per-bar monitor-loops?]
      :or   {bars 1 monitor-loops? true}}]
  (when (running?)
    (throw (ex-info "Supervisor watchdog already running; call stop-watchdog! first" {})))
  (loop-ns/-register-pause-check! any-down?)
  (let [running?# (atom true)
        t         (Thread.
                   (fn []
                     (while @running?#
                       (try
                         (doseq [sname (keys @services)]
                           (check-service! sname))
                         (check-stale-ticks!)
                         (when monitor-loops?
                           (check-loops!))
                         (catch Throwable e
                           (log "watchdog tick error (" (.getSimpleName (class e))
                                "): " (.getMessage e))))
                       (try (Thread/sleep (long (watchdog-sleep-ms interval-ms bars beats-per-bar)))
                            (catch InterruptedException _)))))]
    (.setDaemon t true)
    (.setName t "nous-supervisor-watchdog")
    (reset! watchdog-atom {:thread t :running? running?#
                           :interval-ms interval-ms
                           :bars bars :beats-per-bar beats-per-bar})
    (.start t)
    :started))

(defn stop-watchdog!
  "Stop the supervisor watchdog and remove the pause-check hook from deflive-loop."
  []
  (when-let [{:keys [^Thread thread running?]} @watchdog-atom]
    (reset! running? false)
    (.interrupt thread)
    (reset! watchdog-atom nil))
  (loop-ns/-register-pause-check! nil)
  :stopped)

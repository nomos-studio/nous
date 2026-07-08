; SPDX-License-Identifier: EPL-2.0
(ns nous.link
  "nous beat-clock integration — receives 24 PPQN ticks from kairos/aion.

  kairos and aion are always Link peers; this namespace subscribes to
  MSG-TICK (0x50) pushes via nous.kairos/on-tick! and derives beat position,
  BPM, and a timeline compatible with nous.loop/sleep!.

  ## Quick start
    (require '[nous.link :as link])
    (link/active?)          ; => true when kairos/aion is connected and ticking
    (link/bpm)              ; => estimated session BPM
    (link/playing?)         ; => true when ticks are arriving
    (link/link-timeline)    ; => {:bpm N :beat0-beat N :beat0-epoch-ms N}

  ## How it works
  kairos/aion push MSG-TICK at 24 PPQN over the IPC channel. Each tick
  carries {:beat N :tick-n N} (plus :mods when modulators are running).
  This namespace uses consecutive tick arrivals to estimate BPM, anchors a
  timeline to each tick, and fires transport-change hooks on start/stop
  transitions.

  nous.loop/sleep! checks (link/active?) and, when true, uses link-timeline
  (the :current component of :link-time-id) to compute its wall-clock deadline.

  ## Imperative transport calls
  set-bpm!, start-transport!, stop-transport! send MSG-LINK-SET-TEMPO (0x38),
  MSG-LINK-START-TRANSPORT (0x39), and MSG-LINK-STOP-TRANSPORT (0x3A) to kairos
  respectively. enable! and disable! are no-ops — kairos/aion own the Link
  peer and it is always active.

  Key design decisions: Q7 (Link integration), §15 of R&R doc."
  (:require [nous.clock  :as clock]
            [nous.kairos :as kairos])
  (:import  [java.util.concurrent.locks LockSupport]))

;; ---------------------------------------------------------------------------
;; System state reference (injected by nous.core/start!)
;; ---------------------------------------------------------------------------

(def ^:private system-ref (atom nil))

(defn -register-system!
  "Called by nous.core/start! to inject the system-state atom.
  Not part of the public API."
  [state-atom]
  (reset! system-ref state-atom))

;; ---------------------------------------------------------------------------
;; Transport-change hook registry
;; ---------------------------------------------------------------------------

(defonce ^:private transport-hook-registry (atom {}))

;; ---------------------------------------------------------------------------
;; BPM estimation from tick intervals
;;
;; At 24 PPQN, consecutive ticks are (60 / bpm / 24) seconds apart.
;; We maintain a rolling window of (beat, epoch-ms) pairs and derive BPM
;; from the slope across the window to smooth out jitter.
;;
;; bpm-change-threshold — minimum BPM delta that triggers the correction
;; policy. Changes below this are treated as normal estimation noise.
;; ---------------------------------------------------------------------------

(def ^:private tick-window-size    8)
(def ^:private tick-window         (atom (clojure.lang.PersistentQueue/EMPTY)))
(def ^:private bpm-change-threshold 0.5)

(defn- enqueue-tick-sample [beat epoch-ms]
  (swap! tick-window
         (fn [q]
           (let [q' (conj q {:beat beat :epoch-ms epoch-ms})]
             (if (> (count q') tick-window-size)
               (pop q')
               q')))))

(defn- estimate-bpm
  "Estimate BPM from the oldest and newest sample in the window.
  Returns nil when insufficient data."
  []
  (let [samples (seq @tick-window)]
    (when (>= (count samples) 2)
      (let [oldest (first samples)
            newest (last  samples)
            db     (- (:beat newest)     (:beat oldest))
            dt-s   (/ (- (:epoch-ms newest) (:epoch-ms oldest)) 1000.0)]
        (when (and (pos? db) (pos? dt-s))
          (* 60.0 (/ db dt-s)))))))

;; ---------------------------------------------------------------------------
;; Quantum alignment helpers
;; ---------------------------------------------------------------------------

(defn next-quantum-beat
  "Return the beat number of the next quantum boundary strictly after beat.
  Used by deflive-loop to delay loop start until the next bar boundary."
  [beat quantum]
  (let [q (double quantum)
        b (double beat)]
    (* q (Math/ceil (/ b q)))))

;; ---------------------------------------------------------------------------
;; MSG-TICK subscription
;; ---------------------------------------------------------------------------

(defn- fire-transport-hooks [playing?]
  (doseq [[_ hook-fn] @transport-hook-registry]
    (try (hook-fn playing?)
         (catch Exception e
           (binding [*out* *err*]
             (println "[link] transport hook error:" (.getMessage e)))))))

(defn- next-bar-beat
  "Return the next bar-boundary beat strictly after current-beat."
  [current-beat]
  (let [q (double (or (when-let [s @system-ref]
                        (get-in @s [:config :link/quantum]))
                      4))]
    (next-quantum-beat current-beat q)))

(defn- on-tick
  "Handler called on every 24 PPQN tick pushed by kairos/aion.
  tick-ev — {:beat N :tick-n N} (plus :mods when modulators are running).

  Applies the correction policy stored at [:config :link-correction-policy]:
    :bar-quantize (default) — significant BPM changes go to :pending until the
      next bar boundary, then promote to :current. Anchor is updated every tick.
    :snap — always apply BPM changes immediately; :pending is never populated."
  [{:keys [beat]}]
  (when beat
    (let [now-ms  (System/currentTimeMillis)
          _       (enqueue-tick-sample beat now-ms)
          est-bpm (estimate-bpm)]
      (when-let [s @system-ref]
        (let [prev-bpm (get-in @s [:link-state :bpm])]
          (swap! s (fn [state]
                     (let [tid        (:link-time-id state)
                           policy     (get-in state [:config :link-correction-policy]
                                              :bar-quantize)
                           first?     (nil? tid)
                           ;; Use the reported tick beat directly — it is the Link
                           ;; beat position, more authoritative than wall-clock derive.
                           ;; Track whether promotion fired so we don't also start a
                           ;; new pending on the very same tick (the window sample
                           ;; enqueued before BPM estimation spans the bar boundary,
                           ;; producing an unreliable estimate for that one tick).
                           promoted?  (boolean (and tid
                                                    (:pending tid)
                                                    (>= beat (:apply-at (:pending tid)))))
                           tid'       (if promoted?
                                        {:current (assoc (:timeline (:pending tid))
                                                         :beat0-beat     beat
                                                         :beat0-epoch-ms now-ms)
                                         :pending nil}
                                        tid)
                           active-bpm (:bpm (:current tid'))
                           bpm-changed? (and est-bpm
                                             active-bpm
                                             (> (Math/abs (- est-bpm active-bpm))
                                                bpm-change-threshold))
                           new-tid    (cond
                                        (nil? est-bpm)
                                        tid'

                                        ;; First tick, snap policy, just promoted, or
                                        ;; change within threshold: apply immediately
                                        (or first? promoted? (= policy :snap) (not bpm-changed?))
                                        {:current {:bpm            est-bpm
                                                   :beat0-beat     beat
                                                   :beat0-epoch-ms now-ms}
                                         :pending nil}

                                        ;; Significant BPM change under :bar-quantize:
                                        ;; update anchor in :current but defer new BPM
                                        :else
                                        (-> tid'
                                            (assoc-in [:current :beat0-beat]     beat)
                                            (assoc-in [:current :beat0-epoch-ms] now-ms)
                                            (assoc :pending
                                                   {:timeline {:bpm            est-bpm
                                                               :beat0-beat     beat
                                                               :beat0-epoch-ms now-ms}
                                                    :policy   :bar-quantize
                                                    :apply-at (next-bar-beat beat)})))]
                       (-> state
                           (assoc :link-time-id new-tid)
                           (assoc :link-state
                                  (merge (or (:link-state state) {})
                                         {:playing true :last-tick-ms now-ms}
                                         (when est-bpm {:bpm est-bpm})))))))
          (doseq [[_ entry] (:loops @s)]
            (when-let [^Thread t (:thread entry)]
              (LockSupport/unpark t)))
          (when (and (nil? prev-bpm) est-bpm)
            (fire-transport-hooks true)))))))

(def ^:private tick-key (kairos/on-tick! on-tick))

;; ---------------------------------------------------------------------------
;; Playing state — based on recency of last tick
;; ---------------------------------------------------------------------------

(def ^:private stale-tick-ms 500)

(defn- ticks-arriving?
  []
  (when-let [s @system-ref]
    (when-let [last-ms (get-in @s [:link-state :last-tick-ms])]
      (< (- (System/currentTimeMillis) last-ms) stale-tick-ms))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn active?
  "Return true when kairos/aion is connected and ticks are arriving."
  []
  (boolean (and (kairos/connected?) (ticks-arriving?))))

(defn link-timeline
  "Return the current beat timeline map, or nil when inactive.
  Shape: {:bpm N :beat0-beat N :beat0-epoch-ms N}
  Returns the :current component of :link-time-id — the in-effect BPM,
  not any pending deferred value. Used by nous.loop/sleep!."
  []
  (when (active?)
    (when-let [s @system-ref] (get-in @s [:link-time-id :current]))))

(defn link-state
  "Return the most recently derived link state map, or nil."
  []
  (when-let [s @system-ref] (:link-state @s)))

(defn bpm
  "Return the estimated session BPM, or nil when inactive."
  []
  (:bpm (link-state)))

(defn peers
  "Return the number of connected Link peers.
  Currently always nil — kairos/aion do not yet push peer count."
  []
  (:peers (link-state)))

(defn playing?
  "Return true when ticks are actively arriving from kairos/aion."
  []
  (boolean (ticks-arriving?)))

;; ---------------------------------------------------------------------------
;; Vestigial imperative calls
;;
;; These previously sent IPC commands to the sidecar's Link engine.
;; kairos/aion own the Link peer and it is always on. Preserved for
;; call-site compatibility; pending explicit kairos MSG types.
;; ---------------------------------------------------------------------------

(defn enable!
  "Store the Link correction policy in system config.
  kairos/aion own the Link peer; it is always active.
  Previously: joined or created a Link session via the sidecar.

  Options:
    :policy — :bar-quantize (default) or :snap"
  [& {:keys [policy] :or {policy :bar-quantize}}]
  (println "[link] enable! stores correction policy —" policy)
  (when-let [s @system-ref]
    (swap! s assoc-in [:config :link-correction-policy] policy)))

(defn disable!
  "Clears local :link-state and :link-time-id from system-state.
  Does not stop kairos/aion — the peer continues running.
  Previously: left the Link session via the sidecar."
  []
  (println "[link] disable! is vestigial — clearing local link state only")
  (when-let [s @system-ref]
    (swap! s dissoc :link-state :link-time-id)))

(defn set-bpm!
  "Propose a new tempo to the Ableton Link session via kairos.

  bpm — target BPM (positive number); subject to Link consensus."
  [bpm]
  (kairos/send-link-set-tempo! bpm))

(defn start-transport!
  "Request transport start via the Ableton Link session in kairos."
  []
  (kairos/send-link-start-transport!))

(defn stop-transport!
  "Request transport stop via the Ableton Link session in kairos."
  []
  (kairos/send-link-stop-transport!))

;; ---------------------------------------------------------------------------
;; Transport-change hooks
;; ---------------------------------------------------------------------------

(defn on-transport-change!
  "Register a hook called when the Link transport state changes.

  hook-key — any value, used to remove the hook
  f        — (fn [playing?]) called synchronously on the kairos reader thread

  Example:
    (link/on-transport-change! ::my-hook
      (fn [playing?]
        (if playing? (session!) (end-session!))))"
  [hook-key f]
  (swap! transport-hook-registry assoc hook-key f)
  nil)

(defn remove-transport-hook!
  "Remove the transport-change hook identified by hook-key. No-op if absent."
  [hook-key]
  (swap! transport-hook-registry dissoc hook-key)
  nil)

(defn transport-hooks
  "Return a snapshot of the currently registered transport-change hooks."
  []
  @transport-hook-registry)


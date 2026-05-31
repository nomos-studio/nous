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

  nous.loop/sleep! checks (link/active?) and, when true, uses :link-timeline
  to compute its wall-clock deadline — same contract as before, different source.

  ## Vestigial imperative calls
  enable!, disable!, set-bpm!, start-transport!, stop-transport! previously
  sent IPC commands to the sidecar's Link engine. kairos/aion own the Link
  peer and it is always on; those calls are preserved as no-ops with
  deprecation notices until explicit kairos MSG types are defined.

  Key design decisions: Q7 (Link integration), §15 of R&R doc."
  (:require [nous.kairos :as kairos])
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
;; ---------------------------------------------------------------------------

(def ^:private tick-window-size 8)
(def ^:private tick-window      (atom (clojure.lang.PersistentQueue/EMPTY)))

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
;; MSG-TICK subscription
;; ---------------------------------------------------------------------------

(defn- fire-transport-hooks [playing?]
  (doseq [[_ hook-fn] @transport-hook-registry]
    (try (hook-fn playing?)
         (catch Exception e
           (binding [*out* *err*]
             (println "[link] transport hook error:" (.getMessage e)))))))

(defn- on-tick
  "Handler called on every 24 PPQN tick pushed by kairos/aion.
  tick-ev — {:beat N :tick-n N} (plus :mods when modulators are running)."
  [{:keys [beat]}]
  (when beat
    (let [now-ms  (System/currentTimeMillis)
          _       (enqueue-tick-sample beat now-ms)
          est-bpm (estimate-bpm)
          timeline (when est-bpm
                     {:bpm            est-bpm
                      :beat0-beat     beat
                      :beat0-epoch-ms now-ms})]
      (when-let [s @system-ref]
        (let [prev-bpm (get-in @s [:link-state :bpm])]
          (swap! s (fn [state]
                     (cond-> state
                       est-bpm (assoc :link-timeline timeline)
                       true    (assoc :link-state
                                      (merge (or (:link-state state) {})
                                             {:playing true
                                              :last-tick-ms now-ms}
                                             (when est-bpm {:bpm est-bpm}))))))
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
  Used by nous.loop/sleep! to compute beat→epoch-ms."
  []
  (when (active?)
    (when-let [s @system-ref] (:link-timeline @s))))

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
  "No-op. kairos/aion own the Link peer; it is always active.
  Previously: joined or created a Link session via the sidecar."
  [& _]
  (println "[link] enable! is vestigial — kairos/aion own the Link peer"))

(defn disable!
  "Clears local :link-state and :link-timeline from system-state.
  Does not stop kairos/aion — the peer continues running.
  Previously: left the Link session via the sidecar."
  []
  (println "[link] disable! is vestigial — clearing local link state only")
  (when-let [s @system-ref]
    (swap! s dissoc :link-state :link-timeline)))

(defn set-bpm!
  "No-op. BPM is owned by the Link session in kairos/aion.
  Previously: proposed a tempo change to the sidecar Link engine.
  Pending a kairos MSG type."
  [_bpm]
  (println "[link] set-bpm! is vestigial — kairos/aion own BPM; not yet wired"))

(defn start-transport!
  "No-op. Transport is owned by the Link session in kairos/aion.
  Previously: sent IPC 0x13 to request transport start.
  Pending a kairos MSG type."
  []
  (println "[link] start-transport! is vestigial — pending kairos MSG type"))

(defn stop-transport!
  "No-op. Transport is owned by the Link session in kairos/aion.
  Previously: sent IPC 0x14 to request transport stop.
  Pending a kairos MSG type."
  []
  (println "[link] stop-transport! is vestigial — pending kairos MSG type"))

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

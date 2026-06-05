; SPDX-License-Identifier: EPL-2.0
(ns nous.defensemble
  "defensemble — inter-voice tension monitor with optional imitative entry.

  defensemble is a pure supervisor: it reads voice pitches and motion
  directions from the ctrl tree and publishes aggregate tension metrics.
  It does not control voices directly.

  ## Quick start — tension monitoring

    ;; Each voice generator must be configured with :voice-name
    (deflattice bass-voice  :fundamental :C2 :voice-name :bass  ...)
    (defexcursion soprano-arc :fundamental :C4 :voice-name :soprano ...)

    ;; Define the ensemble monitor
    (defensemble string-quartet
      :voices [:bass :soprano]
      :consonance-horizon 6.5)

    ;; Inspect tension at any time
    (ensemble-tension string-quartet)  ;=> 0.0–1.0

  ## Josquin-style imitative entry

    ;; Leader voice runs its own loop
    (deflattice leader :fundamental :C4 :voice-name :voice-1 ...)

    ;; Ensemble with imitation config
    (defensemble josquin-duo
      :voices [:voice-1 :voice-2]
      :imitation {:voice-2 {:follows     :voice-1
                             :interval    7    ; semitones up (perfect fifth)
                             :delay-steps 8}}) ; wait 8 leader steps before entering

    ;; Follower loop uses make-imitation-seq — start it whenever you like;
    ;; it rests automatically until delay-steps leader notes have buffered
    (deflive-loop :follower-loop {}
      (run-step! (make-imitation-seq josquin-duo :voice-2)))

  ## Published ctrl paths

    [:harmony :tension]              — aggregate [0,1] tension
    [:harmony :voice-consonance]     — map of {[v1 v2] tenney-h}
    [:harmony :parallel-pairs]       — set of parallel-motion pair vectors

  ## Tension bands (Tenney H)

    H < 3.5   — fusion risk (unison / octave zone)
    3.5–5.5   — imperfect consonance (target zone for counterpoint)
    H > 5.5   — active dissonance"
  (:require [nomos.maths.harmonic :as h]
            [nous.ctrl            :as ctrl]
            [nous.seq             :as sq]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn ^:no-doc register!
  "Register an ensemble context atom under the given name keyword."
  [k ctx-atom]
  (swap! registry assoc k ctx-atom))

;; ---------------------------------------------------------------------------
;; Voice pair enumeration
;; ---------------------------------------------------------------------------

(defn- voice-pairs
  "All ordered [v1 v2] pairs (v1 before v2 by index) for a voice vector."
  [voices]
  (let [n (count voices)]
    (for [i (range n) j (range (inc i) n)]
      [(nth voices i) (nth voices j)])))

;; ---------------------------------------------------------------------------
;; Consonance and tension
;; ---------------------------------------------------------------------------

(defn- compute-consonance
  "Return a map of {[v1 v2] tenney-h} for all voice pairs in `pitches`."
  [voices pitches]
  (into {}
        (map (fn [[v1 v2]]
               (let [m1   (long (get pitches v1 60))
                     m2   (long (get pitches v2 60))
                     dist (Math/abs (- m1 m2))]
                 [[v1 v2] (h/midi->tenney-h dist)])))
        (voice-pairs voices)))

(defn- compute-tension
  "Mean inter-voice Tenney H normalized to [0,1] over `horizon`."
  ^double [consonance-map ^double horizon]
  (if (empty? consonance-map)
    0.0
    (let [hs   (vals consonance-map)
          mean (/ (double (reduce + 0.0 hs)) (count hs))]
      (min 1.0 (/ mean horizon)))))

(defn- detect-parallel-pairs
  "Return set of [v1 v2] pairs that moved in parallel motion from one perfect
  consonance to another — i.e. both the previous interval AND the current
  interval are below `fusion-threshold`, and both voices moved in the same
  non-zero direction.

  Requires `prev-consonance` (the consonance map from the preceding step) so
  the 'from' interval can be checked. If the pair was not already in the
  fusion zone before the move, it is not flagged even if it arrives there."
  [prev-consonance curr-consonance motions ^double fusion-threshold]
  (into #{}
        (filter (fn [[v1 v2]]
                  (let [from-h (double (get prev-consonance [v1 v2] 99.0))
                        to-h   (double (get curr-consonance [v1 v2] 99.0))
                        d1     (long (get motions v1 0))
                        d2     (long (get motions v2 0))]
                    (and (< from-h fusion-threshold)
                         (< to-h fusion-threshold)
                         (= d1 d2)
                         (not (zero? d1))))))
        (keys curr-consonance)))

;; ---------------------------------------------------------------------------
;; Update
;; ---------------------------------------------------------------------------

(defn run-update!
  "Read all voice pitches and motions from the ctrl tree, recompute
  tension metrics, and publish results back to the ctrl tree.

  Called automatically via ctrl/watch! on each voice-pitch path."
  [ctx-atom]
  (let [{:keys [voices consonance-horizon fusion-threshold last-consonance]} @ctx-atom
        prev-cons (or last-consonance {})
        pitches   (into {} (map (fn [v] [v (or (ctrl/get [:harmony :voice-pitch v]) 60)]) voices))
        motions   (into {} (map (fn [v] [v (or (ctrl/get [:harmony :voice-motion v]) 0)]) voices))
        cons-map  (compute-consonance voices pitches)
        tension   (compute-tension cons-map (double consonance-horizon))
        par-prs   (detect-parallel-pairs prev-cons cons-map motions (double fusion-threshold))
        ;; Successive parallel perfect consonances add a dissonance penalty
        tension+  (min 1.0 (+ tension (* 0.15 (count par-prs))))]
    (swap! ctx-atom assoc
           :last-tension        tension+
           :last-consonance     cons-map
           :last-parallel-pairs par-prs)
    (try (ctrl/set! [:harmony :tension] tension+)          (catch Exception _ nil))
    (try (ctrl/set! [:harmony :voice-consonance] cons-map) (catch Exception _ nil))
    (try (ctrl/set! [:harmony :parallel-pairs] par-prs)   (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Imitation buffer
;; ---------------------------------------------------------------------------

(defn- pop-buffer-entry!
  "Atomically pop and return the first entry from the imitation buffer for
  `follower-voice`, or nil if the buffer is empty."
  [ctx-atom follower-voice]
  (let [result (volatile! nil)]
    (swap! ctx-atom
           (fn [state]
             (let [buf (get-in state [:imitation-buffers follower-voice])]
               (if (seq buf)
                 (do (vreset! result (peek buf))
                     (update-in state [:imitation-buffers follower-voice] pop))
                 state))))
    @result))

(defn- leader->followers-map
  "Build {leader-voice [follower1 follower2 ...]} from the imitation config."
  [imitation]
  (reduce (fn [m [follower {:keys [follows]}]]
            (update m follows (fnil conj []) follower))
          {}
          imitation))

;; ---------------------------------------------------------------------------
;; Monitor lifecycle
;; ---------------------------------------------------------------------------

(defn start-monitor!
  "Attach ctrl/watch! on [:harmony :voice-pitch v] for each voice in the
  ensemble (tension monitoring) and on each leader voice (imitation buffering)."
  [ctx-atom]
  (let [{:keys [voices ens-name imitation]} @ctx-atom]
    ;; Tension watches
    (doseq [v voices]
      (try
        (ctrl/watch! [:harmony :voice-pitch v]
                     [::ensemble ens-name v]
                     (fn [_k _ref _old _new] (run-update! ctx-atom)))
        (catch Exception _ nil)))
    ;; Imitation buffering watches — one per unique leader voice
    (when imitation
      (doseq [[leader followers] (leader->followers-map imitation)]
        (try
          (ctrl/watch! [:harmony :voice-pitch leader]
                       [::imitation ens-name leader]
                       (fn [_k _ref _old new-midi]
                         (when new-midi
                           (let [dur (double (or (ctrl/get [:harmony :voice-duration leader]) 1.0))]
                             (doseq [f followers]
                               (swap! ctx-atom update-in [:imitation-buffers f]
                                      conj {:midi (long new-midi) :dur/beats dur}))))))
          (catch Exception _ nil))))
    (swap! ctx-atom assoc :monitoring? true)))

(defn stop-monitor!
  "Remove all ctrl/watch! registrations for this ensemble."
  [ctx-atom]
  (let [{:keys [voices ens-name imitation]} @ctx-atom]
    (doseq [v voices]
      (try (ctrl/unwatch! [:harmony :voice-pitch v] [::ensemble ens-name v])
           (catch Exception _ nil)))
    (when imitation
      (doseq [leader (keys (leader->followers-map imitation))]
        (try (ctrl/unwatch! [:harmony :voice-pitch leader] [::imitation ens-name leader])
             (catch Exception _ nil))))
    (swap! ctx-atom assoc :monitoring? false)))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-ensemble-context
  "Build an ensemble context map from keyword options. Used by defensemble."
  [opts]
  (let [{:keys [voices consonance-horizon fusion-threshold dissonance-threshold
                ens-name imitation]
         :or   {consonance-horizon   6.5
                fusion-threshold     3.5
                dissonance-threshold 5.5}} opts
        init-buffers (when imitation
                       (into {} (map (fn [[follower _]]
                                       [follower clojure.lang.PersistentQueue/EMPTY])
                                     imitation)))]
    {:ens-name             ens-name
     :voices               (vec voices)
     :consonance-horizon   (double consonance-horizon)
     :fusion-threshold     (double fusion-threshold)
     :dissonance-threshold (double dissonance-threshold)
     :imitation            imitation
     :imitation-buffers    (or init-buffers {})
     :monitoring?          false
     :last-tension         0.0
     :last-consonance      {}
     :last-parallel-pairs  #{}}))

;; ---------------------------------------------------------------------------
;; defensemble macro
;; ---------------------------------------------------------------------------

(defmacro defensemble
  "Define an inter-voice tension monitor with optional imitative entry.

  Creates a var bound to an atom holding the ensemble context, registered at
  [:defensemble <name>]. Automatically attaches ctrl/watch! on each voice's
  [:harmony :voice-pitch] path so tension metrics update whenever any voice
  steps. If :imitation is configured, also buffers leader voice history for
  follower sequencers.

  Parameters:
    :voices               — seq of voice name keywords matching :voice-name in
                            lattice/excursion generators
    :consonance-horizon   — Tenney H at which tension reaches 1.0 (default 6.5)
    :fusion-threshold     — Tenney H below which voices risk fusion (default 3.5)
    :dissonance-threshold — Tenney H above which voices are in active dissonance
                            (default 5.5; informational only, not enforced)
    :imitation            — map of {follower-voice {:follows leader-voice
                                                    :interval semitones
                                                    :delay-steps N}}
                            :interval   — semitone transposition (positive = up)
                            :delay-steps — rest for this many leader steps before
                                          the follower begins playing (default 0)

  Tension operations:
    (ensemble-tension ctx-atom)         — current tension [0,1]
    (ensemble-consonance ctx-atom)      — map of {[v1 v2] tenney-h}
    (ensemble-parallel-pairs ctx-atom)  — set of parallel-motion pairs
    (run-update! ctx-atom)              — trigger a manual update
    (start-monitor! ctx-atom)           — re-attach ctrl watchers
    (stop-monitor! ctx-atom)            — detach ctrl watchers

  Imitation operations:
    (make-imitation-seq ctx-atom follower-voice) — IStepSequencer for a follower
    (imitation-buffer-size ctx-atom follower-voice) — buffered step count
    (clear-imitation-buffer! ctx-atom follower-voice) — reset buffer and delay"
  [ensemble-name & opts]
  (let [opts-map (apply hash-map opts)
        ename    (keyword (name ensemble-name))]
    `(do
       (def ~ensemble-name
         (atom (make-ensemble-context (assoc ~opts-map :ens-name ~ename))))
       (register! ~ename ~ensemble-name)
       (ctrl/defnode! [:defensemble ~ename] :type :data :value @~ensemble-name)
       (start-monitor! ~ensemble-name)
       ~ensemble-name)))

;; ---------------------------------------------------------------------------
;; ImitationSeq — IStepSequencer for a follower voice
;; ---------------------------------------------------------------------------

(defrecord ImitationSeq [ctx-atom follower-voice interval delay-steps rest-beats vel started-atom])

(defn make-imitation-seq
  "Create an IStepSequencer for `follower-voice` that plays the leader's
  buffered history, transposed by :interval semitones.

  The sequencer returns rests until `delay-steps` leader steps have
  accumulated in the buffer (one-time startup hold). After that it plays
  continuously, returning rests whenever the buffer is momentarily empty.

  The `:interval`, `:delay-steps`, and `:follows` values are read from the
  :imitation config in the context atom at creation time.

  Options:
    :vel        — MIDI velocity for imitated notes (default 100)
    :rest-beats — duration of rests when buffer is empty or in delay (default 1.0)"
  [ctx-atom follower-voice & {:keys [vel rest-beats] :or {vel 100 rest-beats 1.0}}]
  (let [cfg         (get-in @ctx-atom [:imitation follower-voice] {})
        interval    (long (:interval cfg 0))
        delay-steps (long (:delay-steps cfg 0))]
    (->ImitationSeq ctx-atom follower-voice interval delay-steps
                    (double rest-beats) (long vel) (atom false))))

(extend-protocol sq/IStepSequencer
  ImitationSeq
  (next-event [is]
    (let [follower    (:follower-voice is)
          interval    (long (:interval is))
          delay-steps (long (:delay-steps is))
          rest-beats  (double (:rest-beats is))
          vel         (long (:vel is))
          started?    @(:started-atom is)
          buf-size    (count (get-in @(:ctx-atom is) [:imitation-buffers follower]))]
      ;; Hold in rest until delay-steps entries have accumulated (once only)
      (if (and (not started?) (< buf-size delay-steps))
        {:event nil :beats rest-beats}
        (do
          (when-not started? (reset! (:started-atom is) true))
          (if-let [entry (pop-buffer-entry! (:ctx-atom is) follower)]
            (let [midi       (long (:midi entry))
                  dur        (double (get entry :dur/beats rest-beats))
                  transposed (max 0 (min 127 (+ midi interval)))]
              {:event {:pitch/midi transposed :dur/beats dur :gate/on? true
                       :mod/velocity vel}
               :beats dur})
            ;; Buffer momentarily empty after delay crossed — rest one beat
            {:event nil :beats rest-beats})))))
  (seq-cycle-length [_] nil))

;; ---------------------------------------------------------------------------
;; Imitation inspection and control
;; ---------------------------------------------------------------------------

(defn imitation-buffer-size
  "Return the number of leader steps currently buffered for `follower-voice`."
  [ctx-atom follower-voice]
  (count (get-in @ctx-atom [:imitation-buffers follower-voice])))

(defn clear-imitation-buffer!
  "Reset the imitation buffer for `follower-voice` to empty and reset its
  delay counter (the follower will re-observe the startup hold)."
  [ctx-atom follower-voice]
  (swap! ctx-atom assoc-in [:imitation-buffers follower-voice]
         clojure.lang.PersistentQueue/EMPTY)
  nil)

;; ---------------------------------------------------------------------------
;; Inspection
;; ---------------------------------------------------------------------------

(defn ensemble-names
  "Return a seq of registered ensemble context names."
  []
  (or (keys @registry) '()))

(defn ensemble-tension
  "Return the current aggregate tension [0,1] for `ctx-atom`."
  [ctx-atom]
  (:last-tension @ctx-atom))

(defn ensemble-consonance
  "Return the current voice-pair consonance map {[v1 v2] tenney-h}."
  [ctx-atom]
  (:last-consonance @ctx-atom))

(defn ensemble-parallel-pairs
  "Return the set of voice pairs currently flagged for parallel motion into
  the fusion zone."
  [ctx-atom]
  (:last-parallel-pairs @ctx-atom))

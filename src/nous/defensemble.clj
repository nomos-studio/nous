; SPDX-License-Identifier: EPL-2.0
(ns nous.defensemble
  "defensemble — inter-voice tension monitor.

  defensemble is a pure supervisor: it reads voice pitches and motion
  directions from the ctrl tree and publishes aggregate tension metrics.
  It does not control voices directly.

  ## Quick start

    ;; Each voice generator must be configured with :voice-name
    (deflattice bass-voice  :fundamental :C2 :voice-name :bass  ...)
    (defexcursion soprano-arc :fundamental :C4 :voice-name :soprano ...)

    ;; Define the ensemble monitor
    (defensemble string-quartet
      :voices [:bass :soprano]
      :consonance-horizon 6.5)

    ;; Inspect tension at any time
    (ensemble-tension string-quartet)  ;=> 0.0–1.0

  ## Published ctrl paths

    [:harmony :tension]              — aggregate [0,1] tension
    [:harmony :voice-consonance]     — map of {[v1 v2] tenney-h}
    [:harmony :parallel-pairs]       — set of parallel-motion pair vectors

  ## Tension bands (Tenney H)

    H < 3.5   — fusion risk (unison / octave zone)
    3.5–5.5   — imperfect consonance (target zone for counterpoint)
    H > 5.5   — active dissonance"
  (:require [nomos.maths.harmonic :as h]
            [nous.ctrl            :as ctrl]))

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
;; Monitor lifecycle
;; ---------------------------------------------------------------------------

(defn start-monitor!
  "Attach ctrl/watch! on [:harmony :voice-pitch v] for each voice in the ensemble.
  Each watch triggers run-update! when any voice pitch changes."
  [ctx-atom]
  (let [{:keys [voices ens-name]} @ctx-atom]
    (doseq [v voices]
      (try
        (ctrl/watch! [:harmony :voice-pitch v]
                     [::ensemble ens-name v]
                     (fn [_k _ref _old _new] (run-update! ctx-atom)))
        (catch Exception _ nil)))
    (swap! ctx-atom assoc :monitoring? true)))

(defn stop-monitor!
  "Remove all ctrl/watch! registrations for this ensemble."
  [ctx-atom]
  (let [{:keys [voices ens-name]} @ctx-atom]
    (doseq [v voices]
      (try
        (ctrl/unwatch! [:harmony :voice-pitch v] [::ensemble ens-name v])
        (catch Exception _ nil)))
    (swap! ctx-atom assoc :monitoring? false)))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(defn make-ensemble-context
  "Build an ensemble context map from keyword options. Used by defensemble."
  [opts]
  (let [{:keys [voices consonance-horizon fusion-threshold dissonance-threshold ens-name]
         :or   {consonance-horizon   6.5
                fusion-threshold     3.5
                dissonance-threshold 5.5}} opts]
    {:ens-name             ens-name
     :voices               (vec voices)
     :consonance-horizon   (double consonance-horizon)
     :fusion-threshold     (double fusion-threshold)
     :dissonance-threshold (double dissonance-threshold)
     :monitoring?          false
     :last-tension         0.0
     :last-consonance      {}
     :last-parallel-pairs  #{}}))

;; ---------------------------------------------------------------------------
;; defensemble macro
;; ---------------------------------------------------------------------------

(defmacro defensemble
  "Define an inter-voice tension monitor.

  Creates a var bound to an atom holding the ensemble context, registered at
  [:defensemble <name>]. Automatically attaches ctrl/watch! on each voice's
  [:harmony :voice-pitch] path so tension metrics update whenever any voice
  steps.

  Parameters:
    :voices               — seq of voice name keywords matching :voice-name in
                            lattice/excursion generators
    :consonance-horizon   — Tenney H at which tension reaches 1.0 (default 6.5)
    :fusion-threshold     — Tenney H below which voices risk fusion (default 3.5)
    :dissonance-threshold — Tenney H above which voices are in active dissonance
                            (default 5.5; informational only, not enforced)

  Operations:
    (ensemble-tension ctx-atom)         — current tension [0,1]
    (ensemble-consonance ctx-atom)      — map of {[v1 v2] tenney-h}
    (ensemble-parallel-pairs ctx-atom)  — set of parallel-motion pairs
    (run-update! ctx-atom)              — trigger a manual update
    (start-monitor! ctx-atom)           — re-attach ctrl watchers
    (stop-monitor! ctx-atom)            — detach ctrl watchers"
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

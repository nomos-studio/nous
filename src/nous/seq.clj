; SPDX-License-Identifier: EPL-2.0
(ns nous.seq
  "IStepSequencer — unified protocol for all step-based generators.

  Every sequencer in nous — Pattern×Rhythm motifs, arpeggiators, raw note
  lists, generative engines — implements IStepSequencer. The protocol has two
  operations: advance one step and report cycle length.

  ## Protocol

    (next-event sq)         → {:event note-map-or-nil :beats duration}
    (seq-cycle-length sq)   → long (steps per cycle) or nil (infinite)

  :event nil means this step is a rest — advance the clock by :beats, play nothing.

  ## Running a sequencer

    (run-step!  sq)              ; play one step, block until done (infinite sources)
    (run-step!  sq {:xf xf})    ; same, with transformer
    (run-cycle! sq)              ; play one full cycle, block until done
    (run-cycle! sq {:xf xf})    ; same, with transformer applied to each event
    (seq-loop! sq)               ; loop indefinitely, return {:running? atom :future f}
    (seq-loop! sq {:xf xf})     ; same, with transformer

  ## The note map

  Every :event returned by next-event is a standard nous step map:
    {:pitch/midi   long     — MIDI note number
     :dur/beats    number   — gate duration in beats
     :mod/velocity long     — velocity 0–127
     :midi/channel long     — MIDI channel 1–16 (optional)
     ...}                   — any additional keys (parameter locks, mod targets)

  Additional keys beyond the standard set are parameter locks — they are
  passed through live/play! and merged into the step before dispatch.
  The *step-mod-ctx* dynamic LFOs take priority over any locked values.

  ## Composition

  Because any IStepSequencer can be advanced step-by-step, sequencers compose:

    ;; Drive arp chord selection from a motif state
    (let [harmony-seq (make-motif-state chord-pattern chord-rhythm)
          arp-state   (make-arp-state :up (chord/chord :C 4 :maj))]
      (deflive-loop :comp {}
        ;; advance harmony one step per bar, derive arp chord from it
        (let [{:keys [event]} (next-event harmony-seq)]
          (when event
            (reset-arp-chord! arp-state (:pitch/midi event))))
        (run-cycle! arp-state)))

  ## Relationship to ITransformer

  ITransformer (nous.transform) is post-generation: it receives a note map
  and returns zero or more transformed events (echoes, harmonies, etc.).
  IStepSequencer is pre-dispatch: it generates the note maps. They compose:

    (run-cycle! sq {:xf (compose-xf (harmonize ...) (echo ...))})

  See nous.pattern/make-motif-state, nous.arp/make-arp-state."
  (:require [nous.loop      :as loop-ns]
            [nous.live      :as live]
            [nous.transform :as xf]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol IStepSequencer
  (next-event [sq]
    "Advance the sequencer one step. Mutates internal position.

    Returns a map:
      {:event note-map :beats duration}   — a note; note-map is a standard
                                            nous step map (see ns docstring).
      {:event nil      :beats duration}   — a rest; sleep only.")
  (seq-cycle-length [sq]
    "Number of steps in one full cycle.
    Returns a positive long, or nil for infinite/generative sequencers."))

;; ---------------------------------------------------------------------------
;; Runners
;; ---------------------------------------------------------------------------

(defn- dispatch!
  "Play event through xf if provided, otherwise directly via live/play!."
  [event xf]
  (when event
    (if xf (xf/play-transformed! xf event) (live/play! event))))

(defn run-step!
  "Advance `sq` one step and play the resulting event.

  Use this inside deflive-loop for infinite/generative sequencers where the
  loop body controls how many steps occur per iteration.

  Options:
    :xf — an ITransformer applied to the event.

  Returns nil.

  Example:
    ;; Generative fractal — one step per 1/8 beat, loop controls pacing
    (deflive-loop :texture {}
      (run-step! fractal-state)
      ;; sleep! already called by run-step! for :beats duration)

    ;; With transformer
    (deflive-loop :echo-melody {}
      (run-step! fractal-state {:xf my-echo}))"
  ([sq]
   (run-step! sq nil))
  ([sq {:keys [xf]}]
   (let [{:keys [event beats]} (next-event sq)]
     (dispatch! event xf)
     (loop-ns/sleep! beats))
   nil))

(defn run-cycle!
  "Play one full cycle of sequencer `sq`, blocking until complete.

  Options:
    :xf — an ITransformer (from nous.transform) applied to each event.
          nil means play directly via live/play!.

  Returns nil. Intended to be called from inside a deflive-loop body.

  Example:
    (deflive-loop :melody {:harmony (scale/scale :C 4 :major)}
      (run-cycle! my-motif-state))

    (deflive-loop :melody {:harmony (scale/scale :C 4 :major)}
      (run-cycle! my-motif-state {:xf (harmonize {:intervals [7]})}))"
  ([sq]
   (run-cycle! sq nil))
  ([sq {:keys [xf]}]
   (let [n (seq-cycle-length sq)]
     (if n
       (dotimes [_ n]
         (let [{:keys [event beats]} (next-event sq)]
           (dispatch! event xf)
           (loop-ns/sleep! beats)))
       ;; Infinite sequencer: run-cycle! plays one step.
       ;; Use (deflive-loop ... (run-cycle! sq)) to loop, or run-step! explicitly.
       (run-step! sq {:xf xf})))
   nil))

(defn seq-loop!
  "Start an indefinitely looping sequencer in a background future.

  Returns a handle map:
    {:running? atom    — deref to check; reset! false to request stop
     :future   future  — the background future}

  The loop runs complete cycles (via run-cycle!) until :running? is false.
  Tempo changes take effect at the start of the next cycle.

  Options:
    :xf — ITransformer applied to each event (see run-cycle!).

  Example:
    (def handle (seq-loop! my-arp-state))
    ;; later:
    (reset! (:running? handle) false)

  For named, supervised loops prefer deflive-loop. seq-loop! is for
  anonymous or programmatically-managed sequencers."
  ([sq]
   (seq-loop! sq nil))
  ([sq {:keys [xf]}]
   (let [running? (atom true)]
     {:running? running?
      :future   (future
                  (try
                    (loop []
                      (when @running?
                        (run-cycle! sq {:xf xf})
                        (recur)))
                    (catch InterruptedException _)
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "[seq-loop!] error:" (.getMessage e))))))})))

(defn stop-seq!
  "Stop a seq-loop! handle.

  Signals the loop to stop after the current cycle completes, then cancels
  the future. Safe to call multiple times.

  Example:
    (stop-seq! handle)"
  [{:keys [running? future]}]
  (when running? (reset! running? false))
  (when future   (future-cancel future))
  nil)

;; ---------------------------------------------------------------------------
;; make-degree-seq — theory-relative step sequencer
;; ---------------------------------------------------------------------------

(defn make-degree-seq
  "Build an IStepSequencer from a vector of degree-step maps.

  Each step is a map with:
    :degree  — 1-indexed scale degree (required); nil or :rest = rest
    :octave  — octave shift relative to scale root (default 0)
    :vel     — velocity 0–127 (default 100)
    :gate    — gate duration in beats (default = beats-per-step × 0.9)
    :prob    — fire probability 0.0–1.0 (default 1.0)

  Arguments:
    steps          — vector of step maps
    beats-per-step — grid step size in beats (e.g. 1/4 for a 16-step bar)

  At each step the sequencer emits a tagged pitch selector
  {:pitch/degree N :pitch/octave O …} into play!. Resolution against
  *harmony-ctx* happens at fire time — changing the active scale recolours
  the next step without restarting the loop.

  Example:
    (def sq
      (make-degree-seq
        [{:degree 1} {:degree 3} {:degree 5 :vel 90} {:degree :rest}
         {:degree 2} {:degree 4} {:degree 6 :octave 1} {:degree 1 :prob 0.75}]
        1/4))
    (deflive-loop :seq {:harmony (scale/scale :C 4 :dorian)}
      (run-cycle! sq))"
  [steps beats-per-step]
  (let [steps (vec steps)
        cnt   (count steps)
        pos   (atom 0)]
    (reify
      IStepSequencer
      (next-event [_]
        (let [i    @pos
              step (nth steps (mod i cnt))
              deg  (:degree step)
              prob (double (:prob step 1.0))]
          (swap! pos inc)
          (if (or (nil? deg) (= :rest deg) (> (rand) prob))
            {:event nil :beats beats-per-step}
            {:event {:pitch/degree (long deg)
                     :pitch/octave (long (:octave step 0))
                     :dur/beats    (or (:gate step) (* beats-per-step 0.9))
                     :mod/velocity (long (:vel step 100))}
             :beats beats-per-step})))
      (seq-cycle-length [_] cnt))))

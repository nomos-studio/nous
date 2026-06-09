; SPDX-License-Identifier: EPL-2.0
(ns nous.mts
  "Scheduled MTS (MIDI Tuning Standard) operations.

  Provides `retune-arc!` — a gradual, beat-accurate retune that interpolates
  between two `{MIDI-note → Hz}` freq-maps (as produced by `scala/scale->freq-map`)
  and sends an MTS Bulk Dump at each step via `kairos/send-mts!`.

  ## Quick start

    (require '[nous.mts   :as mts]
             '[nous.scala :as scala]
             '[nous.link  :as link]
             '[nous.loop  :as loop])

    (def ji-map  (scala/scale->freq-map partch-43))
    (def tet-map (scala/scale->freq-map (scala/parse-scl tet-scl)))

    ;; 8 retune steps over 32 beats, starting on the next 32-beat boundary
    (mts/retune-arc! ji-map tet-map
                     (link/next-quantum-beat (loop/-current-beat) 32) 32)

  ## Interpolation

  `lerp-freq-maps` uses log-frequency (cents) interpolation so each step
  covers a perceptually equal interval — equivalent to geometric-mean Hz at
  t=0.5.  This is more musically uniform than Hz-linear interpolation for
  wide-interval arcs like JI→12-TET."
  (:require [nous.kairos :as kairos]
            [nous.loop   :as loop]))

;; ---------------------------------------------------------------------------
;; Pure interpolation
;; ---------------------------------------------------------------------------

(defn lerp-freq-maps
  "Interpolate between two {MIDI-note → Hz} maps at t ∈ [0.0, 1.0].

  Interpolation is log-linear (cents space), so each step covers a
  perceptually equal interval.  Both maps must contain entries for keys 0–127;
  maps produced by `scala/scale->freq-map` always satisfy this.

  Returns a new {MIDI-note → Hz} map with 128 entries."
  [from-map to-map ^double t]
  (into {}
    (map (fn [^long k]
           (let [f0 (Math/log (double (get from-map k)))
                 f1 (Math/log (double (get to-map k)))]
             [k (Math/exp (+ f0 (* t (- f1 f0))))]))
         (range 128))))

;; ---------------------------------------------------------------------------
;; Beat sequence (pure — testable without the scheduler)
;; ---------------------------------------------------------------------------

(defn arc-beats
  "Return the sequence of absolute beat positions for `steps` evenly-spaced
  retune events starting at `start-beat` and spanning `beats` beats.

  With steps=8: [start, start+beats/7, ..., start+beats]
  First step always fires at start-beat (t=0), last at start-beat+beats (t=1)."
  ^doubles [^double start-beat ^double beats ^long steps]
  (if (<= steps 1)
    [start-beat]
    (mapv (fn [^long i]
            (+ start-beat (* (/ (double i) (double (dec steps))) beats)))
          (range steps))))

;; ---------------------------------------------------------------------------
;; Scheduled retune arc
;; ---------------------------------------------------------------------------

(defn retune-arc!
  "Schedule a gradual retune from `from-map` to `to-map` across a beat span.

  from-map   — starting {MIDI-note → Hz} map (from `scala/scale->freq-map`)
  to-map     — ending {MIDI-note → Hz} map
  start-beat — Link beat at which the first MTS dump fires
  beats      — duration of the arc in beats (last dump fires at start+beats)

  Options:
    :steps       — number of MTS sends including first and last (default 8;
                   minimum 2). Space them at least 1 beat apart at your session
                   BPM to give the synthesiser time to process each dump.
    :tuning-prog — MTS tuning program slot to write (default 0)
    :device-id   — MTS device-id (:all or integer, default :all)

  Spawns a daemon future thread that parks on each beat boundary then sends
  the interpolated MTS Bulk Dump via `kairos/send-mts!`.  Returns the future
  so callers can deref it to wait for completion.

  The arc is fire-and-forget — if kairos disconnects mid-arc the remaining
  sends are silently dropped (send-mts! no-ops when not connected).

  Example — 8-bar JI→12-TET emergence starting on the next 32-beat boundary:

    (let [ji-map  (scala/scale->freq-map partch-43)
          tet-map (scala/scale->freq-map (scala/parse-scl twelve-tet))
          start   (link/next-quantum-beat (loop/-current-beat) 32)]
      (retune-arc! ji-map tet-map start 32 :steps 8))"
  [from-map to-map start-beat beats
   & {:keys [steps tuning-prog device-id]
      :or   {steps 8 tuning-prog 0 device-id :all}}]
  (let [steps      (max 2 (long steps))
        start-beat (double start-beat)
        beats      (double beats)
        beat-seq   (arc-beats start-beat beats steps)]
    (future
      (doseq [[i target-beat] (map-indexed vector beat-seq)]
        (let [t        (/ (double i) (double (dec steps)))
              freq-map (lerp-freq-maps from-map to-map t)]
          (loop/-park-until-beat! target-beat)
          (kairos/send-mts! freq-map
                            :tuning-prog tuning-prog
                            :device-id   device-id))))))

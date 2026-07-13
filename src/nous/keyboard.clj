; SPDX-License-Identifier: EPL-2.0
(ns nous.keyboard
  "Keyboard mode dispatch (M16/M17) — pitch, interval, interval-last-note.

  The physical keyboard (12 keys: a w s e d f t g y h u j) has three modes:

    :pitch              — piano layout, absolute MIDI notes (M2 behaviour)
    :interval           — solfege wheel, relative navigation from running position
    :interval-last-note — interval mode with anchor seeded by last pitch-mode note

  All keyboard/sequencer state lives on the ctrl-tree — the single source of
  truth (see doc/design-ctrl-authority.md). There is no private position or
  recording state: mode at [:keyboard :mode], wheel position at
  [:keyboard :interval_position], recording flag at [:keyboard :recording], and
  the tone row at [:seq :tone_row] / [:seq :tone_row_in_progress]. Reads use
  ctrl-tree.core/ctrl-read; writes use ctrl-write! (which echoes to BEAM via the
  mounted [:keyboard] and [:seq] prefixes).

  Tone row recording (M17): start-recording! / stop-recording! toggle capture
  mode. While recording, each interval keypress appends {:interval n :vel v} to
  [:seq :tone_row_in_progress]; stop-recording! commits it to [:seq :tone_row].

  Pitch mode note events are handled by the existing rt/kairos-voice chain in
  nous.jinterface; this namespace adds interval dispatch on top and records
  the anchor note for :interval-last-note mode."
  (:require [ctrl-tree.core :as ct]
            [nous.live      :as live]
            [nous.loop      :as loop-ns]
            [nous.pitch     :as pitch]
            [nous.scale     :as scale-ns]))

;; ---------------------------------------------------------------------------
;; Key mappings
;; ---------------------------------------------------------------------------

;; Piano layout — same as sc-keyboard/rt (local copy avoids cross-dep)
(def ^:private pitch-key->midi
  {"a" 60 "w" 61 "s" 62 "e" 63 "d" 64
   "f" 65 "t" 66 "g" 67 "y" 68 "h" 69
   "u" 70 "j" 71})

;; Default interval map: key → scale step delta.
;; Layout loosely follows the Misha home-row convention:
;;   a/s  = ±1 (minor 2nd / major 2nd range)
;;   d/f  = ±2
;;   g/h  = ±3
;;   j/w  = ±4 / -4
;;   e/t  = octave sentinels (handled as ±n-steps in interval-note-on!)
(def default-interval-map
  {"a" -1 "s" +1 "d" -2 "f" +2 "g" -3 "h" +3 "j" +4 "w" -4})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- n-steps
  "Steps in the active harmony context; falls back to 7 (diatonic)."
  []
  (let [hctx loop-ns/*harmony-ctx*]
    (if hctx (count (:intervals hctx)) 7)))

(defn- step-name
  "Note-class string ('C', 'E', 'C♯') from a nous.pitch/Pitch record."
  [p]
  (let [names ["C" "D" "E" "F" "G" "A" "B"]
        acc   (case (:accidental p) :sharp "♯" :flat "♭" "")]
    (str (nth names (long (:step p 0))) acc)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn keyboard-mode
  "Return the active keyboard mode keyword (:pitch, :interval, :interval-last-note).
  Defaults to :pitch when unset."
  []
  (or (ct/ctrl-read [:keyboard :mode]) :pitch))

(defn current-position
  "Return the current solfege wheel position as a 1-indexed scale degree."
  []
  (or (ct/ctrl-read [:keyboard :interval_position]) 1))

(defn reset-position!
  "Reset the interval wheel to the root position (degree 1). REPL utility."
  []
  (ct/ctrl-write! [:keyboard :interval_position] 1)
  nil)

(defn recording?
  "Return true when tone row recording is active."
  []
  (= true (ct/ctrl-read [:keyboard :recording])))

(defn interval-note-on!
  "Navigate the solfege wheel by the interval delta mapped to `key`, then
  fire play! with :pitch/degree. No-op when `key` is not in the interval map.

  Writes [:keyboard :interval_position] (1-indexed degree) and
  [:keyboard :interval_note_name] to the ctrl-tree so BEAM can render the
  solfege wheel. When recording is active, also appends the interval delta to
  [:seq :tone_row_in_progress]."
  [key]
  (let [imap  (or (ct/ctrl-read [:keyboard :qwerty-map]) default-interval-map)
        delta (get imap key)]
    (when delta
      (let [n    (n-steps)
            pos0 (dec (or (ct/ctrl-read [:keyboard :interval_position]) 1))
            pos  (mod (+ pos0 (long delta)) n)
            deg  (inc pos)
            hctx loop-ns/*harmony-ctx*
            note-name (when hctx
                        (try (step-name (scale-ns/pitch-at hctx pos))
                             (catch Exception _ nil)))]
        (ct/ctrl-write! [:keyboard :interval_position] deg)
        (when note-name
          (ct/ctrl-write! [:keyboard :interval_note_name] note-name))
        ;; Recording (M17): append {:interval n :vel 100} to the in-progress row.
        ;; Keypress dispatch is single-threaded (JInterface receive loop / REPL),
        ;; so this read-modify-write on [:seq :tone_row_in_progress] does not race.
        (when (recording?)
          (let [row-step {:interval (long delta) :vel 100}
                row      (conj (or (ct/ctrl-read [:seq :tone_row_in_progress]) [])
                               row-step)]
            (ct/ctrl-write! [:seq :tone_row_in_progress] row)))
        (live/play! {:pitch/degree deg :dur/beats 1/4})))))

(defn record-anchor!
  "Record the anchor note after a pitch-mode keypress.
  Writes [:keyboard :anchor_note] (MIDI integer). Used by :interval-last-note
  mode so that the wheel position is known relative to the last absolute pitch."
  [key]
  (when-let [midi (pitch-key->midi key)]
    (ct/ctrl-write! [:keyboard :anchor_note] midi)))

(defn set-mode!
  "Write [:keyboard :mode] to the ctrl-tree. mode must be one of:
    :pitch, :interval, :interval-last-note"
  [mode]
  {:pre [(#{:pitch :interval :interval-last-note} mode)]}
  (ct/ctrl-write! [:keyboard :mode] mode)
  nil)

;; ---------------------------------------------------------------------------
;; Tone row recording (M17)
;; ---------------------------------------------------------------------------

(defn start-recording!
  "Enter tone row record mode. Clears the in-progress row and arms the
  interval dispatcher to capture each step to [:seq :tone_row_in_progress]."
  []
  (ct/ctrl-write! [:keyboard :recording] true)
  (ct/ctrl-write! [:seq :tone_row_in_progress] [])
  nil)

(defn stop-recording!
  "Leave record mode and commit the in-progress row to [:seq :tone_row].
  The committed row is what make-interval-seq reads for playback."
  []
  (ct/ctrl-write! [:keyboard :recording] false)
  (ct/ctrl-write! [:seq :tone_row] (or (ct/ctrl-read [:seq :tone_row_in_progress]) []))
  nil)

(defn commit-row!
  "Commit the current in-progress row to [:seq :tone_row] without stopping
  recording. Use for length-threshold auto-commits."
  []
  (ct/ctrl-write! [:seq :tone_row] (or (ct/ctrl-read [:seq :tone_row_in_progress]) []))
  nil)

(defn clear-row!
  "Clear the in-progress recording buffer without leaving record mode."
  []
  (ct/ctrl-write! [:seq :tone_row_in_progress] [])
  nil)

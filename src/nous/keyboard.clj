; SPDX-License-Identifier: EPL-2.0
(ns nous.keyboard
  "Keyboard mode dispatch (M16) — pitch, interval, interval-last-note.

  The physical keyboard (12 keys: a w s e d f t g y h u j) has three modes:

    :pitch              — piano layout, absolute MIDI notes (M2 behaviour)
    :interval           — solfege wheel, relative navigation from running position
    :interval-last-note — interval mode with anchor seeded by last pitch-mode note

  Mode is set via (ctrl/set! [:keyboard :mode] :interval) or any ctrl-tree write.
  Interval state is tracked in a private atom and echoed to [:keyboard :interval-position].

  Pitch mode note events are handled by the existing rt/kairos-voice chain in
  nous.jinterface; this namespace adds interval dispatch on top and records
  the anchor note for :interval-last-note mode."
  (:require [ctrl-tree.core :as ct]
            [nous.ctrl      :as ctrl]
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
;; Running position state
;; ---------------------------------------------------------------------------

;; 0-indexed position on the solfege wheel.
;; Invariant: always in [0, n-steps − 1] for the current harmony context.
(defonce ^:private pos-atom (atom 0))

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
  (or (ctrl/get [:keyboard :mode]) :pitch))

(defn current-position
  "Return the current solfege wheel position as a 1-indexed scale degree."
  []
  (inc @pos-atom))

(defn reset-position!
  "Reset the interval wheel to the root position (degree 1). REPL utility."
  []
  (reset! pos-atom 0)
  (ct/ctrl-write! [:keyboard :interval-position] 1)
  nil)

(defn interval-note-on!
  "Navigate the solfege wheel by the interval delta mapped to `key`, then
  fire play! with :pitch/degree. No-op when `key` is not in the interval map.

  Also writes [:keyboard :interval-position] (1-indexed degree) and
  [:keyboard :interval-note-name] (note-class string) to the ctrl-tree so
  BEAM can render the solfege wheel display."
  [key]
  (let [imap  (or (ctrl/get [:keyboard :qwerty-map]) default-interval-map)
        delta (get imap key)]
    (when delta
      (let [n    (n-steps)
            pos  (mod (+ @pos-atom (long delta)) n)]
        (reset! pos-atom pos)
        (let [deg  (inc pos)
              hctx loop-ns/*harmony-ctx*
              note-name (when hctx
                          (try (step-name (scale-ns/pitch-at hctx pos))
                               (catch Exception _ nil)))]
          (ct/ctrl-write! [:keyboard :interval-position] deg)
          (when note-name
            (ct/ctrl-write! [:keyboard :interval-note-name] note-name))
          (live/play! {:pitch/degree deg :dur/beats 1/4}))))))

(defn record-anchor!
  "Record the anchor note after a pitch-mode keypress.
  Writes [:keyboard :anchor-note] (MIDI integer). Used by :interval-last-note
  mode so that the wheel position is known relative to the last absolute pitch."
  [key]
  (when-let [midi (pitch-key->midi key)]
    (ct/ctrl-write! [:keyboard :anchor-note] midi)))

(defn set-mode!
  "Write [:keyboard :mode] to the ctrl-tree. mode must be one of:
    :pitch, :interval, :interval-last-note"
  [mode]
  {:pre [(#{:pitch :interval :interval-last-note} mode)]}
  (ct/ctrl-write! [:keyboard :mode] mode)
  nil)

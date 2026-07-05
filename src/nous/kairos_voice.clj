; SPDX-License-Identifier: EPL-2.0
(ns nous.kairos-voice
  "Keyboard → kairos/SurgeXT note dispatch.

  Mirrors nous.sc-keyboard but routes note events to kairos via
  nous.kairos/send-note-on! / send-note-off! rather than SC.

  Uses the same chromatic piano key layout as sc-keyboard:
    a w s e d f t g y h u j  (C4–B4)

  Writes the live voice state to ctrl-tree paths under [:voices :surge-1]
  so txlog and ctrl-tree observers see the active note.

  Start/stop:
    (kairos-voice/start!)   ; initialise ctrl-tree paths
    (kairos-voice/stop!)    ; send note-off for any stuck note

  Requires a connected kairos process — use (kairos/start-kairos! ...) or
  (kairos/connect!) first."
  (:require [nous.kairos       :as kairos]
            [ctrl-tree.core    :as ct]))

;; ---------------------------------------------------------------------------
;; Pitch mapping (shared with sc-keyboard)
;; ---------------------------------------------------------------------------

(def key->note
  "Maps laptop key chars to MIDI note numbers (C4=60, chromatic piano layout)."
  {"a" 60 "w" 61 "s" 62 "e" 63 "d" 64
   "f" 65 "t" 66 "g" 67 "y" 68 "h" 69
   "u" 70 "j" 71})

(defn note->hz
  "Equal-temperament Hz from MIDI note number (A4=440 Hz)."
  [note]
  (* 440.0 (Math/pow 2.0 (/ (- note 69) 12.0))))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private active-keys
  ;; key string → MIDI note number (for clean note-off)
  (atom {}))

(defonce ^:private started-state
  (atom false))

;; ---------------------------------------------------------------------------
;; Note dispatch
;; ---------------------------------------------------------------------------

(defn note-on!
  "Dispatch a note-on for the given key string to kairos.
  No-op when the key is not in the piano layout or kairos is not connected."
  [key]
  (when-let [note (key->note key)]
    (when (kairos/connected?)
      (let [hz (note->hz note)]
        (kairos/send-note-on! note 0.8)
        (swap! active-keys assoc key note)
        (ct/ctrl-write! [:voices :surge-1 :key]  note)
        (ct/ctrl-write! [:voices :surge-1 :freq] hz)
        (ct/ctrl-write! [:voices :surge-1 :gate] true)))))

(defn note-off!
  "Dispatch a note-off for the given key string to kairos.
  No-op when the key was never pressed or kairos is not connected."
  [key]
  (when-let [note (get @active-keys key)]
    (when (kairos/connected?)
      (kairos/send-note-off! note)
      (swap! active-keys dissoc key)
      (ct/ctrl-write! [:voices :surge-1 :gate] false))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn stop!
  "Send note-off for any stuck notes and disarm the keyboard handler."
  []
  (doseq [[_ note] @active-keys]
    (try (when (kairos/connected?) (kairos/send-note-off! note))
         (catch Exception _)))
  (reset! active-keys {})
  (reset! started-state false))

(defn start!
  "Initialise ctrl-tree [:voices :surge-1 ...] paths.

  Safe to call multiple times — subsequent calls are no-ops."
  []
  (when-not @started-state
    (ct/ctrl-write! [:voices :surge-1 :key]  60)
    (ct/ctrl-write! [:voices :surge-1 :freq] 440.0)
    (ct/ctrl-write! [:voices :surge-1 :amp]  0.8)
    (ct/ctrl-write! [:voices :surge-1 :gate] false)
    (reset! started-state true)))

(defn started? [] @started-state)

; SPDX-License-Identifier: EPL-2.0
(ns nous.sc-keyboard
  "Keyboard → SC note dispatch.

  Maps laptop key events from [:input :keyboard :key_down/:key_up] ctrl-tree
  paths to scsynth note on/off, using the same piano-layout as nous.aion.

  Maintains an active-nodes map (key → SC node-id) for clean note-off.
  Writes freq/gate to ctrl-tree paths under [:synths :sc-default] so
  txlog and any ctrl-tree observers see the live state.

  Start/stop:
    (sc-keyboard/start!)   ; sends :blade SynthDef, arms key handler
    (sc-keyboard/stop!)    ; kills all active nodes, clears state

  Keys: a w s e d f t g y h u j  (C4–B4, chromatic piano layout)"
  (:require [nous.sc          :as sc]
            [ctrl-tree.core   :as ct]))

;; ---------------------------------------------------------------------------
;; Pitch mapping
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

(defonce ^:private active-nodes
  ;; key string → SC node-id
  (atom {}))

(defonce ^:private started-state
  (atom false))

;; ---------------------------------------------------------------------------
;; Note dispatch
;; ---------------------------------------------------------------------------

(defn key-down!
  "Dispatch a note-on for the given key string.
  No-op if key is not in the piano layout or SC is not connected."
  [key]
  (when-let [note (key->note key)]
    (when (sc/sc-connected?)
      (let [hz      (note->hz note)
            node-id (sc/sc-synth! :blade {:freq hz :amp 0.7 :attack 0.02 :release 0.5})]
        (swap! active-nodes assoc key node-id)
        (ct/ctrl-write! [:synths :sc-default :freq] hz)
        (ct/ctrl-write! [:synths :sc-default :gate] true)))))

(defn key-up!
  "Dispatch a note-off for the given key string.
  Releases the active node (gate envelope tail).  No-op if not active."
  [key]
  (when-let [node-id (get @active-nodes key)]
    (sc/free-synth! node-id)
    (swap! active-nodes dissoc key)
    (ct/ctrl-write! [:synths :sc-default :gate] false)))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn stop!
  "Kill all active SC nodes and disarm the keyboard handler."
  []
  (doseq [node-id (vals @active-nodes)]
    (try (sc/free-synth! node-id)
         (catch Exception _)))
  (reset! active-nodes {})
  (reset! started-state false))

(defn start!
  "Arm the keyboard → SC note handler.

  Sends the :blade SynthDef to scsynth when connected (idempotent) and
  publishes the sc-default ctrl-tree schema paths.  Safe to call before
  SC is connected — ensure-synthdef! is a no-op when disconnected.

  Safe to call multiple times — subsequent calls are no-ops."
  []
  (when-not @started-state
    (sc/ensure-synthdef! :blade)
    (ct/ctrl-write! [:synths :sc-default :freq] 440.0)
    (ct/ctrl-write! [:synths :sc-default :amp]  0.7)
    (ct/ctrl-write! [:synths :sc-default :gate] false)
    (reset! started-state true)))

(defn started? [] @started-state)

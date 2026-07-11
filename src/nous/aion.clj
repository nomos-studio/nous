; SPDX-FileCopyrightText: 2025-2026 nomos-studio contributors
;
; SPDX-License-Identifier: EPL-2.0
(ns nous.aion
  "Compatibility shim for the aion session substrate.

  nous.rt is now the unified nomos-rt connection layer.  This namespace
  re-exports the keyboard layout and note API so call sites in jinterface
  and tests continue to work unchanged."
  (:require [nous.rt :as rt]))

(def key->note
  "Physical key letter → MIDI note number (chromatic layout, middle C = 60)."
  rt/key->note)

(def ^:dynamic *dispatch-diag* rt/*dispatch-diag*)

(defn note-on!
  "Send a note-on for physical key character (e.g. \"a\"). velocity is 0.0–1.0."
  ([key]          (rt/note-on! key))
  ([key velocity] (rt/note-on! key velocity)))

(defn note-off!
  "Send a note-off for physical key character."
  [key]
  (rt/note-off! key))

(defn connected?     [] (rt/connected?))
(defn estimated-beat [] (rt/estimated-beat))
(def  ^:dynamic *bar-beats* rt/*bar-beats*)

(defn stop!
  "Disconnect from the aion socket. Alias for rt/disconnect!."
  []
  (rt/disconnect!))

(defn connect-at-next-bar!
  "Reconnect to aion at the next bar boundary. Alias for rt/connect-at-next-bar!."
  ([]          (rt/connect-at-next-bar!))
  ([bar-beats] (rt/connect-at-next-bar! bar-beats)))

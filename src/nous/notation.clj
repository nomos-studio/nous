; SPDX-FileCopyrightText: 2025-2026 nomos-studio contributors
; SPDX-License-Identifier: EPL-2.0
(ns nous.notation
  "Music notation export — MusicXML and LilyPond via the music21 bridge.

  BeamMount echoes results back through:
    [:notation :corpus :musicxml]   [:notation :corpus :lilypond]
    [:notation :session :musicxml]  [:notation :session :lilypond]

  All public functions are fire-and-forget (return a future).
  Errors are printed to *err* rather than thrown."
  (:require [clojure.data.json  :as json]
            [ctrl-tree.core     :as ct]
            [ctrl-tree.refs     :as refs]
            [nous.ctrl          :as ctrl]
            [nous.dirs          :as dirs]
            [nous.m21           :as m21]
            [nous.txlog-store   :as tx]))

;; ── Keyboard key → MIDI pitch (matches PianoLive @key_layout) ────────────────

(def ^:private key->midi
  {"a" 60 "w" 61 "s" 62 "e" 63 "d" 64
   "f" 65 "t" 66 "g" 67 "y" 68 "h" 69 "u" 70 "j" 71})

;; ── Private helpers ───────────────────────────────────────────────────────────

(defn- log-err [ctx msg]
  (binding [*out* *err*]
    (println (str "[nous.notation] " ctx ": " msg))))

(defn- txlog-note-events
  "Keyboard key_down entries from the in-memory txlog within [beat-from, beat-to].
  Each entry maps to {:pitch midi-int :start beat-double :dur 0.5}."
  [beat-from beat-to]
  (->> (tx/recent-entries 500)
       (filter #(and (= (:path %) [:input :keyboard :key_down])
                     (let [b (:beat %)]
                       (and b
                            (>= b (double beat-from))
                            (<= b (double beat-to))))))
       (keep (fn [{:keys [after beat]}]
               (when-let [midi (get key->midi after)]
                 {:pitch midi :start (double beat) :dur 0.5})))))

;; ── Public API ────────────────────────────────────────────────────────────────

(defn export-corpus!
  "Export BWV chorale bwv-num as MusicXML + LilyPond, writing results to
  the ctrl-tree so BeamMount echoes them to nomos_beam. Fire-and-forget."
  [bwv-num]
  (future
    (try
      (let [resp (m21/server-call! {"op" "export-corpus" "bwv" bwv-num})]
        (if (= "ok" (:status resp))
          (do
            (when-let [xml (:musicxml resp)]
              (ct/ctrl-write! [:notation :corpus :musicxml] xml))
            (when-let [ly (:lilypond resp)]
              (ct/ctrl-write! [:notation :corpus :lilypond] ly)))
          (log-err "export-corpus" (:message resp))))
      (catch Exception e
        (log-err "export-corpus" (.getMessage e))))))

(defn export-session!
  "Export keyboard note events in beat range [beat-from, beat-to] as MusicXML
  and LilyPond. Events are extracted from the in-memory txlog. Fire-and-forget."
  [beat-from beat-to]
  (future
    (try
      (let [events (vec (txlog-note-events beat-from beat-to))]
        (if (seq events)
          (let [resp (m21/server-call! {"op"     "export-session"
                                        "events" events
                                        "tempo"  120.0})]
            (if (= "ok" (:status resp))
              (do
                (when-let [xml (:musicxml resp)]
                  (ct/ctrl-write! [:notation :session :musicxml] xml))
                (when-let [ly (:lilypond resp)]
                  (ct/ctrl-write! [:notation :session :lilypond] ly)))
              (log-err "export-session" (:message resp))))
          (log-err "export-session" "no key events found in beat range")))
      (catch Exception e
        (log-err "export-session" (.getMessage e))))))

(defn save-session!
  "Write the current session LilyPond source to <session-dir>/notation.ly
  and compile to PDF if the `lilypond` binary is available on PATH.
  Returns the .ly path written, or nil when no LilyPond content is available."
  []
  ;; Read from the ctrl-tree STM store (refs/tree-state) — the same store
  ;; export-session! writes to via ct/ctrl-write!. Reading via nous.ctrl/get
  ;; here would query the *other* store and always find nil.
  (when-let [ly (get @refs/tree-state [:notation :session :lilypond])]
    (let [session-dir (or (ctrl/get [:session :dir]) (dirs/sessions-dir))
          ly-path     (str session-dir "/notation.ly")]
      (spit ly-path ly)
      (try
        (let [proc (-> (ProcessBuilder. ["lilypond" "-o" session-dir ly-path])
                       (.redirectErrorStream true)
                       .start)]
          (when-not (zero? (.waitFor proc))
            (log-err "save-session" "lilypond exited non-zero; .ly file preserved")))
        (catch Exception _
          (log-err "save-session" (str "lilypond not on PATH; .ly saved to " ly-path))))
      ly-path)))

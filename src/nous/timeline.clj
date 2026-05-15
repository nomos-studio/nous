; SPDX-License-Identifier: EPL-2.0
(ns cljseq.timeline
  "Master timeline — shared beat/wall-clock coordinate system for cljseq.

  Holds a reference to the system-state atom injected by cljseq.core/start!
  via -register-system!. Provides read-only accessors so cljseq.loop,
  cljseq.ctrl, cljseq.sc, and other consumers can query beat position and
  tempo without introducing a circular dependency on cljseq.core.

  The local timeline is stored under :timeline in system-state as:
    {:bpm            <double>   ; current tempo
     :beat0-epoch-ms <long>     ; wall-clock ms at the anchor beat
     :beat0-beat     <double>}  ; beat number at the anchor

  When Ableton Link is active, :link-timeline carries the same shape but
  anchored to the Link session. effective-timeline returns whichever is live.

  Key design decisions: Q1 (virtual time), Q59 (beat↔epoch-ms arithmetic),
  Q60 (drift-free LockSupport park)."
  (:require [cljseq.clock :as clock])
  (:import  [java.util UUID]))

;; ---------------------------------------------------------------------------
;; System state reference — injected by cljseq.core/start!
;; ---------------------------------------------------------------------------

(def ^:private system-ref (atom nil))

(defn -register-system!
  [state-atom]
  (reset! system-ref state-atom))

;; ---------------------------------------------------------------------------
;; Sortable UUID
;; ---------------------------------------------------------------------------

(defn squuid
  "Return a time-sortable UUID.  Upper 32 bits encode the current epoch-second;
  remaining 96 bits are random.  Results within the same second sort by random
  suffix; ordering across seconds is preserved."
  []
  (let [epoch-s     (quot (System/currentTimeMillis) 1000)
        base        (UUID/randomUUID)
        high        (.getMostSignificantBits base)
        low         (.getLeastSignificantBits base)
        new-high    (bit-or (bit-shift-left epoch-s 32)
                            (bit-and high 0xFFFFFFFF))]
    (UUID. new-high low)))

;; ---------------------------------------------------------------------------
;; Timeline accessors
;; ---------------------------------------------------------------------------

(defn- local-timeline []
  (when-let [s @system-ref] (:timeline @s)))

(defn- link-timeline []
  (when-let [s @system-ref] (:link-timeline @s)))

(defn effective-timeline
  "Return the active timeline map: Link timeline when Link is active,
  local timeline otherwise.  Returns nil when the system is not started."
  []
  (or (link-timeline) (local-timeline)))

(defn current-beat
  "Current beat position on the effective timeline.  Returns 0.0 before start!."
  ^double []
  (if-let [tl (effective-timeline)]
    (clock/epoch-ms->beat (System/currentTimeMillis) tl)
    0.0))

(defn current-bpm
  "Current BPM from the effective timeline.  Returns 120.0 before start!."
  ^double []
  (if-let [tl (effective-timeline)]
    (double (:bpm tl))
    120.0))

(defn current-wall-ms
  "Current wall-clock time in milliseconds."
  ^long []
  (System/currentTimeMillis))

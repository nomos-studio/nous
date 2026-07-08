; SPDX-License-Identifier: EPL-2.0
(ns nous.timeline
  "Master timeline — shared beat/wall-clock coordinate system for nous.

  Holds a reference to the system-state atom injected by nous.core/start!
  via -register-system!. Provides read-only accessors so nous.loop,
  nous.ctrl, nous.sc, and other consumers can query beat position and
  tempo without introducing a circular dependency on nous.core.

  The local timeline is stored under :timeline in system-state as:
    {:bpm            <double>   ; current tempo
     :beat0-epoch-ms <long>     ; wall-clock ms at the anchor beat
     :beat0-beat     <double>}  ; beat number at the anchor

  When Ableton Link is active, :link-time-id carries the time identity:
    {:current <timeline-map>  ; the BPM mapping in effect right now
     :pending <transition>}   ; deferred BPM change, or nil
  effective-timeline returns the :current component (or the local timeline).

  Key design decisions: Q1 (virtual time), Q59 (beat↔epoch-ms arithmetic),
  Q60 (drift-free LockSupport park)."
  (:require [nous.clock :as clock])
  (:import  [java.util UUID]))

;; ---------------------------------------------------------------------------
;; System state reference — injected by nous.core/start!
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
  (when-let [s @system-ref] (get-in @s [:link-time-id :current])))

(defn effective-timeline
  "Return the active timeline map: Link timeline when Link is active,
  local timeline otherwise.  Returns nil when the system is not started."
  []
  (or (link-timeline) (local-timeline)))

(defn time-identity
  "Return the full Link time identity map:
    {:current <timeline-map>   ; BPM mapping in effect right now
     :pending <transition>}    ; deferred BPM change (or nil)
  Returns nil before start! or before Link ticks have established a timeline."
  []
  (when-let [s @system-ref] (:link-time-id @s)))

(defn pending-timeline
  "Return the pending timeline map (the BPM mapping that will apply at the next
  bar boundary), or nil when no deferred transition is scheduled."
  []
  (get-in (time-identity) [:pending :timeline]))

(defn pending-apply-at
  "Return the beat position at which the pending BPM transition will be applied,
  or nil when no deferred transition is scheduled."
  []
  (get-in (time-identity) [:pending :apply-at]))

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

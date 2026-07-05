; SPDX-License-Identifier: EPL-2.0
(ns nous.transport-ctrl
  "Writes kairos/Link transport state into the ctrl-tree for UI consumption.

  Subscribes to the 24 PPQN MSG-TICK stream from nous.kairos/on-tick! and
  echoes beat boundaries and estimated BPM as ctrl-tree writes, which in turn
  propagate to BEAM via NousPort's ctrl_write_echo path.

  Paths published:
    [:transport :playing]  — boolean, true while ticks arrive
    [:transport :bpm]      — estimated BPM (double), updated once per beat
    [:transport :beat_n]   — integer beat counter, increments on each beat

  Start/stop:
    (transport-ctrl/start!)
    (transport-ctrl/stop!)

  Design note: writes happen only on beat boundaries (tick-n = 0) to avoid
  flooding the ctrl-tree and Jinterface echo at 24 PPQN.  BPM is sampled from
  nous.link/bpm at the same point."
  (:require [nous.kairos :as kairos]
            [nous.link   :as link]
            [ctrl-tree.core :as ct]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private tick-handler-key (atom nil))
(defonce ^:private started-state    (atom false))

;; ---------------------------------------------------------------------------
;; Internal tick handler
;; ---------------------------------------------------------------------------

(defn- on-beat-tick!
  "Called on every 24 PPQN tick; writes ctrl-tree only on beat boundaries."
  [{:keys [beat tick-n]}]
  (when (and beat (zero? (or tick-n 1)))
    (let [beat-n (int beat)
          bpm    (link/bpm)]
      (ct/ctrl-write! [:transport :beat_n]  beat-n)
      (ct/ctrl-write! [:transport :playing] true)
      (when bpm
        (ct/ctrl-write! [:transport :bpm] bpm)))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn start!
  "Register the tick handler and initialise ctrl-tree [:transport :*] paths.
  Safe to call multiple times — subsequent calls are no-ops."
  []
  (when-not @started-state
    (ct/ctrl-write! [:transport :playing] false)
    (ct/ctrl-write! [:transport :bpm]     120.0)
    (ct/ctrl-write! [:transport :beat_n]  0)
    (reset! tick-handler-key (kairos/on-tick! on-beat-tick!))
    (reset! started-state true)))

(defn stop!
  "Deregister the tick handler and mark transport as stopped."
  []
  (when @started-state
    (when-let [k @tick-handler-key]
      (kairos/off-tick! k)
      (reset! tick-handler-key nil))
    (ct/ctrl-write! [:transport :playing] false)
    (reset! started-state false)))

(defn started? [] @started-state)

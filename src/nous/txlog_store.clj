; SPDX-License-Identifier: EPL-2.0
(ns nous.txlog-store
  "In-memory txlog for nous. Wraps txlog.core + ctrl-tree.txlog.
  Provides a 120 BPM wall-clock beat function (no Link yet) and an in-memory
  SQLite backend. All ctrl-tree writes are automatically logged once start! is
  called. Link sync replaces current-beat in a later milestone."
  (:require [ctrl-tree.txlog :as ct-txlog]
            [txlog.core      :as txlog]))

(def ^:private bpm 120.0)

(defonce ^:private state
  (atom {:log nil :start-ns nil}))

(defn current-beat
  "Current beat at 120 BPM from the wall clock since start!.
  Returns 0.0 before start! is called."
  []
  (if-let [t0 (:start-ns @state)]
    (double (/ (* (- (System/nanoTime) t0) bpm) (* 60.0 1e9)))
    0.0))

(defn start!
  "Open an in-memory txlog and install it into the ctrl-tree.
  Idempotent — no-op if already running."
  []
  (when-not (:log @state)
    (let [log (txlog/open ":memory:")]
      (swap! state assoc :log log :start-ns (System/nanoTime))
      (ct-txlog/install! log current-beat)
      :started)))

(defn stop!
  "Uninstall the txlog sink and close the in-memory db."
  []
  (when-let [log (:log @state)]
    (ct-txlog/uninstall!)
    (txlog/close log)
    (swap! state assoc :log nil :start-ns nil)
    :stopped))

(defn running? [] (boolean (:log @state)))

(defn recent-entries
  "Return the last n entries from the in-memory txlog, newest first.
  Returns [] before start! or if the log is empty."
  ([]    (recent-entries 50))
  ([n]
   (when-let [log (:log @state)]
     (take n (reverse (txlog/read-all log))))))

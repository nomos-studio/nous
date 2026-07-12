; SPDX-License-Identifier: EPL-2.0
(ns nous.beam-mount
  "BeamMount — IMount implementation that echoes ctrl-tree writes back to BEAM.

  Registered in the ctrl-tree mount-table at a path prefix (e.g. [:input :keyboard]).
  After every ctrl-write! that matches the prefix, mount-write! sends a
  :ctrl_write_echo message to the Elixir.NomosBeam.NousPort registered process.

  The echo map carries beat, wall_ns, and source so NousPort can populate the
  TxlogBuffer without a separate IPC round-trip.

  The BeamMount runs post-commit (never inside dosync) so it is safe to block on
  the Jinterface send call."
  (:require [protomatter.protocols :as p])
  (:import [com.ericsson.otp.erlang
            OtpMbox
            OtpErlangAtom OtpErlangBinary OtpErlangDouble OtpErlangList
            OtpErlangLong OtpErlangMap OtpErlangObject]))

;; ── Clojure → Erlang term ────────────────────────────────────────────────────

(defn- ^OtpErlangObject clj->otp [v]
  (cond
    (keyword? v)  (OtpErlangAtom. ^String (name v))
    (boolean? v)  (OtpErlangAtom. ^String (str v))
    (string? v)   (OtpErlangBinary. (.getBytes ^String v "UTF-8"))
    (float? v)    (OtpErlangDouble. (double v))
    (integer? v)  (OtpErlangLong. (long v))
    (vector? v)   (OtpErlangList.
                    ^"[Lcom.ericsson.otp.erlang.OtpErlangObject;"
                    (into-array OtpErlangObject (mapv clj->otp v)))
    (map? v)      (OtpErlangMap.
                    ^"[Lcom.ericsson.otp.erlang.OtpErlangObject;"
                    (into-array OtpErlangObject (mapv clj->otp (keys v)))
                    ^"[Lcom.ericsson.otp.erlang.OtpErlangObject;"
                    (into-array OtpErlangObject (mapv clj->otp (vals v))))
    :else         (OtpErlangBinary. (.getBytes ^String (str v) "UTF-8"))))

;; ── BeamMount deftype ────────────────────────────────────────────────────────

;; NousPort is registered under its Elixir module atom.
(def ^:private nous-port-name "Elixir.NomosBeam.NousPort")

(deftype BeamMount [^OtpMbox mbox ^String beam-node beat-fn]
  p/IMount
  (mount-write! [_ path value]
    (try
      (.send mbox nous-port-name beam-node
             (clj->otp {:op      :ctrl_write_echo
                        :path    path
                        :value   value
                        :beat    (double (beat-fn))
                        :wall_ns (System/nanoTime)
                        :source  :ctrl_tree_write}))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "[nous.beam-mount] send error: " (.getMessage e)))))))
  (mount-recable! [_ _changes]
    nil))

(defn beam-mount
  "Construct a BeamMount.
  mbox       — OtpMbox to send from
  beam-node  — target BEAM node name string (e.g. \"nomos_beam@localhost\")
  beat-fn    — 0-arity fn returning current beat as double (from nous.txlog-store)"
  [^OtpMbox mbox ^String beam-node beat-fn]
  (BeamMount. mbox beam-node beat-fn))

;; ── Note event telemetry ─────────────────────────────────────────────────────

;; Registered by jinterface/start! once the mbox is live.
(defonce ^:private active-mbox      (atom nil))
(defonce ^:private active-beam-node (atom nil))

(defn register-sender!
  "Register the OtpMbox and BEAM node name for fire-and-forget note telemetry.
  Called by nous.jinterface/start! after the distribution node is created."
  [^OtpMbox mbox ^String beam-node]
  (reset! active-mbox mbox)
  (reset! active-beam-node beam-node))

(defn send-note-event!
  "Non-blocking: send a :note_event telemetry message to NousPort on BEAM.
  No-op when not connected. Swallows all exceptions."
  [midi vel beat dur-beats]
  (when-let [^OtpMbox mbox @active-mbox]
    (when-let [^String bn @active-beam-node]
      (try
        (.send mbox nous-port-name bn
               (clj->otp {:op    :note_event
                           :pitch (long midi)
                           :vel   (long vel)
                           :beat  (double beat)
                           :dur   (double dur-beats)}))
        (catch Exception _)))))

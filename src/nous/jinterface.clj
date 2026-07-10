; SPDX-License-Identifier: EPL-2.0
(ns nous.jinterface
  "Jinterface IPC bridge between nous (JVM) and nomos_beam (BEAM).

  Lifecycle:
    (start! \"nous@127.0.0.1\" \"nomos_studio_dev\" \"nomos_beam@127.0.0.1\")
    (stop!)

  The node creates a mailbox named ctrl. nomos_beam's NousPort GenServer
  sends Erlang maps to the ctrl mailbox on nous@localhost and receives echoes
  back on the Elixir.NomosBeam.NousPort registered name.

  Message protocol
  ────────────────
  BEAM → nous (ctrl mailbox):
    %{op: :ctrl_write, path: [...atoms...], value: binary}
    %{op: :service_down, service: :sc}      — ScSynth Port exited; nous marks SC :stopped
    %{op: :service_down, service: :aion}    — aion Port exited; nous calls aion/stop!
    %{op: :aion_reconnect}                  — aion restarted; nous reconnects at next bar

  nous → BEAM (:nous_port registered name):
    %{op: :ctrl_write_echo, path: [...atoms...], value: binary}
    (delivered by BeamMount post ctrl-write! commit)"
  (:require [ctrl-tree.core    :as ct]
            [ctrl-tree.refs    :as refs]
            [clojure.data.json  :as json]
            [nous.aion          :as aion]
            [nous.beam-mount    :as bm]
            [nous.kairos-voice  :as kairos-voice]
            [nous.m21           :as m21]
            [nous.notation      :as notation]
            [nous.runtime       :as runtime]
            [nous.sc-keyboard   :as sc-keyboard]
            [nous.txlog-store   :as tx])
  (:import [com.ericsson.otp.erlang
            OtpNode OtpMbox
            OtpErlangAtom OtpErlangBinary OtpErlangList
            OtpErlangLong OtpErlangMap OtpErlangObject
            OtpErlangString]))

;; ── State ────────────────────────────────────────────────────────────────────

(defonce ^:private state
  (atom {:node nil :mbox nil :thread nil :running-ref nil}))

;; ── Erlang term → Clojure ─────────────────────────────────────────────────────

(defn- otp->clj [^OtpErlangObject obj]
  (cond
    (instance? OtpErlangAtom obj)
    (let [s (.atomValue ^OtpErlangAtom obj)]
      (case s
        "true"  true
        "false" false
        (keyword s)))

    (instance? OtpErlangBinary obj)
    (String. (.binaryValue ^OtpErlangBinary obj) "UTF-8")

    (instance? OtpErlangString obj)
    (.stringValue ^OtpErlangString obj)

    (instance? OtpErlangLong obj)
    (.longValue ^OtpErlangLong obj)

    (instance? OtpErlangList obj)
    (mapv otp->clj (.elements ^OtpErlangList obj))

    (instance? OtpErlangMap obj)
    (let [m ^OtpErlangMap obj]
      (zipmap (mapv otp->clj (.keys m))
              (mapv otp->clj (.values m))))

    :else (str obj)))

;; ── Message dispatch ──────────────────────────────────────────────────────────

(defn- handle-msg! [msg-obj]
  (let [msg (otp->clj msg-obj)]
    (when (map? msg)
      (case (:op msg)
        :ctrl_write
        (let [{:keys [path value]} msg]
          (when (vector? path)
            (ct/ctrl-write! path value)
            (cond
              (= path [:input :keyboard :key_down])
              (do (when-let [note (aion/key->note value)]
                    (ct/ctrl-write! [:diagnostic :midi :note_on]
                                    {:note note :velocity 0.8 :channel 0}))
                  (aion/note-on!         value)
                  (sc-keyboard/key-down!  value)
                  (kairos-voice/note-on!  value))

              (= path [:input :keyboard :key_up])
              (do (when-let [note (aion/key->note value)]
                    (ct/ctrl-write! [:diagnostic :midi :note_off]
                                    {:note note :channel 0}))
                  (aion/note-off!        value)
                  (sc-keyboard/key-up!   value)
                  (kairos-voice/note-off! value)))))
        :service_down
        (case (:service msg)
          :sc     (runtime/set! [:sc :status] :stopped)
          :kairos (runtime/set! [:kairos :status] :stopped)
          :m21    (do (m21/disconnect!)
                      (runtime/set! [:m21 :status] :stopped))
          :aion   (do (aion/stop!)
                      (runtime/set! [:aion :status] :stopped))
          nil)

        :aion_reconnect
        (aion/connect-at-next-bar!)

        :corpus_query
        (let [query (:query msg)]
          (case query
            :list_chorales
            (future
              (try
                (let [nums (m21/list-chorales)]
                  (ct/ctrl-write! [:corpus :chorales] (json/write-str nums)))
                (catch Exception e
                  (binding [*out* *err*]
                    (println (str "[nous.jinterface] corpus list-chorales error: "
                                  (.getMessage e)))))))
            ;; {:metadata bwv} — fetch metadata for one chorale
            (when-let [bwv (:metadata query)]
              (future
                (try
                  (let [meta (m21/chorale-metadata bwv)]
                    (ct/ctrl-write! [:corpus :selected_metadata]
                                    (json/write-str meta)))
                  (catch Exception e
                    (binding [*out* *err*]
                      (println (str "[nous.jinterface] corpus metadata error: "
                                    (.getMessage e))))))))))

        :repl_eval
        ;; Evaluate a Clojure form sent from the browser REPL panel.
        ;; Runs in a future to avoid blocking the receive loop.
        (let [form (:form msg)]
          (when (string? form)
            (future
              (let [out (java.io.StringWriter.)
                    err (java.io.StringWriter.)]
                (let [result
                      (try
                        (let [v (binding [*ns*  (or (find-ns 'nous.user) *ns*)
                                          *out* out
                                          *err* err]
                                  (eval (read-string form)))]
                          {:form form :value (pr-str v) :out (str out) :err (str err)})
                        (catch Throwable t
                          {:form form
                           :error (str (.getSimpleName (class t)) ": " (.getMessage t))
                           :out (str out) :err (str err)}))]
                  (ct/ctrl-write! [:repl :last_result] (json/write-str result)))))))

        :notation_export_corpus
        (when-let [bwv (:bwv msg)]
          (notation/export-corpus! bwv))

        :notation_export_session
        (let [{:keys [beat_from beat_to]} msg]
          (when (and beat_from beat_to)
            (notation/export-session! beat_from beat_to)))

        :notation_save_session
        (notation/save-session!)

        ;; Ignore unrecognised ops
        nil))))

;; ── Receive loop (runs on daemon thread) ─────────────────────────────────────

(defn- receive-loop [^OtpMbox mbox running-ref]
  (try
    (loop []
      (when @running-ref
        (when-let [msg (try (.receive mbox 1000)
                            (catch com.ericsson.otp.erlang.OtpErlangExit _e nil)
                            (catch Exception _e nil))]
          (try
            (handle-msg! msg)
            (catch Exception e
              (binding [*out* *err*]
                (println (str "[nous.jinterface] dispatch error: " (.getMessage e)))))))
        (recur)))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "[nous.jinterface] receive loop fatal: " (.getMessage e)))))))

;; ── Lifecycle ─────────────────────────────────────────────────────────────────

(defn- ensure-epmd! []
  (try
    (.. (ProcessBuilder. ^java.util.List ["epmd" "-daemon"])
        (inheritIO)
        (start)
        (waitFor))
    (Thread/sleep 300)
    (catch Exception _)))

(defn start!
  "Start the Jinterface node and mount a BeamMount in the ctrl-tree.

  node-name  — this node's Erlang name, e.g. \"nous@127.0.0.1\"
  cookie     — shared BEAM cookie string, e.g. \"nomos_studio_dev\"
  beam-node  — BEAM peer node name for echoes, e.g. \"nomos_beam@127.0.0.1\""
  [node-name cookie beam-node]
  (when (:node @state)
    (throw (ex-info "nous.jinterface already running — call stop! first" {})))
  (let [node       (try (OtpNode. ^String node-name ^String cookie)
                        (catch java.io.IOException _
                          (ensure-epmd!)
                          (OtpNode. ^String node-name ^String cookie)))
        mbox       (.createMbox node "ctrl")
        running-a  (atom true)
        thread     (doto (Thread. ^Runnable #(receive-loop mbox running-a))
                     (.setDaemon true)
                     (.setName "nous-jinterface")
                     (.start))]
    (tx/start!)
    (dosync
      (alter refs/mount-table assoc [:input :keyboard]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:transport]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:theory]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:corpus]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:session]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:repl]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:notation]
             (bm/beam-mount mbox beam-node tx/current-beat))
      (alter refs/mount-table assoc [:diagnostic]
             (bm/beam-mount mbox beam-node tx/current-beat)))
    (swap! state assoc
           :node       node
           :mbox       mbox
           :thread     thread
           :running-ref running-a)
    :started))

(defn stop!
  "Stop the Jinterface node and remove the BeamMount from the ctrl-tree."
  []
  (let [{:keys [node running-ref]} @state]
    (when node
      (reset! running-ref false)
      (dosync
        (alter refs/mount-table dissoc [:input :keyboard])
        (alter refs/mount-table dissoc [:transport])
        (alter refs/mount-table dissoc [:theory])
        (alter refs/mount-table dissoc [:corpus])
        (alter refs/mount-table dissoc [:session])
        (alter refs/mount-table dissoc [:repl])
        (alter refs/mount-table dissoc [:notation])
        (alter refs/mount-table dissoc [:diagnostic]))
      (tx/stop!)
      (.close ^OtpNode node)
      (swap! state assoc :node nil :mbox nil :thread nil :running-ref nil)
      :stopped)))

(defn running?
  "Return true if the Jinterface node is currently started."
  []
  (boolean (:node @state)))

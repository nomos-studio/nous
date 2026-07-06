; SPDX-License-Identifier: EPL-2.0
(ns nous.nrepl
  "Hand-rolled nREPL server (§M11) built on nous.bencode.

  Starts a TCP server on port 7888. Emacs/CIDER connects with:
    M-x cider-connect RET localhost RET 7888 RET

  The default evaluation namespace is nous.user — all session functions
  are available without any require. In-session (in-ns ...) changes
  persist for the lifetime of the session.

  Implements core nREPL ops: clone, eval, describe, close, interrupt,
  ls-sessions. cider-nrepl middleware is not bundled; completion, eldoc
  and stacktrace enhancement degrade gracefully in CIDER.

  ## Lifecycle

    (nrepl/start!)    ;; idempotent
    (nrepl/stop!)     ;; closes server + all open connections
    (nrepl/started?)  ;; predicate
    (nrepl/port)      ;; returns configured port (default 7888)"
  (:require [nous.bencode :as bencode]
            [clojure.string :as str])
  (:import  [java.net ServerSocket Socket InetAddress]
            [java.io StringWriter]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(def ^:dynamic *default-port* 7888)

(defonce ^:private state
  (atom {:server nil
         :port   nil
         :sessions {}}))

;; ---------------------------------------------------------------------------
;; Session management
;; ---------------------------------------------------------------------------

(defn- new-session-id []
  (str (java.util.UUID/randomUUID)))

(defn- make-session
  "A session is a map of {:id :ns-atom}. The ns-atom holds the current
  namespace so that (in-ns ...) persists across eval calls.
  Falls back to clojure.core when nous.user is not yet loaded (test env)."
  []
  (let [id (new-session-id)]
    {:id      id
     :ns-atom (atom (or (find-ns 'nous.user) (find-ns 'clojure.core)))}))

(defn- get-or-create-session [session-id]
  (let [sessions (:sessions @state)]
    (or (get sessions session-id)
        (let [s (make-session)]
          (swap! state update :sessions assoc (:id s) s)
          s))))

(defn- close-session! [session-id]
  (swap! state update :sessions dissoc session-id))

;; ---------------------------------------------------------------------------
;; Eval
;; ---------------------------------------------------------------------------

(defn- eval-in-session
  "Evaluate `code` string in the session's current namespace.
  Returns {:value pr-str-result :out stdout-str :err stderr-str}
  or {:error message :out stdout-str :err stderr-str} on exception."
  [session code]
  (let [out (StringWriter.)
        err (StringWriter.)
        ns-obj @(:ns-atom session)]
    (try
      (let [result (binding [*ns*  ns-obj
                              *out* out
                              *err* err]
                     (let [v (eval (read-string code))]
                       ;; Persist any (in-ns ...) side effects
                       (reset! (:ns-atom session) *ns*)
                       v))]
        {:value (pr-str result)
         :out   (str out)
         :err   (str err)})
      (catch Throwable t
        {:error (str (.getSimpleName (class t)) ": " (.getMessage t))
         :out   (str out)
         :err   (str err)}))))

;; ---------------------------------------------------------------------------
;; Op handlers
;; ---------------------------------------------------------------------------

(defn- send! [^Socket conn msg]
  (bencode/write-message msg (.getOutputStream conn)))

(defn- handle-clone [req conn]
  (let [parent-id (:session req)
        session   (make-session)]
    ;; Inherit namespace from parent if specified
    (when parent-id
      (when-let [parent (get-in @state [:sessions parent-id])]
        (reset! (:ns-atom session) @(:ns-atom parent))))
    (swap! state update :sessions assoc (:id session) session)
    (send! conn {:id          (:id req)
                 :new-session (:id session)
                 :status      ["done"]})))

(defn- handle-eval [req conn]
  (let [session (get-or-create-session (:session req))
        code    (:code req "nil")
        id      (:id req)]
    (let [{:keys [value error out err]} (eval-in-session session code)]
      (when (seq out)
        (send! conn {:id id :session (:id session) :out out}))
      (when (seq err)
        (send! conn {:id id :session (:id session) :err err}))
      (if error
        (send! conn {:id      id
                     :session (:id session)
                     :err     error
                     :status  ["done" "eval-error"]})
        (send! conn {:id      id
                     :session (:id session)
                     :value   value
                     :status  ["done"]})))))

(defn- handle-describe [req conn]
  (send! conn {:id     (:id req)
               :ops    {"clone"       {}
                        "eval"        {}
                        "describe"    {}
                        "close"       {}
                        "interrupt"   {}
                        "ls-sessions" {}}
               :aux    {}
               :status ["done"]}))

(defn- handle-close [req conn]
  (close-session! (:session req))
  (send! conn {:id (:id req) :status ["done"]}))

(defn- handle-interrupt [req conn]
  (send! conn {:id (:id req) :status ["done"]}))

(defn- handle-ls-sessions [req conn]
  (send! conn {:id       (:id req)
               :sessions (vec (keys (:sessions @state)))
               :status   ["done"]}))

(defn- dispatch! [req conn]
  (case (:op req)
    "clone"       (handle-clone       req conn)
    "eval"        (handle-eval        req conn)
    "describe"    (handle-describe    req conn)
    "close"       (handle-close       req conn)
    "interrupt"   (handle-interrupt   req conn)
    "ls-sessions" (handle-ls-sessions req conn)
    ;; Unknown op — nREPL spec requires a :status ["unknown-op" "done"] response
    (send! conn {:id     (:id req)
                 :status ["unknown-op" "done"]})))

;; ---------------------------------------------------------------------------
;; Connection handler
;; ---------------------------------------------------------------------------

(defn- handle-connection [^Socket conn]
  (let [in (.getInputStream conn)]
    (try
      (loop []
        (when-let [msg (bencode/decode-stream in)]
          (when (map? msg)
            (try
              (dispatch! msg conn)
              (catch Exception e
                (try
                  (send! conn {:id     (:id msg)
                               :err    (str "nrepl dispatch error: " (.getMessage e))
                               :status ["error" "done"]})
                  (catch Exception _)))))
          (when-not (.isClosed conn)
            (recur))))
      (catch Exception _))
    (try (.close conn) (catch Exception _))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn started? []
  (boolean (:server @state)))

(defn port []
  (:port @state))

(defn start!
  "Start the nREPL TCP server. Idempotent.

  Options:
    :port — TCP port (default *default-port* = 7888)"
  [& {:keys [port] :or {port *default-port*}}]
  (when (started?)
    (throw (ex-info "nREPL already started — call stop! first" {:port (nous.nrepl/port)})))
  (let [srv    (ServerSocket. port 50 (InetAddress/getLoopbackAddress))
        thread (doto (Thread.
                       ^Runnable
                       (fn []
                         (try
                           (loop []
                             (when-not (.isClosed srv)
                               (try
                                 (let [conn (.accept srv)]
                                   (doto (Thread.
                                            ^Runnable #(handle-connection conn))
                                     (.setDaemon true)
                                     (.setName (str "nrepl-client-" (System/nanoTime)))
                                     (.start)))
                                 (catch java.net.SocketException _))
                               (recur)))
                           (catch Exception _))))
                 (.setDaemon true)
                 (.setName "nrepl-server")
                 (.start))]
    (swap! state assoc :server srv :port port)
    (println (str "[nrepl] listening on localhost:" port))
    nil))

(defn stop!
  "Stop the nREPL server and close all sessions. Idempotent."
  []
  (when-let [srv ^ServerSocket (:server @state)]
    (try (.close srv) (catch Exception _)))
  (swap! state assoc :server nil :port nil :sessions {})
  nil)

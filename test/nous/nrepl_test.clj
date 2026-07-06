; SPDX-License-Identifier: EPL-2.0
(ns nous.nrepl-test
  "Tests for nous.nrepl — hand-rolled nREPL TCP server.

  Tests run a real server on an ephemeral port, connect via TCP,
  and exercise the core nREPL ops over the wire."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [nous.bencode  :as bencode]
            [nous.nrepl    :as nrepl])
  (:import  [java.net Socket InetAddress]
            [java.io BufferedInputStream]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- find-free-port []
  (with-open [srv (java.net.ServerSocket. 0)]
    (.getLocalPort srv)))

(defn- connect! [port]
  (let [sock (Socket. (InetAddress/getLoopbackAddress) port)]
    {:sock sock
     :in   (BufferedInputStream. (.getInputStream sock))
     :out  (.getOutputStream sock)}))

(defn- send-msg! [{:keys [out]} msg]
  (bencode/write-message msg out))

(defn- recv-msg! [{:keys [in]}]
  (bencode/decode-stream in))

(defn- rpc! [conn msg]
  (send-msg! conn msg)
  (loop [frames []]
    (let [f (recv-msg! conn)]
      (let [frames' (conj frames f)
            status  (get f :status [])]
        (if (some #{"done" "error" "unknown-op"} status)
          frames'
          (recur frames'))))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private test-port (atom nil))

(use-fixtures :once
  (fn [f]
    (let [p (find-free-port)]
      (reset! test-port p)
      (nrepl/start! :port p)
      (try (f) (finally (nrepl/stop!))))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(deftest lifecycle-started
  (is (true? (nrepl/started?)))
  (is (= @test-port (nrepl/port))))

(deftest idempotent-start-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (nrepl/start! :port @test-port))))

;; ---------------------------------------------------------------------------
;; describe op
;; ---------------------------------------------------------------------------

(deftest describe-returns-ops
  (let [conn   (connect! @test-port)
        frames (rpc! conn {:op "describe" :id "d1"})]
    (try
      (let [resp (first frames)]
        (is (= "d1" (:id resp)))
        (is (some? (:ops resp)))
        (is (contains? (:ops resp) :eval))
        (is (contains? (:ops resp) :clone))
        (is (some #{"done"} (:status resp))))
      (finally (.close (:sock conn))))))

;; ---------------------------------------------------------------------------
;; clone op
;; ---------------------------------------------------------------------------

(deftest clone-returns-new-session
  (let [conn   (connect! @test-port)
        frames (rpc! conn {:op "clone" :id "c1"})]
    (try
      (let [resp (first frames)]
        (is (= "c1" (:id resp)))
        (is (string? (:new-session resp)))
        (is (not (clojure.string/blank? (:new-session resp))))
        (is (some #{"done"} (:status resp))))
      (finally (.close (:sock conn))))))

;; ---------------------------------------------------------------------------
;; eval op
;; ---------------------------------------------------------------------------

(deftest eval-arithmetic
  (let [conn      (connect! @test-port)
        clone-r   (rpc! conn {:op "clone" :id "cl-1"})
        session   (:new-session (first clone-r))
        frames    (rpc! conn {:op "eval" :code "(+ 1 2)" :id "e1" :session session})]
    (try
      (let [value-f (first (filter :value frames))
            done-f  (first (filter #(some #{"done"} (:status %)) frames))]
        (is (= "3" (:value value-f)))
        (is (some? done-f)))
      (finally (.close (:sock conn))))))

(deftest eval-stdout-captured
  (let [conn    (connect! @test-port)
        clone-r (rpc! conn {:op "clone" :id "cl-2"})
        session (:new-session (first clone-r))
        frames  (rpc! conn {:op "eval" :code "(print \"hello\")" :id "e2" :session session})]
    (try
      (let [out-f (first (filter :out frames))]
        (is (= "hello" (:out out-f))))
      (finally (.close (:sock conn))))))

(deftest eval-error-returned
  (let [conn    (connect! @test-port)
        clone-r (rpc! conn {:op "clone" :id "cl-3"})
        session (:new-session (first clone-r))
        frames  (rpc! conn {:op "eval" :code "(/ 1 0)" :id "e3" :session session})]
    (try
      (let [status (mapcat :status frames)]
        (is (some #{"eval-error"} status)))
      (finally (.close (:sock conn))))))

;; ---------------------------------------------------------------------------
;; unknown op
;; ---------------------------------------------------------------------------

(deftest unknown-op-returns-unknown-op-status
  (let [conn   (connect! @test-port)
        frames (rpc! conn {:op "nonexistent" :id "u1"})]
    (try
      (let [status (mapcat :status frames)]
        (is (some #{"unknown-op"} status)))
      (finally (.close (:sock conn))))))

; SPDX-License-Identifier: EPL-2.0
(ns nous.m21-test
  "Unit tests for nous.m21.

  All tests run without a Python interpreter or live server.
  Socket connections are injected via *open-channel*; the request/response
  layer is mocked via with-redefs; on-disk cache tests use a temp directory
  bound via *cache-dir*."
  (:require [clojure.test      :refer [deftest is testing use-fixtures]]
            [clojure.java.io   :as io]
            [clojure.data.json :as json]
            [nous.m21          :as m21])
  (:import [java.io PipedReader PipedWriter BufferedReader BufferedWriter]))

;; ---------------------------------------------------------------------------
;; Helpers to access private state
;;
;; @#'nous.m21/mem-cache dereferences the Var to obtain the atom; then
;; operations like reset!, swap!, deref act on the atom itself.
;; ---------------------------------------------------------------------------

(defn- mem-atom    [] @#'nous.m21/mem-cache)
(defn- server-atom [] @#'nous.m21/server-state)

(defn- parse-edn    [s]       (#'nous.m21/parse-edn s))
(defn- cached-load  [id mode] (#'nous.m21/cached-load id mode))

;; ---------------------------------------------------------------------------
;; Pipe-pair helper — synthetic socket for connection tests
;; ---------------------------------------------------------------------------

(defn- make-pipe-pair
  "Return {:channel :pipe :writer bw :reader br :server-reader br2 :server-writer bw2}.
  :channel is a truthy sentinel; nous.m21 calls (boolean ch) to test connected?."
  []
  (let [in-reader  (PipedReader.)
        in-writer  (PipedWriter. in-reader)
        out-reader (PipedReader.)
        out-writer (PipedWriter. out-reader)]
    {:channel       :pipe
     :writer        (BufferedWriter. in-writer)
     :reader        (BufferedReader. out-reader)
     :server-reader (BufferedReader. in-reader)
     :server-writer (BufferedWriter. out-writer)}))

(defn- inject-open-channel [pipe]
  (fn [_socket-path]
    (select-keys pipe [:channel :writer :reader])))

;; ---------------------------------------------------------------------------
;; Test fixtures — isolate each test with fresh state
;; ---------------------------------------------------------------------------

(defn- reset-state! [f]
  (reset! (mem-atom) {})
  (m21/disconnect!)
  (f)
  (m21/disconnect!)
  (reset! (mem-atom) {}))

(use-fixtures :each reset-state!)

;; ---------------------------------------------------------------------------
;; parse-edn — strips ; comment lines
;; ---------------------------------------------------------------------------

(deftest parse-edn-plain-test
  (testing "parse-edn reads EDN without comments"
    (is (= [{:pitches [60 64 67] :dur/beats 1.0}]
           (parse-edn "[{:pitches [60 64 67] :dur/beats 1.0}]")))))

(deftest parse-edn-strips-comment-lines-test
  (testing "parse-edn strips leading ; comment lines"
    (let [src "; nous-m21-cache script-mtime=12345\n[{:rest true :dur/beats 0.5}]"]
      (is (= [{:rest true :dur/beats 0.5}]
             (parse-edn src))))))

(deftest parse-edn-inline-comment-preserved-test
  (testing "parse-edn strips whole-line ; comments but not other lines"
    (is (= 42 (parse-edn "  ; comment\n42")))))

;; ---------------------------------------------------------------------------
;; connected? / server-running? (alias) — initial state
;; ---------------------------------------------------------------------------

(deftest server-not-running-test
  (testing "server-running? / connected? false when disconnected"
    (is (false? (m21/connected?)))
    (is (false? (m21/server-running?)))))

(deftest server-info-nil-when-stopped-test
  (testing "server-info returns nil when not connected"
    (is (nil? (m21/server-info)))))

;; ---------------------------------------------------------------------------
;; connect! / disconnect! — M9 socket client lifecycle
;; ---------------------------------------------------------------------------

(deftest connect-and-disconnect-test
  (let [pipe (make-pipe-pair)]
    (binding [m21/*open-channel* (inject-open-channel pipe)]
      (testing "connect! returns true"
        (is (true? (m21/connect!))))

      (testing "connected? true after connect!"
        (is (true? (m21/connected?)))
        (is (true? (m21/server-running?))))

      (testing "server-info returns socket-path"
        (is (= "/tmp/m21.sock" (:socket-path (m21/server-info)))))

      (testing "double connect! throws"
        (is (thrown? clojure.lang.ExceptionInfo (m21/connect!))))

      (testing "disconnect! clears state"
        (m21/disconnect!)
        (is (false? (m21/connected?)))
        (is (nil? (m21/server-info))))

      (testing "disconnect! is idempotent"
        (m21/disconnect!)
        (is (false? (m21/connected?)))))))

(deftest stop-server-is-disconnect-alias-test
  (let [pipe (make-pipe-pair)]
    (binding [m21/*open-channel* (inject-open-channel pipe)]
      (m21/connect!)
      (m21/stop-server!)
      (is (false? (m21/connected?))))))

(deftest ensure-server-auto-connects-test
  (let [pipe (make-pipe-pair)]
    (binding [m21/*open-channel* (inject-open-channel pipe)]
      (is (false? (m21/connected?)))
      (m21/ensure-server!)
      (is (true? (m21/connected?)))
      ;; idempotent
      (m21/ensure-server!)
      (is (true? (m21/connected?))))))

(deftest server-call-roundtrip-test
  (let [pipe (make-pipe-pair)
        {:keys [server-reader server-writer]} pipe]
    (binding [m21/*open-channel* (inject-open-channel pipe)]
      (m21/connect!)
      (let [result-p (promise)
            t        (Thread. ^Runnable
                       #(deliver result-p
                          (try (m21/server-call! {"op" "ping" "id" 99})
                               (catch Exception e {:error (.getMessage e)}))))]
        (.start t)
        (let [raw (.readLine server-reader)
              req (json/read-str raw)]
          (is (= "ping" (get req "op")))
          (.write server-writer (json/write-str {"status" "ok" "id" 99}))
          (.newLine server-writer)
          (.flush server-writer))
        (.join t 3000)
        (let [resp (deref result-p 0 :timeout)]
          (is (= "ok" (:status resp)))
          (is (= 99  (:id resp))))))))

;; ---------------------------------------------------------------------------
;; eventually! — returns a promise
;; ---------------------------------------------------------------------------

(deftest eventually-returns-promise-test
  (testing "eventually! returns a promise that delivers the function result"
    (let [p (m21/eventually! (constantly :ok))]
      (is (= :ok (deref p 2000 :timeout))))))

(deftest eventually-exception-leaves-promise-undelivered-test
  (testing "eventually! with a throwing fn leaves the promise undelivered (timeout)"
    ;; The future catches the exception internally; promise is never delivered.
    (let [p (m21/eventually! #(throw (ex-info "boom" {})))]
      (is (= :timed-out (deref p 200 :timed-out))))))

;; ---------------------------------------------------------------------------
;; clear-cache! — no-arg arity clears everything
;; ---------------------------------------------------------------------------

(deftest clear-cache-all-test
  (testing "clear-cache! with no args resets in-memory cache to {}"
    (reset! (mem-atom) {[371 :chords] [{:pitches [60] :dur/beats 1.0}]
                        [4   :parts]  {:soprano []}})
    (m21/clear-cache!)
    (is (= {} @(mem-atom)))))

(deftest clear-cache-all-removes-disk-files-test
  (testing "clear-cache! deletes on-disk cache files"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "m21-test-" (System/currentTimeMillis)))]
      (.mkdirs tmp-dir)
      (let [f (io/file tmp-dir "bwv371-chords.edn")]
        (spit f "[]")
        (binding [m21/*cache-dir* (str tmp-dir)]
          (m21/clear-cache!))
        (is (not (.exists f)))
        (.delete tmp-dir)))))

;; ---------------------------------------------------------------------------
;; clear-cache! — single bwv-id arity
;; ---------------------------------------------------------------------------

(deftest clear-cache-single-bwv-test
  (testing "clear-cache! with bwv-id evicts only that chorale from mem cache"
    (reset! (mem-atom) {[371 :chords] :data-371
                        [4   :chords] :data-4})
    (m21/clear-cache! 371)
    (is (nil? (get @(mem-atom) [371 :chords])))
    (is (= :data-4 (get @(mem-atom) [4 :chords])))))

(deftest clear-cache-single-removes-both-modes-test
  (testing "clear-cache! bwv-id removes both :chords and :parts entries"
    (reset! (mem-atom) {[371 :chords] :c
                        [371 :parts]  :p})
    (m21/clear-cache! 371)
    (is (nil? (get @(mem-atom) [371 :chords])))
    (is (nil? (get @(mem-atom) [371 :parts])))))

;; ---------------------------------------------------------------------------
;; cached-load — memory cache hit
;; ---------------------------------------------------------------------------

(deftest cached-load-memory-hit-test
  (testing "cached-load returns in-memory entry without calling ensure-server!"
    (let [data          [{:pitches [60 64 67] :dur/beats 1.0}]
          server-called (atom false)]
      (reset! (mem-atom) {[371 :chords] data})
      (with-redefs [nous.m21/ensure-server! (fn [] (reset! server-called true))]
        (let [result (cached-load 371 :chords)]
          (is (= data result))
          (is (false? @server-called)))))))

;; ---------------------------------------------------------------------------
;; cached-load — disk cache hit
;; ---------------------------------------------------------------------------

(deftest cached-load-disk-hit-test
  (testing "cached-load reads from disk cache when mem-cache is empty"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "m21-disk-test-" (System/currentTimeMillis)))
          ;; Use the real script-mtime so the freshness check passes without stubbing.
          real-mtime (#'nous.m21/script-mtime)
          server-called (atom false)]
      (.mkdirs tmp-dir)
      (let [f (io/file tmp-dir "bwv371-chords.edn")]
        (spit f (str "; nous-m21-cache script-mtime=" real-mtime "\n"
                     "[{:pitches [60 64 67] :dur/beats 1.0}]"))
        (with-redefs [nous.m21/ensure-server! (fn [] (reset! server-called true))]
          (binding [m21/*cache-dir* (str tmp-dir)]
            (let [result (cached-load 371 :chords)]
              (is (= [{:pitches [60 64 67] :dur/beats 1.0}] result))
              (is (false? @server-called)))))
        (.delete f)
        (.delete tmp-dir)))))

;; ---------------------------------------------------------------------------
;; cached-load — server fallback
;; ---------------------------------------------------------------------------

(deftest cached-load-server-fallback-test
  (testing "cached-load calls the server when mem and disk caches are cold"
    (let [raw-edn       "[{:pitches [48 55 62 67] :dur/beats 2.0}]"
          server-req    (atom nil)
          ensure-called (atom false)]
      (with-redefs [nous.m21/ensure-server!    (fn [] (reset! ensure-called true))
                    nous.m21/m21-request!      (fn [req]
                                                   (reset! server-req req)
                                                   {"status" "ok" "edn" raw-edn})
                    nous.m21/write-disk-cache! (fn [& _])]
        (let [result (cached-load 42 :chords)]
          (is (= [{:pitches [48 55 62 67] :dur/beats 2.0}] result))
          (is (true? @ensure-called))
          (is (= {"op" "load" "bwv" "42" "mode" "chords"} @server-req))
          (is (= [{:pitches [48 55 62 67] :dur/beats 2.0}]
                 (get @(mem-atom) [42 :chords]))))))))

(deftest cached-load-server-error-throws-test
  (testing "cached-load throws ex-info when server returns error status"
    (with-redefs [nous.m21/ensure-server! (fn [])
                  nous.m21/m21-request!   (fn [_]
                                              {"status" "error" "message" "not found"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"m21 server error"
                            (cached-load 9999 :chords))))))

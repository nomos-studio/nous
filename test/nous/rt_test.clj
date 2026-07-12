; SPDX-FileCopyrightText: 2025-2026 nomos-studio contributors
;
; SPDX-License-Identifier: EPL-2.0
(ns nous.rt-test
  (:require [clojure.test :refer [deftest is testing]]
            [nous.rt      :as rt])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel Channels]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Keyboard layout
;; ---------------------------------------------------------------------------

(deftest key->note-mapping-test
  (testing "chromatic layout starting from middle C"
    (is (= 60 (rt/key->note "a")))
    (is (= 61 (rt/key->note "w")))
    (is (= 62 (rt/key->note "s")))
    (is (= 63 (rt/key->note "e")))
    (is (= 64 (rt/key->note "d")))
    (is (= 65 (rt/key->note "f")))
    (is (= 66 (rt/key->note "t")))
    (is (= 67 (rt/key->note "g")))
    (is (= 68 (rt/key->note "y")))
    (is (= 69 (rt/key->note "h")))
    (is (= 70 (rt/key->note "u")))
    (is (= 71 (rt/key->note "j"))))

  (testing "unknown keys return nil"
    (is (nil? (rt/key->note "z")))
    (is (nil? (rt/key->note "")))))

;; ---------------------------------------------------------------------------
;; disconnected state
;; ---------------------------------------------------------------------------

(deftest connected?-false-when-idle-test
  (testing "connected? is false when no socket is open"
    (is (false? (rt/connected?)))))

;; ---------------------------------------------------------------------------
;; note-on! / note-off! no-op when disconnected
;; ---------------------------------------------------------------------------

(deftest note-on-noop-when-disconnected-test
  (testing "note-on! returns nil when no socket is open"
    (is (nil? (rt/note-on! "a"))))

  (testing "note-on! ignores unknown keys"
    (is (nil? (rt/note-on! "z"))))

  (testing "note-on! with explicit velocity is also a no-op"
    (is (nil? (rt/note-on! "a" 0.5)))))

(deftest note-off-noop-when-disconnected-test
  (testing "note-off! returns nil when no socket is open"
    (is (nil? (rt/note-off! "a"))))

  (testing "note-off! ignores unknown keys"
    (is (nil? (rt/note-off! "z")))))

;; ---------------------------------------------------------------------------
;; on-tick! / off-tick!
;; ---------------------------------------------------------------------------

(deftest on-tick-registration-test
  (testing "on-tick! returns a distinct key each call"
    (let [k1 (rt/on-tick! (fn [_]))
          k2 (rt/on-tick! (fn [_]))]
      (is (some? k1))
      (is (some? k2))
      (is (not= k1 k2))
      (rt/off-tick! k1)
      (rt/off-tick! k2)))

  (testing "off-tick! returns nil"
    (let [k (rt/on-tick! (fn [_]))]
      (is (nil? (rt/off-tick! k))))))

;; ---------------------------------------------------------------------------
;; connect! / disconnect! lifecycle with a mock socket
;; ---------------------------------------------------------------------------

(defn- temp-socket-path []
  (let [f (File/createTempFile "rt-test-" ".sock")]
    (.delete f)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(deftest connect-disconnect-lifecycle-test
  (testing "connect! sets connected? to true; disconnect! resets it"
    (let [sock-path (temp-socket-path)
          srv       (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                      (.bind (UnixDomainSocketAddress/of sock-path)))]
      (doto (Thread. #(try (.accept srv) (catch Exception _)))
        (.setDaemon true)
        .start)
      (Thread/sleep 30)
      (is (false? (rt/connected?)))
      (rt/connect! sock-path {:clap false :audio false} :retry 3)
      (is (true? (rt/connected?)))
      (is (= {:clap false :audio false} (rt/capabilities)))
      (is (false? (rt/has-capability? :clap)))
      (rt/disconnect!)
      (is (false? (rt/connected?)))
      (.close srv))))

(deftest capabilities-reflect-connect-args-test
  (testing "capabilities returns the map passed to connect!"
    (let [sock-path (temp-socket-path)
          srv       (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                      (.bind (UnixDomainSocketAddress/of sock-path)))]
      (doto (Thread. #(try (.accept srv) (catch Exception _)))
        (.setDaemon true)
        .start)
      (Thread/sleep 30)
      (rt/connect! sock-path {:clap true :audio true} :retry 3)
      (is (= {:clap true :audio true} (rt/capabilities)))
      (is (true? (rt/has-capability? :clap)))
      (is (true? (rt/has-capability? :audio)))
      (rt/disconnect!)
      (.close srv))))

;; ---------------------------------------------------------------------------
;; Push handler dispatch — diagnostic tap socket roundtrip
;; ---------------------------------------------------------------------------

(defn- write-ipc-frame
  "Write a minimal IPC frame [uint32-len][uint8-type][3 reserved][payload] to ch."
  [^java.nio.channels.WritableByteChannel ch msg-type ^bytes payload]
  (let [plen (alength payload)
        hdr  (doto (ByteBuffer/allocate 8)
               (.order ByteOrder/BIG_ENDIAN)
               (.putInt plen)
               (.put (unchecked-byte msg-type))
               (.put (byte 0)) (.put (byte 0)) (.put (byte 0))
               (.flip))]
    (.write ch hdr)
    (.write ch (ByteBuffer/wrap payload))))

(deftest diag-tap-socket-roundtrip-test
  (testing "0x54 frame written to socket traverses reader thread to *dispatch-diag*"
    ;; This exercises the untested glue: _diag-handler-registered wires
    ;; push-handler 0x54 → handle-diag-frame! → *dispatch-diag*.
    ;; The prior unit tests call handle-diag-frame! directly; this test goes
    ;; through the live reader thread and push handler dispatch table.
    (rt/disconnect!)
    (let [sock-path (temp-socket-path)
          captured  (atom nil)
          srv       (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                      (.bind (UnixDomainSocketAddress/of sock-path)))]
      (doto (Thread.
             (fn []
               (try
                 (let [client (.accept srv)]
                   (write-ipc-frame client 0x54
                                    (.getBytes "{:bytes [144 60 101]}" "UTF-8")))
                 (catch Exception _))))
        (.setDaemon true)
        .start)
      (Thread/sleep 30)
      ;; with-redefs changes the root binding — visible to the reader thread
      ;; which is a plain Thread and does not inherit dynamic bindings.
      (with-redefs [rt/*dispatch-diag* #(reset! captured %)]
        (rt/connect! sock-path {} :retry 3)
        (let [deadline (+ (System/currentTimeMillis) 2000)]
          (loop []
            (when (and (nil? @captured)
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 10)
              (recur))))
        (rt/disconnect!))
      (.close srv)
      (is (some? @captured) "push handler dispatched frame within 2 s")
      (is (= {:bytes [144 60 101]} @captured)
          "correct EDN delivered to *dispatch-diag*"))))

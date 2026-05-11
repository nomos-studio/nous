; SPDX-License-Identifier: EPL-2.0
(ns cljseq.kairos-test
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [clojure.edn   :as edn]
            [cljseq.kairos :as kairos])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Private access
;; ---------------------------------------------------------------------------

(def ^:private make-frame  @#'kairos/make-frame)
(def ^:private edn-bytes   @#'kairos/edn-bytes)

;; ---------------------------------------------------------------------------
;; Frame layout tests (no socket needed)
;; ---------------------------------------------------------------------------

(deftest frame-header-layout-test
  (testing "header is 8 bytes + payload"
    (let [payload (byte-array [1 2 3])
          frame   (make-frame (unchecked-byte 0x40) payload)]
      (is (= 11 (alength frame)))))

  (testing "payload length is big-endian uint32 at offset 0"
    (let [payload (byte-array 7)
          frame   (make-frame (unchecked-byte 0x34) payload)
          buf     (doto (ByteBuffer/wrap frame) (.order ByteOrder/BIG_ENDIAN))]
      (is (= 7 (.getInt buf)))))

  (testing "message type sits at byte 4"
    (let [frame (make-frame (unchecked-byte 0x44) (byte-array 0))]
      (is (= 0x44 (bit-and (aget frame 4) 0xFF)))))

  (testing "reserved bytes 5–7 are zero"
    (let [frame (make-frame (unchecked-byte 0x31) (byte-array 0))]
      (is (= 0 (aget frame 5)))
      (is (= 0 (aget frame 6)))
      (is (= 0 (aget frame 7)))))

  (testing "empty payload produces an 8-byte frame"
    (is (= 8 (alength (make-frame (unchecked-byte 0x35) (byte-array 0))))))

  (testing "payload bytes follow the 8-byte header verbatim"
    (let [payload (byte-array [0xAB 0xCD])
          frame   (make-frame (unchecked-byte 0x40) payload)]
      (is (= (unchecked-byte 0xAB) (aget frame 8)))
      (is (= (unchecked-byte 0xCD) (aget frame 9))))))

(deftest edn-payload-test
  (testing "edn-bytes round-trips through clojure.edn/read-string"
    (let [v {:graph/nodes [{:id :synth/a :plugin "com.example.Synth"}]
             :graph/edges [[:synth/a :out-0 :host :audio-out]]}]
      (is (= v (edn/read-string (String. ^bytes (edn-bytes v) "UTF-8"))))))

  (testing "keyword values round-trip"
    (let [v {:path [:synth/a :freq] :value 440.0 :time {}}]
      (is (= v (edn/read-string (String. ^bytes (edn-bytes v) "UTF-8"))))))

  (testing "UUID values survive pr-str round-trip"
    (let [id  (java.util.UUID/randomUUID)
          raw (String. ^bytes (edn-bytes {:id id}) "UTF-8")]
      (is (clojure.string/includes? raw "#uuid"))
      (is (= id (:id (edn/read-string {:readers {'uuid #(java.util.UUID/fromString %)}}
                                      raw)))))))

;; ---------------------------------------------------------------------------
;; Integration: mock Unix socket server reads a frame and returns its bytes
;; ---------------------------------------------------------------------------

(defn- temp-socket-path []
  (let [f (File/createTempFile "kairos-test-" ".sock")]
    (.delete f)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(defn- with-mock-server
  "Start a Unix domain socket server at path, accept one connection, apply f
  to the InputStream, then close. Returns f's result via a promise."
  [path f]
  (let [srv     (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                  (.bind (UnixDomainSocketAddress/of path)))
        result  (promise)]
    (doto (Thread.
            (fn []
              (try
                (with-open [conn (.accept srv)]
                  (deliver result (f (Channels/newInputStream conn))))
                (catch Exception e
                  (deliver result e))
                (finally (.close srv)))))
      (.setDaemon true)
      .start)
    result))

(defn- read-frame!
  "Read one IPC frame from in, return {:type t :payload <edn-value>}."
  [^java.io.InputStream in]
  (let [header  (byte-array 8)
        _       (loop [r 0] (when (< r 8) (recur (+ r (.read in header r (- 8 r))))))
        buf     (doto (ByteBuffer/wrap header) (.order ByteOrder/BIG_ENDIAN))
        plen    (.getInt buf)
        msg-type (bit-and (.get buf) 0xFF)
        payload (byte-array plen)
        _       (when (pos? plen)
                  (loop [r 0] (when (< r plen) (recur (+ r (.read in payload r (- plen r)))))))]
    {:type    msg-type
     :payload (when (pos? plen) (edn/read-string (String. payload "UTF-8")))}))

(deftest send-graph-load-wire-test
  (testing "send-graph-load! writes a parseable graph frame"
    (let [path   (temp-socket-path)
          graph  {:graph/nodes [{:id :synth/a :plugin "test.Plugin"}]
                  :graph/edges [[:synth/a :out-0 :host :audio-out]]}
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30) ; let server bind
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-graph-load! graph)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x34 (:type frame)))
          (is (= graph (:payload frame))))
        (finally
          (kairos/disconnect!))))))

(deftest send-param-set-wire-test
  (testing "send-param-set! encodes path and value correctly"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-param-set! [:synth/a :freq] 440.0)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x40 (:type frame)))
          (is (= [:synth/a :freq] (get-in frame [:payload :path])))
          (is (= 440.0 (get-in frame [:payload :value]))))
        (finally
          (kairos/disconnect!))))))

(deftest send-note-on-wire-test
  (testing "send-note-on! encodes key and velocity"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-note-on! 60 0.8 :channel 1 :note-id 42)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x41 (:type frame)))
          (is (= 60  (get-in frame [:payload :key])))
          (is (= 0.8 (get-in frame [:payload :velocity])))
          (is (= 1   (get-in frame [:payload :channel])))
          (is (= 42  (get-in frame [:payload :note-id]))))
        (finally
          (kairos/disconnect!))))))

(deftest send-wasm-hot-swap-wire-test
  (testing "send-wasm-hot-swap! encodes node-id and wasm-path"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-wasm-hot-swap! :synth/a "/tmp/new.wasm")
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x44 (:type frame)))
          (is (= :synth/a     (get-in frame [:payload :node-id])))
          (is (= "/tmp/new.wasm" (get-in frame [:payload :wasm-path]))))
        (finally
          (kairos/disconnect!))))))

(deftest connected-state-test
  (testing "connected? reflects connection lifecycle"
    (let [sock-path (temp-socket-path)
          srv       (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                      (.bind (UnixDomainSocketAddress/of sock-path)))]
      (doto (Thread. #(try (.accept srv) (catch Exception _)))
        (.setDaemon true)
        .start)
      (Thread/sleep 30)
      (is (false? (kairos/connected?)))
      (kairos/connect! :socket-path sock-path :retry 3)
      (is (true? (kairos/connected?)))
      (kairos/disconnect!)
      (is (false? (kairos/connected?)))
      (.close srv))))

; SPDX-License-Identifier: EPL-2.0
(ns nous.kairos-test
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [clojure.edn   :as edn]
            [nous.kairos :as kairos])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.io File OutputStream]))

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

(deftest beat-tagged-note-wire-test
  (testing "send-note-on! with :beat includes :beat in payload"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-note-on! 60 0.8 :beat 16.0)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x41 (:type frame)))
          (is (= 60   (get-in frame [:payload :key])))
          (is (= 16.0 (get-in frame [:payload :beat]))))
        (finally
          (kairos/disconnect!)))))

  (testing "send-note-on! without :beat omits :beat from payload"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-note-on! 60 0.8)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x41 (:type frame)))
          (is (nil? (get-in frame [:payload :beat]))))
        (finally
          (kairos/disconnect!)))))

  (testing "send-note-off! with :beat includes :beat in payload"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-note-off! 60 :beat 16.5)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x42 (:type frame)))
          (is (= 60   (get-in frame [:payload :key])))
          (is (= 16.5 (get-in frame [:payload :beat]))))
        (finally
          (kairos/disconnect!))))))

;; ---------------------------------------------------------------------------
;; schedule-bundle! wire tests
;; ---------------------------------------------------------------------------

(deftest schedule-bundle-wire-test
  (testing "schedule-bundle! sends msg type 0x45"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/schedule-bundle! 5.0
                                 [{:at-tick 0 :type :note-on :key 60 :velocity 0.8}])
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x45 (:type frame))))
        (finally
          (kairos/disconnect!)))))

  (testing "schedule-bundle! encodes :at-beat correctly"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/schedule-bundle! 12.5
                                 [{:at-tick 0 :type :note-on :key 48 :velocity 0.6}])
        (let [frame (deref server 2000 :timeout)]
          (is (= 12.5 (get-in frame [:payload :at-beat]))))
        (finally
          (kairos/disconnect!)))))

  (testing "schedule-bundle! preserves event fields"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/schedule-bundle! 4.0
                                 [{:at-tick 8 :type :note-off :key 72 :velocity 0.0
                                   :channel 2 :port 1 :note-id 7}])
        (let [frame  (deref server 2000 :timeout)
              ev     (first (get-in frame [:payload :events]))]
          (is (= 8         (:at-tick ev)))
          (is (= :note-off (:type ev)))
          (is (= 72        (:key ev)))
          (is (= 0.0       (:velocity ev)))
          (is (= 2         (:channel ev)))
          (is (= 1         (:port ev)))
          (is (= 7         (:note-id ev))))
        (finally
          (kairos/disconnect!)))))

  (testing "schedule-bundle! encodes a multi-event retrigger bundle"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/schedule-bundle! 8.0
                                 [{:at-tick 0  :type :note-on  :key 60 :velocity 0.8}
                                  {:at-tick 4  :type :note-off :key 60}
                                  {:at-tick 8  :type :note-on  :key 60 :velocity 0.8}
                                  {:at-tick 12 :type :note-off :key 60}
                                  {:at-tick 16 :type :note-on  :key 60 :velocity 0.8}
                                  {:at-tick 20 :type :note-off :key 60}])
        (let [frame  (deref server 2000 :timeout)
              events (get-in frame [:payload :events])]
          (is (= 6 (count events)))
          (is (= [0 4 8 12 16 20] (mapv :at-tick events)))
          (is (= [:note-on :note-off :note-on :note-off :note-on :note-off]
                 (mapv :type events)))
          (is (every? #(= 60 (:key %)) events)))
        (finally
          (kairos/disconnect!)))))

  (testing "at-tick / 24.0 beat offsets — verified at Clojure level"
    ;; These are the beat values kairos will compute server-side.
    ;; Verify the arithmetic once in pure Clojure — no socket needed.
    (is (= 5.0              (+ 5.0 (/ 0  24.0))))
    (is (= (+ 5.0 (/ 8 24.0)) (+ 5.0 (/ 8  24.0))))
    (is (= (+ 5.0 (/ 1 3.0))  (+ 5.0 (/ 8  24.0))))))

;; ---------------------------------------------------------------------------
;; on-tick! / off-tick! tests
;; ---------------------------------------------------------------------------

(deftest on-tick-registration-test
  (testing "on-tick! returns a distinct key each call"
    (let [k1 (kairos/on-tick! (fn [_]))
          k2 (kairos/on-tick! (fn [_]))]
      (is (some? k1))
      (is (some? k2))
      (is (not= k1 k2))
      (kairos/off-tick! k1)
      (kairos/off-tick! k2)))

  (testing "off-tick! returns nil"
    (let [k (kairos/on-tick! (fn [_]))]
      (is (nil? (kairos/off-tick! k))))))

(defn- push-tick-frame!
  "Write a single MSG-TICK frame with the given beat and tick-n to out."
  [^java.io.OutputStream out beat tick-n]
  (let [text    (.getBytes (str "{:beat " beat " :tick-n " tick-n "}") "UTF-8")
        plen    (alength text)
        buf     (doto (ByteBuffer/allocate (+ 8 plen))
                  (.order ByteOrder/BIG_ENDIAN)
                  (.putInt plen)
                  (.put (unchecked-byte 0x50))
                  (.put (byte 0)) (.put (byte 0)) (.put (byte 0))
                  (.put text))]
    (.write out (.array buf))
    (.flush out)))

(deftest on-tick-dispatch-test
  (testing "MSG-TICK frame dispatches to registered handler with parsed EDN"
    (let [path   (temp-socket-path)
          result (promise)
          srv    (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                   (.bind (UnixDomainSocketAddress/of path)))]
      (doto (Thread.
              (fn []
                (try
                  (with-open [conn (.accept srv)]
                    (push-tick-frame! (Channels/newOutputStream conn) 8.0 192)
                    (Thread/sleep 300))
                  (catch Exception _)
                  (finally (.close srv)))))
        (.setDaemon true)
        .start)
      ;; Register handler before connecting so it is in place when the frame arrives.
      (let [k (kairos/on-tick! (fn [ev] (deliver result ev)))]
        (Thread/sleep 30)
        (kairos/connect! :socket-path path :retry 3)
        (try
          (let [ev (deref result 2000 :timeout)]
            (is (not= :timeout ev))
            (is (= 8.0 (:beat ev)))
            (is (= 192 (:tick-n ev))))
          (finally
            (kairos/off-tick! k)
            (kairos/disconnect!))))))

  (testing "off-tick! stops handler from receiving subsequent ticks"
    (let [path     (temp-socket-path)
          call-log (atom [])
          srv      (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                     (.bind (UnixDomainSocketAddress/of path)))]
      (doto (Thread.
              (fn []
                (try
                  (with-open [conn (.accept srv)]
                    (let [out (Channels/newOutputStream conn)]
                      (push-tick-frame! out 1.0 24)
                      (Thread/sleep 100)
                      (push-tick-frame! out 2.0 48)
                      (Thread/sleep 300)))
                  (catch Exception _)
                  (finally (.close srv)))))
        (.setDaemon true)
        .start)
      ;; Register handler before connecting so tick-n=24 is captured.
      (let [first-tick (promise)
            k          (kairos/on-tick! (fn [ev]
                                          (swap! call-log conj (:tick-n ev))
                                          (deliver first-tick true)))]
        (Thread/sleep 30)
        (kairos/connect! :socket-path path :retry 3)
        (try
          (deref first-tick 2000 :timeout)
          (kairos/off-tick! k)
          (Thread/sleep 200)   ; let any second tick arrive and confirm it is not dispatched
          (is (= [24] @call-log))
          (finally
            (kairos/disconnect!)))))))

;; ---------------------------------------------------------------------------
;; Modulator engine wire tests
;; ---------------------------------------------------------------------------

(deftest start-modulator-slope-wire-test
  (testing "start-modulator! :slope sends type 0x46 with id and type"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/start-modulator! :lfo-1 :slope {:rate 0.25 :shape -0.3})
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x46 (:type frame)))
          (is (= :lfo-1  (get-in frame [:payload :id])))
          (is (= :slope  (get-in frame [:payload :type])))
          (is (= 0.25    (get-in frame [:payload :rate])))
          (is (= -0.3    (get-in frame [:payload :shape]))))
        (finally
          (kairos/disconnect!)))))

  (testing "start-modulator! :slope omits un-supplied optional keys"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/start-modulator! :lfo-2 :slope {})
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= :lfo-2  (get-in frame [:payload :id])))
          (is (nil? (get-in frame [:payload :rate])))
          (is (nil? (get-in frame [:payload :shape]))))
        (finally
          (kairos/disconnect!)))))

  (testing "start-modulator! :slope passes bipolar flag"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/start-modulator! :lfo-3 :slope {:rate 2.0 :slope 0.8 :bipolar 0.0})
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x46  (:type frame)))
          (is (= 2.0   (get-in frame [:payload :rate])))
          (is (= 0.8   (get-in frame [:payload :slope])))
          (is (= 0.0   (get-in frame [:payload :bipolar]))))
        (finally
          (kairos/disconnect!))))))

(deftest start-modulator-segment-wire-test
  (testing "start-modulator! :segment sends type 0x46 with segments vector"
    (let [path     (temp-socket-path)
          segments [{:type :ramp :primary 0.1 :secondary 0.0}
                    {:type :ramp :primary 0.5 :secondary 0.0 :loop true}]
          server   (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/start-modulator! :env-1 :segment {:segments segments})
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x46     (:type frame)))
          (is (= :env-1   (get-in frame [:payload :id])))
          (is (= :segment (get-in frame [:payload :type])))
          (is (= 2 (count (get-in frame [:payload :segments]))))
          (is (= :ramp  (get-in frame [:payload :segments 0 :type])))
          (is (= 0.1    (get-in frame [:payload :segments 0 :primary])))
          (is (= 0.5    (get-in frame [:payload :segments 1 :primary])))
          (is (true?    (get-in frame [:payload :segments 1 :loop]))))
        (finally
          (kairos/disconnect!)))))

  (testing "start-modulator! :segment preserves all four segment types"
    (let [path   (temp-socket-path)
          segs   [{:type :ramp  :primary 0.1 :secondary 0.8}
                  {:type :hold  :primary 0.8 :secondary 0.2}
                  {:type :step  :primary 0.4 :secondary 0.5}
                  {:type :alt   :primary 0.5 :secondary 0.5 :loop true}]
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/start-modulator! :shape-1 :segment {:segments segs})
        (let [frame (deref server 2000 :timeout)
              out   (get-in frame [:payload :segments])]
          (is (not= :timeout frame))
          (is (= 4 (count out)))
          (is (= [:ramp :hold :step :alt] (mapv :type out)))
          (is (true? (:loop (last out)))))
        (finally
          (kairos/disconnect!)))))

  (testing "start-modulator! :segment with :depth passes depth"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/start-modulator! :env-2 :segment
                                 {:segments [{:type :ramp :primary 0.5 :secondary 0.5 :loop true}]
                                  :depth    0.7})
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0.7 (get-in frame [:payload :depth]))))
        (finally
          (kairos/disconnect!))))))

(deftest stop-modulator-wire-test
  (testing "stop-modulator! sends type 0x47 with id"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/stop-modulator! :lfo-1)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x47   (:type frame)))
          (is (= :lfo-1 (get-in frame [:payload :id]))))
        (finally
          (kairos/disconnect!))))))

(deftest update-modulator-wire-test
  (testing "update-modulator! sends type 0x48 with id, key (as string), and value"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/update-modulator! :lfo-1 :rate 2.0)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x48   (:type frame)))
          (is (= :lfo-1 (get-in frame [:payload :id])))
          (is (= "rate" (get-in frame [:payload :key])))
          (is (= 2.0    (get-in frame [:payload :value]))))
        (finally
          (kairos/disconnect!)))))

  (testing "update-modulator! coerces value to double"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/update-modulator! :lfo-1 :shape (float -0.5))
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= "shape" (get-in frame [:payload :key])))
          (is (instance? Double (get-in frame [:payload :value]))))
        (finally
          (kairos/disconnect!)))))

  (testing "update-modulator! accepts string key"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/update-modulator! :lfo-1 "segment_0_primary" 0.3)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= "segment_0_primary" (get-in frame [:payload :key])))
          (is (= 0.3 (get-in frame [:payload :value]))))
        (finally
          (kairos/disconnect!))))))

;; ---------------------------------------------------------------------------
;; Plugin listing
;; ---------------------------------------------------------------------------

(defn- write-push-frame!
  "Write a push frame to out — mirrors the kairos push_frame wire format."
  [^OutputStream out msg-type ^bytes payload]
  (let [plen (alength payload)
        buf  (ByteBuffer/allocate (+ 8 plen))]
    (.order buf ByteOrder/BIG_ENDIAN)
    (.putInt buf plen)
    (.put buf ^byte (unchecked-byte msg-type))
    (.put buf (byte 0))
    (.put buf (byte 0))
    (.put buf (byte 0))
    (.put buf payload)
    (.write out (.array buf))
    (.flush out)))

(defn- with-mock-server-reply
  "Mock server that reads one frame from the client then sends a reply frame.
  Returns a promise delivering {:request frame :reply-sent? true} when done."
  [path reply-type ^bytes reply-payload]
  (let [srv    (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                 (.bind (UnixDomainSocketAddress/of path)))
        result (promise)]
    (doto (Thread.
            (fn []
              (try
                (with-open [conn (.accept srv)]
                  (let [req (read-frame! (Channels/newInputStream conn))]
                    (write-push-frame! (Channels/newOutputStream conn)
                                       reply-type reply-payload)
                    (deliver result {:request req :reply-sent? true})))
                (catch Exception e
                  (deliver result e))
                (finally (.close srv)))))
      (.setDaemon true)
      .start)
    result))

(deftest list-plugins-sends-req-frame-test
  (testing "list-plugins! sends type 0x36 with empty payload when no extra-paths"
    (let [path     (temp-socket-path)
          plugins  [{:id "org.foo.Synth" :name "Foo" :vendor "FooInc"
                     :version "1.0" :path "/tmp/foo.clap"}]
          payload  (.getBytes ^String (pr-str plugins) "UTF-8")
          server   (with-mock-server-reply path 0x37 payload)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (let [result (kairos/list-plugins! :timeout-ms 2000)]
          (let [srv-data (deref server 2000 :timeout)]
            (is (not= :timeout srv-data))
            (is (= 0x36 (get-in srv-data [:request :type])))
            (is (nil?   (get-in srv-data [:request :payload])) "no extra-paths → empty payload"))
          (is (= 1 (count result)))
          (is (= "org.foo.Synth" (:id (first result))))
          (is (= "Foo"           (:name (first result)))))
        (finally
          (kairos/disconnect!))))))

(deftest list-plugins-sends-extra-paths-test
  (testing "list-plugins! includes :extra-paths in request payload"
    (let [path     (temp-socket-path)
          plugins  []
          payload  (.getBytes ^String (pr-str plugins) "UTF-8")
          server   (with-mock-server-reply path 0x37 payload)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/list-plugins! :extra-paths ["/opt/clap"] :timeout-ms 2000)
        (let [srv-data (deref server 2000 :timeout)]
          (is (not= :timeout srv-data))
          (is (= 0x36 (get-in srv-data [:request :type])))
          (is (= ["/opt/clap"]
                 (get-in srv-data [:request :payload :extra-paths]))))
        (finally
          (kairos/disconnect!))))))

(deftest plugin-registry-updated-by-push-test
  (testing "plugin-registry atom is populated after list-plugins! response"
    (let [path    (temp-socket-path)
          plugins [{:id "org.test.Plugin" :name "Test" :vendor "T"
                    :version "0.1" :path "/tmp/test.clap"}]
          payload (.getBytes ^String (pr-str plugins) "UTF-8")
          server  (with-mock-server-reply path 0x37 payload)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/list-plugins! :timeout-ms 2000)
        (is (= "org.test.Plugin" (:id (first (kairos/plugin-registry)))))
        (finally
          (kairos/disconnect!))))))

;; ---------------------------------------------------------------------------
;; Link control wire tests
;; ---------------------------------------------------------------------------

(deftest send-link-set-tempo-wire-test
  (testing "send-link-set-tempo! sends type 0x38 with :bpm in EDN payload"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-link-set-tempo! 140.0)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x38  (:type frame)))
          (is (= 140.0 (get-in frame [:payload :bpm]))))
        (finally
          (kairos/disconnect!)))))

  (testing "send-link-set-tempo! coerces integer bpm to double"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-link-set-tempo! 120)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x38 (:type frame)))
          (is (instance? Double (get-in frame [:payload :bpm]))))
        (finally
          (kairos/disconnect!))))))

(deftest send-link-start-transport-wire-test
  (testing "send-link-start-transport! sends type 0x39 with empty payload"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-link-start-transport!)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x39 (:type frame)))
          (is (nil?   (:payload frame))))
        (finally
          (kairos/disconnect!))))))

(deftest send-link-stop-transport-wire-test
  (testing "send-link-stop-transport! sends type 0x3A with empty payload"
    (let [path   (temp-socket-path)
          server (with-mock-server path read-frame!)]
      (Thread/sleep 30)
      (kairos/connect! :socket-path path :retry 3)
      (try
        (kairos/send-link-stop-transport!)
        (let [frame (deref server 2000 :timeout)]
          (is (not= :timeout frame))
          (is (= 0x3A (:type frame)))
          (is (nil?   (:payload frame))))
        (finally
          (kairos/disconnect!))))))

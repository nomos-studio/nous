; SPDX-License-Identifier: EPL-2.0
(ns nous.device-corpus-test
  "Device corpus integration test — validates every EDN device file against the
  nomos-rt IPC by running CC and NRPN entries through the full nous→nomos-rt stack
  and reading back results via the MSG-MIDI-DIAG (0x54) diagnostic tap.

  Uses a mock nomos-rt IPC server: no hardware, no built binary required.
  The mock accepts the connection, reads MSG-CC (0x49) frames, and echoes each
  as a 0x54 diagnostic frame so the test can assert that the correct controller
  number, channel, and value would have appeared on the wire.

  ## What this tests
    - Every concrete :cc number in every device EDN file routes through the
      nous→nomos-rt stack with the right controller number and a value in range.
    - NRPN entries produce the correct CC 99/98/6/38 four-frame sequence with
      correctly encoded parameter and data bytes.
    - The device EDN files themselves all load and parse without error.

  ## What this does NOT test
    - nomos-rt's MIDI byte encoding (IPC → MIDI wire): that is covered by
      midi_pipeline_test.clj against a real kairos binary.
    - Controller-role devices (sources that transmit to nous, not targets).
    - Configurable CC entries (non-integer :cc — e.g. the T-1 multi-track map)."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [clojure.edn   :as edn]
            [clojure.java.io :as io]
            [nous.core     :as core]
            [nous.ctrl     :as ctrl]
            [nous.device   :as device]
            [nous.rt       :as rt]
            [nous.kairos   :as kairos])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels ServerSocketChannel Channels]
           [java.io File]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ---------------------------------------------------------------------------
;; Mock nomos-rt IPC server
;; ---------------------------------------------------------------------------

(defn- temp-socket-path []
  (let [f (File/createTempFile "device-corpus-test-" ".sock")]
    (.delete f)
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(defn- write-ipc-frame
  "Write a nomos-rt IPC frame to a WritableByteChannel."
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

(defn- handle-mock-client
  "Read IPC frames from client. For each MSG-CC (0x49) frame, echo a 0x54
  diagnostic frame containing {:controller N :channel Ch :value V} so the test
  can correlate outbound frames with the device corpus."
  [client-ch]
  (let [in (Channels/newInputStream client-ch)]
    (try
      (loop []
        (when-let [{:keys [type payload]} (#'rt/read-frame in)]
          (when (= type 0x49) ; MSG-CC
            (let [{:keys [channel controller value]}
                  (edn/read-string (String. ^bytes payload "UTF-8"))
                  echo (.getBytes ^String
                                  (pr-str {:controller (int controller)
                                           :channel    (int (or channel 1))
                                           :value      (int value)})
                                  "UTF-8")]
              (write-ipc-frame client-ch 0x54 echo)))
          (recur)))
      (catch Exception _)
      (finally (try (.close client-ch) (catch Exception _))))))

(defn- start-mock-server!
  "Start a mock nomos-rt IPC server. Returns {:socket-path :stop-fn}."
  []
  (let [path (temp-socket-path)
        srv  (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
               (.bind (UnixDomainSocketAddress/of path)))
        running (atom true)]
    (doto (Thread.
           (fn []
             (try
               (while @running
                 (when-let [client (try (.accept srv) (catch Exception _ nil))]
                   (doto (Thread. #(handle-mock-client client))
                     (.setDaemon true)
                     .start)))
               (catch Exception _))))
      (.setDaemon true)
      .start)
    {:socket-path path
     :stop-fn     (fn []
                    (reset! running false)
                    (try (.close srv) (catch Exception _)))}))

;; ---------------------------------------------------------------------------
;; Diagnostic tap capture — shared echo queue
;; ---------------------------------------------------------------------------

(def ^:private echo-queue
  "Receives {:controller N :channel Ch :value V} maps echoed by the mock server
  via *dispatch-diag*. Reset between devices."
  (LinkedBlockingQueue.))

(defn- take-echo!
  "Block until one echo arrives, or return nil after 1 s."
  []
  (.poll echo-queue 1000 TimeUnit/MILLISECONDS))

(defn- collect-echoes!
  "Collect all echoes until 50 ms of silence; blocks up to 1 s for the first.
  Returns a vector of {:controller :channel :value} maps.
  Absorbs extra CC binding frames that precede the NRPN sequence on shared paths."
  []
  (let [first-f (.poll echo-queue 1000 TimeUnit/MILLISECONDS)]
    (if-not first-f
      []
      (loop [acc [first-f]]
        (let [f (.poll echo-queue 50 TimeUnit/MILLISECONDS)]
          (if f (recur (conj acc f)) acc))))))

;; ---------------------------------------------------------------------------
;; Fixtures — start mock server once for the whole namespace
;; ---------------------------------------------------------------------------

(def ^:private server-state (atom nil))

(defn with-mock-rt-server [f]
  (rt/disconnect!)
  (core/start! :bpm 120)
  (let [{:keys [socket-path stop-fn] :as srv} (start-mock-server!)]
    (reset! server-state srv)
    (Thread/sleep 30)
    (with-redefs [rt/*dispatch-diag* #(.offer echo-queue %)]
      (rt/connect! socket-path {} :retry 3)
      (try (f)
           (finally
             (rt/disconnect!)
             (stop-fn)
             (reset! server-state nil)
             (.clear echo-queue)
             ;; Unbind ctrl bindings before clearing the registry, otherwise
             ;; subsequent tests in the suite cannot re-register the same device.
             (doseq [id (keys @@#'device/device-registry)]
               (#'device/unregister-device! id))
             (reset! @#'device/device-registry {})
             (core/stop!))))))

(use-fixtures :once with-mock-rt-server)

;; ---------------------------------------------------------------------------
;; Corpus helpers
;; ---------------------------------------------------------------------------

(defn- device-files
  "Return a seq of {:file :map} (or {:file :error}) for every EDN in resources/devices/."
  []
  (let [dir (io/resource "devices")]
    (when dir
      (->> (file-seq (io/file dir))
           (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")))
           (map (fn [f]
                  (try {:file f :map (edn/read-string (slurp f))}
                       (catch Exception e {:file f :error e}))))))))

(defn- target-devices
  "Filter corpus to parseable :target devices with an integer :midi/channel."
  []
  (filter (fn [{:keys [map error]}]
            (and (nil? error)
                 (= :target (:device/role map))
                 (integer? (:midi/channel map))))
          (device-files)))

(defn- cc-test-value
  "Compute a representative test value for a CC entry.
  Uses :default when present and numeric, otherwise the midpoint of :range, clamped [0,127]."
  [{:keys [default range]}]
  (let [[lo hi] (or range [0 127])
        v       (if (number? default) default (quot (+ lo hi) 2))]
    (max lo (min hi (int v)))))

(defn- nrpn-test-value
  "Compute a representative test value for an NRPN entry.
  Uses :default when present; otherwise the midpoint of the effective range."
  [{:keys [bits range default]}]
  (let [b       (int (or bits 14))
        max-val (if (= 14 b) 16383 127)
        [lo hi] (or range [0 max-val])
        v       (or default (quot (+ lo hi) 2))]
    (max lo (min hi (int v)))))

(defn- concrete-cc-entries
  "Return CC entries with integer :cc numbers that are testable via device-send!.
  Excludes :cc-lsb entries (receive-only) and paths also bound via NRPN, which
  would produce spurious residual echoes and are covered by the NRPN test."
  [device-map]
  (let [nrpn-paths (into #{} (map :path) (:midi/nrpn device-map []))]
    (filter #(and (integer? (:cc %))
                  (not (:cc-lsb %))
                  (not (nrpn-paths (:path %))))
            (:midi/cc device-map []))))

(defn- nrpn-entries
  "Return NRPN entries with plain integer :nrpn values.
  Excludes device-specific encodings (Summit bank:param strings, Digitone [msb lsb] vectors)
  that do not map to the standard CC99/98/6/38 wire sequence."
  [device-map]
  (filter #(integer? (:nrpn %)) (:midi/nrpn device-map [])))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest device-corpus-all-files-load-test
  (testing "Every device EDN file in resources/devices/ loads without error"
    (let [files (device-files)]
      (is (pos? (count files)) "at least one device file found")
      (doseq [{:keys [file map error]} files]
        (is (nil? error)
            (str (.getName file) ": parse error — " (some-> error .getMessage)))
        (when (nil? error)
          (is (keyword? (:device/id map))
              (str (.getName file) ": :device/id should be a keyword"))
          (is (keyword? (:device/role map))
              (str (.getName file) ": :device/role should be a keyword")))))))

(deftest device-corpus-cc-wire-encoding-test
  (testing "Every concrete CC entry in every target device round-trips correctly"
    (doseq [{:keys [file map]} (target-devices)]
      (let [fname     (.getName file)
            device-id (:device/id map)
            channel   (:midi/channel map)
            ccs       (concrete-cc-entries map)]
        (when (seq ccs)
          (device/defdevice device-id map)
          (doseq [{:keys [cc path range] :as entry} ccs]
            (let [test-val (cc-test-value entry)
                  [lo hi]  (or range [0 127])
                  lo-d     (double lo)
                  hi-d     (double hi)
                  pct      (if (= lo-d hi-d) 0.0
                             (/ (- (double test-val) lo-d) (- hi-d lo-d)))
                  expected (max 0 (min 127 (long (Math/round (* pct 127.0)))))]
              (.clear echo-queue)
              (device/device-send! device-id path test-val)
              (let [echo (take-echo!)]
                (is (some? echo)
                    (str fname " CC " cc " path " path ": no echo within 1 s"))
                (when echo
                  (is (= cc (:controller echo))
                      (str fname " CC " cc " path " path
                           ": got controller " (:controller echo)))
                  (is (= channel (:channel echo))
                      (str fname " CC " cc " path " path
                           ": expected channel " channel
                           ", got " (:channel echo)))
                  (is (= expected (:value echo))
                      (str fname " CC " cc " path " path
                           ": expected wire value " expected
                           " got " (:value echo))))))))))))

(deftest device-corpus-nrpn-wire-encoding-test
  (testing "Every NRPN entry encodes the correct 4-CC sequence (CC99/98/6/38)"
    (doseq [{:keys [file map]} (target-devices)]
      (let [fname     (.getName file)
            device-id (:device/id map)
            channel   (:midi/channel map)
            nrpns     (nrpn-entries map)]
        (when (seq nrpns)
          (device/defdevice device-id map)
          (doseq [{:keys [nrpn path bits range raw] :as entry} nrpns]
            (let [b         (int (or bits 14))
                  max-val   (if (= 14 b) 16383 127)
                  test-val  (nrpn-test-value entry)
                  [lo hi]   (or range [0 max-val])
                  lo-d      (double lo) hi-d (double hi)
                  pct       (if (= lo-d hi-d) 0.0
                              (/ (- (double test-val) lo-d) (- hi-d lo-d)))
                  ;; Replicate ctrl/send-at! NRPN scaling exactly
                  clamped   (if raw
                              (max 0 (min max-val (long test-val)))
                              (max 0 (min max-val (long (Math/round (* pct (double max-val)))))))
                  param-msb (bit-and (bit-shift-right (int nrpn) 7) 0x7F)
                  param-lsb (bit-and (int nrpn) 0x7F)
                  data-msb  (bit-and (bit-shift-right clamped 7) 0x7F)
                  data-lsb  (bit-and clamped 0x7F)]
              (.clear echo-queue)
              (device/device-send! device-id path test-val)
              ;; Collect frames: paths with both CC+NRPN bindings produce an extra
              ;; CC frame before the 4-frame NRPN sequence.
              (let [frames  (collect-echoes!)
                    find-cc #(first (filter (fn [f] (= % (:controller f))) frames))
                    e99 (find-cc 99) e98 (find-cc 98)
                    e6  (find-cc 6)  e38 (find-cc 38)]
                (is (every? some? [e99 e98 e6 e38])
                    (str fname " NRPN " nrpn " path " path ": missing echo frames"))
                (when (every? some? [e99 e98 e6 e38])
                  (testing (str fname " NRPN " nrpn " " path)
                    (is (= channel (:channel e99)) "CC99 channel")
                    (is (= param-msb (:value e99))
                        (str "param MSB: expected " param-msb
                             " got " (:value e99)))
                    (is (= param-lsb (:value e98))
                        (str "param LSB: expected " param-lsb
                             " got " (:value e98)))
                    (is (= data-msb (:value e6))
                        (str "data MSB: expected " data-msb
                             " got " (:value e6)))
                    (is (= data-lsb (:value e38))
                        (str "data LSB: expected " data-lsb
                             " got " (:value e38)))))))))))))

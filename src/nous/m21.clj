; SPDX-License-Identifier: EPL-2.0
(ns nous.m21
  "nous Music21 integration — Phase 1 + Phase 2.

  Wraps the Music21 Python server subprocess (script/m21_server.py).
  The server starts lazily on first corpus request and stays alive for the
  JVM session, eliminating the 2-4 second music21 startup cost on every call.
  Calls are synchronous; use `eventually!` to defer from a live loop.

  ## Lifecycle
  The m21 server subprocess starts automatically on the first call to
  `load-chorale` or `list-chorales`. It exits when:
    - `stop-server!` is called (e.g. from core/stop!)
    - `core/stop!` is called (which calls stop-server! automatically)
    - The parent JVM process dies (stdin EOF, via OS pipe semantics)

  ## Server session cache
  Once a chorale is extracted, the Python server keeps the EDN string in its
  own in-memory session dict. The Clojure side layers a two-level cache on top:
    1. Python in-process dict  — instant (same JVM session, server already started)
    2. Clojure in-memory atom  — instant (survives namespace reload)
    3. On-disk EDN file        — fast file read; invalidated by m21_server.py mtime
    4. Python music21 extraction — ~100-500ms per chorale (populates all caches)

  ## Usage
    ;; 1. Start a sidecar with MIDI input monitoring enabled
    (start-sidecar! :midi-port 0 :midi-in-port 1)

    ;; 2. Play a Bach chorale (server starts automatically)
    (m21/play-chorale! 371)

    ;; 3. Inspect what's running
    (m21/server-running?)   ; => true
    (m21/server-info)       ; => {:pid N :script \"...m21_server.py\"}

    ;; 4. Manual lifecycle (usually not needed — core/stop! handles it)
    (m21/stop-server!)

  ## Chordified playback (Phase 1)
    (m21/load-chorale 371)       ; => vec of chord maps {:pitches [...] :dur/beats N}
    (m21/play-chorale! 371)      ; play BWV 371 — all voices on one channel
    (m21/play-chorale! 371 1/2)  ; dur multiplier (0.5 = double speed)

  ## Per-voice SATB on separate MIDI channels (Phase 2)
    (m21/load-chorale-parts 371)              ; => {:soprano [...] :alto [...] ...}
    (m21/play-chorale-parts! 371)             ; SATB on ch 1/2/3/4, blocks until done
    (m21/play-chorale-parts! 371 {:dur-mult 0.8
                                  :channels {:soprano 5 :alto 6 :tenor 7 :bass 8}})

  ## Caching
    (m21/clear-cache!)       ; evict everything (mem + disk)
    (m21/clear-cache! 371)   ; evict BWV 371 only (both modes)

  ## Phase 1 chord map format
    {:pitches [48 55 62 67] :dur/beats 1.0}   ; chord — MIDI bass→soprano
    {:rest true :dur/beats 1.0}               ; rest

  ## Phase 2 per-voice event format
    {:pitch 67 :dur/beats 1.0}   ; single note
    {:rest true :dur/beats 1.0}  ; rest

  Key design decisions: Q20 (m21/eventually!), Q22 (batch subprocess),
  Q23 (native hot-path / Music21 depth split), Q24 (no bundled corpus)."
  (:require [clojure.data.json  :as json]
            [clojure.edn        :as edn]
            [clojure.java.io    :as io]
            [clojure.string     :as str]
            [nous.dirs          :as dirs]
            [nous.live          :as live]
            [nous.loop          :as loop-ns])
  (:import  [java.io BufferedReader BufferedWriter InputStreamReader
                     OutputStreamWriter]
            [java.net UnixDomainSocketAddress StandardProtocolFamily]
            [java.nio.channels SocketChannel Channels]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *cache-dir*
  "Directory for on-disk EDN cache files.
  Default: (dirs/corpora-dir)/m21  (~/.local/share/nous/corpora/m21 on XDG)
  Override: (alter-var-root #'m21/*cache-dir* (constantly \"/my/path\"))
  or set NOUS_DATA to redirect the entire user data tree."
  (str (dirs/corpora-dir) "/m21"))

(def ^:private server-script "script/m21_server.py")
(def ^:private default-socket-path "/tmp/m21.sock")

;; ---------------------------------------------------------------------------
;; Socket connection state (M9: BEAM supervises the process)
;; ---------------------------------------------------------------------------

(defonce ^:private server-state
  (atom {:channel nil    ; SocketChannel
         :writer  nil    ; BufferedWriter (to server socket)
         :reader  nil})) ; BufferedReader (from server socket)

(defn connected?
  "True if nous.m21 has an open socket connection to m21_server.py."
  []
  (boolean (:channel @server-state)))

(defn server-running?
  "Alias for connected? — preserved for call-site compatibility."
  []
  (connected?))

(defn server-info
  "Return a map describing the active connection, or nil if not connected.
  Keys: :socket-path."
  []
  (when (connected?)
    {:socket-path (or (:socket-path @server-state) default-socket-path)}))

;; ---------------------------------------------------------------------------
;; Connection lifecycle
;; ---------------------------------------------------------------------------

(def ^:dynamic *open-channel*
  "Injected in tests: (fn [socket-path] {:channel ch :writer bw :reader br})
  Production uses the real Unix domain socket open."
  nil)

(defn- open-channel! [socket-path retry]
  (if *open-channel*
    (*open-channel* socket-path)
    (let [ch (loop [attempt 0 delay-ms 50]
                (when (> attempt retry)
                  (throw (ex-info "Could not connect to m21 server"
                                  {:socket-path socket-path :attempts attempt})))
                (or (try
                      (doto (SocketChannel/open StandardProtocolFamily/UNIX)
                        (.connect (UnixDomainSocketAddress/of socket-path)))
                      (catch java.net.ConnectException _ nil)
                      (catch java.nio.file.NoSuchFileException _ nil))
                    (do (Thread/sleep delay-ms)
                        (recur (inc attempt) (min 500 (* 2 delay-ms))))))]
      (let [out (Channels/newOutputStream ch)
            in  (Channels/newInputStream ch)]
        {:channel ch
         :writer  (BufferedWriter. (OutputStreamWriter. out "UTF-8"))
         :reader  (BufferedReader. (InputStreamReader. in "UTF-8"))}))))

(defn connect!
  "Connect to the m21 server socket started by NomosBeam.M21Supervisor.

  Options:
    :socket-path — Unix domain socket path (default \"/tmp/m21.sock\")
    :retry       — connection attempts before throwing (default 5)

  Returns true on success."
  [& {:keys [socket-path retry] :or {socket-path default-socket-path retry 5}}]
  (when (connected?) (throw (ex-info "m21 already connected — call disconnect! first" {})))
  (let [{:keys [channel writer reader]} (open-channel! socket-path retry)]
    (swap! server-state assoc
           :channel     channel
           :writer      writer
           :reader      reader
           :socket-path socket-path)
    true))

(defn disconnect!
  "Close the socket connection to m21_server.py. Does not kill the process."
  []
  (when-let [^SocketChannel ch (:channel @server-state)]
    (try (.close ch) (catch Exception _)))
  (swap! server-state assoc :channel nil :writer nil :reader nil :socket-path nil)
  nil)

(defn ensure-server!
  "Connect to the m21 server socket if not already connected.
  Retries up to 5 times; throws if the socket is not available."
  []
  (when-not (connected?)
    (connect!)))

(defn- m21-request!
  "Send a request map and return the parsed JSON response. Serialised on server-state."
  [req]
  (locking server-state
    (let [{:keys [^BufferedWriter writer ^BufferedReader reader]} @server-state]
      (.write writer ^String (json/write-str req))
      (.newLine writer)
      (.flush writer)
      (json/read-str (.readLine reader)))))

(defn server-call!
  "Send an arbitrary op request and return the response with keywordized keys.
  Ensures a connection is open first.

  Used by nous.composition and other namespaces that extend the m21 protocol.

  Example:
    (server-call! {:op \"parse-midi\" :path \"/abs/path/file.mid\"})"
  [req]
  (ensure-server!)
  (let [resp (m21-request! req)]
    (into {} (map (fn [[k v]] [(keyword k) v]) resp))))

(defn stop-server!
  "Disconnect from m21_server.py. Does not stop the OS process (BEAM owns it).
  Called automatically by core/stop!. Safe to call if not connected."
  []
  (disconnect!))

;; ---------------------------------------------------------------------------
;; Cache — in-memory + on-disk EDN cache for extracted corpora
;; ---------------------------------------------------------------------------

;; In-memory cache: {[bwv-id mode] parsed-data}.
;; defonce survives namespace reload so extracted data persists across eval cycles.
(defonce ^:private mem-cache (atom {}))

(defn- script-mtime
  "Return last-modified timestamp (ms) of m21_server.py, or 0 if not found."
  ^long []
  (.lastModified (io/file server-script)))

(defn- cache-file
  "Return the on-disk cache java.io.File for bwv-id and mode (:chords or :parts)."
  [bwv-id mode]
  (io/file *cache-dir* (str "bwv" bwv-id "-" (name mode) ".edn")))

(defn- path-hash
  "Return a 16-hex-char digest of corpus-path for use in cache filenames."
  [path]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        raw (.digest md (.getBytes ^String path "UTF-8"))]
    (apply str (take 16 (mapcat #(format "%02x" (bit-and % 0xff)) raw)))))

(defn- work-cache-file
  "Return the on-disk cache java.io.File for a general corpus path and mode."
  [path mode]
  (io/file *cache-dir* (str "work-" (path-hash path) "-" (name mode) ".edn")))

(defn- parse-edn
  "Strip comment lines (starting with ;) from s and parse as EDN."
  [s]
  (->> (str/split-lines s)
       (remove #(str/starts-with? (str/trim %) ";"))
       (str/join "\n")
       edn/read-string))

(defn- write-disk-cache*!
  "Write raw EDN string to the given cache File, prefixed with a script-mtime header."
  [^java.io.File f raw-edn]
  (try
    (io/make-parents f)
    (spit f (str "; nous-m21-cache script-mtime=" (script-mtime) "\n" raw-edn))
    (catch Exception e
      (binding [*out* *err*]
        (println "[nous.m21] disk cache write failed:" (.getMessage e))))))

(defn- write-disk-cache!
  "Write raw EDN string to the on-disk cache, prefixed with a script-mtime header."
  [bwv-id mode raw-edn]
  (write-disk-cache*! (cache-file bwv-id mode) raw-edn))

(defn- write-work-disk-cache!
  "Write raw EDN string to the on-disk work cache for a corpus path."
  [path mode raw-edn]
  (write-disk-cache*! (work-cache-file path mode) raw-edn))

(defn- read-disk-cache*
  "Return cache file content if it exists and script-mtime still matches; nil otherwise."
  [^java.io.File f]
  (try
    (when (.exists f)
      (let [content    (slurp f)
            first-line (first (str/split-lines content))
            cached-mtime (when (str/starts-with? first-line "; nous-m21-cache")
                           (some-> (re-find #"script-mtime=(\d+)" first-line)
                                   second
                                   Long/parseLong))]
        (when (= cached-mtime (script-mtime))
          content)))
    (catch Exception _ nil)))

(defn- read-disk-cache
  "Return on-disk cache content for bwv-id/mode if it exists and script mtime
  still matches; return nil if missing or stale."
  [bwv-id mode]
  (read-disk-cache* (cache-file bwv-id mode)))

(defn- read-work-disk-cache
  "Return on-disk cache content for a corpus path/mode if valid; nil otherwise."
  [path mode]
  (read-disk-cache* (work-cache-file path mode)))

(defn- cached-load
  "Load bwv-id in mode (:chords or :parts) through the three-level cache.

  Lookup order:
    1. Clojure in-memory atom  — instant (across namespace reloads)
    2. On-disk EDN file        — fast (file read); validates script mtime
    3. Python m21 server       — music21 extraction; ~100-500ms on first call;
                                 Python session cache makes repeat server calls instant"
  [bwv-id mode]
  (let [k [bwv-id mode]]
    (or (get @mem-cache k)
        (when-let [raw (read-disk-cache bwv-id mode)]
          (let [data (parse-edn raw)]
            (swap! mem-cache assoc k data)
            data))
        (do
          (ensure-server!)
          (let [resp (m21-request! {"op" "load" "bwv" (str bwv-id) "mode" (name mode)})]
            (when (= "error" (get resp "status"))
              (throw (ex-info "m21 server error"
                              {:bwv bwv-id :mode mode :message (get resp "message")})))
            (let [raw  (get resp "edn")
                  data (parse-edn raw)]
              (write-disk-cache! bwv-id mode raw)
              (swap! mem-cache assoc k data)
              data))))))

(defn- cached-load-work
  "Load a corpus work by path and mode through the three-level cache.

  Lookup order:
    1. Clojure in-memory atom  — instant
    2. On-disk EDN file        — fast file read; validates script mtime
    3. Python m21 server       — music21 extraction; ~100ms–2s on first call"
  [path mode]
  (let [k [path mode]]
    (or (get @mem-cache k)
        (when-let [raw (read-work-disk-cache path mode)]
          (let [data (parse-edn raw)]
            (swap! mem-cache assoc k data)
            data))
        (do
          (ensure-server!)
          (let [resp (m21-request! {"op" "load-work" "path" path "mode" (name mode)})]
            (when (= "error" (get resp "status"))
              (throw (ex-info "m21 server error"
                              {:path path :mode mode :message (get resp "message")})))
            (let [raw  (get resp "edn")
                  data (parse-edn raw)]
              (write-work-disk-cache! path mode raw)
              (swap! mem-cache assoc k data)
              data))))))

(defn clear-cache!
  "Evict cached extractions from both in-memory and on-disk stores.

  With no args: clears all cached chorales.
  With bwv-id:  clears that chorale only (both :chords and :parts modes).

  Examples:
    (m21/clear-cache!)       ; evict everything
    (m21/clear-cache! 371)   ; evict BWV 371 only"
  ([]
   (reset! mem-cache {})
   (let [dir (io/file *cache-dir*)]
     (when (.exists dir)
       (doseq [^java.io.File f (.listFiles dir)]
         (.delete f))))
   (println "[nous.m21] cache cleared")
   nil)
  ([bwv-id]
   (swap! mem-cache
          #(dissoc % [bwv-id :chords] [bwv-id :parts]))
   (doseq [mode [:chords :parts]]
     (let [f (cache-file bwv-id mode)]
       (when (.exists f) (.delete f))))
   nil))

;; ---------------------------------------------------------------------------
;; Public corpus API
;; ---------------------------------------------------------------------------

(defn list-chorales
  "Return a sorted vector of BWV numbers available in the music21 corpus."
  []
  (ensure-server!)
  (let [resp (m21-request! {"op" "list"})]
    (when (= "error" (get resp "status"))
      (throw (ex-info "m21 server error" {:message (get resp "message")})))
    (vec (get resp "data"))))

(defn chorale-metadata
  "Return theory metadata for a Bach chorale by BWV number.

  Keys: :bwv, :title, :composer, :key, :mode, :time-sig, :measures

  Example:
    (m21/chorale-metadata 371)
    ;;=> {:bwv 371 :title \"\" :composer \"J.S. Bach\"
    ;;    :key \"G\" :mode \"major\" :time-sig \"4/4\" :measures 42}"
  [bwv]
  (ensure-server!)
  (let [resp (m21-request! {"op" "metadata" "bwv" bwv})]
    (when (= "error" (get resp "status"))
      (throw (ex-info "m21 server error" {:bwv bwv :message (get resp "message")})))
    (into {} (map (fn [[k v]] [(keyword k) v]) resp))))

(defn search-corpus
  "Search the music21 corpus.

  Options map (all optional):
    :composer        — composer name string (e.g. \"palestrina\", \"josquin\")
    :file-extension  — file extension string (e.g. \"abc\", \"mxl\", \"xml\")
    :title           — title substring
    :limit           — max results (default 100)

  Returns a vector of maps:
    {:path \"palestrina/kyrie.mxl\" :title \"Kyrie\" :composer \"Palestrina\"}

  Examples:
    (m21/search-corpus {:composer \"palestrina\"})
    (m21/search-corpus {:file-extension \"abc\" :limit 20})"
  [{:keys [composer file-extension title limit]
    :or   {limit 100}}]
  (ensure-server!)
  (let [req  (cond-> {"op" "search" "limit" limit}
               composer        (assoc "composer" composer)
               file-extension  (assoc "fileExtension" file-extension)
               title           (assoc "title" title))
        resp (m21-request! req)]
    (when (= "error" (get resp "status"))
      (throw (ex-info "m21 server error" {:message (get resp "message")})))
    (mapv #(into {} (map (fn [[k v]] [(keyword k) v]) %))
          (get resp "data"))))

(defn load-work
  "Load any music21 corpus work by path.

  `path` — corpus path, e.g. \"palestrina/kyrie.mxl\"
  `mode` — :parts (default), :chords, or :intervals

  Returns:
    :parts     — {:soprano [...] :alto [...] ...}   per-voice event maps
    :chords    — vector of chord maps (same format as load-chorale)
    :intervals — {:soprano [{:from 60 :to 62 :semitones 2 :dur/beats 1.0} ...] ...}

  Use search-corpus to discover available paths.

  Examples:
    (m21/load-work \"palestrina/kyrie.mxl\" :parts)
    (m21/load-work \"josquin/mass...\" :intervals)"
  ([path]       (load-work path :parts))
  ([path mode]  (cached-load-work path mode)))

(defn load-chorale
  "Load a Bach chorale from the music21 corpus by BWV number.

  Returns a vector of chord maps:
    {:pitches [48 55 62 67] :dur/beats 1.0}  ; chord
    {:rest true :dur/beats 1.0}              ; rest

  :pitches are MIDI note numbers sorted ascending (bass to soprano).
  :dur/beats is duration in quarter-note beats.

  Example:
    (m21/load-chorale 371)   ; BWV 371 \"Ich dank' dir, lieber Herre\""
  [bwv-id]
  (cached-load bwv-id :chords))

;; ---------------------------------------------------------------------------
;; Playback
;; ---------------------------------------------------------------------------

(defn play-chorale!
  "Play a Bach chorale through the active sidecar.

  `bwv-id`      — BWV number (integer), e.g. 371
  `dur-mult`    — duration multiplier (default 1.0); 0.5 = double speed

  Must be called from inside a live loop (or with *virtual-time* bound),
  since it calls sleep! to advance beat time between chords.

  Example (from REPL):
    (core/deflive-loop :chorale {}
      (m21/play-chorale! 371)
      (core/stop-loop! :chorale))"
  ([bwv-id]
   (play-chorale! bwv-id 1.0))
  ([bwv-id dur-mult]
   (let [chords (load-chorale bwv-id)]
     (doseq [{:keys [pitches rest dur/beats]} chords]
       (let [dur (* (double (or beats 1)) (double dur-mult))]
         (when-not rest
           (live/play-voicing! pitches))
         (loop-ns/sleep! dur))))))

;; ---------------------------------------------------------------------------
;; Phase 2 — per-voice SATB extraction and playback
;; ---------------------------------------------------------------------------

(def ^:private satb-channels
  "Default MIDI channel mapping for SATB voices."
  {:soprano 1 :alto 2 :tenor 3 :bass 4})

(defn load-chorale-parts
  "Load a Bach chorale from the music21 corpus by BWV number, extracting
  each voice part independently.

  Returns a map of voice keyword → event vector:
    {:soprano [{:pitch 67 :dur/beats 1.0} {:rest true :dur/beats 0.5} ...]
     :alto    [{:pitch 62 :dur/beats 1.0} ...]
     :tenor   [{:pitch 55 :dur/beats 1.0} ...]
     :bass    [{:pitch 48 :dur/beats 1.0} ...]}

  :pitch is a single MIDI note number.
  :dur/beats is duration in quarter-note beats.

  Example:
    (m21/load-chorale-parts 371)   ; BWV 371 \"Ich dank' dir, lieber Herre\""
  [bwv-id]
  (cached-load bwv-id :parts))

(defn- play-voice-events!
  "Play a voice event sequence on `channel`, advancing *virtual-time* via sleep!.
  Must be called from a thread that has *virtual-time* bound."
  [events channel dur-mult]
  (binding [loop-ns/*synth-ctx* {:midi/channel channel}]
    (doseq [{:keys [pitch rest dur/beats]} events]
      (let [dur (* (double (or beats 1)) (double dur-mult))]
        (when-not rest
          (live/play! {:pitch/midi pitch :dur/beats dur}))
        (loop-ns/sleep! dur)))))

(defn play-chorale-parts!
  "Play a Bach chorale with SATB voices on separate MIDI channels.

  Each voice is played in a background thread sharing the same start beat,
  so all four voices stay phase-locked. Blocks until all voices complete.

  `bwv-id`  — BWV number (integer), e.g. 371
  `opts`    — optional map:
    :dur-mult — duration multiplier (default 1.0); 0.5 = double speed
    :channels — {voice-kw channel} override; default {:soprano 1 :alto 2 :tenor 3 :bass 4}

  Example:
    (m21/play-chorale-parts! 371)
    (m21/play-chorale-parts! 371 {:dur-mult 0.5
                                  :channels {:soprano 5 :alto 6 :tenor 7 :bass 8}})"
  ([bwv-id] (play-chorale-parts! bwv-id {}))
  ([bwv-id {:keys [dur-mult channels] :or {dur-mult 1.0}}]
   (let [parts      (load-chorale-parts bwv-id)
         ch-map     (merge satb-channels channels)
         start-beat (loop-ns/-current-beat)
         futures    (mapv (fn [[voice events]]
                            (let [ch (get ch-map voice 1)]
                              (future
                                (binding [loop-ns/*virtual-time* start-beat]
                                  (play-voice-events! events ch dur-mult)))))
                          parts)]
     (doseq [f futures] @f)
     nil)))

;; ---------------------------------------------------------------------------
;; eventually! — defer m21 calls from a live loop
;; ---------------------------------------------------------------------------

(defn eventually!
  "Run `f` in a background thread, returning a promise.
  Safe to call from a live loop — does not block the loop thread.

  Example:
    (def result (m21/eventually! #(m21/load-chorale 371)))
    ;; ... later ...
    @result   ; => chord seq"
  [f]
  (let [p (promise)]
    (future (deliver p (f)))
    p))

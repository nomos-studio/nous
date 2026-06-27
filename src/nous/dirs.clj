; SPDX-License-Identifier: EPL-2.0
(ns nous.dirs
  "Platform-appropriate directory resolution for nous.

  ## Two tiers

  ### User tier  — per-user, writable, survives product updates

    macOS   ~/Library/Application Support/nous/
    Linux   ~/.local/share/nous/   (XDG_DATA_HOME respected)

  User subdirectories (created on first access):
    (user-devices-dir)  — user-created / learned device maps
    (corpora-dir)       — m21 corpus, imported score data
    (sessions-dir)      — saved session state
    (scales-dir)        — Scala (.scl) and keyboard mapping (.kbm) files

  User config:
    macOS   ~/Library/Application Support/nous/
    Linux   ~/.config/nous/   (XDG_CONFIG_HOME respected)

  User cache:
    macOS   ~/Library/Caches/nous/
    Linux   ~/.cache/nous/   (XDG_CACHE_HOME respected)

  ### System tier  — shipped with the product, read-only to the user

  nomos-studio/maps is a deployment artifact containing device maps and
  parameter schema.  Peers resolve it via NOUS_MAPS or the
  platform-conventional system path:

    macOS   /Library/Application Support/nomos-studio/maps
    Linux   /usr/local/share/nomos-studio/maps

  System subdirectories:
    (system-devices-dir) — (system-maps-dir)/devices
    (schema-dir)         — (system-maps-dir)/schema

  ## Environment variable overrides

  All take priority over the active layout and platform defaults:

    NOUS_DATA    — overrides (user-data-dir)
    NOUS_CONFIG  — overrides (user-config-dir)
    NOUS_CACHE   — overrides (user-cache-dir)
    NOUS_MAPS    — overrides (system-maps-dir)
    NOUS_LAYOUT  — selects layout: 'xdg' or 'macos-native'
                   (auto-detected from platform when absent)
    NOUS_TOPOLOGY — path to studio topology EDN file

  ## Resource search order for device maps

  `(resolve-device-resource name)` searches three tiers in order:

    1. (user-devices-dir)/<name>         — user-created / learned (writable)
    2. (system-devices-dir)/<name>       — nomos-studio/maps deployment
    3. classpath resources/devices/<name> — temporary fallback

  User maps shadow system maps of the same filename.

  ## Layout abstraction

  A layout is a plain map:
    {:id     keyword
     :data   (fn [] path-string)
     :config (fn [] path-string)
     :cache  (fn [] path-string)}

  Tests inject a custom layout via set-layout! without protocol machinery."
  (:require [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- home [] (System/getProperty "user.home"))

(defn- read-env [k] (System/getenv k))

(defn- macos?
  "True when running on macOS."
  []
  (.startsWith (System/getProperty "os.name" "") "Mac"))

(defn- ensure-dir
  "Create path and all parents if absent. Returns path as a string."
  ^String [^String path]
  (.mkdirs (io/file path))
  path)

;; ---------------------------------------------------------------------------
;; Layout definitions
;; ---------------------------------------------------------------------------

(def ^:private user-app "nous")
(def ^:private system-vendor "nomos-studio")

(defn xdg-layout
  "XDG Base Directory Specification layout.
  Respects XDG_DATA_HOME, XDG_CONFIG_HOME, XDG_CACHE_HOME."
  []
  {:id     :xdg
   :data   (fn [] (str (or (read-env "XDG_DATA_HOME")
                           (str (home) "/.local/share"))
                       "/" user-app))
   :config (fn [] (str (or (read-env "XDG_CONFIG_HOME")
                           (str (home) "/.config"))
                       "/" user-app))
   :cache  (fn [] (str (or (read-env "XDG_CACHE_HOME")
                           (str (home) "/.cache"))
                       "/" user-app))})

(defn macos-native-layout
  "macOS native layout following Apple HIG conventions:
    ~/Library/Application Support/nous/   — data and config
    ~/Library/Caches/nous/                — cache"
  []
  {:id     :macos-native
   :data   (fn [] (str (home) "/Library/Application Support/" user-app))
   :config (fn [] (str (home) "/Library/Application Support/" user-app))
   :cache  (fn [] (str (home) "/Library/Caches/" user-app))})

(defn- default-layout
  "Select layout from NOUS_LAYOUT env var, falling back to platform detection."
  []
  (case (read-env "NOUS_LAYOUT")
    "macos-native" (macos-native-layout)
    "xdg"          (xdg-layout)
    (if (macos?) (macos-native-layout) (xdg-layout))))

(defonce ^:private active-layout (atom (default-layout)))

;; ---------------------------------------------------------------------------
;; Layout management
;; ---------------------------------------------------------------------------

(defn set-layout!
  "Change the active directory layout.

  layout — :xdg, :macos-native, or a layout map with :id/:data/:config/:cache.
  Returns the layout :id keyword."
  [layout]
  (let [l (case layout
            :xdg          (xdg-layout)
            :macos-native (macos-native-layout)
            layout)]
    (reset! active-layout l)
    (:id l)))

(defn active-layout-id [] (:id @active-layout))

;; ---------------------------------------------------------------------------
;; User tier — per-user directories
;; ---------------------------------------------------------------------------

(defn user-data-dir
  "Root user data directory.
  Override with NOUS_DATA.
  Default: ~/Library/Application Support/nous (macOS) or ~/.local/share/nous (Linux)"
  ^String []
  (or (read-env "NOUS_DATA") ((:data @active-layout))))

(defn user-config-dir
  "Root user config directory.
  Override with NOUS_CONFIG.
  Default: ~/Library/Application Support/nous (macOS) or ~/.config/nous (Linux)"
  ^String []
  (or (read-env "NOUS_CONFIG") ((:config @active-layout))))

(defn user-cache-dir
  "Root user cache directory.
  Override with NOUS_CACHE.
  Default: ~/Library/Caches/nous (macOS) or ~/.cache/nous (Linux)"
  ^String []
  (or (read-env "NOUS_CACHE") ((:cache @active-layout))))

(defn user-devices-dir
  "User-created and learned device maps. Shadows system maps of the same name.
  Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/devices")))

(defn corpora-dir
  "User-imported corpora (m21 cache, score data). Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/corpora")))

(defn sessions-dir
  "Saved session state and conductor scripts. Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/sessions")))

(defn scales-dir
  "Scala (.scl) and keyboard mapping (.kbm) files. Created on first access."
  ^String []
  (ensure-dir (str (user-data-dir) "/scales")))

;; ---------------------------------------------------------------------------
;; System tier — nomos-studio/maps deployment artifact
;; ---------------------------------------------------------------------------

(defn- system-maps-default
  "Platform-conventional path for the deployed nomos-studio/maps artifact."
  []
  (if (macos?)
    (str "/Library/Application Support/" system-vendor "/maps")
    (str "/usr/local/share/" system-vendor "/maps")))

(defn system-maps-dir
  "Root of the deployed nomos-studio/maps artifact (read-only).
  Override with NOUS_MAPS for development or non-standard installations.
  Default: /Library/Application Support/nomos-studio/maps (macOS)
           /usr/local/share/nomos-studio/maps (Linux)"
  ^String []
  (or (read-env "NOUS_MAPS") (system-maps-default)))

(defn system-devices-dir
  "Device maps shipped with nomos-studio/maps. Read-only."
  ^String []
  (str (system-maps-dir) "/devices"))

(defn schema-dir
  "Parameter schema files shipped with nomos-studio/maps (curves.edn, etc.). Read-only."
  ^String []
  (str (system-maps-dir) "/schema"))

;; ---------------------------------------------------------------------------
;; Resource resolution
;; ---------------------------------------------------------------------------

(defn resolve-device-resource
  "Resolve a device map filename to a URL.

  Search order:
    1. (user-devices-dir)/<name>          — user-created / learned (writable)
    2. (system-devices-dir)/<name>        — nomos-studio/maps deployment
    3. classpath resources/devices/<name> — fallback

  name — filename only, e.g. \"hydrasynth-explorer.edn\"

  Returns a java.net.URL or nil."
  [name]
  (let [fname (if (.contains ^String name "/")
                (subs name (inc (.lastIndexOf ^String name "/")))
                name)]
    (or (let [f (io/file (user-devices-dir) fname)]
          (when (.exists f) (.toURL f)))
        (let [f (io/file (system-devices-dir) fname)]
          (when (.exists f) (.toURL f)))
        (io/resource (str "devices/" fname)))))

(defn resolve-scala-resource
  "Resolve a Scala scale filename to a URL.

  Search order:
    1. (scales-dir)/<name>               — user scales (writable)
    2. classpath resources/scales/<name> — built-in bundled scales

  Returns a java.net.URL or nil."
  [name]
  (or (let [f (io/file (scales-dir) name)]
        (when (.exists f) (.toURL f)))
      (io/resource (str "scales/" name))))

(defn topology-path
  "Canonical path for the user's studio topology file.
  Override with NOUS_TOPOLOGY.
  Default: (user-config-dir)/topology.edn"
  ^String []
  (or (read-env "NOUS_TOPOLOGY")
      (str (user-config-dir) "/topology.edn")))

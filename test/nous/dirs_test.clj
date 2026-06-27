; SPDX-License-Identifier: EPL-2.0
(ns nous.dirs-test
  "Unit tests for nous.dirs — layout selection, path resolution,
  environment variable overrides, and device resource search order."
  (:require [clojure.java.io  :as io]
            [clojure.string   :as str]
            [clojure.test     :refer [deftest is testing use-fixtures]]
            [nous.dirs      :as dirs]))

;; ---------------------------------------------------------------------------
;; Fixture — restore the active layout after each test
;; ---------------------------------------------------------------------------

(defn restore-layout [f]
  (let [original-id (dirs/active-layout-id)
        original    (case original-id
                      :xdg         (dirs/xdg-layout)
                      :macos-native (dirs/macos-native-layout)
                      (dirs/xdg-layout))]
    (try (f)
         (finally (dirs/set-layout! original)))))

(use-fixtures :each restore-layout)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- home [] (System/getProperty "user.home"))

(defn- test-layout
  "Return a layout map that uses a fixed temp-style prefix for all dirs."
  [prefix]
  {:id     :test
   :data   (constantly (str prefix "/data"))
   :config (constantly (str prefix "/config"))
   :cache  (constantly (str prefix "/cache"))})

;; ---------------------------------------------------------------------------
;; Layout constructors
;; ---------------------------------------------------------------------------

(deftest xdg-layout-id-test
  (testing "xdg-layout has :id :xdg"
    (is (= :xdg (:id (dirs/xdg-layout))))))

(deftest macos-native-layout-id-test
  (testing "macos-native-layout has :id :macos-native"
    (is (= :macos-native (:id (dirs/macos-native-layout))))))

(deftest xdg-layout-data-dir-default-test
  (testing "xdg-layout data dir uses ~/.local/share when XDG_DATA_HOME unset"
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (let [layout (dirs/xdg-layout)
            result ((:data layout))]
        (is (str/starts-with? result (str (home) "/.local/share")))
        (is (str/ends-with? result "/nous"))))))

(deftest xdg-layout-respects-xdg-data-home-test
  (testing "xdg-layout uses XDG_DATA_HOME when set"
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (case k
                                           "XDG_DATA_HOME" "/custom/data"
                                           nil))]
      (let [layout (dirs/xdg-layout)]
        (is (= "/custom/data/nous" ((:data layout))))))))

(deftest xdg-layout-config-dir-test
  (testing "xdg-layout config dir uses ~/.config by default"
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (let [layout (dirs/xdg-layout)]
        (is (str/starts-with? ((:config layout)) (str (home) "/.config")))))))

(deftest xdg-layout-cache-dir-test
  (testing "xdg-layout cache dir uses ~/.cache by default"
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (let [layout (dirs/xdg-layout)]
        (is (str/starts-with? ((:cache layout)) (str (home) "/.cache")))))))

(deftest macos-native-layout-paths-test
  (testing "macos-native-layout data dir uses ~/Library/Application Support"
    (let [layout (dirs/macos-native-layout)]
      (is (str/includes? ((:data layout)) "Library/Application Support/nous"))))
  (testing "macos-native-layout cache dir uses ~/Library/Caches"
    (let [layout (dirs/macos-native-layout)]
      (is (str/includes? ((:cache layout)) "Library/Caches/nous")))))

;; ---------------------------------------------------------------------------
;; set-layout! / active-layout-id
;; ---------------------------------------------------------------------------

(deftest set-layout-keyword-xdg-test
  (testing "set-layout! :xdg switches to XDG layout"
    (dirs/set-layout! :xdg)
    (is (= :xdg (dirs/active-layout-id)))))

(deftest set-layout-keyword-macos-native-test
  (testing "set-layout! :macos-native switches to macOS native layout"
    (dirs/set-layout! :macos-native)
    (is (= :macos-native (dirs/active-layout-id)))))

(deftest set-layout-returns-id-test
  (testing "set-layout! returns the layout :id"
    (is (= :xdg (dirs/set-layout! :xdg)))
    (is (= :macos-native (dirs/set-layout! :macos-native)))))

(deftest set-layout-custom-map-test
  (testing "set-layout! accepts a custom layout map"
    (dirs/set-layout! (test-layout "/tmp/nous-test"))
    (is (= :test (dirs/active-layout-id)))))

;; ---------------------------------------------------------------------------
;; user-data-dir / user-config-dir / user-cache-dir
;; ---------------------------------------------------------------------------

(deftest user-data-dir-uses-layout-test
  (testing "user-data-dir returns layout data path"
    (dirs/set-layout! (test-layout "/tmp/test-data"))
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (is (= "/tmp/test-data/data" (dirs/user-data-dir))))))

(deftest user-data-dir-env-override-test
  (testing "NOUS_DATA overrides the active layout"
    (dirs/set-layout! (test-layout "/tmp/test-data"))
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (when (= k "NOUS_DATA")
                                           "/override/data"))]
      (is (= "/override/data" (dirs/user-data-dir))))))

(deftest user-config-dir-env-override-test
  (testing "NOUS_CONFIG overrides the active layout"
    (dirs/set-layout! (test-layout "/tmp/test-cfg"))
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (when (= k "NOUS_CONFIG")
                                           "/override/config"))]
      (is (= "/override/config" (dirs/user-config-dir))))))

(deftest user-cache-dir-env-override-test
  (testing "NOUS_CACHE overrides the active layout"
    (dirs/set-layout! (test-layout "/tmp/test-cache"))
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (when (= k "NOUS_CACHE")
                                           "/override/cache"))]
      (is (= "/override/cache" (dirs/user-cache-dir))))))

(deftest nous-data-takes-priority-over-xdg-vars-test
  (testing "NOUS_DATA takes priority over XDG_DATA_HOME"
    (dirs/set-layout! :xdg)
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (case k
                                           "NOUS_DATA"     "/nous-override"
                                           "XDG_DATA_HOME" "/xdg-home"
                                           nil))]
      (is (= "/nous-override" (dirs/user-data-dir))))))

;; ---------------------------------------------------------------------------
;; System tier
;; ---------------------------------------------------------------------------

(deftest system-maps-dir-nous-maps-override-test
  (testing "NOUS_MAPS overrides the system maps path"
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (when (= k "NOUS_MAPS")
                                           "/custom/maps"))]
      (is (= "/custom/maps" (dirs/system-maps-dir))))))

(deftest system-devices-dir-under-maps-test
  (testing "system-devices-dir is (system-maps-dir)/devices"
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (when (= k "NOUS_MAPS")
                                           "/custom/maps"))]
      (is (= "/custom/maps/devices" (dirs/system-devices-dir))))))

(deftest schema-dir-under-maps-test
  (testing "schema-dir is (system-maps-dir)/schema"
    (with-redefs [nous.dirs/read-env (fn [k]
                                         (when (= k "NOUS_MAPS")
                                           "/custom/maps"))]
      (is (= "/custom/maps/schema" (dirs/schema-dir))))))

;; ---------------------------------------------------------------------------
;; Subdirectory helpers — structure checks
;; ---------------------------------------------------------------------------

(deftest user-devices-dir-under-data-dir-test
  (testing "user-devices-dir is a subdirectory of user-data-dir"
    (dirs/set-layout! (test-layout (System/getProperty "java.io.tmpdir")))
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (let [data    (dirs/user-data-dir)
            devices (dirs/user-devices-dir)]
        (is (str/starts-with? devices data))
        (is (str/ends-with? devices "/devices"))))))

(deftest corpora-dir-under-data-dir-test
  (testing "corpora-dir is a subdirectory of user-data-dir"
    (dirs/set-layout! (test-layout (System/getProperty "java.io.tmpdir")))
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (let [data    (dirs/user-data-dir)
            corpora (dirs/corpora-dir)]
        (is (str/starts-with? corpora data))
        (is (str/ends-with? corpora "/corpora"))))))

(deftest sessions-dir-under-data-dir-test
  (testing "sessions-dir is a subdirectory of user-data-dir"
    (dirs/set-layout! (test-layout (System/getProperty "java.io.tmpdir")))
    (with-redefs [nous.dirs/read-env (constantly nil)]
      (let [data     (dirs/user-data-dir)
            sessions (dirs/sessions-dir)]
        (is (str/starts-with? sessions data))
        (is (str/ends-with? sessions "/sessions"))))))

;; ---------------------------------------------------------------------------
;; resolve-device-resource — search order
;; ---------------------------------------------------------------------------

(deftest resolve-device-classpath-test
  (testing "resolve-device-resource finds built-in device maps on classpath"
    (let [url (dirs/resolve-device-resource "hydrasynth-explorer.edn")]
      (is (some? url) "hydrasynth-explorer.edn found on classpath")
      (is (instance? java.net.URL url)))))

(deftest resolve-device-unknown-returns-nil-test
  (testing "resolve-device-resource returns nil for unknown device"
    (let [url (dirs/resolve-device-resource "no-such-device-xyz.edn")]
      (is (nil? url)))))

(deftest resolve-device-user-shadows-classpath-test
  (testing "user-devices-dir shadows classpath for same filename"
    (let [tmp-dir  (str (System/getProperty "java.io.tmpdir")
                        "/nous-dirs-test-" (System/currentTimeMillis))
          tmp-file (io/file tmp-dir "hydrasynth-explorer.edn")]
      (try
        (.mkdirs (io/file tmp-dir))
        (spit tmp-file ";; user override\n{:device/id :test}")
        (with-redefs [nous.dirs/user-devices-dir (constantly tmp-dir)]
          (let [url (dirs/resolve-device-resource "hydrasynth-explorer.edn")]
            (is (some? url))
            (is (str/includes? (slurp url) "user override")
                "user file content returned, not classpath bundled map")))
        (finally
          (.delete tmp-file)
          (.delete (io/file tmp-dir)))))))

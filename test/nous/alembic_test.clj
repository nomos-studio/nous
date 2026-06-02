; SPDX-License-Identifier: EPL-2.0
(ns nous.alembic-test
  "Tests for nous.alembic — Faust→WASM pipeline integration."
  (:require [clojure.test    :refer [deftest is testing]]
            [nous.alembic    :as na]))

;; ---------------------------------------------------------------------------
;; patch-descriptor — pure data construction, no faust required
;; ---------------------------------------------------------------------------

(deftest patch-descriptor-defaults
  (testing "defaults: :voice node, stereo"
    (let [desc (na/patch-descriptor "/tmp/test.wasm")]
      (is (= 1 (count (get-in desc [:topology :nodes]))))
      (let [node (first (get-in desc [:topology :nodes]))]
        (is (= :voice (:id node)))
        (is (= :kairos-grid (:type node)))
        (is (= "/tmp/test.wasm" (get-in node [:patch :modules 0 :wasm-path])))
        (is (= "wasm" (get-in node [:patch :modules 0 :type])))
        (is (= "audio-out" (get-in node [:patch :modules 1 :type]))))
      (let [routes (get-in desc [:topology :routes])]
        (is (= 2 (count routes)))
        (is (= {:from [:voice 0] :to :master/left}  (first routes)))
        (is (= {:from [:voice 1] :to :master/right} (second routes)))))))

(deftest patch-descriptor-mono
  (testing "channels=1 fans output to both sides"
    (let [desc   (na/patch-descriptor "/tmp/mono.wasm" :channels 1)
          routes (get-in desc [:topology :routes])]
      (is (= 2 (count routes)))
      (is (= {:from [:voice 0] :to :master/left}  (first routes)))
      (is (= {:from [:voice 0] :to :master/right} (second routes))))))

(deftest patch-descriptor-custom-node
  (testing "custom :node-id appears in nodes and routes"
    (let [desc (na/patch-descriptor "/tmp/filter.wasm" :node-id :filt :channels 2)]
      (is (= :filt (get-in desc [:topology :nodes 0 :id])))
      (is (= [:filt 0] (get-in desc [:topology :routes 0 :from])))
      (is (= [:filt 1] (get-in desc [:topology :routes 1 :from]))))))

(deftest patch-descriptor-cables
  (testing "stereo cables connect synth outputs to audio-out"
    (let [patch (get-in (na/patch-descriptor "/tmp/s.wasm" :channels 2)
                        [:topology :nodes 0 :patch])]
      (is (= [[:synth 0 :out 0] [:synth 1 :out 1]] (:cables patch)))))
  (testing "mono cables connect channel 0 only"
    (let [patch (get-in (na/patch-descriptor "/tmp/m.wasm" :channels 1)
                        [:topology :nodes 0 :patch])]
      (is (= [[:synth 0 :out 0]] (:cables patch))))))

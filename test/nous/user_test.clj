; SPDX-License-Identifier: EPL-2.0
(ns nous.user-test
  (:require [clojure.test :refer [deftest is testing]]
            [nous.user]))

;; Access private helpers via var interning.
(def ^:private sidecar-cli-args #'nous.user/sidecar-cli-args)
(def ^:private locate-sidecar   #'nous.user/locate-sidecar)

;; ---------------------------------------------------------------------------
;; sidecar-cli-args
;; ---------------------------------------------------------------------------

(deftest sidecar-cli-args-always-includes-socket
  (is (= ["--socket" "/tmp/kairos.sock"]
         (sidecar-cli-args "/tmp/kairos.sock" true {}))))

(deftest sidecar-cli-args-common-flags
  (testing "bpm and midi-port are passed through"
    (let [args (sidecar-cli-args "/tmp/kairos.sock" true
                                 {:bpm 140 :midi-port "IAC Bus 1"})]
      (is (some #{"--bpm"} args))
      (is (some #{"140"} args))
      (is (some #{"--midi-port"} args))
      (is (some #{"IAC Bus 1"} args))))
  (testing "no-audio boolean becomes bare flag"
    (let [args (sidecar-cli-args "/tmp/kairos.sock" true {:no-audio true})]
      (is (some #{"--no-audio"} args))))
  (testing "nil options are omitted"
    (let [args (sidecar-cli-args "/tmp/kairos.sock" true {:bpm nil :midi-port nil})]
      (is (= ["--socket" "/tmp/kairos.sock"] args)))))

(deftest sidecar-cli-args-kairos-only-flags
  (testing "plugin-path passed when kairos? is true"
    (let [args (sidecar-cli-args "/tmp/kairos.sock" true
                                 {:plugin-path "/opt/clap" :block-size 256})]
      (is (some #{"--plugin-path"} args))
      (is (some #{"/opt/clap"} args))
      (is (some #{"--block-size"} args))
      (is (some #{"256"} args))))
  (testing "plugin-path omitted when kairos? is false"
    (let [args (sidecar-cli-args "/tmp/aion.sock" false
                                 {:plugin-path "/opt/clap" :block-size 256
                                  :midi-port "IAC"})]
      (is (not (some #{"--plugin-path"} args)))
      (is (not (some #{"/opt/clap"} args)))
      (is (not (some #{"--block-size"} args)))
      (is (some #{"--midi-port"} args)))))

;; ---------------------------------------------------------------------------
;; locate-sidecar
;; ---------------------------------------------------------------------------

(deftest locate-sidecar-user-binary-kairos
  (let [[bin kairos?] (locate-sidecar "/usr/local/bin/kairos")]
    (is (= "/usr/local/bin/kairos" bin))
    (is (true? kairos?))))

(deftest locate-sidecar-user-binary-aion
  (let [[bin kairos?] (locate-sidecar "/usr/local/bin/aion")]
    (is (= "/usr/local/bin/aion" bin))
    (is (false? kairos?))))

(deftest locate-sidecar-custom-binary-treated-as-kairos
  (let [[bin kairos?] (locate-sidecar "/opt/custom/nomos-rt")]
    (is (= "/opt/custom/nomos-rt" bin))
    (is (true? kairos?) "non-aion binary is treated as kairos")))

(deftest locate-sidecar-nil-throws-when-nothing-installed
  ;; When no binary is given and neither kairos nor aion is in standard paths,
  ;; locate-sidecar should throw. We rely on the fact that a deliberately
  ;; invalid path is never found by find-bin.
  ;; This test is a no-op on machines that have kairos/aion installed;
  ;; it guards the throw path specifically when user-binary is nil.
  ;; We test the throw indirectly via start-sidecar! in environments without binaries.
  (when-not (#'nous.user/find-bin "kairos")
    (when-not (#'nous.user/find-bin "aion")
      (is (thrown? clojure.lang.ExceptionInfo
                   (locate-sidecar nil))))))

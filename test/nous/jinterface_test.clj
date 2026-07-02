; SPDX-License-Identifier: EPL-2.0
(ns nous.jinterface-test
  "Unit tests for the Erlang-term ↔ Clojure conversion in nous.jinterface.
  These tests exercise the private conversion functions via the ns-resolve trick
  without requiring a live OTP node."
  (:require [clojure.test :refer [deftest is are testing]]
            [nous.jinterface :as ji])
  (:import [com.ericsson.otp.erlang
            OtpErlangAtom OtpErlangBinary OtpErlangList
            OtpErlangLong OtpErlangMap OtpErlangObject]))

;; Access private otp->clj for white-box testing.
(def ^:private otp->clj @#'nous.jinterface/otp->clj)

(deftest otp->clj-atom
  (testing "atoms become keywords"
    (is (= :ctrl_write (otp->clj (OtpErlangAtom. "ctrl_write"))))
    (is (= :key_down   (otp->clj (OtpErlangAtom. "key_down"))))
    (is (= :input      (otp->clj (OtpErlangAtom. "input")))))
  (testing "reserved atoms become booleans"
    (is (= true  (otp->clj (OtpErlangAtom. "true"))))
    (is (= false (otp->clj (OtpErlangAtom. "false"))))))

(deftest otp->clj-binary
  (testing "binaries become UTF-8 strings"
    (is (= "a"   (otp->clj (OtpErlangBinary. (.getBytes "a" "UTF-8")))))
    (is (= "KeyA" (otp->clj (OtpErlangBinary. (.getBytes "KeyA" "UTF-8")))))))

(deftest otp->clj-long
  (is (= 42 (otp->clj (OtpErlangLong. 42)))))

(deftest otp->clj-list
  (testing "list of atoms → vector of keywords"
    (let [atoms (into-array OtpErlangObject
                            [(OtpErlangAtom. "input")
                             (OtpErlangAtom. "keyboard")
                             (OtpErlangAtom. "key_down")])]
      (is (= [:input :keyboard :key_down]
             (otp->clj (OtpErlangList. atoms)))))))

(deftest otp->clj-map
  (testing "map with atom keys and binary values"
    (let [ks (into-array OtpErlangObject
                         [(OtpErlangAtom. "op")
                          (OtpErlangAtom. "path")
                          (OtpErlangAtom. "value")])
          path-atoms (into-array OtpErlangObject
                                 [(OtpErlangAtom. "input")
                                  (OtpErlangAtom. "keyboard")
                                  (OtpErlangAtom. "key_down")])
          vs  (into-array OtpErlangObject
                          [(OtpErlangAtom. "ctrl_write")
                           (OtpErlangList. path-atoms)
                           (OtpErlangBinary. (.getBytes "a" "UTF-8"))])
          result (otp->clj (OtpErlangMap. ks vs))]
      (is (= :ctrl_write (:op result)))
      (is (= [:input :keyboard :key_down] (:path result)))
      (is (= "a" (:value result))))))

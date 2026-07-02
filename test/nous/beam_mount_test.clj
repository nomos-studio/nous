; SPDX-License-Identifier: EPL-2.0
(ns nous.beam-mount-test
  "Tests for BeamMount internals and ctrl-tree dispatch.

  OtpMbox has only package-private constructors, so direct BeamMount
  instantiation with a real mailbox requires a live OtpNode (integration test
  territory). We test two things independently here:

  1. clj->otp roundtrip: produce an Erlang term, decode it with otp->clj, and
     assert we recover the original Clojure value.

  2. ctrl-tree dispatch: register a SpyMount and verify ctrl-write! calls
     mount-write! with the correct path and value."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ctrl-tree.refs :as refs]
            [ctrl-tree.core :as ct]
            [nous.beam-mount :as bm]
            [nous.jinterface :as ji]
            [protomatter.protocols :as p]))

;; Access private conversion fns for roundtrip testing.
(def ^:private clj->otp @#'nous.beam-mount/clj->otp)
(def ^:private otp->clj @#'nous.jinterface/otp->clj)

;; ── clj→otp→clj roundtrips ───────────────────────────────────────────────────

(deftest clj->otp-keyword-roundtrip
  (testing "keyword survives clj→otp→clj"
    (is (= :ctrl_write_echo (otp->clj (clj->otp :ctrl_write_echo))))))

(deftest clj->otp-string-roundtrip
  (testing "string survives clj→otp→clj (via binary)"
    (is (= "a" (otp->clj (clj->otp "a"))))))

(deftest clj->otp-vector-roundtrip
  (testing "vector of keywords survives clj→otp→clj"
    (let [path [:input :keyboard :key_down]]
      (is (= path (otp->clj (clj->otp path)))))))

(deftest clj->otp-map-roundtrip
  (testing "map with keyword keys and mixed values survives clj→otp→clj"
    (let [m {:op :ctrl_write_echo :path [:input :keyboard :key_down] :value "a"}]
      (is (= m (otp->clj (clj->otp m)))))))

;; ── ctrl-tree dispatch ────────────────────────────────────────────────────────

(deftype SpyMount [calls-atom]
  p/IMount
  (mount-write! [_ path value]
    (swap! calls-atom conj {:path path :value value}))
  (mount-recable! [_ _changes] nil))

(defn- clean-mount-table [f]
  (dosync (ref-set refs/mount-table {}))
  (f)
  (dosync (ref-set refs/mount-table {})))

(use-fixtures :each clean-mount-table)

(deftest mount-is-dispatched-on-ctrl-write
  (testing "mount-write! fires when ctrl-write! matches the registered prefix"
    (let [calls (atom [])
          spy   (SpyMount. calls)]
      (dosync (alter refs/mount-table assoc [:input :keyboard] spy))
      (ct/ctrl-write! [:input :keyboard :key_down] "d")
      (is (= 1 (count @calls)))
      (is (= {:path [:input :keyboard :key_down] :value "d"}
             (first @calls))))))

(deftest mount-not-dispatched-for-unregistered-prefix
  (testing "no mount fires when no prefix matches"
    (let [calls (atom [])
          spy   (SpyMount. calls)]
      (dosync (alter refs/mount-table assoc [:other :subtree] spy))
      (ct/ctrl-write! [:input :keyboard :key_down] "d")
      (is (= 0 (count @calls))))))

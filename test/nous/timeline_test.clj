; SPDX-License-Identifier: EPL-2.0
(ns nous.timeline-test
  "Tests for nous.timeline — time-identity observable functions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nous.core     :as core]
            [nous.kairos   :as kairos]
            [nous.link     :as link]
            [nous.timeline :as timeline]))

;; ---------------------------------------------------------------------------
;; Helpers — private atom access
;; ---------------------------------------------------------------------------

(defn- system-ref [] @#'link/system-ref)

(defmacro with-bpm
  "Temporarily replace the private estimate-bpm fn to return a constant bpm.
  alter-var-root is used because with-redefs cannot accept #'private-var syntax."
  [bpm & body]
  `(let [orig# @#'link/estimate-bpm]
     (alter-var-root #'link/estimate-bpm (fn [_#] (constantly ~bpm)))
     (try ~@body
          (finally (alter-var-root #'link/estimate-bpm (fn [_#] orig#))))))


;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (core/start! :bpm 120)
    (when-let [s @(system-ref)]
      (swap! s dissoc :link-state :link-time-id))
    (t)
    (core/stop!)))

;; ---------------------------------------------------------------------------
;; time-identity
;; ---------------------------------------------------------------------------

(deftest time-identity-nil-before-ticks
  (testing "time-identity returns nil when no Link ticks have arrived"
    (is (nil? (timeline/time-identity)))))

(deftest time-identity-map-after-ticks
  (testing "time-identity returns {:current ... :pending nil} after ticks establish BPM"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (let [tid (timeline/time-identity)]
        (is (map? tid)                      "time-identity should return a map")
        (is (map? (:current tid))           ":current should be a timeline map")
        (is (nil? (:pending tid))           ":pending should be nil initially")
        (is (number? (:bpm (:current tid))) ":current :bpm should be a number")))))

;; ---------------------------------------------------------------------------
;; pending-timeline
;; ---------------------------------------------------------------------------

(deftest pending-timeline-nil-when-no-pending
  (testing "pending-timeline returns nil when no deferred transition is scheduled"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (is (nil? (timeline/pending-timeline))
          "pending-timeline should be nil when no BPM change is deferred"))))

(deftest pending-timeline-returns-map-when-pending
  (testing "pending-timeline returns the pending timeline map when a BPM change is deferred"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (link/enable! :policy :bar-quantize)
      (with-bpm 160.0 (#'link/on-tick {:beat 1.5 :tick-n 1}))
      (let [pt (timeline/pending-timeline)]
        (is (map? pt)           "pending-timeline should return a map when pending")
        (is (number? (:bpm pt)) ":bpm should be a number in the pending timeline")
        (is (> (:bpm pt) 140.0) "pending :bpm should reflect the new estimate")))))

;; ---------------------------------------------------------------------------
;; pending-apply-at
;; ---------------------------------------------------------------------------

(deftest pending-apply-at-nil-when-no-pending
  (testing "pending-apply-at returns nil when no deferred transition is scheduled"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (is (nil? (timeline/pending-apply-at))
          "pending-apply-at should be nil when no BPM change is deferred"))))

(deftest pending-apply-at-returns-beat-when-pending
  (testing "pending-apply-at returns a beat double when a BPM change is deferred"
    (with-redefs [kairos/connected? (constantly true)]
      (with-bpm 120.0 (#'link/on-tick {:beat 1.0 :tick-n 0}))
      (link/enable! :policy :bar-quantize)
      (with-bpm 160.0 (#'link/on-tick {:beat 1.5 :tick-n 1}))
      (let [at (timeline/pending-apply-at)]
        (is (number? at) "pending-apply-at should be a number when pending")
        (is (> at 1.5)   "apply-at should be after the beat that triggered the change")))))

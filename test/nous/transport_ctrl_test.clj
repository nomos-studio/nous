; SPDX-License-Identifier: EPL-2.0
(ns nous.transport-ctrl-test
  (:require [clojure.test          :refer [deftest is testing use-fixtures]]
            [nous.transport-ctrl   :as transport-ctrl]))

(use-fixtures :each
  (fn [f]
    (transport-ctrl/stop!)
    (f)
    (transport-ctrl/stop!)))

(deftest lifecycle
  (testing "not started initially"
    (is (false? (transport-ctrl/started?))))

  (testing "start! sets started? true"
    (transport-ctrl/start!)
    (is (true? (transport-ctrl/started?))))

  (testing "start! is idempotent"
    (transport-ctrl/start!)
    (transport-ctrl/start!)
    (is (true? (transport-ctrl/started?))))

  (testing "stop! clears started?"
    (transport-ctrl/start!)
    (transport-ctrl/stop!)
    (is (false? (transport-ctrl/started?))))

  (testing "stop! is idempotent"
    (transport-ctrl/stop!)
    (transport-ctrl/stop!)
    (is (false? (transport-ctrl/started?)))))

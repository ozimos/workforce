(ns com.ozimos.workforce.org.headcount-actors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.org.rbac :as rbac]))

(deftest reporting-manager-cardinality-rules-test
  (testing "valid combinations: 0, 1 employee, 1 headcount, or 1 employee + 1 headcount"
    (is (true? (rbac/valid-reporting-managers? nil)))
    (is (true? (rbac/valid-reporting-managers? [])))
    (is (true? (rbac/valid-reporting-managers? [{:type :employee :id "emp-1"}])))
    (is (true? (rbac/valid-reporting-managers? [{:type :headcount :id "req-1"}])))
    (is (true? (rbac/valid-reporting-managers? [{:type :employee :id "emp-1"}
                                                {:type :headcount :id "req-1"}])))
    (is (true? (rbac/valid-reporting-managers? {:employee-id "emp-1"})))
    (is (true? (rbac/valid-reporting-managers? {:headcount-id "req-1"})))
    (is (true? (rbac/valid-reporting-managers? {:employee-id "emp-1" :headcount-id "req-1"}))))

  (testing "invalid combinations: >1 of same type or >2 total"
    ;; Two employees (violates max 1 employee)
    (is (false? (rbac/valid-reporting-managers? [{:type :employee :id "emp-1"}
                                                 {:type :employee :id "emp-2"}])))
    ;; Two headcounts (violates max 1 headcount)
    (is (false? (rbac/valid-reporting-managers? [{:type :headcount :id "req-1"}
                                                 {:type :headcount :id "req-2"}])))
    ;; Three managers (violates max 2 total)
    (is (false? (rbac/valid-reporting-managers? [{:type :employee :id "emp-1"}
                                                 {:type :headcount :id "req-1"}
                                                 {:type :employee :id "emp-2"}])))
    (is (false? (rbac/valid-reporting-managers? [{:type :unknown :id "x-1"}])))))

(deftest resolve-effective-reporting-test
  (testing "dual reporting managers: employee becomes acting reporting manager in the tree"
    (let [res (rbac/resolve-effective-reporting [{:type :employee :id "emp-alice"}
                                                 {:type :headcount :id "req-vp-eng"}])]
      (is (= "emp-alice" (:tree-parent-id res))
          "Employee must be selected as the parent on the org tree")
      (is (true? (:acting-reporting-manager? res))
          "Employee must be marked as acting reporting manager")
      (is (= "emp-alice" (:employee-reporting-manager-id res)))
      (is (= "req-vp-eng" (:headcount-reporting-manager-id res)))))

  (testing "dual reporting managers via map format"
    (let [res (rbac/resolve-effective-reporting {:employee-id "emp-bob" :headcount-id "req-dir"})]
      (is (= "emp-bob" (:tree-parent-id res)))
      (is (true? (:acting-reporting-manager? res)))
      (is (= "emp-bob" (:employee-reporting-manager-id res)))
      (is (= "req-dir" (:headcount-reporting-manager-id res)))))

  (testing "employee only: regular reporting manager, not acting"
    (let [res (rbac/resolve-effective-reporting [{:type :employee :id "emp-carol"}])]
      (is (= "emp-carol" (:tree-parent-id res)))
      (is (false? (:acting-reporting-manager? res)))
      (is (= "emp-carol" (:employee-reporting-manager-id res)))
      (is (nil? (:headcount-reporting-manager-id res)))))

  (testing "headcount only: headcount is tree parent, not acting"
    (let [res (rbac/resolve-effective-reporting [{:type :headcount :id "req-team-lead"}])]
      (is (= "req-team-lead" (:tree-parent-id res)))
      (is (false? (:acting-reporting-manager? res)))
      (is (nil? (:employee-reporting-manager-id res)))
      (is (= "req-team-lead" (:headcount-reporting-manager-id res)))))

  (testing "no reporting manager"
    (let [res (rbac/resolve-effective-reporting nil)]
      (is (nil? (:tree-parent-id res)))
      (is (false? (:acting-reporting-manager? res))))))

(deftest headcount-actors-recognition-test
  (let [sample-hc {:request-id "req-201"
                   :title "Staff Distributed Systems Engineer"
                   :owner "u-owner-1"
                   :hiring-manager "u-hm-1"
                   :reporting-manager {:type :employee :id "emp-lead-1"}
                   :recruiters ["u-recruiter-1" "u-recruiter-2"]
                   :approvers ["u-vp-1" "u-cfo-1"]
                   :collaborators ["u-collab-1"]
                   :sourcers ["u-sourcer-1"]}]

    (testing "owner is recognized as an actor"
      (is (true? (rbac/is-actor-on-request? {:user-id "u-owner-1"} sample-hc))))

    (testing "hiring manager is recognized as an actor"
      (is (true? (rbac/is-actor-on-request? {:user-id "u-hm-1"} sample-hc))))

    (testing "reporting manager is recognized as an actor"
      (is (true? (rbac/is-actor-on-request? {:user-id "emp-lead-1"} sample-hc))))

    (testing "recruiters are recognized as actors"
      (is (true? (rbac/is-actor-on-request? {:user-id "u-recruiter-1"} sample-hc)))
      (is (true? (rbac/is-actor-on-request? {:user-id "u-recruiter-2"} sample-hc))))

    (testing "approvers are recognized as actors"
      (is (true? (rbac/is-actor-on-request? {:user-id "u-vp-1"} sample-hc)))
      (is (true? (rbac/is-actor-on-request? {:user-id "u-cfo-1"} sample-hc))))

    (testing "collaborators are recognized as actors"
      (is (true? (rbac/is-actor-on-request? {:user-id "u-collab-1"} sample-hc))))

    (testing "sourcers are recognized as actors"
      (is (true? (rbac/is-actor-on-request? {:user-id "u-sourcer-1"} sample-hc))))

    (testing "unrelated third party is NOT an actor"
      (is (false? (rbac/is-actor-on-request? {:user-id "u-stranger"} sample-hc))))))

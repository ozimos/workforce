(ns com.ozimos.workforce.frontend.ui.pages.headcount-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard :as dept-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.headcount :as headcount]
   [com.ozimos.workforce.frontend.ui.pages.org-chart :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings :as policy-settings]))

(deftest component-initial-states-test
  (testing "HeadcountPage initial state contains expected defaults"
    (let [init-state (comp/get-initial-state headcount/HeadcountPage {})]
      (is (true? (:loading init-state)))
      (is (= "L4" (:form-level init-state)))
      (is (vector? (:pending-approvals init-state)))))

  (testing "DeptDashboard initial state"
    (let [init-state (comp/get-initial-state dept-dashboard/DeptDashboard {})]
      (is (= "eng-dept" (:unit-id init-state)))))

  (testing "OrgChart initial state"
    (let [init-state (comp/get-initial-state org-chart/OrgChart {})]
      (is (true? (:loading init-state)))
      (is (map? (:hierarchy init-state)))))

  (testing "PolicySettings initial state"
    (let [init-state (comp/get-initial-state policy-settings/PolicySettings {})]
      (is (true? (:loading init-state)))
      (is (map? (:permissions init-state)))
      (is (vector? (:rules init-state))))))

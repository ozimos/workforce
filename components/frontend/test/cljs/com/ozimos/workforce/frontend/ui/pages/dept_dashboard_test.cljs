(ns com.ozimos.workforce.frontend.ui.pages.dept-dashboard-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard :as dept-dash]))

(deftest dept-dashboard-component-test
  (testing "DeptDashboard initial state contains expected defaults"
    (let [init-state (comp/get-initial-state dept-dash/DeptDashboard {})]
      (is (false? (:loading init-state)))
      (is (nil? (:error init-state)))
      (is (= "eng-dept" (:unit-id init-state)))
      (is (nil? (:dashboard init-state)))
      (is (vector? (:available-units init-state)))))

  (testing "DeptDashboard query contains expected fields"
    (let [q (comp/get-query dept-dash/DeptDashboard)]
      (is (contains? (set q) :loading))
      (is (contains? (set q) :error))
      (is (contains? (set q) :unit-id))
      (is (contains? (set q) :dashboard))
      (is (contains? (set q) :active-org))
      (is (contains? (set q) :available-units)))))

(ns com.ozimos.workforce.frontend.ui.pages.org-chart-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.org-chart :as org-chart]))

(deftest org-chart-component-test
  (testing "OrgChart initial state contains expected defaults"
    (let [init-state (comp/get-initial-state org-chart/OrgChart {})]
      (is (true? (:loading init-state)))
      (is (nil? (:error init-state)))
      (is (nil? (:active-org init-state)))
      (is (map? (:units init-state)))
      (is (map? (:hierarchy init-state)))
      (is (= "" (:search-term init-state)))
      (is (set? (:collapsed-nodes init-state)))
      (is (false? (:show-create-modal init-state)))
      (is (= 5 (get-in init-state [:create-form :budget])))
      (is (false? (:show-budget-modal init-state)))))

  (testing "OrgChart query contains expected fields"
    (let [q (comp/get-query org-chart/OrgChart)]
      (is (contains? (set q) :loading))
      (is (contains? (set q) :error))
      (is (contains? (set q) :active-org))
      (is (contains? (set q) :units))
      (is (contains? (set q) :hierarchy))
      (is (contains? (set q) :search-term))
      (is (contains? (set q) :show-create-modal))
      (is (contains? (set q) :show-budget-modal)))))

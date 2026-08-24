(ns com.ozimos.workforce.org.rbac-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.org.rbac :as rbac]))

(def sample-hierarchy
  {"unit-corp" #{"unit-eng" "unit-finance"}
   "unit-eng"  #{"unit-platform" "unit-product"}
   "unit-platform" #{"unit-infra" "unit-security"}
   "unit-product" #{}
   "unit-finance" #{"unit-accounting"}})

(def sample-role-permissions
  {:admin           {:view-headcount :view-all
                     :view-comp true
                     :view-bonus true
                     :view-rsu true}
   :hr              {:view-headcount :view-all
                     :view-comp true
                     :view-bonus true
                     :view-rsu false}
   :dept-head       {:view-headcount :view-tree
                     :view-comp true
                     :view-bonus false
                     :view-rsu false}
   :hiring-manager  {:view-headcount :view-own
                     :view-comp true
                     :view-bonus false
                     :view-rsu false}
   :employee        {:view-headcount :view-own
                     :view-comp false
                     :view-bonus false
                     :view-rsu false}})

(def sample-request
  {:request-id "req-101"
   :unit-id "unit-infra"
   :requester-id 42
   :title "Senior Infrastructure Engineer"
   :salary-band "$160k - $190k"
   :bonus-target "15%"
   :rsu "$50k / 4 yrs"
   :approved-by [10]
   :current-approver-id 20})

(deftest descendant-unit-test
  (testing "Same unit is descendant of itself"
    (is (rbac/descendant-unit? sample-hierarchy "unit-eng" "unit-eng")))

  (testing "Direct child unit"
    (is (rbac/descendant-unit? sample-hierarchy "unit-eng" "unit-platform")))

  (testing "Deep nested descendant"
    (is (rbac/descendant-unit? sample-hierarchy "unit-eng" "unit-infra"))
    (is (rbac/descendant-unit? sample-hierarchy "unit-corp" "unit-security")))

  (testing "Non-descendant sibling or ancestor"
    (is (not (rbac/descendant-unit? sample-hierarchy "unit-eng" "unit-finance")))
    (is (not (rbac/descendant-unit? sample-hierarchy "unit-platform" "unit-eng")))
    (is (not (rbac/descendant-unit? sample-hierarchy "unit-product" "unit-infra")))))

(deftest eval-headcount-visibility-test
  (testing "Admin has view-all and full unmasked compensation"
    (let [viewer {:user-id 999 :role :admin :unit-id "unit-corp"}
          result (rbac/eval-headcount-visibility viewer sample-request sample-hierarchy sample-role-permissions)]
      (is (some? result))
      (is (= "$160k - $190k" (:salary-band result)))
      (is (= "15%" (:bonus-target result)))
      (is (= "$50k / 4 yrs" (:rsu result)))))

  (testing "HR has view-all with RSU masked"
    (let [viewer {:user-id 888 :role :hr :unit-id "unit-corp"}
          result (rbac/eval-headcount-visibility viewer sample-request sample-hierarchy sample-role-permissions)]
      (is (some? result))
      (is (= "$160k - $190k" (:salary-band result)))
      (is (= "15%" (:bonus-target result)))
      (is (nil? (:rsu result)))))

  (testing "Department Head (Eng) can view infra request in their subtree with bonus/rsu masked"
    (let [viewer {:user-id 100 :role :dept-head :unit-id "unit-eng"}
          result (rbac/eval-headcount-visibility viewer sample-request sample-hierarchy sample-role-permissions)]
      (is (some? result))
      (is (= "$160k - $190k" (:salary-band result)))
      (is (nil? (:bonus-target result)))
      (is (nil? (:rsu result)))))

  (testing "Department Head (Finance) CANNOT view infra request outside their subtree"
    (let [viewer {:user-id 200 :role :dept-head :unit-id "unit-finance"}
          result (rbac/eval-headcount-visibility viewer sample-request sample-hierarchy sample-role-permissions)]
      (is (nil? result))))

  (testing "Requester (Employee) can view own request with comp/bonus/rsu masked"
    (let [viewer {:user-id 42 :role :employee :unit-id "unit-infra"}
          result (rbac/eval-headcount-visibility viewer sample-request sample-hierarchy sample-role-permissions)]
      (is (some? result))
      (is (= "Senior Infrastructure Engineer" (:title result)))
      (is (nil? (:salary-band result)))
      (is (nil? (:bonus-target result)))
      (is (nil? (:rsu result)))))

  (testing "Other employee CANNOT view request they are not an actor on"
    (let [viewer {:user-id 777 :role :employee :unit-id "unit-infra"}
          result (rbac/eval-headcount-visibility viewer sample-request sample-hierarchy sample-role-permissions)]
      (is (nil? result)))))

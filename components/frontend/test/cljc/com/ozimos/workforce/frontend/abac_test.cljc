(ns com.ozimos.workforce.frontend.abac-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [com.ozimos.workforce.frontend.abac :as abac]))

(def sample-headcount
  {:headcount/id "hc-001"
   :headcount/title "Senior Backend Engineer"
   :headcount/division-id "ENG"
   :headcount/dept-id "BE"
   :headcount/job-level "L5"
   :headcount/location "US-CA"
   :headcount/status "open"})

(def sample-employee
  {:person/id "emp-001"
   :person/name "Alice Smith"
   :person/division-id "ENG"
   :person/dept-id "BE"
   :person/job-level "L8"
   :person/location "US-CA"})

(deftest policy-active-test
  (testing "identifies active vs inactive policies"
    (is (false? (abac/policy-active? nil)))
    (is (false? (abac/policy-active? {})))
    (is (false? (abac/policy-active? {:allowed-divisions nil :allowed-depts nil})))
    (is (true? (abac/policy-active? {:allowed-divisions #{"ENG"}})))
    (is (true? (abac/policy-active? {:allowed-levels #{"L5"}})))))

(deftest accessible-headcount-test
  (testing "nil or inactive policy allows all"
    (is (true? (abac/accessible-headcount? sample-headcount nil)))
    (is (true? (abac/accessible-headcount? sample-headcount {}))))

  (testing "matching policy grants access"
    (let [policy {:allowed-divisions #{"ENG"}
                  :allowed-depts #{"BE"}
                  :allowed-levels #{"L5"}
                  :allowed-locations #{"US-CA"}}]
      (is (true? (abac/accessible-headcount? sample-headcount policy)))))

  (testing "division mismatch denies access"
    (is (false? (abac/accessible-headcount? sample-headcount {:allowed-divisions #{"SALES"}}))))

  (testing "department mismatch denies access"
    (is (false? (abac/accessible-headcount? sample-headcount {:allowed-depts #{"FE"}}))))

  (testing "level mismatch denies access"
    (is (false? (abac/accessible-headcount? sample-headcount {:allowed-levels #{"L3" "L4"}}))))

  (testing "location mismatch denies access"
    (is (false? (abac/accessible-headcount? sample-headcount {:allowed-locations #{"EU" "GB"}}))))

  (testing "partial policy only checks specified dimensions"
    (let [policy {:allowed-divisions #{"ENG"}}]
      (is (true? (abac/accessible-headcount? sample-headcount policy))))))

(deftest accessible-employee-test
  (testing "evaluates employee dimensions for reporting screens"
    (is (true? (abac/accessible-employee? sample-employee nil)))
    (is (true? (abac/accessible-employee? sample-employee {:allowed-divisions #{"ENG"}})))
    (is (false? (abac/accessible-employee? sample-employee {:allowed-divisions #{"SALES"}})))
    (is (true? (abac/accessible-employee? sample-employee {:allowed-levels #{"L8"}})))
    (is (false? (abac/accessible-employee? sample-employee {:allowed-levels #{"L4" "L5"}})))))

(deftest filter-accessible-collections-test
  (let [hcs [sample-headcount
             (assoc sample-headcount :headcount/id "hc-002" :headcount/division-id "SALES" :headcount/dept-id "ENT")
             (assoc sample-headcount :headcount/id "hc-003" :headcount/location "GB")]
        policy {:allowed-divisions #{"ENG"}
                :allowed-locations #{"US-CA"}}]
    (testing "filters headcounts by policy"
      (let [filtered (abac/filter-accessible-headcounts hcs policy)]
        (is (= 1 (count filtered)))
        (is (= "hc-001" (:headcount/id (first filtered))))))

    (testing "nil policy preserves all headcounts"
      (is (= 3 (count (abac/filter-accessible-headcounts hcs nil)))))))

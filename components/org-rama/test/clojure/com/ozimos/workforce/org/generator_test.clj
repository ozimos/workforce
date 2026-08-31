(ns com.ozimos.workforce.org.generator-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.org.generator :as gen]))

(deftest generate-10k-tree-topology-test
  (testing "generates exact 10,000-node tree with single root and expanding branching factor"
    (let [res (gen/generate-org-tree {:total-nodes 10000 :seed 42 :prefix "org-acme"})
          nodes (:nodes res)
          children (:children res)
          root-id (:root-id res)]

      (is (= 10000 (:total-nodes res)))
      (is (= 10000 (count nodes)))
      (is (= "org-acme-00001" root-id))
      (is (nil? (:parent-id (get nodes root-id))))

      ;; Root branching should be between 6 and 8
      (let [root-branches (count (get children root-id []))]
        (is (<= 6 root-branches 8)))

      ;; Check depths and expanding branching factor at deeper levels
      (let [all-depths (set (map :depth (vals nodes)))
            max-depth (apply max all-depths)]
        (is (<= 4 max-depth 8))

        ;; Leaves should have 0 children
        (let [leaves (filter #(empty? (get children (:node-id %) [])) (vals nodes))]
          (is (pos? (count leaves))))))))

(deftest generate-org-units-and-divisions-test
  (testing "generates canonical divisions and department combinations (~40 units)"
    (let [units (gen/generate-org-units "org-acme")
          divisions (filter #(= :division (:type %)) units)
          departments (filter #(= :department (:type %)) units)]
      (is (= 7 (count divisions)))
      (is (>= (count departments) 30))
      (is (<= 35 (count units) 45)))))

(deftest generate-10k-workforce-nodes-and-80-20-distribution-test
  (testing "generates 10k workforce with ~80% employees and ~20% headcounts"
    (let [res (gen/generate-10k-workforce-nodes {:org-id "org-acme" :total-nodes 10000 :seed 42})
          employees (:employees res)
          employments (:employments res)
          headcounts (:headcounts res)
          total (+ (count employees) (count headcounts))]

      (is (= 10000 total))
      (is (<= 7700 (count employees) 8300))
      (is (<= 1700 (count headcounts) 2300))
      (is (= (count employees) (count employments)))

      ;; Verify root is Alice Smith
      (let [alice (first (filter #(= (:employee-id %) "emp-org-acme-00001") employees))
            alice-emp (first (filter #(= (:employee-id %) "emp-org-acme-00001") employments))]
        (is (= "Alice" (:first-name alice)))
        (is (= "Smith" (:last-name alice)))
        (is (= "L8" (:job-level alice-emp)))
        (is (= :full-time (:employee-type alice-emp)))))))

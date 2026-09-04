(ns com.ozimos.workforce.org.unconnected-nodes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.org.core :as org-core]))

(deftest collect-all-reachable-nodes-test
  (testing "transitive closure collects all reachable descendants"
    (let [hierarchy {"root" ["mgr-1" "mgr-2"]
                     "mgr-1" ["emp-a" "emp-b"]
                     "mgr-2" ["req-hc-1"]
                     "orphan-mgr" ["orphan-child"]}
          reachable (org-core/collect-all-reachable-nodes hierarchy ["root"])]
      (is (= #{"root" "mgr-1" "mgr-2" "emp-a" "emp-b" "req-hc-1"} reachable)
          "Must include root and all reachable descendants")
      (is (not (contains? reachable "orphan-mgr"))
          "Must not include disconnected nodes")
      (is (not (contains? reachable "orphan-child"))
          "Must not include children of disconnected nodes"))))

(deftest compute-unconnected-data-test
  (testing "identifies disconnected employees and headcounts and partitions roots vs subtrees"
    (let [workforce [{:person/id "root" :person/name "CEO"}
                     {:person/id "mgr-1" :person/name "Manager 1"}
                     {:person/id "emp-a" :person/name "Alice"}
                     {:person/id "orphan-lead" :person/name "Isolated Lead"}
                     {:person/id "orphan-dev" :person/name "Isolated Dev"}
                     {:person/id "standalone-worker" :person/name "No Manager Worker"}]
          headcounts [{:headcount/id "req-hc-1" :headcount/title "Backend Eng"}
                      {:headcount/id "req-orphan-hc" :headcount/title "Isolated Headcount"}]
          hierarchy {"root" ["mgr-1"]
                     "mgr-1" ["emp-a" "req-hc-1"]
                     "orphan-lead" ["orphan-dev" "req-orphan-hc"]}
          parent-map {"mgr-1" "root"
                      "emp-a" "mgr-1"
                      "req-hc-1" "mgr-1"
                      "orphan-dev" "orphan-lead"
                      "req-orphan-hc" "orphan-lead"
                      "standalone-worker" nil}
          result (org-core/compute-unconnected-data workforce headcounts hierarchy ["root"] parent-map)]

      (testing "unconnected count"
        (is (= 4 (:unconnected-count result))
            "4 nodes are unconnected: orphan-lead, orphan-dev, req-orphan-hc, standalone-worker"))

      (testing "unconnected workforce list"
        (let [unconnected-emp-ids (set (map :person/id (:unconnected-workforce result)))]
          (is (= #{"orphan-lead" "orphan-dev" "standalone-worker"} unconnected-emp-ids))))

      (testing "unconnected headcounts list"
        (let [unconnected-hc-ids (set (map :headcount/id (:unconnected-headcounts result)))]
          (is (= #{"req-orphan-hc"} unconnected-hc-ids))))

      (testing "unconnected hierarchy subtree"
        (is (= {"orphan-lead" ["orphan-dev" "req-orphan-hc"]}
               (:unconnected-hierarchy result))
            "Hierarchy for orphan subtree must be preserved"))

      (testing "unconnected roots"
        (is (= ["orphan-lead" "standalone-worker"]
               (sort (:unconnected-roots result)))
            "Orphan lead and standalone worker are unconnected roots (parents outside unconnected set)")))))

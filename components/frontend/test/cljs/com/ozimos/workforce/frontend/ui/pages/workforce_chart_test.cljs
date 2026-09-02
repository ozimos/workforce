(ns com.ozimos.workforce.frontend.ui.pages.workforce-chart-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.workforce-chart :as wf]))

(def sample-workforce
  {"emp-alice" {:person/id "emp-alice"
                :person/name "Alice Smith"
                :person/title "CEO"
                :person/role :admin
                :person/department-name "Executive"
                :person/compensation {:salary 300000 :currency "USD"}}
   "emp-bob"   {:person/id "emp-bob"
                :person/name "Bob Jones"
                :person/title "VP Engineering"
                :person/role :vp
                :person/department-name "Engineering"
                :person/compensation {:salary 220000 :currency "USD"}}})

(def sample-hierarchy
  {nil ["emp-alice"]
   "emp-alice" ["emp-bob"]})

(def sample-headcounts
  {"emp-alice" [{:headcount/id "req-1"
                 :headcount/title "VP Product"
                 :headcount/job-level "L7"
                 :headcount/location "US-CA"
                 :headcount/division-id "div-prod"
                 :headcount/dept-id "dept-prod"}]})

(deftest component-metadata-test
  (testing "WorkforceNode and HeadcountCard have Fulcro query and ident for normalization"
    (is (= :person/id (:ident (meta wf/WorkforceNode))))
    (is (vector? (:query (meta wf/WorkforceNode))))
    (is (= :headcount/id (:ident (meta wf/HeadcountCard))))
    (is (vector? (:query (meta wf/HeadcountCard))))
    (is (= :workforce-chart/root (:ident (meta wf/WorkforceChart))))))

(deftest workforce-chart-rendering-test
  (testing "renders real backend workforce tree"
    (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                    :workforce-hierarchy sample-hierarchy
                                    :collapsed-workforce #{}
                                    :permissions {:view-comp true}})]
      (is (vector? hiccup))
      (let [rendered-str (pr-str hiccup)]
        (is (clojure.string/includes? rendered-str "Alice Smith"))
        (is (clojure.string/includes? rendered-str "Bob Jones"))
        (is (clojure.string/includes? rendered-str "CEO"))
        (is (clojure.string/includes? rendered-str "VP Engineering")))))

  (testing "renders authentic empty state when no workforce records exist"
    (let [hiccup (wf/WorkforceChart {:workforce {}
                                    :workforce-hierarchy {}
                                    :loading false})]
      (is (clojure.string/includes? (pr-str hiccup) "No workforce members registered in this organization"))
      ;; Ensure no hardcoded mock personas appear
      (is (not (clojure.string/includes? (pr-str hiccup) "Frank Miller")))
      (is (not (clojure.string/includes? (pr-str hiccup) "Carol Williams"))))))

(deftest rbac-comp-masking-test
  (testing "authorized viewer sees compensation figures"
    (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                    :workforce-hierarchy sample-hierarchy
                                    :collapsed-workforce #{}
                                    :permissions {:view-comp true}})]
      (is (clojure.string/includes? (pr-str hiccup) "$300,000"))))

  (testing "unauthorized viewer sees restricted notice"
    (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                    :workforce-hierarchy sample-hierarchy
                                    :collapsed-workforce #{}
                                    :permissions {:role :employee :view-comp false}})]
      (is (clojure.string/includes? (pr-str hiccup) "🔒 Comp restricted"))
      (is (not (clojure.string/includes? (pr-str hiccup) "$300,000"))))))

(deftest abac-headcount-integration-test
  (testing "headcounts matching ABAC policy are visible"
    (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                    :workforce-hierarchy sample-hierarchy
                                    :headcounts-by-manager sample-headcounts
                                    :abac/policy {:allowed-divisions #{"div-prod"}}})]
      (is (clojure.string/includes? (pr-str hiccup) "VP Product"))))

  (testing "headcounts forbidden by ABAC policy are hidden"
    (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                    :workforce-hierarchy sample-hierarchy
                                    :headcounts-by-manager sample-headcounts
                                    :abac/policy {:allowed-divisions #{"div-eng"}}})]
      (is (not (clojure.string/includes? (pr-str hiccup) "VP Product"))))))

(ns com.ozimos.workforce.frontend.ui.pages.people-chart-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.people-chart-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:loading false
          :error nil
          :active-org {:org/id "org-acme" :org/name "Acme Corp" :org/role "ADMIN"}
          :people {"u-alice" {:person/id "u-alice" :person/name "Alice Smith" :person/title "CEO" :person/role :admin :person/department-name "Exec" :person/compensation {:salary 320000 :currency "USD"}}
                   "u-dan"   {:person/id "u-dan" :person/name "Dan Johnson" :person/title "Eng Manager" :person/role :hiring-manager :person/department-name "Backend" :person/manager-id "u-alice" :person/compensation {:salary 175000 :currency "USD"}}}
          :people-hierarchy {nil ["u-alice"]
                             "u-alice" ["u-dan"]}
          :people-search ""
          :collapsed-people #{}
          :permissions {:view-comp true}}
         overrides))

(defn- valid-hiccup? [node]
  (cond
    (nil? node) true
    (string? node) (not (or (str/starts-with? (str/trim node) "[") (str/starts-with? (str/trim node) "{")))
    (number? node) true
    (boolean? node) true
    (vector? node) (and (keyword? (first node))
                        (let [[_ maybe-attrs & more] node
                              children (if (map? maybe-attrs) more (cons maybe-attrs more))]
                          (every? valid-hiccup? children)))
    (sequential? node) (every? valid-hiccup? node)
    :else false))

(deftest people-chart-renders-tree
  (testing "renders CEO and reporting staff"
    (let [hiccup (sut/PeopleChartReplicant (base-props {}))
          html (rs/render hiccup)]
      (is (true? (valid-hiccup? hiccup)))
      (is (str/includes? html "People Organization Chart"))
      (is (str/includes? html "Alice Smith"))
      (is (str/includes? html "Dan Johnson"))
      (is (str/includes? html "/org-chart-2")))))

(deftest view-comp-rbac-masking
  (testing "shows compensation when user has view-comp permission"
    (let [html (rs/render (sut/PeopleChartReplicant (base-props {:active-org {:org/role "ADMIN"}})))]
      (is (str/includes? html "$320000"))
      (is (str/includes? html "Comp Visible"))))
  (testing "masks compensation when user lacks view-comp permission"
    (let [html (rs/render (sut/PeopleChartReplicant (base-props {:active-org {:org/role "employee"}
                                                                 :permissions {:view-comp false}})))]
      (is (not (str/includes? html "$320000")))
      (is (str/includes? html "Comp restricted"))
      (is (str/includes? html "Comp Masked")))))

(deftest pure-state-transitions
  (testing "collapse toggle and search pure functions"
    (let [db (base-props {})]
      (is (= #{"u-alice"} (:collapsed-people (sut/toggle-person-collapse-state db "u-alice"))))
      (is (= #{} (:collapsed-people (sut/expand-all-people-state (assoc db :collapsed-people #{"u-alice"})))))
      (is (= "dan" (:people-search (sut/set-people-search-state db "dan")))))))

(deftest headless-interaction
  (testing "denormalize and transaction support"
    (let [app-inst (app/headless-synchronous-app sut/PeopleChartReplicant)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/PeopleChartReplicant))]
      (swap! state-atom merge (base-props {}))
      (let [h1 (sut/PeopleChartReplicant (denorm/db->tree query @state-atom @state-atom))]
        (is (str/includes? (rs/render h1) "Alice Smith"))
        (comp/transact! app-inst [(sut/set-people-search {:value "Dan"})])
        (is (= "Dan" (:people-search @state-atom)))
        (let [h2 (sut/PeopleChartReplicant (denorm/db->tree query @state-atom @state-atom))]
          (is (str/includes? (rs/render h2) "Dan")))))))

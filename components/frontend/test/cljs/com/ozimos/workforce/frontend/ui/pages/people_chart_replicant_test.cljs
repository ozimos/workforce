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

(defn- base-headcounts []
  {:headcount/id    "hc-001"
   :headcount/title "Senior Engineer"
   :headcount/division-id "ENG"
   :headcount/dept-id "BE"
   :headcount/job-level "L5"
   :headcount/location "US"
   :headcount/status "open"})

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

;; =============================================================================
;; ABAC Headcount Filtering Tests (Unit-Level, Pure Functions)
;; =============================================================================

(deftest accessible-headcount-predicate
  (testing "nil policy grants access to all headcounts (unrestricted/admin)"
    (is (true? (sut/accessible-headcount? (base-headcounts) nil))))

  (testing "matching all allowed dimensions grants access"
    (let [policy {:allowed-divisions #{"ENG"}
                  :allowed-depts #{"BE"}
                  :allowed-levels #{"L5"}
                  :allowed-locations #{"US"}}]
      (is (true? (sut/accessible-headcount? (base-headcounts) policy)))))

  (testing "division mismatch denies access"
    (let [policy {:allowed-divisions #{"SALES"}}]
      (is (false? (sut/accessible-headcount? (base-headcounts) policy)))))

  (testing "dept mismatch denies access"
    (let [policy {:allowed-depts #{"FE"}}]
      (is (false? (sut/accessible-headcount? (base-headcounts) policy)))))

  (testing "job level mismatch denies access"
    (let [policy {:allowed-levels #{"L3" "L4"}}]
      (is (false? (sut/accessible-headcount? (base-headcounts) policy)))))

  (testing "location mismatch denies access"
    (let [policy {:allowed-locations #{"EU" "Remote"}}]
      (is (false? (sut/accessible-headcount? (base-headcounts) policy)))))

  (testing "nil dimension in policy means unrestricted for that dimension"
    (let [policy {:allowed-divisions nil   ;; unrestricted
                  :allowed-depts #{"BE"}
                  :allowed-levels nil      ;; unrestricted
                  :allowed-locations #{"US" "EU"}}]
      (is (true? (sut/accessible-headcount? (base-headcounts) policy)))))

  (testing "partial policy — only specified dimensions are checked"
    (let [policy {:allowed-divisions #{"ENG"}}  ;; only division restricted
          hc-be {:headcount/id "hc-be" :headcount/division-id "ENG" :headcount/dept-id "ANY"
                 :headcount/job-level "L2" :headcount/location "LATAM"}]
      ;; All other dimensions unrestricted (nil) — only division matters
      (is (true? (sut/accessible-headcount? hc-be policy)))))

  (testing "empty policy map behaves as fully unrestricted (all nil dimensions)"
    (is (true? (sut/accessible-headcount? (base-headcounts) {})))))

(deftest filter-accessible-headcounts-pure
  (let [hc-eng-be  {:headcount/id "hc-1" :headcount/division-id "ENG" :headcount/dept-id "BE"
                    :headcount/job-level "L5" :headcount/location "US"}
        hc-eng-fe  {:headcount/id "hc-2" :headcount/division-id "ENG" :headcount/dept-id "FE"
                    :headcount/job-level "L3" :headcount/location "EU"}
        hc-sales   {:headcount/id "hc-3" :headcount/division-id "SALES" :headcount/dept-id "SDR"
                    :headcount/job-level "L2" :headcount/location "US"}]

    (testing "nil policy returns all headcounts"
      (is (= [hc-eng-be hc-eng-fe hc-sales]
             (sut/filter-accessible-headcounts [hc-eng-be hc-eng-fe hc-sales] nil))))

    (testing "policy restricting to ENG division filters out SALES"
      (let [policy {:allowed-divisions #{"ENG"}}
            result (sut/filter-accessible-headcounts [hc-eng-be hc-eng-fe hc-sales] policy)]
        (is (= 2 (count result)))
        (is (not (some #(= "hc-3" (:headcount/id %)) result)))))

    (testing "policy restricting to BE dept returns only BE headcount"
      (let [policy {:allowed-depts #{"BE"}}
            result (sut/filter-accessible-headcounts [hc-eng-be hc-eng-fe hc-sales] policy)]
        (is (= 1 (count result)))
        (is (= "hc-1" (:headcount/id (first result))))))

    (testing "policy restricting to L5 level returns only L5 headcounts"
      (let [policy {:allowed-levels #{"L5"}}
            result (sut/filter-accessible-headcounts [hc-eng-be hc-eng-fe hc-sales] policy)]
        (is (= 1 (count result)))
        (is (= "hc-1" (:headcount/id (first result))))))

    (testing "combined division+location policy filters correctly"
      (let [policy {:allowed-divisions #{"ENG"}
                    :allowed-locations #{"EU"}}
            result (sut/filter-accessible-headcounts [hc-eng-be hc-eng-fe hc-sales] policy)]
        (is (= 1 (count result)))
        (is (= "hc-2" (:headcount/id (first result))))))

    (testing "empty headcount list returns empty"
      (is (= [] (sut/filter-accessible-headcounts [] {:allowed-divisions #{"ENG"}}))))))

;; =============================================================================
;; ABAC Integration Tests (Rendering)
;; =============================================================================

(deftest abac-headcounts-rendering
  (testing "headcounts visible when abac-policy is nil (unrestricted)"
    (let [hcs-by-mgr {"u-alice" [{:headcount/id "hc-1"
                                  :headcount/title "Senior Engineer"
                                  :headcount/division-id "ENG"
                                  :headcount/dept-id "BE"
                                  :headcount/job-level "L5"
                                  :headcount/location "US"}]}
          props (base-props {:headcounts-by-manager hcs-by-mgr})
          html (rs/render (sut/PeopleChartReplicant props))]
      (is (str/includes? html "Senior Engineer"))
      (is (str/includes? html "HC Unrestricted"))))

  (testing "ABAC-allowed headcounts are shown; forbidden ones hidden"
    (let [hcs-by-mgr {"u-alice" [{:headcount/id "hc-1"
                                  :headcount/title "Senior Engineer"
                                  :headcount/division-id "ENG"
                                  :headcount/dept-id "BE"
                                  :headcount/job-level "L5"
                                  :headcount/location "US"}
                                 {:headcount/id "hc-2"
                                  :headcount/title "Sales Director"
                                  :headcount/division-id "SALES"
                                  :headcount/dept-id "AE"
                                  :headcount/job-level "M3"
                                  :headcount/location "EU"}]}
          ;; Only ENG division allowed
          abac-policy {:allowed-divisions #{"ENG"}}
          props (base-props {:headcounts-by-manager hcs-by-mgr
                             :abac/headcount-policy abac-policy})
          html (rs/render (sut/PeopleChartReplicant props))]
      (is (str/includes? html "Senior Engineer"))       ;; ENG → visible
      (is (not (str/includes? html "Sales Director")))  ;; SALES → hidden
      (is (str/includes? html "HC Scoped"))))           ;; policy indicator shown

  (testing "fully forbidden policy hides all headcounts"
    (let [hcs-by-mgr {"u-alice" [{:headcount/id "hc-1"
                                  :headcount/title "Senior Engineer"
                                  :headcount/division-id "ENG"
                                  :headcount/dept-id "BE"
                                  :headcount/job-level "L5"
                                  :headcount/location "US"}]}
          abac-policy {:allowed-divisions #{"SALES"}}  ;; ENG not allowed
          props (base-props {:headcounts-by-manager hcs-by-mgr
                             :abac/headcount-policy abac-policy})
          html (rs/render (sut/PeopleChartReplicant props))]
      (is (not (str/includes? html "Senior Engineer")))
      ;; But employees still visible
      (is (str/includes? html "Alice Smith"))))

  (testing "employees are never hidden by ABAC (only headcounts are filtered)"
    (let [abac-policy {:allowed-divisions #{}  ;; nothing allowed
                       :allowed-depts #{}
                       :allowed-levels #{}
                       :allowed-locations #{}}
          props (base-props {:abac/headcount-policy abac-policy})
          html (rs/render (sut/PeopleChartReplicant props))]
      ;; Employees always visible — ABAC doesn't touch person nodes
      (is (str/includes? html "Alice Smith"))
      (is (str/includes? html "Dan Johnson")))))

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

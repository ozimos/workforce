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
  (let [hier-with-hc {nil ["emp-alice"]
                      "emp-alice" ["emp-bob" "req-1"]}]
    (testing "headcounts matching ABAC policy are visible"
      (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                      :workforce-hierarchy hier-with-hc
                                      :headcounts-by-manager sample-headcounts
                                      :abac/policy {:allowed-divisions #{"div-prod"}}})]
        (is (str/includes? (pr-str hiccup) "VP Product"))))

    (testing "headcounts forbidden by ABAC policy are hidden"
      (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                      :workforce-hierarchy hier-with-hc
                                      :headcounts-by-manager sample-headcounts
                                      :abac/policy {:allowed-divisions #{"div-eng"}}})]
        (is (not (str/includes? (pr-str hiccup) "VP Product")))))))

(deftest headcount-actors-and-tree-placement-test
  (let [headcount-data {"req-10" {:headcount/id "req-10"
                                  :headcount/title "Staff Backend Engineer"
                                  :headcount/job-level "L5"
                                  :headcount/location "Remote"
                                  :headcount/dept-id "Engineering"
                                  :headcount/status "open"
                                  :headcount/hiring-manager "emp-bob"
                                  :headcount/reporting-manager "emp-bob"
                                  :headcount/acting-reporting-manager? true
                                  :headcount/recruiters ["recruiter-1"]
                                  :headcount/approvers ["approver-1" "approver-2"]
                                  :headcount/collaborators ["collab-1"]
                                  :headcount/sourcers ["sourcer-1"]
                                  :headcount/owner "emp-alice"}
                        "req-11" {:headcount/id "req-11"
                                  :headcount/title "Junior Engineer"
                                  :headcount/job-level "L3"
                                  :headcount/location "Remote"
                                  :headcount/dept-id "Engineering"
                                  :headcount/status "open"
                                  :headcount/reporting-manager {:type :headcount :id "req-10"}}}
        workforce-with-acting {"emp-alice" {:person/id "emp-alice" :person/name "Alice Smith" :person/title "CEO"}
                               "emp-bob"   {:person/id "emp-bob" :person/name "Bob Jones" :person/title "VP Eng"
                                            :person/acting-reporting-manager? true}}
        hierarchy {nil ["emp-alice"]
                   "emp-alice" ["emp-bob" "req-10"]
                   "req-10" ["req-11"]}]

    (testing "headcounts slot directly into the tree hierarchy alongside employees"
      (let [hiccup (wf/WorkforceChart {:workforce workforce-with-acting
                                       :workforce-hierarchy hierarchy
                                       :headcounts headcount-data})
            rendered (pr-str hiccup)]
        ;; Both employee and headcount are rendered in the tree
        (is (str/includes? rendered "Bob Jones"))
        (is (str/includes? rendered "Staff Backend Engineer"))
        ;; Headcount card displays requisition badge
        (is (str/includes? rendered "Open Headcount"))
        ;; Nested headcount reporting to another headcount is rendered
        (is (str/includes? rendered "Junior Engineer"))))

    (testing "headcount card displays all actors"
      (let [hiccup (wf/WorkforceChart {:workforce workforce-with-acting
                                       :workforce-hierarchy hierarchy
                                       :headcounts headcount-data})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "Hiring Mgr:"))
        (is (str/includes? rendered "emp-bob"))
        (is (str/includes? rendered "Acting Reporting Mgr:"))
        (is (str/includes? rendered "Recruiter:"))
        (is (str/includes? rendered "recruiter-1"))
        (is (str/includes? rendered "Approvers:"))
        (is (str/includes? rendered "approver-1, approver-2"))
        (is (str/includes? rendered "Collaborators:"))
        (is (str/includes? rendered "collab-1"))
        (is (str/includes? rendered "Sourcers:"))
        (is (str/includes? rendered "sourcer-1"))
        (is (str/includes? rendered "Owner:"))
        (is (str/includes? rendered "emp-alice"))))

    (testing "acting reporting manager badge renders on employee card"
      (let [hiccup (wf/WorkforceChart {:workforce workforce-with-acting
                                       :workforce-hierarchy hierarchy
                                       :headcounts headcount-data})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "⚡ Acting Reporting Manager"))))))

(deftest root-resolution-algorithm-test
  (testing "1. Default CEO title match"
    (let [nodes [{:person/id "e1" :person/title "Founder"}
                 {:person/id "e2" :person/title "Chief Executive Officer (CEO)"}]
          hier {"e1" ["e3"] "e2" ["e4"]}
          res (wf/resolve-full-org-root nodes hier {})]
      (is (= "e2" (:root-id res)))
      (is (nil? (:synthetic-node res)))))

  (testing "2. Graceful fallback when no CEO exists: picks employee with highest descendant count"
    (let [nodes [{:person/id "lead-a" :person/title "Managing Director"}
                 {:person/id "lead-b" :person/title "Partner"}
                 {:person/id "emp-1" :person/title "Associate"}
                 {:person/id "emp-2" :person/title "Associate"}
                 {:person/id "emp-3" :person/title "Junior"}]
          ;; lead-a has 3 descendants (emp-1 -> emp-3, emp-2)
          ;; lead-b has 0 descendants
          hier {"lead-a" ["emp-1" "emp-2"]
                "emp-1" ["emp-3"]}
          res (wf/resolve-full-org-root nodes hier {})]
      (is (= "lead-a" (:root-id res)) "Must gracefully select employee with highest subtree size")
      (is (= 3 (wf/count-descendants hier "lead-a")))
      (is (= 0 (wf/count-descendants hier "lead-b")))))

  (testing "3. App setting: explicit root ID"
    (let [nodes [{:person/id "e1" :person/title "CEO"}
                 {:person/id "e2" :person/title "President"}]
          hier {"e1" [] "e2" ["e1"]}
          res (wf/resolve-full-org-root nodes hier {:root-id "e2"})]
      (is (= "e2" (:root-id res)))))

  (testing "4. App setting: co-equal top-level leadership creates synthetic visual root node"
    (let [nodes [{:person/id "co-1" :person/title "Co-Founder"}
                 {:person/id "co-2" :person/title "Co-Founder"}]
          hier {"co-1" ["e3"] "co-2" ["e4"]}
          res (wf/resolve-full-org-root nodes hier {:co-equal-ids ["co-1" "co-2"]
                                                   :visual-root-title "Executive Committee"})]
      (is (= "__visual_root__" (:root-id res)))
      (is (some? (:synthetic-node res)))
      (is (= "Executive Committee" (:person/name (:synthetic-node res))))
      (is (= ["co-1" "co-2"] (:co-equal-ids res))))))

(deftest dual-tabs-and-my-org-test
  (let [extended-workforce {"emp-alice" {:person/id "emp-alice"
                                         :person/name "Alice Smith"
                                         :person/title "CEO"
                                         :person/email "alice@acme.com"}
                            "emp-bob"   {:person/id "emp-bob"
                                         :person/name "Bob Jones"
                                         :person/title "VP Engineering"
                                         :person/email "bob@acme.com"}
                            "emp-carol" {:person/id "emp-carol"
                                         :person/name "Carol Danvers"
                                         :person/title "Staff Engineer"
                                         :person/email "carol@acme.com"}}
        extended-hierarchy {nil ["emp-alice"]
                            "emp-alice" ["emp-bob"]
                            "emp-bob" ["emp-carol"]}]

    (testing "My org tab is hidden when current user email is not in workforce and no custom root is set"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :current-user/email "outsider@unknown.com"
                                      :custom-root-id nil})]
        (is (str/includes? (pr-str hiccup) "Full org"))
        (is (not (str/includes? (pr-str hiccup) "👤 My org")))))

    (testing "My org tab is visible when current user email matches an employee"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                       :workforce-hierarchy extended-hierarchy
                                       :current-user/email "bob@acme.com"
                                       :custom-root-id nil})]
        (is (str/includes? (pr-str hiccup) "Full org"))
        (is (str/includes? (pr-str hiccup) "👤 My org"))))

    (testing "My org tab renders subtree rooted at matched user"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :current-user/email "bob@acme.com"
                                      :active-chart-tab :tab/my-org
                                      :custom-root-id nil})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "Viewing My Org: "))
        (is (str/includes? rendered "Bob Jones"))
        (is (str/includes? rendered "Carol Danvers"))
        ;; Alice (CEO) is above Bob, so Alice is not in Bob's subtree
        (is (not (str/includes? rendered "Alice Smith")))))

    (testing "Set as Root button is rendered on each card"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :current-user/email "alice@acme.com"
                                      :active-chart-tab :tab/full-org})]
        (is (str/includes? (pr-str hiccup) "Set as Root"))))

    (testing "Manually set custom root makes My org visible and renders custom subtree"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :current-user/email "outsider@unknown.com"
                                      :custom-root-id "emp-bob"
                                      :active-chart-tab :tab/my-org})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "👤 My org"))
        (is (str/includes? rendered "Custom Root"))
        (is (str/includes? rendered "Viewing My Org: "))
        (is (str/includes? rendered "Bob Jones"))
        (is (str/includes? rendered "Carol Danvers"))
        (is (not (str/includes? rendered "Alice Smith")))))))

(deftest workforce-chart-progressive-loading-ui-test
  (let [extended-workforce {"emp-alice" {:person/id "emp-alice" :person/name "Alice Smith" :person/title "CEO" :person/email "alice@acme.com"}
                            "emp-bob" {:person/id "emp-bob" :person/name "Bob Jones" :person/title "VP Eng" :person/email "bob@acme.com"}
                            "emp-carol" {:person/id "emp-carol" :person/name "Carol Danvers" :person/title "Eng Manager" :person/email "carol@acme.com"}}
        extended-hierarchy {nil ["emp-alice"]
                            "emp-alice" ["emp-bob"]
                            "emp-bob" ["emp-carol"]}]
    (testing "Card shows '⌛ Loading...' when branch is in-flight"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :loading-branches #{"emp-alice"}
                                      :collapsed-workforce #{"emp-alice"}
                                      :active-chart-tab :tab/full-org})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "⌛ Loading..."))))

    (testing "Search autocomplete dropdown renders matching employees"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :workforce-search "Dave"
                                      :server-search-results [{:person/id "emp-dave"
                                                              :person/name "Dave Wilson"
                                                              :person/title "Staff Engineer"
                                                              :person/department-name "Backend"
                                                              :person/ancestor-path ["emp-alice" "emp-bob" "emp-dave"]}]
                                      :active-chart-tab :tab/full-org})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "Dave Wilson"))
        (is (str/includes? rendered "Staff Engineer"))
        (is (str/includes? rendered "Jump ➔"))))

    (testing "Total workforce badge reflects total vs loaded counts"
      (let [hiccup (wf/WorkforceChart {:workforce extended-workforce
                                      :workforce-hierarchy extended-hierarchy
                                      :total-workforce-count 10000
                                      :active-chart-tab :tab/full-org})
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "10000"))
        (is (str/includes? rendered "loaded"))))))

(deftest avatar-rendering-test
  (testing "renders img with avatar-url, loading=lazy, decoding=async, and explicit dimensions when present"
    (let [wf-with-avatar (assoc-in sample-workforce ["emp-alice" :person/avatar-url] "https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?w=80")
          hiccup (wf/WorkforceChart {:workforce wf-with-avatar
                                    :workforce-hierarchy sample-hierarchy
                                    :collapsed-workforce #{}})
          rendered (pr-str hiccup)]
      (is (str/includes? rendered ":src \"https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?w=80\""))
      (is (str/includes? rendered ":loading \"lazy\""))
      (is (str/includes? rendered ":decoding \"async\""))
      (is (str/includes? rendered ":width 40"))
      (is (str/includes? rendered ":height 40"))))

  (testing "falls back to colored initials badge when avatar-url is absent"
    (let [wf-without-avatar (assoc-in sample-workforce ["emp-bob" :person/avatar-url] nil)
          hiccup (wf/WorkforceChart {:workforce wf-without-avatar
                                    :workforce-hierarchy sample-hierarchy
                                    :collapsed-workforce #{}})
          rendered (pr-str hiccup)]
      (is (str/includes? rendered "\"BJ\"")))))

(deftest workforce-chart-pan-zoom-rendering-test
  (testing "renders viewport with GPU transform style and floating zoom HUD"
    (let [hiccup (wf/WorkforceChart {:workforce sample-workforce
                                    :workforce-hierarchy sample-hierarchy
                                    :chart/pan {:x 120 :y 45}
                                    :chart/zoom 1.15
                                    :chart/panning? true})
          rendered (pr-str hiccup)]
      ;; Check transform plane container styling
      (is (str/includes? rendered "translate3d(120px, 45px, 0) scale(1.15)"))
      (is (str/includes? rendered ":cursor \"grabbing\""))
      ;; Check zoom HUD controls
      (is (str/includes? rendered "115%"))
      (is (str/includes? rendered "➕"))
      (is (str/includes? rendered "➖"))
      (is (str/includes? rendered "⛶ Fit")))))

(deftest workforce-chart-unconnected-drawer-test
  (let [orphan-emp {:person/id "emp-orphan"
                    :person/name "Oscar Orphan"
                    :person/title "Rogue Engineer"
                    :person/department-name "Lab"}
        orphan-child {:person/id "emp-child"
                      :person/name "Charlie Child"
                      :person/title "Junior Intern"}
        props {:workforce sample-workforce
               :workforce-hierarchy sample-hierarchy
               :unconnected/workforce [orphan-emp orphan-child]
               :unconnected/headcounts []
               :unconnected/hierarchy {"emp-orphan" ["emp-child"]}
               :unconnected/roots ["emp-orphan"]
               :unconnected/count 2}]
    (testing "renders disconnected badge in stat badges row"
      (let [hiccup (wf/WorkforceChart props)
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "⚠️ Disconnected:"))
        (is (str/includes? rendered "\"2\""))))

    (testing "renders collapsed floating trigger button when drawer is closed"
      (let [hiccup (wf/WorkforceChart (assoc props :unconnected-drawer-open? false))
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "⚠️ Disconnected"))
        (is (not (str/includes? rendered "Disconnected Nodes")))))

    (testing "renders expanded slide-over drawer with orphan hierarchy when open"
      (let [hiccup (wf/WorkforceChart (assoc props :unconnected-drawer-open? true))
            rendered (pr-str hiccup)]
        (is (str/includes? rendered "Disconnected Nodes"))
        (is (str/includes? rendered "Oscar Orphan"))
        (is (str/includes? rendered "Orphan Subtree Root"))
        (is (str/includes? rendered "Charlie Child"))
        (is (str/includes? rendered "Subtree Report"))
        (is (str/includes? rendered "🎯 Set as Root"))))))


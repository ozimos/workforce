(ns com.ozimos.workforce.frontend.core-routing-test
  "Unit tests for routing, auth-guards, and org-chart rendering in Pure Replicant frontend core."
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.statecharts.integration.fulcro :as scf]
   [com.ozimos.workforce.frontend.auth-statechart :as auth-sc]
   [com.ozimos.workforce.frontend.ui.pages.org-chart :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.workforce-chart :as workforce-chart]
   [com.ozimos.workforce.frontend.ui.root :as root-rc]
   [replicant.string :as rs]))

(deftest auth-guard-rendering
  (testing "when unauthenticated, root component renders login view and NO navbar"
    (let [props {:route :route/login
                 :logged-in? false
                 :active-org nil
                 :orgs []}
          hiccup (root-rc/Root props)
          html (rs/render hiccup)]
      (is (false? (:logged-in? props)))
      ;; Nav branding should NOT be rendered when unauthenticated
      (is (not (str/includes? html "href=\"/org-chart\"")))
      (is (str/includes? html "Sign in to your account"))))

  (testing "when authenticated, root component renders navbar with org badge"
    (let [props {:route :route/home
                 :logged-in? true
                 :active-org {:org/id 0 :org/name "Acme Corp" :org/role "ADMIN"}
                 :orgs [{:org/id 0 :org/name "Acme Corp"}]
                 :dropdown-open false}
          hiccup (root-rc/Root props)
          html (rs/render hiccup)]
      (is (true? (:logged-in? props)))
      (is (str/includes? html "Workforce"))
      (is (str/includes? html "Acme Corp"))
      (is (str/includes? html "href=\"/org-chart\""))
      (is (str/includes? html "Workforce Dashboard")))))

(deftest org-chart-populated-units-rendering
  (testing "resolve-page-view maps :route/org-chart-2 to OrgChart"
    (is (= org-chart/OrgChart
           (root-rc/resolve-page-view :route/org-chart-2))))

  (testing "resolve-page-view maps :route/org-chart to WorkforceChart"
    (is (= workforce-chart/WorkforceChart
           (root-rc/resolve-page-view :route/org-chart))))

  (testing "when org units exist, org-chart-2 page renders hierarchy tree, KPI badges, and NOT empty message"
    (let [units {"org-acme-div-eng"
                 {:unit/id "org-acme-div-eng"
                  :unit/name "Engineering"
                  :unit/division-id "ENG"
                  :unit/dept-id "ALL"
                  :unit/parent-id nil
                  :unit/budget 25
                  :unit/filled 20
                  :unit/open 5
                  :unit/pending 0}
                 "org-acme-dept-eng-frontend"
                 {:unit/id "org-acme-dept-eng-frontend"
                  :unit/name "Web Platform & Frontend Apps"
                  :unit/division-id "ENG"
                  :unit/dept-id "FE"
                  :unit/parent-id "org-acme-div-eng"
                  :unit/budget 8
                  :unit/filled 7
                  :unit/open 1
                  :unit/pending 0}}
          hierarchy {nil ["org-acme-div-eng"]
                     "org-acme-div-eng" ["org-acme-dept-eng-frontend"]}
          ;; Test OrgChart directly rather than via Root
          ;; to isolate the routing content from the root layout rendering
          page-props {:loading false
                      :error nil
                      :active-org {:org/id 0 :org/name "Acme Corp" :org/role "ADMIN"}
                      :units units
                      :hierarchy hierarchy
                      :collapsed-nodes #{}
                      :search-term ""}
          html (rs/render (org-chart/OrgChart page-props))]
      (is (str/includes? html "Divisions &amp; Departments Chart"))
      (is (str/includes? html "Acme Corp"))
      (is (str/includes? html "Engineering"))
      (is (str/includes? html "Web Platform &amp; Frontend Apps"))
      (is (str/includes? html "Allocated Budget"))
      (is (str/includes? html "Filled Seats"))
      ;; Crucial regression check: empty state message must NOT appear
      (is (not (str/includes? html "No Organizational Units Found")))
      (is (not (str/includes? html "Get started by creating your first Division")))))

  (testing "org-chart renders workforce chart by default"
    (let [props {:route :route/org-chart
                 :logged-in? true
                 :active-org {:org/id 0 :org/name "Acme Corp" :org/role "ADMIN"}
                 :orgs [{:org/id 0 :org/name "Acme Corp"}]
                 :workforce {"u-alice" {:person/id "u-alice" :person/name "Alice Smith" :person/title "CEO" :person/role :admin :person/department-name "Exec" :person/compensation {:salary 320000 :currency "USD"}}}
                 :workforce-hierarchy {nil ["u-alice"]}
                 :collapsed-workforce #{}
                 :workforce-search ""
                 :loading false
                 :error nil}
          hiccup (root-rc/Root props)
          html (rs/render hiccup)]
      (is (str/includes? html "Workforce Chart"))
      (is (str/includes? html "Alice Smith")))))

(deftest dynamic-router-union-query-test
  (testing "MainRouter metadata defines union query over all target components"
    (let [m (meta root-rc/MainRouter)
          query (:query m)
          target-map (:target-map m)
          route-segment-map (:route-segment-map m)]
      (is (= :main-router (:router-id m)))
      (is (map? (first query)))
      (is (contains? (first query) :router/current-route))
      (is (contains? target-map :org-chart/root))
      (is (contains? target-map :login/root))
      (is (= ["org-chart"] (first (filter #(= % ["org-chart"]) (keys route-segment-map)))))))

  (testing "MainRouter renders active target view when routed"
    (let [router-props {:router/current-route {:login/root {:identifier "alice@acme.com"
                                                                      :password ""
                                                                      :error-msg nil
                                                                      :mfa-required false}}}
          hiccup (root-rc/MainRouter router-props)
          html (rs/render hiccup)]
      (is (str/includes? html "Sign in to your account"))
      (is (str/includes? html "alice@acme.com")))))

(deftest ^:async statechart-logout-test
  (testing "sending :event/logout triggers server-logout-fn and transitions to unauthenticated"
    (cljs.test/async done
      (let [app-inst (app/fulcro-app {})
            logged-out? (atom false)
            redirected? (atom false)]
        (scf/install-fulcro-statecharts! app-inst
          {:extra-env {:server-logout-fn (fn [] (reset! logged-out? true))
                       :clear-tokens-fn (constantly nil)
                       :redirect-fn (fn [target _] (when (= target "/login") (reset! redirected? true)))}})
        (scf/register-statechart! app-inst auth-sc/machine-id auth-sc/auth-routing-chart)
        (scf/start! app-inst {:machine auth-sc/machine-id
                              :session-id auth-sc/default-session-id})
        (scf/send! app-inst auth-sc/default-session-id :event/token-valid {:path "/org-chart"})
        (scf/send! app-inst auth-sc/default-session-id :event/logout)
        (js/setTimeout
          (fn []
            (is (true? @logged-out?) "server-logout-fn must be invoked on logout")
            (is (true? @redirected?) "user must be redirected to /login on logout")
            (done))
          50)))))


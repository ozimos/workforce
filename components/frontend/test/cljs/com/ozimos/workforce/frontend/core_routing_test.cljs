(ns com.ozimos.workforce.frontend.core-routing-test
  "Unit tests for routing, auth-guards, and org-chart rendering in Pure Replicant frontend core."
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.statecharts.integration.fulcro :as scf]
   [com.ozimos.workforce.frontend.auth-statechart :as auth-sc]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.core :as core]
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

(deftest statechart-logout-test
  (testing "sending :event/logout triggers server-logout-fn and transitions to unauthenticated"
    (let [app-inst (app/fulcro-app {})
          logged-out? (atom false)
          redirected? (atom false)]
      (scf/install-fulcro-statecharts! app-inst
        {:event-loop? false
         :extra-env {:server-logout-fn (fn [] (reset! logged-out? true))
                     :clear-tokens-fn (constantly nil)
                     :clear-form-fn (constantly nil)
                     :redirect-fn (fn [target _] (when (= target "/login") (reset! redirected? true)))}})
      (scf/register-statechart! app-inst auth-sc/machine-id auth-sc/auth-routing-chart)
      (scf/start! app-inst {:machine auth-sc/machine-id
                            :session-id auth-sc/default-session-id})
      (scf/send! app-inst auth-sc/default-session-id :event/token-valid {:path "/org-chart"})
      (scf/process-events! app-inst)
      (is (some #{:state/authenticated}
                (scf/current-configuration app-inst auth-sc/default-session-id)))
      (scf/send! app-inst auth-sc/default-session-id :event/logout)
      (scf/process-events! app-inst)
      (is (true? @logged-out?) "server-logout-fn must be invoked on logout")
      (is (true? @redirected?) "user must be redirected to /login on logout")
      (is (some #{:state/unauthenticated}
                (scf/current-configuration app-inst auth-sc/default-session-id))))))

(deftest statechart-logout-clears-form-test
  (testing "unauthenticated on-entry clears sensitive login form fields and normalized ident"
    (let [app-inst (app/fulcro-app {})
          state-atom (::app/state-atom app-inst)
          _ (swap! state-atom assoc
                   :identifier "alice@acme.com" :password "P@ssword123"
                   :error-msg "bad" :mfa-required true :mfa-token "tok" :mfa-code "123456"
                   :login/root {:identifier "alice@acme.com" :password "P@ssword123"})
          _ (swap! state-atom assoc-in [:login/root :main] {:identifier "alice@acme.com" :password "P@ssword123"})
          cleared-form? (atom false)
          redirected-to (atom nil)
          redirected-return-to (atom ::not-set)]
      (scf/install-fulcro-statecharts! app-inst
        {:event-loop? false
         :extra-env {:server-logout-fn (constantly nil)
                     :clear-tokens-fn (constantly nil)
                     :clear-form-fn (fn []
                                      (reset! cleared-form? true)
                                      (swap! state-atom
                                             (fn [db]
                                               (-> db
                                                   (dissoc :identifier :password :error-msg :mfa-required :mfa-token :mfa-code)
                                                   (assoc-in [:login/root :main] {})))))
                     :redirect-fn (fn [target return-to]
                                    (reset! redirected-to target)
                                    (reset! redirected-return-to return-to))
                     :sync-route-fn (constantly nil)
                     :fetch-session-fn (constantly nil)
                     :fetch-page-data-fn (constantly nil)}})
      (scf/register-statechart! app-inst auth-sc/machine-id auth-sc/auth-routing-chart)
      (scf/start! app-inst {:machine auth-sc/machine-id
                            :session-id auth-sc/default-session-id})
      (scf/send! app-inst auth-sc/default-session-id :event/token-valid {:path "/"})
      (scf/process-events! app-inst)
      ;; sanity: form populated before logout
      (is (= "alice@acme.com" (:identifier @state-atom)))
      (is (= "P@ssword123" (:password @state-atom)))
      (scf/send! app-inst auth-sc/default-session-id :event/logout)
      (scf/process-events! app-inst)
      (is (true? @cleared-form?) "clear-form-fn must be invoked on unauthenticated entry (logout)")
      (is (nil? (:identifier @state-atom)) "identifier must be cleared after logout")
      (is (nil? (:password @state-atom)) "password must be cleared after logout")
      (is (nil? (:error-msg @state-atom)))
      (is (nil? (:mfa-required @state-atom)))
      (let [login-ident (get-in @state-atom [:login/root :main])]
        (is (nil? (:identifier login-ident)) "normalized login ident identifier must be cleared")
        (is (nil? (:password login-ident)) "normalized login ident password must be cleared"))
      (is (= "/login" @redirected-to) "must redirect to /login on logout")
      (is (nil? @redirected-return-to) "explicit logout must not set return-to (Option B)"))))

(deftest statechart-logout-redirect-owns-return-to-test
  (testing "guard redirect preserves return-to, explicit logout does not"
    ;; Guard case: unauthenticated navigate to protected
    (let [app-inst (app/fulcro-app {})
          redirected-return-to (atom ::not-set)]
      (scf/install-fulcro-statecharts! app-inst
        {:event-loop? false
         :extra-env {:server-logout-fn (constantly nil)
                     :clear-tokens-fn (constantly nil)
                     :clear-form-fn (constantly nil)
                     :redirect-fn (fn [_ return-to] (reset! redirected-return-to return-to))
                     :sync-route-fn (constantly nil)
                     :fetch-session-fn (constantly nil)
                     :fetch-page-data-fn (constantly nil)}})
      (scf/register-statechart! app-inst auth-sc/machine-id auth-sc/auth-routing-chart)
      (scf/start! app-inst {:machine auth-sc/machine-id
                            :session-id auth-sc/default-session-id})
      (scf/send! app-inst auth-sc/default-session-id :event/no-token {:path "/"})
      (scf/process-events! app-inst)
      (scf/send! app-inst auth-sc/default-session-id :event/navigate {:path "/org-chart"})
      (scf/process-events! app-inst)
      (is (= "/org-chart" @redirected-return-to) "guard must preserve attempted protected path as return-to"))
    ;; Explicit logout case: authenticated -> unauthenticated must NOT set return-to (Option B)
    ;; even when the user was active on a protected path.
    (let [app-inst2 (app/fulcro-app {})
          redirected-return-to2 (atom ::not-set)]
      (scf/install-fulcro-statecharts! app-inst2
        {:event-loop? false
         :extra-env {:current-path "/org-chart"
                     :server-logout-fn (constantly nil)
                     :clear-tokens-fn (constantly nil)
                     :clear-form-fn (constantly nil)
                     :redirect-fn (fn [_ return-to] (reset! redirected-return-to2 return-to))
                     :sync-route-fn (constantly nil)
                     :fetch-session-fn (constantly nil)
                     :fetch-page-data-fn (constantly nil)}})
      (scf/register-statechart! app-inst2 auth-sc/machine-id auth-sc/auth-routing-chart)
      (scf/start! app-inst2 {:machine auth-sc/machine-id
                             :session-id auth-sc/default-session-id})
      (scf/send! app-inst2 auth-sc/default-session-id :event/token-valid {:path "/org-chart"})
      (scf/process-events! app-inst2)
      (is (some #{:state/authenticated} (scf/current-configuration app-inst2 auth-sc/default-session-id)))
      (scf/send! app-inst2 auth-sc/default-session-id :event/logout {:logout? true})
      (scf/process-events! app-inst2)
      (is (nil? @redirected-return-to2) "logout must redirect with nil return-to even from protected path"))))

(deftest statechart-expired-token-inactivity-roundtrip-test
  (testing "expired token after prolonged inactivity redirects to /login with return-to and restores page A after login"
    (let [app-inst (app/fulcro-app {})
          state-atom (::app/state-atom app-inst)
          redirected-to (atom nil)
          synced-route (atom nil)]
      (scf/install-fulcro-statecharts! app-inst
        {:event-loop? false
         :extra-env {:current-path "/org-chart"
                     :server-logout-fn (constantly nil)
                     :clear-tokens-fn (constantly nil)
                     :clear-form-fn (constantly nil)
                     :redirect-fn (fn [target return-to]
                                    (reset! redirected-to target)
                                    (if return-to
                                      (swap! state-atom assoc :auth/return-to return-to)
                                      (swap! state-atom dissoc :auth/return-to)))
                     :sync-route-fn (fn [path _] (reset! synced-route path))
                     :fetch-session-fn (constantly nil)
                     :fetch-page-data-fn (constantly nil)}})
      (scf/register-statechart! app-inst auth-sc/machine-id auth-sc/auth-routing-chart)
      (scf/start! app-inst {:machine auth-sc/machine-id
                            :session-id auth-sc/default-session-id})
      ;; User was active on Page A (/org-chart)
      (scf/send! app-inst auth-sc/default-session-id :event/token-valid {:path "/org-chart"})
      (scf/process-events! app-inst)
      (is (some #{:state/authenticated} (scf/current-configuration app-inst auth-sc/default-session-id)))

      ;; Prolonged inactivity: token expired, backend returns 401, triggering :event/auth-failure
      (scf/send! app-inst auth-sc/default-session-id :event/auth-failure)
      (scf/process-events! app-inst)

      ;; Assert redirected to /login and return-to is Page A (/org-chart)
      (is (= "/login" @redirected-to) "expired token must redirect to /login")
      (is (= "/org-chart" (:auth/return-to @state-atom)) "Page A path must be preserved in :auth/return-to")
      (is (some #{:state/unauthenticated} (scf/current-configuration app-inst auth-sc/default-session-id)))

      ;; Successful login on /login restores return-to
      (let [return-to (:auth/return-to @state-atom)]
        (swap! state-atom dissoc :auth/return-to)
        (scf/send! app-inst auth-sc/default-session-id :event/login-success {:return-to return-to}))
      (scf/process-events! app-inst)

      ;; Assert user returned to Page A (/org-chart) and is authenticated
      (is (= "/org-chart" @synced-route) "user must be returned to Page A (/org-chart) after login")
      (is (some #{:state/authenticated} (scf/current-configuration app-inst auth-sc/default-session-id))))))

(deftest workforce-chart-button-events-test
  (testing "Replicant event dispatch invokes expand-or-fetch and set-custom-root correctly"
    (let [app-inst core/app-inst
          state-atom (::app/state-atom app-inst)
          dispatcher (bridge/dispatch! app-inst core/event-handlers)
          mock-event-map {:replicant/trigger :replicant.trigger/dom-event}]
      ;; 1. Test set-custom-root
      (swap! state-atom assoc :custom-root-id nil :active-chart-tab :tab/full-org)
      (dispatcher mock-event-map
                 [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-custom-root {:id "emp-bob"}])
      (is (= "emp-bob" (:custom-root-id @state-atom))
          "Set as Root button dispatch must update :custom-root-id in app state")
      (is (= :tab/my-org (:active-chart-tab @state-atom))
          "Set as Root button dispatch must switch active tab to :tab/my-org")

      ;; 2. Test expand-or-fetch toggle
      (swap! state-atom assoc :collapsed-workforce #{"emp-bob"})
      (dispatcher mock-event-map
                 [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-or-fetch {:id "emp-bob" :all-loaded? true}])
      (is (not (contains? (:collapsed-workforce @state-atom) "emp-bob"))
          "Expand button dispatch must remove node from :collapsed-workforce")

      ;; 3. Test collapse
      (dispatcher mock-event-map
                 [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-or-fetch {:id "emp-bob" :all-loaded? true}])
      (is (contains? (:collapsed-workforce @state-atom) "emp-bob")
          "Collapse button dispatch must add node back to :collapsed-workforce")

      ;; 4. Test expand-all and collapse-all
      (swap! state-atom assoc :workforce-hierarchy {"emp-root" ["emp-a" "emp-b"] "emp-a" [] "emp-b" []})
      (dispatcher mock-event-map
                 [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/collapse-all {}])
      (is (= #{"emp-root" "emp-a" "emp-b"} (:collapsed-workforce @state-atom))
          "Collapse all dispatch must collapse all parent nodes")
      (dispatcher mock-event-map
                 [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-all {}])
      (is (= #{} (:collapsed-workforce @state-atom))
          "Expand all dispatch must clear collapsed nodes"))))

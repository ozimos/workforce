(ns com.ozimos.workforce.frontend.core
  (:require
   [clojure.string :as str]
   [com.fulcrologic.devtools.chrome.target :as chrome-devtools]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.fulcrologic.statecharts.integration.fulcro :as scf]
   [com.ozimos.workforce.frontend.abac :as abac]
   [com.ozimos.workforce.frontend.auth-statechart :as auth-sc]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.routing :as routing]
   [com.ozimos.workforce.frontend.transit :as transit]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant :as nav]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant :as create-org]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant :as dept-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant :as forgot-password]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant :as headcount]
   [com.ozimos.workforce.frontend.ui.pages.join-org-replicant :as join-org]
   [com.ozimos.workforce.frontend.ui.pages.login-replicant :as login]
   [com.ozimos.workforce.frontend.views.org-chart :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant :as policy-settings]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant :as profile]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant :as register]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant :as reset-password]
   [com.ozimos.workforce.frontend.ui.pages.workforce-chart :as workforce-chart]
   [com.ozimos.workforce.frontend.ui.root-replicant :as root-rc]
   [fulcro.inspect.tool :as inspect]
   [goog.dom :as gdom]
   [replicant.dom :as r]))

;; -----------------------------------------------------------------------------
;; Headless Fulcro Application
;; Normalized DB, EQL queries, and defmutations with ZERO React DOM instances.
;; -----------------------------------------------------------------------------

(defonce app-inst (app/fulcro-app {}))

(defn- is-logged-in? []
  (and (exists? js/localStorage)
       (some? (.getItem js/localStorage "access-token"))))

(defn- current-path-route
  "Delegates to routing/path->route (SSOT) but preserves \"/\" auth-sensitive semantics.
   Keep in sync with routing/path->route."
  ([] (current-path-route (if (and (exists? js/window) (exists? js/window.location)) js/window.location.pathname "/")))
  ([path]
   (let [p (or path "/")
         pathname (first (str/split p #"\?" 2))]
     (if (= pathname "/")
       (if (is-logged-in?) :route/home :route/login)
       (routing/path->route p)))))

(declare fetch-org-chart! fetch-workforce-chart! fetch-user-session!)

(defn- route->target-ident
  "Maps a route keyword to the normalized Fulcro App DB ident for the page."
  [route]
  (case route
    (:route/login :route/login-replicant)                     [:login-replicant/root :main]
    (:route/register :route/register-replicant)               [:register-replicant/root :main]
    (:route/create-org :route/create-org-replicant)           [:create-org-replicant/root :main]
    (:route/join-org :route/join-org-replicant)               [:join-org-replicant/root :main]
    (:route/org-dashboard :route/org-dashboard-replicant)     [:org-dashboard-replicant/root :main]
    (:route/org-chart :route/org-chart-replicant)             [:workforce-chart/root :main]
    (:route/org-chart-2 :route/org-chart-2-replicant)         [:org-chart-replicant/root :main]
    (:route/dept-dashboard :route/dept-dashboard-replicant)   [:dept-dashboard-replicant/root :main]
    (:route/headcount :route/headcount-replicant)             [:headcount-replicant/root :main]
    (:route/policies :route/policies-replicant)               [:policy-settings-replicant/root :main]
    (:route/profile :route/profile-replicant)                 [:profile-replicant/root :main]
    (:route/forgot-password :route/forgot-password-replicant) [:forgot-password-replicant/root :main]
    (:route/reset-password :route/reset-password-replicant)   [:reset-password-replicant/root :main]
    (:route/verify :route/verify-replicant)                   [:verify-replicant/root :main]
    (:route/home :route/home-replicant)                       [:home-replicant/root :main]
    [:login-replicant/root :main]))

(defn- sync-route-state!
  "Pure helper to update both flat :route and normalized Dynamic Router ident in App DB."
  [state-atom route logged-in?]
  (let [target-ident (route->target-ident route)
        [ident-table ident-id] target-ident]
    (swap! state-atom
           (fn [db]
             (-> db
                 (assoc :route route :logged-in? logged-in?)
                 ;; Update normalized router table in Fulcro App DB
                 (assoc-in [:root-router/by-id :main-router]
                           {:router/id :main-router
                            :router/current-route target-ident})
                 ;; Ensure target ident table entry exists with current DB state
                 (assoc-in [ident-table ident-id] (select-keys db [:identifier :password :error-msg :loading :success
                                                                   :email :confirm-password :field-errors :name
                                                                   :active-org :units :hierarchy :search-term
                                                                   :collapsed-nodes :pending-approvals :submitting
                                                                   :permissions :rules :new-username :mfa-stage
                                                                   :mfa-secret :mfa-backup-codes :totp-code
                                                                   :unit-id :dashboard :available-units
                                                                   :invitations :members :invite-email :invite-role
                                                                   :status :message :people :people-hierarchy
                                                                   :people-search :collapsed-people]))
                 ;; Set top-level router pointer
                 (assoc :root/router [:root-router/by-id :main-router]))))))

(defn- clear-stored-tokens! []
  (when (exists? js/localStorage)
    (.removeItem js/localStorage "access-token")
    (.removeItem js/localStorage "refresh-token")
    (.removeItem js/localStorage "user-info")
    (.removeItem js/localStorage "username")
    (.removeItem js/localStorage "email")))

(defn- handle-statechart-redirect! [target-path return-to-path]
  (let [state-atom (::app/state-atom app-inst)]
    (when return-to-path
      (swap! state-atom assoc :auth/return-to return-to-path))
    (when (and (exists? js/window) (exists? js/window.history))
      (.replaceState js/window.history nil "" target-path))
    (let [route (current-path-route target-path)]
      (sync-route-state! state-atom route false))))

(defn- handle-statechart-sync-route! [path logged-in?]
  (let [state-atom (::app/state-atom app-inst)
        route (current-path-route path)
        ;; Ensure browser URL matches the synced route — fixes login-success
        ;; return-to case where DB was updated but history still at /login.
        ;; Path may contain query string (e.g. /dept-dashboard?unit-id=123).
        full-path (or path "/")
        current-url (when (and (exists? js/window) (exists? js/window.location))
                      (str js/window.location.pathname js/window.location.search))]
    (when (and (exists? js/window) (exists? js/window.history)
               (not= current-url full-path))
      ;; Use replaceState for sync (idempotent, no extra history entry).
      ;; Runtime navigate! already did push/replace, so this is no-op in that case.
      (.replaceState js/window.history nil "" full-path))
    (sync-route-state! state-atom route logged-in?)))

(defn- handle-statechart-fetch-page-data! [path]
  (when (is-logged-in?)
    (let [route (current-path-route path)
          state-atom (::app/state-atom app-inst)]
      (if (:active-org @state-atom)
        (cond
          (#{:route/org-chart :route/org-chart-replicant} route)
          (fetch-workforce-chart!)

          (#{:route/org-chart-2 :route/org-chart-2-replicant
             :route/dept-dashboard :route/dept-dashboard-replicant} route)
          (fetch-org-chart!))
        (fetch-user-session!)))))

(defn- navigate!
   ([path] (navigate! path nil))
   ([path return-to]
    (when (and (exists? js/window) (exists? js/window.history))
      (if return-to
        (.replaceState js/window.history nil "" path)
        (.pushState js/window.history nil "" path)))
    (scf/send! app-inst auth-sc/default-session-id :event/navigate {:path path :return-to return-to})))

(defn navigate
  "Public SPA navigation: push the path onto history and route in-app
   without a full page reload."
  [path]
  (navigate! path))

(defn load-rc!
  "Pure headless data loader: sends a component's EQL query to `/api/query`
   and automatically merges and normalizes the response into the Fulcro App DB."
  [component ident-or-query & [{:keys [on-success on-error]}]]
  (when (is-logged-in?)
    (let [state-atom (::app/state-atom app-inst)
          eql-query (cond
                      (vector? ident-or-query) ident-or-query
                      (map? ident-or-query) [ident-or-query]
                      :else [{ident-or-query (:query (meta component))}])]
      (swap! state-atom assoc :loading true :error nil)
      (-> (transit/fetch-transit "/api/query" eql-query)
          (.then (fn [{:keys [body status]}]
                   (if (and status (>= status 400))
                     (do
                       (swap! state-atom assoc :loading false :error (str "Request failed with status " status))
                       (when on-error (on-error body)))
                     (do
                       (swap! state-atom assoc :loading false)
                       (when on-success (on-success body))))))
          (.catch (fn [err]
                    (swap! state-atom assoc :loading false :error (str err))
                    (when on-error (on-error err))))))))

(defn- fetch-org-chart! []
  (when (is-logged-in?)
    (let [state-atom (::app/state-atom app-inst)
          active-org (:active-org @state-atom)]
      (when-let [org-id (:org/id active-org)]
        (load-rc! org-chart/OrgChartReplicant
                  [{[:org/id org-id]
                    [{:org/chart [:org/id :org/hierarchy
                                  {:org/units [:unit/id :unit/name :unit/division-id
                                               :unit/dept-id :unit/parent-id :unit/budget
                                               :unit/filled :unit/open :unit/pending
                                               :unit/actors :unit/children]}]}]}]
                  {:on-success
                   (fn [body]
                     (let [chart (or (get-in body [[:org/id org-id] :org/chart])
                                     (some (fn [[k v]] (when (and (vector? k) (= :org/id (first k))) (:org/chart v))) body))
                           unit-list (:org/units chart)
                           ;; Normalize entities into distinct dimensional tables
                           div-table (into {}
                                           (keep (fn [u]
                                                   (let [div-id (:unit/division-id u)]
                                                     (when (seq div-id)
                                                       [div-id {:division/id div-id
                                                                :division/name (if (nil? (:unit/parent-id u))
                                                                                 (:unit/name u)
                                                                                 div-id)}]))))
                                           unit-list)
                           dept-table (into {}
                                            (keep (fn [u]
                                                    (let [dept-id (:unit/dept-id u)]
                                                      (when (and (seq dept-id) (not= dept-id "ALL"))
                                                        [dept-id {:dept/id dept-id
                                                                  :dept/name (if (some? (:unit/parent-id u))
                                                                               (:unit/name u)
                                                                               dept-id)}]))))
                                            unit-list)
                           unit-table (into {}
                                            (map (fn [u]
                                                   [(:unit/id u)
                                                    (assoc u
                                                           :unit/division (when-let [div-id (:unit/division-id u)]
                                                                            [:division/id div-id])
                                                           :unit/dept (when-let [dept-id (:unit/dept-id u)]
                                                                        (when (not= dept-id "ALL")
                                                                          [:dept/id dept-id])))]))
                                            unit-list)
                           hier (or (:org/hierarchy chart)
                                    (reduce (fn [acc u]
                                              (let [p (:unit/parent-id u)]
                                                (update acc p (fnil conj []) (:unit/id u))))
                                            {}
                                            unit-list))]
                       (swap! state-atom
                              (fn [db]
                                (-> db
                                    (assoc :units unit-table
                                           :hierarchy hier
                                           :loading false)
                                    (update :division/id merge div-table)
                                    (update :dept/id merge dept-table)
                                    (update :unit/id merge unit-table))))))})))))

(defn- fetch-workforce-branch! [manager-id]
  (let [state-atom (::app/state-atom app-inst)
        org-id (get-in @state-atom [:active-org :org/id])
        mutation-expr [(list 'org/fetch-workforce-branch {:org/id org-id :manager/id manager-id})]]
    (swap! state-atom update :loading-branches (fnil conj #{}) manager-id)
    (-> (transit/fetch-transit "/api/query" mutation-expr)
        (.then (fn [{:keys [body status]}]
                 (if (and status (>= status 400))
                   (swap! state-atom (fn [s] (-> s (update :loading-branches disj manager-id)
                                               (assoc :error "Failed to load direct reports"))))
                   (let [res (or (get body 'org/fetch-workforce-branch)
                                 (first (vals body)))
                         workforce-list (:workforce/list res)
                         branch-hier (:workforce-hierarchy res)
                         hcs-list (:headcounts/list res)
                         hcs-by-mgr (:headcounts-by-manager res)
                         person-table (into {} (map (fn [p] [(:person/id p) p])) workforce-list)
                         hc-table (into {} (map (fn [h] [(:headcount/id h) h])) hcs-list)]
                     (swap! state-atom
                            (fn [s]
                              (-> s
                                  (update :loading-branches disj manager-id)
                                  (update :collapsed-workforce disj manager-id)
                                  (assoc-in [:person/id] (merge (get s :person/id {}) person-table))
                                  (assoc-in [:headcount/id] (merge (get s :headcount/id {}) hc-table))
                                  (update :workforce merge person-table)
                                  (update :workforce-hierarchy merge branch-hier)
                                  (update :headcounts-by-manager merge hcs-by-mgr))))))))
        (.catch (fn [err]
                  (swap! state-atom (fn [s] (-> s (update :loading-branches disj manager-id)
                                              (assoc :error (str err))))))))))

(defonce ^:private search-debounce-timer (atom nil))

(defn- search-workforce! [term]
  (let [state-atom (::app/state-atom app-inst)
        org-id (get-in @state-atom [:active-org :org/id])
        term-clean (some-> term str/trim)]
    (when @search-debounce-timer
      (js/clearTimeout @search-debounce-timer))
    (if (empty? term-clean)
      (swap! state-atom assoc :server-search-results nil :searching? false)
      (reset! search-debounce-timer
              (js/setTimeout
                (fn []
                  (swap! state-atom assoc :searching? true)
                  (-> (transit/fetch-transit "/api/query" [(list 'org/search-workforce {:org/id org-id :term term-clean})])
                      (.then (fn [{:keys [body]}]
                               (let [res (or (get body 'org/search-workforce) (first (vals body)))
                                     results (:results res)]
                                 (swap! state-atom assoc :server-search-results results :searching? false))))
                      (.catch (fn [_]
                                (swap! state-atom assoc :searching? false)))))
                250)))))

(defn- select-search-result! [result]
  (let [state-atom (::app/state-atom app-inst)
        path (:person/ancestor-path result)
        ancestor-mgrs (vec (butlast path))]
    (swap! state-atom (fn [s]
                        (-> s
                            (update :collapsed-workforce (fn [c] (apply disj (or c #{}) (or ancestor-mgrs []))))
                            (assoc :workforce-search (:person/name result)
                                   :server-search-results nil))))
    (doseq [mgr-id ancestor-mgrs]
      (let [s @state-atom
            children (get-in s [:workforce-hierarchy mgr-id] [])
            loaded? (every? #(contains? (:workforce s) %) children)]
        (when-not loaded?
          (fetch-workforce-branch! mgr-id))))))

(defn- fetch-workforce-chart! []
  (when (is-logged-in?)
    (let [state-atom (::app/state-atom app-inst)
          active-org (:active-org @state-atom)]
      (when-let [org-id (:org/id active-org)]
        (load-rc! workforce-chart/WorkforceChart
                  [{[:org/id org-id]
                    [{:org/workforce-chart [:org/id
                                            :workforce/list
                                            :workforce-hierarchy
                                            :headcounts/list
                                            :headcounts-by-manager
                                            :org/chart-settings
                                            :total-workforce-count
                                            :total-headcount-count]}]}]
                  {:on-success
                   (fn [body]
                     (let [chart (or (get-in body [[:org/id org-id] :org/workforce-chart])
                                     (some (fn [[k v]] (when (and (vector? k) (= :org/id (first k))) (:org/workforce-chart v))) body))
                           workforce-list (:workforce/list chart)
                           hierarchy (:workforce-hierarchy chart)
                           headcounts-list (:headcounts/list chart)
                           headcounts-by-mgr (:headcounts-by-manager chart)
                           chart-settings (:org/chart-settings chart)
                           total-wf-count (:total-workforce-count chart)
                           saved-custom-root (when (exists? js/localStorage)
                                               (.getItem js/localStorage (str "workforce-custom-root:" org-id)))

                           ;; Query-Driven DB Normalization:
                           person-table (into {}
                                              (map (fn [p] [(:person/id p) p]))
                                              workforce-list)

                           headcount-table (into {}
                                                 (map (fn [h] [(:headcount/id h) h]))
                                                 headcounts-list)

                           ;; Compute initial collapsed nodes:
                           root-id (first (get hierarchy nil []))
                           all-managers (set (keys (dissoc hierarchy nil)))
                           initial-collapsed (disj all-managers root-id)]

                       (swap! state-atom
                              (fn [s]
                                (-> s
                                    (assoc-in [:person/id] (merge (get s :person/id {}) person-table))
                                    (assoc-in [:headcount/id] (merge (get s :headcount/id {}) headcount-table))
                                    (assoc :workforce person-table
                                           :workforce-hierarchy hierarchy
                                           :headcounts-by-manager headcounts-by-mgr
                                           :org/chart-settings chart-settings
                                           :total-workforce-count total-wf-count
                                           :loading-branches #{}
                                           :server-search-results nil
                                           :custom-root-id (or saved-custom-root (:custom-root-id s))
                                           :active-chart-tab (or (:active-chart-tab s) :tab/full-org)
                                           :collapsed-workforce initial-collapsed
                                           :loading false))))))})))))

(defn- fetch-user-session! []
  (when (is-logged-in?)
    (load-rc! nil
              [:current-user/email :current-user/username :current-user/verified
               {:user/active-org [:org/id :org/name :org/role]}
               {:user/orgs [:org/id :org/name]}]
              {:on-success
               (fn [data]
                 (let [active-org (:user/active-org data)
                       orgs (:user/orgs data)
                       org-table (into {}
                                       (keep (fn [org]
                                               (when-let [oid (:org/id org)]
                                                 [oid org])))
                                       (concat (when active-org [active-org]) (or orgs [])))
                       state-atom (::app/state-atom app-inst)]
                   (when-let [e (:current-user/email data)]
                     (.setItem js/localStorage "email" e))
                   (when-let [u (:current-user/username data)]
                     (.setItem js/localStorage "username" u))
                   (swap! state-atom
                          (fn [db]
                            (-> db
                                (assoc :active-org active-org
                                       :orgs (or orgs [])
                                       :current-user/email (:current-user/email data)
                                       :current-user/username (:current-user/username data)
                                       :abac/policy (let [p (:user/abac-policy data)]
                                                      (if (abac/policy-active? p) p {})))
                                (update :org/id merge org-table))))
                   (let [curr-route (:route @state-atom)]
                     (if (#{:route/org-chart :route/org-chart-replicant} curr-route)
                       (fetch-workforce-chart!)
                       (fetch-org-chart!)))))})))

(defn- handle-login-submit! []
  (let [state-atom (::app/state-atom app-inst)
        {:keys [identifier password]} @state-atom]
    (swap! state-atom assoc :error-msg nil)
    (-> (json/fetch-json "/api/auth/login" "POST" {:identifier identifier :password password})
        (.then (fn [{:keys [status body]}]
                 (cond
                   (and (= 200 status) (:mfa-required body))
                   (swap! state-atom login/set-mfa-required-state (:mfa-token body))

                   (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (when-let [u (:user body)]
                       (when (:email u) (.setItem js/localStorage "email" (:email u)))
                       (when (:username u) (.setItem js/localStorage "username" (:username u))))
                     ;; Let the statechart handle redirect & session loading.
                     ;; Pass :return-to so it navigates back to the originally requested URL.
                     (let [return-to (:auth/return-to @state-atom)]
                       (swap! state-atom dissoc :auth/return-to)
                       (scf/send! app-inst auth-sc/default-session-id :event/login-success {:return-to return-to})))

                   :else
                   (swap! state-atom login/set-error-msg-state
                          (or (-> body :errors :credentials first)
                              "Invalid email/username or password"))))))))

(defn- handle-mfa-submit! []
  (let [state-atom (::app/state-atom app-inst)
        {:keys [mfa-token mfa-code]} @state-atom]
    (swap! state-atom assoc :error-msg nil)
    (-> (json/fetch-json "/api/auth/mfa/login" "POST" {:mfa-token mfa-token :code mfa-code})
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (.setItem js/localStorage "mfa-enabled" "true")
                     (let [return-to (:auth/return-to @state-atom)]
                       (swap! state-atom dissoc :auth/return-to)
                       (scf/send! app-inst auth-sc/default-session-id :event/login-success {:return-to return-to})))
                   (swap! state-atom login/set-error-msg-state
                          (or (-> body :errors :code first) "Invalid 2FA code"))))))))

(defn- handle-register-submit! []
  (let [state-atom (::app/state-atom app-inst)
        {:keys [email password confirm-password]} @state-atom]
    (if (not= password confirm-password)
      (swap! state-atom register/set-field-errors-state {:confirm-password "Passwords do not match"})
      (-> (json/fetch-json "/api/auth/register" "POST" {:email email :password password})
          (.then (fn [{:keys [status body]}]
                   (if (= 201 status)
                     (do
                       (when (exists? js/localStorage)
                         (when-let [at (:access-token body)] (.setItem js/localStorage "access-token" at))
                         (when-let [rt (:refresh-token body)] (.setItem js/localStorage "refresh-token" rt))
                         (when-let [u (get-in body [:user :username])] (.setItem js/localStorage "username" u))
                         (when-let [e (get-in body [:user :email])] (.setItem js/localStorage "email" e)))
                       (swap! state-atom register/set-success-state (get-in body [:user :username]))
                       (when (exists? js/window.history)
                         (.pushState js/window.history nil "" "/"))
                       (sync-route-state! state-atom :route/home true)
                       (fetch-user-session!))
                     (let [err-map (or (get-in body [:errors :errors]) (:errors body) {})
                           field-errs (into {} (filter (comp some? val)
                                                 {:email    (first (:email err-map))
                                                  :password (first (:password err-map))
                                                  :username (first (:username err-map))}))]
                       (swap! state-atom register/set-field-errors-state field-errs)
                       (when (empty? field-errs)
                         (swap! state-atom register/set-error-msg-state "Registration failed"))))))))))

(defn- handle-create-org-submit! []
  (let [state-atom (::app/state-atom app-inst)
        org-name   (:name @state-atom)]
    (swap! state-atom create-org/set-loading-state true)
    (let [query [(list 'org/create {:org/name org-name})]]
      (-> (transit/fetch-transit "/api/query" query)
          (.then (fn [{:keys [body]}]
                   (let [org-data (get body 'org/create)]
                     (if (or (:org/errors org-data) (get body :errors))
                       (swap! state-atom create-org/set-error-msg-state
                              (or (-> org-data :org/errors :name first)
                                  (-> body :errors :auth first)
                                  "Failed to create organization"))
                       (do
                         (swap! state-atom create-org/set-success-state (:org/name org-data))
                         (fetch-user-session!))))))
          (.catch (fn [_]
                    (swap! state-atom create-org/set-error-msg-state "Network error")))))))

;; -----------------------------------------------------------------------------
;; Global Replicant Event Dispatcher -> Fulcro Mutations & State Updates
;; -----------------------------------------------------------------------------

(def event-handlers
  {;; Global Generic Navigation
   :navigate
   (fn [_ path] (navigate! path))

   ;; Navigation Bar Mutations
   ::nav/toggle-dropdown
   (fn [_] (comp/transact! app-inst [(nav/toggle-dropdown {})]))
   ::nav/switch-org
   (fn [_ org-id]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :dropdown-open false)
       (-> (transit/fetch-transit "/api/query" [(list 'org/switch {:org/id org-id})])
           (.then (fn []
                    (fetch-user-session!))))))
   ::nav/logout
   (fn [_]
     (scf/send! app-inst auth-sc/default-session-id :event/logout))

   ;; Org Chart Mutations
   ::org-chart/toggle-collapse
   (fn [_ id] (comp/transact! app-inst [(org-chart/toggle-collapse {:id id})]))
   ::org-chart/expand-all
   (fn [_] (comp/transact! app-inst [(org-chart/expand-all {})]))
   ::org-chart/collapse-all
   (fn [_] (comp/transact! app-inst [(org-chart/collapse-all {})]))
   ::org-chart/set-search-term
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (comp/transact! app-inst [(org-chart/set-search-term {:value v})])))
   ::org-chart/refresh
   (fn [_] (fetch-org-chart!))

   ;; Workforce Chart Events
   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-search-term
   (fn [data]
     (let [term (if (map? data) (:term data) data)
           state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :workforce-search term)
       (search-workforce! term)))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/select-search-result
   (fn [{:keys [result]}]
     (select-search-result! result))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-or-fetch
   (fn [{:keys [id all-loaded?]}]
     (let [state-atom (::app/state-atom app-inst)
           s @state-atom
           collapsed? (contains? (:collapsed-workforce s) id)]
       (if (not collapsed?)
         (swap! state-atom update :collapsed-workforce conj id)
         (if all-loaded?
           (swap! state-atom update :collapsed-workforce disj id)
           (fetch-workforce-branch! id)))))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/toggle-collapse
   (fn [{:keys [id]}]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom update :collapsed-workforce
              (fn [s]
                (let [curr (or s #{})]
                  (if (contains? curr id)
                    (disj curr id)
                    (conj curr id)))))))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-all
   (fn [_]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :collapsed-workforce #{})))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/collapse-all
   (fn [_]
     (let [state-atom (::app/state-atom app-inst)
           hierarchy (:workforce-hierarchy @state-atom)
           all-parents (set (keys (dissoc hierarchy nil)))]
       (swap! state-atom assoc :collapsed-workforce all-parents)))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/refresh
   (fn [_] (fetch-workforce-chart!))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-active-tab
   (fn [data]
     (let [tab (if (map? data) (:tab data) data)
           state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :active-chart-tab tab)))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-custom-root
   (fn [{:keys [id]}]
     (let [state-atom (::app/state-atom app-inst)
           org-id (get-in @state-atom [:active-org :org/id])]
       (when (exists? js/localStorage)
         (.setItem js/localStorage (str "workforce-custom-root:" org-id) id))
       (swap! state-atom
              (fn [s]
                (-> s
                    (assoc :custom-root-id id
                           :active-chart-tab :tab/my-org)
                    (update :collapsed-workforce disj id))))))

   :com.ozimos.workforce.frontend.ui.pages.workforce-chart/reset-custom-root
   (fn [_]
     (let [state-atom (::app/state-atom app-inst)
           org-id (get-in @state-atom [:active-org :org/id])]
       (when (exists? js/localStorage)
         (.removeItem js/localStorage (str "workforce-custom-root:" org-id)))
       (swap! state-atom assoc :custom-root-id nil)))

   ;; Dept Dashboard
   ::dept-dashboard/set-tab
   (fn [_ tab]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :active-tab tab)))

   ;; Headcount Mutations
   ::headcount/set-form-field
   (fn [ev field]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (comp/transact! app-inst [(headcount/set-form-field {:field field :value v})])))
   ::headcount/set-pending-approvals
   (fn [_ approvals] (comp/transact! app-inst [(headcount/set-pending-approvals {:approvals approvals})]))

   ;; Policy Settings
   ::policy-settings/set-selected-unit
   (fn [_ id]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :selected-unit-id id)))
   ::policy-settings/toggle-editor-modal
   (fn [_]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom update :editor-open? not)))

   ;; Login Form
   ::login/set-identifier
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom login/set-identifier-state v))))
   ::login/set-password
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom login/set-password-state v))))
   ::login/set-mfa-code
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom login/set-mfa-code-state v))))
   ::login/submit-login
   (fn [ev]
     (when-let [js-ev (:replicant/js-event ev)]
       (.preventDefault js-ev))
     (handle-login-submit!))
   ::login/submit-mfa
   (fn [ev]
     (when-let [js-ev (:replicant/js-event ev)]
       (.preventDefault js-ev))
     (handle-mfa-submit!))

   ;; Register Form
   ::register/set-email
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom register/set-email-state v))))
   ::register/set-password
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom register/set-password-state v))))
   ::register/set-confirm-password
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom register/set-confirm-password-state v))))
   ::register/submit
   (fn [ev]
     (when-let [js-ev (:replicant/js-event ev)]
       (.preventDefault js-ev))
     (handle-register-submit!))

   ;; Create Org Form
   ::create-org/set-name
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom create-org/set-name-state v))))
   ::create-org/submit
   (fn [ev]
     (when-let [js-ev (:replicant/js-event ev)]
       (.preventDefault js-ev))
     (handle-create-org-submit!))

   ;; Join Org Form
   ::join-org/accept-invitation
   (fn [_ id]
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom join-org/set-accepting-state id)))

   ;; Forgot Password Form
   ::forgot-password/set-email
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom forgot-password/set-email-state v))))

   ;; Reset Password Form
   ::reset-password/set-password
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom reset-password/set-password-state v))))
   ::reset-password/set-confirm-password
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom reset-password/set-confirm-password-state v))))

   ;; Profile Form
   ::profile/set-new-username
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (comp/transact! app-inst [(profile/set-new-username {:value v})])))})

;; -----------------------------------------------------------------------------
;; Pure Replicant Direct Mount with Fulcro Normalized State
;; -----------------------------------------------------------------------------

(defonce ^:private render-scheduled? (atom false))

(defn render! []
  (when-let [mount-el (when (exists? js/document) (gdom/getElement "app"))]
    (let [state-atom (::app/state-atom app-inst)
          db @state-atom
          query (:query (meta root-rc/RootReplicant))
          denormalized-tree (denorm/db->tree query db db)
          route (or (:route db) (current-path-route))
          [target-ident-key _] (route->target-ident route)
          router-props {:router/current-route {target-ident-key db}}
          enriched-tree (assoc denormalized-tree :root/router router-props)]
      (r/render mount-el (root-rc/RootReplicant enriched-tree)))))

(defn schedule-render! []
  (when-not @render-scheduled?
    (reset! render-scheduled? true)
    (if (exists? js/requestAnimationFrame)
      (js/requestAnimationFrame
        (fn []
          (reset! render-scheduled? false)
          (render!)))
      (do
        (reset! render-scheduled? false)
        (render!)))))

(defn ^:export init []
  (try
    (r/set-dispatch! (bridge/dispatch! app-inst event-handlers))
    (let [state-atom (::app/state-atom app-inst)
          current-path (if (and (exists? js/window) (exists? js/window.location))
                         (str js/window.location.pathname js/window.location.search)
                         "/")
          logged-in? (is-logged-in?)
          verified? (boolean
                      (or (:current-user/verified @state-atom)
                          (when (and (exists? js/localStorage)
                                     (= "true" (.getItem js/localStorage "verified")))
                            true)))
          should-redirect? (and logged-in?
                                (routing/should-redirect-public? current-path verified?)
                                (not= (first (str/split current-path #"\?" 2)) "/"))]

      ;; ------------------------------------------------------------------
      ;; Synchronous boot guard — must run BEFORE render! to prevent
      ;; the protected page from flashing while the statechart event queue
      ;; processes asynchronously (core.async in CLJS is always async).
      ;; ------------------------------------------------------------------
      (if logged-in?
        ;; Authenticated: redirect public (verify only when verified) to home.
        (if should-redirect?
          (do
            (when (and (exists? js/window) (exists? js/window.history))
              (.replaceState js/window.history nil "" "/"))
            (sync-route-state! state-atom :route/home true)
            (fetch-user-session!))
          (do (sync-route-state! state-atom (current-path-route current-path) true)
              (fetch-user-session!)))
        ;; Unauthenticated: if on a protected path redirect to /login now,
        ;; storing return-to so the statechart can restore it after login.
        (if (auth-sc/protected-path? current-path)
          (do
            (swap! state-atom assoc :auth/return-to current-path)
            (when (and (exists? js/window) (exists? js/window.history))
              (.replaceState js/window.history nil "" "/login"))
            (sync-route-state! state-atom :route/login false))
          (sync-route-state! state-atom (current-path-route current-path) false)))

      ;; ------------------------------------------------------------------
      ;; Install the Fulcro Statechart — governs ALL subsequent navigations,
      ;; login/logout, and 401 auth failures.
      ;; ------------------------------------------------------------------
      (scf/install-fulcro-statecharts! app-inst {:extra-env {:current-path current-path
                                                             :clear-tokens-fn clear-stored-tokens!
                                                             :redirect-fn handle-statechart-redirect!
                                                             :sync-route-fn handle-statechart-sync-route!
                                                             :fetch-session-fn fetch-user-session!
                                                             :fetch-page-data-fn handle-statechart-fetch-page-data!
                                                             :verified verified?
                                                             :verified? verified?}})
      (scf/register-statechart! app-inst auth-sc/machine-id auth-sc/auth-routing-chart)
      (scf/start! app-inst {:machine auth-sc/machine-id
                            :session-id auth-sc/default-session-id})
      ;; Inform the statechart of the boot state (it manages future transitions).
      (if logged-in?
        (scf/send! app-inst auth-sc/default-session-id :event/token-valid {:path current-path})
        (scf/send! app-inst auth-sc/default-session-id :event/no-token {:path current-path}))
      ;; Wire 401 -> statechart :event/auth-failure (both transit and json)
      (transit/register-auth-failure-handler!
        (fn [] (scf/send! app-inst auth-sc/default-session-id :event/auth-failure)))
      (json/register-auth-failure-handler!
        (fn [] (scf/send! app-inst auth-sc/default-session-id :event/auth-failure)))

      ;; Add watch on normalized Fulcro state atom for automatic render dispatch
      (remove-watch state-atom ::replicant-root)
      (add-watch state-atom ::replicant-root
        (fn [_ _ old-state new-state]
          (when-not (identical? old-state new-state)
            (schedule-render!))))
      ;; Connect Fulcro Inspect DevTools
      (try
        (chrome-devtools/install!)
        (inspect/add-fulcro-inspect! app-inst)
        (catch :default e
          (js/console.warn "Fulcro inspect registration skipped:" e)))
      (when (exists? js/window)
        (set! (.-fulcro_app js/window) app-inst))

      ;; Initial render — route is already in the correct state from the sync guard above
      (render!))
    (catch :default e
      (js/console.error "Replicant Direct Mount failed:" e))))

(defn ^:export refresh []
  (try
    (render!)
    (catch :default e
      (js/console.error "Replicant Direct Remount failed:" e))))

(when (exists? js/window)
  (.addEventListener js/window "popstate"
    (fn [_]
      (let [path (if (exists? js/window.location)
                   (str js/window.location.pathname js/window.location.search)
                   "/")]
        (scf/send! app-inst auth-sc/default-session-id :event/navigate {:path path}))))

  (.addEventListener js/window "error"
    (fn [e] (js/console.error "Uncaught error:" (.-error e) (.-message e))))

  (.addEventListener js/window "unhandledrejection"
    (fn [e] (js/console.error "Unhandled rejection:" (.-reason e))))

  (init))

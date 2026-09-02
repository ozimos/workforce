(ns com.ozimos.workforce.frontend.core
  (:require
   [com.fulcrologic.devtools.chrome.target :as chrome-devtools]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.transit :as transit]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant :as nav]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant :as create-org]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant :as dept-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant :as forgot-password]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant :as headcount]
   [com.ozimos.workforce.frontend.ui.pages.join-org-replicant :as join-org]
   [com.ozimos.workforce.frontend.ui.pages.login-replicant :as login]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant :as policy-settings]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant :as profile]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant :as register]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant :as reset-password]
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

(defn- current-path-route []
  (let [path (if (exists? js/window.location.pathname) js/window.location.pathname "/")]
    (cond
      (= path "/register") :route/register
      (= path "/register-replicant") :route/register-replicant
      (= path "/create-org") :route/create-org
      (= path "/create-org-replicant") :route/create-org-replicant
      (= path "/join-org") :route/join-org
      (= path "/join-org-replicant") :route/join-org-replicant
      (= path "/org-dashboard") :route/org-dashboard
      (= path "/org-dashboard-replicant") :route/org-dashboard-replicant
      (= path "/org-chart") :route/org-chart
      (= path "/org-chart-replicant") :route/org-chart-replicant
      (= path "/dept-dashboard") :route/dept-dashboard
      (= path "/dept-dashboard-replicant") :route/dept-dashboard-replicant
      (= path "/headcount") :route/headcount
      (= path "/headcount-replicant") :route/headcount-replicant
      (= path "/policies") :route/policies
      (= path "/policies-replicant") :route/policies-replicant
      (= path "/profile") :route/profile
      (= path "/profile-replicant") :route/profile-replicant
      (= path "/forgot-password") :route/forgot-password
      (= path "/forgot-password-replicant") :route/forgot-password-replicant
      (.startsWith path "/reset-password") :route/reset-password
      (.startsWith path "/verify") :route/verify
      (= path "/login") :route/login
      (= path "/login-replicant") :route/login-replicant
      (= path "/home-replicant") :route/home-replicant
      (= path "/") (if (is-logged-in?) :route/home :route/login)
      :else :route/login)))

(declare fetch-org-chart! fetch-user-session!)

(defn- navigate! [path]
  (when (exists? js/window.history)
    (.pushState js/window.history nil "" path)
    (let [route (current-path-route)
          state-atom (::app/state-atom app-inst)]
      (swap! state-atom assoc :route route :logged-in? (is-logged-in?))
      (when (is-logged-in?)
        (if (:active-org @state-atom)
          (when (#{:route/org-chart :route/org-chart-replicant :route/dept-dashboard :route/dept-dashboard-replicant} route)
            (fetch-org-chart!))
          (fetch-user-session!))))))

(defn- fetch-org-chart! []
  (when (is-logged-in?)
    (let [state-atom (::app/state-atom app-inst)
          active-org (:active-org @state-atom)]
      (when-let [org-id (:org/id active-org)]
        (swap! state-atom assoc :loading true :error nil)
        (-> (transit/fetch-transit "/api/query"
              [{[:org/id org-id]
                [{:org/chart [:org/id :org/hierarchy
                              {:org/units [:unit/id :unit/name :unit/division-id
                                           :unit/dept-id :unit/parent-id :unit/budget
                                           :unit/filled :unit/open :unit/pending
                                           :unit/actors :unit/children]}]}]}])
            (.then (fn [{:keys [body]}]
                     (let [chart (get-in body [[:org/id org-id] :org/chart])
                           unit-list (:org/units chart)
                           unit-map (into {} (map (fn [u] [(:unit/id u) u])) unit-list)
                           hier (or (:org/hierarchy chart)
                                    (reduce (fn [acc u]
                                              (let [p (:unit/parent-id u)]
                                                (update acc p (fnil conj []) (:unit/id u))))
                                            {}
                                            unit-list))]
                       (swap! state-atom assoc
                              :units unit-map
                              :hierarchy hier
                              :loading false))))
            (.catch (fn [err]
                      (swap! state-atom assoc
                             :loading false
                             :error (str "Failed to load org chart: " err)))))))))

(defn- fetch-user-session! []
  (when (is-logged-in?)
    (-> (transit/fetch-transit "/api/query"
          [:current-user/email :current-user/username :current-user/verified
           {:user/active-org [:org/id :org/name :org/role]}
           {:user/orgs [:org/id :org/name]}])
        (.then (fn [{:keys [status body]}]
                 (when (= 200 status)
                   (let [data body
                         active-org (:user/active-org data)
                         orgs (:user/orgs data)
                         state-atom (::app/state-atom app-inst)]
                     (when-let [e (:current-user/email data)]
                       (.setItem js/localStorage "email" e))
                     (when-let [u (:current-user/username data)]
                       (.setItem js/localStorage "username" u))
                     (swap! state-atom assoc
                            :active-org active-org
                            :orgs (or orgs []))
                     (fetch-org-chart!))))))))

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
                     (swap! state-atom assoc :logged-in? true :route :route/home)
                     (when (exists? js/window.history)
                       (.pushState js/window.history nil "" "/"))
                     (fetch-user-session!))

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
                     (swap! state-atom assoc :logged-in? true :route :route/home)
                     (when (exists? js/window.history)
                       (.pushState js/window.history nil "" "/"))
                     (fetch-user-session!))
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
                       (swap! state-atom assoc :logged-in? true :route :route/home)
                       (when (exists? js/window.history)
                         (.pushState js/window.history nil "" "/"))
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
  {;; Global Navigation
   :com.ozimos.workforce.frontend.ui.pages.home-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.components.nav-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.login-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.register-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.create-org-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.join-org-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.org-chart-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.headcount-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.profile-replicant/navigate
   (fn [_ path] (navigate! path))
   :com.ozimos.workforce.frontend.ui.pages.verify-replicant/navigate
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
     (when (exists? js/localStorage)
       (.removeItem js/localStorage "access-token")
       (.removeItem js/localStorage "refresh-token")
       (.removeItem js/localStorage "user-info")
       (.removeItem js/localStorage "username")
       (.removeItem js/localStorage "email"))
     (let [state-atom (::app/state-atom app-inst)]
       (swap! state-atom assoc :logged-in? false :active-org nil :orgs []))
     (navigate! "/login"))

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
          denormalized-tree (denorm/db->tree query db db)]
      (r/render mount-el (root-rc/RootReplicant denormalized-tree)))))

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
    (r/set-dispatch! (bridge/dispatch! event-handlers))
    (let [state-atom (::app/state-atom app-inst)
          route (current-path-route)
          logged-in? (is-logged-in?)]
      ;; Sync initial route and auth status into Fulcro DB
      (swap! state-atom assoc :route route :logged-in? logged-in?)
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

      ;; Initial render
      (render!)
      ;; Fetch user session if logged in
      (when logged-in?
        (fetch-user-session!)))
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
      (let [route (current-path-route)
            state-atom (::app/state-atom app-inst)]
        (swap! state-atom assoc :route route :logged-in? (is-logged-in?))
        (when (is-logged-in?)
          (fetch-user-session!)))))

  (.addEventListener js/window "error"
    (fn [e] (js/console.error "Uncaught error:" (.-error e) (.-message e))))

  (.addEventListener js/window "unhandledrejection"
    (fn [e] (js/console.error "Unhandled rejection:" (.-reason e))))

  (init))

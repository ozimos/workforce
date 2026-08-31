(ns com.ozimos.workforce.frontend.core
  (:require
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
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
   [goog.dom :as gdom]
   [replicant.dom :as r]))

;; -----------------------------------------------------------------------------
;; Headless Fulcro Application
;; Normalized DB, EQL queries, and defmutations with ZERO React DOM instances.
;; -----------------------------------------------------------------------------

(defonce app-inst (app/fulcro-app {}))

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
      (= path "/") :route/home
      :else :route/login)))

(defn- is-logged-in? []
  (and (exists? js/localStorage)
       (some? (.getItem js/localStorage "access-token"))))

(defn- navigate! [path]
  (when (exists? js/window.history)
    (.pushState js/window.history nil "" path)
    (let [route (current-path-route)
          state-atom (::app/state-atom app-inst)]
      (swap! state-atom assoc :route route :logged-in? (is-logged-in?)))))

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
   (fn [_ org-id] (js/console.log "Switch org:" org-id))
   ::nav/logout
   (fn [_]
     (when (exists? js/localStorage)
       (.removeItem js/localStorage "access-token")
       (.removeItem js/localStorage "refresh-token")
       (.removeItem js/localStorage "user-info"))
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

   ;; Create Org Form
   ::create-org/set-name
   (fn [ev]
     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
       (let [state-atom (::app/state-atom app-inst)]
         (swap! state-atom create-org/set-name-state v))))

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
      ;; Initial render
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
      (let [route (current-path-route)
            state-atom (::app/state-atom app-inst)]
        (swap! state-atom assoc :route route :logged-in? (is-logged-in?)))))

  (.addEventListener js/window "error"
    (fn [e] (js/console.error "Uncaught error:" (.-error e) (.-message e))))

  (.addEventListener js/window "unhandledrejection"
    (fn [e] (js/console.error "Unhandled rejection:" (.-reason e))))

  (init))

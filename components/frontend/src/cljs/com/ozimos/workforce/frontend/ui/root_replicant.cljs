(ns com.ozimos.workforce.frontend.ui.root-replicant
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc defrouter-rc]])
  (:require
   [com.ozimos.workforce.frontend.ui.components.nav-replicant :as nav]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant :as create-org]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant :as dept-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant :as forgot-password]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant :as headcount]
   [com.ozimos.workforce.frontend.ui.pages.home-replicant :as home]
   [com.ozimos.workforce.frontend.ui.pages.join-org-replicant :as join-org]
   [com.ozimos.workforce.frontend.ui.pages.login-replicant :as login]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.people-chart-replicant :as people-chart]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant :as org-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant :as policy-settings]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant :as profile]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant :as register]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant :as reset-password]
   [com.ozimos.workforce.frontend.ui.pages.verify-replicant :as verify]))

;; -----------------------------------------------------------------------------
;; Replicant Dynamic Router (Union Query over all routable page targets)
;; -----------------------------------------------------------------------------

(defrouter-rc MainRouter
  {:router-id :main-router
   :router-targets [login/LoginReplicant
                    register/RegisterReplicant
                    create-org/CreateOrgReplicant
                    join-org/JoinOrgReplicant
                    org-dashboard/OrgDashboardReplicant
                    people-chart/PeopleChartReplicant
                    org-chart/OrgChartReplicant
                    dept-dashboard/DeptDashboardReplicant
                    headcount/HeadcountReplicant
                    policy-settings/PolicySettingsReplicant
                    profile/ProfileReplicant
                    forgot-password/ForgotPasswordReplicant
                    reset-password/ResetPasswordReplicant
                    verify/VerifyReplicant
                    home/HomeReplicant]})

;; Fallback resolution helper for backwards compatibility / direct route keywords
(defn resolve-page-view [route]
  (case route
    (:route/login :route/login-replicant)                       login/LoginReplicant
    (:route/register :route/register-replicant)                 register/RegisterReplicant
    (:route/create-org :route/create-org-replicant)             create-org/CreateOrgReplicant
    (:route/join-org :route/join-org-replicant)                 join-org/JoinOrgReplicant
    (:route/org-dashboard :route/org-dashboard-replicant)       org-dashboard/OrgDashboardReplicant
    (:route/org-chart :route/org-chart-replicant)               people-chart/PeopleChartReplicant
    (:route/org-chart-2 :route/org-chart-2-replicant)           org-chart/OrgChartReplicant
    (:route/dept-dashboard :route/dept-dashboard-replicant)     dept-dashboard/DeptDashboardReplicant
    (:route/headcount :route/headcount-replicant)               headcount/HeadcountReplicant
    (:route/policies :route/policies-replicant)                 policy-settings/PolicySettingsReplicant
    (:route/profile :route/profile-replicant)                   profile/ProfileReplicant
    (:route/forgot-password :route/forgot-password-replicant)   forgot-password/ForgotPasswordReplicant
    (:route/reset-password :route/reset-password-replicant)     reset-password/ResetPasswordReplicant
    (:route/verify :route/verify-replicant)                     verify/VerifyReplicant
    (:route/home :route/home-replicant)                         home/HomeReplicant
    login/LoginReplicant))

(defrc RootReplicant
  {:query [:route :logged-in? :active-org :orgs :dropdown-open
           :status :message :user :units :hierarchy :collapsed-nodes
           :search-term :loading :error :active-tab :pending-approvals
           {:root/router (:query (meta MainRouter))}]
   :ident :root-replicant/root}
  [{:keys [logged-in? root/router] :as props}]
  [:div {:class "min-h-full"}
   (when logged-in?
     (nav/NavBarReplicant props))
   [:main
    (if router
      (MainRouter router)
      ;; Direct fallback if router prop not denormalized yet
      (let [view-fn (resolve-page-view (:route props))]
        (if view-fn
          (view-fn props)
          [:div {:class "flex items-center justify-center h-64"}
           [:p {:class "text-gray-500"} "Loading..."]]))) ]])


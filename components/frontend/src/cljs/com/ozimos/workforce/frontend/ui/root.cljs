(ns com.ozimos.workforce.frontend.ui.root
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc defrouter-rc]])
  (:require
   [com.ozimos.workforce.frontend.ui.components.nav :as nav]
   [com.ozimos.workforce.frontend.ui.pages.create-org :as create-org]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard :as dept-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password :as forgot-password]
   [com.ozimos.workforce.frontend.ui.pages.headcount :as headcount]
   [com.ozimos.workforce.frontend.ui.pages.home :as home]
   [com.ozimos.workforce.frontend.ui.pages.join-org :as join-org]
   [com.ozimos.workforce.frontend.ui.pages.login :as login]
   [com.ozimos.workforce.frontend.ui.pages.org-chart :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.workforce-chart :as workforce-chart]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard :as org-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings :as policy-settings]
   [com.ozimos.workforce.frontend.ui.pages.profile :as profile]
   [com.ozimos.workforce.frontend.ui.pages.register :as register]
   [com.ozimos.workforce.frontend.ui.pages.reset-password :as reset-password]
   [com.ozimos.workforce.frontend.ui.pages.verify :as verify]))

;; -----------------------------------------------------------------------------
;; Replicant Dynamic Router (Union Query over all routable page targets)
;; -----------------------------------------------------------------------------

(defrouter-rc MainRouter
  {:router-id :main-router
   :router-targets [login/Login
                    register/Register
                    create-org/CreateOrg
                    join-org/JoinOrg
                    org-dashboard/OrgDashboard
                    workforce-chart/WorkforceChart
                    org-chart/OrgChart
                    dept-dashboard/DeptDashboard
                    headcount/Headcount
                    policy-settings/PolicySettings
                    profile/Profile
                    forgot-password/ForgotPassword
                    reset-password/ResetPassword
                    verify/Verify
                    home/Home]})

;; Fallback resolution helper for backwards compatibility / direct route keywords
(defn resolve-page-view [route]
  (case route
    :route/login                       login/Login
    :route/register                 register/Register
    :route/create-org             create-org/CreateOrg
    :route/join-org                 join-org/JoinOrg
    :route/org-dashboard       org-dashboard/OrgDashboard
    :route/org-chart               workforce-chart/WorkforceChart
    :route/org-chart-2           org-chart/OrgChart
    :route/dept-dashboard     dept-dashboard/DeptDashboard
    :route/headcount               headcount/Headcount
    :route/policies                 policy-settings/PolicySettings
    :route/profile                   profile/Profile
    :route/forgot-password   forgot-password/ForgotPassword
    :route/reset-password     reset-password/ResetPassword
    :route/verify                     verify/Verify
    :route/home                         home/Home
    login/Login))

(defrc Root
  {:query [:route :logged-in? :active-org :orgs :dropdown-open
           :status :message :user :units :hierarchy :collapsed-nodes
           :search-term :loading :error :active-tab :pending-approvals
           {:root/router (:query (meta MainRouter))}]
   :ident :root/root}
  [{:keys [logged-in? root/router] :as props}]
  [:div {:class "min-h-full"}
   (when logged-in?
     (nav/NavBar props))
   [:main
    (if router
      (MainRouter router)
      ;; Direct fallback if router prop not denormalized yet
      (let [view-fn (resolve-page-view (:route props))]
        (if view-fn
          (view-fn props)
          [:div {:class "flex items-center justify-center h-64"}
           [:p {:class "text-gray-500"} "Loading..."]]))) ]])


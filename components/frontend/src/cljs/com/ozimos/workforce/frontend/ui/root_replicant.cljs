(ns com.ozimos.workforce.frontend.ui.root-replicant
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
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
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant :as org-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant :as policy-settings]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant :as profile]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant :as register]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant :as reset-password]
   [com.ozimos.workforce.frontend.ui.pages.verify-replicant :as verify]))

(defn resolve-page-view [route]
  (case route
    :route/login                    login/LoginReplicant
    :route/login-replicant          login/LoginReplicant
    :route/register                 register/RegisterReplicant
    :route/register-replicant       register/RegisterReplicant
    :route/create-org               create-org/CreateOrgReplicant
    :route/create-org-replicant     create-org/CreateOrgReplicant
    :route/join-org                 join-org/JoinOrgReplicant
    :route/join-org-replicant       join-org/JoinOrgReplicant
    :route/org-dashboard            org-dashboard/OrgDashboardReplicant
    :route/org-dashboard-replicant  org-dashboard/OrgDashboardReplicant
    :route/org-chart                org-chart/OrgChartReplicant
    :route/org-chart-replicant      org-chart/OrgChartReplicant
    :route/dept-dashboard           dept-dashboard/DeptDashboardReplicant
    :route/dept-dashboard-replicant dept-dashboard/DeptDashboardReplicant
    :route/headcount                headcount/HeadcountReplicant
    :route/headcount-replicant      headcount/HeadcountReplicant
    :route/policies                 policy-settings/PolicySettingsReplicant
    :route/policies-replicant       policy-settings/PolicySettingsReplicant
    :route/profile                  profile/ProfileReplicant
    :route/profile-replicant        profile/ProfileReplicant
    :route/forgot-password          forgot-password/ForgotPasswordReplicant
    :route/forgot-password-replicant forgot-password/ForgotPasswordReplicant
    :route/reset-password           reset-password/ResetPasswordReplicant
    :route/reset-password-replicant reset-password/ResetPasswordReplicant
    :route/verify                   verify/VerifyReplicant
    :route/verify-replicant         verify/VerifyReplicant
    :route/home-replicant           home/HomeReplicant
    :route/home                     home/HomeReplicant
    login/LoginReplicant))

(defrc RootReplicant
  {:query [:route :logged-in? :active-org :orgs :dropdown-open
           :status :message :user :units :hierarchy :collapsed-nodes
           :search-term :loading :error :active-tab :pending-approvals]
   :ident :root-replicant/root}
  [{:keys [route logged-in?] :as props}]
  (let [view-fn (resolve-page-view route)]
    [:div {:class "min-h-full"}
     (when logged-in?
       (nav/NavBarReplicant props))
     [:main
      (if view-fn
        (view-fn props)
        [:div {:class "flex items-center justify-center h-64"}
         [:p {:class "text-gray-500"} "Loading..."]])]]))

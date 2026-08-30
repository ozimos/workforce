(ns com.ozimos.workforce.frontend.ui.root
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [div p]]
   [com.ozimos.workforce.frontend.ui.components.nav :as nav]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant-host :as nav-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.create-org :as create-org]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant-host :as create-org-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard :as dept-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant-host :as dept-dashboard-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password :as forgot-password]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant-host :as forgot-password-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.headcount :as headcount]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant-host :as headcount-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.home :as home]
   [com.ozimos.workforce.frontend.ui.pages.home-replicant-host :as home-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.join-org :as join-org]
   [com.ozimos.workforce.frontend.ui.pages.join-org-replicant-host :as join-org-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.login :as login]
   [com.ozimos.workforce.frontend.ui.pages.login-replicant-host :as login-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.org-chart :as org-chart]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-host :as org-chart-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard :as org-dashboard]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant-host :as org-dashboard-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings :as policy-settings]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant-host :as policy-settings-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.profile :as profile]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant-host :as profile-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.register :as register]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant-host :as register-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.reset-password :as reset-password]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant-host :as reset-password-replicant-host]
   [com.ozimos.workforce.frontend.ui.pages.verify :as verify]
   [com.ozimos.workforce.frontend.ui.pages.verify-replicant-host :as verify-replicant-host]))

(defn- current-page
  []
  (let [path (or js/window.location.pathname "")]
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
      (.startsWith path "/reset-password-replicant") :route/reset-password-replicant
      (.startsWith path "/reset-password") :route/reset-password
      (.startsWith path "/verify-replicant") :route/verify-replicant
      (.startsWith path "/verify") :route/verify
      (= path "/login") :route/login
      (= path "/login-replicant") :route/login-replicant
      (= path "/home-replicant") :route/home-replicant
      (= path "/") :route/home
      :else :route/login)))

(defn- logged-in?
  []
  (and (exists? js/localStorage)
       (some? (.getItem js/localStorage "access-token"))))

(defn- route-for-page [page]
  (case page
    :route/login "/login"
    :route/login-replicant "/login-replicant"
    :route/register "/register"
    :route/register-replicant "/register-replicant"
    :route/create-org "/create-org"
    :route/create-org-replicant "/create-org-replicant"
    :route/join-org "/join-org"
    :route/join-org-replicant "/join-org-replicant"
    :route/org-dashboard "/org-dashboard"
    :route/org-dashboard-replicant "/org-dashboard-replicant"
    :route/org-chart "/org-chart"
    :route/org-chart-replicant "/org-chart-replicant"
    :route/dept-dashboard "/dept-dashboard"
    :route/dept-dashboard-replicant "/dept-dashboard-replicant"
    :route/headcount "/headcount"
    :route/headcount-replicant "/headcount-replicant"
    :route/policies "/policies"
    :route/policies-replicant "/policies-replicant"
    :route/profile "/profile"
    :route/profile-replicant "/profile-replicant"
    :route/forgot-password "/forgot-password"
    :route/forgot-password-replicant "/forgot-password-replicant"
    :route/reset-password "/reset-password"
    :route/reset-password-replicant "/reset-password-replicant"
    :route/verify "/verify"
    :route/verify-replicant "/verify-replicant"
    :route/home-replicant "/home-replicant"
    :route/home "/"
    "/login"))

(def nav-factory            (delay (comp/factory nav/NavBar)))
(def login-factory          (delay (comp/factory login/Login)))
(def login-replicant-host-factory (delay (comp/factory login-replicant-host/LoginReplicantHost)))
(def register-factory       (delay (comp/factory register/Register)))
(def register-replicant-host-factory (delay (comp/factory register-replicant-host/RegisterReplicantHost)))
(def create-org-factory     (delay (comp/factory create-org/CreateOrg)))
(def create-org-replicant-host-factory (delay (comp/factory create-org-replicant-host/CreateOrgReplicantHost)))
(def join-org-factory        (delay (comp/factory join-org/JoinOrg)))
(def join-org-replicant-host-factory (delay (comp/factory join-org-replicant-host/JoinOrgReplicantHost)))
(def org-dashboard-factory  (delay (comp/factory org-dashboard/OrgDashboard)))
(def org-dashboard-replicant-host-factory (delay (comp/factory org-dashboard-replicant-host/OrgDashboardReplicantHost)))
(def org-chart-factory      (delay (comp/factory org-chart/OrgChart)))
(def org-chart-replicant-host-factory (delay (comp/factory org-chart-replicant-host/OrgChartReplicantHost)))
(def dept-dashboard-factory (delay (comp/factory dept-dashboard/DeptDashboard)))
(def dept-dashboard-replicant-host-factory (delay (comp/factory dept-dashboard-replicant-host/DeptDashboardReplicantHost)))
(def headcount-factory      (delay (comp/factory headcount/HeadcountPage)))
(def headcount-replicant-host-factory (delay (comp/factory headcount-replicant-host/HeadcountReplicantHost)))
(def policy-settings-factory (delay (comp/factory policy-settings/PolicySettings)))
(def policy-settings-replicant-host-factory (delay (comp/factory policy-settings-replicant-host/PolicySettingsReplicantHost)))
(def forgot-pw-factory      (delay (comp/factory forgot-password/ForgotPassword)))
(def forgot-pw-replicant-host-factory (delay (comp/factory forgot-password-replicant-host/ForgotPasswordReplicantHost)))
(def reset-pw-factory        (delay (comp/factory reset-password/ResetPassword)))
(def reset-pw-replicant-host-factory (delay (comp/factory reset-password-replicant-host/ResetPasswordReplicantHost)))
(def verify-factory         (delay (comp/factory verify/Verify)))
(def verify-replicant-host-factory (delay (comp/factory verify-replicant-host/VerifyReplicantHost)))
(def home-factory           (delay (comp/factory home/Home)))
(def home-replicant-host-factory (delay (comp/factory home-replicant-host/HomeReplicantHost)))
(def profile-factory        (delay (comp/factory profile/Profile)))
(def profile-replicant-host-factory (delay (comp/factory profile-replicant-host/ProfileReplicantHost)))

(defn- browser-env?
  "Returns true only when executing inside a real browser environment (not Node.js SSR)."
  []
  (and (exists? js/window)
       (exists? js/window.location)
       (not (and (exists? js/process)
                 (exists? js/process.versions)
                 (some? (.-node (.-versions js/process)))))))

(defsc Root [_ _]
  {:query []}
  (let [page (current-page)
        logged-in (logged-in?)]
    (when (and (browser-env?)
               (not logged-in)
               (= page :route/home))
      (set! js/window.location.pathname (route-for-page :route/login)))
    (div {:className "min-h-full"}
      (when logged-in (div {:key "nav"} (@nav-factory)))
      (div {:key "page"} (case page
                           :route/login (@login-factory)
                           :route/login-replicant (@login-replicant-host-factory)
                           :route/register (@register-factory)
                           :route/register-replicant (@register-replicant-host-factory)
                           :route/create-org (@create-org-factory)
                           :route/create-org-replicant (@create-org-replicant-host-factory)
                           :route/join-org (@join-org-factory)
                           :route/join-org-replicant (@join-org-replicant-host-factory)
                           :route/org-dashboard (@org-dashboard-factory)
                           :route/org-dashboard-replicant (@org-dashboard-replicant-host-factory)
                           :route/org-chart (@org-chart-factory)
                           :route/org-chart-replicant (@org-chart-replicant-host-factory)
                           :route/dept-dashboard (@dept-dashboard-factory)
                           :route/dept-dashboard-replicant (@dept-dashboard-replicant-host-factory)
                           :route/headcount (@headcount-factory)
                           :route/headcount-replicant (@headcount-replicant-host-factory)
                           :route/policies (@policy-settings-factory)
                           :route/policies-replicant (@policy-settings-replicant-host-factory)
                           :route/forgot-password (@forgot-pw-factory)
                           :route/forgot-password-replicant (@forgot-pw-replicant-host-factory)
                           :route/reset-password (@reset-pw-factory)
                           :route/reset-password-replicant (@reset-pw-replicant-host-factory)
                           :route/verify (@verify-factory)
                           :route/verify-replicant (@verify-replicant-host-factory)
                           :route/home-replicant (@home-replicant-host-factory)
                           :route/home (@home-factory)
                           :route/profile (@profile-factory)
                           :route/profile-replicant (@profile-replicant-host-factory)
                           (div {:className "flex items-center justify-center h-64"}
                             (p {:className "text-gray-500"} "Loading...")))))))

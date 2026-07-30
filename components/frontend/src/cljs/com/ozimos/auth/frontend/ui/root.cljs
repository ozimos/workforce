(ns com.ozimos.auth.frontend.ui.root
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [div p]]
   [com.ozimos.auth.frontend.ui.components.nav :as nav]
   [com.ozimos.auth.frontend.ui.pages.create-org :as create-org]
   [com.ozimos.auth.frontend.ui.pages.forgot-password :as forgot-password]
   [com.ozimos.auth.frontend.ui.pages.home :as home]
   [com.ozimos.auth.frontend.ui.pages.join-org :as join-org]
   [com.ozimos.auth.frontend.ui.pages.login :as login]
   [com.ozimos.auth.frontend.ui.pages.org-dashboard :as org-dashboard]
   [com.ozimos.auth.frontend.ui.pages.profile :as profile]
   [com.ozimos.auth.frontend.ui.pages.register :as register]
   [com.ozimos.auth.frontend.ui.pages.reset-password :as reset-password]
   [com.ozimos.auth.frontend.ui.pages.verify :as verify]))

(defn- current-page
  []
  (let [path (or js/window.location.pathname "")]
    (cond
      (= path "/register") :route/register
      (= path "/create-org") :route/create-org
      (= path "/join-org") :route/join-org
      (= path "/org-dashboard") :route/org-dashboard
      (= path "/profile") :route/profile
      (= path "/forgot-password") :route/forgot-password
      (.startsWith path "/reset-password") :route/reset-password
      (.startsWith path "/verify") :route/verify
      (= path "/login") :route/login
      (= path "/") :route/home
      :else :route/login)))

(defn- logged-in?
  []
  (and (exists? js/localStorage)
       (some? (.getItem js/localStorage "access-token"))))

(defn- route-for-page [page]
  (case page
    :route/login "/login"
    :route/register "/register"
    :route/create-org "/create-org"
    :route/join-org "/join-org"
    :route/org-dashboard "/org-dashboard"
    :route/forgot-password "/forgot-password"
    :route/reset-password "/reset-password"
    :route/verify "/verify"
    :route/home "/"
    "/login"))

(def nav-factory        (delay (comp/factory nav/NavBar)))
(def login-factory      (delay (comp/factory login/Login)))
(def register-factory   (delay (comp/factory register/Register)))
(def create-org-factory (delay (comp/factory create-org/CreateOrg)))
(def join-org-factory    (delay (comp/factory join-org/JoinOrg)))
(def org-dashboard-factory (delay (comp/factory org-dashboard/OrgDashboard)))
(def forgot-pw-factory  (delay (comp/factory forgot-password/ForgotPassword)))
(def reset-pw-factory    (delay (comp/factory reset-password/ResetPassword)))
(def verify-factory     (delay (comp/factory verify/Verify)))
(def home-factory       (delay (comp/factory home/Home)))

(defsc Root [_ _]
  {:query []}
  (let [page (current-page)
        logged-in (logged-in?)]
    (when (and (not logged-in)
               (= page :route/home))
      (set! js/window.location.pathname (route-for-page :route/login)))
    (div {:className "min-h-full"}
      (when logged-in (div {:key "nav"} (@nav-factory)))
      (div {:key "page"} (case page
                           :route/login (@login-factory)
                           :route/register (@register-factory)
                           :route/create-org (@create-org-factory)
                           :route/join-org (@join-org-factory)
                           :route/org-dashboard (@org-dashboard-factory)
                           :route/forgot-password (@forgot-pw-factory)
                           :route/reset-password (@reset-pw-factory)
                           :route/verify (@verify-factory)
                           :route/home (@home-factory)
                           (div {:className "flex items-center justify-center h-64"}
                             (p {:className "text-gray-500"} "Loading...")))))))

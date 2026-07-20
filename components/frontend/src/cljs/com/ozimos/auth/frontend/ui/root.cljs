(ns com.ozimos.auth.frontend.ui.root
  (:require [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.ozimos.auth.frontend.ui.components.nav :as nav]
            [com.ozimos.auth.frontend.ui.pages.login :as login]
            [com.ozimos.auth.frontend.ui.pages.register :as register]
            [com.ozimos.auth.frontend.ui.pages.forgot-password :as forgot-password]
            [com.ozimos.auth.frontend.ui.pages.reset-password :as reset-password]
            [com.ozimos.auth.frontend.ui.pages.verify :as verify]
            [com.ozimos.auth.frontend.ui.pages.home :as home]))

(defn- current-page
  []
  (let [hash (or js/window.location.hash "")]
    (cond
      (.startsWith hash "#/register") :route/register
      (.startsWith hash "#/forgot-password") :route/forgot-password
      (.startsWith hash "#/reset-password") :route/reset-password
      (.startsWith hash "#/verify") :route/verify
      (.startsWith hash "#/login") :route/login
      (.startsWith hash "#/") :route/home
      :else :route/login)))

(defn- logged-in?
  []
  (some? (.getItem js/localStorage "access-token")))

(defn- route-for-page [page]
  (case page
    :route/login "#!/login"
    :route/register "#!/register"
    :route/forgot-password "#!/forgot-password"
    :route/reset-password "#!/reset-password"
    :route/verify "#!/verify"
    :route/home "#!/"
    "#!/login"))

(defsc Root [_ {:keys []}]
  {:query []
   :ident (fn [] [:root :singleton])}
  (let [page (current-page)
        logged-in (logged-in?)]
    (when (and (not logged-in)
               (= page :route/home))
      (set! js/window.location.hash (route-for-page :route/login)))
    [:div {:class "min-h-full"}
     (when logged-in (nav/nav-bar))
     (case page
       :route/login (login/ui-login)
       :route/register (register/ui-register)
       :route/forgot-password (forgot-password/ui-forgot-password)
       :route/reset-password (reset-password/ui-reset-password)
       :route/verify (verify/ui-verify)
       :route/home (home/ui-home)
       [:div {:class "flex items-center justify-center h-64"}
        [:p {:class "text-gray-500"} "Loading..."]])]))
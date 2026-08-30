(ns com.ozimos.workforce.frontend.web.router)

(defn path->route [path]
  (cond
    (= path "/login") :route/login
    (= path "/login-replicant") :route/login-replicant
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
    (and path (.startsWith path "/reset-password-replicant")) :route/reset-password-replicant
    (and path (.startsWith path "/reset-password")) :route/reset-password
    (and path (.startsWith path "/verify-replicant")) :route/verify-replicant
    (and path (.startsWith path "/verify")) :route/verify
    (= path "/home-replicant") :route/home-replicant
    (= path "/") :route/home
    :else :route/home))

(defn route->path [route]
  (case route
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
    "/"))

(defn navigate!
  ([app-state route] (navigate! app-state route {}))
  ([app-state route params]
   (let [path (route->path route)]
     (when (and (exists? js/window) (exists? js/window.history))
       (.pushState js/window.history nil "" path))
     (swap! app-state assoc :current-route route :route-params params))))

(defn init-router! [app-state]
  (when (exists? js/window)
    (let [current-path (.-pathname js/window.location)]
      (swap! app-state assoc :current-route (path->route current-path)))
    (.addEventListener js/window "popstate"
      (fn [_]
        (let [path (.-pathname js/window.location)]
          (swap! app-state assoc :current-route (path->route path)))))))

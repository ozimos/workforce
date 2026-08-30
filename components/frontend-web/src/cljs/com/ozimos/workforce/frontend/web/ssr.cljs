(ns com.ozimos.workforce.frontend.web.ssr
  (:require
   [replicant.string :as rs]
   [com.ozimos.workforce.frontend.ui.root-replicant :as root]
   [com.ozimos.workforce.frontend.web.router :as router]))

(defn- escape-html [s]
  (-> (str s)
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;")
      (.replace "'" "&#39;")))

(defn- page-title [route]
  (case route
    :route/login "Sign In"
    :route/login-replicant "Sign In (Replicant)"
    :route/register "Create Account"
    :route/register-replicant "Create Account (Replicant)"
    :route/create-org "Create Organization"
    :route/create-org-replicant "Create Organization (Replicant)"
    :route/join-org "Join Organization"
    :route/join-org-replicant "Join Organization (Replicant)"
    :route/org-dashboard "Organization Dashboard"
    :route/org-dashboard-replicant "Organization Dashboard (Replicant)"
    :route/org-chart "Organization Chart"
    :route/org-chart-replicant "Organization Chart (Replicant)"
    :route/dept-dashboard "Department Dashboard"
    :route/dept-dashboard-replicant "Department Dashboard (Replicant)"
    :route/headcount "Headcount"
    :route/headcount-replicant "Headcount (Replicant)"
    :route/policies "Policies"
    :route/policies-replicant "Policies (Replicant)"
    :route/profile "Profile"
    :route/profile-replicant "Profile (Replicant)"
    :route/forgot-password "Forgot Password"
    :route/forgot-password-replicant "Forgot Password (Replicant)"
    :route/reset-password "Reset Password"
    :route/reset-password-replicant "Reset Password (Replicant)"
    :route/verify "Verify Account"
    :route/verify-replicant "Verify Account (Replicant)"
    :route/home-replicant "Dashboard (Replicant)"
    :route/home "Dashboard"
    "Best Auth"))

(defn- page-description [route]
  (case route
    :route/login "Sign in to your account"
    :route/login-replicant "Replicant sign in (pure hiccup)"
    :route/register "Create a new account"
    :route/register-replicant "Replicant registration (pure hiccup)"
    :route/org-chart "Interactive organizational hierarchy"
    :route/org-chart-replicant "Replicant rendering of organizational hierarchy (pure hiccup)"
    :route/headcount "Headcount requisitions and approvals"
    :route/headcount-replicant "Replicant headcount (pure hiccup)"
    :route/home-replicant "Replicant home dashboard (pure hiccup)"
    :route/home "Dashboard - Best Auth"
    "Best Auth - Authentication Template"))

(defn- setup-ssr-globals [path search]
  (when (exists? js/window)
    (when (exists? (.-location js/window))
      (set! (.-pathname (.-location js/window)) (or path "/"))
      (set! (.-search (.-location js/window)) (if (seq search) (str "?" search) "")))))

(defn ^:export render-page-html
  ([path] (render-page-html path "" "" ""))
  ([path search] (render-page-html path search "" ""))
  ([path search initial-data-json] (render-page-html path search initial-data-json ""))
  ([path search initial-data-json initial-data-nonce]
   (setup-ssr-globals path search)
   (let [route (router/path->route path)
         title (str "Best Auth - " (page-title route))
         description (page-description route)
         state {:current-route route
                :nav-state {:active-org {:org/name "Demo Co" :org/role "ADMIN"}
                            :orgs [{:org/id "1" :org/name "Demo Co"}]
                            :dropdown-open false}
                :page-state {}}
         {:keys [status html error-message]}
         (try
           (let [hiccup (root/RootView state)
                 rendered (rs/render hiccup)]
             {:status :ok :html rendered})
           (catch :default e
             {:status :error
              :error-message (str e)}))]
     (str "<!DOCTYPE html>"
          "<html lang=\"en\" class=\"h-full\">"
          "<head>"
          "<meta charset=\"UTF-8\">"
          "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
          "<title>" (escape-html title) "</title>"
          "<meta name=\"description\" content=\"" (escape-html description) "\">"
          "<meta name=\"ssr-status\" content=\"" (name status) "\">"
          "<link href=\"/css/app.css\" rel=\"stylesheet\">"
          (when (seq initial-data-json)
            (str "<script"
                 (when (seq initial-data-nonce)
                   (str " nonce=\"" (escape-html initial-data-nonce) "\""))
                 ">window.__INITIAL_DATA__=" initial-data-json "</script>"))
          "</head>"
          "<body class=\"h-full bg-gray-50\">"
          (case status
            :error   (str "<div id=\"ssr-error\" "
                          "style=\"background:#fee;color:#c00;padding:1em;margin:1em;"
                          "border:2px solid #c00;white-space:pre-wrap;font-family:monospace\">"
                          (escape-html error-message)
                          "</div>")
            "")
          "<div id=\"app\" class=\"h-full\">"
          (if (= status :ok) html "")
          "</div>"
          "<script src=\"/js/main.js\"></script>"
          "<!-- " (case status
                    :ok      "SSR OK"
                    :error   (str "SSR ERROR: " (escape-html error-message)))
          " -->"
          "</body>"
          "</html>"))))

(ns com.ozimos.workforce.frontend.ssr
  (:require
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.routing :as routing]
   [com.ozimos.workforce.frontend.ui.root :as root-rc]
   [replicant.string :as rstr]))

(defn- escape-html [s]
  (-> s
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;")
      (.replace "'" "&#39;")))

(defn- authenticated? []
  (= js/process.env.SSR_AUTHENTICATED "true"))

(defn- ssr-verified? []
  (boolean
    (or (= js/process.env.SSR_VERIFIED "true")
        (when (and (exists? js/localStorage)
                   (= "true" (.getItem js/localStorage "verified")))
          true))))

(defn- setup-ssr-globals [path search]
  (set! (.-pathname (.-location js/window)) path)
  (set! (.-search (.-location js/window)) (if (seq search) (str "?" search) "")))

(defn- page-title [path]
  (cond
    (= path "/login")            "Sign In"
    (= path "/login")  "Sign In"
    (= path "/register")         "Create Account"
    (= path "/register") "Create Account"
    (= path "/create-org")       "Create Organization"
    (= path "/create-org") "Create Organization"
    (= path "/join-org")         "Join Organization"
    (= path "/join-org") "Join Organization"
    (= path "/org-dashboard")    "Organization Dashboard"
    (= path "/org-dashboard") "Organization Dashboard"
    (= path "/policies")        "Policies"
    (= path "/policies") "Policies"
    (= path "/org-chart")        "Organization Chart"
    (= path "/org-chart") "Organization Chart"
    (= path "/headcount")        "Headcount"
    (= path "/headcount") "Headcount"
    (= path "/dept-dashboard")   "Department Dashboard"
    (= path "/dept-dashboard") "Department Dashboard"
    (= path "/profile")          "Profile"
    (= path "/profile") "Profile"
    (= path "/forgot-password")  "Forgot Password"
    (= path "/forgot-password") "Forgot Password"
    (= path "/reset-password")   "Reset Password"
    (= path "/reset-password") "Reset Password"
    (= path "/verify")           "Verify Account"
    (= path "/verify") "Verify Account"
    (= path "/home")   "Dashboard"
    (= path "/")                 "Dashboard"
    :else                        "Best Auth"))

(defn- page-description [path]
  (cond
    (= path "/login")            "Sign in to your account"
    (= path "/login")  "Sign in to your account"
    (= path "/register")         "Create a new account"
    (= path "/register") "Create a new account"
    (= path "/create-org")       "Create a new organization"
    (= path "/create-org") "Create a new organization"
    (= path "/join-org")         "Join an existing organization"
    (= path "/join-org") "Join an existing organization"
    (= path "/org-dashboard")    "Manage your organization"
    (= path "/org-dashboard") "Manage your organization"
    (= path "/policies")        "Policies and approval routing"
    (= path "/policies") "Policies and approval routing"
    (= path "/org-chart")        "Interactive organizational hierarchy"
    (= path "/org-chart") "Interactive organizational hierarchy"
    (= path "/headcount")        "Headcount requisitions and approvals"
    (= path "/headcount") "Headcount requisitions and approvals"
    (= path "/dept-dashboard")   "Department headcount dashboard"
    (= path "/dept-dashboard") "Department headcount dashboard"
    (= path "/profile")          "Update your profile"
    (= path "/profile") "Update your profile"
    (= path "/forgot-password")  "Reset your password"
    (= path "/forgot-password") "Reset your password"
    (= path "/reset-password")   "Set a new password"
    (= path "/reset-password") "Set a new password"
    (= path "/verify")           "Verify your email address"
    (= path "/verify") "Verify your email address"
    (= path "/home")   "Home dashboard"
    (= path "/")                 "Dashboard - Best Auth"
    :else                        "Best Auth - Authentication Template"))

(defn- current-path-route
  "Delegates to routing/path->route (SSOT)."
  [path]
  (routing/path->route path))

(defn ^:export render-page-html
  ([path] (render-page-html path "" "" ""))
  ([path search] (render-page-html path search "" ""))
  ([path search initial-data-json] (render-page-html path search initial-data-json ""))
  ([path search initial-data-json initial-data-nonce]
   (setup-ssr-globals path search)
   ;; SSR auth guard — mirrors client boot guard in core.cljs
   ;; Unauth protected -> /login, auth public -> / (verify only when verified)
   (let [verified? (ssr-verified?)
         effective-path (cond
                          (and (not (authenticated?))
                               (or (= path "/") (routing/protected-path? path)))
                          "/login"

                          (and (authenticated?)
                               (not= path "/")
                               (routing/should-redirect-public? path verified?))
                          "/"

                          :else path)
         title (str "Best Auth - " (page-title effective-path))
         description (page-description effective-path)
         {:keys [status html error-message]}
         (try
           (let [app-inst (app/fulcro-app {})
                 state-atom (::app/state-atom app-inst)
                 route (current-path-route effective-path)
                 logged-in? (authenticated?)]
             (swap! state-atom assoc :route route :logged-in? logged-in?)
             (let [db @state-atom
                   query (:query (meta root-rc/Root))
                   denormalized-tree (denorm/db->tree query db db)
                   hiccup (root-rc/Root denormalized-tree)
                   rendered (rstr/render hiccup)]
               {:status :ok :html rendered}))
           (catch js/Error e
             {:status :error
              :error-message (str (.-message e) "\n" (.-stack e))}))]
     (str "<!DOCTYPE html>"
          "<html lang=\"en\" class=\"h-full\">"
          "<head>"
          "<meta charset=\"UTF-8\">"
          "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
          "<title>" (escape-html title) "</title>"
          "<meta name=\"description\" content=\"" (escape-html description) "\">"
          "<meta name=\"ssr-status\" content=\"" (name status) "\">"
          (when (authenticated?)
            "<meta name=\"ssr-authenticated\" content=\"true\">")
          (when (ssr-verified?)
            "<meta name=\"ssr-verified\" content=\"true\">")
          (when (cond
                  (and (not (authenticated?))
                       (or (= path "/") (routing/protected-path? path))) true
                  (and (authenticated?)
                       (not= path "/")
                       (routing/should-redirect-public? path (ssr-verified?))) true
                  :else false)
            (if (authenticated?)
              "<meta name=\"ssr-redirect\" content=\"/\">"
              "<meta name=\"ssr-redirect\" content=\"/login\">"))
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
            :limited (str "<div id=\"ssr-limited\" "
                          "style=\"background:#fff3cd;color:#856404;padding:1em;margin:1em;"
                          "border:2px solid #ffc107;font-family:monospace\">"
                          (escape-html error-message)
                          "</div>")
            "")
          "<div id=\"app\" class=\"h-full\">"
          (if (= status :ok) html "")
          "</div>"
          "<script src=\"/js/main.js\"></script>"
          "<!-- " (case status
                    :ok      "SSR OK"
                    :limited "SSR LIMITED"
                    :error   (str "SSR ERROR: " (escape-html error-message)))
          " -->"
          "</body>"
          "</html>"))))
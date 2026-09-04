(ns com.ozimos.workforce.frontend.routing
  "Shared route classification and mapping — single source of truth for
   public vs protected paths. Used by auth_statechart, core (boot guard),
   and ssr (SSR guard) to avoid drift."
  (:require
   [clojure.string :as str]))

(defn public-path?
  "Returns true if the URL path does not require authentication.
   `path` is expected to be `window.location.pathname` (no query/hash).
   Also handles full path with query by stripping search."
  [path]
  (let [p (or path "/")
        ;; strip query string if present (e.g. \"/dept-dashboard?unit-id=123\")
        pathname (first (str/split p #"\?" 2))]
    (or (= pathname "/")
        (= pathname "/login")
        (= pathname "/register")
        (= pathname "/forgot-password")
        (str/starts-with? pathname "/reset-password")
        (str/starts-with? pathname "/verify"))))

(def protected-path?
  "Returns true if the URL path requires an active authenticated session."
  (complement public-path?))

(defn verify-path?
  "Returns true if path is /verify (with optional query)."
  [path]
  (let [pathname (first (str/split (or path "/") #"\?" 2))]
    (str/starts-with? pathname "/verify")))

(defn should-redirect-public?
  "When authenticated, should public path redirect to /?
   Verify is exception: stays when !verified, redirects when verified."
  [path verified?]
  (boolean
    (and (public-path? path)
         (if (verify-path? path)
           verified?   ; verify -> / only if verified
           true))))  ; all other public -> /

(defn path->route
  "Pure mapping from pathname to route keyword (no auth check for \"/\").
   Caller decides \"/\" semantics (logged-in? -> :route/home else :route/login)."
  [path]
  (let [p (or path "/")
        pathname (first (str/split p #"\?" 2))]
    (cond
      (= pathname "/register") :route/register
      (= pathname "/create-org") :route/create-org
      (= pathname "/join-org") :route/join-org
      (= pathname "/org-dashboard") :route/org-dashboard
      (= pathname "/org-chart") :route/org-chart
      (= pathname "/org-chart-2") :route/org-chart-2
      (= pathname "/dept-dashboard") :route/dept-dashboard
      (= pathname "/headcount") :route/headcount
      (= pathname "/policies") :route/policies
      (= pathname "/profile") :route/profile
      (= pathname "/forgot-password") :route/forgot-password
      (str/starts-with? pathname "/reset-password") :route/reset-password
      (str/starts-with? pathname "/verify") :route/verify
      (= pathname "/login") :route/login
      (= pathname "/home") :route/home
      (= pathname "/") :route/home
      :else :route/login)))
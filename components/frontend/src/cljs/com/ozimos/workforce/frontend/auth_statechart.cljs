(ns com.ozimos.workforce.frontend.auth-statechart
  "Fulcro Statechart governing user authentication lifecycle, route protection,
   and unauthenticated redirection for the Workforce pure-Replicant frontend."
  (:require
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]))

(def machine-id :auth-routing-machine)
(def default-session-id :auth-routing)

(defn public-path?
  "Returns true if the URL path does not require authentication."
  [path]
  (let [p (or path "/")]
    (or (= p "/")
        (= p "/login")
        (= p "/login-replicant")
        (= p "/register")
        (= p "/register-replicant")
        (= p "/forgot-password")
        (= p "/forgot-password-replicant")
        (str/starts-with? p "/reset-password")
        (str/starts-with? p "/verify"))))

(def protected-path?
  "Returns true if the URL path requires an active authenticated session."
  (complement public-path?))

(defn- script-action
  "Helper to create a script element with a 2/4-arity function."
  [f]
  (script {:expr (fn
                   ([env data] (f env data nil nil))
                   ([env data e-name e-data] (f env data e-name e-data)))}))

(def auth-routing-chart
  "Statechart governing:
   - :state/checking-auth -> initial token check
   - :state/unauthenticated -> public routes, login/register, protected route redirection
   - :state/authenticated -> protected routes, session loading, return-to navigation"
  (chart/statechart {:id machine-id
                     :initial :state/checking-auth}

    ;; -------------------------------------------------------------------------
    ;; 1. Initial State: Checking Authentication Token
    ;; -------------------------------------------------------------------------
    (state {:id :state/checking-auth}
      (transition {:event :event/token-valid
                   :target :state/authenticated})
      (transition {:event :event/no-token
                   :target :state/unauthenticated}))

    ;; -------------------------------------------------------------------------
    ;; 2. Unauthenticated State
    ;; -------------------------------------------------------------------------
    (state {:id :state/unauthenticated}
      (on-entry
        (script-action
          (fn [env _ _ e-data]
            (let [clear-fn (:clear-tokens-fn env)
                  redirect-fn (:redirect-fn env)
                  path (or (:path e-data)
                           (:current-path env)
                           (when (and (exists? js/window) (exists? js/window.location))
                             js/window.location.pathname)
                           "/")]
              (when clear-fn (clear-fn))
              (when (and redirect-fn (protected-path? path))
                ;; Redirect to /login and pass the attempted path as return-to
                (redirect-fn "/login" path))))))

      ;; Navigating while unauthenticated
      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (protected-path? (:path e-data)))
                   :target :state/unauthenticated
                   :content [(script-action
                               (fn [env _ _ e-data]
                                 (when-let [redirect-fn (:redirect-fn env)]
                                   (redirect-fn "/login" (:path e-data)))))]})

      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (not (protected-path? (:path e-data))))
                   :target :state/unauthenticated
                   :content [(script-action
                               (fn [env _ _ e-data]
                                 (when-let [sync-fn (:sync-route-fn env)]
                                   (sync-fn (:path e-data) false))))]})

      ;; Successful login moves into authenticated state
      (transition {:event :event/login-success
                   :target :state/authenticated}))

    ;; -------------------------------------------------------------------------
    ;; 3. Authenticated State
    ;; -------------------------------------------------------------------------
    (state {:id :state/authenticated}
      (on-entry
        (script-action
          (fn [env _ _ e-data]
            (let [sync-fn (:sync-route-fn env)
                  fetch-session (:fetch-session-fn env)
                  fetch-data (:fetch-page-data-fn env)
                  return-to (:return-to e-data)
                  path (or return-to
                           (:path e-data)
                           (when (and (exists? js/window) (exists? js/window.location))
                             js/window.location.pathname)
                           "/")]
              (when fetch-session (fetch-session))
              (when sync-fn (sync-fn path true))
              (when fetch-data (fetch-data path))))))

      ;; Navigating while authenticated
      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (public-path? (:path e-data)))
                   :target :state/authenticated
                   :content [(script-action
                               (fn [env _ _ _]
                                 (when-let [redirect-fn (:redirect-fn env)]
                                   (redirect-fn "/"))))]})

      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (not (public-path? (:path e-data))))
                   :target :state/authenticated
                   :content [(script-action
                               (fn [env _ _ e-data]
                                 (when-let [sync-fn (:sync-route-fn env)]
                                   (sync-fn (:path e-data) true))
                                 (when-let [fetch-data (:fetch-page-data-fn env)]
                                   (fetch-data (:path e-data)))))]})

      ;; Logout and 401 Auth Failure
      (transition {:event :event/logout
                   :target :state/unauthenticated})
      (transition {:event :event/auth-failure
                   :target :state/unauthenticated}))))

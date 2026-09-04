(ns com.ozimos.workforce.frontend.auth-statechart
  "Fulcro Statechart governing user authentication lifecycle, route protection,
   and unauthenticated redirection for the Workforce pure-Replicant frontend."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [on-entry script state transition]]
   [com.ozimos.workforce.frontend.routing :as routing]))

(def machine-id :auth-routing-machine)
(def default-session-id :auth-routing)

(defn public-path?
  "Returns true if the URL path does not require authentication.
   Delegates to routing/public-path? (SSOT)."
  [path]
  (routing/public-path? path))

(def protected-path?
  "Returns true if the URL path requires an active authenticated session.
   Delegates to routing/protected-path? (SSOT)."
  routing/protected-path?)

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
      (on-entry {}
        (script-action
          (fn [env _ _ e-data]
            (let [clear-fn (:clear-tokens-fn env)
                  clear-form-fn (:clear-form-fn env)
                  redirect-fn (:redirect-fn env)
                  path (or (:path e-data)
                           (:current-path env)
                           (when (and (exists? js/window) (exists? js/window.location))
                             (str js/window.location.pathname js/window.location.search))
                           "/")
                  target "/login"
                  return-to (when (protected-path? path) path)]
              (when clear-fn (clear-fn))
              (when clear-form-fn (clear-form-fn))
              ;; Option B: on-entry owns redirect unconditionally.
              ;; Transition :event/logout is server-only; this avoids duplicate
              ;; pushState and prevents return-to being set on explicit logout.
              ;; For guard (protected → /login) preserve return-to; for explicit
              ;; logout (e.g. from "/" ) still land on /login with nil return-to.
              (when (and redirect-fn (not= path target))
                (redirect-fn target return-to))))))

      ;; Navigating while unauthenticated
      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (protected-path? (:path e-data)))
                   :target :state/unauthenticated}
        (script-action
          (fn [env _ _ e-data]
            (when-let [redirect-fn (:redirect-fn env)]
              (redirect-fn "/login" (:path e-data))))))

      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (not (protected-path? (:path e-data))))
                   :target :state/unauthenticated}
        (script-action
          (fn [env _ _ e-data]
            (when-let [sync-fn (:sync-route-fn env)]
              (sync-fn (:path e-data) false)))))

      ;; Successful login moves into authenticated state
      (transition {:event :event/login-success
                   :target :state/authenticated}))

    ;; -------------------------------------------------------------------------
    ;; 3. Authenticated State
    ;; -------------------------------------------------------------------------
    (state {:id :state/authenticated}
      (on-entry {}
        (script-action
          (fn [env _ _ e-data]
            (let [sync-fn (:sync-route-fn env)
                  fetch-session (:fetch-session-fn env)
                  fetch-data (:fetch-page-data-fn env)
                  return-to (:return-to e-data)
                  raw-path (or return-to
                               (:path e-data)
                               (when (and (exists? js/window) (exists? js/window.location))
                                 (str js/window.location.pathname js/window.location.search))
                               "/")
                  verified? (or (:verified env)
                                (:verified? env)
                                (when (and (exists? js/localStorage)
                                           (= "true" (.getItem js/localStorage "verified")))
                                  true))
                  ;; If no return-to and current public should redirect (verify only when verified)
                  path (if (and (nil? return-to)
                                (routing/should-redirect-public? raw-path verified?))
                         "/"
                         raw-path)]
              (when fetch-session (fetch-session))
              (when sync-fn (sync-fn path true))
              (when fetch-data (fetch-data path))))))

      ;; Navigating while authenticated - public routes bounce to home (verify only when verified)
      (transition {:event :event/navigate
                   :cond (fn [env _ _ e-data]
                           (let [verified? (or (:verified env)
                                               (:verified? env)
                                               (when (and (exists? js/localStorage)
                                                          (= "true" (.getItem js/localStorage "verified")))
                                                 true))]
                             (routing/should-redirect-public? (:path e-data) verified?)))
                   :target :state/authenticated}
        (script-action
          (fn [env _ _ _]
            (when-let [sync-fn (:sync-route-fn env)]
              (sync-fn "/" true)))))

      (transition {:event :event/navigate
                   :cond (fn [_ _ _ e-data] (not (public-path? (:path e-data))))
                   :target :state/authenticated}
        (script-action
          (fn [env _ _ e-data]
            (when-let [sync-fn (:sync-route-fn env)]
              (sync-fn (:path e-data) true))
            (when-let [fetch-data (:fetch-page-data-fn env)]
              (fetch-data (:path e-data))))))

      ;; Logout and 401 Auth Failure — Option B: server-only, on-entry owns clear/redirect
      (transition {:event :event/logout
                   :target :state/unauthenticated}
        (script-action
          (fn [env _ _ _]
            (when-let [server-logout (:server-logout-fn env)]
              (server-logout)))))
      (transition {:event :event/auth-failure
                   :target :state/unauthenticated}))))

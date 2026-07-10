(ns com.ozimos.auth.auth_api.routes
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.parameters :as parameters]
            [muuntaja.core :as m]
            [com.ozimos.auth.auth_api.handlers :as handlers]
            [com.ozimos.auth.auth_api.middleware :as mw]
            [com.ozimos.auth.schema.interface.registration :as reg-schema]
            [reitit.coercion.malli :as rcm]))

(defn router
  "Build the reitit router with all auth routes.
   `deps` contains component instances needed by handlers."
  [deps]
  (ring/router
   [["/api"
     ["/auth"
      ["/register"
       {:post {:summary "Register a new user"
               :parameters {:body reg-schema/register-request}
               :handler (handlers/register deps)
               :responses {201 {:body reg-schema/user-response}
                           409 {:body [:map [:errors [:map]]]}}}}]
      ["/login"
       {:post {:summary "Login with username/password"
               :parameters {:body reg-schema/login-request}
               :handler (handlers/login deps)
               :responses {200 {:body reg-schema/token-response}
                           401 {:body [:map [:errors [:map]]]}}}}]
      ["/refresh"
       {:post {:summary "Refresh access token"
               :parameters {:body reg-schema/refresh-request}
               :handler (handlers/refresh deps)
               :responses {200 {:body reg-schema/token-response}
                           401 {:body [:map [:errors [:map]]]}}}}]
      ["/logout"
       {:post {:summary "Logout (revoke current session)"
               :middleware [mw/wrap-authenticated deps]
               :handler (handlers/logout deps)
               :responses {200 {:body [:map [:message :string]]}}}}]
      ["/logout-everywhere"
       {:post {:summary "Logout from all devices"
               :middleware [mw/wrap-authenticated deps]
               :handler (handlers/logout-everywhere deps)
               :responses {200 {:body [:map [:message :string]]}}}}]
      ["/verify"
       {:post {:summary "Verify account with token"
               :parameters {:body reg-schema/verify-request}
               :handler (handlers/verify deps)
               :responses {200 {:body [:map [:message :string]]}
                           400 {:body [:map [:errors [:map]]]}}}}]
      ["/forgot-password"
       {:post {:summary "Request password reset email"
               :parameters {:body reg-schema/forgot-password-request}
               :handler (handlers/forgot-password deps)
               :responses {200 {:body [:map [:message :string]]}}}}]
      ["/reset-password"
       {:post {:summary "Reset password with token"
               :parameters {:body reg-schema/reset-password-request}
               :handler (handlers/reset-password deps)
               :responses {200 {:body [:map [:message :string]]}
                           400 {:body [:map [:errors [:map]]]}}}}]]
     ["/health"
      {:get {:summary "Health check"
             :handler (handlers/health)}}]]
    {:data {:muuntaja m/instance
            :coercion rcm/coercion
            :middleware [parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         muuntaja/format-request-middleware
                         exception/exception-middleware]}}]))

(defn app
  "Build the Ring handler from the router."
  [deps]
  (ring/ring-handler
   (router deps)
   (fn [_] {:status 404 :body {:error "not found"}})))
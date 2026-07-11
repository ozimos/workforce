(ns com.ozimos.auth.auth-api.routes
  (:require
   [com.ozimos.auth.schema.interface.registration :as reg-schema]
   [muuntaja.core :as m]
   [reitit.coercion.malli :as rcm]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (if (string? body) body (pr-str body))})

(defn- echo-handler
  "Echo handler for Milestone B. Returns the validated body-params."
  [_]
  (fn [{:keys [body-params]}]
    (json-response 200 body-params)))

(defn- echo-register [_]
  (fn [{:keys [body-params]}]
    (json-response 201 body-params)))

(defn- echo-login [_]
  (fn [{:keys [body-params]}]
    (json-response 200 body-params)))

(defn health [_]
  (json-response 200 {"status" "ok"}))

(defn router
  "Build the reitit router with all auth routes.
   `deps` contains component instances needed by handlers.
   For Milestone B, deps is a map with :echo? true to use echo handlers."
  [deps]
  (ring/router
    [["/api"
      ["/auth"
       ["/register"
        {:post {:summary "Register a new user"
                :parameters {:body reg-schema/register-request}
                :handler (echo-register deps)
                :responses {201 {:body [:map [:echo [:map]] [:message :string]]}}}}]
       ["/login"
        {:post {:summary "Login with username/password"
                :parameters {:body reg-schema/login-request}
                :handler (echo-login deps)
                :responses {200 {:body [:map [:echo [:map]] [:message :string]]}}}}]
       ["/refresh"
        {:post {:summary "Refresh access token"
                :parameters {:body reg-schema/refresh-request}
                :handler (echo-handler deps)
                :responses {200 {:body [:map [:echo [:map]]]}}}}]
       ["/logout"
        {:post {:summary "Logout (revoke current session)"
                :handler (echo-handler deps)
                :responses {200 {:body [:map [:message :string]]}}}}]
       ["/logout-everywhere"
        {:post {:summary "Logout from all devices"
                :handler (echo-handler deps)
                :responses {200 {:body [:map [:message :string]]}}}}]
       ["/verify"
        {:post {:summary "Verify account with token"
                :parameters {:body reg-schema/verify-request}
                :handler (echo-handler deps)
                :responses {200 {:body [:map [:echo [:map]]]}}}}]
       ["/forgot-password"
        {:post {:summary "Request password reset email"
                :parameters {:body reg-schema/forgot-password-request}
                :handler (echo-handler deps)
                :responses {200 {:body [:map [:echo [:map]]]}}}}]
       ["/reset-password"
        {:post {:summary "Reset password with token"
                :parameters {:body reg-schema/reset-password-request}
                :handler (echo-handler deps)
                :responses {200 {:body [:map [:echo [:map]]]}}}}]]
      ["/health"
       {:get {:summary "Health check"
              :handler health}}]]]
    {:data {:muuntaja m/instance
            :coercion rcm/coercion
            :middleware [parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         (exception/create-exception-middleware
                           (merge exception/default-handlers
                                  {:reitit.coercion/request-coercion
                                   (exception/create-coercion-handler 400)}))
                         muuntaja/format-request-middleware
                         coercion/coerce-request-middleware]}}))

(defn app
  "Build the Ring handler from the router."
  [deps]
  (ring/ring-handler
    (router deps)
    (fn [_] {:status 404 :body {:error "not found"}})))

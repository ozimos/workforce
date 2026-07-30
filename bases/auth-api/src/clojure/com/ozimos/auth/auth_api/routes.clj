(ns com.ozimos.auth.auth-api.routes
  (:require
   [clojure.java.io :as io]
   [com.ozimos.auth.auth-api.handlers :as handlers]
   [com.ozimos.auth.schema.interface.registration :as reg-schema]
   [muuntaja.core :as m]
   [reitit.coercion.malli :as rcm]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.resource :refer [wrap-resource]]))

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
                         :handler (handlers/register deps)
                         :responses {201 {:body [:map [:id int?] [:username {:optional true} :string] [:email :string] [:verified boolean?]]}
                                     409 {:body [:map [:errors [:map]]]}}}}]
       ["/login"
        {:post {:summary "Login with email/username and password"
                :parameters {:body reg-schema/login-request}
                :handler (handlers/login deps)
                :responses {200 {:body [:map [:access-token :string] [:refresh-token :string] [:expires-in int?]]}
                            401 {:body [:map [:errors [:map]]]}}}}]
       ["/refresh"
        {:post {:summary "Refresh access token"
                :parameters {:body reg-schema/refresh-request}
                :handler (handlers/refresh deps)
                :responses {200 {:body [:map [:access-token :string] [:refresh-token :string] [:expires-in int?]]}
                            401 {:body [:map [:errors [:map]]]}}}}]
       ["/logout"
        {:post {:summary "Logout (revoke current session)"
                :handler (handlers/logout deps)
                :responses {200 {:body [:map [:message :string]]}}}}]
       ["/logout-everywhere"
        {:post {:summary "Logout from all devices"
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
                            400 {:body [:map [:errors [:map]]]}}}}]
       ["/profile/username"
        {:post {:summary "Update username"
                :parameters {:body reg-schema/update-username-request}
                :handler (handlers/update-username deps)
                :responses {200 {:body [:map [:username :string]]}
                            409 {:body [:map [:errors [:map]]]}}}}]]
      ["/query"
       {:post {:summary "Pathom query endpoint (app logic)"
               :handler (handlers/query deps)
               :responses {200 {:body [:map [:ok boolean?] [:query :any]]}}}}]
      ["/health"
       {:get {:summary "Health check"
              :handler handlers/health}}]]]
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

(defn- wrap-spa [handler]
  (fn [request]
    (let [response (handler request)]
      (if (and (= 404 (:status response))
               (not (.startsWith (:uri request) "/api/")))
        (if-let [resource (io/resource "public/index.html")]
          {:status 200
           :headers {"Content-Type" "text/html"}
           :body (slurp resource)}
          response)
        response))))

(defn app
  "Build the Ring handler from the router."
  [deps]
  (-> (ring/ring-handler
        (router deps)
        (wrap-resource
          (fn [_] {:status 404 :body {:error "not found"}})
          "public"))
      (wrap-spa)))

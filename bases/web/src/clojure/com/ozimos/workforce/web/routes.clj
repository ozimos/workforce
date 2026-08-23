(ns com.ozimos.workforce.web.routes
  (:require
   [clojure.java.io :as io]
   [com.ozimos.omni-auth.schema.interface.registration :as reg-schema]
   [com.ozimos.workforce.web.handlers :as handlers]
   [muuntaja.core :as m]
   [reitit.coercion.malli :as rcm]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.resource :refer [wrap-resource]]))

(defn wrap-inject-system
  "Reitit compile-time middleware that injects `system` into every incoming request map as `:system`."
  [system]
  {:name ::inject-system
   :compile (fn [_ _]
              (fn [handler]
                (fn [request]
                  (handler (assoc request :system system)))))})

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
                :handler handlers/register}}]
       ["/login"
        {:post {:summary "Login with email/username and password"
                :parameters {:body reg-schema/login-request}
                :handler handlers/login}}]
       ["/refresh"
        {:post {:summary "Refresh access token"
                :parameters {:body reg-schema/refresh-request}
                :handler handlers/refresh}}]
       ["/logout"
        {:post {:summary "Logout (revoke current session)"
                :handler handlers/logout}}]
       ["/logout-everywhere"
        {:post {:summary "Logout from all devices"
                :handler handlers/logout-everywhere}}]
       ["/verify"
        {:post {:summary "Verify account with token"
                :parameters {:body reg-schema/verify-request}
                :handler handlers/verify}}]
       ["/forgot-password"
        {:post {:summary "Request password reset email"
                :parameters {:body reg-schema/forgot-password-request}
                :handler handlers/forgot-password}}]
       ["/reset-password"
        {:post {:summary "Reset password with token"
                :parameters {:body reg-schema/reset-password-request}
                :handler handlers/reset-password}}]
       ["/mfa"
        ["/setup"
         {:post {:summary "Generate TOTP MFA secret and QR URL"
                 :handler handlers/mfa-setup}}]
        ["/verify-setup"
         {:post {:summary "Verify 6-digit TOTP code and enable MFA"
                 :parameters {:body reg-schema/mfa-verify-setup-request}
                 :handler handlers/mfa-verify-setup}}]
        ["/login"
         {:post {:summary "Verify 2FA challenge token + TOTP/backup code to complete login"
                 :parameters {:body reg-schema/mfa-login-request}
                 :handler handlers/mfa-login}}]
        ["/disable"
         {:post {:summary "Disable MFA using TOTP or backup code"
                 :parameters {:body reg-schema/mfa-disable-request}
                 :handler handlers/mfa-disable}}]
        ["/backup-codes"
         {:get {:summary "Get remaining backup codes count"
                :handler handlers/mfa-backup-codes-status}
          :post {:summary "Regenerate 10 new backup codes (requires valid TOTP or backup code)"
                 :parameters {:body reg-schema/mfa-disable-request}
                 :handler handlers/mfa-backup-codes-regenerate}}]]
       ["/passkeys"
        [""
         {:get {:summary "List user registered passkeys"
                :handler handlers/passkey-list}}]
        ["/register"
         ["/begin"
          {:post {:summary "Begin passkey registration"
                  :handler handlers/passkey-register-begin}}]
         ["/finish"
          {:post {:summary "Finish passkey registration"
                  :handler handlers/passkey-register-finish}}]]
        ["/authenticate"
         ["/begin"
          {:post {:summary "Begin passkey authentication"
                  :handler handlers/passkey-authenticate-begin}}]]
        ["/:credential-id"
         {:delete {:summary "Delete a registered passkey"
                   :handler handlers/passkey-delete}}]]
       ["/oauth"
        ["/:provider/authorize"
         {:get {:summary "Initiate OAuth2 authorization flow"
                :handler handlers/oauth-authorize}}]
        ["/:provider/callback"
         {:get {:summary "OAuth2 provider callback handler"
                :handler handlers/oauth-callback}
          :post {:summary "OAuth2 provider callback handler via POST"
                 :handler handlers/oauth-callback}}]]
       ["/saml"
        ["/authenticate"
         {:get {:summary "Initiate SAML authentication"
                :handler handlers/saml-authenticate}}]
        ["/acs"
         {:post {:summary "SAML Assertion Consumer Service"
                 :handler handlers/saml-acs}}]]]
      ["/query"
       {:post {:summary "Pathom query endpoint (app logic)"
               :handler handlers/query}}]
      ["/health"
       {:get {:summary "Health check"
              :handler handlers/health}}]]]
    {:data {:muuntaja m/instance
            :coercion rcm/coercion
            :middleware [(wrap-inject-system deps)
                         parameters/parameters-middleware
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

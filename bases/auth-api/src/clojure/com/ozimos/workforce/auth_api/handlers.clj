(ns com.ozimos.workforce.auth-api.handlers
  (:require
   [clojure.edn :as edn]
   [com.ozimos.workforce.mfa.interface :as mfa]
   [com.ozimos.workforce.oauth.interface :as oauth]
   [com.ozimos.workforce.pathom.interface :as pathom]
   [com.ozimos.workforce.revocation.interface :as revocation]
   [com.ozimos.workforce.saml.interface :as saml]
   [com.ozimos.workforce.schema.interface :as schema]
   [com.ozimos.workforce.schema.interface.registration :as reg-schema]
   [com.ozimos.workforce.session.interface :as session]
   [com.ozimos.workforce.token.interface :as token]
   [com.ozimos.workforce.user.interface :as user]
   [com.ozimos.workforce.webauthn.interface :as webauthn]
   [jsonista.core :as json]
   [malli.core :as m])
  (:import
   (java.util UUID)))

(defn- parse-user-id
  "Parse a user-id string to Long, returning nil on parse failure."
  [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

(defn- get-decoder [token-decoder]
  (or (:decoder token-decoder) token-decoder))

(defn- get-encoder [token-encoder]
  (or (:encoder token-encoder) token-encoder))

(defn- get-auth-user
  "Extract authenticated user info from the JWT in the Authorization header."
  [request token-decoder]
  (let [header (or (get-in request [:headers "authorization"])
                   (get-in request [:headers :authorization])
                   (get-in request [:headers "Authorization"]))
        token (when header (second (re-find #"(?i)Bearer\s+(.+)" header)))]
    (when token
      (try
        (let [decoder (get-decoder token-decoder)
              jwt (.decode ^org.springframework.security.oauth2.jwt.JwtDecoder decoder token)
              sub (.getSubject jwt)
              roles (.getClaim jwt "roles")]
          (when-let [user-id (parse-user-id sub)]
            {:user-id user-id
             :roles roles
             :jti (.getId jwt)}))
        (catch Exception _ nil)))))

(defn- issue-user-session-tokens
  "Helper to issue JWT access + refresh tokens and record active session for user-record."
  [system user-record]
  (let [token-encoder (:token-encoder system)
        encoder (get-encoder token-encoder)
        issuer "com.ozimos.workforce"
        user-id (:id user-record)
        sub (str user-id)
        roles (:roles user-record)
        active-org-id (user/get-active-org system user-id)
        active-org-role (when active-org-id
                          (:role (user/get-membership system user-id active-org-id)))
        access-jti (str (random-uuid))
        refresh-jti (str (random-uuid))
        access-ttl 900
        refresh-ttl 604800
        access-token (if (and active-org-id active-org-role)
                       (token/issue-access-token encoder issuer sub roles access-jti access-ttl active-org-id active-org-role)
                       (token/issue-access-token encoder issuer sub roles access-jti access-ttl))
        refresh-token (token/issue-refresh-token encoder issuer sub refresh-jti refresh-ttl)
        expires-at (+ (System/currentTimeMillis) (* refresh-ttl 1000))
        user-info (select-keys user-record [:id :username :email :verified])]
    (session/create! system user-id access-jti expires-at)
    (merge user-info
           {:access-token access-token
            :refresh-token refresh-token
            :expires-in access-ttl
            :user user-info})))

(defn register
  [{:keys [body-params system]}]
  (let [result (user/register! system body-params)
        mode (get-in system [:policy :verification-mode] :soft)]
    (if (first result)
      (let [u (second result)]
        (if (= mode :soft)
          {:status 201
           :body (issue-user-session-tokens system u)}
          {:status 201
           :body {:id (:id u)
                  :username (:username u)
                  :email (:email u)
                  :verified false
                  :verification-required true}}))
      {:status 409
       :body (second result)})))

(defn login
  [{:keys [body-params system]}]
  (let [{:keys [token-encoder policy]} system
        {:keys [identifier password]} body-params
        encoder (get-encoder token-encoder)
        user-record (user/find-by-identifier system identifier)
        mode (get policy :verification-mode :soft)]
    (if (and user-record
             (user/matches-password? system password (:pwd-hash user-record)))
      (if (and (= mode :strict) (not (:verified user-record)))
        {:status 403
          :body {:errors {:auth ["Email verification required before logging in."]}}}
        (let [issuer "com.ozimos.workforce"
              sub (str (:id user-record))]
          (if (user/mfa-enabled? system (:id user-record))
            (let [mfa-token (token/issue-mfa-challenge-token encoder issuer sub 300)]
              {:status 200
               :body {:mfa-required true
                      :mfa-token mfa-token}})
            {:status 200
             :body (issue-user-session-tokens system user-record)})))
      {:status 401
        :body {:errors {:credentials ["Invalid username/email or password"]}}})))

(defn refresh
  [{:keys [body-params system]}]
  (let [{:keys [token-decoder token-encoder]} system
        {:keys [refresh-token]} body-params
        decoder (get-decoder token-decoder)
        encoder (get-encoder token-encoder)]
    (try
      (let [jwt (token/decode decoder refresh-token)
            jti (.getId jwt)
            sub (.getSubject jwt)
            type (.getClaim jwt "type")]
        (if (not= type "refresh")
          {:status 401 :body {:errors {:token ["Not a refresh token"]}}}
          (if (revocation/is-revoked? system jti)
            {:status 401 :body {:errors {:token ["Token revoked"]}}}
            (if-let [parsed-id (parse-user-id sub)]
              (let [user-record (user/find-by-id system parsed-id)
                    roles (:roles user-record)
                    active-org-id (user/get-active-org system parsed-id)
                    active-org-role (when active-org-id
                                      (:role (user/get-membership system parsed-id active-org-id)))
                    new-access-jti (str (random-uuid))
                    new-refresh-jti (str (random-uuid))
                    issuer "com.ozimos.workforce"
                    access-token (if (and active-org-id active-org-role)
                                   (token/issue-access-token encoder issuer sub roles new-access-jti 900 active-org-id active-org-role)
                                   (token/issue-access-token encoder issuer sub roles new-access-jti 900))
                    new-refresh-token (token/issue-refresh-token encoder issuer sub new-refresh-jti 604800)]
                (revocation/revoke! system jti (.. jwt getExpiresAt toEpochMilli))
                {:status 200
                 :body {:access-token access-token
                        :refresh-token new-refresh-token
                        :expires-in 900}})
              {:status 401 :body {:errors {:token ["Invalid token"]}}}))))
      (catch Exception _
        {:status 401 :body {:errors {:token ["Invalid token"]}}}))))

(defn logout
  [{:keys [system] :as request}]
  (let [auth-user (get-auth-user request (:token-decoder system))]
    (when auth-user
      (revocation/revoke! system (:jti auth-user)
                          (+ (System/currentTimeMillis) (* 900 1000))))
    {:status 200 :body {:message "Logged out"}}))

(defn logout-everywhere
  [{:keys [system] :as request}]
  (let [auth-user (get-auth-user request (:token-decoder system))]
    (when auth-user
      (session/revoke-all! system (:user-id auth-user))
      (revocation/revoke-all-for-user! system (:user-id auth-user)))
    {:status 200 :body {:message "Logged out from all devices"}}))

(defn verify
  [{:keys [body-params system]}]
  (let [{:keys [user-id]} body-params]
    (if-let [parsed-id (parse-user-id user-id)]
      (if (user/verify! system parsed-id)
        {:status 200 :body {:message "Account verified"}}
        {:status 400 :body {:errors {:user-id ["Invalid user-id"]}}})
      {:status 400 :body {:errors {:user-id ["Invalid user-id"]}}})))

(defn forgot-password
  [{:keys [body-params system]}]
  (let [{:keys [email]} body-params
        user-record (user/find-by-email system email)]
    (when user-record
      (user/create-reset-token! system (:id user-record)))
    {:status 200 :body {:message "If an account exists with this email, password reset instructions have been sent."}}))

(defn reset-password
  [{:keys [body-params system]}]
  (let [{:keys [token password]} body-params
        user-id (try
                  (user/validate-reset-token system token)
                  (catch Exception _ :expired))]
    (cond
      (= user-id :expired)
      {:status 400 :body {:errors {:token ["Reset token has expired"]}}}
      (nil? user-id)
      {:status 400 :body {:errors {:token ["Invalid reset token"]}}}
      :else
      (let [encoded (user/encode-password system password)]
        (user/change-password! system user-id encoded)
        (user/clear-reset-token! system token)
        (session/revoke-all! system user-id)
        (revocation/revoke-all-for-user! system user-id)
        {:status 200 :body {:message "Password updated successfully"}}))))

(defn query
  [{:keys [body-params system] :as request}]
  (let [{:keys [token-decoder]} system
        query-data (cond
                     (vector? body-params) body-params
                     (:query body-params) (:query body-params)
                     (string? (:eql body-params)) (edn/read-string (:eql body-params))
                     :else (:eql body-params))
        auth-user (get-auth-user request token-decoder)
        active-org-id (when auth-user (user/get-active-org system (:user-id auth-user)))
        active-org-role (when (and auth-user active-org-id)
                          (:role (user/get-membership system (:user-id auth-user) active-org-id)))
        auth (when auth-user {:user-id (:user-id auth-user)
                              :current-user (assoc auth-user :id (:user-id auth-user))
                              :active-org-id active-org-id
                              :active-org-role active-org-role})
        env (pathom/build-env system auth)]
    (try
      (let [result (pathom/process env query-data)]
        {:status 200 :body {:ok true :data result}})
      (catch clojure.lang.ExceptionInfo e
        (let [error-type (some #(-> % ex-data :type)
                               (take-while some? (iterate #(.getCause ^Throwable %) e)))]
          (case error-type
            :unauthenticated {:status 401 :body {:ok false :errors {:auth ["Not authenticated"]}}}
            :forbidden {:status 403 :body {:ok false :errors {:auth ["Not authorized"]}}}
            {:status 400 :body {:ok false :errors {:query [(.getMessage e)]}}})))
      (catch Exception e
        {:status 400 :body {:ok false :errors {:query [(.getMessage e)]}}}))))

(defn mfa-setup
  [{:keys [system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [user-record (user/find-by-id system (:user-id auth-user))
          secret (mfa/generate-secret)
          encrypted-secret (mfa/encrypt-secret secret)
          {:keys [plaintext hashes]} (mfa/generate-backup-codes)
          otpauth-url (mfa/generate-otpauth-url secret (or (:email user-record) "user@example.com") "BestAuth")]
      (user/setup-mfa! system (:user-id auth-user) encrypted-secret hashes)
      {:status 200
       :body {:secret secret
              :otpauth-url otpauth-url
              :backup-codes plaintext}})
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn mfa-verify-setup
  [{:keys [body-params system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [{:keys [code]} body-params
          encrypted-secret (user/get-mfa-secret system (:user-id auth-user))
          secret (when encrypted-secret (mfa/decrypt-secret encrypted-secret))
          valid? (boolean (and secret (mfa/verify-totp secret code)))]
      (if valid?
        (do
          (user/verify-mfa-setup! system (:user-id auth-user))
          {:status 200 :body {:message "MFA enabled successfully"}})
        {:status 400 :body {:errors {:code ["Invalid 6-digit TOTP code"]}}}))
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn mfa-login
  [{:keys [body-params system]}]
  (let [{:keys [token-decoder token-encoder]} system
        {:keys [mfa-token code]} body-params
        decoder (get-decoder token-decoder)
        encoder (get-encoder token-encoder)]
    (try
      (let [jwt (token/decode decoder mfa-token)
            type (.getClaim jwt "type")
            sub (.getSubject jwt)
            parsed-id (parse-user-id sub)]
        (if (or (not= type "mfa-challenge") (nil? parsed-id))
          {:status 401 :body {:errors {:mfa-token ["Invalid 2FA challenge token"]}}}
          (let [encrypted-secret (user/get-mfa-secret system parsed-id)
                secret (when encrypted-secret (mfa/decrypt-secret encrypted-secret))
                backup-hashes (user/get-mfa-backup-codes system parsed-id)
                totp-valid? (and secret (mfa/verify-totp secret code))
                matching-backup-hash (when (and (not totp-valid?) (seq backup-hashes))
                                       (mfa/verify-backup-code code backup-hashes))]
            (if (or totp-valid? matching-backup-hash)
              (do
                (when matching-backup-hash
                  (user/consume-mfa-backup-code! system parsed-id matching-backup-hash))
                (let [user-record (user/find-by-id system parsed-id)
                      issuer "com.ozimos.workforce"
                      roles (:roles user-record)
                      active-org-id (user/get-active-org system parsed-id)
                      active-org-role (when active-org-id
                                        (:role (user/get-membership system parsed-id active-org-id)))
                      access-jti (str (random-uuid))
                      refresh-jti (str (random-uuid))
                      access-ttl 900
                      refresh-ttl 604800
                      access-token (if (and active-org-id active-org-role)
                                     (token/issue-access-token encoder issuer sub roles access-jti access-ttl active-org-id active-org-role)
                                     (token/issue-access-token encoder issuer sub roles access-jti access-ttl))
                      refresh-token (token/issue-refresh-token encoder issuer sub refresh-jti refresh-ttl)
                      expires-at (+ (System/currentTimeMillis) (* refresh-ttl 1000))]
                  (session/create! system parsed-id access-jti expires-at)
                  {:status 200
                   :body {:access-token access-token
                          :refresh-token refresh-token
                          :expires-in access-ttl
                          :user {:id (:id user-record)
                                 :username (:username user-record)
                                 :email (:email user-record)}}}))
              {:status 401 :body {:errors {:code ["Invalid 6-digit TOTP code or backup code"]}}}))))
      (catch Exception _
        {:status 401 :body {:errors {:mfa-token ["Invalid 2FA challenge token"]}}}))))

(defn mfa-disable
  [{:keys [body-params system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [{:keys [code]} body-params
          user-id (:user-id auth-user)
          encrypted-secret (user/get-mfa-secret system user-id)
          secret (when encrypted-secret (mfa/decrypt-secret encrypted-secret))
          backup-hashes (user/get-mfa-backup-codes system user-id)
          totp-valid? (and secret (mfa/verify-totp secret code))
          matching-backup-hash (when (and (not totp-valid?) (seq backup-hashes))
                                 (mfa/verify-backup-code code backup-hashes))]
      (if (or totp-valid? matching-backup-hash)
        (do
          (user/disable-mfa! system user-id)
          {:status 200 :body {:message "MFA disabled successfully"}})
        {:status 400 :body {:errors {:code ["Invalid 6-digit TOTP code or backup code"]}}}))
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn mfa-backup-codes-status
  [{:keys [system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [cnt (user/count-mfa-backup-codes system (:user-id auth-user))]
      {:status 200 :body {:remaining cnt}})
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn mfa-backup-codes-regenerate
  [{:keys [body-params system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [{:keys [code]} body-params
          user-id (:user-id auth-user)
          encrypted-secret (user/get-mfa-secret system user-id)
          secret (when encrypted-secret (mfa/decrypt-secret encrypted-secret))
          backup-hashes (user/get-mfa-backup-codes system user-id)
          totp-valid? (and secret (mfa/verify-totp secret code))
          matching-backup-hash (when (and (not totp-valid?) (seq backup-hashes))
                                 (mfa/verify-backup-code code backup-hashes))]
      (if (or totp-valid? matching-backup-hash)
        (let [{:keys [plaintext hashes]} (mfa/generate-backup-codes)]
          (user/regenerate-mfa-backup-codes! system user-id hashes)
          {:status 200 :body {:backup-codes plaintext}})
        {:status 400 :body {:errors {:code ["Invalid 6-digit TOTP code or backup code"]}}}))
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn passkey-register-begin
  [{:keys [system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [user-record (user/find-by-id system (:user-id auth-user))
          rp (webauthn/make-relying-party {:rp-id "localhost"
                                          :rp-name "BestAuth"
                                          :origins "http://localhost:8080"})
          creation-opts (webauthn/start-registration-options rp (:user-id auth-user) (:username user-record) (:email user-record))
          opts-json (webauthn/creation-options-to-json creation-opts)]
      {:status 200
       :body {:options (json/read-value opts-json json/keyword-keys-object-mapper)
              :options-json opts-json}})
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn passkey-register-finish
  [{:keys [body-params system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [{:keys [options-json response-json nickname]} body-params
          rp (webauthn/make-relying-party {:rp-id "localhost"
                                          :rp-name "BestAuth"
                                          :origins "http://localhost:8080"})]
      (try
        (let [result (webauthn/finish-registration rp options-json response-json)
              {:keys [credential-id public-key-cose sign-count user-handle]} result]
          (user/register-passkey! system (:user-id auth-user) credential-id public-key-cose sign-count user-handle (or nickname "Passkey"))
          {:status 200 :body {:message "Passkey registered successfully" :credential-id credential-id}})
        (catch Exception e
          {:status 400 :body {:errors {:passkey [(.getMessage e)]}}})))
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn passkey-authenticate-begin
  [{:keys [system]}]
  (let [rp (webauthn/make-relying-party {:rp-id "localhost"
                                          :rp-name "BestAuth"
                                          :origins "http://localhost:8080"})
        assertion-req (webauthn/start-assertion-options rp)
        req-json (webauthn/assertion-request-to-json assertion-req)]
    {:status 200
     :body {:request (json/read-value req-json json/keyword-keys-object-mapper)
            :request-json req-json}}))

(defn passkey-list
  [{:keys [system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [passkeys (user/list-passkeys-for-user system (:user-id auth-user))]
      {:status 200 :body {:passkeys passkeys}})
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn passkey-delete
  [{:keys [path-params system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [cred-id (:credential-id path-params)]
      (user/remove-passkey! system (:user-id auth-user) cred-id)
      {:status 200 :body {:message "Passkey deleted successfully"}})
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn oauth-authorize
  [{:keys [path-params]}]
  (let [provider (:provider path-params)]
    {:status 302
     :headers {"Location" (str "/api/auth/oauth/" provider "/callback?code=mock-code-123")}}))

(defn oauth-callback
  [{:keys [path-params params body-params system]}]
  (let [provider (:provider path-params)
        ;; Extract oauth user info from query params, body params or mock default
        provider-user-id (or (get params "sub") (get body-params :sub) (get params "provider_user_id") (get body-params :provider_user_id) "mock-sub-999")
        email (or (get params "email") (get body-params :email) (str provider-user-id "@" provider ".com"))
        name (or (get params "name") (get body-params :name) "OAuth User")
        [ok? result] (oauth/handle-oauth-callback system provider {:provider-user-id provider-user-id
                                                      :email email
                                                      :name name})]
    (if ok?
      {:status 200 :body result}
      {:status 400 :body result})))

(defn saml-authenticate
  [_]
  {:status 302
   :headers {"Location" "/api/auth/saml/sso-login-mock"}})

(defn saml-acs
  [{:keys [params body-params system]}]
  (let [name-id (or (get params "name-id") (get body-params :name-id) (get params "NameID") (get body-params :NameID) "saml-mock-user-1")
        email (or (get params "email") (get body-params :email) (str name-id "@saml-provider.com"))
        name (or (get params "name") (get body-params :name) "SAML User")
        [ok? result] (saml/handle-saml-assertion system {:name-id name-id
                                                        :email email
                                                        :name name})]
    (if ok?
      {:status 200 :body result}
      {:status 400 :body result})))

(defn health [_]
  {:status 200 :body {:status "ok"}})


(ns com.ozimos.auth.auth-api.handlers
  (:require
   [clojure.edn :as edn]
   [com.ozimos.auth.mfa.interface :as mfa]
   [com.ozimos.auth.pathom.interface :as pathom]
   [com.ozimos.auth.revocation.interface :as revocation]
   [com.ozimos.auth.schema.interface :as schema]
   [com.ozimos.auth.schema.interface.registration :as reg-schema]
   [com.ozimos.auth.session.interface :as session]
   [com.ozimos.auth.token.interface :as token]
   [com.ozimos.auth.user.interface :as user]
   [com.ozimos.auth.webauthn.interface :as webauthn]
   [jsonista.core :as json]
   [malli.core :as m])
  (:import
   (java.util UUID)))

(defn- parse-user-id
  "Parse a user-id string to Long, returning nil on parse failure."
  [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

(defn- get-auth-user
  "Extract authenticated user info from the JWT in the Authorization header."
  [request token-decoder]
  (when-let [header (get-in request [:headers "authorization"])]
    (when-let [token (second (re-find #"(?i)Bearer\s+(.+)" header))]
      (try
        (let [jwt (.decode (:decoder token-decoder) token)
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
  (let [{:keys [token-encoder]} system
        {:keys [encoder]} token-encoder
        issuer "com.ozimos.auth"
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
        expires-at (+ (System/currentTimeMillis) (* refresh-ttl 1000))]
    (session/create! system user-id access-jti expires-at)
    {:access-token access-token
     :refresh-token refresh-token
     :expires-in access-ttl
     :user (select-keys user-record [:id :username :email :verified])}))

(defn register
  [{:keys [body-params system]}]
  (let [result (user/register! system body-params)]
    (if (first result)
      (let [u (second result)
            session-data (issue-user-session-tokens system u)]
        {:status 201
         :body session-data})
      {:status 409
       :body (second result)})))

(defn login
  [{:keys [body-params system]}]
  (let [{:keys [token-encoder]} system
        {:keys [identifier password]} body-params
        {:keys [encoder]} token-encoder
        user-record (user/find-by-identifier system identifier)]
    (if (and user-record
             (user/matches-password? system password (:pwd-hash user-record)))
      (let [issuer "com.ozimos.auth"
            sub (str (:id user-record))]
        (if (user/mfa-enabled? system (:id user-record))
          (let [mfa-token (token/issue-mfa-challenge-token encoder issuer sub 300)]
            {:status 200
             :body {:mfa-required true
                    :mfa-token mfa-token}})
          {:status 200
           :body (issue-user-session-tokens system user-record)}))
      {:status 401
       :body {:errors {:credentials ["Invalid username/email or password"]}}})))

(defn refresh
  [{:keys [body-params system]}]
  (let [{:keys [token-decoder token-encoder]} system
        {:keys [refresh-token]} body-params
        {:keys [decoder]} token-decoder
        {:keys [encoder]} token-encoder]
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
                    issuer "com.ozimos.auth"
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
    {:status 200 :body {:message "If the email exists, a reset link has been sent"}}))

(defn reset-password
  [{:keys [body-params system]}]
  (let [{:keys [token password]} body-params]
    (try
      (if-let [user-id (user/validate-reset-token system token)]
        (let [pwd-hash (user/encode-password system password)]
          (user/change-password! system user-id pwd-hash)
          (user/clear-reset-token! system token)
          {:status 200 :body {:message "Password reset successfully"}})
        {:status 400 :body {:errors {:token ["Invalid or expired reset token"]}}})
      (catch Exception e
        (if (instance? clojure.lang.ExceptionInfo e)
          {:status 400 :body {:errors {:token [(.getMessage e)]}}}
          (throw e))))))

(defn query
  [{:keys [body-params system] :as request}]
  (let [{:keys [token-decoder]} system
        base-env (or (:pathom-env system) (pathom/build-env system))
        query (cond
                (:query body-params) (:query body-params)
                (string? (:eql body-params)) (edn/read-string (:eql body-params))
                :else (:eql body-params))
        auth-user (get-auth-user request token-decoder)
        auth (when auth-user {:user-id (:user-id auth-user)})
        env (if auth
              (assoc base-env :auth auth)
              base-env)]
    (try
      (let [result (pathom/process env query)]
        {:status 200 :body {:ok true :data result}})
      (catch clojure.lang.ExceptionInfo e
        (let [error-type (some #(-> % ex-data :type)
                               (take-while some? (iterate #(.getCause ^Throwable %) e)))]
          (case error-type
            :unauthenticated {:status 401 :body {:errors {:auth ["Not authenticated"]}}}
            :forbidden {:status 403 :body {:errors {:auth ["Not authorized"]}}}
            {:status 400 :body {:errors {:query [(.getMessage e)]}}})))
      (catch Exception e
        {:status 400 :body {:errors {:query [(.getMessage e)]}}}))))

(defn mfa-setup
  [{:keys [system] :as request}]
  (if-let [auth-user (get-auth-user request (:token-decoder system))]
    (let [user-record (user/find-by-id system (:user-id auth-user))
          secret (mfa/generate-secret)
          encrypted-secret (mfa/encrypt-secret secret)
          {:keys [plaintext hashes]} (mfa/generate-backup-codes)
          otpauth-url (mfa/generate-otpauth-url secret (:email user-record) "BestAuth")]
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
          encrypted-secret (user/get-mfa-secret system (:user-id auth-user))]
      (if (and encrypted-secret
               (let [secret (mfa/decrypt-secret encrypted-secret)]
                 (mfa/verify-totp secret code)))
        {:status 200 :body {:message "MFA enabled successfully"}}
        {:status 400 :body {:errors {:code ["Invalid 6-digit TOTP code"]}}}))
    {:status 401 :body {:errors {:auth ["Not authenticated"]}}}))

(defn mfa-login
  [{:keys [body-params system]}]
  (let [{:keys [token-decoder token-encoder]} system
        {:keys [mfa-token code]} body-params
        {:keys [decoder]} token-decoder
        {:keys [encoder]} token-encoder]
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
                      issuer "com.ozimos.auth"
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

(defn health [_]
  {:status 200 :body {:status "ok"}})

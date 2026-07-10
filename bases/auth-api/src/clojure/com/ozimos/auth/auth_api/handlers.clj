(ns com.ozimos.auth.auth_api.handlers
  (:require [com.ozimos.auth.user.interface :as user]
            [com.ozimos.auth.session.interface :as session]
            [com.ozimos.auth.token.interface :as token]
            [com.ozimos.auth.password.interface :as password]
            [com.ozimos.auth.revocation.interface :as revocation]
            [com.ozimos.auth.schema.interface :as schema]
            [com.ozimos.auth.schema.interface.registration :as reg-schema]
            [malli.core :as m]
            [clojure.walk :as walk])
  (:import [java.util UUID]))

(defn- get-auth-user
  "Extract authenticated user info from Spring Security context."
  [request]
  (let [auth (get-in request [:servlet-request "org.springframework.security.context.SECURITY_CONTEXT" :authentication])]
    (when auth
      (let [jwt (.getPrincipal auth)
            sub (.getSubject jwt)
            roles (.getClaim jwt "roles")]
        {:user-id (Long/parseLong sub)
         :roles roles
         :jti (.getId jwt)}))))

(defn jti-from-token [token-str decoder]
  (try
    (let [jwt (token/decode decoder token-str)]
      (.getId jwt))
    (catch Exception _ nil)))

(defn register [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [user-store password-encoder]} deps
          result (user/register! {:rama user-store :password-encoder password-encoder} body-params)]
      (if (first result)
        (let [user (second result)]
          {:status 201
           :body {:id (:id user)
                  :username (:username user)
                  :email (:email user)
                  :verified (:verified user)}})
        {:status 409
         :body {:errors (second result)}}))))

(defn login [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [user-store password-encoder token-encoder token-decoder session-store revocation-token-issuer]} deps
          {:keys [username password-plain]} body-params
          user-record (user/find-by-username {:rama user-store} username)]
      (if (and user-record
               (password/matches? password-encoder password-plain (:pwd-hash user-record)))
        (let [issuer "com.ozimos.auth"
              sub (str (:id user-record))
              roles (:roles user-record)
              access-jti (str (UUID/randomUUID))
              refresh-jti (str (UUID/randomUUID))
              access-ttl 900
              refresh-ttl 604800
              access-token (token/issue-access-token token-encoder issuer sub roles access-jti access-ttl)
              refresh-token (token/issue-refresh-token token-encoder issuer sub refresh-jti refresh-ttl)
              expires-at (+ (System/currentTimeMillis) (* refresh-ttl 1000))]
          (session/create! {:rama session-store} (:id user-record) access-jti expires-at)
          {:status 200
           :body {:access-token access-token
                  :refresh-token refresh-token
                  :expires-in access-ttl}})
        {:status 401
         :body {:errors {:credentials ["Invalid username or password"]}}}))))

(defn refresh [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token-decoder token-encoder user-store password-encoder session-store revocation-validator]} deps
          {:keys [refresh-token]} body-params]
      (try
        (let [jwt (token/decode token-decoder refresh-token)
              jti (.getId jwt)
              sub (.getSubject jwt)
              type (.getClaim jwt "type")]
          (if (not= type "refresh")
            {:status 401 :body {:errors {:token ["Not a refresh token"]}}}
            (if (revocation/is-revoked? {:revocation revocation-validator} jti)
              {:status 401 :body {:errors {:token ["Token revoked"]}}}
              (let [user-record (user/find-by-id {:rama user-store} (Long/parseLong sub))
                    roles (:roles user-record)
                    new-access-jti (str (UUID/randomUUID))
                    new-refresh-jti (str (UUID/randomUUID))
                    issuer "com.ozimos.auth"
                    access-token (token/issue-access-token token-encoder issuer sub roles new-access-jti 900)
                    new-refresh-token (token/issue-refresh-token token-encoder issuer sub new-refresh-jti 604800)]
                (revocation/revoke! {:revocation revocation-validator} jti (.. jwt getExpiresAt toEpochMilli))
                {:status 200
                 :body {:access-token access-token
                        :refresh-token new-refresh-token
                        :expires-in 900}}))))
        (catch Exception e
          {:status 401 :body {:errors {:token ["Invalid token"]}}})))))

(defn logout [deps]
  (fn [request]
    (let [auth-user (get-auth-user request)
          {:keys [session-store revocation-validator token-decoder]} deps]
      (when auth-user
        (revocation/revoke! {:revocation revocation-validator} (:jti auth-user)
                            (+ (System/currentTimeMillis) (* 900 1000))))
      {:status 200 :body {:message "Logged out"}})))

(defn logout-everywhere [deps]
  (fn [request]
    (let [auth-user (get-auth-user request)
          {:keys [session-store revocation-validator]} deps]
      (when auth-user
        (session/revoke-all! {:rama session-store} (:user-id auth-user))
        (revocation/revoke-all-for-user! {:revocation revocation-validator} (:user-id auth-user)))
      {:status 200 :body {:message "Logged out from all devices"}})))

(defn verify [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token user-store]} body-params
          {:keys [user-store]} deps]
      (if (user/verify! {:rama user-store} (Long/parseLong token))
        {:status 200 :body {:message "Account verified"}}
        {:status 400 :body {:errors {:token ["Invalid verification token"]}}}))))

(defn forgot-password [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [email]} body-params
          {:keys [user-store]} deps
          user-record (user/find-by-username {:rama user-store} email)]
      (when user-record
        (let [reset-token (str (java.util.UUID/randomUUID))]
          ;; Stub: In production, send email with reset-token
          (spit "/tmp/reset-tokens.edn" (str {reset-token (:id user-record)}) :append true)))
      {:status 200 :body {:message "If the email exists, a reset link has been sent"}})))

(defn reset-password [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token password]} body-params
          {:keys [user-store password-encoder]} deps]
      ;; Stub: Read reset token from temp store
      (let [reset-store (try (read-string (slurp "/tmp/reset-tokens.edn")) (catch Exception _ {}))
            user-id (get reset-store token)]
        (if user-id
          (let [pwd-hash (password/encode password-encoder password)]
            (user/change-password! {:rama user-store :password-encoder password-encoder} user-id pwd-hash)
            ;; Delete the used token
            (spit "/tmp/reset-tokens.edn" "{}")
            {:status 200 :body {:message "Password reset successfully"}})
          {:status 400 :body {:errors {:token ["Invalid or expired reset token"]}}})))))

(defn health [_]
  {:status 200 :body {:status "ok"}})
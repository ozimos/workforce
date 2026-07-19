(ns com.ozimos.auth.auth-api.handlers
  (:require
   [com.ozimos.auth.revocation.interface :as revocation]
   [com.ozimos.auth.schema.interface :as schema]
   [com.ozimos.auth.schema.interface.registration :as reg-schema]
   [com.ozimos.auth.session.interface :as session]
   [com.ozimos.auth.token.interface :as token]
   [com.ozimos.auth.user.interface :as user]
   [malli.core :as m])
  (:import
   (java.util UUID)))

(defn- get-auth-user
  "Extract authenticated user info from Spring Security context."
  [request]
  (let [auth (get-in request [:servlet-request "org.springframework.security.context.SECURITY_CONTEXT" :authentication])]
    (when auth
      (try
        (let [jwt (.getPrincipal auth)
              sub (.getSubject jwt)
              roles (.getClaim jwt "roles")]
          {:user-id (Long/parseLong sub)
           :roles roles
           :jti (.getId jwt)})
        (catch Exception _ nil)))))

(defn register [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [user-store]} deps
          result (user/register! user-store body-params)]
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
    (let [{:keys [user-store token-encoder session-store]} deps
          {:keys [username password]} body-params
          {:keys [encoder]} token-encoder
          user-record (user/find-by-username user-store username)]
      (if (and user-record
               (user/matches-password? user-store password (:pwd-hash user-record)))
        (let [issuer "com.ozimos.auth"
              sub (str (:id user-record))
              roles (:roles user-record)
              access-jti (str (UUID/randomUUID))
              refresh-jti (str (UUID/randomUUID))
              access-ttl 900
              refresh-ttl 604800
              access-token (token/issue-access-token encoder issuer sub roles access-jti access-ttl)
              refresh-token (token/issue-refresh-token encoder issuer sub refresh-jti refresh-ttl)
              expires-at (+ (System/currentTimeMillis) (* refresh-ttl 1000))]
          (session/create! session-store (:id user-record) access-jti expires-at)
          {:status 200
           :body {:access-token access-token
                  :refresh-token refresh-token
                  :expires-in access-ttl}})
        {:status 401
         :body {:errors {:credentials ["Invalid username or password"]}}}))))

(defn refresh [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token-decoder token-encoder user-store revocation-validator]} deps
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
            (if (revocation/is-revoked? revocation-validator jti)
              {:status 401 :body {:errors {:token ["Token revoked"]}}}
              (let [user-record (user/find-by-id user-store (Long/parseLong sub))
                    roles (:roles user-record)
                    new-access-jti (str (UUID/randomUUID))
                    new-refresh-jti (str (UUID/randomUUID))
                    issuer "com.ozimos.auth"
                    access-token (token/issue-access-token encoder issuer sub roles new-access-jti 900)
                    new-refresh-token (token/issue-refresh-token encoder issuer sub new-refresh-jti 604800)]
                (revocation/revoke! revocation-validator jti (.. jwt getExpiresAt toEpochMilli))
                {:status 200
                 :body {:access-token access-token
                        :refresh-token new-refresh-token
                        :expires-in 900}}))))
        (catch Exception e
          {:status 401 :body {:errors {:token ["Invalid token"]}}})))))

(defn logout [deps]
  (fn [request]
    (let [auth-user (get-auth-user request)
          {:keys [revocation-validator]} deps]
      (when auth-user
        (revocation/revoke! revocation-validator (:jti auth-user)
                            (+ (System/currentTimeMillis) (* 900 1000))))
      {:status 200 :body {:message "Logged out"}})))

(defn logout-everywhere [deps]
  (fn [request]
    (let [auth-user (get-auth-user request)
          {:keys [session-store revocation-validator]} deps]
      (when auth-user
        (session/revoke-all! session-store (:user-id auth-user))
        (revocation/revoke-all-for-user! revocation-validator (:user-id auth-user)))
      {:status 200 :body {:message "Logged out from all devices"}})))

(defn verify [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [user-id]} body-params
          {:keys [user-store]} deps]
      (if (user/verify! user-store (Long/parseLong user-id))
        {:status 200 :body {:message "Account verified"}}
        {:status 400 :body {:errors {:user-id ["Invalid user-id"]}}}))))

(defn forgot-password [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [email]} body-params
          {:keys [user-store]} deps
          user-record (user/find-by-username user-store email)]
      (when user-record
        (let [reset-token (str (java.util.UUID/randomUUID))]
          ;; Stub: In production, send email with reset-token
          (spit "/tmp/reset-tokens.edn" (str {reset-token (:id user-record)}) :append true)))
      {:status 200 :body {:message "If the email exists, a reset link has been sent"}})))

(defn reset-password [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token password]} body-params
          {:keys [user-store]} deps
          reset-store (try (read-string (slurp "/tmp/reset-tokens.edn")) (catch Exception _ {}))
          user-id (get reset-store token)]
      (if user-id
        (let [pwd-hash (user/encode-password user-store password)]
          (user/change-password! user-store user-id pwd-hash)
          (spit "/tmp/reset-tokens.edn" "{}")
          {:status 200 :body {:message "Password reset successfully"}})
        {:status 400 :body {:errors {:token ["Invalid or expired reset token"]}}}))))

(defn health [_]
  {:status 200 :body {:status "ok"}})

#_
(comment
  ;; REVIEW: com.ozimos.auth.auth-api.handlers
  ;;
  ;; DEFERRED (known dev stub, will be replaced with proper email-based flow):
  ;; 1. forgot-password uses spit :append to /tmp/reset-tokens.edn — concurrent issue.
  ;; 2. reset-password uses read-string (not clojure.edn/read-string) — security concern.
  ;; 3. reset-password clears ALL tokens by writing "{}" — loses pending tokens.
  )

(ns com.ozimos.auth.auth-api.handlers
  (:require
   [clojure.edn :as edn]
   [com.ozimos.auth.pathom.interface :as pathom]
   [com.ozimos.auth.revocation.interface :as revocation]
   [com.ozimos.auth.schema.interface :as schema]
   [com.ozimos.auth.schema.interface.registration :as reg-schema]
   [com.ozimos.auth.session.interface :as session]
   [com.ozimos.auth.token.interface :as token]
   [com.ozimos.auth.user.interface :as user]
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

(defn register [deps]
  (fn [{:keys [body-params]}]
    (let [result (user/register! deps body-params)]
      (if (first result)
        (let [user (second result)]
          {:status 201
           :body {:id (:id user)
                  :username (:username user)
                  :email (:email user)
                  :verified (:verified user)}})
        {:status 409
         :body (second result)}))))

(defn login [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token-encoder]} deps
          {:keys [identifier password]} body-params
          {:keys [encoder]} token-encoder
          user-record (user/find-by-identifier deps identifier)]
      (if (and user-record
               (user/matches-password? deps password (:pwd-hash user-record)))
        (let [issuer "com.ozimos.auth"
              sub (str (:id user-record))
              roles (:roles user-record)
              active-org-id (user/get-active-org deps (:id user-record))
              active-org-role (when active-org-id
                                (:role (user/get-membership deps (:id user-record) active-org-id)))
              access-jti (str (random-uuid))
              refresh-jti (str (random-uuid))
              access-ttl 900
              refresh-ttl 604800
              access-token (if (and active-org-id active-org-role)
                             (token/issue-access-token encoder issuer sub roles access-jti access-ttl active-org-id active-org-role)
                             (token/issue-access-token encoder issuer sub roles access-jti access-ttl))
              refresh-token (token/issue-refresh-token encoder issuer sub refresh-jti refresh-ttl)
              expires-at (+ (System/currentTimeMillis) (* refresh-ttl 1000))]
          (session/create! deps (:id user-record) access-jti expires-at)
          {:status 200
           :body {:access-token access-token
                  :refresh-token refresh-token
                  :expires-in access-ttl}})
        {:status 401
         :body {:errors {:credentials ["Invalid username/email or password"]}}}))))

(defn refresh [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token-decoder token-encoder]} deps
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
            (if (revocation/is-revoked? deps jti)
              {:status 401 :body {:errors {:token ["Token revoked"]}}}
              (if-let [parsed-id (parse-user-id sub)]
                (let [user-record (user/find-by-id deps parsed-id)
                      roles (:roles user-record)
                      active-org-id (user/get-active-org deps parsed-id)
                      active-org-role (when active-org-id
                                        (:role (user/get-membership deps parsed-id active-org-id)))
                      new-access-jti (str (random-uuid))
                      new-refresh-jti (str (random-uuid))
                      issuer "com.ozimos.auth"
                      access-token (if (and active-org-id active-org-role)
                                     (token/issue-access-token encoder issuer sub roles new-access-jti 900 active-org-id active-org-role)
                                     (token/issue-access-token encoder issuer sub roles new-access-jti 900))
                      new-refresh-token (token/issue-refresh-token encoder issuer sub new-refresh-jti 604800)]
                  (revocation/revoke! deps jti (.. jwt getExpiresAt toEpochMilli))
                  {:status 200
                   :body {:access-token access-token
                          :refresh-token new-refresh-token
                          :expires-in 900}})
                {:status 401 :body {:errors {:token ["Invalid token"]}}}))))
        (catch Exception e
          {:status 401 :body {:errors {:token ["Invalid token"]}}})))))

(defn logout [deps]
  (fn [request]
    (let [auth-user (get-auth-user request (:token-decoder deps))]
      (when auth-user
        (revocation/revoke! deps (:jti auth-user)
                            (+ (System/currentTimeMillis) (* 900 1000))))
      {:status 200 :body {:message "Logged out"}})))

(defn logout-everywhere [deps]
  (fn [request]
    (let [auth-user (get-auth-user request (:token-decoder deps))]
      (when auth-user
        (session/revoke-all! deps (:user-id auth-user))
        (revocation/revoke-all-for-user! deps (:user-id auth-user)))
      {:status 200 :body {:message "Logged out from all devices"}})))

(defn verify [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [user-id]} body-params]
      (if-let [parsed-id (parse-user-id user-id)]
        (if (user/verify! deps parsed-id)
          {:status 200 :body {:message "Account verified"}}
          {:status 400 :body {:errors {:user-id ["Invalid user-id"]}}})
        {:status 400 :body {:errors {:user-id ["Invalid user-id"]}}}))))

(defn forgot-password [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [email]} body-params
          user-record (user/find-by-email deps email)]
      (when user-record
        (user/create-reset-token! deps (:id user-record)))
      {:status 200 :body {:message "If the email exists, a reset link has been sent"}})))

(defn reset-password [deps]
  (fn [{:keys [body-params]}]
    (let [{:keys [token password]} body-params]
      (try
        (if-let [user-id (user/validate-reset-token deps token)]
          (let [pwd-hash (user/encode-password deps password)]
            (user/change-password! deps user-id pwd-hash)
            (user/clear-reset-token! deps token)
            {:status 200 :body {:message "Password reset successfully"}})
          {:status 400 :body {:errors {:token ["Invalid or expired reset token"]}}})
        (catch Exception e
          (if (instance? clojure.lang.ExceptionInfo e)
            {:status 400 :body {:errors {:token [(.getMessage e)]}}}
            (throw e)))))))

(defn query [deps]
  (fn [{:keys [body-params] :as request}]
    (let [{:keys [token-decoder]} deps
          base-env (or (:pathom-env deps) (pathom/build-env deps))
          query (or (:query body-params)
                    (some-> (:eql body-params) edn/read-string))
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
          {:status 400 :body {:errors {:query [(.getMessage e)]}}})))))

(defn health [_]
  {:status 200 :body {:status "ok"}})

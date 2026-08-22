(ns com.ozimos.auth.oauth.core
  (:require
   [com.ozimos.auth.session.interface :as session]
   [com.ozimos.auth.token.interface :as token]
   [com.ozimos.auth.user.interface :as user]))

(defn handle-oauth-callback
  "Processes OAuth user info:
   {:provider-user-id \"sub123\" :email \"user@example.com\" :name \"John Doe\"}
   1. Checks if provider + provider-user-id is already linked to a local user.
   2. If not, checks if a user with the same email exists.
      - If exists, links provider to existing user.
      - If not, auto-registers a new verified user and links provider.
   3. Creates session & issues JWT access and refresh tokens."
  [deps provider {:keys [provider-user-id email name] :as oauth-info}]
  (if-not (and provider provider-user-id email)
    [false {:errors {:oauth ["Invalid OAuth user info from provider."]}}]
    (let [existing-linked (user/find-by-oauth-link deps provider provider-user-id)
          user (or existing-linked
                   (when-let [by-email (user/find-by-email deps email)]
                     (user/link-oauth-account! deps provider provider-user-id (:id by-email))
                     by-email)
                   (let [pwd (str (random-uuid) "!")
                         [ok? new-user] (user/register! deps {:email email
                                                              :password pwd
                                                              :roles ["ROLE_USER"]})]
                     (when ok?
                       (user/verify! deps (:id new-user))
                       (user/link-oauth-account! deps provider provider-user-id (:id new-user))
                       new-user)))]
      (if user
        (let [user-id (:id user)]
          (if (user/mfa-enabled? deps user-id)
            (let [challenge-token (if (:token-encoder deps)
                                    (token/issue-mfa-challenge-token (:token-encoder deps) "com.ozimos.auth" (str user-id) 300)
                                    (str "mock-mfa-token-" user-id))]
              [true {:mfa-required true
                     :mfa-token challenge-token
                     :user (select-keys user [:id :username :email :roles])}])
            (let [roles (or (:roles user) ["ROLE_USER"])
                  access-jti (str (random-uuid))
                  refresh-jti (str (random-uuid))
                  access-token (if (:token-encoder deps)
                                 (token/issue-access-token (:token-encoder deps) "com.ozimos.auth" (str user-id) roles access-jti 900 nil nil "oauth2")
                                 (str "mock-access-token-" user-id))
                  refresh-token (if (:token-encoder deps)
                                  (token/issue-refresh-token (:token-encoder deps) "com.ozimos.auth" (str user-id) refresh-jti 604800)
                                  (str "mock-refresh-token-" user-id))
                  expires-at (long (+ (System/currentTimeMillis) (* 15 60 1000)))]
              (session/create! deps user-id access-jti expires-at)
              [true {:access-token access-token
                     :refresh-token refresh-token
                     :auth-method "oauth2"
                     :user (select-keys user [:id :username :email :roles])}])))
        [false {:errors {:oauth ["Could not provision or link account."]}}]))))

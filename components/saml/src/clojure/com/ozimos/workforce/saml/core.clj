(ns com.ozimos.workforce.saml.core
  (:require
   [com.ozimos.workforce.session.interface :as session]
   [com.ozimos.workforce.token.interface :as token]
   [com.ozimos.workforce.user.interface :as user]))

(defn handle-saml-assertion
  "Processes SAML assertion input:
   {:name-id \"saml-user-123\" :email \"user@enterprise.com\" :name \"Jane Enterprise\"}
   1. Checks if SAML name-id is already linked via `find-by-oauth-link` with provider \"saml\".
   2. If not, checks if user with matching email exists.
      - If exists, links SAML provider to existing user.
      - If not, registers a verified user and links SAML provider.
   3. Creates session & issues JWT access token with `auth-method=\"saml\"` claim and refresh token."
  [deps {:keys [name-id email name] :as saml-info}]
  (if-not (and name-id email)
    [false {:errors {:saml ["Invalid SAML assertion: name-id and email required."]}}]
    (let [provider "saml"
          existing-linked (user/find-by-oauth-link deps provider name-id)
          user (or existing-linked
                   (when-let [by-email (user/find-by-email deps email)]
                     (user/link-oauth-account! deps provider name-id (:id by-email))
                     by-email)
                   (let [pwd (str (random-uuid) "!")
                         [ok? new-user] (user/register! deps {:email email
                                                              :password pwd
                                                              :roles ["ROLE_USER"]})]
                     (when ok?
                       (user/verify! deps (:id new-user))
                       (user/link-oauth-account! deps provider name-id (:id new-user))
                       new-user)))]
      (if user
        (let [user-id (:id user)]
          (if (user/mfa-enabled? deps user-id)
            (let [challenge-token (if (:token-encoder deps)
                                    (token/issue-mfa-challenge-token (:token-encoder deps) "com.ozimos.workforce" (str user-id) 300)
                                    (str "mock-mfa-token-" user-id))]
              [true {:mfa-required true
                     :mfa-token challenge-token
                     :user (select-keys user [:id :username :email :roles])}])
            (let [roles (or (:roles user) ["ROLE_USER"])
                  access-jti (str (random-uuid))
                  refresh-jti (str (random-uuid))
                  access-token (if (:token-encoder deps)
                                 (token/issue-access-token (:token-encoder deps) "com.ozimos.workforce" (str user-id) roles access-jti 900 nil nil "saml")
                                 (str "mock-saml-access-token-" user-id))
                  refresh-token (if (:token-encoder deps)
                                  (token/issue-refresh-token (:token-encoder deps) "com.ozimos.workforce" (str user-id) refresh-jti 604800)
                                  (str "mock-saml-refresh-token-" user-id))
                  expires-at (long (+ (System/currentTimeMillis) (* 15 60 1000)))]
              (session/create! deps user-id access-jti expires-at)
              [true {:access-token access-token
                     :refresh-token refresh-token
                     :auth-method "saml"
                     :user (select-keys user [:id :username :email :roles])}])))
        [false {:errors {:saml ["Could not provision or link SAML account."]}}]))))

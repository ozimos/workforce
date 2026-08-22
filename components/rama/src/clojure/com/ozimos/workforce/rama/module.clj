(ns com.ozimos.workforce.rama.module
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.ozimos.workforce.rama.extension :as ext]
            [com.ozimos.workforce.rama.registry :as reg]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops])
  (:import [com.rpl.rama.helpers ModuleUniqueIdPState]))

(defrecord Registration [uuid username pwd-hash email roles])
(defrecord Verification [user-id])
(defrecord PasswordChange [user-id new-pwd-hash])
(defrecord UsernameChange [user-id new-username])
(defrecord SessionStart [user-id session-id jti expires-at])
(defrecord SessionEnd [session-id])
(defrecord Revocation [jti expires-at])
(defrecord RevokeAllForUser [user-id])
(defrecord ClearRevocation [jti])
(defrecord ResetToken [token user-id expires-at])
(defrecord ClearResetToken [token])
(defrecord MfaSetup [user-id encrypted-secret backup-code-hashes])
(defrecord MfaDisable [user-id])
(defrecord MfaConsumeBackupCode [user-id code-hash])
(defrecord MfaRegenerateBackupCodes [user-id backup-code-hashes])
(defrecord WebAuthnRegister [user-id credential-id public-key-cose sign-count user-handle nickname created-at])
(defrecord WebAuthnUpdateSignCount [user-id credential-id new-sign-count])
(defrecord WebAuthnRemoveCredential [user-id credential-id])
(defrecord OAuthLink [provider provider-user-id user-id])

(defmodule AuthModule [setup topologies]
  (declare-depot setup *registration-depot (hash-by :username))
  (declare-depot setup *verification-depot (hash-by :user-id))
  (declare-depot setup *password-change-depot (hash-by :user-id))
  (declare-depot setup *username-change-depot (hash-by :user-id))
  (declare-depot setup *session-depot (hash-by :user-id))
  (declare-depot setup *session-end-depot (hash-by :session-id))
  (declare-depot setup *revoke-all-depot (hash-by :user-id))
  (declare-depot setup *revocation-depot (hash-by :jti))
  (declare-depot setup *clear-revocation-depot (hash-by :jti))
  (declare-depot setup *reset-token-depot (hash-by :token))
  (declare-depot setup *clear-reset-token-depot (hash-by :token))
  (declare-depot setup *mfa-setup-depot (hash-by :user-id))
  (declare-depot setup *mfa-disable-depot (hash-by :user-id))
  (declare-depot setup *mfa-consume-backup-code-depot (hash-by :user-id))
  (declare-depot setup *mfa-regenerate-backup-codes-depot (hash-by :user-id))
  (declare-depot setup *webauthn-register-depot (hash-by :user-id))
  (declare-depot setup *webauthn-sign-count-depot (hash-by :user-id))
  (declare-depot setup *webauthn-remove-depot (hash-by :user-id))
  (declare-depot setup *oauth-link-depot (hash-by :provider))

  (doseq [extension (reg/get-registered-extensions)]
    (ext/declare-depots extension setup))

  (let [s (stream-topology topologies "auth")
        id-gen (ModuleUniqueIdPState. "$$id")]
    (declare-pstate s $$username->id {String Long})
    (declare-pstate s $$email->id {String Long})
    (declare-pstate s $$registration-ids {String Long})

    (declare-pstate s $$profiles
                    {Long (fixed-keys-schema {:username String
                                              :pwd-hash String
                                              :email String
                                              :verified Boolean
                                              :roles (vector-schema String)})})
    (declare-pstate s $$sessions
                    {String (fixed-keys-schema {:user-id Long :jti String :expires-at Long})})
    (declare-pstate s $$user-sessions
                    {Long (set-schema String {:subindex? true})})
    (declare-pstate s $$user-active-jtis
                    {Long (set-schema String {:subindex? true})})
    (declare-pstate s $$all-session-ids
                    {String (set-schema String {:subindex? true})})
    (declare-pstate s $$all-revoked-jtis
                    {String (set-schema String {:subindex? true})})
    (declare-pstate s $$revoked-tokens
                    {String Long})
    (declare-pstate s $$reset-tokens
                    {String (fixed-keys-schema {:user-id Long :expires-at Long})})

    ;; MFA: user-id -> encrypted TOTP secret
    (declare-pstate s $$mfa-secrets {Long String})
    ;; MFA: user-id -> boolean flag
    (declare-pstate s $$mfa-enabled {Long Boolean})
    ;; MFA: user-id -> map of hashed backup code to boolean true
    (declare-pstate s $$mfa-backup-codes
                    {Long {String Boolean}})

    ;; WebAuthn: user-id -> {credential-id -> {public-key, sign-count, user-handle, nickname, created-at}}
    (declare-pstate s $$webauthn-credentials
                    {Long {String (fixed-keys-schema {:public-key String
                                                      :sign-count Long
                                                      :user-handle String
                                                      :nickname String
                                                      :created-at Long})}})

    ;; OAuth & SAML Account Links: provider -> provider-user-id -> local user-id
    (declare-pstate s $$oauth-link {String {String Long}})

    (doseq [extension (reg/get-registered-extensions)]
      (ext/declare-pstates extension s))

    (.declarePState id-gen s)

    (<<sources s
               ;; Registration
               (source> *registration-depot :> {:keys [*uuid *username *pwd-hash *email *roles]})
               (local-select> (keypath *username) $$username->id :> *existing-id)
               (local-select> (keypath *uuid) $$registration-ids :> *existing-reg-uuid)
               (<<if (nil? *existing-id)
                     ;; Username available — register
                     (java-macro! (.genId id-gen "*user-id"))
                     (local-transform> [(keypath *username) (termval *user-id)] $$username->id)
                     (|hash *email)
                     (local-transform> [(keypath *email) (termval *user-id)] $$email->id)
                     (local-transform> [(keypath *uuid) (termval *user-id)] $$registration-ids)
                     (|hash *user-id)
                     (local-transform> [(keypath *user-id)
                                        (multi-path [:username (termval *username)]
                                                    [:pwd-hash (termval *pwd-hash)]
                                                    [:email (termval *email)]
                                                    [:verified (termval false)]
                                                    [:roles (termval *roles)])]
                                       $$profiles)
                      (ack-return> *user-id)
                      (else>)
                      (ack-return> *existing-id))

               ;; Verification
                (source> *verification-depot :> {:keys [*user-id]})
                (local-transform> [(keypath *user-id :verified) (termval true)] $$profiles)

               ;; Password change
                (source> *password-change-depot :> {:keys [*user-id *new-pwd-hash]})
                (local-transform> [(keypath *user-id :pwd-hash) (termval *new-pwd-hash)] $$profiles)

               ;; Username change
                (source> *username-change-depot :> {:keys [*user-id *new-username]})
                (local-select> (keypath *user-id :username) $$profiles :> *old-username)
                (<<if (not= *old-username *new-username)
                      ;; Switch to new-username partition — check uniqueness
                      (|hash *new-username)
                      (local-select> (keypath *new-username) $$username->id :> *existing-id)
                      (<<if (nil? *existing-id)
                            ;; Clear old username mapping (guard against nil profile)
                            (<<if (some? *old-username)
                                  (|hash *old-username)
                                  (local-transform> [(keypath *old-username) NONE>] $$username->id))
                            ;; Set new username mapping
                            (|hash *new-username)
                            (local-transform> [(keypath *new-username) (termval *user-id)] $$username->id)
                            ;; Update profile
                            (|hash *user-id)
                            (local-transform> [(keypath *user-id :username) (termval *new-username)] $$profiles)
                            (ack-return> :ok)
                            (else>)
                            (ack-return> :taken))
                      (else>)
                      (ack-return> :ok))

               ;; Session start
               (source> *session-depot :> {:keys [*user-id *session-id *jti *expires-at]})
               (|hash *session-id)
               (local-transform> [(keypath *session-id)
                                  (multi-path [:user-id (termval *user-id)]
                                              [:jti (termval *jti)]
                                              [:expires-at (termval *expires-at)])]
                                 $$sessions)
               (|hash *user-id)
               (local-transform> [(keypath *user-id) NONE-ELEM (termval *session-id)] $$user-sessions)
               (local-transform> [(keypath *user-id) NONE-ELEM (termval *jti)] $$user-active-jtis)

               ;; Single session end
                (source> *session-end-depot :> {:keys [*session-id]})
                (local-select> (keypath *session-id :user-id) $$sessions :> *user-id)
                (local-select> (keypath *session-id :jti) $$sessions :> *jti)
                (<<if (some? *user-id)
                      (local-transform> [(keypath *session-id) NONE>] $$sessions)
                      (|hash *user-id)
                      (local-transform> [(keypath *user-id) NONE-ELEM (termval *session-id)] $$user-sessions)
                      (local-transform> [(keypath *user-id) NONE-ELEM (termval *jti)] $$user-active-jtis))

               ;; Revoke-all
               (source> *revoke-all-depot :> {:keys [*user-id]})
               (local-select> (keypath *user-id) $$user-sessions :> *session-ids)
               (ops/explode *session-ids :> *sid)
               (|hash *sid)
               (local-transform> [(keypath *sid) NONE>] $$sessions)
               (|hash *user-id)
               (local-transform> [(keypath *user-id) NONE>] $$user-sessions)
               (local-select> (keypath *user-id) $$user-active-jtis :> *jtis)
               (ops/explode *jtis :> *jti)
               (|hash *jti)
               (local-transform> [(keypath *jti) (termval (System/currentTimeMillis))] $$revoked-tokens)
               (|hash *user-id)
               (local-transform> [(keypath *user-id) NONE>] $$user-active-jtis)

               ;; Token revocation
                (source> *revocation-depot :> {:keys [*jti *expires-at]})
                (local-transform> [(keypath *jti) (termval *expires-at)] $$revoked-tokens)

                ;; Clear revocation
                (source> *clear-revocation-depot :> {:keys [*jti]})
                (local-transform> [(keypath *jti) NONE>] $$revoked-tokens)

                ;; Reset token
                (source> *reset-token-depot :> {:keys [*token *user-id *expires-at]})
                (local-transform> [(keypath *token)
                                   (multi-path [:user-id (termval *user-id)]
                                               [:expires-at (termval *expires-at)])]
                                   $$reset-tokens)

                 ;; Clear reset token
                 (source> *clear-reset-token-depot :> {:keys [*token]})
                 (local-transform> [(keypath *token) NONE>] $$reset-tokens)

                 ;; MFA setup & enable
                 (source> *mfa-setup-depot :> {:keys [*user-id *encrypted-secret *backup-code-hashes]})
                 (local-transform> [(keypath *user-id) (termval *encrypted-secret)] $$mfa-secrets)
                 (local-transform> [(keypath *user-id) (termval true)] $$mfa-enabled)
                 (local-transform> [(keypath *user-id) NONE>] $$mfa-backup-codes)
                 (ops/explode *backup-code-hashes :> *code-hash)
                 (local-transform> [(keypath *user-id *code-hash) (termval true)] $$mfa-backup-codes)

                 ;; MFA disable
                 (source> *mfa-disable-depot :> {:keys [*user-id]})
                 (local-transform> [(keypath *user-id) NONE>] $$mfa-secrets)
                 (local-transform> [(keypath *user-id) (termval false)] $$mfa-enabled)
                 (local-transform> [(keypath *user-id) NONE>] $$mfa-backup-codes)

                 ;; Consume backup code
                 (source> *mfa-consume-backup-code-depot :> {:keys [*user-id *code-hash]})
                 (local-transform> [(keypath *user-id *code-hash) NONE>] $$mfa-backup-codes)

                 ;; Regenerate backup codes
                 (source> *mfa-regenerate-backup-codes-depot :> {:keys [*user-id *backup-code-hashes]})
                 (local-transform> [(keypath *user-id) NONE>] $$mfa-backup-codes)
                 (ops/explode *backup-code-hashes :> *code-hash)
                 (local-transform> [(keypath *user-id *code-hash) (termval true)] $$mfa-backup-codes)

                 ;; WebAuthn register
                 (source> *webauthn-register-depot :> {:keys [*user-id *credential-id *public-key-cose *sign-count *user-handle *nickname *created-at]})
                 (local-transform> [(keypath *user-id *credential-id)
                                    (multi-path [:public-key (termval *public-key-cose)]
                                                [:sign-count (termval *sign-count)]
                                                [:user-handle (termval *user-handle)]
                                                [:nickname (termval *nickname)]
                                                [:created-at (termval *created-at)])]
                                   $$webauthn-credentials)

                 ;; WebAuthn update sign count
                 (source> *webauthn-sign-count-depot :> {:keys [*user-id *credential-id *new-sign-count]})
                 (local-transform> [(keypath *user-id *credential-id :sign-count) (termval *new-sign-count)] $$webauthn-credentials)

                  ;; WebAuthn remove credential
                  (source> *webauthn-remove-depot :> {:keys [*user-id *credential-id]})
                  (local-transform> [(keypath *user-id *credential-id) NONE>] $$webauthn-credentials)

                  ;; OAuth & SAML Link
                  (source> *oauth-link-depot :> {:keys [*provider *provider-user-id *user-id]})
                  (local-transform> [(keypath *provider *provider-user-id) (termval *user-id)] $$oauth-link))

    (doseq [extension (reg/get-registered-extensions)]
      (ext/build-topology extension s))))

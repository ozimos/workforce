(ns com.ozimos.workforce.user.interface
  (:require
   [com.ozimos.workforce.user.core :as core]))

(defn register!
  "Register a new user. Returns [true user] on success, [false {:errors ...}] on failure.
   `input` is a map with :email, :password, optionally :username and :roles.
   If :username is omitted, one is auto-derived from the email local-part."
  [deps input]
  (core/register! deps input))

(defn find-by-username
  "Look up a user by username. Returns user map or nil."
  [deps username]
  (core/find-by-username deps username))

(defn find-by-email
  "Look up a user by email. Returns user map or nil."
  [deps email]
  (core/find-by-email deps email))

(defn find-by-identifier
  "Look up a user by email or username. Tries email first, falls back to username.
   Returns user map or nil."
  [deps identifier]
  (core/find-by-identifier deps identifier))

(defn find-by-id
  "Look up a user by id. Returns user map or nil."
  [deps user-id]
  (core/find-by-id deps user-id))

(defn verify!
  "Mark a user as verified. Returns true on success."
  [deps user-id]
  (core/verify! deps user-id))

(defn change-password!
  "Change a user's password hash. Returns true on success."
  [deps user-id new-pwd-hash]
  (core/change-password! deps user-id new-pwd-hash))

(defn encode-password
  "Encode a plaintext password using BCrypt. Returns the hash string."
  [deps plain]
  (core/encode-password deps plain))

(defn matches-password?
  "Check if a plaintext password matches a BCrypt hash."
  [deps plain encoded]
  (core/matches-password? deps plain encoded))

(defn update-username!
  "Update a user's username. Returns [true new-username] on success,
   [false {:errors ...}] on failure."
  [deps user-id new-username]
  (core/update-username! deps user-id new-username))

(defn create-reset-token!
  "Generate a password reset token and store in Rama. Returns the token string."
  [deps user-id]
  (core/create-reset-token! deps user-id))

(defn validate-reset-token
  "Lookup a reset token in Rama, check expiry. Returns user-id or nil.
   Throws if the token is expired."
  [deps token]
  (core/validate-reset-token deps token))

(defn clear-reset-token!
  "Remove a reset token from Rama (marks it as consumed)."
  [deps token]
  (core/clear-reset-token! deps token))

(defn mfa-enabled?
  "Check if MFA is enabled for a user."
  [deps user-id]
  (core/mfa-enabled? deps user-id))

(defn setup-mfa!
  "Setup MFA for a user with encrypted secret and backup code hashes."
  [deps user-id encrypted-secret backup-code-hashes]
  (core/setup-mfa! deps user-id encrypted-secret backup-code-hashes))

(defn verify-mfa-setup!
  "Verify MFA setup for a user."
  [deps user-id]
  (core/verify-mfa-setup! deps user-id))

(defn disable-mfa!
  "Disable MFA for a user."
  [deps user-id]
  (core/disable-mfa! deps user-id))

(defn get-mfa-secret
  "Get a user's encrypted MFA secret string."
  [deps user-id]
  (core/get-mfa-secret deps user-id))

(defn get-mfa-backup-codes
  "Get a user's set of hashed backup codes."
  [deps user-id]
  (core/get-mfa-backup-codes deps user-id))

(defn consume-mfa-backup-code!
  "Remove a used backup code hash."
  [deps user-id code-hash]
  (core/consume-mfa-backup-code! deps user-id code-hash))

(defn regenerate-mfa-backup-codes!
  "Replace a user's backup code hashes."
  [deps user-id backup-code-hashes]
  (core/regenerate-mfa-backup-codes! deps user-id backup-code-hashes))

(defn count-mfa-backup-codes
  "Return count of remaining backup codes."
  [deps user-id]
  (core/count-mfa-backup-codes deps user-id))

(defn register-passkey!
  "Register a WebAuthn passkey credential."
  [deps user-id credential-id public-key-cose sign-count user-handle nickname]
  (core/register-passkey! deps user-id credential-id public-key-cose sign-count user-handle nickname))

(defn update-passkey-sign-count!
  "Update sign count for a WebAuthn passkey."
  [deps user-id credential-id new-sign-count]
  (core/update-passkey-sign-count! deps user-id credential-id new-sign-count))

(defn remove-passkey!
  "Remove a registered WebAuthn passkey."
  [deps user-id credential-id]
  (core/remove-passkey! deps user-id credential-id))

(defn list-passkeys-for-user
  "List all registered WebAuthn passkeys for a user."
  [deps user-id]
  (core/list-passkeys-for-user deps user-id))

(defn link-oauth-account!
  "Link a 3rd party OAuth or SAML provider account to a local user-id."
  [deps provider provider-user-id user-id]
  (core/link-oauth-account! deps provider provider-user-id user-id))

(defn find-by-oauth-link
  "Look up a local user by OAuth/SAML provider and provider-user-id."
  [deps provider provider-user-id]
  (core/find-by-oauth-link deps provider provider-user-id))

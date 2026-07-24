(ns com.ozimos.auth.user.interface
  (:require
   [com.ozimos.auth.user.core :as core]))

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

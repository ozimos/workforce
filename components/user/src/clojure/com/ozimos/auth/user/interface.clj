(ns com.ozimos.auth.user.interface
  (:require
   [com.ozimos.auth.user.core :as core]))

(defn register!
  "Register a new user. Returns [true user] on success, [false {:errors ...}] on failure.
   `input` is a map with :username, :email, :password, optionally :roles."
  [deps input]
  (core/register! deps input))

(defn find-by-username
  "Look up a user by username. Returns user map or nil."
  [deps username]
  (core/find-by-username deps username))

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

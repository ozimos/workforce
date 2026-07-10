(ns com.ozimos.auth.session.interface
  (:require [com.ozimos.auth.session.core :as core]))

(defn create!
  "Create a session for user-id with the given jti and expiry (epoch ms). Returns session-id."
  [deps user-id jti expires-at]
  (core/create! deps user-id jti expires-at))

(defn verify
  "Verify a session by session-id. Returns session map or nil."
  [deps session-id]
  (core/verify deps session-id))

(defn revoke!
  "Revoke a single session by session-id."
  [deps session-id]
  (core/revoke! deps session-id))

(defn revoke-all!
  "Revoke all sessions for a user."
  [deps user-id]
  (core/revoke-all! deps user-id))

(defn list-for-user
  "List all active session-ids for a user."
  [deps user-id]
  (core/list-for-user deps user-id))
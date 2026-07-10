(ns com.ozimos.auth.session.core
  (:require [com.ozimos.auth.rama.interface :as rama]
            [com.rpl.rama :as rama]
            [com.rpl.rama.path :refer [keypath]])
  (:import [java.util UUID]))

(defn create! [{:keys [rama] :as deps} user-id jti expires-at]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        session-depot (rama/depot cmgr mod-name "*session-depot")
        session-id (str (UUID/randomUUID))]
    (rama/foreign-append! session-depot
     (->SessionStart user-id jti expires-at))
    session-id))

(defn verify [{:keys [rama] :as deps} session-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        sessions-pstate (rama/pstate cmgr mod-name "$$sessions")]
    (rama/foreign-select-one (keypath session-id) sessions-pstate {:pkey session-id})))

(defn revoke! [{:keys [rama] :as deps} session-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        session-end-depot (rama/depot cmgr mod-name "*session-end-depot")]
    (rama/foreign-append! session-end-depot (->SessionEnd session-id))
    true))

(defn revoke-all! [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        revoke-all-depot (rama/depot cmgr mod-name "*revoke-all-depot")]
    (rama/foreign-append! revoke-all-depot (->RevokeAllForUser user-id))
    true))

(defn list-for-user [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        user-sessions-pstate (rama/pstate cmgr mod-name "$$user-sessions")]
    (rama/foreign-select [(keypath user-id) ALL] user-sessions-pstate {:pkey user-id})))
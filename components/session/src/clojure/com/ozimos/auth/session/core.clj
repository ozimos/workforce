(ns com.ozimos.auth.session.core
  (:require
   [com.ozimos.auth.rama.interface :as rama]
   [com.ozimos.auth.rama.module :refer [->RevokeAllForUser ->SessionEnd ->SessionStart]]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [ALL keypath]]
   [integrant.core :as ig])
  (:import
   (java.util UUID)))

(defn create! [{:keys [rama] :as deps} user-id jti expires-at]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        session-depot (rama/depot cmgr mod-name "*session-depot")
        session-id (str (UUID/randomUUID))]
    (ramaapi/foreign-append! session-depot
      (->SessionStart user-id session-id jti expires-at))
    session-id))

(defn verify [{:keys [rama] :as deps} session-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        sessions-pstate (rama/pstate cmgr mod-name "$$sessions")]
    (ramaapi/foreign-select-one (keypath session-id) sessions-pstate {:pkey session-id})))

(defn revoke! [{:keys [rama] :as deps} session-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        session-end-depot (rama/depot cmgr mod-name "*session-end-depot")]
    (ramaapi/foreign-append! session-end-depot (->SessionEnd session-id))
    true))

(defn revoke-all! [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        revoke-all-depot (rama/depot cmgr mod-name "*revoke-all-depot")]
    (ramaapi/foreign-append! revoke-all-depot (->RevokeAllForUser user-id))
    true))

(defn list-for-user [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        user-sessions-pstate (rama/pstate cmgr mod-name "$$user-sessions")]
    (ramaapi/foreign-select [(keypath user-id) ALL] user-sessions-pstate {:pkey user-id})))

(defmethod ig/init-key :session/store [_ {:keys [rama] :as deps}]
  (merge deps {:rama rama}))

(defmethod ig/halt-key! :session/store [_ _])

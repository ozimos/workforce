(ns com.ozimos.workforce.session.core
  (:require
   [com.ozimos.workforce.rama.interface :as rama]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [ALL keypath]]
   [integrant.core :as ig])
  (:import
   (java.util UUID)))

(defn- get-cmgr [deps]
  (cond
    (instance? com.rpl.rama.cluster.ClusterManagerBase deps) deps
    (:cluster-manager (:rama/cluster deps)) (:cluster-manager (:rama/cluster deps))
    (:cluster-manager (:rama deps)) (:cluster-manager (:rama deps))
    (:cluster-manager deps) (:cluster-manager deps)
    (get-in deps [:com.ozimos.workforce.rama/cluster-manager :cluster-manager]) (get-in deps [:com.ozimos.workforce.rama/cluster-manager :cluster-manager])
    :else (throw (ex-info "Could not resolve Rama cluster manager from deps" {:deps-keys (keys deps)}))))

(defn- safe-select-one [path pstate opts]
  (try
    (ramaapi/foreign-select-one path pstate opts)
    (catch Throwable t
      (if (or (instance? rpl.rama.generated.ObjectMissingException t)
              (instance? rpl.rama.generated.ObjectMissingException (.getCause t))
              (clojure.string/includes? (str t) "ObjectMissingException"))
        nil
        (throw t)))))

(defn- safe-select [path pstate opts]
  (try
    (ramaapi/foreign-select path pstate opts)
    (catch Throwable t
      (if (or (instance? rpl.rama.generated.ObjectMissingException t)
              (instance? rpl.rama.generated.ObjectMissingException (.getCause t))
              (clojure.string/includes? (str t) "ObjectMissingException"))
        []
        (throw t)))))

(defn create! [deps user-id jti expires-at]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        session-depot (rama/depot cmgr mod-name "*session-depot")
        session-id (str (random-uuid))]
    (ramaapi/foreign-append! session-depot
      (rama/->SessionStart user-id session-id jti expires-at))
    session-id))

(defn verify [deps session-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        sessions-pstate (rama/pstate cmgr mod-name "$$sessions")]
    (safe-select-one (keypath session-id) sessions-pstate {:pkey session-id})))

(defn revoke! [deps session-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        session-end-depot (rama/depot cmgr mod-name "*session-end-depot")]
    (ramaapi/foreign-append! session-end-depot (rama/->SessionEnd session-id))
    true))

(defn revoke-all! [deps user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        revoke-all-depot (rama/depot cmgr mod-name "*revoke-all-depot")]
    (ramaapi/foreign-append! revoke-all-depot (rama/->RevokeAllForUser user-id))
    true))

(defn list-for-user [deps user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        user-sessions-pstate (rama/pstate cmgr mod-name "$$user-sessions")]
    (safe-select [(keypath user-id) ALL] user-sessions-pstate {:pkey user-id})))

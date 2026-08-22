(ns com.ozimos.auth.revocation.core
  (:require
   [com.ozimos.auth.rama.interface :as rama]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [keypath]]
   [integrant.core :as ig])
  (:import
   (org.springframework.security.oauth2.core OAuth2Error OAuth2TokenValidator OAuth2TokenValidatorResult)
   (org.springframework.security.oauth2.jwt Jwt)))

(defn- get-cmgr [deps]
  (cond
    (instance? com.rpl.rama.cluster.ClusterManagerBase deps) deps
    (:cluster-manager (:rama/cluster deps)) (:cluster-manager (:rama/cluster deps))
    (:cluster-manager (:rama deps)) (:cluster-manager (:rama deps))
    (:cluster-manager deps) (:cluster-manager deps)
    (get-in deps [:com.ozimos.auth.rama/cluster-manager :cluster-manager]) (get-in deps [:com.ozimos.auth.rama/cluster-manager :cluster-manager])
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

(defn is-revoked? [deps jti]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        revoked-pstate (rama/pstate cmgr mod-name "$$revoked-tokens")]
    (some? (safe-select-one (keypath jti) revoked-pstate {:pkey jti}))))

(defn revoke! [deps jti expiry]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        revocation-depot (rama/depot cmgr mod-name "*revocation-depot")]
    (ramaapi/foreign-append! revocation-depot (rama/->Revocation jti expiry)))
  true)

(defn revoke-all-for-user! [deps user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        revoke-all-depot (rama/depot cmgr mod-name "*revoke-all-depot")]
    (ramaapi/foreign-append! revoke-all-depot (rama/->RevokeAllForUser user-id)))
  true)

(defn make-validator
  "Returns an OAuth2TokenValidator<Jwt> that rejects tokens whose jti is revoked."
  [deps]
  (reify OAuth2TokenValidator
    (validate [_ jwt]
      (let [jti (.getId ^Jwt jwt)]
        (if (and jti (is-revoked? deps jti))
          (OAuth2TokenValidatorResult/failure
            (OAuth2Error. "token_revoked"
                          "The token has been revoked"
                          "https://tools.ietf.org/html/rfc6750#section-6.6"))
          (OAuth2TokenValidatorResult/success))))))

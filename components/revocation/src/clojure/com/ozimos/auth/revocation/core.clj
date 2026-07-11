(ns com.ozimos.auth.revocation.core
  (:require
   [com.ozimos.auth.rama.interface :as rama]
   [com.ozimos.auth.rama.module :refer [->Revocation ->RevokeAllForUser]]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [keypath]]
   [integrant.core :as ig])
  (:import
   (java.time Instant)
   (org.springframework.security.oauth2.core OAuth2Error OAuth2TokenValidator OAuth2TokenValidatorResult)
   (org.springframework.security.oauth2.jwt Jwt)))

(defn is-revoked? [deps jti]
  (if-let [store (:atom-store deps)]
    (contains? @store jti)
    (let [{:keys [rama]} deps
          cmgr (:cluster-manager rama)
          mod-name (rama/module-name)
          revoked-pstate (rama/pstate cmgr mod-name "$$revoked-tokens")]
      (some? (ramaapi/foreign-select-one (keypath jti) revoked-pstate {:pkey jti})))))

(defn revoke! [deps jti expiry]
  (if-let [store (:atom-store deps)]
    (swap! store conj jti)
    (let [{:keys [rama]} deps
          cmgr (:cluster-manager rama)
          mod-name (rama/module-name)
          revocation-depot (rama/depot cmgr mod-name "*revocation-depot")]
      (ramaapi/foreign-append! revocation-depot (->Revocation jti expiry))))
  true)

(defn revoke-all-for-user! [deps user-id]
  (if-let [store (:atom-store deps)]
    nil
    (let [{:keys [rama]} deps
          cmgr (:cluster-manager rama)
          mod-name (rama/module-name)
          revoke-all-depot (rama/depot cmgr mod-name "*revoke-all-depot")]
      (ramaapi/foreign-append! revoke-all-depot (->RevokeAllForUser user-id))))
  true)

(defn make-validator
  "Returns an OAuth2TokenValidator<Jwt> that rejects tokens whose jti is revoked."
  [deps]
  (reify OAuth2TokenValidator
    (^OAuth2TokenValidatorResult validate [_ ^Jwt jwt]
      (let [jti (.getId jwt)]
        (if (and jti (is-revoked? deps jti))
          (OAuth2TokenValidatorResult/failure
            (OAuth2Error. "token_revoked"
                          "The token has been revoked"
                          "https://tools.ietf.org/html/rfc6750#section-6.6"))
          (OAuth2TokenValidatorResult/success))))))

(defmethod ig/init-key :revocation/validator [_ {:keys [rama] :as opts}]
  (if rama
    {:rama rama}
    {:atom-store (atom #{})}))

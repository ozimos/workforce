(ns com.ozimos.auth.token.core
  (:require
   [integrant.core :as ig])
  (:import
   (com.nimbusds.jose JWSAlgorithm)
   (com.nimbusds.jose.jwk JWKSet KeyUse RSAKey RSAKeyGenerator)
   (com.nimbusds.jose.proc SecurityContext)
   (java.time Instant)
   (org.springframework.security.oauth2.jose.jwk JWKSource)
   (org.springframework.security.oauth2.jose.jws SignatureAlgorithm)
   (org.springframework.security.oauth2.jwt JwsHeader Jwt JwtClaimsSet JwtDecoder JwtEncoder JwtEncoderParameters NimbusJwtDecoder NimbusJwtEncoder)))

(defn gen-rsa-key
  "Generate a 2048-bit RSA signing key with the given key id."
  ^RSAKey [key-id]
  (-> (RSAKeyGenerator. 2048)
      (.keyID key-id)
      (.keyUse KeyUse/SIGNATURE)
      (.algorithm (JWSAlgorithm/RS256))
      (.generate)))

(defn- make-jwk-source
  ^JWKSource [^RSAKey rsa-key]
  (let [jwk-set (JWKSet. rsa-key)]
    (reify JWKSource
      (^java.util.List get [_ ^org.springframework.security.oauth2.jose.jwk.JWKSelector selector ^SecurityContext ctx]
        (.select selector jwk-set)))))

(defn make-encoder
  ^JwtEncoder [^RSAKey rsa-key]
  (NimbusJwtEncoder. (make-jwk-source rsa-key)))

(defn make-decoder
  (^JwtDecoder [^RSAKey rsa-key]
   (make-decoder rsa-key nil))
  (^JwtDecoder [^RSAKey rsa-key revocation-validator]
   (let [builder (NimbusJwtDecoder/withPublicKey (.toRSAPublicKey rsa-key))
         decoder (if revocation-validator
                   (let [default-validator (org.springframework.security.oauth2.core.OAuth2TokenValidator.
                                             (proxy [Object] []))
                         ;; Use the default timestamp + issuer validators
                         ;; plus our custom revocation validator
                         ts-validator (org.springframework.security.oauth2.jwt.JwtValidators/createDefault)
                         combined (org.springframework.security.oauth2.jwt.DelegatingOAuth2TokenValidator.
                                    [ts-validator revocation-validator])]
                     (.jwtValidator builder combined))
                   builder)]
     (.build decoder))))

(defn issue-access-token
  (^String [^JwtEncoder encoder issuer subject roles jti ttl-seconds]
   (let [now (Instant/now)
         claims (-> (JwtClaimsSet/builder)
                    (.issuer issuer)
                    (.subject subject)
                    (.id jti)
                    (.issuedAt now)
                    (.expiresAt (.plusSeconds now ttl-seconds))
                    (.claim "roles" (vec roles))
                    (.claim "type" "access")
                    (.build))
         header (-> (JwsHeader/with SignatureAlgorithm/RS256) (.build))]
     (.getTokenValue (.encode encoder (JwtEncoderParameters/from header claims))))))

(defn issue-refresh-token
  (^String [^JwtEncoder encoder issuer
            subject jti ttl-seconds]
   (let [now (Instant/now)
         claims (-> (JwtClaimsSet/builder)
                    (.issuer issuer)
                    (.subject subject)
                    (.id jti)
                    (.issuedAt now)
                    (.expiresAt (.plusSeconds now ttl-seconds))
                    (.claim "type" "refresh")
                    (.build))
         header (-> (JwsHeader/with SignatureAlgorithm/RS256) (.build))]
     (.getTokenValue (.encode encoder (JwtEncoderParameters/from header claims))))))

(defn decode
  (^Jwt [^JwtDecoder decoder token-string]
   (.decode decoder token-string)))

(defmethod ig/init-key :token/encoder [_ {:keys [rsa-key-id]
                                          :or {rsa-key-id "auth-template-key-1"}}]
  (let [rsa-key (gen-rsa-key rsa-key-id)]
    {:encoder (make-encoder rsa-key)
     :rsa-key rsa-key}))

(defmethod ig/halt-key! :token/encoder [_ _])

(defmethod ig/init-key :token/decoder [_ {:keys [rsa-key-id revocation-validator]
                                          :or {rsa-key-id "auth-template-key-1"}}]
  (let [rsa-key (gen-rsa-key rsa-key-id)
        ;; Note: In production, the decoder must use the SAME rsa-key as the
        ;; encoder. We re-generate it here for simplicity in dev; production
        ;; should persist the key and pass it via config.
        decoder (make-decoder rsa-key revocation-validator)]
    {:decoder decoder
     :rsa-key rsa-key}))

(defmethod ig/halt-key! :token/decoder [_ _])

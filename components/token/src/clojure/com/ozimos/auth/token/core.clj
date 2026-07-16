(ns com.ozimos.auth.token.core
  (:require
   [com.ozimos.auth.revocation.core :as revocation]
   [integrant.core :as ig])
  (:import
   (com.nimbusds.jose JWSAlgorithm)
   (com.nimbusds.jose.jwk JWKSelector JWKSet KeyUse RSAKey)
   (com.nimbusds.jose.jwk.source JWKSource)
   (com.nimbusds.jose.proc SecurityContext)
   (java.security KeyPairGenerator)
   (java.time Instant)
   (java.util UUID)
   (org.springframework.security.oauth2.core DelegatingOAuth2TokenValidator)
   (org.springframework.security.oauth2.jose.jws SignatureAlgorithm)
   (org.springframework.security.oauth2.jwt JwsHeader Jwt JwtClaimsSet JwtDecoder JwtEncoder JwtEncoderParameters JwtValidators NimbusJwtDecoder NimbusJwtEncoder)))

(defn gen-rsa-key
  "Generate a 2048-bit RSA signing key with the given key id."
  ^RSAKey [key-id]
  (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
        kp (.generateKeyPair kpg)
        pub (.getPublic kp)
        priv (.getPrivate kp)]
    (-> (new com.nimbusds.jose.jwk.RSAKey$Builder pub)
        (.privateKey priv)
        (.keyID key-id)
        (.keyUse KeyUse/SIGNATURE)
        (.algorithm (JWSAlgorithm/RS256))
        (.build))))

(defn- make-jwk-source
  ^JWKSource [^RSAKey rsa-key]
  (let [jwk-set (JWKSet. rsa-key)]
    (reify JWKSource
      (get [_ selector ctx]
        (.select ^JWKSelector selector jwk-set)))))

(defn make-encoder
  ^JwtEncoder [^RSAKey rsa-key]
  (NimbusJwtEncoder. (make-jwk-source rsa-key)))

(defn make-decoder
  (^JwtDecoder [^RSAKey rsa-key]
   (make-decoder rsa-key nil))
  (^JwtDecoder [^RSAKey rsa-key revocation-validator]
   (let [decoder (.build (NimbusJwtDecoder/withPublicKey (.toRSAPublicKey rsa-key)))]
     (if revocation-validator
       (let [ts-validator (org.springframework.security.oauth2.jwt.JwtValidators/createDefault)
             combined (DelegatingOAuth2TokenValidator.
                        [ts-validator revocation-validator])]
         (.setJwtValidator decoder combined)
         decoder)
       decoder))))

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

(defmethod ig/init-key :token/decoder [_ {:keys [encoder rsa-key-id revocation-validator]
                                          :or {rsa-key-id "auth-template-key-1"}}]
  (let [key (or (:rsa-key encoder) (gen-rsa-key rsa-key-id))
        validator (when revocation-validator
                    (revocation/make-validator revocation-validator))
        decoder (make-decoder key validator)]
    {:decoder decoder
     :rsa-key key}))

(defmethod ig/halt-key! :token/decoder [_ _])

(ns com.ozimos.auth.token.interface
  (:require
   [com.ozimos.auth.token.core :as core]))

(defn issue-access-token
  "Issue a short-lived access JWT. Returns the token string.
    `encoder` is a JwtEncoder instance.
    - subject: user-id as string
    - roles: set of role strings
    - jti: unique token id (UUID string)
    - ttl-seconds: time-to-live
    Optionally:
    - org-id: Long (org context for this token)
    - org-role: String (user's role in the org)"
  ([encoder issuer subject roles jti ttl-seconds]
   (core/issue-access-token encoder issuer subject roles jti ttl-seconds))
  ([encoder issuer subject roles jti ttl-seconds org-id org-role]
   (core/issue-access-token encoder issuer subject roles jti ttl-seconds org-id org-role)))

(defn issue-refresh-token
  "Issue a long-lived refresh JWT. Returns the token string."
  [encoder issuer subject jti ttl-seconds]
  (core/issue-refresh-token encoder issuer subject jti ttl-seconds))

(defn issue-mfa-challenge-token
  "Issue a short-lived 2FA MFA challenge JWT (5-minute TTL)."
  [encoder issuer subject ttl-seconds]
  (core/issue-mfa-challenge-token encoder issuer subject ttl-seconds))

(defn decode
  "Decode and validate a JWT string. Returns the Jwt object or throws."
  [decoder token-string]
  (core/decode decoder token-string))

(defn gen-rsa-key
  "Generate a 2048-bit RSA key pair as a Nimbus RSAKey."
  ^com.nimbusds.jose.jwk.RSAKey [key-id]
  (core/gen-rsa-key key-id))

(defn make-encoder
  "Create a NimbusJwtEncoder from an RSAKey."
  ^org.springframework.security.oauth2.jwt.JwtEncoder [rsa-key]
  (core/make-encoder rsa-key))

(defn make-decoder
  "Create a NimbusJwtDecoder from an RSAKey's public key and an optional revocation validator."
  ^org.springframework.security.oauth2.jwt.JwtDecoder [rsa-key revocation-validator]
  (core/make-decoder rsa-key revocation-validator))

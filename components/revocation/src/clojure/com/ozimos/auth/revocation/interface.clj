(ns com.ozimos.auth.revocation.interface
  (:require [com.ozimos.auth.revocation.core :as core]))

(defn is-revoked?
  "Check if a token jti is revoked. Returns true/false."
  [deps jti]
  (core/is-revoked? deps jti))

(defn revoke!
  "Revoke a token by jti with the given expiry (epoch ms)."
  [deps jti expiry]
  (core/revoke! deps jti expiry))

(defn revoke-all-for-user!
  "Revoke all active tokens for a user."
  [deps user-id]
  (core/revoke-all-for-user! deps user-id))

(defn validator
  "Return an OAuth2TokenValidator<Jwt> that checks Rama $$revoked-tokens."
  [deps]
  (core/make-validator deps))
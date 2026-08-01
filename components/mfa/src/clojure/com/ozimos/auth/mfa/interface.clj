(ns com.ozimos.auth.mfa.interface
  (:require
   [com.ozimos.auth.mfa.core :as core]))

(defn generate-secret
  "Generate a 20-byte Base32 encoded TOTP secret."
  []
  (core/generate-secret))

(defn calculate-totp
  "Calculate a 6-digit TOTP string for a given Base32 secret and time counter step."
  [secret-base32 time-step]
  (core/calculate-totp secret-base32 time-step))

(defn verify-totp
  "Verify a 6-digit TOTP string against a Base32 secret across a clock drift window."
  ([secret-base32 code]
   (core/verify-totp secret-base32 code))
  ([secret-base32 code current-time-ms]
   (core/verify-totp secret-base32 code current-time-ms)))

(defn generate-otpauth-url
  "Construct an otpauth:// URI for authenticator QR codes."
  [secret-base32 user-email issuer]
  (core/generate-otpauth-url secret-base32 user-email issuer))

(defn generate-backup-codes
  "Generate 10 single-use recovery backup codes. Returns {:plaintext [...] :hashes [...]}."
  []
  (core/generate-backup-codes))

(defn verify-backup-code
  "Verify a candidate backup code against a set of BCrypt code hashes."
  [candidate-code code-hashes]
  (core/verify-backup-code candidate-code code-hashes))

(defn encrypt-secret
  "Encrypt a TOTP Base32 secret string at rest using AES-GCM."
  ([secret]
   (core/encrypt-secret secret))
  ([secret key-bytes]
   (core/encrypt-secret secret key-bytes)))

(defn decrypt-secret
  "Decrypt an AES-GCM encrypted TOTP Base32 secret string."
  ([encrypted-b64]
   (core/decrypt-secret encrypted-b64))
  ([encrypted-b64 key-bytes]
   (core/decrypt-secret encrypted-b64 key-bytes)))

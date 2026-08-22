(ns com.ozimos.workforce.mfa.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.mfa.interface :as mfa]))

(deftest base32-roundtrip-test
  (testing "Base32 encoding and decoding round-trip"
    (let [secret (mfa/generate-secret)]
      (is (= 32 (.length secret)))
      (is (re-matches #"[A-Z2-7]+" secret))
      (let [decoded (com.ozimos.workforce.mfa.core/decode-base32 secret)
            re-encoded (com.ozimos.workforce.mfa.core/encode-base32 decoded)]
        (is (= secret re-encoded))))))

(deftest totp-verification-test
  (testing "RFC 6238 TOTP calculation and verification"
    (let [secret (mfa/generate-secret)
          now (System/currentTimeMillis)
          current-step (quot (quot now 1000) 30)
          code (mfa/calculate-totp secret current-step)]
      (is (= 6 (.length code)))
      (is (re-matches #"\d{6}" code))
      (is (true? (mfa/verify-totp secret code now)))
      (is (false? (mfa/verify-totp secret "000000" now)))
      (is (false? (mfa/verify-totp secret "12345" now))))))

(deftest totp-clock-drift-test
  (testing "TOTP verification across clock drift window (step -1, 0, +1)"
    (let [secret (mfa/generate-secret)
          now (System/currentTimeMillis)
          current-step (quot (quot now 1000) 30)
          code-past (mfa/calculate-totp secret (dec current-step))
          code-future (mfa/calculate-totp secret (inc current-step))
          code-far-future (mfa/calculate-totp secret (+ current-step 5))]
      (is (true? (mfa/verify-totp secret code-past now)) "past step within 30s verified")
      (is (true? (mfa/verify-totp secret code-future now)) "future step within 30s verified")
      (is (false? (mfa/verify-totp secret code-far-future now)) "step beyond window rejected"))))

(deftest backup-codes-test
  (testing "Backup code generation and verification"
    (let [{:keys [plaintext hashes]} (mfa/generate-backup-codes)]
      (is (= 10 (count plaintext)))
      (is (= 10 (count hashes)))
      (let [code1 (first plaintext)
            match (mfa/verify-backup-code code1 hashes)]
        (is (some? match))
        (is (nil? (mfa/verify-backup-code "invalidcode" hashes)))))))

(deftest secret-encryption-test
  (testing "AES-GCM secret encryption and decryption at rest"
    (let [secret (mfa/generate-secret)
          encrypted (mfa/encrypt-secret secret)
          decrypted (mfa/decrypt-secret encrypted)]
      (is (not= secret encrypted))
      (is (= secret decrypted)))))

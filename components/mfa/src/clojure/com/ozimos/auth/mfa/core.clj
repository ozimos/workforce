(ns com.ozimos.auth.mfa.core
  (:require
   [clojure.string :as string])
  (:import
   (java.nio ByteBuffer)
   (java.security SecureRandom)
   (javax.crypto Cipher Mac)
   (javax.crypto.spec GCMParameterSpec SecretKeySpec)
   (org.springframework.security.crypto.bcrypt BCryptPasswordEncoder)))

(def ^:private base32-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

(defn encode-base32
  "Encode a byte array into a Base32 string (RFC 4648)."
  [^bytes bytes-data]
  (let [sb (StringBuilder.)
        len (alength bytes-data)]
    (loop [i 0 index 0 digit 0]
      (if (< i len)
        (let [b (bit-and (aget bytes-data i) 0xff)]
          (if (> index 3)
            (let [b-curr (bit-and (bit-shift-right b (- 8 (+ index 5))) 0x1f)
                  _ (.append sb (.charAt base32-alphabet b-curr))
                  index' (- (+ index 5) 8)]
              (recur i index' b))
            (let [index' (+ index 5)
                  b-curr (bit-and (bit-shift-right b (- 8 index')) 0x1f)]
              (.append sb (.charAt base32-alphabet b-curr))
              (recur (inc i) index' b))))
        (when (pos? index)
          (let [b-curr (bit-and (bit-shift-left digit (- 5 index)) 0x1f)]
            (.append sb (.charAt base32-alphabet b-curr))))))
    (.toString sb)))

(defn decode-base32
  "Decode a Base32 string into a byte array (RFC 4648)."
  [^String base32-str]
  (let [clean-str (-> base32-str string/upper-case (string/replace #"=" ""))
        len (.length clean-str)
        out-bytes (byte-array (quot (* len 5) 8))]
    (loop [i 0 index 0 current-byte 0 out-idx 0]
      (if (< i len)
        (let [ch (.charAt clean-str i)
              val (.indexOf base32-alphabet (int ch))]
          (if (= val -1)
            (throw (IllegalArgumentException. (str "Invalid Base32 char: " ch)))
            (let [index' (+ index 5)]
              (if (>= index' 8)
                (let [index'' (- index' 8)
                      b (bit-or current-byte (bit-shift-right val index''))]
                  (aset-byte out-bytes out-idx (byte b))
                  (recur (inc i) index'' (bit-and (bit-shift-left val (- 8 index'')) 0xff) (inc out-idx)))
                (recur (inc i) index' (bit-or current-byte (bit-shift-left val (- 8 index'))) out-idx)))))
        out-bytes))))

(defn generate-secret
  "Generate a 20-byte (160-bit) Base32 encoded TOTP secret."
  []
  (let [bytes-data (byte-array 20)
        random (SecureRandom.)]
    (.nextBytes random bytes-data)
    (encode-base32 bytes-data)))

(defn- hmac-sha1
  [^bytes key-bytes ^bytes data-bytes]
  (let [mac (Mac/getInstance "HmacSHA1")
        key-spec (SecretKeySpec. key-bytes "HmacSHA1")]
    (.init mac key-spec)
    (.doFinal mac data-bytes)))

(defn calculate-totp
  "Calculate a 6-digit TOTP string for a given Base32 secret and time counter step (RFC 6238)."
  [^String secret-base32 ^long time-step]
  (let [key-bytes (decode-base32 secret-base32)
        msg-bytes (-> (ByteBuffer/allocate 8) (.putLong time-step) .array)
        hash (hmac-sha1 key-bytes msg-bytes)
        offset (bit-and (aget hash (dec (alength hash))) 0xf)
        binary (bit-or
                 (bit-shift-left (bit-and (aget hash offset) 0x7f) 24)
                 (bit-shift-left (bit-and (aget hash (+ offset 1)) 0xff) 16)
                 (bit-shift-left (bit-and (aget hash (+ offset 2)) 0xff) 8)
                 (bit-and (aget hash (+ offset 3)) 0xff))
        otp (mod binary 1000000)]
    (String/format "%06d" (into-array Object [otp]))))

(defn verify-totp
  "Verify a 6-digit TOTP string against a Base32 secret across a clock drift window (step -1, 0, +1)."
  ([^String secret-base32 ^String code]
   (verify-totp secret-base32 code (System/currentTimeMillis)))
  ([^String secret-base32 ^String code ^long current-time-ms]
   (when (and (string? code) (= 6 (.length code)))
     (let [current-step (quot (quot current-time-ms 1000) 30)]
       (boolean
         (some (fn [step-offset]
                 (= code (calculate-totp secret-base32 (+ current-step step-offset))))
               [-1 0 1]))))))

(defn generate-otpauth-url
  "Construct an otpauth:// URI for authenticator QR codes."
  [^String secret-base32 ^String user-email ^String issuer]
  (str "otpauth://totp/" issuer ":" user-email "?secret=" secret-base32 "&issuer=" issuer))

(def ^:private bcrypt (BCryptPasswordEncoder. 12))

(defn generate-backup-codes
  "Generate 10 single-use 8-character alphanumeric recovery backup codes.
   Returns {:plaintext [code1 ... code10] :hashes [hash1 ... hash2]}."
  []
  (let [random (SecureRandom.)
        chars "abcdefghijklmnopqrstuvwxyz0123456789"
        gen-code (fn []
                   (apply str (repeatedly 8 #(nth chars (.nextInt random 36)))))
        plain (vec (repeatedly 10 gen-code))
        hashes (vec (map (fn [code] (.encode bcrypt code)) plain))]
    {:plaintext plain
     :hashes hashes}))

(defn verify-backup-code
  "Verify a candidate backup code against a set/vector of BCrypt code hashes.
   Returns the matching hash if valid, or nil if invalid."
  [candidate-code code-hashes]
  (some (fn [hash-val]
          (when (.matches bcrypt candidate-code hash-val)
            hash-val))
        code-hashes))

(def ^:private default-system-key-bytes
  ;; 32-byte (256-bit) default key for AES-GCM secret encryption at rest
  (.getBytes "best-auth-mfa-system-secret-key-32b" "UTF-8"))

(defn encrypt-secret
  "Encrypt a TOTP Base32 secret string at rest using AES-GCM."
  ([^String secret]
   (encrypt-secret secret default-system-key-bytes))
  ([^String secret ^bytes key-bytes]
   (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
         iv (byte-array 12)
         _ (.nextBytes (SecureRandom.) iv)
         gcm-spec (GCMParameterSpec. 128 iv)
         secret-key (SecretKeySpec. key-bytes "AES")]
     (.init cipher Cipher/ENCRYPT_MODE secret-key gcm-spec)
     (let [encrypted (.doFinal cipher (.getBytes secret "UTF-8"))
           combined (byte-array (+ 12 (alength encrypted)))]
       (System/arraycopy iv 0 combined 0 12)
       (System/arraycopy encrypted 0 combined 12 (alength encrypted))
       (.encodeToString (java.util.Base64/getEncoder) combined)))))

(defn decrypt-secret
  "Decrypt an AES-GCM encrypted TOTP Base32 secret string."
  ([^String encrypted-b64]
   (decrypt-secret encrypted-b64 default-system-key-bytes))
  ([^String encrypted-b64 ^bytes key-bytes]
   (let [combined (.decode (java.util.Base64/getDecoder) encrypted-b64)
         iv (byte-array 12)
         _ (System/arraycopy combined 0 iv 0 12)
         cipher-len (- (alength combined) 12)
         cipher-bytes (byte-array cipher-len)
         _ (System/arraycopy combined 12 cipher-bytes 0 cipher-len)
         cipher (Cipher/getInstance "AES/GCM/NoPadding")
         gcm-spec (GCMParameterSpec. 128 iv)
         secret-key (SecretKeySpec. key-bytes "AES")]
     (.init cipher Cipher/DECRYPT_MODE secret-key gcm-spec)
     (String. (.doFinal cipher cipher-bytes) "UTF-8"))))

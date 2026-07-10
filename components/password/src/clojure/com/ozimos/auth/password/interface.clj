(ns com.ozimos.auth.password.interface
  (:require [com.ozimos.auth.password.core :as core]))

(defn encode
  "Encode a plaintext password using BCrypt. Returns the hash string."
  [encoder plain]
  (core/encode encoder plain))

(defn matches?
  "Check if a plaintext password matches a BCrypt hash."
  [encoder plain encoded]
  (core/matches? encoder plain encoded))

(defn make-encoder
  "Create a BCryptPasswordEncoder with the given strength (default 12)."
  ^org.springframework.security.crypto.password.PasswordEncoder [strength]
  (core/make-encoder strength))
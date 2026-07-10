(ns com.ozimos.auth.password.core
  (:require [integrant.core :as ig])
  (:import [org.springframework.security.crypto.bcrypt BCryptPasswordEncoder]
           [org.springframework.security.crypto.password PasswordEncoder]))

(defn make-encoder
  (^PasswordEncoder [] (make-encoder 12))
  (^PasswordEncoder [strength]
   (BCryptPasswordEncoder. ^int (or strength 12))))

(defn encode [^PasswordEncoder encoder plain]
  (.encode encoder plain))

(defn matches? [^PasswordEncoder encoder plain encoded]
  (.matches encoder plain encoded))

(defmethod ig/init-key :password/encoder [_ {:keys [strength]
                                              :or {strength 12}}]
  (make-encoder strength))

(defmethod ig/halt-key! :password/encoder [_ _])
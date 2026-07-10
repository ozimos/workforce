(ns com.ozimos.auth.user.interface
  (:require [com.ozimos.auth.user.core :as core]))

(defn register! [deps input]
  (core/register! deps input))

(defn find-by-username [deps username]
  (core/find-by-username deps username))

(defn find-by-id [deps user-id]
  (core/find-by-id deps user-id))

(defn verify! [deps user-id]
  (core/verify! deps user-id))

(defn change-password! [deps user-id new-pwd-hash]
  (core/change-password! deps user-id new-pwd-hash))
(ns com.ozimos.workforce.user-rama.interface
  (:require
   [com.ozimos.workforce.user.interface :as user]))

(def register! user/register!)
(def find-by-id user/find-by-id)
(def find-by-username user/find-by-username)
(def find-by-email user/find-by-email)
(def find-by-identifier user/find-by-identifier)
(def update-username! user/update-username!)
(def verify! user/verify!)
(def change-password! user/change-password!)
(def encode-password user/encode-password)
(def create-reset-token! user/create-reset-token!)
(def validate-reset-token user/validate-reset-token)
(def clear-reset-token! user/clear-reset-token!)

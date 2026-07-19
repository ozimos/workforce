(ns com.ozimos.auth.schema.interface
  (:require
   [malli.core :as m]
   [malli.util :as mu]))

(def email
  [:and
   :string
   [:re #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"]])

(def username
  [:and
   [:string {:min 3 :max 32}]
   [:re #"^[a-zA-Z0-9_-]+$"]])

(def password
  [:string {:min 8 :max 128}])

(def role [:enum "ROLE_USER" "ROLE_ADMIN"])
(def roles [:vector {:min 1 :max 10} role])

(defn valid-email? [e] (m/validate email e))
(defn valid-username? [u] (m/validate username u))
(defn valid-password? [p] (m/validate password p))


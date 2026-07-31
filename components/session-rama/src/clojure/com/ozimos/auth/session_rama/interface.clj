(ns com.ozimos.auth.session-rama.interface
  (:require
   [com.ozimos.auth.session.interface :as session]))

(def create! session/create!)
(def verify session/verify)
(def revoke! session/revoke!)
(def revoke-all! session/revoke-all!)
(def list-for-user session/list-for-user)

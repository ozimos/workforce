(ns com.ozimos.workforce.session-rama.interface
  (:require
   [com.ozimos.workforce.session.interface :as session]))

(def create! session/create!)
(def verify session/verify)
(def revoke! session/revoke!)
(def revoke-all! session/revoke-all!)
(def list-for-user session/list-for-user)

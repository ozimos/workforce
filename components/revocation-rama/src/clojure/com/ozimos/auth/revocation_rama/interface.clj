(ns com.ozimos.auth.revocation-rama.interface
  (:require
   [com.ozimos.auth.revocation.interface :as revocation]))

(def is-revoked? revocation/is-revoked?)
(def revoke! revocation/revoke!)
(def revoke-all-for-user! revocation/revoke-all-for-user!)
(def validator revocation/validator)

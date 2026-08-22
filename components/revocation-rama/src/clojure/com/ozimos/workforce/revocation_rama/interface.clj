(ns com.ozimos.workforce.revocation-rama.interface
  (:require
   [com.ozimos.workforce.revocation.interface :as revocation]))

(def is-revoked? revocation/is-revoked?)
(def revoke! revocation/revoke!)
(def revoke-all-for-user! revocation/revoke-all-for-user!)
(def validator revocation/validator)

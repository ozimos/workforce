(ns com.ozimos.workforce.saml.interface
  (:require
   [com.ozimos.workforce.saml.core :as core]))

(defn handle-saml-assertion
  "Processes a SAML 2.0 assertion (email, name, name-id) from an IdP.
   Links SAML account via `user/link-oauth-account!` with provider \"saml\",
   provisions a local user if needed, creates session, and issues JWT access token
   with `auth-method=\"saml\"`. Returns [true response-map] or [false {:errors ...}]."
  [deps saml-info]
  (core/handle-saml-assertion deps saml-info))

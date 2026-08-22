(ns com.ozimos.workforce.webauthn.interface
  (:require
   [com.ozimos.workforce.webauthn.core :as core]))

(defn make-relying-party
  "Construct a Yubico RelyingParty instance."
  [config]
  (core/make-relying-party config))

(defn make-credential-repository
  "Create a Yubico CredentialRepository instance backed by function lookups."
  [fns]
  (core/make-credential-repository fns))

(defn start-registration-options
  "Generate PublicKeyCredentialCreationOptions for WebAuthn registration."
  [rp user-id username email]
  (core/start-registration-options rp user-id username email))

(defn creation-options-to-json
  "Convert PublicKeyCredentialCreationOptions to JSON string."
  [options]
  (core/creation-options-to-json options))

(defn finish-registration
  "Validate an AuthenticatorAttestationResponse JSON against CreationOptions JSON."
  [rp creation-options-json response-json]
  (core/finish-registration rp creation-options-json response-json))

(defn start-assertion-options
  "Generate PublicKeyCredentialRequestOptions for WebAuthn authentication."
  [rp]
  (core/start-assertion-options rp))

(defn assertion-request-to-json
  "Convert AssertionRequest to JSON string containing RequestOptions."
  [request]
  (core/assertion-request-to-json request))

(defn finish-assertion
  "Validate an AuthenticatorAssertionResponse JSON against AssertionRequest JSON."
  [rp assertion-request-json response-json]
  (core/finish-assertion rp assertion-request-json response-json))

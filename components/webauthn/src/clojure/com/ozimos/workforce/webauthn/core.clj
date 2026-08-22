(ns com.ozimos.workforce.webauthn.core
  (:require
   [integrant.core :as ig]
   [jsonista.core :as json])
  (:import
   (com.yubico.webauthn
     AssertionRequest
     AssertionResult
     CredentialRepository
     FinishAssertionOptions
     FinishRegistrationOptions
     RegisteredCredential
     RegistrationResult
     RelyingParty
     StartAssertionOptions
     StartRegistrationOptions)
   (com.yubico.webauthn.data
     AttestationConveyancePreference
     AuthenticatorAttachment
     AuthenticatorSelectionCriteria
     ByteArray
     PublicKeyCredential
     PublicKeyCredentialCreationOptions
     PublicKeyCredentialDescriptor
     PublicKeyCredentialRequestOptions
     RelyingPartyIdentity
     UserIdentity)
   (java.util Collections Optional Set)))

(defn- to-byte-array
  ^ByteArray [^bytes b]
  (ByteArray. b))

(defn- byte-array-from-hex
  ^ByteArray [^String hex-str]
  (ByteArray/fromHex hex-str))

(defn make-credential-repository
  "Create a Yubico CredentialRepository instance backed by function lookups."
  [{:keys [get-credential-ids-for-username
           get-user-handle-for-username
           get-username-for-user-handle
           lookup-credential
           lookup-all-credentials]}]
  (reify CredentialRepository
    (getCredentialIdsForUsername [_ username]
      (if get-credential-ids-for-username
        (get-credential-ids-for-username username)
        (Collections/emptySet)))

    (getUserHandleForUsername [_ username]
      (if get-user-handle-for-username
        (Optional/ofNullable (get-user-handle-for-username username))
        (Optional/empty)))

    (getUsernameForUserHandle [_ user-handle]
      (if get-username-for-user-handle
        (Optional/ofNullable (get-username-for-user-handle user-handle))
        (Optional/empty)))

    (lookup [_ credential-id user-handle]
      (if lookup-credential
        (Optional/ofNullable (lookup-credential credential-id user-handle))
        (Optional/empty)))

    (lookupAll [_ credential-id]
      (if lookup-all-credentials
        (lookup-all-credentials credential-id)
        (Collections/emptySet)))))

(defn make-relying-party
  "Construct a Yubico RelyingParty instance."
  ^RelyingParty [{:keys [rp-id rp-name origins credential-repository]}]
  (let [rp-identity (-> (RelyingPartyIdentity/builder)
                        (.id rp-id)
                        (.name rp-name)
                        (.build))
        repo (or credential-repository
                 (make-credential-repository {}))]
    (-> (RelyingParty/builder)
        (.identity rp-identity)
        (.credentialRepository repo)
        (.origins (Set/copyOf (if (coll? origins) origins [origins])))
        (.build))))

(defn start-registration-options
  "Generate PublicKeyCredentialCreationOptions for WebAuthn registration."
  [^RelyingParty rp ^long user-id ^String username ^String email]
  (let [user-handle-bytes (-> (java.nio.ByteBuffer/allocate 8) (.putLong user-id) .array)
        user-handle (to-byte-array user-handle-bytes)
        user-id-obj (-> (UserIdentity/builder)
                        (.name username)
                        (.displayName (or email username))
                        (.id user-handle)
                        (.build))
        start-opts (-> (StartRegistrationOptions/builder)
                       (.user user-id-obj)
                       (.build))]
    (.startRegistration rp start-opts)))

(defn creation-options-to-json
  "Convert PublicKeyCredentialCreationOptions to JSON string."
  [^PublicKeyCredentialCreationOptions options]
  (.toCredentialsCreateJson options))

(defn finish-registration
  "Validate an AuthenticatorAttestationResponse JSON against CreationOptions JSON.
   Returns a map with :credential-id, :public-key-cose, :sign-count, and :user-handle."
  [^RelyingParty rp ^String creation-options-json ^String response-json]
  (let [options (PublicKeyCredentialCreationOptions/fromJson creation-options-json)
        pkc (PublicKeyCredential/parseRegistrationResponseJson response-json)
        finish-opts (-> (FinishRegistrationOptions/builder)
                        (.request options)
                        (.response pkc)
                        (.build))
        ^RegistrationResult result (.finishRegistration rp finish-opts)
        key-id (.getKeyId result)
        pub-key (.getPublicKeyCose result)
        user-handle (.getUserHandle result)]
    {:credential-id (.getHex key-id)
     :public-key-cose (.getHex pub-key)
     :sign-count (.getSignatureCount result)
     :user-handle (when user-handle (.getHex user-handle))}))

(defn start-assertion-options
  "Generate PublicKeyCredentialRequestOptions for WebAuthn authentication."
  [^RelyingParty rp]
  (let [start-opts (-> (StartAssertionOptions/builder) (.build))]
    (.startAssertion rp start-opts)))

(defn assertion-request-to-json
  "Convert AssertionRequest to JSON string containing RequestOptions."
  [^AssertionRequest request]
  (.toCredentialsGetJson (.getPublicKeyCredentialRequestOptions request)))

(defn finish-assertion
  "Validate an AuthenticatorAssertionResponse JSON against AssertionRequest or RequestOptions.
   Returns a map with :credential-id, :sign-count, and :user-handle."
  [^RelyingParty rp ^String assertion-request-json ^String response-json]
  (let [request (AssertionRequest/fromJson assertion-request-json)
        pkc (PublicKeyCredential/parseAssertionResponseJson response-json)
        finish-opts (-> (FinishAssertionOptions/builder)
                        (.request request)
                        (.response pkc)
                        (.build))
        ^AssertionResult result (.finishAssertion rp finish-opts)
        credential (.getCredential result)]
    {:credential-id (.. result getCredential getCredentialId getHex)
     :sign-count (.getSignatureCount result)
     :user-handle (when-let [uh (.getUserHandle result)] (.getHex uh))
     :success (.isSuccess result)}))

(defmethod ig/init-key :webauthn/rp [_ {:keys [rp-id rp-name origin]
                                       :or {rp-id "localhost"
                                            rp-name "BestAuth"
                                            origin "http://localhost:8080"}}]
  {:rp (make-relying-party {:rp-id rp-id
                           :rp-name rp-name
                           :origins origin})
   :rp-id rp-id
   :rp-name rp-name
   :origin origin})

(defmethod ig/halt-key! :webauthn/rp [_ _])

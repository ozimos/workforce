(ns com.ozimos.workforce.user.core
  (:require
   [clojure.string :as str]
   [com.ozimos.workforce.rama.interface :as rama]
   [com.ozimos.workforce.schema.interface :as schema]
   [com.ozimos.workforce.schema.interface.registration :as registration]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [ALL keypath]]
   [integrant.core :as ig]
   [malli.core :as m])
  (:import
   (java.util UUID)
   (org.springframework.security.crypto.bcrypt BCryptPasswordEncoder)
   (org.springframework.security.crypto.password PasswordEncoder)))

(defn- get-cmgr [deps]
  (cond
    (instance? com.rpl.rama.cluster.ClusterManagerBase deps) deps
    (:cluster-manager (:rama/cluster deps)) (:cluster-manager (:rama/cluster deps))
    (:cluster-manager (:rama deps)) (:cluster-manager (:rama deps))
    (:cluster-manager deps) (:cluster-manager deps)
    (get-in deps [:com.ozimos.workforce.rama/cluster-manager :cluster-manager]) (get-in deps [:com.ozimos.workforce.rama/cluster-manager :cluster-manager])
    :else (throw (ex-info "Could not resolve Rama cluster manager from deps" {:deps-keys (keys deps)}))))

(defn- ->user-id [id]
  (when id
    (if (number? id)
      (long id)
      (try (Long/parseLong (str id)) (catch Exception _ nil)))))

(defn- safe-select-one [path pstate]
  (try
    (ramaapi/foreign-select-one path pstate)
    (catch Throwable t
      (if (or (instance? rpl.rama.generated.ObjectMissingException t)
              (instance? rpl.rama.generated.ObjectMissingException (.getCause t))
              (str/includes? (str t) "ObjectMissingException"))
        nil
        (throw t)))))

(defn- safe-select [path pstate]
  (try
    (ramaapi/foreign-select path pstate)
    (catch Throwable t
      (if (or (instance? rpl.rama.generated.ObjectMissingException t)
              (instance? rpl.rama.generated.ObjectMissingException (.getCause t))
              (str/includes? (str t) "ObjectMissingException"))
        []
        (throw t)))))

(defn- make-encoder
  (^PasswordEncoder [] (BCryptPasswordEncoder. 12))
  (^PasswordEncoder [strength] (BCryptPasswordEncoder. ^int (or strength 12))))

(defn encode-password [deps plain]
  (let [encoder (or (:password-encoder deps) (make-encoder))]
    (.encode ^PasswordEncoder encoder plain)))

(defn matches-password? [deps plain encoded]
  (let [encoder (or (:password-encoder deps) (make-encoder))]
    (.matches ^PasswordEncoder encoder plain encoded)))

(defn update-username! [deps user-id new-username]
  (if-not (m/validate schema/username new-username)
    [false {:errors {:new-username ["Must be 3–32 characters, letters, numbers, underscores, or hyphens."]}}]
    (let [uid (->user-id user-id)
          cmgr (get-cmgr deps)
          mod-name (rama/module-name)
          depot (rama/depot cmgr mod-name "*username-change-depot")
          result (ramaapi/foreign-append! depot
                   (rama/->UsernameChange uid new-username))]
      (case (get result "auth")
        :ok    [true new-username]
        :taken [false {:errors {:new-username ["Username already taken."]}}]
        [false {:errors {:new-username ["Update failed."]}}]))))

(defn- derive-username-from-email
  ([email]
   (derive-username-from-email email ""))
  ([email suffix]
   (let [local-part (-> email (str/split #"@") first)
         sanitized  (str/replace local-part #"[^a-zA-Z0-9_-]" "_")
         max-len    (- 32 (count suffix))
         base       (apply str (take max-len sanitized))
         base       (if (< (count base) 3) (str base "_user") base)]
     (str base suffix))))

(defn register! [deps input]
  (when-not (m/validate registration/register-request input)
    (throw (ex-info "Invalid registration input" {:input input})))
  (let [{:keys [email password roles]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        reg-depot (rama/depot cmgr mod-name "*registration-depot")
        pwd-hash (encode-password deps password)
        roles (or (vec roles) ["ROLE_USER"])
        email->id (rama/pstate cmgr mod-name "$$email->id")
        existing-email (safe-select-one (keypath email) email->id)]
    (if existing-email
      [false {:errors {:email ["Email already taken."]}}]
      (let [base-username (or (:username input) (derive-username-from-email email))]
        (loop [username base-username
               attempt 1]
          (let [uuid (str (random-uuid))
                result (ramaapi/foreign-append! reg-depot
                         (rama/->Registration uuid username pwd-hash email roles))]
            (if-let [user-id (get result "auth")]
              (let [user {:id user-id
                          :username username
                          :email email
                          :verified false
                          :roles roles}]
                [true user])
              (if (< attempt 5)
                (recur (derive-username-from-email email (str "_" (inc attempt)))
                       (inc attempt))
                [false {:errors {:username ["Username already taken."]}}]))))))))

(defn- read-profile [profiles user-id]
  (let [profile (safe-select-one (keypath user-id) profiles)]
    (when (:username profile)
      (update profile :roles set))))

(defn find-by-username [deps username]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        username->id (rama/pstate cmgr mod-name "$$username->id")
        profiles (rama/pstate cmgr mod-name "$$profiles")
        user-id (safe-select-one (keypath username) username->id)]
    (when user-id
      (let [uid (->user-id user-id)
            profile (read-profile profiles uid)]
        (when profile
          (assoc profile :id uid))))))

(defn find-by-id [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        profiles (rama/pstate cmgr mod-name "$$profiles")
        profile (read-profile profiles uid)]
    (when profile
      (assoc profile :id uid))))

(defn find-by-email [deps email]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        email->id (rama/pstate cmgr mod-name "$$email->id")
        profiles (rama/pstate cmgr mod-name "$$profiles")
        user-id (safe-select-one (keypath email) email->id)]
    (when user-id
      (let [uid (->user-id user-id)
            profile (read-profile profiles uid)]
        (when profile
          (assoc profile :id uid))))))

(defn find-by-identifier [deps identifier]
  (or (find-by-email deps identifier)
      (find-by-username deps identifier)))

(defn verify! [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        verify-depot (rama/depot cmgr mod-name "*verification-depot")]
    (ramaapi/foreign-append! verify-depot (rama/->Verification uid))
    true))

(defn change-password! [deps user-id new-pwd-hash]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pwd-change-depot (rama/depot cmgr mod-name "*password-change-depot")]
    (ramaapi/foreign-append! pwd-change-depot (rama/->PasswordChange uid new-pwd-hash))
    true))

(defn create-reset-token! [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        reset-depot (rama/depot cmgr mod-name "*reset-token-depot")
        token (str (random-uuid))
        expires-at (+ (System/currentTimeMillis) (* 15 60 1000))]
    (ramaapi/foreign-append! reset-depot (rama/->ResetToken token uid expires-at))
    token))

(defn validate-reset-token [deps token]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        reset-pstate (rama/pstate cmgr mod-name "$$reset-tokens")
        entry (safe-select-one (keypath token) reset-pstate)]
    (when entry
      (let [now (System/currentTimeMillis)]
        (when (< (:expires-at entry) now)
          (throw (ex-info "Reset token expired" {:token token :expires-at (:expires-at entry)})))
        (:user-id entry)))))

(defn clear-reset-token! [deps token]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        clear-depot (rama/depot cmgr mod-name "*clear-reset-token-depot")]
    (ramaapi/foreign-append! clear-depot (rama/->ClearResetToken token))))

(defn- now-ms [] (System/currentTimeMillis))

(defn create-org! [deps input]
  (let [{:keys [name owner-user-id]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        org-create-depot (rama/depot cmgr mod-name "*org-create-depot")
        uuid (str (random-uuid))
        created-at (now-ms)
        ;; Check org name uniqueness
        org-name->id (rama/pstate cmgr mod-name "$$org-name->id")
        existing-org (safe-select-one (keypath name) org-name->id)]
    (if existing-org
      [false {:errors {:name ["Organization name already taken"]}}]
      (let [result (ramaapi/foreign-append! org-create-depot
                     (rama/->OrgCreate uuid name owner-user-id created-at))]
        (if-let [org-id (get result "auth")]
          (let [org {:id org-id
                     :name name
                     :owner-user-id owner-user-id
                     :created-at created-at}]
            [true org])
          [false {:errors {:name ["Organization name already taken"]}}])))))

(defn find-org-by-id [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        orgs (rama/pstate cmgr mod-name "$$orgs")
        org (safe-select-one (keypath org-id) orgs)]
    (when (:name org)
      (assoc org :id org-id))))

(defn find-orgs-for-user [deps user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        user-orgs (rama/pstate cmgr mod-name "$$user-orgs")
        memberships (rama/pstate cmgr mod-name "$$memberships")
        orgs (rama/pstate cmgr mod-name "$$orgs")
        org-ids (safe-select [(keypath user-id) ALL] user-orgs)]
    (->> org-ids
         (map (fn [org-id]
                (let [membership (safe-select-one (keypath user-id org-id) memberships)
                      org (safe-select-one (keypath org-id) orgs)]
                  {:id org-id
                   :name (:name org)
                   :role (:role membership)
                   :status (:status membership)
                   :joined-at (:joined-at membership)})))
         (filter :name)
         vec)))

(defn invite-to-org! [deps input]
  (let [{:keys [org-id email role invited-by]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        invite-depot (rama/depot cmgr mod-name "*org-invite-depot")
        invitation-id (str (random-uuid))
        created-at (now-ms)
        expires-at (+ created-at (* 7 24 60 60 1000))]
    (ramaapi/foreign-append! invite-depot
      (rama/->OrgInvite invitation-id org-id email role invited-by created-at expires-at))
    [true {:invitation-id invitation-id}]))

(defn join-org! [deps input]
  (let [{:keys [user-id invitation-id]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        invitations (rama/pstate cmgr mod-name "$$invitations")
        invitation (safe-select-one (keypath invitation-id) invitations)]
    (if (nil? invitation)
      [false {:errors {:invitation ["Invitation not found"]}}]
      (if (= (:status invitation) "ACCEPTED")
        [false {:errors {:invitation ["Invitation already accepted"]}}]
        (if (< (:expires-at invitation) (now-ms))
          [false {:errors {:invitation ["Invitation expired"]}}]
          (let [join-depot (rama/depot cmgr mod-name "*org-join-depot")
                joined-at (now-ms)]
            (ramaapi/foreign-append! join-depot
              (rama/->OrgJoin user-id invitation-id joined-at))
            [true {:org-id (:org-id invitation)}]))))))

(defn switch-org! [deps user-id org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        switch-depot (rama/depot cmgr mod-name "*org-switch-depot")]
    (ramaapi/foreign-append! switch-depot (rama/->OrgSwitch user-id org-id))
    true))

(defn get-active-org [deps user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        active-org (rama/pstate cmgr mod-name "$$user-active-org")]
    (safe-select-one (keypath user-id) active-org)))

(defn list-members [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        org-members (rama/pstate cmgr mod-name "$$org-members")
        org-users (rama/pstate cmgr mod-name "$$org-users")
        user-ids (safe-select [(keypath org-id) ALL] org-users)]
    (->> user-ids
         (map (fn [uid]
                (let [membership (safe-select-one (keypath org-id uid) org-members)]
                  {:user-id uid
                   :role (:role membership)
                   :status (:status membership)
                   :joined-at (:joined-at membership)})))
         (filter :role)
         vec)))

(defn update-member-role! [deps org-id target-user-id new-role]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        update-depot (rama/depot cmgr mod-name "*org-member-update-depot")]
    (ramaapi/foreign-append! update-depot
      (rama/->OrgMemberUpdate org-id target-user-id new-role))
    true))

(defn remove-member! [deps org-id target-user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        remove-depot (rama/depot cmgr mod-name "*org-member-remove-depot")]
    (ramaapi/foreign-append! remove-depot
      (rama/->OrgMemberRemove org-id target-user-id))
    true))

(defn list-invitations-for-user [deps email]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        email->invitations (rama/pstate cmgr mod-name "$$email->invitations")
        invitations (rama/pstate cmgr mod-name "$$invitations")
        orgs (rama/pstate cmgr mod-name "$$orgs")
        invitation-ids (safe-select [(keypath email) ALL] email->invitations)]
    (->> invitation-ids
         (map (fn [inv-id]
                (let [inv (safe-select-one (keypath inv-id) invitations)
                      org (safe-select-one (keypath (:org-id inv)) orgs)]
                  {:invitation/id inv-id
                   :invitation/org-id (:org-id inv)
                   :invitation/org-name (:name org)
                   :invitation/role (:role inv)
                   :invitation/status (:status inv)
                   :invitation/expires-at (:expires-at inv)})))
         (filter #(= (:invitation/status %) "PENDING"))
         vec)))

(defn get-membership [deps user-id org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        memberships (rama/pstate cmgr mod-name "$$memberships")]
    (safe-select-one (keypath user-id org-id) memberships)))

;; --- MFA Functions ---

(defn mfa-enabled? [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        mfa-enabled-pstate (rama/pstate cmgr mod-name "$$mfa-enabled")]
    (true? (safe-select-one (keypath uid) mfa-enabled-pstate))))

(defn setup-mfa! [deps user-id encrypted-secret backup-code-hashes]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        setup-depot (rama/depot cmgr mod-name "*mfa-setup-depot")]
    (ramaapi/foreign-append! setup-depot
      (rama/->MfaSetup uid encrypted-secret backup-code-hashes))
    true))

(defn verify-mfa-setup! [deps user-id]
  true)

(defn disable-mfa! [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        disable-depot (rama/depot cmgr mod-name "*mfa-disable-depot")]
    (ramaapi/foreign-append! disable-depot
      (rama/->MfaDisable uid))
    true))

(defn get-mfa-secret [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        mfa-secrets (rama/pstate cmgr mod-name "$$mfa-secrets")]
    (safe-select-one (keypath uid) mfa-secrets)))

(defn get-mfa-backup-codes [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        mfa-backup-codes (rama/pstate cmgr mod-name "$$mfa-backup-codes")
        raw (safe-select-one (keypath uid) mfa-backup-codes)]
    (if (nil? raw)
      #{}
      (if (map? raw)
        (set (keys raw))
        (if (coll? raw)
          (set raw)
          #{raw})))))

(defn consume-mfa-backup-code! [deps user-id code-hash]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        consume-depot (rama/depot cmgr mod-name "*mfa-consume-backup-code-depot")]
    (ramaapi/foreign-append! consume-depot
      (rama/->MfaConsumeBackupCode uid code-hash))
    true))

(defn regenerate-mfa-backup-codes! [deps user-id backup-code-hashes]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        regen-depot (rama/depot cmgr mod-name "*mfa-regenerate-backup-codes-depot")]
    (ramaapi/foreign-append! regen-depot
      (rama/->MfaRegenerateBackupCodes uid backup-code-hashes))
    true))

(defn count-mfa-backup-codes [deps user-id]
  (count (get-mfa-backup-codes deps user-id)))

;; --- WebAuthn / Passkey Functions ---

(defn register-passkey! [deps user-id credential-id public-key-cose sign-count user-handle nickname]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        register-depot (rama/depot cmgr mod-name "*webauthn-register-depot")
        created-at (System/currentTimeMillis)]
    (ramaapi/foreign-append! register-depot
      (rama/->WebAuthnRegister uid credential-id public-key-cose sign-count user-handle nickname created-at))
    true))

(defn update-passkey-sign-count! [deps user-id credential-id new-sign-count]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        sign-count-depot (rama/depot cmgr mod-name "*webauthn-sign-count-depot")]
    (ramaapi/foreign-append! sign-count-depot
      (rama/->WebAuthnUpdateSignCount uid credential-id new-sign-count))
    true))

(defn remove-passkey! [deps user-id credential-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        remove-depot (rama/depot cmgr mod-name "*webauthn-remove-depot")]
    (ramaapi/foreign-append! remove-depot
      (rama/->WebAuthnRemoveCredential uid credential-id))
    true))

(defn list-passkeys-for-user [deps user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        credentials-pstate (rama/pstate cmgr mod-name "$$webauthn-credentials")
        creds-map (safe-select-one (keypath uid) credentials-pstate)]
    (mapv (fn [[cred-id data]]
            (assoc data :credential-id cred-id))
          creds-map)))

;; --- OAuth & SAML Account Linking Functions ---

(defn link-oauth-account! [deps provider provider-user-id user-id]
  (let [uid (->user-id user-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        link-depot (rama/depot cmgr mod-name "*oauth-link-depot")]
    (ramaapi/foreign-append! link-depot
      (rama/->OAuthLink provider provider-user-id uid))
    true))

(defn find-by-oauth-link [deps provider provider-user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        oauth-link-pstate (rama/pstate cmgr mod-name "$$oauth-link")
        user-id (safe-select-one (keypath provider provider-user-id) oauth-link-pstate)]
    (when user-id
      (find-by-id deps user-id))))

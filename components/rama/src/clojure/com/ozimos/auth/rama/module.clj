(ns com.ozimos.auth.rama.module
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops])
  (:import [com.rpl.rama.helpers ModuleUniqueIdPState]))

(defrecord Registration [uuid username pwd-hash email roles])
(defrecord Verification [user-id])
(defrecord PasswordChange [user-id new-pwd-hash])
(defrecord UsernameChange [user-id new-username])
(defrecord SessionStart [user-id session-id jti expires-at])
(defrecord SessionEnd [session-id])
(defrecord Revocation [jti expires-at])
(defrecord RevokeAllForUser [user-id])
(defrecord ClearRevocation [jti])
(defrecord ResetToken [token user-id expires-at])
(defrecord ClearResetToken [token])

(defrecord OrgCreate [uuid name owner-user-id created-at])
(defrecord OrgInvite [invitation-id org-id email role invited-by created-at expires-at])
(defrecord OrgJoin [user-id invitation-id joined-at])
(defrecord OrgSwitch [user-id org-id])
(defrecord OrgMemberUpdate [org-id target-user-id new-role])
(defrecord OrgMemberRemove [org-id target-user-id])
(defrecord InvitationAccept [invitation-id user-id joined-at])

(defmodule AuthModule [setup topologies]
  (declare-depot setup *registration-depot (hash-by :username))
  (declare-depot setup *verification-depot (hash-by :user-id))
  (declare-depot setup *password-change-depot (hash-by :user-id))
  (declare-depot setup *username-change-depot (hash-by :user-id))
  (declare-depot setup *session-depot (hash-by :user-id))
  (declare-depot setup *session-end-depot (hash-by :session-id))
  (declare-depot setup *revoke-all-depot (hash-by :user-id))
  (declare-depot setup *revocation-depot (hash-by :jti))
  (declare-depot setup *clear-revocation-depot (hash-by :jti))
  (declare-depot setup *reset-token-depot (hash-by :token))
  (declare-depot setup *clear-reset-token-depot (hash-by :token))
  (declare-depot setup *org-create-depot (hash-by :owner-user-id))
  (declare-depot setup *org-invite-depot (hash-by :org-id))
  (declare-depot setup *org-join-depot (hash-by :user-id))
  (declare-depot setup *org-switch-depot (hash-by :user-id))
  (declare-depot setup *org-member-update-depot (hash-by :org-id))
  (declare-depot setup *org-member-remove-depot (hash-by :org-id))

  (let [s (stream-topology topologies "auth")
        id-gen (ModuleUniqueIdPState. "$$id")]
    (declare-pstate s $$username->id {String Long})
    (declare-pstate s $$email->id {String Long})
    (declare-pstate s $$registration-ids {String Long})

    (declare-pstate s $$profiles
                    {Long (fixed-keys-schema {:username String
                                              :pwd-hash String
                                              :email String
                                              :verified Boolean
                                              :roles (vector-schema String)})})
    (declare-pstate s $$sessions
                    {String (fixed-keys-schema {:user-id Long :jti String :expires-at Long})})
    (declare-pstate s $$user-sessions
                    {Long (set-schema String {:subindex? true})})
    (declare-pstate s $$user-active-jtis
                    {Long (set-schema String {:subindex? true})})
    (declare-pstate s $$all-session-ids
                    {String (set-schema String {:subindex? true})})
    (declare-pstate s $$all-revoked-jtis
                    {String (set-schema String {:subindex? true})})
    (declare-pstate s $$revoked-tokens
                    {String Long})
    (declare-pstate s $$reset-tokens
                    {String (fixed-keys-schema {:user-id Long :expires-at Long})})

    ;; Organizations: org-id -> {name, owner-user-id, created-at}
    (declare-pstate s $$orgs
                    {Long (fixed-keys-schema {:name String
                                              :owner-user-id Long
                                              :created-at Long})})
    ;; Org name -> org-id (for uniqueness check)
    (declare-pstate s $$org-name->id {String Long})
    ;; Org creation dedup: uuid -> org-id
    (declare-pstate s $$org-create-ids {String Long})
    ;; User -> {org-id {role, status, joined-at, invited-by}}
    (declare-pstate s $$memberships
                    {Long {Long (fixed-keys-schema {:role String
                                                    :status String
                                                    :joined-at Long
                                                    :invited-by Long})}})
    ;; Org -> {user-id {role, status, joined-at, invited-by}}
    (declare-pstate s $$org-members
                    {Long {Long (fixed-keys-schema {:role String
                                                    :status String
                                                    :joined-at Long
                                                    :invited-by Long})}})
    ;; User -> active org-id
    (declare-pstate s $$user-active-org {Long Long})
    ;; User -> set of org-ids (for fast listing)
    (declare-pstate s $$user-orgs
                    {Long (set-schema Long {:subindex? true})})
    ;; Org -> set of user-ids (for fast listing, includes pending)
    (declare-pstate s $$org-users
                    {Long (set-schema Long {:subindex? true})})
    ;; Invitations: invitation-id -> {org-id, email, role, invited-by, status, created-at, expires-at}
    (declare-pstate s $$invitations
                    {String (fixed-keys-schema {:org-id Long
                                                :email String
                                                :role String
                                                :invited-by Long
                                                :status String
                                                :created-at Long
                                                :expires-at Long})})
    ;; Invitation by email (for listing user's pending invitations): email -> set of invitation-ids
    (declare-pstate s $$email->invitations
                    {String (set-schema String {:subindex? true})})
    ;; Org -> set of pending invitation-ids
    (declare-pstate s $$org-invitations
                    {Long (set-schema String {:subindex? true})})

    (.declarePState id-gen s)

    (<<sources s
               ;; Registration
               (source> *registration-depot :> {:keys [*uuid *username *pwd-hash *email *roles]})
               (local-select> (keypath *username) $$username->id :> *existing-id)
               (local-select> (keypath *uuid) $$registration-ids :> *existing-reg-uuid)
               (<<if (nil? *existing-id)
                     ;; Username available — register
                     (java-macro! (.genId id-gen "*user-id"))
                     (local-transform> [(keypath *username) (termval *user-id)] $$username->id)
                     (|hash *email)
                     (local-transform> [(keypath *email) (termval *user-id)] $$email->id)
                     (local-transform> [(keypath *uuid) (termval *user-id)] $$registration-ids)
                     (|hash *user-id)
                     (local-transform> [(keypath *user-id)
                                        (multi-path [:username (termval *username)]
                                                    [:pwd-hash (termval *pwd-hash)]
                                                    [:email (termval *email)]
                                                    [:verified (termval false)]
                                                    [:roles (termval *roles)])]
                                       $$profiles)
                      (ack-return> *user-id)
                      (else>)
                      (ack-return> *existing-id))

               ;; Verification
                (source> *verification-depot :> {:keys [*user-id]})
                (local-transform> [(keypath *user-id :verified) (termval true)] $$profiles)

               ;; Password change
                (source> *password-change-depot :> {:keys [*user-id *new-pwd-hash]})
                (local-transform> [(keypath *user-id :pwd-hash) (termval *new-pwd-hash)] $$profiles)

               ;; Username change
                (source> *username-change-depot :> {:keys [*user-id *new-username]})
                (local-select> (keypath *user-id :username) $$profiles :> *old-username)
                (<<if (not= *old-username *new-username)
                      ;; Switch to new-username partition — check uniqueness
                      (|hash *new-username)
                      (local-select> (keypath *new-username) $$username->id :> *existing-id)
                      (<<if (nil? *existing-id)
                            ;; Clear old username mapping (guard against nil profile)
                            (<<if (some? *old-username)
                                  (|hash *old-username)
                                  (local-transform> [(keypath *old-username) NONE>] $$username->id))
                            ;; Set new username mapping
                            (|hash *new-username)
                            (local-transform> [(keypath *new-username) (termval *user-id)] $$username->id)
                            ;; Update profile
                            (|hash *user-id)
                            (local-transform> [(keypath *user-id :username) (termval *new-username)] $$profiles)
                            (ack-return> :ok)
                            (else>)
                            (ack-return> :taken))
                      (else>)
                      (ack-return> :ok))

               ;; Session start
               (source> *session-depot :> {:keys [*user-id *session-id *jti *expires-at]})
               (|hash *session-id)
               (local-transform> [(keypath *session-id)
                                  (multi-path [:user-id (termval *user-id)]
                                              [:jti (termval *jti)]
                                              [:expires-at (termval *expires-at)])]
                                 $$sessions)
               (|hash *user-id)
               (local-transform> [(keypath *user-id) NONE-ELEM (termval *session-id)] $$user-sessions)
               (local-transform> [(keypath *user-id) NONE-ELEM (termval *jti)] $$user-active-jtis)

               ;; Single session end
                (source> *session-end-depot :> {:keys [*session-id]})
                (local-select> (keypath *session-id :user-id) $$sessions :> *user-id)
                (local-select> (keypath *session-id :jti) $$sessions :> *jti)
                (<<if (some? *user-id)
                      (local-transform> [(keypath *session-id) NONE>] $$sessions)
                      (|hash *user-id)
                      (local-transform> [(keypath *user-id) NONE-ELEM (termval *session-id)] $$user-sessions)
                      (local-transform> [(keypath *user-id) NONE-ELEM (termval *jti)] $$user-active-jtis))

               ;; Revoke-all
               (source> *revoke-all-depot :> {:keys [*user-id]})
               (local-select> (keypath *user-id) $$user-sessions :> *session-ids)
               (ops/explode *session-ids :> *sid)
               (|hash *sid)
               (local-transform> [(keypath *sid) NONE>] $$sessions)
               (|hash *user-id)
               (local-transform> [(keypath *user-id) NONE>] $$user-sessions)
               (local-select> (keypath *user-id) $$user-active-jtis :> *jtis)
               (ops/explode *jtis :> *jti)
               (|hash *jti)
               (local-transform> [(keypath *jti) (termval (System/currentTimeMillis))] $$revoked-tokens)
               (|hash *user-id)
               (local-transform> [(keypath *user-id) NONE>] $$user-active-jtis)

               ;; Token revocation
                (source> *revocation-depot :> {:keys [*jti *expires-at]})
                (local-transform> [(keypath *jti) (termval *expires-at)] $$revoked-tokens)

;; Clear revocation
                  (source> *clear-revocation-depot :> {:keys [*jti]})
                  (local-transform> [(keypath *jti) NONE>] $$revoked-tokens)

                ;; Reset token
                (source> *reset-token-depot :> {:keys [*token *user-id *expires-at]})
                (local-transform> [(keypath *token)
                                   (multi-path [:user-id (termval *user-id)]
                                               [:expires-at (termval *expires-at)])]
                                   $$reset-tokens)

                 ;; Clear reset token
                 (source> *clear-reset-token-depot :> {:keys [*token]})
                 (local-transform> [(keypath *token) NONE>] $$reset-tokens)

                ;; Organization creation
                (source> *org-create-depot :> {:keys [*uuid *name *owner-user-id *created-at]})
                (local-select> (keypath *name) $$org-name->id :> *existing-org-id)
                (local-select> (keypath *uuid) $$org-create-ids :> *existing-create-uuid)
                (<<if (nil? *existing-org-id)
                      (java-macro! (.genId id-gen "*org-id"))
                      (|hash *name)
                      (local-transform> [(keypath *name) (termval *org-id)] $$org-name->id)
                      (|hash *uuid)
                      (local-transform> [(keypath *uuid) (termval *org-id)] $$org-create-ids)
                      (|hash *org-id)
                      (local-transform> [(keypath *org-id)
                                         (multi-path [:name (termval *name)]
                                                     [:owner-user-id (termval *owner-user-id)]
                                                     [:created-at (termval *created-at)])]
                                        $$orgs)
                      ;; Owner becomes ADMIN member with ACTIVE status
                      (|hash *owner-user-id)
                      (local-transform> [(keypath *owner-user-id *org-id)
                                         (multi-path [:role (termval "ADMIN")]
                                                     [:status (termval "ACTIVE")]
                                                     [:joined-at (termval *created-at)]
                                                     [:invited-by (termval *owner-user-id)])]
                                        $$memberships)
                      (|hash *org-id)
                      (local-transform> [(keypath *org-id *owner-user-id)
                                         (multi-path [:role (termval "ADMIN")]
                                                     [:status (termval "ACTIVE")]
                                                     [:joined-at (termval *created-at)]
                                                     [:invited-by (termval *owner-user-id)])]
                                        $$org-members)
                      ;; Add to user-orgs and org-users sets
                      (|hash *owner-user-id)
                      (local-transform> [(keypath *owner-user-id) NONE-ELEM (termval *org-id)] $$user-orgs)
                      (|hash *org-id)
                      (local-transform> [(keypath *org-id) NONE-ELEM (termval *owner-user-id)] $$org-users)
                      ;; Set as active org for the owner
                      (|hash *owner-user-id)
                      (local-transform> [(keypath *owner-user-id) (termval *org-id)] $$user-active-org)
                      (ack-return> *org-id)
                      (else>)
                      (ack-return> *existing-org-id))

                ;; Organization invitation
                (source> *org-invite-depot :> {:keys [*invitation-id *org-id *email *role *invited-by *created-at *expires-at]})
                (|hash *invitation-id)
                (local-transform> [(keypath *invitation-id)
                                    (multi-path [:org-id (termval *org-id)]
                                                [:email (termval *email)]
                                                [:role (termval *role)]
                                                [:invited-by (termval *invited-by)]
                                                [:status (termval "PENDING")]
                                                [:created-at (termval *created-at)]
                                                [:expires-at (termval *expires-at)])]
                                  $$invitations)
                ;; Index by email and org for fast lookup
                (|hash *email)
                (local-transform> [(keypath *email) NONE-ELEM (termval *invitation-id)] $$email->invitations)
                (|hash *org-id)
                (local-transform> [(keypath *org-id) NONE-ELEM (termval *invitation-id)] $$org-invitations)
                (ack-return> *invitation-id)

                ;; Accept invitation (join org)
                (source> *org-join-depot :> {:keys [*user-id *invitation-id *joined-at]})
                (|hash *invitation-id)
                (local-select> (keypath *invitation-id :org-id) $$invitations :> *org-id)
                (local-select> (keypath *invitation-id :role) $$invitations :> *role)
                (local-select> (keypath *invitation-id :invited-by) $$invitations :> *invited-by)
                (<<if (some? *org-id)
                      ;; Mark invitation as ACCEPTED
                      (|hash *invitation-id)
                      (local-transform> [(keypath *invitation-id :status) (termval "ACCEPTED")] $$invitations)
                      ;; Add to user's memberships
                      (|hash *user-id)
                      (local-transform> [(keypath *user-id *org-id)
                                         (multi-path [:role (termval *role)]
                                                     [:status (termval "ACTIVE")]
                                                     [:joined-at (termval *joined-at)]
                                                     [:invited-by (termval *invited-by)])]
                                        $$memberships)
                      ;; Add to org's members
                      (|hash *org-id)
                      (local-transform> [(keypath *org-id *user-id)
                                         (multi-path [:role (termval *role)]
                                                     [:status (termval "ACTIVE")]
                                                     [:joined-at (termval *joined-at)]
                                                     [:invited-by (termval *invited-by)])]
                                        $$org-members)
                      ;; Add to sets
                      (|hash *user-id)
                      (local-transform> [(keypath *user-id) NONE-ELEM (termval *org-id)] $$user-orgs)
                      (|hash *org-id)
                      (local-transform> [(keypath *org-id) NONE-ELEM (termval *user-id)] $$org-users)
                      (ack-return> *org-id)
                      (else>)
                      (ack-return> -1))

                ;; Switch active org
                (source> *org-switch-depot :> {:keys [*user-id *org-id]})
                (|hash *user-id)
                (local-transform> [(keypath *user-id) (termval *org-id)] $$user-active-org)

                ;; Update member role
                (source> *org-member-update-depot :> {:keys [*org-id *target-user-id *new-role]})
                (|hash *target-user-id)
                (local-transform> [(keypath *target-user-id *org-id :role) (termval *new-role)] $$memberships)
                (|hash *org-id)
                (local-transform> [(keypath *org-id *target-user-id :role) (termval *new-role)] $$org-members)

                ;; Remove member from org
                (source> *org-member-remove-depot :> {:keys [*org-id *target-user-id]})
                (|hash *target-user-id)
                (local-transform> [(keypath *target-user-id *org-id) NONE>] $$memberships)
                (|hash *org-id)
                (local-transform> [(keypath *org-id *target-user-id) NONE>] $$org-members)
                (|hash *target-user-id)
                (local-transform> [(keypath *target-user-id) NONE-ELEM (termval *org-id)] $$user-orgs)
                (|hash *org-id)
                (local-transform> [(keypath *org-id) NONE-ELEM (termval *target-user-id)] $$org-users))))

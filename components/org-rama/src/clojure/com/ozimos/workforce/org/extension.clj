(ns com.ozimos.workforce.org.extension
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require
   [com.ozimos.workforce.org.records]
   [com.ozimos.workforce.rama.extension :as ext]
   [integrant.core :as ig])
  (:import
   [com.rpl.rama.helpers ModuleUniqueIdPState]))

(defrecord OrgExtension []
  ext/RamaModuleExtension
  (declare-depots [_ setup]
    (declare-depot setup *org-create-depot (hash-by :owner-user-id))
    (declare-depot setup *org-invite-depot (hash-by :org-id))
    (declare-depot setup *org-join-depot (hash-by :user-id))
    (declare-depot setup *org-switch-depot (hash-by :user-id))
    (declare-depot setup *org-member-update-depot (hash-by :org-id))
    (declare-depot setup *org-member-remove-depot (hash-by :org-id)))

  (declare-pstates [_ s]
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
    ;; Invitation by email: email -> set of invitation-ids
    (declare-pstate s $$email->invitations
                    {String (set-schema String {:subindex? true})})
    ;; Org -> set of pending invitation-ids
    (declare-pstate s $$org-invitations
                    {Long (set-schema String {:subindex? true})}))

  (build-topology [_ s]
    (let [id-gen (ModuleUniqueIdPState. "$$org-id-gen")]
      (.declarePState id-gen s)
      (<<sources s
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
                 (local-transform> [(keypath *org-id) NONE-ELEM (termval *target-user-id)] $$org-users)))))

(defmethod ig/init-key :workforce/org-extension [_ _]
  (->OrgExtension))

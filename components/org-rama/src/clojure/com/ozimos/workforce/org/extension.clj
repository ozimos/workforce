(ns com.ozimos.workforce.org.extension
  (:require
   [com.ozimos.omni-auth.rama.extension :as ext]
   [com.ozimos.workforce.org.records]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.ops :as ops]
   [com.rpl.rama.path :refer :all]
   [integrant.core :as ig])
  (:import
   (com.rpl.rama.helpers ModuleUniqueIdPState)))

(declare
  *org-create-depot *org-invite-depot *org-join-depot *org-switch-depot
  *org-member-update-depot *org-member-remove-depot
  *org-unit-depot *headcount-depot *actor-depot *policy-depot
  $$orgs $$org-name->id $$org-create-ids $$memberships $$user-orgs $$org-users
  $$user-active-org $$invitations $$org-invitations $$email->invitations $$org-members
  $$org-units $$org->units $$org-hierarchy $$org-child-parent
  $$headcount-requests $$unit-requests $$user-pending-approvals
  $$request-timeline $$unit-headcount-stats $$approval-sla
  $$unit-actors $$approval-rules $$role-permissions
  $$processed-idempotency-keys)

(defn- now-ms [] (System/currentTimeMillis))

(defn default-val [v d]
  (if (nil? v) d v))

(defn default-stats [budget]
  {:budget (or budget 0) :filled 0 :open (or budget 0) :pending 0})

(defn update-stats-filled [stats delta]
  (let [filled (+ (or (:filled stats) 0) delta)
        budget (or (:budget stats) 0)
        open (max 0 (- budget filled))]
    (assoc stats :filled filled :open open)))

(defn update-stats-pending [stats delta]
  (let [pending (max 0 (+ (or (:pending stats) 0) delta))]
    (assoc stats :pending pending)))

(defn get-step-role-str [chain step-num]
  (let [step-def (get chain (dec (or step-num 1)))]
    (str (:role step-def))))

(defn make-unit-map [u o div dept nm p b ca]
  {:unit-id u
   :org-id o
   :division-id div
   :dept-id dept
   :name nm
   :parent-id p
   :budget (or b 0)
   :created-at ca})

(defn headcount-create->map [cmd]
  {:request-id (:request-id cmd)
   :org-id (:org-id cmd)
   :unit-id (:unit-id cmd)
   :division-id (:division-id cmd)
   :dept-id (:dept-id cmd)
   :location (:location cmd)
   :job-level (:job-level cmd)
   :employee-type (:employee-type cmd)
   :requester-id (:requester-id cmd)
   :title (:title cmd)
   :justification (:justification cmd)
   :job-description (:job-description cmd)
   :salary-band (:salary-band cmd)
   :bonus-target (:bonus-target cmd)
   :status (or (:status cmd) :in-approval)
   :current-step (or (:current-step cmd) 1)
   :chain-snapshot (or (:chain-snapshot cmd) [])
   :approved-by []
   :created-at (:created-at cmd)
   :updated-at (:created-at cmd)})

(defonce ^:private org-id-gen (ModuleUniqueIdPState. "$$org-id-gen"))

(defrecord OrgExtension []
  ext/RamaModuleExtension
  (declare-depots [_ setup]
    ;; Core Organization Depots
    (declare-depot setup *org-create-depot (hash-by :owner-user-id))
    (declare-depot setup *org-invite-depot (hash-by :org-id))
    (declare-depot setup *org-join-depot (hash-by :user-id))
    (declare-depot setup *org-switch-depot (hash-by :user-id))
    (declare-depot setup *org-member-update-depot (hash-by :org-id))
    (declare-depot setup *org-member-remove-depot (hash-by :org-id))

    ;; Org Unit & Hierarchy Depot
    (declare-depot setup *org-unit-depot (hash-by :org-id))

    ;; Headcount Requisition Depot
    (declare-depot setup *headcount-depot (hash-by :org-id))

    ;; Scoped Actor Assignments Depot
    (declare-depot setup *actor-depot (hash-by :org-id))

    ;; Policy & Permissions Depot
    (declare-depot setup *policy-depot (hash-by :org-id)))

  (declare-pstates [_ s]
    (.declarePState org-id-gen s)
    ;; --- 1. Core Organization PStates ---
    (declare-pstate s $$orgs
                    {Long (fixed-keys-schema {:name String
                                              :owner-user-id Long
                                              :created-at Long})})
    (declare-pstate s $$org-name->id {String Long})
    (declare-pstate s $$org-create-ids {String Long})
    (declare-pstate s $$memberships
                    {Long {Long (fixed-keys-schema {:role String
                                                    :status String
                                                    :joined-at Long
                                                    :invited-by Long})}})
    (declare-pstate s $$user-orgs
                    {Long (map-schema Long Object)})
    (declare-pstate s $$org-users
                    {Long (map-schema Long Object)})
    (declare-pstate s $$user-active-org
                    {Long Long})
    (declare-pstate s $$invitations
                    {String (fixed-keys-schema {:org-id Long
                                                :email String
                                                :role String
                                                :invited-by Long
                                                :created-at Long
                                                :expires-at Long
                                                :status String})})
    (declare-pstate s $$org-invitations
                    {Long (map-schema String Object)})
    (declare-pstate s $$email->invitations
                    {String (map-schema String Object)})
    (declare-pstate s $$org-members
                    {Long {Long (fixed-keys-schema {:role String
                                                    :status String
                                                    :joined-at Long
                                                    :invited-by Long})}})

    ;; --- 2. Org Unit & Division Hierarchy PStates ---
    (declare-pstate s $$org-units
                    {String (map-schema Object Object)})
    (declare-pstate s $$org->units
                    {Long (map-schema String Object)})
    (declare-pstate s $$org-hierarchy
                    {String (map-schema String Object)})
    (declare-pstate s $$org-child-parent
                    {String String})

    ;; --- 3. Headcount Requisitions & Queues ---
    (declare-pstate s $$headcount-requests
                    {String (map-schema Object Object)})
    (declare-pstate s $$unit-requests
                    {String (map-schema String Object)})
    (declare-pstate s $$user-pending-approvals
                    {Long (map-schema String Object)})
    (declare-pstate s $$request-timeline
                    {String (vector-schema (map-schema Object Object))})

    ;; --- 4. Analytics, SLA & Scoped Actors ---
    (declare-pstate s $$unit-headcount-stats
                    {String (map-schema Object Object)})
    (declare-pstate s $$approval-sla
                    {String (vector-schema Long)})
    (declare-pstate s $$unit-actors
                    {String (map-schema String Long)})

    ;; --- 5. Approval Rules & Role Permissions ---
    (declare-pstate s $$approval-rules
                    {Long (vector-schema (map-schema Object Object))})
    (declare-pstate s $$role-permissions
                    {Long (map-schema Object Object)})

    ;; --- 6. Idempotency deduplication ---
    ;; Nested under org-id for co-location with headcount data.
    (declare-pstate s $$processed-idempotency-keys
                    {Long (map-schema String Object)}))

  (build-topology [_ s]
    #_{:clj-kondo/ignore [:unused-binding]}
    (<<sources s
               ;; -------------------------------------------------------------
               ;; Organization Creation
               ;; -------------------------------------------------------------
               (source> *org-create-depot :> {:keys [*uuid *name *owner-user-id *created-at]})
               (local-select> (keypath *name) $$org-name->id :> *existing-org-id)
               (<<if (nil? *existing-org-id)
                     (java-macro! (.genId org-id-gen "*org-id"))
                       (|hash *name)
                       (local-transform> [(keypath *name) (termval *org-id)] $$org-name->id)
                       (|hash *uuid)
                       (local-transform> [(keypath *uuid) (termval *org-id)] $$org-create-ids)
                       (|hash *org-id)
                       (hash-map :name *name :owner-user-id *owner-user-id :created-at *created-at :> *org-map)
                       (local-transform> [(keypath *org-id) (termval *org-map)] $$orgs)
                       ;; Owner becomes ADMIN member with ACTIVE status
                       (hash-map :role "ADMIN" :status "ACTIVE" :joined-at *created-at :invited-by *owner-user-id :> *owner-membership)
                       (|hash *owner-user-id)
                       (local-transform> [(keypath *owner-user-id *org-id) (termval *owner-membership)] $$memberships)
                       (|hash *org-id)
                       (local-transform> [(keypath *org-id *owner-user-id) (termval *owner-membership)] $$org-members)
                       ;; Add to user-orgs and org-users sets
                       (|hash *owner-user-id)
                       (local-transform> [(keypath *owner-user-id *org-id) (termval true)] $$user-orgs)
                       (|hash *org-id)
                       (local-transform> [(keypath *org-id *owner-user-id) (termval true)] $$org-users)
                       ;; Set as active org for the owner
                       (|hash *owner-user-id)
                       (local-transform> [(keypath *owner-user-id) (termval *org-id)] $$user-active-org)
                       (ack-return> *org-id)
                       (else>)
                       (ack-return> *existing-org-id))

                 ;; -------------------------------------------------------------
                 ;; Organization Invitation
                 ;; -------------------------------------------------------------
                 (source> *org-invite-depot :> {:keys [*invitation-id *org-id *email *role *invited-by *created-at *expires-at]})
                 (|hash *invitation-id)
                 (hash-map :org-id *org-id
                           :email *email
                           :role *role
                           :invited-by *invited-by
                           :status "PENDING"
                           :created-at *created-at
                           :expires-at *expires-at :> *inv-map)
                 (local-transform> [(keypath *invitation-id) (termval *inv-map)] $$invitations)
                 ;; Index by email and org for fast lookup
                 (|hash *email)
                 (local-transform> [(keypath *email *invitation-id) (termval true)] $$email->invitations)
                 (|hash *org-id)
                 (local-transform> [(keypath *org-id *invitation-id) (termval true)] $$org-invitations)
                 (ack-return> *invitation-id)

                 ;; -------------------------------------------------------------
                 ;; Accept Invitation (Join Org)
                 ;; -------------------------------------------------------------
                 (source> *org-join-depot :> {:keys [*user-id *invitation-id *joined-at]})
                 (|hash *invitation-id)
                 (local-select> (keypath *invitation-id :org-id) $$invitations :> *org-id)
                 (local-select> (keypath *invitation-id :role) $$invitations :> *role)
                 (local-select> (keypath *invitation-id :invited-by) $$invitations :> *invited-by)
                 (<<if (some? *org-id)
                       ;; Mark invitation as ACCEPTED
                       (|hash *invitation-id)
                       (local-transform> [(keypath *invitation-id :status) (termval "ACCEPTED")] $$invitations)
                       ;; Add to user's memberships and org members
                       (hash-map :role *role :status "ACTIVE" :joined-at *joined-at :invited-by *invited-by :> *mem-map)
                       (|hash *user-id)
                       (local-transform> [(keypath *user-id *org-id) (termval *mem-map)] $$memberships)
                       (|hash *org-id)
                       (local-transform> [(keypath *org-id *user-id) (termval *mem-map)] $$org-members)
                       ;; Add to sets
                       (|hash *user-id)
                       (local-transform> [(keypath *user-id *org-id) (termval true)] $$user-orgs)
                       (|hash *org-id)
                       (local-transform> [(keypath *org-id *user-id) (termval true)] $$org-users)
                       (ack-return> *org-id)
                       (else>)
                       (ack-return> -1))

                 ;; -------------------------------------------------------------
                 ;; Switch Active Org
                 ;; -------------------------------------------------------------
                 (source> *org-switch-depot :> {:keys [*user-id *org-id]})
                 (|hash *user-id)
                 (local-transform> [(keypath *user-id) (termval *org-id)] $$user-active-org)
                 (ack-return> *org-id)

                 ;; -------------------------------------------------------------
                 ;; Update Member Role
                 ;; -------------------------------------------------------------
                 (source> *org-member-update-depot :> {:keys [*org-id *target-user-id *new-role]})
                 (|hash *target-user-id)
                 (local-transform> [(keypath *target-user-id *org-id :role) (termval *new-role)] $$memberships)
                 (|hash *org-id)
                 (local-transform> [(keypath *org-id *target-user-id :role) (termval *new-role)] $$org-members)
                 (ack-return> *new-role)

                 ;; -------------------------------------------------------------
                 ;; Remove Member from Org
                 ;; -------------------------------------------------------------
                 (source> *org-member-remove-depot :> {:keys [*org-id *target-user-id]})
                 (|hash *target-user-id)
                 (local-transform> [(keypath *target-user-id *org-id) NONE>] $$memberships)
                 (local-transform> [(keypath *target-user-id *org-id) NONE>] $$user-orgs)
                 (|hash *org-id)
                 (local-transform> [(keypath *org-id *target-user-id) NONE>] $$org-members)
                 (local-transform> [(keypath *org-id *target-user-id) NONE>] $$org-users)
                 (ack-return> true)

                 ;; -------------------------------------------------------------
                 ;; Org Unit & Hierarchy Management
                 ;; -------------------------------------------------------------
                 (source> *org-unit-depot :> *unit-cmd)
                 (instance? com.ozimos.workforce.org.records.OrgUnitCreate *unit-cmd :> *is-unit-create?)
                 (<<if *is-unit-create?
                       (get *unit-cmd :unit-id :> *u)
                       (get *unit-cmd :org-id :> *o)
                       (get *unit-cmd :division-id :> *div)
                       (get *unit-cmd :dept-id :> *dept)
                       (get *unit-cmd :name :> *nm)
                       (get *unit-cmd :parent-id :> *p)
                       (get *unit-cmd :budget :> *b-raw)
                       (get *unit-cmd :created-at :> *ca)
                       (make-unit-map *u *o *div *dept *nm *p *b-raw *ca :> *unit-map)
                       (|hash *u)
                       (local-transform> [(keypath *u) (termval *unit-map)] $$org-units)
                       (default-stats *b-raw :> *init-stats)
                       (local-transform> [(keypath *u) (termval *init-stats)] $$unit-headcount-stats)
                       (|hash *o)
                       (local-transform> [(keypath *o *u) (termval true)] $$org->units)
                       (<<if (some? *p)
                             (|hash *p)
                             (local-transform> [(keypath *p *u) (termval true)] $$org-hierarchy)
                             (|hash *u)
                             (local-transform> [(keypath *u) (termval *p)] $$org-child-parent)
                             (ack-return> *u)
                             (else>)
                             (|hash *u)
                             (ack-return> *u))
                       (else>)
                       (instance? com.ozimos.workforce.org.records.OrgUnitUpdate *unit-cmd :> *is-unit-update?)
                       (<<if *is-unit-update?
                             (get *unit-cmd :unit-id :> *u)
                             (get *unit-cmd :name :> *nm)
                             (get *unit-cmd :budget :> *b)
                             (|hash *u)
                             (local-transform> [(keypath *u :name) (termval *nm)] $$org-units)
                             (local-transform> [(keypath *u :budget) (termval *b)] $$org-units)
                             (local-transform> [(keypath *u :budget) (termval *b)] $$unit-headcount-stats)
                             (ack-return> *u)
                             (else>)
                             (instance? com.ozimos.workforce.org.records.OrgUnitReparent *unit-cmd :> *is-unit-reparent?)
                             (<<if *is-unit-reparent?
                                   (get *unit-cmd :unit-id :> *u)
                                   (get *unit-cmd :new-parent-id :> *np)
                                   (|hash *u)
                                   (local-select> (keypath *u) $$org-child-parent :> *old-p)
                                   (<<if (some? *old-p)
                                         (|hash *old-p)
                                         (local-transform> [(keypath *old-p *u) NONE>] $$org-hierarchy))
                                   (|hash *np)
                                   (local-transform> [(keypath *np *u) (termval true)] $$org-hierarchy)
                                   (|hash *u)
                                   (local-transform> [(keypath *u) (termval *np)] $$org-child-parent)
                                   (local-transform> [(keypath *u :parent-id) (termval *np)] $$org-units)
                                   (ack-return> *u)
                                   (else>)
                                   (instance? com.ozimos.workforce.org.records.OrgUnitSetBudget *unit-cmd :> *is-unit-budget?)
                                   (<<if *is-unit-budget?
                                         (get *unit-cmd :unit-id :> *u)
                                         (get *unit-cmd :budget :> *b)
                                         (|hash *u)
                                         (local-transform> [(keypath *u :budget) (termval *b)] $$org-units)
                                         (local-transform> [(keypath *u :budget) (termval *b)] $$unit-headcount-stats)
                                         (ack-return> *u)
                                         (else>)
                                         (ack-return> nil)))))

                  ;; -------------------------------------------------------------
                  ;; Headcount Requisition Lifecycle
                  ;; -------------------------------------------------------------
                  (source> *headcount-depot :> *req-cmd)
                  ;; Idempotency guard: if the event carries an :idempotency-key that has been
                  ;; seen before for this org, return :duplicate without reprocessing.
                  (get *req-cmd :idempotency-key :> *ikey)
                  (get *req-cmd :org-id :> *oid-ikey)
                  (<<if (some? *ikey)
                        (local-select> (keypath *oid-ikey *ikey) $$processed-idempotency-keys :> *dup-record)
                        (else>)
                        (identity nil :> *dup-record))
                  (<<if (some? *dup-record)
                        (get *dup-record :result-id :> *existing-rid)
                        (ack-return> (default-val *existing-rid :duplicate))
                        (else>)
                        (<<if (some? *ikey)
                              (now-ms :> *now-ts)
                              (get *req-cmd :request-id :> *raw-rid)
                              (hash-map :processed-at *now-ts :result-id *raw-rid :> *ikey-val)
                              (local-transform> [(keypath *oid-ikey *ikey) (termval *ikey-val)] $$processed-idempotency-keys))
                        (instance? com.ozimos.workforce.org.records.HeadcountCreate *req-cmd :> *is-req-create?)
                        (<<if *is-req-create?
                              (headcount-create->map *req-cmd :> *req-map)
                              (get *req-cmd :request-id :> *rid)
                              (get *req-cmd :unit-id :> *uid)
                              (get *req-cmd :requester-id :> *rqid)
                              (get *req-cmd :created-at :> *ca)
                              (get *req-cmd :chain-snapshot :> *chain-raw)
                              (|hash *rid)
                              (local-transform> [(keypath *rid) (termval *req-map)] $$headcount-requests)
                              (hash-map :event :created :actor *rqid :timestamp *ca :> *created-event)
                              (local-transform> [(keypath *rid) (termval [*created-event])] $$request-timeline)
                              (|hash *uid)
                              (local-transform> [(keypath *uid *rid) (termval true)] $$unit-requests)
                              (local-select> (keypath *uid) $$unit-headcount-stats :> *curr-stats)
                              (update-stats-pending *curr-stats 1 :> *next-stats)
                              (local-transform> [(keypath *uid) (termval *next-stats)] $$unit-headcount-stats)
                              ;; Resolve Step 1 Approver
                              (get-step-role-str *chain-raw 1 :> *role-str)
                              (local-select> (keypath *uid *role-str) $$unit-actors :> *app-id)
                              (<<if (some? *app-id)
                                    (|hash *app-id)
                                    (local-transform> [(keypath *app-id *rid) (termval true)] $$user-pending-approvals)
                                    (|hash *rid)
                                    (local-transform> [(keypath *rid :current-approver-id) (termval *app-id)] $$headcount-requests)
                                    (ack-return> *rid)
                                    (else>)
                                    (|hash *rid)
                                    (ack-return> *rid))
                              (else>)
                              (instance? com.ozimos.workforce.org.records.HeadcountApproveStep *req-cmd :> *is-req-approve?)
                              (<<if *is-req-approve?
                                    (get *req-cmd :request-id :> *rid)
                                    (get *req-cmd :approver-user-id :> *app-uid)
                                    (get *req-cmd :approved-at :> *ts)
                                    (|hash *app-uid)
                                    (local-transform> [(keypath *app-uid *rid) NONE>] $$user-pending-approvals)
                                    (|hash *rid)
                                    (local-select> (keypath *rid) $$headcount-requests :> *req)
                                    (get *req :current-step :> *curr-step-raw)
                                    (default-val *curr-step-raw 1 :> *curr-step)
                                    (get *req :chain-snapshot :> *chain-raw)
                                    (default-val *chain-raw [] :> *chain)
                                    (count *chain :> *total-steps)
                                    (get *req :unit-id :> *uid)
                                    (local-transform> [(keypath *rid :approved-by) NONE-ELEM (termval *app-uid)] $$headcount-requests)
                                    (hash-map :event :approved-step :step *curr-step :actor *app-uid :timestamp *ts :> *app-event)
                                    (local-transform> [(keypath *rid) AFTER-ELEM (termval *app-event)] $$request-timeline)
                                    (<<if (>= *curr-step *total-steps)
                                          ;; Final Approval Reached -> Status :approved
                                          (local-transform> [(keypath *rid :status) (termval :approved)] $$headcount-requests)
                                          (local-transform> [(keypath *rid :current-approver-id) (termval nil)] $$headcount-requests)
                                          ;; Record approval SLA latency
                                          (get *req :created-at :> *req-created-ts)
                                          (- *ts *req-created-ts :> *latency-ms)
                                          (|hash *uid)
                                          (local-transform> [(keypath *uid) AFTER-ELEM (termval *latency-ms)] $$approval-sla)
                                          (ack-return> :approved)
                                          (else>)
                                          ;; Advance to next step
                                          (inc *curr-step :> *next-step)
                                          (get-step-role-str *chain *next-step :> *next-role-str)
                                          (local-transform> [(keypath *rid :current-step) (termval *next-step)] $$headcount-requests)
                                          (|hash *uid)
                                          (local-select> (keypath *uid *next-role-str) $$unit-actors :> *next-app-id)
                                          (<<if (some? *next-app-id)
                                                (|hash *next-app-id)
                                                (local-transform> [(keypath *next-app-id *rid) (termval true)] $$user-pending-approvals)
                                                (|hash *rid)
                                                (local-transform> [(keypath *rid :current-approver-id) (termval *next-app-id)] $$headcount-requests)
                                                (ack-return> :step-advanced)
                                                (else>)
                                                (|hash *rid)
                                                (ack-return> :step-advanced)))
                                    (else>)
                                    (instance? com.ozimos.workforce.org.records.HeadcountReject *req-cmd :> *is-req-reject?)
                                    (<<if *is-req-reject?
                                          (get *req-cmd :request-id :> *rid)
                                          (get *req-cmd :rejecter-user-id :> *rej-uid)
                                          (get *req-cmd :reason :> *reason)
                                          (get *req-cmd :rejected-at :> *ts)
                                          (|hash *rid)
                                          (local-select> (keypath *rid) $$headcount-requests :> *req)
                                          (get *req :current-approver-id :> *app-uid)
                                          (<<if (some? *app-uid)
                                                (|hash *app-uid)
                                                (local-transform> [(keypath *app-uid *rid) NONE>] $$user-pending-approvals))
                                          (|hash *rid)
                                          (local-transform> [(keypath *rid :status) (termval :rejected)] $$headcount-requests)
                                          (local-transform> [(keypath *rid :rejected-by) (termval *rej-uid)] $$headcount-requests)
                                          (local-transform> [(keypath *rid :rejection-reason) (termval *reason)] $$headcount-requests)
                                          (local-transform> [(keypath *rid :current-approver-id) (termval nil)] $$headcount-requests)
                                          (hash-map :event :rejected :actor *rej-uid :reason *reason :timestamp *ts :> *rej-event)
                                          (local-transform> [(keypath *rid) AFTER-ELEM (termval *rej-event)] $$request-timeline)
                                          (get *req :unit-id :> *uid)
                                          (|hash *uid)
                                          (local-select> (keypath *uid) $$unit-headcount-stats :> *curr-stats)
                                          (update-stats-pending *curr-stats -1 :> *next-stats)
                                          (local-transform> [(keypath *uid) (termval *next-stats)] $$unit-headcount-stats)
                                          (ack-return> :rejected)
                                          (else>)
                                          (instance? com.ozimos.workforce.org.records.HeadcountTransitionHire *req-cmd :> *is-req-hire?)
                                          (<<if *is-req-hire?
                                                (get *req-cmd :request-id :> *rid)
                                                (get *req-cmd :org-id :> *oid)
                                                (get *req-cmd :hired-user-id :> *hired-uid)
                                                (get *req-cmd :role :> *role-raw)
                                                (default-val *role-raw "MEMBER" :> *role)
                                                (get *req-cmd :transitioned-at :> *ts)
                                                (|hash *rid)
                                                (local-select> (keypath *rid) $$headcount-requests :> *req)
                                                (get *req :unit-id :> *uid)
                                                (local-transform> [(keypath *rid :status) (termval :filled)] $$headcount-requests)
                                                (local-transform> [(keypath *rid :hired-user-id) (termval *hired-uid)] $$headcount-requests)
                                                (hash-map :event :hired :actor *hired-uid :role *role :timestamp *ts :> *hired-event)
                                                (local-transform> [(keypath *rid) AFTER-ELEM (termval *hired-event)] $$request-timeline)
                                                ;; Update unit stats: increment filled
                                                (|hash *uid)
                                                (local-select> (keypath *uid) $$unit-headcount-stats :> *curr-stats)
                                                (update-stats-filled *curr-stats 1 :> *next-stats)
                                                (local-transform> [(keypath *uid) (termval *next-stats)] $$unit-headcount-stats)
                                                ;; Add new member to org
                                                (get *req :requester-id :> *req-by)
                                                (hash-map :role *role :status "ACTIVE" :joined-at *ts :invited-by *req-by :> *hired-mem)
                                                (|hash *hired-uid)
                                                (local-transform> [(keypath *hired-uid *oid) (termval *hired-mem)] $$memberships)
                                                (local-transform> [(keypath *hired-uid *oid) (termval true)] $$user-orgs)
                                                (|hash *oid)
                                                (local-transform> [(keypath *oid *hired-uid) (termval *hired-mem)] $$org-members)
                                                (local-transform> [(keypath *oid *hired-uid) (termval true)] $$org-users)
                                                (ack-return> *hired-uid)
                                                (else>)
                                                ;; HeadcountFieldEdit: re-approval reset on sensitive field edit
                                                (instance? com.ozimos.workforce.org.records.HeadcountFieldEdit *req-cmd :> *is-req-field-edit?)
                                                (<<if *is-req-field-edit?
                                                      (get *req-cmd :request-id :> *rid)
                                                      (get *req-cmd :editor-user-id :> *editor-uid)
                                                      (get *req-cmd :field-name :> *field-name)
                                                      (get *req-cmd :new-value :> *new-value)
                                                      (get *req-cmd :edited-at :> *ts)
                                                      (|hash *rid)
                                                      (local-select> (keypath *rid) $$headcount-requests :> *req-data)
                                                      (get *req-data :status :> *current-status)
                                                      (= *current-status :in-approval :> *needs-reset?)
                                                      (<<if *needs-reset?
                                                            ;; Clear current approver from pending queue
                                                            (get *req-data :current-approver-id :> *curr-approver)
                                                            (<<if (some? *curr-approver)
                                                                  (|hash *curr-approver)
                                                                  (local-transform> [(keypath *curr-approver *rid) NONE>] $$user-pending-approvals))
                                                            ;; Reset approval state to :draft / step 0
                                                            (|hash *rid)
                                                            (local-transform> [(keypath *rid :status) (termval :draft)] $$headcount-requests)
                                                            (local-transform> [(keypath *rid :current-step) (termval 0)] $$headcount-requests)
                                                            (local-transform> [(keypath *rid :current-approver-id) (termval nil)] $$headcount-requests)
                                                            (local-transform> [(keypath *rid :approved-by) (termval [])] $$headcount-requests)
                                                            ;; Apply the field update
                                                            (local-transform> [(keypath *rid *field-name) (termval *new-value)] $$headcount-requests)
                                                            (hash-map :event :field-edit-reset :field *field-name :new-value *new-value :actor *editor-uid :timestamp *ts :> *edit-event)
                                                            (local-transform> [(keypath *rid) AFTER-ELEM (termval *edit-event)] $$request-timeline)
                                                            (ack-return> :reset-to-draft)
                                                            (else>)
                                                            ;; Not in-approval — just update the field
                                                            (local-transform> [(keypath *rid *field-name) (termval *new-value)] $$headcount-requests)
                                                            (hash-map :event :field-edit :field *field-name :new-value *new-value :actor *editor-uid :timestamp *ts :> *edit-event)
                                                            (local-transform> [(keypath *rid) AFTER-ELEM (termval *edit-event)] $$request-timeline)
                                                            (ack-return> :field-updated))
                                                      (else>)
                                                      (ack-return> nil)))))))

                 ;; -------------------------------------------------------------
                 ;; Actor Scopes & Role Assignments
                 ;; -------------------------------------------------------------
                 (source> *actor-depot :> *act-cmd)
                 (instance? com.ozimos.workforce.org.records.OrgActorAssign *act-cmd :> *is-actor-assign?)
                 (<<if *is-actor-assign?
                       (get *act-cmd :unit-id :> *u)
                       (get *act-cmd :role :> *role-obj)
                       (str *role-obj :> *role)
                       (get *act-cmd :user-id :> *uid)
                       (|hash *u)
                       (local-transform> [(keypath *u *role) (termval *uid)] $$unit-actors)
                       (ack-return> *uid)
                       (else>)
                       (instance? com.ozimos.workforce.org.records.OrgActorRemove *act-cmd :> *is-actor-remove?)
                       (<<if *is-actor-remove?
                             (get *act-cmd :unit-id :> *u)
                             (get *act-cmd :role :> *role-obj)
                             (str *role-obj :> *role)
                             (|hash *u)
                             (local-transform> [(keypath *u *role) NONE>] $$unit-actors)
                             (ack-return> true)
                             (else>)
                             (ack-return> nil)))

                 ;; -------------------------------------------------------------
                 ;; Governance Policies & Role Permissions
                 ;; -------------------------------------------------------------
                 (source> *policy-depot :> *pol-cmd)
                 (instance? com.ozimos.workforce.org.records.ApprovalRuleSet *pol-cmd :> *is-rule-set?)
                 (<<if *is-rule-set?
                       (get *pol-cmd :org-id :> *o)
                       (get *pol-cmd :rules :> *rules)
                       (|hash *o)
                       (local-transform> [(keypath *o) (termval *rules)] $$approval-rules)
                       (count *rules :> *rules-count)
                       (ack-return> *rules-count)
                       (else>)
                       (instance? com.ozimos.workforce.org.records.RolePermissionSet *pol-cmd :> *is-perm-set?)
                       (<<if *is-perm-set?
                             (get *pol-cmd :org-id :> *o)
                             (get *pol-cmd :role :> *role)
                             (get *pol-cmd :permissions :> *perms)
                             (|hash *o)
                             (local-transform> [(keypath *o *role) (termval *perms)] $$role-permissions)
                             (ack-return> *role)
                             (else>)
                             (ack-return> nil))))))

(defmethod ig/init-key :workforce/org-extension [_ _]
  (->OrgExtension))

(ns com.ozimos.workforce.org.core
  (:require
   [com.ozimos.omni-auth.rama.interface :as rama]
   [com.ozimos.workforce.org.records :as rec]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [ALL keypath]]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- get-cmgr [deps]
  (or (-> deps :rama :cluster-manager)
      (:cluster-manager deps)
      (throw (ex-info "Could not resolve Rama cluster manager from deps"
                      {:deps-keys (keys deps)}))))

(defn- safe-select-one [path pstate-obj]
  (when pstate-obj
    (ramaapi/foreign-select-one path pstate-obj)))

(defn- safe-select [path pstate-obj]
  (if pstate-obj
    (ramaapi/foreign-select path pstate-obj)
    []))

(defn- unwrap-ack [res]
  (if (map? res)
    (or (get res "auth") (get res :auth) (first (vals res)))
    res))

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
                     (rec/->OrgCreate uuid name owner-user-id created-at))]
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
        org-ids (keys (or (safe-select-one (keypath user-id) user-orgs) {}))]
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
      (rec/->OrgInvite invitation-id org-id email role invited-by created-at expires-at))
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
              (rec/->OrgJoin user-id invitation-id joined-at))
            [true {:org-id (:org-id invitation)}]))))))

(defn switch-org! [deps user-id org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        switch-depot (rama/depot cmgr mod-name "*org-switch-depot")]
    (ramaapi/foreign-append! switch-depot (rec/->OrgSwitch user-id org-id))
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
        user-ids (keys (or (safe-select-one (keypath org-id) org-users) {}))]
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
      (rec/->OrgMemberUpdate org-id target-user-id new-role)
      :ack)
    true))

(defn remove-member! [deps org-id target-user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        remove-depot (rama/depot cmgr mod-name "*org-member-remove-depot")]
    (ramaapi/foreign-append! remove-depot
      (rec/->OrgMemberRemove org-id target-user-id)
      :ack)
    true))

(defn list-invitations-for-user [deps email]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        email->invitations (rama/pstate cmgr mod-name "$$email->invitations")
        invitations (rama/pstate cmgr mod-name "$$invitations")
        orgs (rama/pstate cmgr mod-name "$$orgs")
        invitation-ids (keys (or (safe-select-one (keypath email) email->invitations) {}))]
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

;; -----------------------------------------------------------------------------
;; Org Unit & Hierarchy Management Core APIs
;; -----------------------------------------------------------------------------

(defn create-org-unit! [deps input]
  (let [{:keys [org-id division-id dept-id name parent-id budget]} input
        unit-id (or (:unit-id input) (str (random-uuid)))
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*org-unit-depot")
        created-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->OrgUnitCreate unit-id org-id division-id dept-id name parent-id budget created-at)
      :ack)
    [true {:unit-id unit-id
           :org-id org-id
           :division-id division-id
           :dept-id dept-id
           :name name
           :parent-id parent-id
           :budget budget
           :created-at created-at}]))

(defn reparent-org-unit! [deps input]
  (let [{:keys [org-id unit-id new-parent-id]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*org-unit-depot")
        reparented-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->OrgUnitReparent unit-id org-id new-parent-id reparented-at)
      :ack)
    [true {:unit-id unit-id :parent-id new-parent-id}]))

(defn set-org-unit-budget! [deps input]
  (let [{:keys [org-id unit-id budget]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*org-unit-depot")
        updated-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->OrgUnitSetBudget unit-id org-id budget updated-at)
      :ack)
    [true {:unit-id unit-id :budget budget}]))

(defn get-org-unit [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        units (rama/pstate cmgr mod-name "$$org-units")]
    (safe-select-one (keypath unit-id) units)))

(defn get-org-children [deps parent-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        hierarchy (rama/pstate cmgr mod-name "$$org-hierarchy")]
    (set (keys (or (safe-select-one (keypath parent-id) hierarchy) {})))))

(defn get-org-hierarchy
  ([deps]
   (reify
     clojure.lang.ILookup
     (valAt [this k] (.valAt this k nil))
     (valAt [_ k not-found]
       (let [res (get-org-children deps k)]
         (if (seq res) res not-found)))
     clojure.lang.IFn
     (invoke [_ k] (get-org-children deps k))))
  ([deps parent-id]
   (get-org-children deps parent-id)))

(defn get-unit-headcount-stats [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        stats (rama/pstate cmgr mod-name "$$unit-headcount-stats")]
    (safe-select-one (keypath unit-id) stats)))

;; -----------------------------------------------------------------------------
;; Headcount Requisition Core APIs
;; -----------------------------------------------------------------------------

(defn create-headcount-request! [deps input]
  (let [{:keys [org-id unit-id division-id dept-id location job-level
                employee-type requester-id title justification
                job-description salary-band bonus-target chain-snapshot
                idempotency-key]} input
        request-id (or (:request-id input) (str (random-uuid)))
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*headcount-depot")
        created-at (now-ms)]
    (let [res (unwrap-ack (ramaapi/foreign-append! depot
                            (rec/->HeadcountCreate request-id org-id unit-id division-id dept-id location
                                                  job-level employee-type requester-id title justification
                                                  job-description salary-band bonus-target :in-approval
                                                  1 (or chain-snapshot []) created-at idempotency-key)
                            :ack))]
      (if (and (string? res) (not= res request-id))
        [true {:request-id res :status :in-approval :current-step 1 :duplicate true}]
        [true {:request-id request-id :status :in-approval :current-step 1}]))))

(defn approve-headcount-step! [deps input]
  (let [{:keys [org-id request-id approver-user-id idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*headcount-depot")
        approved-at (now-ms)]
    (let [res (ramaapi/foreign-append! depot
                (rec/->HeadcountApproveStep request-id org-id approver-user-id approved-at idempotency-key)
                :ack)]
      [true {:result (unwrap-ack res) :request-id request-id}])))

(defn reject-headcount-request! [deps input]
  (let [{:keys [org-id request-id rejecter-user-id reason idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*headcount-depot")
        rejected-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->HeadcountReject request-id org-id rejecter-user-id reason rejected-at idempotency-key)
      :ack)
    [true {:request-id request-id :status :rejected}]))

(defn edit-headcount-field!
  "Appends a HeadcountFieldEdit event to the headcount depot.
   The topology will reset the approval chain to :draft if the request
   is currently :in-approval.
   Pass :idempotency-key in input to make the operation idempotent."
  [deps input]
  (let [{:keys [org-id request-id editor-user-id field-name new-value idempotency-key]} input
        cmgr     (get-cmgr deps)
        mod-name (rama/module-name)
        depot    (rama/depot cmgr mod-name "*headcount-depot")
        edited-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->HeadcountFieldEdit request-id org-id editor-user-id field-name new-value edited-at idempotency-key)
      :ack)
    [true {:request-id request-id :field-name field-name :new-value new-value}]))

(defn transition-headcount-to-hire! [deps input]
  (let [{:keys [org-id request-id hired-user-id role idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*headcount-depot")
        transitioned-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->HeadcountTransitionHire request-id org-id hired-user-id (or role "MEMBER") transitioned-at idempotency-key)
      :ack)
    [true {:request-id request-id :hired-user-id hired-user-id :status :filled}]))

(defn get-headcount-request [deps request-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        reqs (rama/pstate cmgr mod-name "$$headcount-requests")]
    (safe-select-one (keypath request-id) reqs)))

(defn get-user-pending-approvals [deps user-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pending (rama/pstate cmgr mod-name "$$user-pending-approvals")]
    (set (keys (or (safe-select-one (keypath user-id) pending) {})))))

(defn get-request-timeline [deps request-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        timeline (rama/pstate cmgr mod-name "$$request-timeline")]
    (or (safe-select-one (keypath request-id) timeline) [])))

;; -----------------------------------------------------------------------------
;; Scoped Actors & Policies Core APIs
;; -----------------------------------------------------------------------------

(defn assign-org-actor! [deps input]
  (let [{:keys [org-id unit-id user-id role]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*actor-depot")
        assigned-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->OrgActorAssign org-id unit-id user-id role assigned-at)
      :ack)
    [true {:unit-id unit-id :user-id user-id :role role}]))

(defn remove-org-actor! [deps input]
  (let [{:keys [org-id unit-id user-id role]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*actor-depot")
        removed-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->OrgActorRemove org-id unit-id user-id role removed-at)
      :ack)
    [true {:unit-id unit-id :role role}]))

(defn set-approval-rules! [deps org-id rules]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*policy-depot")
        updated-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->ApprovalRuleSet org-id rules updated-at)
      :ack)
    [true {:org-id org-id :count (count rules)}]))

(defn get-approval-rules [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        rules (rama/pstate cmgr mod-name "$$approval-rules")]
    (or (safe-select-one (keypath org-id) rules) [])))

(defn set-role-permissions! [deps org-id role permissions]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*policy-depot")
        updated-at (now-ms)]
    (ramaapi/foreign-append! depot
      (rec/->RolePermissionSet org-id role permissions updated-at)
      :ack)
    [true {:org-id org-id :role role :permissions permissions}]))

(defn get-role-permissions [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        perms (rama/pstate cmgr mod-name "$$role-permissions")]
    (or (safe-select-one (keypath org-id) perms) {})))

(defn get-unit-actors [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        actors (rama/pstate cmgr mod-name "$$unit-actors")]
    (or (safe-select-one (keypath unit-id) actors) {})))

(defn get-approval-sla-latencies [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        sla (rama/pstate cmgr mod-name "$$approval-sla")]
    (or (safe-select-one (keypath unit-id) sla) [])))

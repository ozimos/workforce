(ns com.ozimos.workforce.org.core
  (:require
   [clojure.string :as str]
   [com.ozimos.omni-auth.rama.interface :as rama]
   [com.ozimos.workforce.org.records :as rec]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.ops :as ops]
   [com.rpl.rama.path :refer [keypath]]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- get-cmgr [deps]
  (or (-> deps :rama :cluster-manager)
      (-> deps :rama/cluster :cluster-manager)
      (:cluster-manager deps)
      (throw (ex-info "Could not resolve Rama cluster manager from deps"
                      {:deps-keys (keys deps)}))))

(defn- safe-select-one [path pstate-obj]
  (when pstate-obj
    (ramaapi/foreign-select-one path pstate-obj)))

(defn- unwrap-ack [res]
  (if (map? res)
    (or (get res "auth") (get res :auth) (first (vals res)))
    res))

(defn create-org! [deps input]
  (let [{:keys [id name owner-user-id]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        org-create-depot (rama/depot cmgr mod-name "*org-create-depot")
        uuid (or id (str (ops/random-uuid7)))
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
        id (if (string? org-id)
             (try (parse-long org-id) (catch Exception _ nil))
             org-id)
        org (when id (safe-select-one (keypath id) orgs))]
    (when (:name org)
      (assoc org :id id))))

(defn find-org-by-name [deps name]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        org-name->id (rama/pstate cmgr mod-name "$$org-name->id")
        org-id (safe-select-one (keypath name) org-name->id)]
    (when org-id
      (find-org-by-id deps org-id))))

(defn get-org [deps org-id]
  (find-org-by-id deps org-id))

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
        invitation-id (str (ops/random-uuid7))
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
        unit-id (or (:unit-id input) (str (ops/random-uuid7)))
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

(defn get-unit-actors [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        actors (rama/pstate cmgr mod-name "$$unit-actors")
        raw (or (safe-select-one (keypath unit-id) actors) {})]
    (reduce-kv (fn [acc k v]
                 (let [k-str (str k)
                       clean-k (if (str/starts-with? k-str ":")
                                 (subs k-str 1)
                                 k-str)]
                   (assoc acc (keyword clean-k) v)))
               {}
               raw)))

(defn list-org-units
  "Returns all units belonging to an organization, enriched with budget, headcount stats, actors, and children."
  [deps org-id]
  (let [oid (if (string? org-id)
              (try (parse-long org-id) (catch Exception _ nil))
              org-id)
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        org->units-pstate (rama/pstate cmgr mod-name "$$org->units")
        unit-ids (set (keys (or (safe-select-one (keypath oid) org->units-pstate) {})))]
    (mapv (fn [uid]
            (let [u (get-org-unit deps uid)
                  stats (or (get-unit-headcount-stats deps uid)
                            {:budget (:budget u 0) :filled 0 :open (:budget u 0) :pending 0})
                  actors (get-unit-actors deps uid)
                  children (vec (sort (get-org-children deps uid)))]
              (merge (or u {:unit-id uid :name uid :org-id oid})
                     stats
                     {:actors actors
                      :children children})))
          (sort unit-ids))))

;; -----------------------------------------------------------------------------
;; Headcount Requisition Core APIs
;; -----------------------------------------------------------------------------

(defn create-headcount-request! [deps input]
  (let [{:keys [org-id unit-id division-id dept-id location job-level
                employee-type requester-id title justification
                job-description salary-band bonus-target chain-snapshot
                idempotency-key]} input
        request-id (or (:request-id input) (str (ops/random-uuid7)))
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*headcount-depot")
        created-at (now-ms)
        init-status (or (:status input) :in-approval)
        res (unwrap-ack (ramaapi/foreign-append! depot
                          (rec/->HeadcountCreate request-id org-id unit-id division-id dept-id location
                            job-level employee-type requester-id title justification
                            job-description salary-band bonus-target init-status
                            1 (or chain-snapshot []) created-at idempotency-key)
                          :ack))
        final-rid (if (and (string? res) (seq res)) res request-id)]
    [true {:request-id final-rid :status init-status :current-step 1}]))

(defn approve-headcount-step! [deps input]
  (let [{:keys [org-id request-id approver-user-id idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*headcount-depot")
        approved-at (now-ms)
        res (ramaapi/foreign-append! depot
              (rec/->HeadcountApproveStep request-id org-id approver-user-id approved-at idempotency-key)
              :ack)]
    [true {:result (unwrap-ack res) :request-id request-id}]))

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

(defn list-headcount-requests [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        unit-reqs-pstate (rama/pstate cmgr mod-name "$$unit-requests")
        reqs-pstate (rama/pstate cmgr mod-name "$$headcount-requests")
        units (list-org-units deps org-id)
        unit-ids (map :unit-id units)]
    (vec (keep (fn [req-id] (safe-select-one (keypath req-id) reqs-pstate))
               (distinct (mapcat (fn [uid] (keys (or (safe-select-one (keypath uid) unit-reqs-pstate) {})))
                                 unit-ids))))))

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

(defn get-approval-sla-latencies [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        sla (rama/pstate cmgr mod-name "$$approval-sla")]
    (or (safe-select-one (keypath unit-id) sla) [])))

;; =============================================================================
;; Phase 15: Currency, Load Factors, Custom Attributes & Employee Financials
;; =============================================================================

(defn set-org-currency! [deps input]
  (let [{:keys [org-id base-currency]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*currency-depot")]
    (ramaapi/foreign-append! depot (rec/->OrgCurrencySet org-id (or base-currency "USD") (now-ms)) :ack)
    [true {:org-id org-id :base-currency (or base-currency "USD")}]))

(defn get-org-currency-settings [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$org-currency-settings")]
    (or (safe-select-one (keypath org-id) pstate) {:base-currency "USD"})))

(defn set-fx-rate! [deps input]
  (let [{:keys [org-id from-currency to-currency rate]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*currency-depot")]
    (ramaapi/foreign-append! depot (rec/->OrgFxRateSet org-id from-currency to-currency (double rate) (now-ms)) :ack)
    [true input]))

(defn get-fx-rates [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$fx-rates")]
    (or (safe-select-one (keypath org-id) pstate) {})))

(defn define-employee-type! [deps input]
  (let [{:keys [org-id type-id label annual-multiplier hours-per-week default-benefits?]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*load-factor-depot")]
    (ramaapi/foreign-append! depot
      (rec/->EmployeeTypeDefine org-id type-id label annual-multiplier hours-per-week default-benefits? (now-ms))
      :ack)
    [true {:org-id org-id :type-id (keyword type-id)}]))

(defn get-employee-types [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$employee-types")]
    (or (safe-select-one (keypath org-id) pstate) {})))

(defn set-load-factor! [deps input]
  (let [{:keys [org-id location-code job-category job-level multiplier]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*load-factor-depot")]
    (ramaapi/foreign-append! depot
      (rec/->LoadFactorRuleSet org-id location-code job-category job-level multiplier (now-ms))
      :ack)
    [true input]))

(defn get-load-factors [deps org-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$load-factors")]
    (or (safe-select-one (keypath org-id) pstate) {})))

(defn define-tenant-attribute! [deps input]
  (let [{:keys [org-id attribute-id target-entity label data-type cost-modifier? cost-cadence currency options required? default-value]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*tenant-attr-depot")]
    (ramaapi/foreign-append! depot
      (rec/->TenantAttributeDefine org-id attribute-id (or target-entity :employment) label (or data-type :string)
                                   (boolean cost-modifier?) (or cost-cadence :annual) currency options (boolean required?) default-value (now-ms))
      :ack)
    [true {:org-id org-id :attribute-id (keyword attribute-id)}]))

(defn get-tenant-attributes [deps org-id & [target-entity]]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$tenant-attribute-definitions")]
    (if target-entity
      (or (safe-select-one (keypath org-id (keyword target-entity)) pstate) {})
      (or (safe-select-one (keypath org-id) pstate) {}))))

(defn hire-employee! [deps input]
  (let [{:keys [employee-id org-id user-id first-name last-name personal-email hire-date status
                employment-id unit-id job-title job-category job-level employee-type location
                base-salary currency bonus-target custom-attributes start-date idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*employee-depot")
        eid (or employee-id (str (ops/random-uuid7)))
        empid (or employment-id (str (ops/random-uuid7)))
        now (now-ms)
        h-date (or hire-date (subs (str (java.time.LocalDate/now)) 0 10))]
    (ramaapi/foreign-append! depot
      (rec/->EmployeeHire eid org-id user-id first-name last-name personal-email h-date (or status :active)
                          empid unit-id job-title job-category job-level (or employee-type :full-time) location
                          (or base-salary 0.0) (or currency "USD") (or bonus-target 0.0) (or custom-attributes {})
                          (or start-date h-date) now idempotency-key)
      :ack)
    [true {:employee-id eid :employment-id empid :org-id org-id :unit-id unit-id}]))

(defn transfer-employment! [deps input]
  (let [{:keys [employment-id employee-id org-id unit-id job-title job-category job-level employee-type location
                base-salary currency bonus-target custom-attributes effective-date previous-employment-id idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*employment-depot")
        empid (or employment-id (str (ops/random-uuid7)))
        eff (or effective-date (subs (str (java.time.LocalDate/now)) 0 10))]
    (ramaapi/foreign-append! depot
      (rec/->EmploymentTransfer empid employee-id org-id unit-id job-title job-category job-level (or employee-type :full-time) location
                                (or base-salary 0.0) (or currency "USD") (or bonus-target 0.0) (or custom-attributes {})
                                eff previous-employment-id idempotency-key)
      :ack)
    [true {:employment-id empid :employee-id employee-id :unit-id unit-id}]))

(defn revise-employment-comp! [deps input]
  (let [{:keys [employment-id employee-id org-id base-salary currency bonus-target custom-attributes effective-date idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*employment-depot")
        eff (or effective-date (subs (str (java.time.LocalDate/now)) 0 10))]
    (ramaapi/foreign-append! depot
      (rec/->EmploymentCompRevision employment-id employee-id org-id (or base-salary 0.0) (or currency "USD")
                                    (or bonus-target 0.0) (or custom-attributes {}) eff idempotency-key)
      :ack)
    [true {:employment-id employment-id :employee-id employee-id}]))

(defn terminate-employee! [deps input]
  (let [{:keys [employee-id org-id end-date termination-reason idempotency-key]} input
        cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        depot (rama/depot cmgr mod-name "*employee-depot")
        ed (or end-date (subs (str (java.time.LocalDate/now)) 0 10))]
    (ramaapi/foreign-append! depot
      (rec/->EmployeeTerminate employee-id org-id ed termination-reason (now-ms) idempotency-key)
      :ack)
    [true {:employee-id employee-id :status :terminated}]))

(defn get-employee [deps employee-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$employees")]
    (safe-select-one (keypath employee-id) pstate)))

(defn get-employment [deps employment-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$employments")]
    (safe-select-one (keypath employment-id) pstate)))

(defn get-employee-employment-history [deps employee-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$employee->employment-history")]
    (or (safe-select-one (keypath employee-id) pstate) [])))

(defn list-unit-employments [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$unit->employments")
        emp-ids (keys (or (safe-select-one (keypath unit-id) pstate) {}))]
    (mapv #(get-employment deps %) emp-ids)))

(defn get-unit-cost-stats [deps unit-id]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        pstate (rama/pstate cmgr mod-name "$$unit-cost-stats")]
    (or (safe-select-one (keypath unit-id) pstate)
        {:headcount 0
         :total-raw-base-payroll 0.0
         :total-loaded-payroll 0.0
         :total-custom-modifiers-cost 0.0
         :total-cost-base-currency 0.0})))

;; =============================================================================
;; Workforce Chart & Reporting Hierarchy (Backend RBAC/ABAC Security Layer)
;; =============================================================================

(defonce ^:private seed-workforce-cache (atom {}))

(defn- get-seed-org-data [org-name-or-id]
  (if-let [cached (get @seed-workforce-cache org-name-or-id)]
    cached
    (try
      (when-let [read-fn (requiring-resolve 'com.ozimos.workforce.org.seed/read-seed-nippy)]
        (let [dataset (read-fn)
              orgs (:organizations dataset)
              found (or (some #(when (or (= (:org-id %) (str org-name-or-id))
                                         (= (:name %) (str org-name-or-id))) %) orgs)
                        (first orgs))]
          (when found
            (swap! seed-workforce-cache assoc org-name-or-id found)
            found)))
      (catch Exception _ nil))))

(defn- can-view-comp? [viewer-ctx]
  (let [role-raw (or (:role viewer-ctx) :employee)
        role (keyword (str/lower-case (name role-raw)))]
    (case role
      (:admin :hr :recruiter :dept-head :hiring-manager) true
      false)))

(defn- abac-allows-headcount? [hc policy]
  (if (or (nil? policy) (empty? policy))
    true
    (let [{:keys [allowed-divisions allowed-depts allowed-levels allowed-locations]} policy
          div-id   (or (:division-id hc) (:headcount/division-id hc))
          dept-id  (or (:dept-id hc) (:unit-id hc) (:headcount/dept-id hc))
          level    (or (:job-level hc) (:headcount/job-level hc))
          loc      (or (:location hc) (:headcount/location hc))]
      (and (or (nil? allowed-divisions) (contains? allowed-divisions div-id))
           (or (nil? allowed-depts) (contains? allowed-depts dept-id))
           (or (nil? allowed-levels) (contains? allowed-levels level))
           (or (nil? allowed-locations) (contains? allowed-locations loc))))))

(defonce ^:private org-chart-settings-store (atom {}))

(defn get-org-chart-settings
  "Retrieves org chart configuration settings for org-id."
  [_deps org-id]
  (get @org-chart-settings-store org-id {}))

(defn update-org-chart-settings!
  "Updates org chart configuration settings for org-id."
  [_deps org-id settings]
  (swap! org-chart-settings-store assoc org-id settings)
  settings)

(defn count-descendants
  "Calculates the total number of transitive descendants under node-id in hierarchy."
  [hierarchy node-id]
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY (get hierarchy node-id []))
         visited #{node-id}
         cnt 0]
    (if (empty? queue)
      cnt
      (let [curr (peek queue)
            q' (pop queue)]
        (if (contains? visited curr)
          (recur q' visited cnt)
          (let [children (get hierarchy curr [])]
            (recur (into q' children)
                   (conj visited curr)
                   (inc cnt))))))))

(defn ceo-title?
  "Checks if a title string represents a Chief Executive Officer."
  [title]
  (boolean (and title (re-find #"(?i)\bceo\b" (str title)))))

(defn resolve-full-org-root
  "Resolves the root of the full organization hierarchy according to:
   1. Configured app setting (:root-id or :co-equal-ids)
   2. Employee with job title matching 'CEO'
   3. Graceful fallback: Employee with the highest number of transitive descendants."
  [workforce-list hierarchy chart-settings]
  (let [emp-ids (set (map :person/id workforce-list))
        setting-root-id (:root-id chart-settings)
        co-equal-ids (filterv emp-ids (:co-equal-ids chart-settings))]
    (cond
      ;; 1a. Explicit configured root ID
      (and setting-root-id (contains? emp-ids setting-root-id))
      {:root-id setting-root-id :synthetic-node nil}

      ;; 1b. Configured co-equal leaders (>= 2)
      (>= (count co-equal-ids) 2)
      (let [visual-title (or (:visual-root-title chart-settings) "Executive Leadership")
            synth-node {:person/id "__visual_root__"
                        :person/name visual-title
                        :person/title "Co-Equal Leadership"
                        :person/role :admin
                        :person/department-name "Executive Office"
                        :person/is-synthetic? true}]
        {:root-id "__visual_root__"
         :synthetic-node synth-node
         :co-equal-ids co-equal-ids})

      ;; 2. Employee with title 'CEO'
      :else
      (if-let [ceo-emp (first (filter #(ceo-title? (:person/title %)) workforce-list))]
        {:root-id (:person/id ceo-emp) :synthetic-node nil}

        ;; 3. Graceful fallback: Employee with maximum descendants
        (if (seq workforce-list)
          (let [scored (map (fn [emp]
                              {:id (:person/id emp)
                               :descendants (count-descendants hierarchy (:person/id emp))})
                            workforce-list)
                top (apply max-key :descendants scored)]
            {:root-id (:id top) :synthetic-node nil})
          {:root-id nil :synthetic-node nil})))))

(defn get-org-workforce-chart
  "Retrieves the workforce chart for an organization, enforcing backend-level
   RBAC compensation masking and ABAC headcount filtering before serialization."
  [deps org-id viewer-ctx abac-policy]
  (let [seed-org (get-seed-org-data org-id)
        view-comp? (can-view-comp? viewer-ctx)
        chart-settings (get-org-chart-settings deps org-id)]
    (if seed-org
      ;; 1. Use seeded enterprise workforce dataset (10k nodes)
      (let [tree (:tree seed-org)
            employees (:employees seed-org)
            employments (:employments seed-org)
            headcounts (:headcounts seed-org)
            emp-by-nid (into {} (map (fn [e] [(str/replace (:employee-id e) #"^emp-" "") e])) employees)
            empmt-by-nid (into {} (map (fn [e] [(str/replace (:employee-id e) #"^emp-" "") e])) employments)
            hc-by-nid (into {} (map (fn [h] [(str/replace (:request-id h) #"^req-" "") h])) headcounts)
            children (:children tree)
            raw-root-id (:root-id tree)

            ;; Filter headcounts on the backend via ABAC before transmission
            allowed-hc-by-nid (into {} (filter (fn [[_ h]] (abac-allows-headcount? h abac-policy)) hc-by-nid))

            ;; Build workforce employee list with backend comp masking
            workforce-list
            (mapv (fn [e]
                    (let [nid (str/replace (:employee-id e) #"^emp-" "")
                          empmt (get empmt-by-nid nid)]
                      {:person/id nid
                       :person/name (str (:first-name e) " " (:last-name e))
                       :person/title (:job-title empmt "Employee")
                       :person/email (:personal-email e)
                       :person/unit-id (:unit-id empmt)
                       :person/department-name (:unit-id empmt)
                       :person/division-name (when-let [cat (:job-category empmt)] (name cat))
                       :person/role (case (:job-level empmt)
                                      ("L8" "L7") :admin
                                      ("L6") :vp
                                      ("L5") :dept-head
                                      ("L4") :hiring-manager
                                      :employee)
                       :person/job-level (:job-level empmt)
                       :person/location (:location empmt)
                       :person/compensation (when view-comp?
                                              {:salary (:base-salary empmt)
                                               :currency (:currency empmt "USD")})}))
                  employees)

            ;; Build workforce-hierarchy and headcounts-by-manager
            workforce-hierarchy (atom {nil [raw-root-id]})
            headcounts-by-manager (atom {})]

        (doseq [[parent-nid child-nids] children]
          (let [emp-children (filterv #(contains? emp-by-nid %) child-nids)
                hc-children (keep #(get allowed-hc-by-nid %) child-nids)]
            (when (seq emp-children)
              (swap! workforce-hierarchy assoc parent-nid emp-children))
            (when (seq hc-children)
              (swap! headcounts-by-manager assoc parent-nid
                     (mapv (fn [hc]
                             {:headcount/id (:request-id hc)
                              :headcount/title (:title hc "Open Position")
                              :headcount/job-level (:job-level hc)
                              :headcount/location (:location hc)
                              :headcount/division-id (:division-id hc)
                              :headcount/dept-id (:unit-id hc)
                              :headcount/status (name (or (:status hc) :open))})
                           hc-children)))))

        ;; Apply full org root resolution (Settings -> CEO -> Max Descendants)
        (let [resolved (resolve-full-org-root workforce-list @workforce-hierarchy chart-settings)
              final-workforce (if-let [synth (:synthetic-node resolved)]
                                (conj workforce-list synth)
                                workforce-list)
              final-hierarchy (cond
                                (:synthetic-node resolved)
                                (assoc (dissoc @workforce-hierarchy nil)
                                       nil ["__visual_root__"]
                                       "__visual_root__" (:co-equal-ids resolved))

                                (:root-id resolved)
                                (assoc @workforce-hierarchy nil [(:root-id resolved)])

                                :else
                                @workforce-hierarchy)]
          {:org/id org-id
           :workforce/list final-workforce
           :workforce-hierarchy final-hierarchy
           :headcounts/list (mapv (fn [hc]
                                    {:headcount/id (:request-id hc)
                                     :headcount/title (:title hc "Open Position")
                                     :headcount/job-level (:job-level hc)
                                     :headcount/location (:location hc)
                                     :headcount/division-id (:division-id hc)
                                     :headcount/dept-id (:unit-id hc)
                                     :headcount/status (name (or (:status hc) :open))})
                                  (vals allowed-hc-by-nid))
           :headcounts-by-manager @headcounts-by-manager
           :org/chart-settings chart-settings}))

      ;; 2. Fallback for dynamic/new orgs in Rama
      (let [members (list-members deps org-id)
            headcounts (filterv #(abac-allows-headcount? % abac-policy) (list-headcount-requests deps org-id))
            owner-id (or (:owner-user-id (find-org-by-id deps org-id)) (-> members first :user-id) "u-owner")
            workforce-list
            (mapv (fn [m]
                    {:person/id (str (:user-id m))
                     :person/name (or (:name m) (:email m) (str "User " (:user-id m)))
                     :person/title (if (= (:user-id m) owner-id) "Executive & Owner" (str (:role m "Member")))
                     :person/email (:email m)
                     :person/role (keyword (str/lower-case (str (:role m "employee"))))
                     :person/compensation (when view-comp? {:salary 150000 :currency "USD"})})
                  members)
            base-hierarchy {nil [owner-id]
                            owner-id (mapv #(str (:user-id %)) (remove #(= (:user-id %) owner-id) members))}
            resolved (resolve-full-org-root workforce-list base-hierarchy chart-settings)
            final-workforce (if-let [synth (:synthetic-node resolved)]
                              (conj workforce-list synth)
                              workforce-list)
            final-hierarchy (cond
                              (:synthetic-node resolved)
                              (assoc (dissoc base-hierarchy nil)
                                     nil ["__visual_root__"]
                                     "__visual_root__" (:co-equal-ids resolved))

                              (:root-id resolved)
                              (assoc base-hierarchy nil [(:root-id resolved)])

                              :else
                              base-hierarchy)
            headcounts-by-mgr (if (seq headcounts) {owner-id headcounts} {})]
        {:org/id org-id
         :workforce/list final-workforce
         :workforce-hierarchy final-hierarchy
         :headcounts/list headcounts
         :headcounts-by-manager headcounts-by-mgr
         :org/chart-settings chart-settings}))))

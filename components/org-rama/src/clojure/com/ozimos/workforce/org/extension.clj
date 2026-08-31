(ns com.ozimos.workforce.org.extension
  (:require
   [com.ozimos.omni-auth.rama.extension :as ext]
   [com.ozimos.workforce.org.records]
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]
   [integrant.core :as ig])
  (:import
   (com.rpl.rama.helpers ModuleUniqueIdPState)))

(declare
  *org-create-depot *org-invite-depot *org-join-depot *org-switch-depot
  *org-member-update-depot *org-member-remove-depot
  *org-unit-depot *headcount-depot *actor-depot *policy-depot
  *employee-depot *employment-depot *tenant-attr-depot *currency-depot *load-factor-depot
  $$orgs $$org-name->id $$org-create-ids $$memberships $$user-orgs $$org-users
  $$user-active-org $$invitations $$org-invitations $$email->invitations $$org-members
  $$org-units $$org->units $$org-hierarchy $$org-child-parent
  $$headcount-requests $$unit-requests $$user-pending-approvals
  $$request-timeline $$unit-headcount-stats $$approval-sla
  $$unit-actors $$approval-rules $$role-permissions
  $$processed-idempotency-keys
  $$org-currency-settings $$fx-rates $$employee-types $$load-factors
  $$tenant-attribute-definitions $$employees $$employments
  $$employee->employment-history $$unit->employments $$unit-cost-stats)

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

(defn normalize-annual-cost [val cadence]
  (let [num (or (when (number? val) val) 0.0)]
    (case (keyword cadence)
      :monthly (* num 12.0)
      :one-off num
      :annual num
      num)))

(defn compute-custom-cost-modifiers [custom-attrs attr-defs]
  (reduce-kv
   (fn [acc attr-id val]
     (let [def (get attr-defs (keyword attr-id))
           cost-mod? (true? (:cost-modifier? def))
           cadence (:cost-cadence def :annual)]
       (if (and cost-mod? (number? val))
         (+ acc (normalize-annual-cost val cadence))
         acc)))
   0.0
   (or custom-attrs {})))

(defn resolve-load-factor [load-factors location-code job-category job-level]
  (let [loc (or location-code "*")
        cat (if (keyword? job-category) (name job-category) (or (str job-category) "*"))
        lvl (or job-level "*")]
    (or (get load-factors [loc cat lvl])
        (get load-factors [loc cat "*"])
        (get load-factors [loc "*" "*"])
        (get load-factors ["*" "*" "*"])
        1.0)))

(defn resolve-fx-rate [fx-rates from-curr to-curr]
  (let [from (or from-curr "USD")
        to (or to-curr "USD")]
    (if (= from to)
      1.0
      (or (get fx-rates [from to])
          (when-let [inv (get fx-rates [to from])]
            (if (pos? inv) (/ 1.0 inv) 1.0))
          1.0))))

(defn calculate-employment-loaded-cost
  [emp-data emp-types load-factors fx-rates attr-defs base-curr]
  (let [stated-base (double (or (:base-salary emp-data) 0.0))
        emp-type-kw (keyword (or (:employee-type emp-data) :full-time))
        emp-type-def (get emp-types emp-type-kw)
        type-multiplier (double (or (:annual-multiplier emp-type-def)
                                    (case emp-type-kw
                                      :full-time 1.0
                                      :part-time 0.6
                                      :intern 0.25
                                      1.0)))
        annual-base (* stated-base type-multiplier)
        lf (double (resolve-load-factor load-factors (:location emp-data) (:job-category emp-data) (:job-level emp-data)))
        loaded-base (* annual-base lf)
        bonus-rate (double (or (:bonus-target emp-data) 0.0))
        bonus-val (* annual-base bonus-rate)
        custom-cost (double (compute-custom-cost-modifiers (:custom-attributes emp-data) attr-defs))
        local-total (+ loaded-base bonus-val custom-cost)
        fx (double (resolve-fx-rate fx-rates (or (:currency emp-data) "USD") (or base-curr "USD")))
        converted-total (* local-total fx)
        converted-base (* loaded-base fx)]
    {:annual-base annual-base
     :loaded-base loaded-base
     :custom-modifiers-cost custom-cost
     :local-total local-total
     :converted-base-cost converted-base
     :converted-total-cost converted-total}))

(defn employee-hire->employee-map [cmd]
  {:employee-id (:employee-id cmd)
   :org-id (:org-id cmd)
   :user-id (:user-id cmd)
   :first-name (:first-name cmd)
   :last-name (:last-name cmd)
   :personal-email (:personal-email cmd)
   :hire-date (:hire-date cmd)
   :status (or (:status cmd) :active)
   :current-employment-id (:employment-id cmd)
   :created-at (:created-at cmd)
   :updated-at (:created-at cmd)})

(defn employee-hire->employment-map [cmd]
  {:employment-id (:employment-id cmd)
   :employee-id (:employee-id cmd)
   :org-id (:org-id cmd)
   :unit-id (:unit-id cmd)
   :job-title (:job-title cmd)
   :job-category (:job-category cmd)
   :job-level (:job-level cmd)
   :employee-type (or (:employee-type cmd) :full-time)
   :location (:location cmd)
   :base-salary (or (:base-salary cmd) 0.0)
   :currency (or (:currency cmd) "USD")
   :bonus-target (or (:bonus-target cmd) 0.0)
   :custom-attributes (or (:custom-attributes cmd) {})
   :start-date (or (:start-date cmd) (:hire-date cmd))
   :end-date nil
   :status :active
   :created-at (:created-at cmd)
   :updated-at (:created-at cmd)})

(defn accumulate-unit-cost [cur-cost cost-info]
  (let [cur (or cur-cost {:headcount 0
                          :total-raw-base-payroll 0.0
                          :total-loaded-payroll 0.0
                          :total-custom-modifiers-cost 0.0
                          :total-cost-base-currency 0.0})
        next-hc (+ (or (:headcount cur) 0) 1)
        next-raw-b (+ (or (:total-raw-base-payroll cur) 0.0) (or (:annual-base cost-info) 0.0))
        next-load-b (+ (or (:total-loaded-payroll cur) 0.0) (or (:loaded-base cost-info) 0.0))
        next-cust-c (+ (or (:total-custom-modifiers-cost cur) 0.0) (or (:custom-modifiers-cost cost-info) 0.0))
        next-tot-c (+ (or (:total-cost-base-currency cur) 0.0) (or (:converted-total-cost cost-info) 0.0))]
    {:headcount next-hc
     :total-raw-base-payroll next-raw-b
     :total-loaded-payroll next-load-b
     :total-custom-modifiers-cost next-cust-c
     :total-cost-base-currency next-tot-c}))

(defn dec-unit-cost-headcount [cur-cost]
  (if cur-cost
    (update cur-cost :headcount (fn [hc] (max 0 (dec (or hc 1)))))
    {:headcount 0
     :total-raw-base-payroll 0.0
     :total-loaded-payroll 0.0
     :total-custom-modifiers-cost 0.0
     :total-cost-base-currency 0.0}))

(defn make-employment-transfer-map [cmd eff-date now-ts]
  {:employment-id (:employment-id cmd)
   :employee-id (:employee-id cmd)
   :org-id (:org-id cmd)
   :unit-id (:unit-id cmd)
   :job-title (:job-title cmd)
   :job-category (:job-category cmd)
   :job-level (:job-level cmd)
   :employee-type (or (:employee-type cmd) :full-time)
   :location (:location cmd)
   :base-salary (or (:base-salary cmd) 0.0)
   :currency (or (:currency cmd) "USD")
   :bonus-target (or (:bonus-target cmd) 0.0)
   :custom-attributes (or (:custom-attributes cmd) {})
   :start-date eff-date
   :end-date nil
   :status :active
   :created-at now-ts
   :updated-at now-ts})

(defn process-currency-cmd [curr-cmd]
  (cond
    (instance? com.ozimos.workforce.org.records.OrgCurrencySet curr-cmd)
    [:set-currency (:org-id curr-cmd) (:base-currency curr-cmd) nil]
    (instance? com.ozimos.workforce.org.records.OrgFxRateSet curr-cmd)
    [:set-fx (:org-id curr-cmd) [(:from-currency curr-cmd) (:to-currency curr-cmd)] (:rate curr-cmd)]
    :else nil))

(defn process-load-factor-cmd [lf-cmd]
  (cond
    (instance? com.ozimos.workforce.org.records.EmployeeTypeDefine lf-cmd)
    [:define-type (:org-id lf-cmd) (keyword (:type-id lf-cmd))
     {:type-id (keyword (:type-id lf-cmd))
      :label (:label lf-cmd)
      :annual-multiplier (:annual-multiplier lf-cmd)
      :hours-per-week (:hours-per-week lf-cmd)
      :default-benefits? (:default-benefits? lf-cmd)
      :updated-at (:updated-at lf-cmd)}]

    (instance? com.ozimos.workforce.org.records.LoadFactorRuleSet lf-cmd)
    (let [cat (if (keyword? (:job-category lf-cmd)) (name (:job-category lf-cmd)) (str (:job-category lf-cmd)))]
      [:set-factor (:org-id lf-cmd) [(:location-code lf-cmd) cat (str (:job-level lf-cmd))] (:multiplier lf-cmd)])
    :else nil))

(defn process-tenant-attr-cmd [cmd]
  (let [attr-kw (keyword (:attribute-id cmd))
        tgt-kw (keyword (:target-entity cmd))]
    [tgt-kw attr-kw
     {:attribute-id attr-kw
      :target-entity tgt-kw
      :label (:label cmd)
      :data-type (keyword (:data-type cmd))
      :cost-modifier? (:cost-modifier? cmd)
      :cost-cadence (keyword (:cost-cadence cmd))
      :currency (:currency cmd)
      :options (:options cmd)
      :required? (:required? cmd)
      :default-value (:default-value cmd)
      :updated-at (:updated-at cmd)}]))

(defn process-hire-cmd [cmd]
  [(:employee-id cmd)
   (:employment-id cmd)
   (:org-id cmd)
   (:unit-id cmd)
   (employee-hire->employee-map cmd)
   (employee-hire->employment-map cmd)])

(defn process-terminate-cmd [cmd]
  [(:employee-id cmd)
   (:end-date cmd)
   (:termination-reason cmd)
   (:updated-at cmd)])

(defn process-transfer-cmd [cmd now-ts]
  [(:employment-id cmd)
   (:employee-id cmd)
   (:org-id cmd)
   (:unit-id cmd)
   (:previous-employment-id cmd)
   (:effective-date cmd)
   (make-employment-transfer-map cmd (:effective-date cmd) now-ts)])

(defn process-comp-rev-cmd [cmd]
  [(:employment-id cmd)
   (:base-salary cmd)
   (:currency cmd)
   (:bonus-target cmd)
   (:custom-attributes cmd)])

(defonce ^:private org-id-gen (ModuleUniqueIdPState. "$$org-id-gen"))

(defn- build-org-lifecycle-topology [s]
  #_{:clj-kondo/ignore [:unused-binding :rama-unverifiable-pobject]}
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
             (ack-return> true)))

(defn- build-org-unit-topology [s]
  #_{:clj-kondo/ignore [:unused-binding :rama-unverifiable-pobject]}
  (<<sources s
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
                                     (ack-return> nil)))))))

(defn- build-headcount-topology [s]
  #_{:clj-kondo/ignore [:unused-binding :rama-unverifiable-pobject]}
  (<<sources s
             ;; -------------------------------------------------------------
             ;; Headcount Requisition Lifecycle
             ;; -------------------------------------------------------------
             (source> *headcount-depot :> *req-cmd)
             (get *req-cmd :idempotency-key :> *ikey)
             (get *req-cmd :org-id :> *oid-ikey)
             (<<if (some? *ikey)
                   (|hash *oid-ikey)
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
                         (|hash *oid-ikey)
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
                                           (local-select> (keypath *uid) $$unit-headcount-stats :> *curr-stats-raw)
                                           (default-val *curr-stats-raw (default-stats 0) :> *curr-stats)
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
                                                       (get *req-data :current-approver-id :> *curr-approver)
                                                       (<<if (some? *curr-approver)
                                                             (|hash *curr-approver)
                                                             (local-transform> [(keypath *curr-approver *rid) NONE>] $$user-pending-approvals))
                                                       (|hash *rid)
                                                       (local-transform> [(keypath *rid :status) (termval :draft)] $$headcount-requests)
                                                       (local-transform> [(keypath *rid :current-step) (termval 0)] $$headcount-requests)
                                                       (local-transform> [(keypath *rid :current-approver-id) (termval nil)] $$headcount-requests)
                                                       (local-transform> [(keypath *rid :approved-by) (termval [])] $$headcount-requests)
                                                       (local-transform> [(keypath *rid *field-name) (termval *new-value)] $$headcount-requests)
                                                       (hash-map :event :field-edit-reset :field *field-name :new-value *new-value :actor *editor-uid :timestamp *ts :> *edit-event)
                                                       (local-transform> [(keypath *rid) AFTER-ELEM (termval *edit-event)] $$request-timeline)
                                                       (ack-return> :reset-to-draft)
                                                       (else>)
                                                       (local-transform> [(keypath *rid *field-name) (termval *new-value)] $$headcount-requests)
                                                       (hash-map :event :field-edit :field *field-name :new-value *new-value :actor *editor-uid :timestamp *ts :> *edit-event)
                                                       (local-transform> [(keypath *rid) AFTER-ELEM (termval *edit-event)] $$request-timeline)
                                                       (ack-return> :field-updated))
                                                 (else>)
                                                 (ack-return> nil)))))))))

(defn- build-governance-topology [s]
  #_{:clj-kondo/ignore [:unused-binding :rama-unverifiable-pobject]}
  (<<sources s
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
                         (ack-return> nil)))))

(defn- build-employee-lifecycle-topology [s]
  #_{:clj-kondo/ignore [:unused-binding :rama-unverifiable-pobject]}
  (<<sources s
             ;; -------------------------------------------------------------
             ;; Phase 15: Currency Settings & FX Rates
             ;; -------------------------------------------------------------
             (source> *currency-depot :> *curr-cmd)
             (process-currency-cmd *curr-cmd :> [*op *o *k-or-bc *rate])
             (<<if (= *op :set-currency)
                   (|hash *o)
                   (local-transform> [(keypath *o :base-currency) (termval *k-or-bc)] $$org-currency-settings)
                   (ack-return> *k-or-bc)
                   (else>)
                   (<<if (= *op :set-fx)
                         (|hash *o)
                         (local-transform> [(keypath *o *k-or-bc) (termval *rate)] $$fx-rates)
                         (ack-return> *rate)
                         (else>)
                         (ack-return> nil)))

             ;; -------------------------------------------------------------
             ;; Phase 15: Employee Types & Load Factors
             ;; -------------------------------------------------------------
             (source> *load-factor-depot :> *lf-cmd)
             (process-load-factor-cmd *lf-cmd :> [*lf-op *o *lf-k *lf-val])
             (<<if (= *lf-op :define-type)
                   (|hash *o)
                   (local-transform> [(keypath *o *lf-k) (termval *lf-val)] $$employee-types)
                   (ack-return> *lf-k)
                   (else>)
                   (<<if (= *lf-op :set-factor)
                         (|hash *o)
                         (local-transform> [(keypath *o *lf-k) (termval *lf-val)] $$load-factors)
                         (ack-return> *lf-val)
                         (else>)
                         (ack-return> nil)))

             ;; -------------------------------------------------------------
             ;; Phase 15: Tenant Custom Attribute Definitions
             ;; -------------------------------------------------------------
             (source> *tenant-attr-depot :> *attr-cmd)
             (get *attr-cmd :org-id :> *org-id)
             (process-tenant-attr-cmd *attr-cmd :> [*tgt-kw *attr-kw *attr-def-map])
             (|hash *org-id)
             (local-transform> [(keypath *org-id *tgt-kw *attr-kw) (termval *attr-def-map)] $$tenant-attribute-definitions)
             (ack-return> *attr-kw)

             ;; -------------------------------------------------------------
             ;; Phase 15: Employee Lifecycle (Hire, Status Update, Terminate)
             ;; -------------------------------------------------------------
             (source> *employee-depot :> *emp-cmd)
             (instance? com.ozimos.workforce.org.records.EmployeeHire *emp-cmd :> *is-hire?)
             (<<if *is-hire?
                   (process-hire-cmd *emp-cmd :> [*eid *empid *o *u *employee-map *employment-map])

                   ;; 1. Store Employee & Employment
                   (|hash *eid)
                   (local-transform> [(keypath *eid) (termval *employee-map)] $$employees)
                   (local-select> (keypath *eid) $$employee->employment-history :> *hist)
                   (<<if (some? *hist)
                         (local-transform> [(keypath *eid) AFTER-ELEM (termval *empid)] $$employee->employment-history)
                         (else>)
                         (local-transform> [(keypath *eid) (termval [*empid])] $$employee->employment-history))

                   (|hash *empid)
                   (local-transform> [(keypath *empid) (termval *employment-map)] $$employments)

                   ;; 2. Index employment under org unit
                   (|hash *u)
                   (local-transform> [(keypath *u *empid) (termval true)] $$unit->employments)

                   ;; 3. Update Org Unit Stats (Filled + 1, Open - 1)
                   (|hash *u)
                   (local-select> (keypath *u) $$unit-headcount-stats :> *cur-hc-stats-raw)
                   (default-val *cur-hc-stats-raw (default-stats 0) :> *cur-hc-stats)
                   (update-stats-filled *cur-hc-stats 1 :> *next-hc-stats)
                   (local-transform> [(keypath *u) (termval *next-hc-stats)] $$unit-headcount-stats)

                   ;; 4. Compute Financial Loaded Cost
                   (|hash *o)
                   (local-select> (keypath *o :base-currency) $$org-currency-settings :> *base-curr)
                   (local-select> (keypath *o) $$fx-rates :> *org-fx-rates)
                   (local-select> (keypath *o) $$employee-types :> *org-emp-types)
                   (local-select> (keypath *o) $$load-factors :> *org-load-factors)
                   (local-select> (keypath *o :employment) $$tenant-attribute-definitions :> *emp-attr-defs)
                   (calculate-employment-loaded-cost *employment-map *org-emp-types *org-load-factors *org-fx-rates *emp-attr-defs *base-curr :> *cost-info)

                   ;; Update $$unit-cost-stats
                   (|hash *u)
                   (local-select> (keypath *u) $$unit-cost-stats :> *cur-cost)
                   (accumulate-unit-cost *cur-cost *cost-info :> *next-cost-map)
                   (local-transform> [(keypath *u) (termval *next-cost-map)] $$unit-cost-stats)

                   (ack-return> *eid)
                   (else>)
                   (instance? com.ozimos.workforce.org.records.EmployeeTerminate *emp-cmd :> *is-terminate?)
                   (<<if *is-terminate?
                         (process-terminate-cmd *emp-cmd :> [*eid *end-date *reason *up-at])
                         (|hash *eid)
                         (local-select> (keypath *eid :current-employment-id) $$employees :> *cur-empid)
                         (local-transform> [(keypath *eid :status) (termval :terminated)] $$employees)
                         (local-transform> [(keypath *eid :termination-reason) (termval *reason)] $$employees)
                         (local-transform> [(keypath *eid :end-date) (termval *end-date)] $$employees)
                         (local-transform> [(keypath *eid :updated-at) (termval *up-at)] $$employees)
                         (<<if (some? *cur-empid)
                               (|hash *cur-empid)
                               (local-select> (keypath *cur-empid :unit-id) $$employments :> *u)
                               (local-select> (keypath *cur-empid :org-id) $$employments :> *o)
                               (local-transform> [(keypath *cur-empid :status) (termval :past)] $$employments)
                               (local-transform> [(keypath *cur-empid :end-date) (termval *end-date)] $$employments)
                               (|hash *u)
                               (local-transform> [(keypath *u *cur-empid) NONE>] $$unit->employments)
                               (local-select> (keypath *u) $$unit-headcount-stats :> *term-hc-stats)
                               (<<if (some? *term-hc-stats)
                                     (update-stats-filled *term-hc-stats -1 :> *term-next-hc-stats)
                                     (local-transform> [(keypath *u) (termval *term-next-hc-stats)] $$unit-headcount-stats))
                               (local-select> (keypath *u) $$unit-cost-stats :> *cur-term-cost)
                               (<<if (some? *cur-term-cost)
                                     (dec-unit-cost-headcount *cur-term-cost :> *next-thc-map)
                                     (local-transform> [(keypath *u) (termval *next-thc-map)] $$unit-cost-stats)))
                         (ack-return> *eid)
                         (else>)
                         (instance? com.ozimos.workforce.org.records.EmployeeStatusUpdate *emp-cmd :> *is-stat-up?)
                         (<<if *is-stat-up?
                               (get *emp-cmd :employee-id :> *eid)
                               (get *emp-cmd :status :> *st)
                               (|hash *eid)
                               (local-transform> [(keypath *eid :status) (termval (keyword *st))] $$employees)
                               (ack-return> *eid)
                               (else>)
                               (ack-return> nil))))

             ;; -------------------------------------------------------------
             ;; Phase 15: Employment Management (Transfers & Comp Revisions)
             ;; -------------------------------------------------------------
             (source> *employment-depot :> *empmgt-cmd)
             (instance? com.ozimos.workforce.org.records.EmploymentTransfer *empmgt-cmd :> *is-transfer?)
             (<<if *is-transfer?
                   (now-ms :> *now-ts)
                   (process-transfer-cmd *empmgt-cmd *now-ts :> [*new-empid *eid *o *new-unit-id *prev-empid *eff-date *new-employment-map])

                   ;; 1. Close previous employment if specified
                   (<<if (some? *prev-empid)
                         (|hash *prev-empid)
                         (local-select> (keypath *prev-empid :unit-id) $$employments :> *old-unit-id)
                         (local-transform> [(keypath *prev-empid :status) (termval :past)] $$employments)
                         (local-transform> [(keypath *prev-empid :end-date) (termval *eff-date)] $$employments)
                         (|hash *old-unit-id)
                         (local-transform> [(keypath *old-unit-id *prev-empid) NONE>] $$unit->employments)
                         (local-select> (keypath *old-unit-id) $$unit-headcount-stats :> *xfer-old-hc-stats)
                         (<<if (some? *xfer-old-hc-stats)
                               (update-stats-filled *xfer-old-hc-stats -1 :> *xfer-next-old-hc-stats)
                               (local-transform> [(keypath *old-unit-id) (termval *xfer-next-old-hc-stats)] $$unit-headcount-stats))
                         (local-select> (keypath *old-unit-id) $$unit-cost-stats :> *cur-old-cost)
                         (<<if (some? *cur-old-cost)
                               (dec-unit-cost-headcount *cur-old-cost :> *next-old-cost-map)
                               (local-transform> [(keypath *old-unit-id) (termval *next-old-cost-map)] $$unit-cost-stats)))

                   ;; 2. Store in PStates
                   (|hash *new-empid)
                   (local-transform> [(keypath *new-empid) (termval *new-employment-map)] $$employments)

                   (|hash *eid)
                   (local-transform> [(keypath *eid :current-employment-id) (termval *new-empid)] $$employees)
                   (local-select> (keypath *eid) $$employee->employment-history :> *xfer-hist)
                   (<<if (some? *xfer-hist)
                         (local-transform> [(keypath *eid) AFTER-ELEM (termval *new-empid)] $$employee->employment-history)
                         (else>)
                         (local-transform> [(keypath *eid) (termval [*new-empid])] $$employee->employment-history))

                   (|hash *new-unit-id)
                   (local-transform> [(keypath *new-unit-id *new-empid) (termval true)] $$unit->employments)
                   (local-select> (keypath *new-unit-id) $$unit-headcount-stats :> *xfer-new-hc-stats-raw)
                   (default-val *xfer-new-hc-stats-raw (default-stats 0) :> *xfer-new-hc-stats)
                   (update-stats-filled *xfer-new-hc-stats 1 :> *xfer-next-new-hc-stats)
                   (local-transform> [(keypath *new-unit-id) (termval *xfer-next-new-hc-stats)] $$unit-headcount-stats)

                   ;; 3. Cost rollup for target unit
                   (|hash *o)
                   (local-select> (keypath *o :base-currency) $$org-currency-settings :> *base-c)
                   (local-select> (keypath *o) $$fx-rates :> *fx-m)
                   (local-select> (keypath *o) $$employee-types :> *et-m)
                   (local-select> (keypath *o) $$load-factors :> *lf-m)
                   (local-select> (keypath *o :employment) $$tenant-attribute-definitions :> *attr-m)
                   (calculate-employment-loaded-cost *new-employment-map *et-m *lf-m *fx-m *attr-m *base-c :> *new-cost-info)

                   (|hash *new-unit-id)
                   (local-select> (keypath *new-unit-id) $$unit-cost-stats :> *cur-tgt-cost)
                   (accumulate-unit-cost *cur-tgt-cost *new-cost-info :> *next-tgt-cost-map)
                   (local-transform> [(keypath *new-unit-id) (termval *next-tgt-cost-map)] $$unit-cost-stats)

                   (ack-return> *new-empid)
                   (else>)
                   (instance? com.ozimos.workforce.org.records.EmploymentCompRevision *empmgt-cmd :> *is-comp-rev?)
                   (<<if *is-comp-rev?
                         (process-comp-rev-cmd *empmgt-cmd :> [*rev-empid *rev-base *rev-curr *rev-bonus *rev-attrs])
                         (|hash *rev-empid)
                         (local-transform> [(keypath *rev-empid :base-salary) (termval *rev-base)] $$employments)
                         (local-transform> [(keypath *rev-empid :currency) (termval *rev-curr)] $$employments)
                         (local-transform> [(keypath *rev-empid :bonus-target) (termval *rev-bonus)] $$employments)
                         (local-transform> [(keypath *rev-empid :custom-attributes) (termval *rev-attrs)] $$employments)
                         (ack-return> *rev-empid)
                         (else>)
                         (ack-return> nil)))))

(defrecord OrgExtension []
  ext/RamaModuleExtension
  (declare-depots [_ setup]
    (declare-depot setup *org-create-depot (hash-by :name))
    (declare-depot setup *org-invite-depot (hash-by :invitation-id))
    (declare-depot setup *org-join-depot (hash-by :invitation-id))
    (declare-depot setup *org-switch-depot (hash-by :user-id))
    (declare-depot setup *org-member-update-depot (hash-by :target-user-id))
    (declare-depot setup *org-member-remove-depot (hash-by :target-user-id))
    (declare-depot setup *org-unit-depot (hash-by :unit-id))
    (declare-depot setup *headcount-depot (hash-by :request-id))
    (declare-depot setup *actor-depot (hash-by :unit-id))
    (declare-depot setup *policy-depot (hash-by :org-id))
    (declare-depot setup *employee-depot (hash-by :org-id))
    (declare-depot setup *employment-depot (hash-by :org-id))
    (declare-depot setup *tenant-attr-depot (hash-by :org-id))
    (declare-depot setup *currency-depot (hash-by :org-id))
    (declare-depot setup *load-factor-depot (hash-by :org-id)))

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
    (declare-pstate s $$processed-idempotency-keys
                    {Long (map-schema String Object)})

    ;; --- 7. Phase 15: Currency, Load Factors, Custom Attributes & Employees ---
    (declare-pstate s $$org-currency-settings
                    {Long (map-schema Object Object)})
    (declare-pstate s $$fx-rates
                    {Long (map-schema Object Object)})
    (declare-pstate s $$employee-types
                    {Long (map-schema Object Object)})
    (declare-pstate s $$load-factors
                    {Long (map-schema Object Object)})
    (declare-pstate s $$tenant-attribute-definitions
                    {Long (map-schema Object Object)})
    (declare-pstate s $$employees
                    {String (map-schema Object Object)})
    (declare-pstate s $$employments
                    {String (map-schema Object Object)})
    (declare-pstate s $$employee->employment-history
                    {String (vector-schema String)})
    (declare-pstate s $$unit->employments
                    {String (map-schema String Object)})
    (declare-pstate s $$unit-cost-stats
                    {String (map-schema Object Object)}))

  (build-topology [_ s]
    (build-org-lifecycle-topology s)
    (build-org-unit-topology s)
    (build-headcount-topology s)
    (build-governance-topology s)
    (build-employee-lifecycle-topology s)))

(defmethod ig/init-key :workforce/org-extension [_ _]
  (->OrgExtension))
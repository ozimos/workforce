(ns com.ozimos.workforce.org.resolvers
  (:require
   [clojure.string :as str]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.core :as org]
   [com.ozimos.workforce.org.errors :as errors]
   [com.ozimos.workforce.org.rbac :as rbac]
   [com.ozimos.workforce.org.rule-engine :as re]
   [com.wsscode.pathom3.connect.operation :as pco]
   [integrant.core :as ig]))

;; -----------------------------------------------------------------------------
;; Authentication & Context Helpers
;; -----------------------------------------------------------------------------

(defn- authenticated-user-id
  "Extract the authenticated user-id from the Pathom env."
  [env]
  (or (get-in env [:auth :user-id])
      (get-in env [:auth :id])
      (get-in env [:request :identity :user-id])
      (get-in env [:request :identity :id])))

(defn- require-auth
  "Returns user-id if authenticated, throws otherwise."
  [env]
  (or (authenticated-user-id env)
      (throw (ex-info "Not authenticated" {:type :unauthenticated}))))

(defn- get-store [deps]
  (or (:user-store deps) deps))

(defn- require-org-admin
  "Returns user-id if the current user is an ADMIN of the given org.
   Throws if not authenticated or not an admin."
  [env deps org-id]
  (let [user-id (require-auth env)
        store (get-store deps)
        membership (org/get-membership store user-id org-id)]
    (when (or (nil? membership)
              (not= (:role membership) "ADMIN"))
      (throw (ex-info "Not authorized: admin role required" {:type :forbidden})))
    user-id))

(defn- require-org-member
  "Returns user-id if the current user is a member of the given org.
   Throws if not authenticated or not a member."
  [env deps org-id]
  (let [user-id (require-auth env)
        store (get-store deps)]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    user-id))

(defn- get-viewer-context
  "Constructs viewer context map {:user-id ... :role ... :unit-id ...} for RBAC evaluation."
  [env store org-id]
  (let [user-id (authenticated-user-id env)
        membership (when (and user-id org-id) (org/get-membership store user-id org-id))
        raw-role (or (:role membership) "employee")
        role-kw (keyword (str/lower-case (str raw-role)))]
    {:user-id user-id
     :role role-kw
     :org-id org-id
     :unit-id (:unit-id membership)}))

;; -----------------------------------------------------------------------------
;; Organization & Membership Resolvers
;; -----------------------------------------------------------------------------

(pco/defresolver user-orgs-resolver
  "Resolve organizations for the current user."
  [env _params]
  {::pco/output [{:user/orgs [:org/id :org/name :org/role :org/status]}]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        orgs (org/find-orgs-for-user store user-id)]
    {:user/orgs
     (mapv (fn [o]
             {:org/id (:id o)
              :org/name (:name o)
              :org/role (:role o)
              :org/status (:status o)})
           orgs)}))

(pco/defresolver active-org-resolver
  "Resolve the current user's active org."
  [env _params]
  {::pco/output [{:user/active-org [:org/id :org/name :org/role]}]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        user-orgs (org/find-orgs-for-user store user-id)
        active-org-id (or (org/get-active-org store user-id)
                          (:id (first user-orgs)))]
    (if active-org-id
      (let [o (org/find-org-by-id store active-org-id)
            membership (org/get-membership store user-id active-org-id)]
        {:user/active-org
         {:org/id active-org-id
          :org/name (:name o)
          :org/role (:role membership)}})
      {:user/active-org nil})))

(pco/defresolver org-members-resolver
  "Resolve members of an org by org-id. Requires admin."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:org/members [:user/id :membership/role :membership/status :membership/joined-at]}]}
  (require-org-admin env deps (:org/id params))
  (let [store (get-store deps)
        members (org/list-members store (:org/id params))]
    {:org/members
     (mapv (fn [m]
             {:user/id (:user-id m)
              :membership/role (:role m)
              :membership/status (:status m)
              :membership/joined-at (:joined-at m)})
           members)}))

(pco/defresolver user-invitations-resolver
  "Resolve pending invitations for the current user by email."
  [env _params]
  {::pco/output [{:user/invitations [:invitation/id :invitation/org-id
                                     :invitation/org-name :invitation/role
                                     :invitation/status :invitation/expires-at]}]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        user-record (user/find-by-id store user-id)
        email (:email user-record)
        invitations (org/list-invitations-for-user store email)]
    {:user/invitations invitations}))

(pco/defresolver org-by-id-resolver
  "Resolve a single org by id. Requires membership."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [:org/id :org/name :org/owner-id :org/created-at]}
  (let [user-id (require-auth env)
        store (get-store deps)
        org-id (:org/id params)]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [o (org/find-org-by-id store org-id)]
      {:org/id org-id
       :org/name (:name o)
       :org/owner-id (:owner-user-id o)
       :org/created-at (:created-at o)})))

;; -----------------------------------------------------------------------------
;; Org Units & Hierarchy Resolvers
;; -----------------------------------------------------------------------------

(pco/defresolver org-chart-resolver
  "Resolve full organization chart hierarchy, department trees, and enriched units."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:org/chart [:org/id
                              :org/hierarchy
                              {:org/units [:unit/id :unit/name :unit/division-id
                                           :unit/dept-id :unit/parent-id :unit/budget
                                           :unit/filled :unit/open :unit/pending
                                           :unit/actors :unit/children]}]}]}
  (let [user-id (require-auth env)
        store (get-store deps)
        org-id (:org/id params)]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [units (org/list-org-units store org-id)
          ;; Hierarchy maps parent-id -> child-ids. Roots (nil parent-id) are
          ;; derived by consumers from :unit/parent-id; a nil map key is not
          ;; Transit-safe, so it must never be materialized here.
          hierarchy (reduce (fn [acc u]
                              (if-let [p (:parent-id u)]
                                (update acc p (fnil conj []) (:unit-id u))
                                acc))
                            {}
                            units)]
      {:org/chart
       {:org/id org-id
        :org/hierarchy hierarchy
        :org/units (mapv (fn [u]
                           {:unit/id (:unit-id u)
                            :unit/name (:name u)
                            :unit/division-id (:division-id u)
                            :unit/dept-id (:dept-id u)
                            :unit/parent-id (:parent-id u)
                            :unit/budget (:budget u 0)
                            :unit/filled (:filled u 0)
                            :unit/open (:open u 0)
                            :unit/pending (:pending u 0)
                            :unit/actors (:actors u {})
                            :unit/children (:children u [])})
                         units)}})))

(pco/defresolver workforce-chart-resolver
  "Resolve workforce organization chart hierarchy, employees, and ABAC-filtered headcounts.
   Supports optional depth-limit parameter (default 2)."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:org/workforce-chart [:org/id
                                       :workforce/list
                                       :workforce-hierarchy
                                       :headcounts/list
                                       :headcounts-by-manager
                                       :org/chart-settings
                                       :total-workforce-count
                                       :total-headcount-count]}]}
  (let [user-id (require-auth env)
        store (get-store deps)
        org-id (:org/id params)
        depth-limit (get params :depth-limit 2)]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [viewer (get-viewer-context env store org-id)
          abac-policy (or (get-in env [:auth :abac-policy])
                          (get-in env [:request :abac-policy])
                          (get-in env [:abac-policy])
                          nil)
          result (org/get-org-workforce-chart store org-id viewer abac-policy {:depth-limit depth-limit})]
      {:org/workforce-chart result})))

(pco/defresolver workforce-branch-resolver
  "Resolve on-demand direct reports branch under a specific manager node."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id :manager/id]
   ::pco/output [{:manager/branch [:org/id
                                   :manager-id
                                   :workforce/list
                                   :workforce-hierarchy
                                   :headcounts/list
                                   :headcounts-by-manager]}]}
  (let [user-id (require-auth env)
        store (get-store deps)
        org-id (or (:org/id params) (:org-id params))
        manager-id (or (:manager/id params) (:manager-id params))]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [viewer (get-viewer-context env store org-id)
          abac-policy (or (get-in env [:auth :abac-policy])
                          (get-in env [:request :abac-policy])
                          (get-in env [:abac-policy])
                          nil)
          branch (org/get-org-workforce-branch store org-id manager-id viewer abac-policy)]
      {:manager/branch branch})))

(pco/defresolver workforce-search-resolver
  "Search workforce employees on the backend, returning matches with ancestor paths."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:workforce/search-results [:person/id
                                            :person/name
                                            :person/title
                                            :person/email
                                            :person/department-name
                                            :person/avatar-url
                                            :person/ancestor-path]}]}
  (let [user-id (require-auth env)
        store (get-store deps)
        org-id (:org/id params)
        p (try (pco/params env) (catch Exception _ {}))
        term (or (:term p)
                 (:search/term p)
                 (get-in env [:request :params :term])
                 (:search/term env)
                 (:term env)
                 (:term params)
                 (:search/term params))]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [viewer (get-viewer-context env store org-id)
          results (org/search-org-workforce store org-id term 15 viewer)]
      {:workforce/search-results results})))

(pco/defresolver dept-dashboard-resolver
  "Resolve department analytics dashboard: budget, filled, open, pending, avg SLA."
  [{:keys [deps] :as env} params]
  {::pco/input [:unit/id]
   ::pco/output [{:dept/dashboard [:unit/id :unit/budget :unit/filled :unit/open
                                   :unit/pending :unit/avg-sla-ms :unit/actors]}]}
  (let [user-id (require-auth env)
        store (get-store deps)
        unit-id (:unit/id params)
        unit (org/get-org-unit store unit-id)]
    (require-org-member env deps (:org-id unit))
    (let [stats (or (org/get-unit-headcount-stats store unit-id)
                    {:budget 0 :filled 0 :open 0 :pending 0})
          sla-list (org/get-approval-sla-latencies store unit-id)
          avg-sla (if (seq sla-list) (quot (reduce + sla-list) (count sla-list)) 0)
          actors (org/get-unit-actors store unit-id)]
      {:dept/dashboard
       {:unit/id unit-id
        :unit/budget (:budget stats 0)
        :unit/filled (:filled stats 0)
        :unit/open (:open stats 0)
        :unit/pending (:pending stats 0)
        :unit/avg-sla-ms avg-sla
        :unit/actors actors}})))

;; -----------------------------------------------------------------------------
;; Headcount Requisitions & Timeline Resolvers
;; -----------------------------------------------------------------------------

(pco/defresolver user-pending-approvals-resolver
  "Resolve all headcount requests awaiting approval by current user."
  [env _params]
  {::pco/output [{:user/pending-approvals [:headcount/id :headcount/title :headcount/unit-id
                                           :headcount/job-level :headcount/status :headcount/current-step]}]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        req-ids (org/get-user-pending-approvals store user-id)
        reqs (->> req-ids
                  (mapv (fn [rid]
                          (when-let [req (org/get-headcount-request store rid)]
                            {:headcount/id rid
                             :headcount/title (:title req)
                             :headcount/unit-id (:unit-id req)
                             :headcount/job-level (:job-level req)
                             :headcount/status (:status req)
                             :headcount/current-step (:current-step req)})))
                  (filterv some?))]
    {:user/pending-approvals reqs}))

(pco/defresolver headcount-request-resolver
  "Resolve single headcount requisition with RBAC visibility and field masking."
  [{:keys [deps] :as env} params]
  {::pco/input [:headcount/id]
   ::pco/output [:headcount/id :headcount/title :headcount/org-id :headcount/unit-id
                 :headcount/division-id :headcount/dept-id :headcount/location
                 :headcount/job-level :headcount/employee-type :headcount/requester-id
                 :headcount/justification :headcount/job-description :headcount/salary-band
                 :headcount/bonus-target :headcount/status :headcount/current-step
                 :headcount/current-approver-id :headcount/chain-snapshot :headcount/approved-by
                 :headcount/created-at]}
  (let [_ (require-auth env)
        store (get-store deps)
        req-id (:headcount/id params)
        raw-req (org/get-headcount-request store req-id)]
    (when raw-req
      (let [org-id (:org-id raw-req)
            viewer (get-viewer-context env store org-id)
            hierarchy (org/get-org-hierarchy store)
            role-perms (org/get-role-permissions store org-id)
            masked-req (rbac/eval-headcount-visibility viewer raw-req hierarchy role-perms)]
        (when masked-req
          {:headcount/id (:request-id masked-req)
           :headcount/title (:title masked-req)
           :headcount/org-id (:org-id masked-req)
           :headcount/unit-id (:unit-id masked-req)
           :headcount/division-id (:division-id masked-req)
           :headcount/dept-id (:dept-id masked-req)
           :headcount/location (:location masked-req)
           :headcount/job-level (:job-level masked-req)
           :headcount/employee-type (:employee-type masked-req)
           :headcount/requester-id (:requester-id masked-req)
           :headcount/justification (:justification masked-req)
           :headcount/job-description (:job-description masked-req)
           :headcount/salary-band (:salary-band masked-req)
           :headcount/bonus-target (:bonus-target masked-req)
           :headcount/status (:status masked-req)
           :headcount/current-step (:current-step masked-req)
           :headcount/current-approver-id (:current-approver-id masked-req)
           :headcount/chain-snapshot (:chain-snapshot masked-req)
           :headcount/approved-by (:approved-by masked-req)
           :headcount/created-at (:created-at masked-req)})))))

(pco/defresolver headcount-timeline-resolver
  "Resolve audit event timeline for a headcount requisition."
  [{:keys [deps] :as env} params]
  {::pco/input [:headcount/id]
   ::pco/output [{:headcount/timeline [:event :actor :timestamp :step :reason :field :new-value]}]}
  (let [store (get-store deps)
        req-id (:headcount/id params)
        req (org/get-headcount-request store req-id)
        timeline (org/get-request-timeline store req-id)]
    (require-org-member env deps (:org-id req))
    {:headcount/timeline timeline}))

(pco/defresolver org-approval-rules-resolver
  "Resolve custom approval routing rules for an organization."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:org/approval-rules [:rule-id :priority :name :conditions :chain]}]}
  (let [store (get-store deps)
        org-id (:org/id params)]
    (require-org-member env deps org-id)
    (let [rules (org/get-approval-rules store org-id)]
      {:org/approval-rules rules})))

(pco/defresolver org-role-permissions-resolver
  "Resolve role permission policies for an organization."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [:org/role-permissions]}
  (let [store (get-store deps)
        org-id (:org/id params)]
    (require-org-member env deps org-id)
    (let [perms (org/get-role-permissions store org-id)]
      {:org/role-permissions perms})))

;; -----------------------------------------------------------------------------
;; Capability Advertisement Resolver (:headcount/available-actions)
;; -----------------------------------------------------------------------------

(defn- compute-available-actions
  "Calculates what actions the current viewer can execute on the target headcount requisition."
  [viewer req]
  (let [viewer-id (:user-id viewer)
        status (:status req)
        requester-id (:requester-id req)
        current-approver-id (:current-approver-id req)
        is-requester? (= viewer-id requester-id)
        is-admin? (= (:role viewer) :admin)
        is-approver? (or (= viewer-id current-approver-id) is-admin?)]
    (cond-> []
      ;; Draft state: submit, edit, cancel
      (= status :draft)
      (into (cond-> (when (or is-requester? is-admin?) [:headcount/edit-field])
            (or is-requester? is-admin?) (conj :headcount/submit :headcount/cancel)))

      ;; In-approval state: approve/reject if approver, edit/cancel if requester/admin
      (= status :in-approval)
      (into (cond-> (when (or is-requester? is-admin?) [:headcount/edit-field])
            is-approver? (conj :headcount/approve :headcount/reject)
            (or is-requester? is-admin?) (conj :headcount/cancel)))

      ;; Approved state: transition to hire, edit, cancel
      (= status :approved)
      (into (cond-> (when (or is-requester? is-admin?) [:headcount/edit-field])
            (or is-admin? (= (:role viewer) :recruiter) (= (:role viewer) :hr)) (conj :headcount/transition-hire)
            is-admin? (conj :headcount/cancel))))))

(pco/defresolver headcount-available-actions-resolver
  "Capability advertisement: dynamically resolves permissible actions on a headcount requisition."
  [{:keys [deps] :as env} params]
  {::pco/input [:headcount/id]
   ::pco/output [:headcount/available-actions]}
  (let [_ (require-auth env)
        store (get-store deps)
        req-id (:headcount/id params)
        raw-req (org/get-headcount-request store req-id)]
    (if raw-req
      (let [viewer (get-viewer-context env store (:org-id raw-req))
            actions (compute-available-actions viewer raw-req)]
        {:headcount/available-actions actions})
      {:headcount/available-actions []})))

;; -----------------------------------------------------------------------------
;; Organization & Member Mutations
;; -----------------------------------------------------------------------------

(pco/defmutation create-org-mutation
  "Create a new organization. The current user becomes ADMIN."
  [env {:org/keys [name]}]
  {::pco/op-name 'org/create
   ::pco/params [:org/name]
   ::pco/output [:org/id :org/name :org/role :org/errors]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        [ok result] (org/create-org! store {:name name :owner-user-id user-id})]
    (if ok
      {:org/id (:id result)
       :org/name (:name result)
       :org/role "ADMIN"}
      {:org/errors (:errors result)})))

(pco/defmutation invite-to-org-mutation
  "Invite a user to join an org by email. Admin only."
  [env {:org/keys [id] :invitation/keys [email role]}]
  {::pco/op-name 'org/invite
   ::pco/params [:org/id :invitation/email :invitation/role]
   ::pco/output [:invitation/id :invitation/errors]}
  (require-org-admin env (:deps env) id)
  (let [store (get-store (:deps env))
        invited-by (require-auth env)
        [ok result] (org/invite-to-org! store {:org-id id :email email :role role :invited-by invited-by})]
    (if ok
      {:invitation/id (:invitation-id result)}
      {:invitation/errors (:errors result)})))

(pco/defmutation join-org-mutation
  "Accept an invitation and join an org."
  [env {:invitation/keys [id]}]
  {::pco/op-name 'org/join
   ::pco/params [:invitation/id]
   ::pco/output [:org/id :invitation/errors]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        [ok result] (org/join-org! store {:user-id user-id :invitation-id id})]
    (if ok
      {:org/id (:org-id result)}
      {:invitation/errors (:errors result)})))

(pco/defmutation switch-org-mutation
  "Switch the current user's active org."
  [env {:org/keys [id]}]
  {::pco/op-name 'org/switch
   ::pco/params [:org/id]
   ::pco/output [:org/id]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))]
    (when (nil? (org/get-membership store user-id id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (org/switch-org! store user-id id)
    {:org/id id}))

(pco/defmutation update-member-role-mutation
  "Update a member's role in an org. Admin only."
  [env params]
  {::pco/op-name 'org/update-member
   ::pco/params [:org/id :user/id :membership/role]
   ::pco/output [:membership/role]}
  (let [org-id (:org/id params)
        target-user-id (:user/id params)
        role (:membership/role params)]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))]
      (org/update-member-role! store org-id target-user-id role)
      {:membership/role role})))

(pco/defmutation remove-member-mutation
  "Remove a member from an org. Admin only."
  [env params]
  {::pco/op-name 'org/remove-member
   ::pco/params [:org/id :user/id]
   ::pco/output [:success]}
  (let [org-id (:org/id params)
        target-user-id (:user/id params)]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))]
      (org/remove-member! store org-id target-user-id)
      {:success true})))

;; -----------------------------------------------------------------------------
;; Org Unit & Policy Mutations
;; -----------------------------------------------------------------------------

(pco/defmutation create-org-unit-mutation
  "Create a new division or department org unit."
  [env params]
  {::pco/op-name 'unit/create
   ::pco/params [:unit/id :unit/org-id :unit/name :unit/parent-id :unit/budget
                 :unit/division-id :unit/dept-id]
   ::pco/output [:unit/id :unit/name :error]}
  (let [org-id (or (:unit/org-id params) (:org/id params))]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/create-org-unit! store {:unit-id (:unit/id params)
                                                   :org-id org-id
                                                   :name (:unit/name params)
                                                   :parent-id (:unit/parent-id params)
                                                   :budget (:unit/budget params 0)
                                                   :division-id (:unit/division-id params)
                                                   :dept-id (:unit/dept-id params)})]
      (if ok
        {:unit/id (:unit-id result) :unit/name (:name result)}
        {:error (errors/make-error :bad_request "Failed to create unit" result)}))))

(pco/defmutation reparent-org-unit-mutation
  "Re-parent an org unit to a new parent division/department."
  [env params]
  {::pco/op-name 'unit/reparent
   ::pco/params [:unit/org-id :unit/id :unit/new-parent-id]
   ::pco/output [:unit/id :unit/parent-id :error]}
  (let [org-id (or (:unit/org-id params) (:org/id params))]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/reparent-org-unit! store {:org-id org-id
                                                     :unit-id (:unit/id params)
                                                     :new-parent-id (:unit/new-parent-id params)})]
      (if ok
        {:unit/id (:unit-id result) :unit/parent-id (:parent-id result)}
        {:error (errors/make-error :bad_request "Failed to reparent unit" result)}))))

(pco/defmutation set-unit-budget-mutation
  "Update headcount budget allocation for an org unit."
  [env params]
  {::pco/op-name 'unit/set-budget
   ::pco/params [:unit/org-id :unit/id :unit/budget]
   ::pco/output [:unit/id :unit/budget :error]}
  (let [org-id (or (:unit/org-id params) (:org/id params))]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/set-org-unit-budget! store {:org-id org-id
                                                       :unit-id (:unit/id params)
                                                       :budget (:unit/budget params)})]
      (if ok
        {:unit/id (:unit-id result) :unit/budget (:budget result)}
        {:error (errors/make-error :bad_request "Failed to set budget" result)}))))

(pco/defmutation assign-actor-mutation
  "Assign a user to a scoped role for a specific org unit."
  [env params]
  {::pco/op-name 'org/assign-actor
   ::pco/params [:org/id :unit/id :user/id :role]
   ::pco/output [:unit/id :user/id :role :error]}
  (let [org-id (:org/id params)]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/assign-org-actor! store {:org-id org-id
                                                    :unit-id (:unit/id params)
                                                    :user-id (:user/id params)
                                                    :role (:role params)})]
      (if ok
        {:unit/id (:unit-id result) :user/id (:user-id result) :role (:role result)}
        {:error (errors/make-error :bad_request "Failed to assign actor" result)}))))

(pco/defmutation remove-actor-mutation
  "Remove a scoped role assignment from a specific org unit."
  [env params]
  {::pco/op-name 'org/remove-actor
   ::pco/params [:org/id :unit/id :user/id :role]
   ::pco/output [:unit/id :role :error]}
  (let [org-id (:org/id params)]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/remove-org-actor! store {:org-id org-id
                                                    :unit-id (:unit/id params)
                                                    :user-id (:user/id params)
                                                    :role (:role params)})]
      (if ok
        {:unit/id (:unit-id result) :role (:role result)}
        {:error (errors/make-error :bad_request "Failed to remove actor" result)}))))

(pco/defmutation set-approval-rules-mutation
  "Set custom approval routing rules for an organization."
  [env params]
  {::pco/op-name 'policy/set-approval-rules
   ::pco/params [:org/id :rules]
   ::pco/output [:org/id :count :error]}
  (let [org-id (:org/id params)]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/set-approval-rules! store org-id (:rules params))]
      (if ok
        {:org/id org-id :count (:count result)}
        {:error (errors/make-error :bad_request "Failed to set approval rules" result)}))))

(pco/defmutation set-role-permissions-mutation
  "Set granular role permission policies for an organization."
  [env params]
  {::pco/op-name 'policy/set-role-permissions
   ::pco/params [:org/id :role :permissions]
   ::pco/output [:org/id :role :permissions :error]}
  (let [org-id (:org/id params)]
    (require-org-admin env (:deps env) org-id)
    (let [store (get-store (:deps env))
          [ok result] (org/set-role-permissions! store org-id (:role params) (:permissions params))]
      (if ok
        {:org/id org-id :role (:role result) :permissions (:permissions result)}
        {:error (errors/make-error :bad_request "Failed to set permissions" result)}))))

;; -----------------------------------------------------------------------------
;; Headcount Requisition Mutations
;; -----------------------------------------------------------------------------

(pco/defmutation create-headcount-mutation
  "Create and submit a new headcount requisition for approval."
  [env params]
  {::pco/op-name 'headcount/create
   ::pco/params [:headcount/org-id :headcount/unit-id :headcount/title :headcount/job-level
                 :headcount/employee-type :headcount/salary-band :headcount/bonus-target
                 :headcount/justification :headcount/job-description :headcount/chain-snapshot
                 :headcount/idempotency-key]
   ::pco/output [:headcount/id :headcount/status :headcount/current-step :error]}
  (let [user-id (require-auth env)
         store (get-store (:deps env))
         org-id (:headcount/org-id params)
         _ (require-org-member env (:deps env) org-id)
         facts {:job-level (:headcount/job-level params "L3")
                :dept-id (:headcount/dept-id params)
                :unit-id (:headcount/unit-id params)
                :employee-type (:headcount/employee-type params :full-time)
                :location (:headcount/location params "remote")}
         chain (or (:headcount/chain-snapshot params)
                   (let [rules (org/get-approval-rules store org-id)
                         matching-rule (re/find-routing-rule rules facts)]
                    (or (:chain matching-rule)
                        [{:step 1 :role :hiring-manager}
                         {:step 2 :role :dept-head}])))
        input {:org-id org-id
               :unit-id (:headcount/unit-id params)
               :division-id (:headcount/division-id params)
               :dept-id (:headcount/dept-id params)
               :location (:headcount/location params "remote")
               :job-level (:headcount/job-level params "L3")
               :employee-type (:headcount/employee-type params :full-time)
               :requester-id user-id
               :title (:headcount/title params)
               :justification (:headcount/justification params)
               :job-description (:headcount/job-description params)
               :salary-band (:headcount/salary-band params)
               :bonus-target (:headcount/bonus-target params)
               :chain-snapshot chain
               :idempotency-key (or (:headcount/idempotency-key params)
                                    (get-in env [:request :headers "idempotency-key"]))}
        [ok result] (org/create-headcount-request! store input)]
    (if ok
      {:headcount/id (:request-id result)
       :headcount/status (:status result)
       :headcount/current-step (:current-step result)}
      {:error (errors/make-error :bad_request "Failed to create headcount request" result)})))

(pco/defmutation approve-headcount-mutation
  "Approve the current step in a headcount requisition's approval chain."
  [env params]
  {::pco/op-name 'headcount/approve-step
   ::pco/params [:headcount/org-id :headcount/request-id :headcount/idempotency-key]
   ::pco/output [:headcount/request-id :headcount/result :error]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        input {:org-id (:headcount/org-id params)
               :request-id (:headcount/request-id params)
               :approver-user-id user-id
               :idempotency-key (or (:headcount/idempotency-key params)
                                    (get-in env [:request :headers "idempotency-key"]))}
        [ok result] (org/approve-headcount-step! store input)]
    (if ok
      {:headcount/request-id (:request-id result)
       :headcount/result (:result result)}
      {:error (errors/make-error :bad_request "Failed to approve step" result)})))

(pco/defmutation reject-headcount-mutation
  "Reject a headcount requisition."
  [env params]
  {::pco/op-name 'headcount/reject
   ::pco/params [:headcount/org-id :headcount/request-id :headcount/reason :headcount/idempotency-key]
   ::pco/output [:headcount/request-id :headcount/status :error]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        input {:org-id (:headcount/org-id params)
               :request-id (:headcount/request-id params)
               :rejecter-user-id user-id
               :reason (:headcount/reason params)
               :idempotency-key (or (:headcount/idempotency-key params)
                                    (get-in env [:request :headers "idempotency-key"]))}
        [ok result] (org/reject-headcount-request! store input)]
    (if ok
      {:headcount/request-id (:request-id result)
       :headcount/status (:status result)}
      {:error (errors/make-error :bad_request "Failed to reject request" result)})))

(pco/defmutation edit-headcount-field-mutation
  "Edit a field on a headcount requisition. Resets approval chain to :draft if currently :in-approval."
  [env params]
  {::pco/op-name 'headcount/edit-field
   ::pco/params [:headcount/org-id :headcount/request-id :headcount/field-name
                 :headcount/new-value :headcount/idempotency-key]
   ::pco/output [:headcount/request-id :headcount/field-name :headcount/new-value :error]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        org-id (:headcount/org-id params)
        req (org/get-headcount-request store (:headcount/request-id params))
        membership (org/get-membership store user-id org-id)]
    (if (nil? req)
      {:error (errors/make-error :not_found "Headcount request not found" {:request-id (:headcount/request-id params)})}
      (if (nil? membership)
        {:error (errors/make-error :unauthorized "Not a member of this org" {})}
        (let [editor-role (keyword (str/lower-case (str (:role membership))))]
          (if (not (or (= user-id (:requester-id req)) (= editor-role :admin)))
            {:error (errors/make-error :unauthorized "Only the requester or an org admin may edit this requisition" {})}
            (let [input {:org-id org-id
                         :request-id (:headcount/request-id params)
                         :editor-user-id user-id
                         :field-name (:headcount/field-name params)
                         :new-value (:headcount/new-value params)
                         :idempotency-key (or (:headcount/idempotency-key params)
                                              (get-in env [:request :headers "idempotency-key"]))}
                  [ok result] (org/edit-headcount-field! store input)]
              (if ok
                {:headcount/request-id (:request-id result)
                 :headcount/field-name (:field-name result)
                 :headcount/new-value (:new-value result)}
                {:error (errors/make-error :bad_request "Failed to edit field" result)}))))))))

(pco/defmutation transition-hire-mutation
  "Transition an approved headcount requisition to a filled hire."
  [env params]
  {::pco/op-name 'headcount/transition-hire
   ::pco/params [:headcount/org-id :headcount/request-id :headcount/hired-user-id
                 :headcount/role :headcount/idempotency-key]
   ::pco/output [:headcount/request-id :headcount/hired-user-id :headcount/status :error]}
  (let [_ (require-auth env)
        store (get-store (:deps env))
        input {:org-id (:headcount/org-id params)
               :request-id (:headcount/request-id params)
               :hired-user-id (:headcount/hired-user-id params)
               :role (:headcount/role params "MEMBER")
               :idempotency-key (or (:headcount/idempotency-key params)
                                    (get-in env [:request :headers "idempotency-key"]))}
        [ok result] (org/transition-headcount-to-hire! store input)]
    (if ok
      {:headcount/request-id (:request-id result)
       :headcount/hired-user-id (:hired-user-id result)
       :headcount/status (:status result)}
      {:error (errors/make-error :bad_request "Failed to transition hire" result)})))

(pco/defmutation update-chart-settings-mutation
  "Updates org chart configuration settings (such as root node or co-equal leadership)."
  [env params]
  {::pco/op-name 'org/update-chart-settings
   ::pco/params [:org/id :chart-settings]
   ::pco/output [:org/id :org/chart-settings :error]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        org-id (or (:org/id params) (:org-id params))
        chart-settings (or (:chart-settings params) (:org/chart-settings params))]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [saved (org/update-org-chart-settings! store org-id chart-settings)]
      {:org/id org-id
       :org/chart-settings saved})))

(pco/defmutation fetch-workforce-branch-mutation
  "Fetch on-demand direct reports for a manager node."
  [env params]
  {::pco/op-name 'org/fetch-workforce-branch
   ::pco/params [:org/id :manager/id]
   ::pco/output [:org/id :manager-id :workforce/list :workforce-hierarchy :headcounts/list :headcounts-by-manager]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        org-id (or (:org/id params) (:org-id params))
        manager-id (or (:manager/id params) (:manager-id params))]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [viewer (get-viewer-context env store org-id)
          abac-policy (or (get-in env [:auth :abac-policy])
                          (get-in env [:request :abac-policy])
                          (get-in env [:abac-policy])
                          nil)]
      (org/get-org-workforce-branch store org-id manager-id viewer abac-policy))))

(pco/defmutation search-workforce-mutation
  "Search workforce employees on the backend, returning matches with ancestor paths."
  [env params]
  {::pco/op-name 'org/search-workforce
   ::pco/params [:org/id :term]
   ::pco/output [:org/id :results]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        org-id (or (:org/id params) (:org-id params))
        term (or (:term params) (:search/term params))]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [viewer (get-viewer-context env store org-id)
          results (org/search-org-workforce store org-id term 15 viewer)]
      {:org/id org-id
       :results results})))

(pco/defmutation logout-mutation
  "Revoke the current user's session token on Rama."
  [env _params]
  {::pco/op-name 'auth/logout
   ::pco/output [:auth/logged-out?]}
  (let [jti (or (get-in env [:auth :current-user :jti])
                (get-in env [:request :identity :jti])
                (get-in env [:auth :jti]))
        system (:deps env)]
    (when (and jti system)
      (try
        ((requiring-resolve 'com.ozimos.omni-auth.revocation.interface/revoke!)
         system
         jti
         (+ (System/currentTimeMillis) (* 900 1000)))
        (catch Exception _ nil)))
    {:auth/logged-out? true}))

;; -----------------------------------------------------------------------------
;; Full Resolvers Index & Integrant Method
;; -----------------------------------------------------------------------------

(def resolvers
  [;; Query Resolvers
   user-orgs-resolver
   active-org-resolver
   org-members-resolver
   user-invitations-resolver
   org-by-id-resolver
   org-chart-resolver
   dept-dashboard-resolver
   user-pending-approvals-resolver
   headcount-request-resolver
   headcount-timeline-resolver
   org-approval-rules-resolver
   org-role-permissions-resolver
   headcount-available-actions-resolver
   workforce-chart-resolver
   workforce-branch-resolver
   workforce-search-resolver

   ;; Mutations
   create-org-mutation
   invite-to-org-mutation
   join-org-mutation
   switch-org-mutation
   update-member-role-mutation
   remove-member-mutation
   create-org-unit-mutation
   reparent-org-unit-mutation
   set-unit-budget-mutation
   assign-actor-mutation
   remove-actor-mutation
   set-approval-rules-mutation
   set-role-permissions-mutation
   create-headcount-mutation
   approve-headcount-mutation
   reject-headcount-mutation
   edit-headcount-field-mutation
   transition-hire-mutation
   update-chart-settings-mutation
   fetch-workforce-branch-mutation
   search-workforce-mutation
   logout-mutation])

(defmethod ig/init-key :workforce/org-resolvers [_ _]
  resolvers)

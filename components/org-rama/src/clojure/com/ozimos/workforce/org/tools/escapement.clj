(ns com.ozimos.workforce.org.tools.escapement
  "Custom Escapement tool declarations for workforce organizational operations."
  (:require
   [com.ozimos.workforce.org.core :as org]
   [com.ozimos.workforce.org.errors :as errors]
   [com.ozimos.workforce.org.rbac :as rbac]
   [com.ozimos.workforce.org.rule-engine :as re]))

;; -----------------------------------------------------------------------------
;; Tool Definitions Registry
;; -----------------------------------------------------------------------------

(def tool-definitions
  [{:name "workforce_get_org_chart"
    :description "Retrieve the full organizational hierarchy tree (divisions and departments) for an organization."
    :parameters {:type "object"
                 :properties {:org-id {:type "string" :description "The ID of the organization."}}
                 :required ["org-id"]}
    :handler (fn [deps _ctx {:keys [org-id]}]
               (let [hierarchy (org/get-org-hierarchy deps)]
                 {:ok true :org-id org-id :hierarchy hierarchy}))}

   {:name "workforce_get_dept_dashboard"
    :description "Retrieve department headcount analytics: budget, filled seats, open positions, pending requisitions, and average approval SLA."
    :parameters {:type "object"
                 :properties {:unit-id {:type "string" :description "The department or division unit ID."}}
                 :required ["unit-id"]}
    :handler (fn [deps _ctx {:keys [unit-id]}]
               (let [stats (or (org/get-unit-headcount-stats deps unit-id)
                               {:budget 0 :filled 0 :open 0 :pending 0})
                     sla-list (org/get-approval-sla-latencies deps unit-id)
                     avg-sla (if (seq sla-list) (quot (reduce + sla-list) (count sla-list)) 0)
                     actors (org/get-unit-actors deps unit-id)]
                 {:ok true
                  :unit-id unit-id
                  :budget (:budget stats 0)
                  :filled (:filled stats 0)
                  :open (:open stats 0)
                  :pending (:pending stats 0)
                  :avg-sla-ms avg-sla
                  :actors actors}))}

   {:name "workforce_get_pending_approvals"
    :description "List all headcount requisitions currently waiting for approval by the authenticated user."
    :parameters {:type "object"
                 :properties {:user-id {:type "integer" :description "User ID of the approver."}}
                 :required ["user-id"]}
    :handler (fn [deps ctx {:keys [user-id]}]
               (let [effective-user-id (or user-id (:user-id ctx))
                     req-ids (org/get-user-pending-approvals deps effective-user-id)
                     reqs (->> req-ids
                               (mapv (fn [rid]
                                       (when-let [req (org/get-headcount-request deps rid)]
                                         {:request-id rid
                                          :title (:title req)
                                          :unit-id (:unit-id req)
                                          :job-level (:job-level req)
                                          :status (:status req)
                                          :current-step (:current-step req)})))
                               (filterv some?))]
                 {:ok true :pending-approvals reqs}))}

   {:name "workforce_get_headcount_request"
    :description "Fetch details for a specific headcount requisition, including status and available actions."
    :parameters {:type "object"
                 :properties {:request-id {:type "string" :description "Unique headcount requisition ID."}}
                 :required ["request-id"]}
    :handler (fn [deps ctx {:keys [request-id]}]
               (if-let [raw-req (org/get-headcount-request deps request-id)]
                 (let [viewer {:user-id (:user-id ctx)
                               :role (keyword (or (:role ctx) "employee"))
                               :unit-id (:unit-id ctx)}
                       org-id (:org-id raw-req)
                       hierarchy (org/get-org-hierarchy deps)
                       role-perms (org/get-role-permissions deps org-id)
                       masked-req (rbac/eval-headcount-visibility viewer raw-req hierarchy role-perms)]
                   (if masked-req
                     {:ok true :headcount-request masked-req}
                     {:ok false :error (errors/make-error :unauthorized "Not authorized to view this request")}))
                 {:ok false :error (errors/make-error :not_found "Headcount request not found")}))}

   {:name "workforce_create_headcount"
    :description "Create and submit a new headcount requisition for routing and approval."
    :parameters {:type "object"
                 :properties {:org-id {:type "string" :description "Organization ID"}
                              :unit-id {:type "string" :description "Department unit ID"}
                              :title {:type "string" :description "Job title for the new position"}
                              :job-level {:type "string" :description "Level (e.g. L3, L4, L5, L6, VP)"}
                              :salary-band {:type "string" :description "Target compensation range"}
                              :bonus-target {:type "string" :description "Target bonus percentage"}
                              :justification {:type "string" :description "Business justification"}
                              :idempotency-key {:type "string" :description "Unique idempotency key"}}
                 :required ["org-id" "unit-id" "title"]}
    :handler (fn [deps ctx params]
               (let [org-id (:org-id params)
                     chain (or (:chain-snapshot params)
                               (let [rules (org/get-approval-rules deps org-id)
                                     matching-rule (re/find-routing-rule rules params)]
                                 (or (:chain matching-rule)
                                     [{:step 1 :role :hiring-manager}
                                      {:step 2 :role :dept-head}])))
                     input (assoc params
                                  :requester-id (or (:requester-id params) (:user-id ctx))
                                  :chain-snapshot chain
                                  :idempotency-key (or (:idempotency-key params) (str (random-uuid))))
                     [ok res] (org/create-headcount-request! deps input)]
                 (if ok
                   {:ok true :headcount res}
                   {:ok false :error res})))}

   {:name "workforce_approve_headcount_step"
    :description "Approve the current step in the approval chain of a headcount requisition."
    :parameters {:type "object"
                 :properties {:org-id {:type "string" :description "Organization ID"}
                              :request-id {:type "string" :description "Headcount requisition ID"}
                              :idempotency-key {:type "string" :description "Unique idempotency key"}}
                 :required ["org-id" "request-id"]}
    :handler (fn [deps ctx params]
               (let [input (assoc params
                                  :approver-user-id (or (:approver-user-id params) (:user-id ctx))
                                  :idempotency-key (or (:idempotency-key params) (str (random-uuid))))
                     [ok res] (org/approve-headcount-step! deps input)]
                 (if ok
                   {:ok true :approval res}
                   {:ok false :error res})))}

   {:name "workforce_reject_headcount"
    :description "Reject a headcount requisition with a reason."
    :parameters {:type "object"
                 :properties {:org-id {:type "string" :description "Organization ID"}
                              :request-id {:type "string" :description "Headcount requisition ID"}
                              :reason {:type "string" :description "Reason for rejection"}
                              :idempotency-key {:type "string" :description "Unique idempotency key"}}
                 :required ["org-id" "request-id" "reason"]}
    :handler (fn [deps ctx params]
               (let [input (assoc params
                                  :rejecter-user-id (or (:rejecter-user-id params) (:user-id ctx))
                                  :idempotency-key (or (:idempotency-key params) (str (random-uuid))))
                     [ok res] (org/reject-headcount-request! deps input)]
                 (if ok
                   {:ok true :rejection res}
                   {:ok false :error res})))}

   {:name "workforce_edit_headcount_field"
    :description "Edit a field on a headcount requisition. Sensitive edits trigger re-approval reset."
    :parameters {:type "object"
                 :properties {:org-id {:type "string" :description "Organization ID"}
                              :request-id {:type "string" :description "Headcount requisition ID"}
                              :field-name {:type "string" :description "Field name keyword to update"}
                              :new-value {:description "New value to assign to the field"}
                              :idempotency-key {:type "string" :description "Unique idempotency key"}}
                 :required ["org-id" "request-id" "field-name" "new-value"]}
    :handler (fn [deps ctx params]
               (let [input (assoc params
                                  :editor-user-id (or (:editor-user-id params) (:user-id ctx))
                                  :field-name (keyword (:field-name params))
                                  :idempotency-key (or (:idempotency-key params) (str (random-uuid))))
                     [ok res] (org/edit-headcount-field! deps input)]
                 (if ok
                   {:ok true :edit res}
                   {:ok false :error res})))}

   {:name "workforce_transition_hire"
    :description "Transition an approved headcount requisition to a filled hire."
    :parameters {:type "object"
                 :properties {:org-id {:type "string" :description "Organization ID"}
                              :request-id {:type "string" :description "Headcount requisition ID"}
                              :hired-user-id {:type "integer" :description "User ID of candidate being hired"}
                              :role {:type "string" :description "Role to assign (e.g. MEMBER, ADMIN)"}
                              :idempotency-key {:type "string" :description "Unique idempotency key"}}
                 :required ["org-id" "request-id" "hired-user-id"]}
    :handler (fn [deps _ctx params]
               (let [input (assoc params
                                  :role (or (:role params) "MEMBER")
                                  :idempotency-key (or (:idempotency-key params) (str (random-uuid))))
                     [ok res] (org/transition-headcount-to-hire! deps input)]
                 (if ok
                   {:ok true :hire res}
                   {:ok false :error res})))}])

(def tools-by-name
  (into {} (map (fn [t] [(:name t) t])) tool-definitions))

(defn call-tool
  "Executes a registered tool by name with the given dependencies, context, and parameters."
  [deps ctx tool-name params]
  (if-let [tool (get tools-by-name tool-name)]
    ((:handler tool) deps ctx params)
    {:ok false :error (errors/make-error :not_found (str "Tool not found: " tool-name))}))

(defn register-tools
  "Registration entry point called by Escapement on startup."
  []
  {:name "workforce-tools"
   :version "0.1.0"
   :tools tool-definitions})

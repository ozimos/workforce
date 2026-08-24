(ns com.ozimos.workforce.org.simulation.agents
  "Autonomous simulation agents powered by Behavior Trees for multi-tier organizational lifecycle testing."
  (:require
   [com.ozimos.workforce.org.simulation.behavior-tree :as bt]
   [com.ozimos.workforce.org.tools.escapement :as esc]))

;; -----------------------------------------------------------------------------
;; 1. Hiring Manager Agent
;; -----------------------------------------------------------------------------

(defn make-hiring-manager-agent
  "Constructs a Behavior Tree agent for a Hiring Manager."
  []
  (bt/sequence*
    "HiringManagerLifecycle"

    ;; Check unit dashboard has open budget
    (bt/action "CheckUnitCapacity"
      (fn [ctx]
        (let [res (esc/call-tool (:deps ctx) ctx "workforce_get_dept_dashboard" {:unit-id (:unit-id ctx)})]
          (if (and (:ok res) (pos? (get res :open 0)))
            {:status :success :context (assoc ctx :dept-stats res)}
            {:status :failure :context (assoc ctx :error "No open capacity")}))))

    ;; Submit headcount requisition
    (bt/action "SubmitHeadcountRequisition"
      (fn [ctx]
        (let [title (or (:req-title ctx) "Senior Software Engineer")
              level (or (:req-level ctx) "L4")
              salary (or (:req-salary ctx) "$150,000 - $180,000")
              res (esc/call-tool (:deps ctx) ctx "workforce_create_headcount"
                                 {:org-id (:org-id ctx)
                                  :unit-id (:unit-id ctx)
                                  :title title
                                  :job-level level
                                  :salary-band salary
                                  :bonus-target "15%"
                                  :justification "Team expansion for core services"
                                  :chain-snapshot (:chain-snapshot ctx)
                                  :idempotency-key (:idempotency-key ctx)})]
          (if (:ok res)
            (let [req-id (get-in res [:headcount :request-id])]
              {:status :success :context (assoc ctx :created-request-id req-id :headcount-res res)})
            {:status :failure :context (assoc ctx :error (:error res))}))))))

;; -----------------------------------------------------------------------------
;; 2. Approver Agent (Director / VP)
;; -----------------------------------------------------------------------------

(defn make-approver-agent
  "Constructs a Behavior Tree agent for an Approver (Director or VP)."
  [role-name]
  (bt/sequence*
    (str "ApproverLifecycle-" role-name)

    ;; Fetch pending approvals
    (bt/action "FetchPendingApprovals"
      (fn [ctx]
        (let [res (esc/call-tool (:deps ctx) ctx "workforce_get_pending_approvals" {:user-id (:user-id ctx)})]
          (if (:ok res)
            {:status :success :context (assoc ctx :pending-reqs (:pending-approvals res))}
            {:status :failure :context (assoc ctx :error "Failed to fetch pending approvals")}))))

    ;; Approve target request
    (bt/action "ApproveTargetRequest"
      (fn [ctx]
        (let [target-id (or (:target-request-id ctx)
                            (:created-request-id ctx)
                            (-> ctx :pending-reqs first :request-id))]
          (if target-id
            (let [res (esc/call-tool (:deps ctx) ctx "workforce_approve_headcount_step"
                                     {:org-id (:org-id ctx)
                                      :request-id target-id
                                      :approver-user-id (:user-id ctx)})]
              (if (:ok res)
                {:status :success :context (assoc ctx :approval-res res :last-approved-id target-id)}
                {:status :failure :context (assoc ctx :error (:error res))}))
            {:status :failure :context (assoc ctx :error "No pending request found to approve")}))))))

;; -----------------------------------------------------------------------------
;; 3. Candidate Recruiter / Transition-to-Hire Agent
;; -----------------------------------------------------------------------------

(defn make-recruiter-hire-agent
  "Constructs a Behavior Tree agent that transitions an approved requisition to a filled hire."
  []
  (bt/sequence*
    "RecruiterHireLifecycle"

    (bt/action "TransitionToHire"
      (fn [ctx]
        (let [target-id (or (:target-request-id ctx) (:created-request-id ctx))
              cand-id (or (:candidate-user-id ctx) 9999)
              res (esc/call-tool (:deps ctx) ctx "workforce_transition_hire"
                                 {:org-id (:org-id ctx)
                                  :request-id target-id
                                  :hired-user-id cand-id
                                  :role "ENGINEER"})]
          (if (:ok res)
            {:status :success :context (assoc ctx :hire-res res)}
            {:status :failure :context (assoc ctx :error (:error res))}))))))

;; -----------------------------------------------------------------------------
;; 4. Chaos & Auditor Agent
;; -----------------------------------------------------------------------------

(defn make-chaos-agent
  "Constructs a Behavior Tree agent that tests fault injection, sensitive field edit reset, and idempotency."
  []
  (bt/sequence*
    "ChaosAuditorLifecycle"

    ;; Sensitive Field Edit on In-Approval Request (Salary Bump)
    (bt/action "InjectSensitiveFieldEdit"
      (fn [ctx]
        (let [target-id (or (:target-request-id ctx) (:created-request-id ctx))
              res (esc/call-tool (:deps ctx) ctx "workforce_edit_headcount_field"
                                 {:org-id (:org-id ctx)
                                  :request-id target-id
                                  :field-name "salary-band"
                                  :new-value "$240,000 - $280,000"})]
          (if (:ok res)
            {:status :success :context (assoc ctx :chaos-edit-res res)}
            {:status :failure :context (assoc ctx :error (:error res))}))))

    ;; Verify Re-Approval Reset to Draft
    (bt/action "VerifyReapprovalReset"
      (fn [ctx]
        (let [target-id (or (:target-request-id ctx) (:created-request-id ctx))
              res (esc/call-tool (:deps ctx) ctx "workforce_get_headcount_request" {:request-id target-id})]
          (if (and (:ok res) (= :draft (get-in res [:headcount-request :status])))
            {:status :success :context (assoc ctx :verified-reset true)}
            {:status :failure :context (assoc ctx :error "Expected status to be reset to :draft")}))))))

(ns com.ozimos.workforce.org.simulation.runner
  "Multi-Agent Simulation Scenarios runner for end-to-end organizational lifecycle verification."
  (:require
   [com.ozimos.workforce.org.core :as org]
   [com.ozimos.workforce.org.simulation.agents :as agents]
   [com.ozimos.workforce.org.simulation.behavior-tree :as bt]
   [com.rpl.rama.ops :as ops]))

;; -----------------------------------------------------------------------------
;; Scenario 1: Complete End-to-End Headcount Approval & Hire Lifecycle
;; -----------------------------------------------------------------------------

(defn run-scenario-1-happy-path
  "Scenario 1: Manager creates requisition -> Director approves Step 1 -> VP approves Step 2 -> Recruiter fills hire."
  [deps {:keys [org-id unit-id manager-id director-id vp-id candidate-id]}]
  (let [hm-agent (agents/make-hiring-manager-agent)
        dir-agent (agents/make-approver-agent "Director")
        vp-agent (agents/make-approver-agent "VP")
        hire-agent (agents/make-recruiter-hire-agent)

        chain [{:step 1 :role :hiring-manager}
               {:step 2 :role :dept-head}]

        ;; Step 1: Manager submits requisition
        ctx0 {:deps deps :org-id org-id :unit-id unit-id :user-id manager-id
              :req-title "Principal Systems Architect" :req-level "L5"
              :chain-snapshot chain}
        res1 (bt/tick hm-agent ctx0)
        _ (assert (= :success (:status res1)) (str "Manager failed: " (:error (:context res1))))
        req-id (get-in res1 [:context :created-request-id])

        ;; Step 2: Director approves Step 1
        ctx1 {:deps deps :org-id org-id :unit-id unit-id :user-id director-id :target-request-id req-id}
        res2 (bt/tick dir-agent ctx1)
        _ (assert (= :success (:status res2)) (str "Director failed: " (:error (:context res2))))

        ;; Step 3: VP approves Step 2
        ctx2 {:deps deps :org-id org-id :unit-id unit-id :user-id vp-id :target-request-id req-id}
        res3 (bt/tick vp-agent ctx2)
        _ (assert (= :success (:status res3)) (str "VP failed: " (:error (:context res3))))

        ;; Step 4: Recruiter transitions to filled hire
        ctx3 {:deps deps :org-id org-id :unit-id unit-id :user-id manager-id :target-request-id req-id :candidate-user-id candidate-id}
        res4 (bt/tick hire-agent ctx3)
        _ (assert (= :success (:status res4)) (str "Recruiter failed: " (:error (:context res4))))]

    {:success true
     :scenario :happy-path
     :request-id req-id
     :final-status (get-in res4 [:context :hire-res :hire :status])}))

;; -----------------------------------------------------------------------------
;; Scenario 2: Dynamic Custom Approval Routing Rule Chain
;; -----------------------------------------------------------------------------

(defn run-scenario-2-dynamic-routing
  "Scenario 2: Custom approval routing rules match job-level L6 and dynamically route to Director."
  [deps {:keys [org-id unit-id manager-id director-id]}]
  (let [_ (org/set-approval-rules! deps org-id
            [{:rule-id "r-exec"
              :priority 100
              :name "Executive L6 Rule"
              :conditions [:= :job-level "L6"]
              :chain [{:step 1 :role :dept-head}]}])

        hm-agent (agents/make-hiring-manager-agent)
        ctx0 {:deps deps :org-id org-id :unit-id unit-id :user-id manager-id
              :req-title "VP of Engineering" :req-level "L6"}
        res1 (bt/tick hm-agent ctx0)
        _ (assert (= :success (:status res1)) (str "Manager failed: " (:error (:context res1))))
        req-id (get-in res1 [:context :created-request-id])

        dir-agent (agents/make-approver-agent "Director")
        ctx1 {:deps deps :org-id org-id :unit-id unit-id :user-id director-id :target-request-id req-id}
        res2 (bt/tick dir-agent ctx1)
        _ (assert (= :success (:status res2)) (str "Director failed: " (:error (:context res2))))]

    {:success true
     :scenario :dynamic-routing
     :request-id req-id
     :approval-result (get-in res2 [:context :approval-res :approval :result])}))

;; -----------------------------------------------------------------------------
;; Scenario 3: Sensitive Field Edit Triggers Re-Approval Reset
;; -----------------------------------------------------------------------------

(defn run-scenario-3-field-edit-reset
  "Scenario 3: Chaos agent injects salary change on in-approval request, triggering re-approval reset to :draft."
  [deps {:keys [org-id unit-id manager-id]}]
  (let [hm-agent (agents/make-hiring-manager-agent)
        chaos-agent (agents/make-chaos-agent)

        ctx0 {:deps deps :org-id org-id :unit-id unit-id :user-id manager-id
              :req-title "Staff Security Researcher" :req-level "L5"
              :chain-snapshot [{:step 1 :role :hiring-manager}]}
        res1 (bt/tick hm-agent ctx0)
        _ (assert (= :success (:status res1)) (str "Manager failed: " (:error (:context res1))))
        req-id (get-in res1 [:context :created-request-id])

        ;; Chaos agent injects salary bump
        ctx1 {:deps deps :org-id org-id :unit-id unit-id :user-id manager-id :target-request-id req-id}
        res2 (bt/tick chaos-agent ctx1)
        _ (assert (= :success (:status res2)) (str "Chaos agent failed: " (:error (:context res2))))]

    {:success true
     :scenario :sensitive-field-edit-reset
     :request-id req-id
     :verified-reset (get-in res2 [:context :verified-reset])}))

;; -----------------------------------------------------------------------------
;; Scenario 4: Idempotency Dedup Guard Verification
;; -----------------------------------------------------------------------------

(defn run-scenario-4-idempotency
  "Scenario 4: Duplicate submissions with identical idempotency key are safely deduplicated."
  [deps {:keys [org-id unit-id manager-id]}]
  (let [idem-key (str "idem-sim-" (ops/random-uuid7))
        input {:org-id org-id
               :unit-id unit-id
               :title "Cloud Architect"
               :job-level "L4"
               :requester-id manager-id
               :chain-snapshot [{:step 1 :role :hiring-manager}]
               :idempotency-key idem-key}

        [ok1 res1] (org/create-headcount-request! deps input)
        [ok2 res2] (org/create-headcount-request! deps input)]

    (assert (and ok1 ok2) "Both idempotent calls should succeed")
    (assert (= (:request-id res1) (:request-id res2)) "Duplicate submission should return identical request-id")

    {:success true
     :scenario :idempotency
     :request-id (:request-id res1)}))

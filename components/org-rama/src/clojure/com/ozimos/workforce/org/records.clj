(ns com.ozimos.workforce.org.records)

(defrecord OrgCreate [uuid name owner-user-id created-at])
(defrecord OrgInvite [invitation-id org-id email role invited-by created-at expires-at])
(defrecord OrgJoin [user-id invitation-id joined-at])
(defrecord OrgSwitch [user-id org-id])
(defrecord OrgMemberUpdate [org-id target-user-id new-role])
(defrecord OrgMemberRemove [org-id target-user-id])
(defrecord InvitationAccept [invitation-id user-id joined-at])

;; Org Unit & Hierarchy Commands
(defrecord OrgUnitCreate [unit-id org-id division-id dept-id name parent-id budget created-at])
(defrecord OrgUnitUpdate [unit-id org-id name budget updated-at])
(defrecord OrgUnitReparent [unit-id org-id new-parent-id reparented-at])
(defrecord OrgUnitSetBudget [unit-id org-id budget updated-at])

;; Headcount Requisition Commands
(defrecord HeadcountCreate [request-id org-id unit-id division-id dept-id location job-level employee-type requester-id title justification job-description salary-band bonus-target status current-step chain-snapshot created-at idempotency-key])
(defrecord HeadcountApproveStep [request-id org-id approver-user-id approved-at idempotency-key])
(defrecord HeadcountReject [request-id org-id rejecter-user-id reason rejected-at idempotency-key])
(defrecord HeadcountTransitionHire [request-id org-id hired-user-id role transitioned-at idempotency-key])
(defrecord HeadcountFieldEdit [request-id org-id editor-user-id field-name new-value edited-at idempotency-key])

;; Scoped Actor Assignments
(defrecord OrgActorAssign [org-id unit-id user-id role assigned-at])
(defrecord OrgActorRemove [org-id unit-id user-id role removed-at])

;; Approval Rules & Role Permissions
(defrecord ApprovalRuleSet [org-id rules updated-at])
(defrecord RolePermissionSet [org-id role permissions updated-at])

;; =============================================================================
;; Phase 15: Currency Settings & Exchange Rates
;; =============================================================================
(defrecord OrgCurrencySet
  [org-id base-currency updated-at])

(defrecord OrgFxRateSet
  [org-id from-currency to-currency rate updated-at])

;; Employee Types & Load Factors (Taxes, Burden, Multipliers)
(defrecord EmployeeTypeDefine
  [org-id type-id label annual-multiplier hours-per-week default-benefits? updated-at])

(defrecord LoadFactorRuleSet
  [org-id location-code job-category job-level multiplier effective-date])

;; Tenant-Defined Custom Attribute Definitions
(defrecord TenantAttributeDefine
  [org-id attribute-id target-entity label data-type cost-modifier? cost-cadence currency options required? default-value updated-at])

;; Employee (Identity Entity) Commands
(defrecord EmployeeHire
  [employee-id org-id user-id first-name last-name personal-email hire-date status
   employment-id unit-id job-title job-category job-level employee-type location
   base-salary currency bonus-target custom-attributes start-date created-at idempotency-key])

(defrecord EmployeeStatusUpdate
  [employee-id org-id status updated-at idempotency-key])

(defrecord EmployeeTerminate
  [employee-id org-id end-date termination-reason updated-at idempotency-key])

;; Employment (Temporal Placement / Position Entity) Commands
(defrecord EmploymentTransfer
  [employment-id employee-id org-id unit-id job-title job-category job-level employee-type location
   base-salary currency bonus-target custom-attributes effective-date previous-employment-id idempotency-key])

(defrecord EmploymentCompRevision
  [employment-id employee-id org-id base-salary currency bonus-target custom-attributes effective-date idempotency-key])


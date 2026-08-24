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

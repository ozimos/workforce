(ns com.ozimos.workforce.org.interface
  (:require
   [com.ozimos.workforce.org.core :as core]
   [com.ozimos.workforce.org.errors :as errors]
   [com.ozimos.workforce.org.extension]
   [com.ozimos.workforce.org.resolvers]
   [com.ozimos.workforce.org.tools.mcp :as mcp]))

(defn create-org!
  "Create a new organization. The creating user becomes the ADMIN.
   Returns [true org] on success, [false {:errors ...}] on failure.
   `input` is a map with :name and :owner-user-id."
  [deps input]
  (core/create-org! deps input))

(defn find-org-by-id
  "Look up an organization by id. Returns org map or nil."
  [deps org-id]
  (core/find-org-by-id deps org-id))

(defn find-orgs-for-user
  "List all organizations for a user with membership info.
   Returns vector of {:id :name :role :status :joined-at}."
  [deps user-id]
  (core/find-orgs-for-user deps user-id))

(defn invite-to-org!
  "Send an invitation to join an org. Returns [true {:invitation-id}] on success,
   [false {:errors ...}] on failure.
   `input` is a map with :org-id, :email, :role, :invited-by."
  [deps input]
  (core/invite-to-org! deps input))

(defn join-org!
  "Accept an invitation and join an org. Returns [true {:org-id}] on success,
   [false {:errors ...}] on failure.
   `input` is a map with :user-id, :invitation-id."
  [deps input]
  (core/join-org! deps input))

(defn switch-org!
  "Set the user's active org. Returns true on success."
  [deps user-id org-id]
  (core/switch-org! deps user-id org-id))

(defn get-active-org
  "Get the user's active org-id. Returns org-id or nil."
  [deps user-id]
  (core/get-active-org deps user-id))

(defn list-members
  "List all members of an org. Returns vector of {:user-id :role :status :joined-at}."
  [deps org-id]
  (core/list-members deps org-id))

(defn update-member-role!
  "Update a member's role in an org. Returns true on success."
  [deps org-id target-user-id new-role]
  (core/update-member-role! deps org-id target-user-id new-role))

(defn remove-member!
  "Remove a member from an org. Returns true on success."
  [deps org-id target-user-id]
  (core/remove-member! deps org-id target-user-id))

(defn list-invitations-for-user
  "List pending invitations for a user by email.
   Returns vector of {:invitation/id :org-id :org-name :role :expires-at}."
  [deps email]
  (core/list-invitations-for-user deps email))

(defn get-membership
  "Get a user's membership in a specific org.
   Returns {:role :status :joined-at} or nil."
  [deps user-id org-id]
  (core/get-membership deps user-id org-id))

;; --- Org Unit Management ---

(defn create-org-unit! [deps input]
  (core/create-org-unit! deps input))

(defn reparent-org-unit! [deps input]
  (core/reparent-org-unit! deps input))

(defn set-org-unit-budget! [deps input]
  (core/set-org-unit-budget! deps input))

(defn get-org-unit [deps unit-id]
  (core/get-org-unit deps unit-id))

(defn get-org-hierarchy
  ([deps]
   (core/get-org-hierarchy deps))
  ([deps parent-id]
   (core/get-org-hierarchy deps parent-id)))

(defn get-org-children [deps parent-id]
  (core/get-org-children deps parent-id))

(defn get-unit-headcount-stats [deps unit-id]
  (core/get-unit-headcount-stats deps unit-id))

;; --- Headcount Requisitions ---

(defn create-headcount-request! [deps input]
  (core/create-headcount-request! deps input))

(defn approve-headcount-step! [deps input]
  (core/approve-headcount-step! deps input))

(defn reject-headcount-request! [deps input]
  (core/reject-headcount-request! deps input))

(defn edit-headcount-field! [deps input]
  (core/edit-headcount-field! deps input))

(defn transition-headcount-to-hire! [deps input]
  (core/transition-headcount-to-hire! deps input))

(defn get-headcount-request [deps request-id]
  (core/get-headcount-request deps request-id))

(defn get-user-pending-approvals [deps user-id]
  (core/get-user-pending-approvals deps user-id))

(defn get-request-timeline [deps request-id]
  (core/get-request-timeline deps request-id))

;; --- Scoped Actors & Policies ---

(defn assign-org-actor! [deps input]
  (core/assign-org-actor! deps input))

(defn remove-org-actor! [deps input]
  (core/remove-org-actor! deps input))

(defn set-approval-rules! [deps org-id rules]
  (core/set-approval-rules! deps org-id rules))

(defn get-approval-rules [deps org-id]
  (core/get-approval-rules deps org-id))

(defn set-role-permissions! [deps org-id role permissions]
  (core/set-role-permissions! deps org-id role permissions))

(defn get-role-permissions [deps org-id]
  (core/get-role-permissions deps org-id))

(defn get-unit-actors [deps unit-id]
  (core/get-unit-actors deps unit-id))

(defn get-approval-sla-latencies [deps unit-id]
  (core/get-approval-sla-latencies deps unit-id))

(defn make-error
  ([error-code message]
   (errors/make-error error-code message nil))
  ([error-code message details]
   (errors/make-error error-code message details)))

(defn valid-error? [err]
  (errors/valid-error? err))

(defn handle-mcp-request [deps ctx request-body]
  (mcp/handle-mcp-request deps ctx request-body))

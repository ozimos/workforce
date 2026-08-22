(ns com.ozimos.workforce.org.interface
  (:require
   [com.ozimos.workforce.org.core :as core]))

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

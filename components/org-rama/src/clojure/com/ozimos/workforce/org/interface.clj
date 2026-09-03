(ns com.ozimos.workforce.org.interface
  (:require
   [com.ozimos.workforce.org.core :as core]
   [com.ozimos.workforce.org.csv :as csv]
   [com.ozimos.workforce.org.errors :as errors]
   [com.ozimos.workforce.org.extension]
   [com.ozimos.workforce.org.resolvers]
   [com.ozimos.workforce.org.seed :as seed]
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

(defn find-org-by-name
  "Look up an organization by name. Returns org map or nil."
  [deps name]
  (core/find-org-by-name deps name))

(defn get-org
  "Look up an organization by id or name."
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

(defn list-org-units
  "Returns all units belonging to an organization, enriched with budget, headcount stats, actors, and children."
  [deps org-id]
  (core/list-org-units deps org-id))

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

(defn get-org-workforce-chart
  ([deps org-id viewer-ctx abac-policy]
   (core/get-org-workforce-chart deps org-id viewer-ctx abac-policy)))

(defn get-org-chart-settings [deps org-id]
  (core/get-org-chart-settings deps org-id))

(defn update-org-chart-settings! [deps org-id settings]
  (core/update-org-chart-settings! deps org-id settings))

;; --- Seed Data Generation & Ingestion ---

(defn generate-seed-data []
  (seed/generate-seed-data))

(defn write-seed-nippy!
  ([] (seed/write-seed-nippy!))
  ([path] (seed/write-seed-nippy! path)))

(defn read-seed-nippy
  ([] (seed/read-seed-nippy))
  ([path] (seed/read-seed-nippy path)))

(defn load-seed-data! [deps dataset]
  (seed/load-seed-data! deps dataset))

(defn ensure-seeded!
  ([deps] (seed/ensure-seeded! deps))
  ([deps path] (seed/ensure-seeded! deps path)))

;; =============================================================================
;; Phase 15: Global Currency, Load Factors, Custom Attributes & Employees
;; =============================================================================

(defn set-org-currency!
  ([deps input] (core/set-org-currency! deps input))
  ([deps org-id base-currency] (core/set-org-currency! deps {:org-id org-id :base-currency base-currency})))

(defn get-org-currency-settings [deps org-id]
  (core/get-org-currency-settings deps org-id))

(defn set-fx-rate!
  ([deps input] (core/set-fx-rate! deps input))
  ([deps org-id from-currency to-currency rate] (core/set-fx-rate! deps {:org-id org-id :from-currency from-currency :to-currency to-currency :rate rate})))

(defn get-fx-rates [deps org-id]
  (core/get-fx-rates deps org-id))

(defn define-employee-type! [deps input]
  (core/define-employee-type! deps input))

(defn get-employee-types [deps org-id]
  (core/get-employee-types deps org-id))

(defn set-load-factor! [deps input]
  (core/set-load-factor! deps input))

(defn get-load-factors [deps org-id]
  (core/get-load-factors deps org-id))

(defn define-tenant-attribute! [deps input]
  (core/define-tenant-attribute! deps input))

(defn get-tenant-attributes
  ([deps org-id]
   (core/get-tenant-attributes deps org-id nil))
  ([deps org-id target-entity]
   (core/get-tenant-attributes deps org-id target-entity)))

(defn hire-employee! [deps input]
  (core/hire-employee! deps input))

(defn transfer-employment! [deps input]
  (core/transfer-employment! deps input))

(defn revise-employment-comp! [deps input]
  (core/revise-employment-comp! deps input))

(defn terminate-employee! [deps input]
  (core/terminate-employee! deps input))

(defn get-employee [deps employee-id]
  (core/get-employee deps employee-id))

(defn get-employment [deps employment-id]
  (core/get-employment deps employment-id))

(defn get-employee-employment-history [deps employee-id]
  (core/get-employee-employment-history deps employee-id))

(defn list-unit-employments [deps unit-id]
  (core/list-unit-employments deps unit-id))

(defn get-unit-cost-stats [deps unit-id]
  (core/get-unit-cost-stats deps unit-id))

(defn generate-csv-template
  "Generates CSV text template with headers and a sample row based on tenant configuration."
  [deps org-id]
  (csv/generate-csv-template deps org-id))

(defn validate-csv
  "Performs pre-flight validation on CSV string for a tenant. Returns a dry-run validation report."
  [deps org-id csv-str]
  (csv/validate-csv deps org-id csv-str))

(defn ingest-csv!
  "Validates and batch-ingests CSV workforce records into Rama depots."
  [deps org-id csv-str & [opts]]
  (csv/ingest-csv! deps org-id csv-str opts))

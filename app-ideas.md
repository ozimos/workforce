# App Ideas for best_auth Demo

Criteria for the demo app:
1. **In-app auth value** — operations must depend on app state that Auth0/external providers can't reach
2. **Multi-DB exercise** — workload spans KV, document, graph, time-series, OLTP, OLAP
3. **Buildable** — simple enough for a template, not a multi-month project
4. **Offline-fit (optional)** — local-first is a bonus, not a requirement

---

## Idea 1: Shared Shopping List

Create lists, add items, check items off, **share lists with other users by email**. Two collaborators see each other's changes in real-time.

### User flows

```
Auth phase (REST)
  Register → automatically gets default "My List" (atomic in topology)
  Login    → fetch lists shared with me

App phase (Pathom)
  POST create-list   → new list, you're owner
  POST add-item      → append `*item-activity-depot`, materialize into `$$items`
  POST toggle-item   → checked=true, checked-by=me
  POST share-list    → lookup user by email via `$$email->id`, add to `$$list-members`
  POST delete-list   → only owner; cascade clears items + removes from member PStates

Deactivate (atomic cascade)
  DELETE user → topology removes user from every `$$list-members`,
                transfers list ownership (or deletes if solo),
                clears their pending offline actions
```

### Why in-app auth is essential

| Operation | What auth knows (Auth0 alone) | What app knows (Rama) | Combined in-app |
|---|---|---|---|
| Share list by email | nothing | lookups user by `$$email->id` | Works |
| Toggle item | only user-id | checks `$$list-members` for list | Permission grated correctly |
| Delete list | only user-id | checks `$$lists.creator-id` | Auth0 can't enforce ownership |
| Deactivate user | removes auth account | orphaned lists, dangling permissions | Single depot event cascades across all PStates |
| "Shared lists I have" | nothing | queries `$$user-foreign-lists` | Auth0-only couldn't populate this |

### Rama data model

```
Depots (append-only event log):
  *list-depot        {:list-id :name :creator-id :created-at}
  *item-depot        {:item-id :list-id :name :qty :notes :actor-id :action :timestamp}
  *share-depot       {:list-id :member-id :added-by}
  *deactivate-depot  {:user-id}

PStates (materialized views):
  $$lists               {String {:name String :creator-id Long :created-at Long}}                    ← KV
  $$items               {String {:list-id String :name String :qty Int
                                  :checked Boolean :checked-by Long :notes String}}                    ← KV + Document
  $$list-members        {String (set-schema String {:subindex? true})}                                ← Graph
  $$user-lists          {Long (set-schema String {:subindex? true})}                                 ← Graph
  $$user-foreign-lists  {Long (set-schema String {:subindex? true})}                                 ← Graph
  $$item-activity        {String (set-schema {:subindex? true} {:limit 50})}                          ← Time-series (bounded per list)
  $$list-completion     {String {:total Long :checked Long}}                                          ← OLAP (live aggregates)
```

### Multi-DB domain coverage

| Domain | Where |
|---|---|
| KV | `$$lists`, `$$items` — direct lookup by ID |
| Document | `$$items[:notes]` — arbitrary text, `:qty` typed field — fixed-keys schema |
| Graph | `$$list-members`, `$$user-lists`, `$$user-foreign-lists` — bidirectional edge sets |
| Time-series | `$$item-activity` per-list bounded log; `*item-depot` append-only global stream |
| OLTP | Every mutation appends a single depot event → topology micro-batch |
| OLAP | `$$list-completion` updated on every toggle, queried in real-time |

### Offline-first fit

Excellent. Items are small, writes are mostly independent (one user checks one item), and conflicts are rare. Optimistic UI + a small replay queue (`datalevin` in CLJS) handles offline mode cleanly:
- Add items offline → queue → replay `*item-depot` appends when online
- Toggle items offline → last-write-wins on sync (correct for shopping lists — latest decision wins)
- Read from local store with periodic refresh from `$$list-completion`

### Build complexity

**Low-medium.** Core surface:
- 4 entities (user, list, item, share)
- 1 topology hook (`*deactivate-depot` cascade)
- ~6 Pathom resolvers/mutations
- 5-7 Fulcro screens (lists index, list detail, add item, share dialog, settings)

### Pathom resolvers

```clojure
(defresolver list-resolver       [env {:list/keys [id]}] ...)
(defresolver my-lists-resolver   [env _]                     ...)
(defresolver shared-lists-resolver [env _]                  ...)
(defresolver list-completion-resolver [env {:list/keys [id]}] ...)
(defresolver list-activity-resolver [env {:list/keys [id]}]  ...)

(defmutation create-list!        [env {:list/keys [name]}]   ...)
(defmutation add-item!           [env {:list/keys [id] :item/keys [name qty notes]}] ...)
(defmutation toggle-item!        [env {:item/keys [id]}]     ...)
(defmutation share-list!         [env {:list/keys [id] :user/keys [email]}] ...)
(defmutation delete-list!       [env {:list/keys [id]}]    ...)
```

---

## Idea 2: Collaborative Approval Workflow

Users submit requests (vacation, expense, document review). Approvers review and approve/reject. Org hierarchy routes pending requests.

### User flows

```
Auth phase (REST)
  Register → default role "employee" (atomic in `*registration-depot`)
  Admin invites new user → creates user with custom role

App phase (Pathom)
  POST submit-request   → new request, routes to manager via `$$org-hierarchy`
  POST approve-request  → adds supervisor signature, marks approved (or moves to next approver)
  POST reject-request   → records reason, notifies requestor
  GET my-pending         → queries `$$user-pending-approvals`
  GET my-submissions     → queries `$$user-requests`

Org changes (cascading auth + app state)
  POST reassign-manager  → new manager inherits all my pending approvals,
                           my old manager has no pending approvals for me
  DELETE deactivate-user → their pending approvals auto-rejected,
                           their submitted requests auto-withdrawn,
                           their manager's load visibly drops
```

### Why in-app auth is essential

The app **IS** auth-aware authorization logic. Auth0 can validate a user exists; it cannot enforce:
- "Only your manager can approve your request" (requires org hierarchy — pure app data)
- "When your manager changes, reassign pending approvals" (auth event + app mutation)
- "VP threshold requires two-level approval" (rule reads user's `mgmt-level` claim checked per request)
- "Deactivate user → reject all their pending approvals" (cross-PState atomic update)

Without in-app auth, every one of these requires an external webhook + Postgres update — eventually consistent and racy.

### Rama data model

```
Depots:
  *request-depot        {:request-id :requestor-id :type-id :details :submitted-at}
  *approval-depot       {:request-id :approver-id :decision :comment :timestamp}
  *hierarchy-depot      {:user-id :manager-id :role-level :manager-id-effective-from}
  *deactivate-depot     {:user-id}

PStates:
  $$requests           {String {:requestor-id Long :type String
                               :status String :submitted-at Long
                               :current-approver-id Long
                               :details (fixed-keys-schema {:amount String :reason String :from Long :to Long})}}   ← KV + Document
  $$user-requests      {Long (set-schema String {:subindex? true})}                                                    ← Graph
  $$user-pending       {Long (set-schema String {:subindex? true})}                                                    ← Graph
  $$hierarchy          {Long {:manager-id Long :role-level String :effective-from Long}}                               ← Graph (tree edge)
  $$reports-subordinate  {Long (set-schema Long {:subindex? true})}                                                    ← Graph (inverse edge)
  $$request-approvals  {String (set-schema {:subindex? true} {:limit 100})}                                            ← Time-series (audit trail)
  $$approval-stats     {String {:pending Long :approved Long :rejected Long :avg-approval-ms Long}}                   ← OLAP
```

### Multi-DB domain coverage

| Domain | Where |
|---|---|
| KV | `$$requests` — single request by ID |
| Document | `$$requests[:details]` fixed-keys schema with `:amount`, `:reason`, `:from`, `:to` |
| Graph | `$$hierarchy` (manager→reports), `$$user-pending` (approver→pending requests), `$$user-requests` (requestor→requests) |
| Time-series | `$$request-approvals` — bounded audit log per request |
| OLTP | Submit/approve/reject each append one depot event |
| OLAP | `$$approval-stats` — live aggregates, queryable in real-time |

### Offline-first fit

Excellent for approvers who are frequently on the move (approve on plane, sync later). Low conflict rate (one approver per request). Easy replay queue.

### Build complexity

**Medium-high.** Core surface:
- 5 entities (user, request, approval, hierarchy, role)
- 3-4 topology hooks (reassignment cascade, deactivation cascade, role-change propagation, expiry-auto-reject)
- ~10 Pathom resolvers/mutations
- 7-10 Fulcro screens (org chart, submit form, pending queue, history, stats dashboard, settings)

Extra: requirement for org hierarchy UI makes the Fulcro routing slightly more complex.

### Pathom resolvers

```clojure
(defresolver request-resolver     [env {:request/keys [id]}] ...)
(defresolver my-pending-resolver  [env _]                    ...)
(defresolver my-submissions-resolver [env _]                ...)
(defresolver hierarchy-resolver   [env {:user/keys [id]}]   ...)
(defresolver approval-stats-resolver [env {:request/keys [id]}] ...)
(defresolver team-metrics-resolver [env _]                  ...)

(defmutation submit-request!    [env {:req/keys [type-id details]}] ...)
(defmutation approve-request!   [env {:request/keys [id] :approval/keys [comment]}] ...)
(defmutation reject-request!    [env {:request/keys [id] :approval/keys [reason]}] ...)
(defmutation reassign-manager!  [env {:user/keys [id] :manager/keys [id]}] ...)
(defmutation deactivate-user!   [env {:user/keys [id]}] ...)
```

---

## Comparison

| Aspect | Shared Shopping List | Approval Workflow |
|---|---|---|
| In-app auth value | High (permission on lists, sharing, account cascade) | Very high (org hierarchy, role-level gating, app IS authz) |
| Multi-DB exercise | 6/6 domains | 6/6 domains |
| Build complexity | Low-medium (~6 Pathom ops, ~7 screens) | Medium-high (~10 Pathom ops, ~10 screens, hierarchy UI) |
| Offline-first | Excellent (last-write-wins works) | Excellent (approver-offline is the canonical use case) |
| Demo clarity | High — "share a list with your spouse" is instantly understood | Medium — workflow is relatable but takes setup to demo |
| "Why Rama" story | Per-list permissions cascade, share-by-email, atomic deactivate, real-time updates | Hierarchy cascade on reassignment, role-gated approvals, audit trail + live metrics |
| Stretch goals | Public read-only lists (gift registries), item categories, budgets | SLA alerts, multi-level approval chains, calendar integration |

---

## Stretch goals beyond the base MVP

### Public gift registry extension (Shared Shopping List)

**Question: is shared shopping list similar to a public gift wish list for weddings/birthdays?**

Yes — and adding "public mode" is a natural extension that amplifies Rama's value:

```
Visibility levels per list:
  :private    — only owner + explicit members
  :shared    — link with token, anyone with token can view
  :public    — discoverable in `/explore` feed
```

New depots/PStates:
- `*list-visibility-depot` — change visibility (append-only log)
- `$$public-lists` — `(set-schema String {:subindex? true})` (discoverable list IDs)
- `$$list-access-tokens` — `{String {:list-id String :created-at Long :expires-at Long}}` for shared-link access (Rama-native TTL expiry)

In-app auth justification deepens:
- Public lists: anyone can view, but only authenticated users can claim/like items
- Gift registries: registry owner sees who claimed what (private), claimers don't see each other (privacy)
- "Item claimed by guest X" — claim event blocks other guests from claiming same item (CAS-style PState update)
- Audit: registry owner can see "Bob's wedding" had 12 visitors and 8 items claimed (OLAP)

This extension exercises **public/anonymous access** to some resolvers (no JWT required for read-only views) and **authenticated mutations** (claim item) — a pattern rarely supported cleanly by Auth0 without a custom anonymous-to-auth upgrade flow.

### Approval Workflow extensions
- SLA breach auto-escalation (topology timer)
- Multi-hop approval routing (already in the schema)
- External submitter (non-authenticated form submission with token approval)

---

## Idea 3: Collaborative Family Tree

Family members research ancestry, add/edit person records, link relationships, fork the tree when there's a disagreement, and vote on which version is most accurate.

### User flows

```
Auth phase (REST)
  Register → auto-joins default tree "My Family" (created atomically in topology)
  Tree invites: member shares join-link or invites by email

App phase (Pathom — base tree, no forking)
  GET  family-tree        → returns subtree rooted at a person with depth N
  GET  person             → person record + relationships + notes
  POST add-person         → new person linked to parents/spouse
  POST edit-person        → update dates, name, bio, living status
  POST add-relationship   → parent-child, spouse, step-parent
  POST invite-member      → add authenticated user to `$$tree-members`

Forking (post-MVP extension)
  GET  fork-list          → all forks of current tree
  POST create-fork        → snapshots tree state at current depot offset,
                              creates independent PState namespace
  GET  fork-diff          → changes between two forks (depot replay comparison)
  POST cast-vote          → vote for a fork as "most accurate"
  POST attempt-merge      → replay events from fork A against fork B's PState,
                              reports conflicts

Privacy gating (all mutations)
  Living persons → hidden from non-members
  Deceased persons → visible to all authenticated users (or public, configurable)
  Relationship to living person → person is visible but contact info is hidden
```

### Why in-app auth is essential

| Operation | What Auth0 alone knows | What Rama app knows | Why in-app wins |
|---|---|---|---|
| View person | nothing | `$$person-visibility` — living/deceased, relation to viewer | Auth0 can't decide "hide living aunts from distant cousins" |
| Edit person | nothing | `$$person-edit-permissions` per subtree | Only descendant branch members can edit their ancestor's record |
| Invite member | nothing | `$$tree-members`, `$$proposed-members` (voting gate) | Community decides, not SSO admin panel |
| Fork tree | nothing | Entire tree PState copied at depot offset | Fork is an app concept; Auth0 has no analog |
| Cast accuracy vote | nothing | `$$fork-votes` — one per user per fork | Vote is tied to authenticated identity within the tree |

**"Deactivate user" cascade:**

```
User leaves tree → topology runs once:

(source> *leave-tree-depot :> {:keys [*user-id *tree-id]})
(local-select> (keypath *user-id) $$tree-members :> *tree-ids)
(local-select> (keypath *tree-id) $$user-permissions :> *perms)
(local-clear> (keypath *tree-id :members *user-id) $$tree-members)
(local-clear> (keypath *user-id *tree-id) $$user-trees)
;; Re-assign their person records: for each $person-id they owned,
;; transfer to nearest relative in the tree
(ops/explode *owned-persons :> *person-id)
(local-select> (keypath *person-id :nearest-relative) $$persons :> *relative-id)
(local-transform> [(keypath *person-id :owner-id) (termval *relative-id)] $$persons)
;; Remove their votes from forks
(local-clear> (keypath *fork-id *user-id) $$fork-votes)
```

Zero-copy, zero-delay, zero-race-conditions. Auth0 alone cannot reach into `$$tree-members` or `$$person-visibility`.

### Rama data model

#### Depots (event log)

| Depot | Record | Partitioning | Purpose |
|---|---|---|---|
| `*person-depot` | `AddPerson`, `EditPerson` | hash-by :tree-id | Person CRUD events |
| `*relationship-depot` | `AddRelationship`, `RemoveRelationship` | hash-by :tree-id | Link/unlink persons |
| `*invite-depot` | `InviteMember`, `RemoveMember` | hash-by :tree-id | Access changes |
| `*fork-depot` | `CreateFork`, `CastVote`, `AttemptMerge` | hash-by :tree-id | Fork governance |
| `*tree-depot` | `CreateTree` | hash-by :tree-id | Tree creation |

#### PStates (materialized views)

| PState | Schema | Domain | Purpose |
|---|---|---|---|
| `$$persons` | `{Long (fixed-keys-schema {:name String :birth-date Long :death-date (either Long nil) :bio String :living? Boolean :owner-id Long :nearest-relative Long})}` | KV + Document | Person records by ID |
| `$$person-parents` | `{Long (set-schema Long {:subindex? true})}` | Graph | Person → parents (up to 2) |
| `$$person-children` | `{Long (set-schema Long {:subindex? true})}` | Graph | Person → children (unbounded) |
| `$$person-spouses` | `{Long (set-schema Long {:subindex? true})}` | Graph | Person → spouses (unbounded) |
| `$$tree-members` | `{String (set-schema Long {:subindex? true})}` | Graph | Tree → member user-ids |
| `$$user-trees` | `{Long (set-schema String {:subindex? true})}` | Graph | User → tree-ids they belong to |
| `$$person-visibility` | `{Long (fixed-keys-schema {:privacy-tags (set-schema String {:subindex? true}) :hidden-from-role String})}` | KV | Per-person privacy rules |
| `$$tree-forks` | `{String (vector-schema {:fork-id String :created-by Long :created-at Long :depot-offset Long :status String :vote-count Int})}` | Document | List of forks per tree |
| `$$fork-votes` | `{String (set-schema Long {:subindex? true})}` | Graph | Fork-id → voter user-ids (one per user per fork) |
| `$$recent-edits` | `{String (set-schema String {:limit 100})}` | Time-series | Per-tree bounded edit log |
| `$$tree-stats` | `{String {:total-persons Long :living-persons Long :deceased-persons Long :generation-depth Int :fork-count Int :completeness Float}}` | OLAP | Real-time tree metrics |

### Multi-DB domain coverage

| Domain | Where in the genealogy app |
|---|---|
| **KV** | `$$persons` — direct lookup by person-id, `$$tree-forks`, `$$tree-stats` |
| **Document** | `$$persons` — `:bio` freeform text field inside `fixed-keys-schema`; `$$tree-forks` — vector of fork descriptors |
| **Graph** | `$$person-parents`, `$$person-children`, `$$person-spouses` — tree walk; `$$tree-members`, `$$user-trees` — membership graph; `$$person-visibility` — role-based access edges |
| **Time-series** | `*person-depot` — event stream (every edit ever); `$$recent-edits` bounded per-tree timeline; `*fork-depot` — vote stream |
| **OLTP** | Add person → one depot append → topology creates person + indexes in PState. Fork → snapshot at depot offset. Merge → event replay across partitions. |
| **OLAP** | `$$tree-stats` — real-time aggregates updated on every person add/edit, queried for tree dashboard. Fork vote counts. Ancestor completion percentage. |

### Offline-first fit

**The canonical offline-first use case.** Genealogy research happens at:
- Libraries (microfilm records, no signal)
- Cemeteries (transcribing headstones)
- Family gatherings (interviewing elderly relatives)
- Archives (restricted rooms, no wifi)

Writes are rare and thoughtful (editing a birth year happens once per person, not 1000x/sec). Optimistic UI on every mutation. Small local store (`datalevin` in CLJS). Queue replays depot events when back online.

Fork-merge maps naturally to offline branches:
1. User goes offline → automatically works on a local "fork"
2. User comes online → proposes merge to the main tree
3. If no conflict → merge accepted
4. If conflict → fork stays separate, community votes

### Build complexity

**Base tree (no forking): Medium** — comparable to shopping list

| Item | Effort | Notes |
|---|---|---|
| Person CRUD + relationship graph | 3 depots, ~6 PStates, 4 mutations, 3 resolvers | |
| Privacy gating (living vs deceased) | Visibility PState + Pathom permission resolver | |
| Tree member management | Invite/remove via email lookup (`$$email->id`) | |
| Fulcro: tree browser | Custom tree/accordion component | Most frontend effort |
| Fulcro: person editor | Form + relationship picker | |

**Forking extension: High** — adds 1-2 phases

| Item | Effort | Notes |
|---|---|---|
| Fork depot + PState snapshot | Topology copies `$$persons` entries at depot offset | |
| Fork diff resolver | Depot event replay comparison across two fork boundaries | Needs careful offset tracking |
| Vote PState + topology | Simple counter | |
| Merge attempt resolver | Replay events from fork A against fork B's PState | Conflict detection is the hard part |
| Fulcro: fork browser/voting UI | Tabbed views with live vote tallies | |

### Comparison to other ideas

| Aspect | Shopping List | Approval Workflow | **Genealogy** |
|---|---|---|---|
| In-app auth | ★★★★☆ | ★★★★★ | **★★★★★** |
| Multi-DB | 6/6 | 6/6 | **6/6** |
| Buildable (MVP) | Low-Med | Med | **Med** |
| Buildable (forking) | N/A | N/A | **High** |
| Offline fit | ★★★★★ | ★★★★★ | **★★★★★** |
| Demo clarity | High | Medium | **High** |
| "Why Rama" wow | Permission cascade | Hierarchy cascade | **Versioning via depot replay + graph traversal** |

### Strength as a Rama demo

Genealogy is the strongest available "why Rama" story:

1. **Graph traversal at scale** — "Show all descendants of John Smith born after 1850 who married a Johnson" is a graph walk that Rama partitions handle natively (each person is one partition hop). In SQL this is a recursive CTE with unpredictable performance.

2. **Versioning via depot replay** — A fork is not a copy of data; it's a **depot offset bookmark**. The original tree's depot continues appending events; the fork replays up to the fork point and then only accepts edits to its own fork depot. Merge = replay fork events against main tree's PState. No existing DB has this concept built-in.

3. **Privacy-by-graph-relationship** — "Can User X see Person Y?" resolved by walking the relationship graph in one `foreign-select-one` hop (are they connected within N degrees?). No join table, no materialized path, no recursive query.

4. **Atomic cascading operations** — "User leaves family" (deactivate) cascades across 5+ PStates in one topology. Single-writer, consistent, sub-millisecond.

---

## Idea 4: Hiring Approval + Org Chart

Employees view the org chart. Visibility of pay, bonus, and headcount data depends on **role, department, and location** (RBAC). Hiring managers and recruiters create headcount requests. Each org has a configurable linear approval chain. Once fully approved, a headcount can transition into an employee record.

### User flows

```
Auth phase (REST)
  Register → assigned to org unit with role (configured in config.edn or admin panel)
  Login → see org chart filtered by your visibility scope

App phase (Pathom)
  GET  org-chart          → returns org tree, each node decorated with visible data
  GET  dept-dashboard     → headcount budget, filled/open/pending counts, SLA stats
  GET  my-pending         → headcount requests awaiting your approval step
  GET  request-thread     → full request with timeline, comments, current step

  POST create-headcount   → new request, snapshots current approval chain, sets step=1
  POST approve-step       → advance to next step (or mark approved if last step)
  POST reject-request     → terminate with reason
  POST transition-hire    → convert approved headcount into employee record
  POST update-org         → restructure hierarchy (manager change, re-parent dept)
```

### Pay and bonus RBAC

The org chart resolver applies per-field visibility based on the viewer's properties:

```clojure
;; Per-org visibility policy (configurable in config.edn or admin UI)
$$comp-visibility-policies
{String (fixed-keys-schema
          {:by-role            (map-schema Keyword
                                (fixed-keys-schema
                                  {:salary     #{:none :band :exact :currency-converted}
                                   :bonus      #{:none :band :exact :percentage}
                                   :rsu        #{:none :band :exact}
                                   :headcount  #{:none :budget-only :filled-open-pending}}))
           :department-silo?   Boolean
           :location-silo?     Boolean
           :manager-override   #{:none :direct-reports :all-subtree}})}
```

Example outcomes for the same target employee:

| Viewer | Salary seen | Bonus seen | Headcount seen |
|---|---|---|---|
| Same dept peer | :band ($150k-$200k) | :none | :none |
| Same dept director | :exact ($180k) | :percentage (15%) | :filled-open-pending |
| Different dept director | :band | :none | :budget-only |
| Same dept + same location recruiter | :band | :none | :filled-open-pending |
| Direct manager | :exact (override) | :exact (override) | :budget-only |
| C-suite (cross-dept, cross-loc) | :exact | :exact | :filled-open-pending |

### Why in-app auth is essential

| Operation | What Auth0 alone knows | What Rama app knows | Why in-app wins |
|---|---|---|---|
| View comp of peer | nothing | `$$comp-visibility-policies` + `$$org-hierarchy` + viewer's role/dept/loc | Auth0 can't encode pay band visibility per user pair |
| Create headcount | nothing | `$$org-units` headcount budget vs current count + `$$user-roles` (are you a hiring manager?) | Budget check is app data |
| Approve step N | nothing | `$$headcount-request.current-step-role` + `$$user-roles` (do you hold that role?) | Chain step = role, not user-id; Auth0 can't evaluate role chains |
| Org restructure | nothing | Topology cascades across all in-flight requests, re-routing to new chain | Atomic, instant, zero-downtime |
| "Openings I'm an actor on" | nothing | `$$org-unit-actors` PState — you're tagged as recruiter/hiring-manager for org X | Actor scope is per-org, not per-tenant |

**"Org restructure mid-approval" — the killer demo:**

```
1. Director of Engineering has 3 headcount requests at "pending Director approval"
2. Reorg: Engineering moves under a new CTO
3. One depot append: {:type :reparent-org :org-id "eng" :new-parent-id "cto-office"}
4. Topology runs once:
   - Updates $$org-hierarchy
   - Iterates all active headcount requests where approval-chain mentions Director
   - Re-evaluates: CTO is now the director-level approver for Engineering
   - Those 3 requests appear in the CTO's pending queue
   - Director's pending queue empties

In a traditional stack: multi-step transaction with SELECT FOR UPDATE
+ application-level re-route logic + webhook to re-index Elasticsearch.
In Rama: one topology micro-batch. Atomic. Deterministic.
```

### Rama data model

#### Depots

| Depot | Record | Partitioning | Purpose |
|---|---|---|---|
| `*org-depot` | `CreateOrgUnit`, `UpdateOrgUnit`, `ReparentOrg`, `SetHeadcountBudget` | hash-by :org-id | Org hierarchy CRUD |
| `*headcount-depot` | `CreateRequest`, `ApproveStep`, `RejectRequest`, `TransitionToHire` | hash-by :org-id | Headcount request lifecycle |
| `*role-depot` | `AssignRole`, `RemoveRole`, `SetActorScope` | hash-by :user-id | User-org-role assignments |
| `*policy-depot` | `SetCompPolicy`, `SetApprovalChain` | hash-by :org-id | Policy updates |

#### PStates

| PState | Schema | Domain | Purpose |
|---|---|---|---|
| `$$org-units` | `{String (fixed-keys-schema {:name String :parent-id (either String nil) :headcount-budget Long :created-at Long})}` | KV + Document | Org unit metadata |
| `$$org-hierarchy` | `{String (set-schema String {:subindex? true})}` | Graph | Parent→children edges |
| `$$org-child-parent` | `{String String}` | Graph | Child→parent (inverse edge) |
| `$$headcount-requests` | `{String (fixed-keys-schema {:org-id String :requester-id Long :title String :justification String :job-description String :salary-band String :status Keyword :current-step Int :approved-by (vector-schema Long) :rejected-by Long :rejection-reason String :created-at Long})}` | KV + Document | Headcount request with full detail |
| `$$org-requests` | `{String (set-schema String {:subindex? true})}` | Graph | Org→active request-ids |
| `$$user-pending-approvals` | `{Long (set-schema String {:subindex? true})}` | Graph | User→pending request-ids |
| `$$org-headcount-stats` | `{String (fixed-keys-schema {:budget Long :filled Long :open Long :pending Long})}` | OLAP | Real-time headcount aggregates |
| `$$comp-visibility-policies` | `{String (fixed-keys-schema {:by-role (map-schema Keyword (fixed-keys-schema {:salary #{:none :band :exact :currency-converted} :bonus #{:none :band :exact :percentage} :rsu #{:none :band :exact} :headcount #{:none :budget-only :filled-open-pending}})) :department-silo? Boolean :location-silo? Boolean :manager-override #{:none :direct-reports :all-subtree}})}` | KV + Document | Per-org RBAC policies |
| `$$user-roles` | `{Long (set-schema String {:subindex? true})}` | Graph | User→role-IDs |
| `$$org-actors` | `{String (set-schema Long {:subindex? true})}` | Graph | Org→actor user-ids (recruiters, hiring managers) |
| `$$actor-orgs` | `{Long (set-schema String {:subindex? true})}` | Graph | User→orgs they're an actor for |
| `$$approval-chains` | `{String (vector-schema {:step Int :role Keyword :min-count Int})}` | Document | Per-org approval policy (linear chain) |
| `$$request-timeline` | `{String (set-schema String {:limit 200})}` | Time-series | Per-request bounded action log |
| `$$approval-sla` | `{String (set-schema Long {:limit 100})}` | OLAP | Per-org approval duration samples |

### Multi-DB domain coverage

| Domain | Where in the hiring approval app |
|---|---|
| **KV** | `$$org-units`, `$$headcount-requests`, `$$comp-visibility-policies`, `$$approval-chains` |
| **Document** | `$$headcount-requests[:justification :job-description]` — freeform text fields inside `fixed-keys-schema`; `$$approval-chains` — structured vector of step descriptors |
| **Graph** | `$$org-hierarchy` + `$$org-child-parent` — org tree traversal; `$$user-roles` — role assignment; `$$org-actors` + `$$actor-orgs` — bidirectional actor scoping; `$$user-pending-approvals` — approval routing |
| **Time-series** | `*headcount-depot` event stream; `$$request-timeline` bounded per-request; `$$approval-sla` latency samples |
| **OLTP** | Create request, approve step, reject, transition, re-parent org, update policy — each a single depot append |
| **OLAP** | `$$org-headcount-stats` live aggregates per org; `$$approval-sla` real-time percentile tracking |

### Offline-first fit

★★★☆☆ — HR approvals are usually done online. However, real-world uses exist:
- Executive approving from a plane (offline queue)
- Recruiting team at a career fair (mobile, intermittent signal)
- Org restructuring planned offline, applied as batch when online

Acceptable but not the primary use case.

### Build complexity

**Medium** — comparable to shopping list (the policy simplification removed the hardest part)

| Item | Effort | Notes |
|---|---|---|
| Org CRUD + hierarchy graph | Low | Same pattern as shopping list lists/members |
| Org chart Fulcro component | Medium | Tree viz with RBAC-per-field rendering |
| Headcount request lifecycle (state machine) | Medium | States: draft → step_1 → ... → step_N → approved → transitioning → filled |
| + Approval chain snapshot | Low | Copy chain from `$$approval-chains` at request creation |
| Pay/bonus visibility resolver | Medium | Conditional PState reads per field based on viewer/target properties |
| Dashboard + stats | Low | OLAP PStates, simple Fulcro charts |
| Role/actor management UI | Medium | Per-org actor assignment, role CRUD |

~10 Pathom resolvers/mutations, ~8 Fulcro screens, 5 depots, ~12 PStates.

### Comparison to other ideas

| Aspect | Shopping List | Approval (gen) | **Hiring Approval** | Genealogy |
|---|---|---|---|---|
| In-app auth | ★★★★☆ | ★★★★★ | **★★★★★** | ★★★★★ |
| Multi-DB | 6/6 | 6/6 | **6/6** | 6/6 |
| Buildable | Low-Med | Med | **Med** (policy simplified) | Med (High w/ forking) |
| Offline fit | ★★★★★ | ★★★★☆ | **★★★☆☆** | ★★★★★ |
| Demo clarity | High | Medium | **High** | High |
| "Why Rama" wow | Permission cascade | Hierarchy cascade | **Org restructure mid-approval + RBAC pay visibility** | Depot versioning + graph walk |

### The strongest "why Rama" moment

**Org restructure mid-approval-cycle** (described above) cannot be replicated in:
- **Postgres**: multi-step transaction + application-level re-route logic + webhook to warm cache
- **Mongo**: application-level query + update each affected document individually
- **Auth0**: completely invisible — Auth0 doesn't know org charts exist

In Rama it's one `source>` + one `ops/explode` + one `local-transform>` — sub-millisecond, atomic, consistent.

---

## Recommendation

For a **template demo** with focused scope, ship **Shared Shopping List** first.

For a **stronger in-app auth case study** (e.g., when presenting at a Clojure/conference), add the public gift registry extension — it clarifies the "Rama vs Auth0" story by demonstrating mixed anonymous/authenticated access to the same data.

For a **blueprint** internal teams would copy (e.g., expense reimbursement, vacation requests), ship **Approval Workflow**.

For the **strongest "why Rama" case study** (conference talk / blog post), ship **Genealogy** — specifically the depot-based forking model that no other database paradigm can match.

For the **best combination of demo clarity + technical depth**, ship **Hiring Approval + Org Chart** — it's as buildable as the shopping list, has the strongest in-app auth story (RBAC on pay data that Auth0 literally cannot enforce), and the org-restructure-mid-approval demo is the single best "why Rama" moment in any of these ideas.

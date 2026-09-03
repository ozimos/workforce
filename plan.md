# workforce — Architecture & Roadmap Plan

## 1. Executive Summary & Core Stack

**workforce** is a multi-tenant workforce and headcount platform: organizations, recursive
org units, headcount requisitions with multi-step approval, scoped RBAC, and a global
multi-currency loaded-cost engine. Identity and authentication are **not** in this repo —
they live in the sibling `omni-auth` repository and are consumed as `local/root` components.

Built entirely on:
- **Clojure 1.12.4** on **JDK 21+** (Java Virtual Threads / Project Loom).
- **Red Planet Labs Rama** — event depots, stream topologies, and materialized PStates. There is no RDBMS.
- **Polylith Architecture** — component/bases/projects with `:interface-ns "interface"`.
- **Buddy** (`buddy-auth`, `buddy-sign`, `buddy-hashers`) — JWT issuance, RS256 verification, password hashing, and Ring auth middleware.
- **Replicant + headless Fulcro** — declarative DOM rendering with zero React, backed by Fulcro's normalized client DB and `db->tree` denormalization.
- **Pathom 3** — EQL attribute resolution over `/api/query` and `/api/eql`.
- **Reitit + Ring + Muuntaja + Malli** — HTTP routing with request/response coercion.
- **Transactional Notification Engine** — MJML templates delivered over HTTP with presets (`:mailpit`, `:resend`, `:postmark`, `:sendgrid`).
- **Integrant + Aero + clj-reload + Launchpad** — sub-second REPL feedback loop and dev port orchestration.

---

## 2. Repository Boundary: workforce vs omni-auth

Two independently versioned git repositories sit side by side under a common parent
directory. `workforce` pulls `omni-auth` components in via relative `local/root` paths
under the `:+default` alias.

| Concern | Owner |
|---|---|
| Users, credentials, password hashing | `omni-auth` |
| Sessions, JWT issuance/verification, refresh | `omni-auth` |
| Token revocation (`$$revoked-tokens`) | `omni-auth` |
| MFA (TOTP), WebAuthn / Passkeys, backup codes | `omni-auth` |
| OAuth2 / OIDC and SAML federation | `omni-auth` |
| MJML email templates + HTTP delivery | `omni-auth` |
| Organizations, org units, memberships, invitations | `workforce` |
| Headcount requisitions, approval chains, SLA | `workforce` |
| Scoped actors, approval rules, role permissions | `workforce` |
| Employees, employments, currency, load factors, cost rollups | `workforce` |
| CSV schema generation, validation, ingestion | `workforce` |
| Replicant UI, Fulcro state graph, SSR | `workforce` |

Components consumed from `omni-auth` (14): `schema`, `config`, `rama`, `user-rama`,
`session-rama`, `revocation-rama`, `token`, `security`, `pathom`, `mfa`, `webauthn`,
`oauth`, `saml`, `notification`.

`workforce` owns two components and two bases:

```
components/org-rama/   # Rama module, depots, PStates, topologies, Pathom 3 resolvers, CSV, MCP
components/frontend/   # Replicant pages/components, Fulcro state graph, transit+json client
bases/web/             # Ring HTTP API, Reitit routes, Jetty 12, static assets & SPA fallback
bases/mobile/          # Placeholder scaffold — empty namespace, no implementation
projects/auth-service/ # Production uberjar packaging
```

---

## 3. Completed Architecture

```
                     ┌────────────────────────────────────────────────────────┐
                     │                    Integrant System                    │
                     │  (config.edn: HTTP presets, Rama cluster, Jetty, Auth) │
                     └───────────────────────────┬────────────────────────────┘
                                                 │
                  ┌──────────────────────────────┴─────────────────────────────┐
                  ▼                                                            ▼
     ┌────────────────────────┐                                   ┌────────────────────────┐
     │   omni-auth Security   │                                   │ workforce Org & Domain │
     │  - User identity       │                                   │  - Multi-tenant Orgs   │
     │  - Sessions & JTI      │                                   │  - Recursive Org Units │
     │  - WebAuthn & MFA      │                                   │  - Headcount Requests  │
     │  - OAuth2 & SAML       │                                   │  - Scoped Actors & RBAC│
     │  - HTTP Notifications  │                                   │  - Approval Workflows  │
     └────────────┬───────────┘                                   │  - Employees & Cost    │
                  │                                               └────────────┬───────────┘
                  └──────────────────────────────┬─────────────────────────────┘
                                                 ▼
                                ┌─────────────────────────────────┐
                                │       Red Planet Labs Rama      │
                                │   - Module Depots (Append-Only) │
                                │   - Stream Topologies           │
                                │   - Materialized PState Views   │
                                └─────────────────────────────────┘
```

### Completed capability set

1. **Multi-Tenant Orgs & Memberships** — create/join/switch, invitations, member roles.
2. **Recursive Org Unit Hierarchy** — Divisions → Departments → Teams, reparenting, budget, headcount stats.
3. **Headcount Requisition Workflows** — multi-step approval chains, field edits resetting to draft, rejection, transition-to-hire, audit timeline, approval SLA latencies, idempotency keys.
4. **Scoped Actors & RBAC** — per-unit actor assignment, org-level approval rules and role permission matrices.
5. **Employees & Employments** — hire, transfer, compensation revision, termination, employment history.
6. **Global Multi-Currency & Loaded Cost Engine** — org base currency, FX rate matrix, employee-type annualization multipliers, location/category/level load factors, tenant-defined custom attributes with active cost modifiers, rolled up into `$$unit-cost-stats`.
7. **Schema-Driven CSV Ingestion** — template generation from tenant schema, dry-run validation, atomic batch ingestion.
8. **Federated Auth** — OAuth2/OIDC provider authorize/callback and SAML SP-initiated authenticate/ACS.
9. **Replicant Frontend** — 15 pages plus nav and root, rendered without React, driven by a headless Fulcro normalized DB and a `defrouter-rc` union-query router.
10. **MJML Transactional Email** — verification, password reset, org invitation; Mailpit in dev.
11. **MCP Endpoint & Escapement Agent Tooling** — `/api/mcp` plus a behavior-tree hiring-approval chart runnable headless or with a live debugger.

---

## 4. Domain Model: Rama Depots & PStates

The `OrgExtension` record registers into the shared Rama module declared by `omni-auth`,
contributing 15 depots, 35 PStates, and 5 topologies.

### 4.1 Depots

| Depot | Partition key |
|---|---|
| `*org-create-depot` | `:name` |
| `*org-invite-depot` | `:invitation-id` |
| `*org-join-depot` | `:invitation-id` |
| `*org-switch-depot` | `:user-id` |
| `*org-member-update-depot` | `:target-user-id` |
| `*org-member-remove-depot` | `:target-user-id` |
| `*org-unit-depot` | `:unit-id` |
| `*headcount-depot` | `:request-id` |
| `*actor-depot` | `:unit-id` |
| `*policy-depot` | `:org-id` |
| `*employee-depot` | `:org-id` |
| `*employment-depot` | `:org-id` |
| `*tenant-attr-depot` | `:org-id` |
| `*currency-depot` | `:org-id` |
| `*load-factor-depot` | `:org-id` |

### 4.2 PStates

**Core organization** — `$$orgs`, `$$org-name->id`, `$$org-create-ids`, `$$memberships`,
`$$user-orgs`, `$$org-users`, `$$user-active-org`, `$$invitations`, `$$org-invitations`,
`$$email->invitations`, `$$org-members`.

**Org units & hierarchy** — `$$org-units`, `$$org->units`, `$$org-hierarchy`, `$$org-child-parent`.

**Headcount** — `$$headcount-requests`, `$$unit-requests`, `$$user-pending-approvals`, `$$request-timeline`.

**Analytics, SLA, actors** — `$$unit-headcount-stats`, `$$approval-sla`, `$$unit-actors`.

**Governance** — `$$approval-rules`, `$$role-permissions`.

**Idempotency** — `$$processed-idempotency-keys`.

**Employees & cost** — `$$org-currency-settings`, `$$fx-rates`, `$$employee-types`,
`$$load-factors`, `$$tenant-attribute-definitions`, `$$employees`, `$$employments`,
`$$employee->employment-history`, `$$unit->employments`, `$$unit-cost-stats`.

### 4.3 Topologies

| Topology | Responsibility |
|---|---|
| `build-org-lifecycle-topology` | Org create, invite, join, switch, member role/removal |
| `build-org-unit-topology` | Unit create/update/reparent/set-budget, hierarchy edges, stats seeding |
| `build-headcount-topology` | Requisition lifecycle, approval chain advance, SLA, idempotency |
| `build-governance-topology` | Scoped actor assignment, approval rules, role permissions |
| `build-employee-lifecycle-topology` | Currency/FX, employee types, load factors, tenant attributes, hire/transfer/comp-revision/terminate, cost rollups |

### 4.4 Loaded cost calculation

Every employment write recomputes an annualized loaded cost (see
`calculate-employment-loaded-cost` in `org/extension.clj`):

```
annual-base    = base-salary × employee-type-multiplier      (:full-time 1.0, :part-time 0.6, :intern 0.25)
loaded-base    = annual-base × load-factor(location, category, level)   [wildcard fallback → 1.0]
bonus          = annual-base × bonus-target
custom         = Σ normalize-annual-cost(attr) for attrs where cost-modifier? = true
                 (:monthly × 12, :one-off × 1, :annual × 1)
local-total    = loaded-base + bonus + custom
converted-total= local-total × fx-rate(employment-currency → org-base-currency)
```

Only attributes flagged `cost-modifier?` enter the total; display-only attributes are
retained on the record but excluded from rollups. `$$unit-cost-stats` accumulates
`:headcount`, `:total-raw-base-payroll`, `:total-loaded-payroll`,
`:total-custom-modifiers-cost`, and `:total-cost-base-currency`.

---

## 5. Security & Token Architecture

Security is **Buddy**-based; there is no Spring/Jakarta filter chain in this codebase.

1. **Password Hashing** — via `com.ozimos.omni-auth.security.core`.
2. **Stateless JWT + Revocation Check**:
   - Access tokens signed RS256 with a key ID.
   - `security/make-auth-backend` + `security/wrap-authentication` decode the bearer token
     and verify the `jti` against the Rama `$$revoked-tokens` PState owned by `omni-auth`.
   - Revocation is per-session (`/api/auth/logout`) or global (`/api/auth/logout-everywhere`).
3. **MFA & WebAuthn / Passkeys**:
   - TOTP with encrypted secrets and single-use recovery backup codes.
   - FIDO2 / Passkey credentials via `/api/auth/passkeys/*`.
4. **Federation** — OAuth2/OIDC and SAML, handled by the `omni-auth` `oauth` and `saml`
   components and exchanged for the application's own JWT on success.
5. **Tenant authorization at the resolver layer** — authentication is not sufficient to
   read an org. Pathom resolvers call `require-org-member`, which rejects the request
   unless `org/get-membership` returns a row for the caller in the target org. This is
   enforced on org metadata, dept-dashboard, and headcount-timeline resolvers, and on the
   `headcount/create` mutation — so a valid JWT for org A cannot read or write org B.
6. **Attribute-based masking** — the people chart applies RBAC compensation masking
   client-side, hiding pay fields the viewer is not cleared to see.

---

## 6. Core API Endpoints

Defined in `bases/web/src/clojure/com/ozimos/workforce/web/routes.clj`.

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register user & send verification email |
| POST | `/api/auth/login` | public | Authenticate; returns JWT pair, or an MFA challenge |
| POST | `/api/auth/refresh` | public | Issue new access token from refresh token |
| POST | `/api/auth/verify` | public | Verify account token |
| POST | `/api/auth/forgot-password` | public | Request password reset email |
| POST | `/api/auth/reset-password` | public | Reset password with token |
| POST | `/api/auth/logout` | authenticated | Revoke current session & JTI |
| POST | `/api/auth/logout-everywhere` | authenticated | Revoke all JTIs for user |
| POST | `/api/auth/mfa/setup` | authenticated | Generate TOTP secret & QR URL |
| POST | `/api/auth/mfa/verify-setup` | authenticated | Confirm TOTP and enable MFA |
| POST | `/api/auth/mfa/login` | public | Complete login with challenge token + TOTP/backup code |
| POST | `/api/auth/mfa/disable` | authenticated | Disable MFA using TOTP or backup code |
| GET/POST | `/api/auth/mfa/backup-codes` | authenticated | Remaining count / regenerate 10 codes |
| GET | `/api/auth/passkeys` | authenticated | List registered passkeys |
| POST | `/api/auth/passkeys/register/begin` | authenticated | Registration challenge options |
| POST | `/api/auth/passkeys/register/finish` | authenticated | Verify attestation and persist |
| POST | `/api/auth/passkeys/authenticate/begin` | mixed | Assertion options |
| DELETE | `/api/auth/passkeys/:credential-id` | authenticated | Remove a passkey |
| GET | `/api/auth/oauth/:provider/authorize` | public | Start OAuth2 flow |
| GET/POST | `/api/auth/oauth/:provider/callback` | public | OAuth2 callback |
| GET | `/api/auth/saml/authenticate` | public | Start SAML SP-initiated flow |
| POST | `/api/auth/saml/acs` | public | SAML assertion consumer service |
| POST | `/api/query` | mixed | Pathom 3 EQL query/mutation |
| POST | `/api/eql` | mixed | Pathom 3 batched EQL |
| POST | `/api/mcp` | mixed | Model Context Protocol endpoint |
| GET | `/api/health` | public | Health check |

Middleware chain: `wrap-inject-system` → `wrap-idempotency-key` → `parameters` →
`muuntaja` format negotiation/response → `exception` → `muuntaja` request → `coercion`.
Non-`/api` 404s fall back to `public/index.html` (SPA fallback).

---

## 7. Frontend Architecture

**Headless Fulcro + Replicant DOM.** Fulcro is used for its normalized client DB, EQL
queries, and mutations — but never for rendering. There are zero React components on the
`core.cljs` render path.

- `com.ozimos.workforce.frontend.core` creates a single `(app/fulcro-app {})` instance.
- Two macros in `components/frontend/src/cljc/.../defrc.cljc` (mirrored under `src/clj/`
  for JVM consumption) generate the view layer:
  - `defrc` produces a **plain function** `props -> hiccup` carrying `:query` and `:ident`
    as metadata (not a React component).
  - `defrouter-rc` produces a data-driven dynamic router: a render fn that resolves
    `{:router/current-route {<ident-key> props}}` against a target map, carrying a Fulcro
    EQL **union query** built from each target's `:query`/`:ident`. `MainRouter` in
    `ui/root_replicant.cljs` registers all 15 page targets under `:router-id :main-router`.
- **Routing is state, not a routing library.** `current-path-route` maps
  `window.location` to a `:route/*` keyword, `route->target-ident` maps that to a Fulcro
  ident, and `sync-route-state!` writes both the flat `:route` key and the normalized
  `[:root-router/by-id :main-router]` entry. `pushState` (`navigate!`) and `popstate`
  both funnel through it.
- `load-rc!` is the headless data loader: given a component (or an explicit query) it
  posts EQL to `/api/query` via `transit/fetch-transit`, managing `:loading`/`:error`
  around the request with optional `:on-success`/`:on-error` hooks.
- **Rendering is driven from `core.cljs`.** `render!` denormalizes `RootReplicant`'s query
  with `denorm/db->tree`, enriches the tree with router props, and calls
  `replicant.dom/render`; `schedule-render!` coalesces state changes behind a
  `requestAnimationFrame` guard so a burst of `transact!` calls paints once.
- `bridge/dispatch!` adapts Replicant's 2-arg `*dispatch*` contract
  `(fn [event-map handler-data])`. Given the app instance it also recognises Fulcro
  mutation expressions — `:on {:click [(my-mutation {:id 1})]}` is forwarded straight to
  `comp/transact!` — so mutation-style events need no entry in the `event-handlers` table.
- Network I/O uses `transit/fetch-transit` for EQL (`/api/query`, both queries and
  mutations) and `json/fetch-json` for REST auth routes. Tokens live in `localStorage`.

**Pages (15):** home, login, register, verify, forgot-password, reset-password, profile,
create-org, join-org, org-dashboard, org-chart, people-chart, dept-dashboard, headcount,
policy-settings — plus `nav` and `root`. 26 `defrc` views and one `defrouter-rc` in total.
Every page has a `*-replicant` implementation; all but `people-chart` also retain a legacy
`defsc` twin plus a `*_replicant_host.cljs` React bridge (14 of them) used for standalone
per-page mounting via `bridge/install-replicant-root!`. `people-chart` is Replicant-only.

Route-to-view names are deliberately crossed: `/org-chart` renders
`people-chart/PeopleChartReplicant` (the people chart with RBAC compensation masking),
while `/org-chart-2` renders `org-chart/OrgChartReplicant` (the unit/department hierarchy).

**Builds** (`shadow-cljs.edn`): `:app` (browser), `:ssr` (node-library → `ssr-output/ssr.js`),
`:test` (node-test → `target/frontend-test.js`).

**SSR** is served by `ssr-server/server.js` (Express) on port 3000, which loads the
`:ssr` bundle and reverse-proxies `/api/*` to the Jetty backend. `bases/web` itself serves
static assets and an SPA fallback; it does not render server-side.

**Tests** — 25 CLJS test namespaces under `components/frontend/test/cljs` (104 `deftest`
forms, 426 assertions), run via `bb fe-test`. Each page test covers render states, pure
state transitions, hiccup well-formedness (guards against raw `[:div` leaking into the
DOM), event purity, and headless Fulcro denormalization + transact cycles.

---

## 8. REPL-First Development Workflow

- **Launchpad (`bb repl`)** provisions nREPL, allocates non-conflicting ports, spawns
  Mailpit, and starts Shadow-CLJS and the Node SSR server.
- **integrant-repl + clj-reload** in `development/src/clojure/user.clj`:
  - `(user/go)` / `(user/start-and-seed!)` — mount Rama topologies, seed data, start Jetty.
  - `(user/halt)` — graceful teardown.
  - `(user/reset)` — hot-reload changed namespaces and rebuild (< 1s).
  - `(user/test-all)` — backend tests against running state.
  - `(user/test-clean)` — backend tests against a fresh ephemeral IPC cluster.
  - `(user/test-ns 'sym)` — single namespace.
  - `(user/gen-seed!)` / `(user/seed!)` — regenerate or load the Nippy seed archive.
- **Babashka tasks** — `bb test-fast`, `bb test-fast <ns>`, `bb fe-test`, `bb lint`,
  `bb fmt-check`, `bb fmt-fix`, `bb css`, `bb fe`, `bb fe-watch`, `bb fe-ssr`, `bb build`,
  `bb kill-ports`, `bb ssr-kill`, `bb test-repl-boot`, `bb sim-test`, `bb agent`,
  `bb agent-debug`, `bb agent-sim`, `bb seed-gen`, `bb seed-load`, `bb wt-new <branch>`.
- **Worktrees** — `deps.local.edn` carries zero-sentinel ports so each worktree gets
  dynamically allocated, non-conflicting ports. Always run `bb repl` from inside the worktree.

`user/test-all` covers `org.resolvers-test`, `org.ipc-test`, and the three
`bases/web` integration suites (main, OAuth, SAML). The remaining org tests —
`csv-test`, `generator-test`, `rbac-test`, `rule-engine-test`, `seed-test`,
`simulation-test` — run through `poly test` (`bb test`) or `bb sim-test`.

---

## 9. Key Decisions

1. **Malli over clojure.spec** — data-driven schemas with first-class Reitit coercion.
2. **Buddy over Spring Security** — Ring middleware (`make-auth-backend` +
   `wrap-authentication`) instead of a servlet filter chain. No Java security
   configuration, no `gen-class`, no servlet context bridging.
3. **Revocation lives in Rama, not in the token** — JWT `exp` bounds validity; the
   `jti` is checked against `$$revoked-tokens` on every authenticated request.
4. **Rama is the sole data store** — orgs, units, requisitions, employees, and cost
   rollups all live in depots and PStates. No relational database.
5. **Fulcro without React** — Fulcro supplies normalization and mutations; Replicant
   supplies rendering. Views are pure functions, so they are testable as data and
   reusable from a non-Fulcro host (the `bases/mobile` scaffold targets a plain atom).
6. **`src/clojure` source paths** — separates Clojure sources from other sources.
7. **No `last-active` tracking** — JWT `exp` bounds validity; no per-request Rama writes.
8. **Idempotency keys on writes** — `$$processed-idempotency-keys` short-circuits
   duplicate headcount depot appends.
9. **CSV-first ingestion** — tenant onboarding and historical migration go through
   schema-driven CSV before any live system integration is attempted.

---

## 10. Roadmap

### 10.1 Machine-to-Machine Auth
Client credentials and device authorization grants for non-human clients.
- New `client` component: `register-client!`, `validate-client!`, `issue-client-token`.
- PState `$$clients {String {:client-secret-hash String :scopes #{String}}}`; depot `*client-register-depot` (hash-by `:client-id`).
- Endpoints: `POST /api/auth/token` (client_credentials), `POST /api/auth/device/authorize`, `POST /api/auth/device/token`.
- PState `$$device-codes {String {:user-code String :status String :expires-at Long :client-id String}}`.
- Per-route scope metadata in Reitit route data (`:scopes ["read"]`) enforced in the auth backend.

### 10.2 Passwordless Auth
- Magic links: `POST /api/auth/magic-link/request`, `GET /api/auth/magic-link/verify?token=…`, PState `$$magic-links`.
- Email/SMS OTP: `POST /api/auth/otp/request`, `POST /api/auth/otp/verify`, 6-digit code, 5-minute expiry, max 3 attempts, PState `$$otps`.
- Delivery reuses the existing `omni-auth` `notification` component. **SMS is not
  currently supported** — the component implements HTTP email presets only, so an SMS
  provider would be a new addition there.
- Rate limiting via a token-bucket PState `$$rate-limits`.

### 10.3 Single Sign-Out (SLO)
- SAML SLO: `GET /api/auth/saml/logout`, `POST /api/auth/saml/slo`.
- OIDC RP-initiated logout: `POST /api/auth/oauth/logout` with `id_token_hint`, plus
  front-channel and back-channel logout endpoints.
- On SLO, reuse the existing revoke-all paths and append an audit event.

### 10.4 Disparate Data Ingestion & Decision Support
`workforce` is positioned as an aggregation and decision layer above systems of record
(HRIS, ATS, finance models) rather than a replacement for them.

```
  ┌───────────────────────────┐   ┌───────────────────────────┐   ┌───────────────────────────┐
  │   HRIS (System of Record) │   │   ATS (Recruiting)        │   │  Finance & Compensation   │
  │   - Workday / BambooHR    │   │   - Greenhouse / Ashby    │   │  - Market Salary Bands    │
  │   - Active Employees      │   │   - Candidate Stages      │   │  - Budget Allocations     │
  └─────────────┬─────────────┘   └─────────────┬─────────────┘   └─────────────┬─────────────┘
                └───────────────────────────────┼───────────────────────────────┘
                                                ▼
                              ┌──────────────────────────────────┐
                              │  workforce — Decision Engine     │
                              │  Requisitions ◄──► Org Realities │
                              │  Materialized cost & runway PStates│
                              └──────────────────────────────────┘
```

Proposed ingestion depots and PStates:
- `*hris-roster-sync-depot` → `$$hris-roster {Long {:employee-id … :job-level … :comp … :dept-id …}}`
- `*ats-pipeline-sync-depot` → `$$ats-candidates {String {:candidate-id … :stage … :target-req-id … :offer-details …}}`
- `*comp-benchmark-sync-depot` → `$$comp-benchmarks {String {:role … :level … :p50-salary … :p75-salary …}}`

Decision features on top of that data: offer impact and runway simulation, internal equity
and compression detection, and requisition bottleneck/SLA radar.

### 10.5 Unified Integration Layer
Direct connectors (Merge.dev / Finch / Kombo style) for continuous HRIS and ATS
synchronization, mapping remote custom fields into `$$tenant-attribute-definitions`
without code changes.

### 10.6 Delivery & Operations

**Uberjar** — `build.clj` defines `clean` and `uber`, producing
`target/auth-service-0.1.0-SNAPSHOT-standalone.jar` with
`com.ozimos.workforce.web.main` as the main class. There is no `bb uber` task yet;
invoke it directly with `clojure -T:build uber`. Expect roughly 295 MB and several
minutes, almost all of it Rama.

The basis is built from the workspace root using the `:uberjar` alias, which supplies
the same bricks as `:+default` without its `:extra-paths`. Two things constrain that
choice: `projects/auth-service/deps.edn` also declares `:uberjar`, but its `:local/root`
paths are relative to the project directory and so do not resolve when the build runs
from the workspace root; and `development/src/clojure` must stay off the build
classpath, because the JVM auto-loads `user.clj`, which requires `:dev`-only tooling.
`b/uber` additionally excludes `META-INF/license/**` and jar signatures, which Netty
and other dependencies ship as conflicting file/directory pairs.

Neither `bb build` nor CI builds the uberjar, so this path is worth wiring up before it
is relied on for deployment.

**CI** — `.github/workflows/ci.yml` runs on pushes and PRs to `main` on Ubuntu with JDK 21:
`bb lint` → `bb build` (Tailwind CSS + shadow-cljs app and ssr) → `bb test` (Polylith JVM
suite) → `bb test-repl-boot` (fresh REPL + in-REPL `test-clean`) → `bb fe-test` and
`node ssr-server/test_proxy.js`. `poly check` and `bb fmt-check` are available but not
yet wired into CI.

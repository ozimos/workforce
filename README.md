# workforce

A strategic **Headcount Intelligence & Hiring Decision Engine** built with Clojure, Red Planet Labs Rama, Polylith, Pathom 3, Replicant, Fulcro normalized state graph, Buddy, and the `omni-auth` security engine.

## Strategic Positioning

**`workforce` does not seek to replace existing HRIS (Workday, BambooHR, Rippling) or ATS (Greenhouse, Ashby, Lever) solutions.** 

Instead, `workforce` unifies data from these disparate systems of record into a **real-time decision-support layer** to empower leadership, hiring managers, and finance teams to make faster, higher-conviction hiring and headcount allocation decisions:

```
  ┌───────────────────────────┐      ┌───────────────────────────┐      ┌───────────────────────────┐
  │   HRIS (System of Record) │      │   ATS (Recruiting Pipeline)│     │  Finance & Compensation   │
  │   - Workday / BambooHR    │      │   - Greenhouse / Ashby    │      │  - Market Salary Bands    │
  │   - Active Employees      │      │   - Candidate Stages      │      │  - Budget Allocations     │
  │   - Historical Placements │      │   - Offers Out / Status   │      │  - Financial Runway       │
  └─────────────┬─────────────┘      └─────────────┬─────────────┘      └─────────────┬─────────────┘
                │                                  │                                  │
                │ Ingestion Depots / Webhooks      │ Ingestion Depots / Webhooks      │ Ingestion Depots
                ▼                                  ▼                                  ▼
 ┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
 │                                   workforce (Decision Engine)                                    │
 │                                                                                                  │
 │   1. Stream Unification (Rama Depots): Ingests worker status, candidate offers, and budgets      │
 │   2. Unified Headcount Graph: Bridges Requisitions ◄──► Candidate Pipelines ◄──► Org Realities  │
 │   3. Dynamic Decision Simulation:                                                                │
 │      - "If we make this L6 offer at $185k, what happens to department runway?"                   │
 │      - "Which open requisitions are blocking Q4 delivery milestones?"                            │
 │      - "What is our team's compensation equity/compression across internal vs. external hires?"  │
 │   4. Materialized Insights (PStates): Instant org rollups, runway forecasts, and approval rules   │
 └──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Key Capabilities

- **Headcount Decision Engine & Requisitions**: Multi-step requisition workflows with dynamic approval routing, budget validation, and real-time SLA metrics.
- **Pipeline-to-Budget Synchronization**: Reconciles open ATS interview pipelines and pending offers against department budget allocations in real time.
- **Event-Sourced Hierarchy & Scoped RBAC**: Rama module topologies managing multi-tenant Organizations, Recursive Org Units (Divisions, Departments, Teams), Scoped Actors, and Dynamic Approval Rules.
- **Zero-React DOM Rendering**: Replicant-driven UI rendering with seamless Fulcro normalized graph state management and zero React runtime overhead.
- **Transactional Email System**: MJML responsive email templates (verification, password reset, org invitations) delivered via HTTP Send APIs with provider presets (`:mailpit`, `:resend`, `:postmark`, `:sendgrid`).
- **Hybrid Stateless Auth**: JWT access tokens validated via RSA public keys with instant Rama PState revocation checks (`$$revoked-tokens`).

---

## Prerequisites

- **JDK 21+** (Temurin 21 or 25 recommended)
- **Babashka** (`bb`) — task runner and development launchpad
- **Clojure CLI** (`clojure`) — `tools.deps`
- **Node.js** (v18+) & **pnpm** — for frontend asset compilation and Node SSR server
- **Mailpit** (`brew install mailpit`) — local transactional email server and inspector

---

## Quick Start

```bash
# Start the full development stack (Launchpad + Mailpit + Shadow-CLJS + SSR + Dev REPL)
bb repl
```

Launchpad orchestrates:
1. **Mailpit Server**: Automatically spawned in the background (SMTP `:1025`, Web UI `http://localhost:8025`).
2. **nREPL Server**: Boots on port `4005` (or dynamic non-conflicting port in worktrees).
3. **Shadow-CLJS**: Watches and compiles `:app` (browser client) and `:ssr` (server-side rendering bundle).
4. **Node SSR Proxy**: Runs `ssr-server/server.js` on port `3000`, routing frontend SSR and proxying `/api/*` to Jetty backend.
5. **Integrant JVM Lifecycle**: Mounts the in-memory Rama cluster, seeds demo data, and starts Jetty on port `8100`.

In your connected editor or REPL:

```clojure
(user/start-and-seed!) ;; Starts system and populates Rama seed data
(user/go)              ;; Starts the system
(user/halt)            ;; Stops the system
(user/reset)           ;; Hot-reloads changed namespaces & rebuilds system (< 1s)
```

---

## Worktrees & Multi-Branch Development

When developing in Git worktrees:

- **Isolated Dev Ports**: Worktrees automatically configure `:nrepl-port 0` and `:jetty/port {:dev 0}` in `deps.local.edn`. Launchpad allocates non-conflicting free ports and records active ports in `.nrepl-port`.
- **Dedicated REPL per Worktree**: Always run `bb repl` inside the worktree directory.
- **Clean Port Management**: Run `bb kill-ports` at any time to terminate any lingering processes across all dev ports (nREPL, Shadow-CLJS, SSR, Jetty, Mailpit).

---

## Verification & URLs

| Service | URL | Description |
|---|---|---|
| **Workforce Web App** | `http://localhost:3000` | Full Replicant frontend via Node SSR proxy |
| **Backend REST & EQL API** | `http://localhost:8100` | Jetty 12 Ring HTTP server + Pathom 3 EQL endpoint |
| **Mailpit Web UI** | `http://localhost:8025` | Local email inspector & inbox viewer |
| **Shadow-CLJS Dashboard** | `http://localhost:9630` | ClojureScript build inspector |

---

## Testing & Quality Assurance

`workforce` provides tiered testing for rapid in-REPL feedback (<0.5s) and full end-to-end multi-runtime verification:

| Command | Environment | Description | Typical Speed |
|---|---|---|---|
| `bb test-fast` | Warm Dev REPL / JVM | Runs backend test suites (Org, Resolvers, Auth, Web, RBAC) | **< 1.0s** (Warm) |
| `bb test-fast <ns>` | Warm Dev REPL | Runs a single test namespace | **~ 50ms** |
| `bb fe-test` | Node.js (`shadow-cljs`) | Headless ClojureScript Replicant page & component tests (93 tests, 341 assertions) | **~ 5s** |
| `bb lint` | `clj-kondo` | Lints entire codebase (workforce + omni-auth components) | **< 1s** (0 errors, 0 warnings) |
| `bb fmt-check` | `standard-clj` | Code style and formatting validator | **< 1s** |
| `bb test` | Standalone JVM | Cold Polylith test runner (`poly test`) | **~ 35s** |

---

## Project Structure

```
workforce/
├── workspace.edn                     # Polylith workspace definition
├── deps.edn                         # Root dependencies & aliases (:dev, :test, :+rama, :+default, :poly)
├── bb.edn                           # Babashka tasks, linting, and port lifecycles
├── bin/launchpad                    # Development launchpad orchestration script
├── shadow-cljs.edn                  # ClojureScript build definitions (:app, :ssr, :test)
├── ssr-server/                      # Express SSR relay & reverse proxy (port 3000)
│
├── components/                      # Polylith components
│   ├── org-rama/                    # Rama OrgModule, depots, PStates, and Pathom 3 resolvers
│   │                                # (Organizations, Org Units, Headcount Requisitions, Scoped Actors, RBAC)
│   └── frontend/                    # Replicant UI pages, components, and Fulcro state graph
│
├── bases/
│   └── web/                         # Ring HTTP API, Reitit routes, Jetty 12 adapter, SSR bridge
│       └── resources/config.edn     # System configuration (HTTP email presets, auth policies, ports)
│
├── development/
│   └── src/clojure/user.clj         # REPL entry point, clj-reload, and interactive comment forms
│
└── projects/
    └── auth-service/                # Production uberjar project packaging
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Clojure 1.12 on JDK 21+ (Java Virtual Threads) |
| **Architecture** | Polylith Modular Architecture |
| **Data Layer** | Red Planet Labs Rama (Event Depots, Topologies, PStates) |
| **API & Graph** | Pathom 3 (EQL Attribute Resolution) + Reitit + Ring |
| **Frontend Rendering** | Replicant (Virtual DOM-free declarative DOM rendering) |
| **State Management** | Fulcro 3 Normalized Graph State DB + Mutations |
| **Email Engine** | MJML Responsive Templates + Pure HTTP Delivery (`hato` / `jsonista`) |
| **Security & Auth** | Buddy (Auth, Sign, Hashers) + Rama Revocation Index |
| **System Lifecycle** | Integrant + integrant-repl + clj-reload |

MIT
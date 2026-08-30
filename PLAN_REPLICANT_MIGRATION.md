# Implementation Plan: Incremental 15-Page Replicant Migration & Mobile Foundation

This plan outlines the systematic migration of the frontend from Fulcro `defsc` (React DOM) to pure Replicant `defrc` (pure Hiccup `.cljc`), executing hardest-first with dual routes (`_replicant.cljs`), per-commit verification gates, JVM SEO SSR on Jetty, and final Polylith component separation.

---

## Key Operational Principles
1. **Zero Downtime / Dual Routing**: Each page gets a parallel `_replicant.cljs` and `views/foo.cljc` namespace mounted at `/foo-replicant`. Legacy `defsc` routes remain untouched until full hydration is proven.
2. **Node :3000 Preserved During Migration**: The Node SSR server remains active until Root is migrated to avoid breaking current agent inspection tooling.
3. **Per-Commit Test Gate**: Every page commit must pass `bb fe-test`, `bb test-fast`, `bb test-fast-clean`, `bb lint`, and `bb fmt-check`.
4. **Polylith Split Last**: The component split (`frontend-ui`, `frontend-web`, `frontend-flutter`) is deferred to Phase 6 to prevent `cljd` dialect checks from interfering with web builds during the page migration.

---

## 8-Phase Execution Strategy

### Phase 0: Stub Mobile Base (Forward Compatibility) (~1h)
- ~~Update `workspace.edn`: dialects `["clj" "cljs"]` $\to$ `["clj" "cljs" "cljc" "cljd"]` for future `src/cljd`.~~ **DEVIATION (executed 2026-08-26)**: clj-poly 0.3.32 validates `:dialects` as an enum of only `"clj"`/`"cljs"`; declaring `cljc`/`cljd` fails `poly check`. `cljc` is read automatically by poly; `:dialects` stays `["clj" "cljs"]` until a polylith release widens the enum (tracked in beads; revisit before Phase 8 `src/cljd`).
- Create `bases/mobile/deps.edn` with `{:paths ["src/clojure" "resources"]}`.
- Create `bases/mobile/src/clojure/com/ozimos/workforce/mobile/core.clj` namespace stub (`com.ozimos.workforce.mobile.core`) and `resources/mobile/.keep`.
- **Do not** add `poly/mobile` to `deps.edn:+default` / `projects/auth-service` / `user.clj` `clj-reload` nor `shadow-cljs.edn`.
- Verify `poly check` passes with 2 bases.

---

### Phase 1: Shared `.cljc` Directory Setup (Frontend Reorganization) (~1h)
- Update `components/frontend/deps.edn` paths: `["src/clj" "src/cljs" "src/cljc" "resources"]`.
- Update `shadow-cljs.edn` source paths to include `"src/cljc"` and `"test/cljc"`.
- Extract `org_chart_replicant.cljs` pure view helpers, formatting (`format-role-name`, `kpi-badge`, `render-unit-card`, `render-tree-branch`), mutations (`toggle-collapse`, `expand-all`, `collapse-all`, `set-search-term`), and `OrgChartReplicant` `defrc` into `components/frontend/src/cljc/com/ozimos/workforce/frontend/views/org_chart.cljc`.
- Create cross-runtime math helper `com.ozimos.workforce.frontend.util.math` (`#?(:cljs js/Math.round :clj Math/round)`).
- Turn `src/cljs/.../org_chart_replicant.cljs` into a lightweight 5-line facade re-exporting `views/org_chart.cljc`.
- Verify `bb fe-test` passes (18 tests, 91 assertions).
- for each of the following phases add replicant ui tests where neccessary

---

### Phase 2: 15-Page Migration (Hardest First, Parallel `_replicant.cljs`) (~17h)

Per page:
1. `src/cljc/.../views/<page>.cljc` — Pure `defrc` view, pure state transition functions `(fn [db params] -> updated-db)`, `:query` metadata preserved.
2. `src/cljs/.../<page>_replicant.cljs` — Client facade and `defmutation` bindings for Fulcro state atom.
3. `src/cljs/.../<page>_replicant_host.cljs` — Mounts Replicant root via bridge on dev route `/<page>-replicant`.
4. `test/cljc/.../<page>_replicant_test.cljc` — 4-tier test suite:
   - Deterministic view equality (`=`)
   - `valid-hiccup?` grammar and no serialized Clojure code (`[:div`)
   - Headless Fulcro `db->tree` query denormalization
   - Headless user interaction & mutation cycle (`comp/transact!` $\to$ `swap!` $\to$ re-render)
   - 2-arg Replicant event dispatch with JS event target value extraction
5. Register route in `root.cljs`, `test_runner.cljs`, and `ssr.cljs`.

#### Migration Order:
| Step | Route / Page | Key Complexity / Handlers |
| :--- | :--- | :--- |
| **0** | `:route/org-chart` | Already done & proven (`org_chart_replicant.cljs:157`) |
| **1** | `headcount` | Headcount inbox, 6-field requisition form, approve-step, reject, "L4" level dropdown |
| **2** | `dept_dashboard` | `fetch-dept-dashboard!`, `init-page-data!`, `avg-sla-ms` math calculations, analytics cards |
| **3** | `org_dashboard` | Org-level metrics rollups, KPI cards, deep links |
| **4** | `policy_settings` | SLA threshold controls, policy update mutations |
| **5** | `profile` | User identity details, MFA status toggle, session management |
| **6** | `nav` | Org-switcher dropdown, active route highlighting, auth token state (Must complete before Root) |
| **7** | `home` | Top-level dashboard cards & quick navigation |
| **8** | `join_org` | Organization invite code acceptance & search input |
| **9** | `create_org` | New organization setup form & tenant initialization |
| **10** | `login` | Identifier/password form, 2FA MFA challenge sub-form |
| **11** | `register` | User signup form & validation |
| **12** | `verify` | Email verification token handler |
| **13** | `forgot_password` | Password reset link request |
| **14** | `reset_password` | Password update with reset token |
| **15** | `Root` | Top-level layout, page router (`js/window.pathname`), `logged-in?` auth gate, delay factories |

---

### Phase 3: Per-Commit Verification Gate (Warm REPL, No PR)
Every single page migration commit must pass:
```bash
bb test-fast <page-ns-test>   # < 50ms warm REPL test
bb test-fast-clean            # ~1s ephemeral IPC, random-uuid
bb fe-test                    # Shadow release test runner -> 0 failures
bb lint                       # clj-kondo
bb fmt-check                  # standard-clj formatting
```

---

### Phase 4: Full Hydration & Demo Seed Removal
- Keep `host.cljs:20` `demo-seed-data` fallback through all 15 pages.
- Delete seed fallback only when `rg defsc src` $\to$ 0, `Root` `defrc` + `bridge/install-replicant-root!` mounts real `app/fulcro-app` via Pathom resolvers (`resolvers.clj:142` `org-chart-resolver` $\to$ `list-org-units:287`).
- Verify all 15 routes return HTTP 200 with `ssr-status="ok"` and no `#ssr-error`.
- Verify 15/15 `valid-hiccup?` test suites pass green without seed fallback.
- Run `bb test-all` across full multi-runtime suite.
- Commit: `refactor(frontend): remove demo seed — full app hydrates via transit`.

---

### Phase 5: JVM SEO SSR on Jetty (Replacing Node SSR)
- Keep Node SSR (`ssr.cljs`, `server.js` on :3000) active until Root is migrated.
- Add `no.cjohansen/replicant "2026.07.1"` and `src/cljc` to `bases/web/deps.edn` (`:+default`).
- Vendor `denormalize.cljc` (pure Fulcro 3.9.5 EQL denormalizer) into `frontend-ui`.
- Create `bases/web/src/clojure/com/ozimos/workforce/web/ssr.clj` using `replicant.string/render` + `denorm/db->tree` directly in Ring handlers on Jetty.
- Replace `wrap-spa` in `routes.clj:145` with `wrap-ssr` serving pre-rendered HTML for all 15 routes from `ssr_meta.cljc`, adding `X-JVM-SSR` header.
- Preserve static CSS/JS shell links (`/css/app.css`, `/js/main.js`).

---

### Phase 6: Polylith Component Separation (~2h)
- Create `components/frontend-ui`: pure `.cljc` views (`defrc`), queries, pure state transition functions, math utils, and vendored `denormalize.cljc`.
- Create `components/frontend-web`: `replicant_bridge.cljs`, `replicant.dom/render`, client navigation, Transit/JSON fetch.
- Create `components/frontend-flutter`: `src/cljd` Hiccup $\to$ Flutter Widget mapper (`Column`, `Row`, `Text`, `Container`, `GestureDetector`).
- Verify `poly check` passes cleanly.

---

### Phase 7: Decommission Node SSR (:3000)
- Delete `ssr-server/`, `ssr-output/`, `:ssr` target from `shadow-cljs.edn:11`, `bb fe-ssr`, `bb ssr-start`, and Launchpad Node supervisor.
- Remove React and ReactDOM from `package.json`.
- Update `AGENTS.md:51` topology documentation (Jetty :8080 becomes sole server for SSR and API).

---

### Phase 8: Mobile Architecture & Client EQL Integration
- Mobile `bases/mobile` uses a plain ClojureDart `atom` with `add-watch` driving Flutter reactive rebuilds.
- Queries and mutations reuse the exact same `.cljc` contracts from `frontend-ui`.
- Network calls invoke Pathom resolvers over standard `dart:io` Transit+JSON HTTP requests (`/api/query`).

---

## Verification Summary

### Automated Testing Commands
1. **Per-Page Unit & Headless Tests**:
   ```bash
   bb fe-test
   ```
2. **Backend IPC & Integration**:
   ```bash
   bb test-fast-clean
   ```
3. **Full Multi-Runtime Suite**:
   ```bash
   bb test-all
   ```
4. **Linting & Style Checks**:
   ```bash
   bb lint && bb fmt-check
   ```

### Manual Verification
- Playwright E2E test runs against both legacy `/org-chart` and new Replicant routes.
- `curl -i http://localhost:8100/<route>` to verify JVM SSR headers (`X-JVM-SSR`) and clean initial HTML.

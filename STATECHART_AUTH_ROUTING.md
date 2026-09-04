# Statecharts Auth & Protected Route Redirection: Problem Analysis and Solution Architecture

This document is prepared for an incoming agent / LLM taking over the task of auth-lifecycle and route-guard integration using **Fulcro Statecharts** (`com.fulcrologic/statecharts`) in the Workforce pure-Replicant frontend.

---

## 1. Context & Architecture Overview

### The Application Stack
- **Backend**: Clojure, Ring, Reitit, Pathom 3, Red Planet Labs Rama (`integrant` managed, hot-reloading REPL via `bb repl`).
- **Web Base (`bases/web`)**: Serves API routes (`/api/*`), static resources from `resources/public`, and wraps all other unmatched requests in `wrap-spa` which returns `public/index.html` (HTTP 200).
- **Frontend (`components/frontend`)**:
  - **Headless Fulcro**: Uses `app/fulcro-app` purely as a normalized graph database, EQL query orchestrator, and mutation engine (NO React DOM, zero React components).
  - **Replicant**: Pure data-driven VDOM library (`defrc`). Listens to Fulcro App DB atom mutations (`add-watch`) and diffs/patches Hiccup into `#app`.
  - **Dynamic Router**: `MainRouter` in `root_replicant.cljs` resolves routes based on `[:root-router/by-id :main-router :router/current-route]`.
  - **Statecharts**: `com.fulcrologic/statecharts 1.4.0-RC19` installed onto `app-inst` using `scf/install-fulcro-statecharts!`.

---

## 2. The Problem: "Why Direct Navigation to Protected Route (e.g. `/org-chart`) Does Not Redirect"

When a user visits `http://localhost:<PORT>/org-chart` directly in a browser tab without an auth token in `localStorage`:

### Issue A: SPA Serving vs. Client-Side Boot Timing
1. **Server-Side Behavior**:
   The backend Ring handler (`wrap-spa` in `bases/web/src/clojure/com/ozimos/workforce/web/routes.clj`) serves `public/index.html` for ANY unmatched GET request. It does **not** do server-side 302 redirects.
   Therefore, the browser receives `index.html` (HTTP 200) and executes `public/js/main.js`.
2. **Client-Side Async Event Loop vs Initial Render**:
   - `com.fulcrologic.statecharts.integration.fulcro/install-fulcro-statecharts!` uses a **`core.async` event loop** in ClojureScript.
   - In CLJS, **all `scf/send!` calls are asynchronous**. The `:event-loop? :immediate` option in Statecharts is JVM/CLJ-only.
   - When `init` ran:
     ```clojure
     (scf/start! app-inst ...)
     (scf/send! app-inst ... :event/no-token)
     ...
     (render!)
     ```
     `(render!)` ran **synchronously immediately**, before the `core.async` go-loop processed `:event/no-token` and fired the `:state/unauthenticated` `on-entry` script.
   - As a result, the UI rendered whatever route was in the browser pathname (`/org-chart` -> `WorkforceChart` or `OrgChartReplicant`), flashing or getting stuck on the protected view before any redirect could execute.

### Issue B: History State & State Atom Sync
In early iterations, `handle-statechart-redirect!` was either not mutating `js/window.history.replaceState` or not updating the route inside the Fulcro DB before the Replicant VDOM diff occurred. If the address bar remains `/org-chart` and the Replicant tree has already mounted the protected component, the unauthenticated state was not visually apparent.

### Issue C: Transitive Dependency Conflict (`promesa`)
`com.wsscode/pathom3 2023.01.31-alpha` brought in `funcool/promesa 8.0.450`. However, `com.fulcrologic/statecharts 1.4.0-RC19` uses `promesa.core/await!` (introduced in `10.0.570`) on the JVM macro compilation classpath.
**Status**: Fixed in commit `86d6524` by explicitly pinning `funcool/promesa 11.0.678` in `deps.edn` and `shadow-cljs.edn`.

---

## 3. The Approach to the Solution

### Architectural Principles
1. **Dual Guard: Eager Synchronous Initial Boot + Statechart Runtime Driver**:
   - **Boot phase (synchronous)**: When `init` runs upon page load, inspect `(is-logged-in?)` and `(auth-sc/protected-path? current-path)` **before** the first `(render!)`.
     - If unauthenticated and on a protected route:
       1. Record `:auth/return-to current-path` in the Fulcro state atom.
       2. Replace the browser URL to `/login` via `(.replaceState js/window.history nil "" "/login")`.
       3. Synchronize the Fulcro App DB router state to `:route/login`.
     - If authenticated:
       1. Synchronize the current route.
       2. Trigger `(fetch-user-session!)`.
   - **Runtime phase (Statecharts)**: Once booted, `auth-routing-chart` governs all subsequent state transitions:
     - Navigation via `navigate!` sends `:event/navigate {:path path}`.
     - Login completion sends `:event/login-success {:return-to return-to}`.
     - Logout clicks send `:event/logout`.
     - 401 token refresh failures in `transit.cljs` dispatch `:event/auth-failure`.

2. **Decoupling via Injected Callbacks (`:extra-env`)**:
   `auth_statechart.cljs` must remain completely decoupled from `core.cljs` (to prevent circular namespace dependencies). All side-effects are passed into `install-fulcro-statecharts!` via `:extra-env`:
   - `:clear-tokens-fn` -> removes access/refresh tokens from `localStorage`.
   - `:redirect-fn` -> mutates history and synchronizes router state.
   - `:sync-route-fn` -> updates `:route` and `:root-router/by-id` in App DB.
   - `:fetch-session-fn` -> loads user profile and permissions from `/api/query`.
   - `:fetch-page-data-fn` -> triggers page-specific queries (e.g. `fetch-workforce-chart!`).

3. **Return-To Deep Linking**:
   When an unauthorized attempt is intercepted (e.g. `/org-chart` or `/dept-dashboard?unit-id=123`):
   - Store the attempted path in `:auth/return-to`.
   - After authentication succeeds, retrieve `:auth/return-to` and navigate directly there rather than defaulting to `/`.

---

## 4. Key Files and Responsibilities

1. **`components/frontend/src/cljs/com/ozimos/workforce/frontend/auth_statechart.cljs`**:
   - Declares the statechart machine `auth-routing-chart`.
   - States:
     - `:state/checking-auth` (boot)
     - `:state/unauthenticated` (handles public routes, redirects protected attempts to `/login`)
     - `:state/authenticated` (handles protected routes, loads session and page data, redirects `/login` attempts back to `/`)

2. **`components/frontend/src/cljs/com/ozimos/workforce/frontend/core.cljs`**:
   - Configures headless Fulcro `app-inst` and Replicant rendering.
   - Houses `current-path-route` (both 0-arity and 1-arity).
   - Houses `sync-route-state!` which normalizes router targets into `[:root-router/by-id :main-router]`.
   - In `init`: executes synchronous boot guard, installs statecharts with `:extra-env`, starts the session, and attaches the Replicant render watch.

3. **`components/frontend/src/cljs/com/ozimos/workforce/frontend/transit.cljs`**:
   - Intercepts 401 status responses from `/api/query` and `/api/eql`.
   - If token refresh fails, calls the registered callback to trigger `:event/auth-failure` on the statechart instead of performing a hard browser reload.

4. **`components/frontend/src/cljs/com/ozimos/workforce/frontend/ui/root_replicant.cljs`**:
   - Defines `RootReplicant` and `MainRouter`.
   - Dynamically renders the target component based on `(:route props)`.

---

## 5. Verification Checklist for the Taking-Over LLM

Before declaring the task complete:
1. **Code Quality**:
   - Run `bb lint` (ensure 0 errors).
   - Run `bb fe-test` (ensure all 108+ frontend unit tests pass).
2. **Runtime Verification**:
   - Start the REPL with `bb repl` (or inspect running `task-22335`).
   - Check the running Jetty port from log (`JETTY_DEV_PORT`, e.g. `65492`).
   - In an incognito browser window (or `curl` / headless browser) with no `access-token` in `localStorage`:
     - Access `http://localhost:<PORT>/org-chart`.
     - Confirm URL immediately updates to `/login` and renders the Login view.
     - Enter valid credentials (`alice@acme.com` / `P@ssword123`).
     - Confirm automatic redirection back to `/org-chart` and successful rendering of the workforce hierarchy.

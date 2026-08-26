# Spike Plan: Integrating Replicant Rendering with Fulcro

## 1. Executive Summary

This spike investigates replacing **React DOM** with **[Replicant](https://github.com/cjohansen/replicant)** as the primary rendering engine in our ClojureScript frontend, while retaining **Fulcro** for data management (normalized client graph DB, EQL query normalization, `df/load!`, Pathom resolvers, and Rama event-sourced backend).

The central motivation is **testing and architectural simplicity**:
- In Fulcro + React, `defsc` produces React class components (opaque JavaScript object graphs) that require JSDOM, headless browser, or visual sandboxes to verify rendering behavior.
- In Replicant, view functions are **pure functions of `props -> Hiccup data`** (plain Clojure vectors and maps). Rendering assertions become pure data equality checks (`=`), runnable in milliseconds on the JVM or Node.js without any browser or DOM.

---

## 2. Hybrid Architecture Design

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Fulcro Core (Unchanged)                         │
│  • Normalized App Database (Atom)                                      │
│  • df/load! Data Fetching & Pathom EQL Resolution                      │
│  • Mutations & State Transitions (Pure Data Manipulation)              │
│  • Routing State in DB                                                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    │ (Atom Watch on State Change)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               Denormalization Layer (Fulcro Built-in)                  │
│               com.fulcrologic.fulcro.algorithms.denormalize/db->tree   │
│               (Converts normalized DB + Root Query -> Tree Map)        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    │ Plain Clojure Map (Props)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   Replicant View Components (defrc)                    │
│  • Pure Clojure functions: (fn [props] [:div ...])                     │
│  • Preserves :query and :ident in Clojure metadata                     │
│  • Event handlers are pure data: {:on {:click [::action args]}}        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    │ Hiccup Data Vector
                                    ▼
┌───────────────────────────────────┬────────────────────────────────────┐
│          Web Browser              │     Mobile (ClojureDart / Flutter) │
│     replicant.dom/render          │     Hiccup -> Flutter Widget Map   │
│   (Zero-dependency DOM diff)      │  (Direct native widget tree in VM) │
└───────────────────────────────────┴────────────────────────────────────┘
```

---

## 3. Core Component Specifications

### 3.1 `defrc` Macro
Replaces `defsc` for view components without creating React class components.

```clojure
(ns com.ozimos.workforce.frontend.defrc)

(defmacro defrc
  "Defines a pure Replicant view component from props -> Hiccup.
   Attaches :query and :ident metadata so Fulcro's df/load! and
   normalization algorithms can query and target it."
  [sym opts [_ props] & body]
  `(def ~sym
     (with-meta
       (fn ~(symbol (str sym "-view")) [~props]
         ~@body)
       (merge {:component-name '~sym} ~opts))))
```

### 3.2 Global Render Bridge (`replicant_bridge.cljs`)
Replaces Fulcro's React reconciler loop with a single watch on the Fulcro app state atom.

```clojure
(ns com.ozimos.workforce.frontend.replicant-bridge
  (:require
   [replicant.dom :as r]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.components :as comp]))

(defonce ^:private root-binding (atom nil))

(defn install-replicant-root!
  "Mounts Replicant as the rendering engine for the Fulcro application."
  [fulcro-app root-component mount-node]
  (reset! root-binding {:app fulcro-app
                        :component root-component
                        :node mount-node})
  (add-watch (:state fulcro-app) ::replicant-render-loop
    (fn [_ _ _ db]
      (let [query  (:query (meta root-component))
            tree   (denorm/db->tree query db db)
            hiccup (root-component tree)]
        (r/render mount-node hiccup)))))

(defn dispatch!
  "Centralized event dispatcher. Receives Replicant event vectors
   and routes them to Fulcro mutations or routing handlers."
  [fulcro-app [action-id & args :as event]]
  (comp/transact! fulcro-app [(list action-id (first args))]))
```

---

## 4. Multi-Platform Evaluation: Web vs Mobile

| Dimension | Web (Replicant) | Mobile (ClojureDart / Flutter) | Mobile (React Native / fulcro-native) |
| :--- | :--- | :--- | :--- |
| **Renderer** | `replicant.dom` (V-DOM $\to$ DOM) | Direct Flutter Widget tree | React Native views |
| **Language Runtime** | ClojureScript $\to$ JS | ClojureDart $\to$ Dart VM | ClojureScript $\to$ Hermes/JSC |
| **State Sharing** | 100% shared (.cljc) | 100% shared (.cljc / .cljd) | 100% shared (.cljc) |
| **Testing Speed** | Instant (`=` on Hiccup data) | Instant (`=` on Hiccup data) | Slow (needs JSDOM / mock React) |
| **Dependencies** | Zero JS dependencies | Zero JS dependencies | Heavy JS / NPM toolchain |

**Key Takeaway for Mobile**:
- Replicant is DOM-focused and does not run on React Native.
- However, because `defrc` view functions output **pure Hiccup data**, on Mobile with **ClojureDart**, a ~100-line mapper transforms the exact same Hiccup data into Flutter Widgets (`Column`, `Row`, `Text`, `ElevatedButton`) natively with zero JS bridge overhead.

---

## 5. Spike Implementation Tasks (Estimated: ~8 hours)

> **Note: Do not start execution yet. Awaiting user kickoff.**

1. **Dependency Setup** (1h) — ✅ Completed (uses `~/.m2` not pre-seeded `m2-repo`; removed `:local-repo` from `shadow-cljs.edn:6`, deleted `m2-repo/`, fixed `.gitignore:29`)
   - Add `no.cjohansen/replicant {:mvn/version "2026.07.1"}` to `shadow-cljs.edn` and `deps.edn`.
2. **Bridge & Macro Construction** (1.5h) — ✅ Completed (`defrc` moved to `defrc.clj` spec-pure `components/frontend/src/clj/com/ozimos/workforce/frontend/defrc.clj:4`, removed `def-component`/`probe` artifacts, `replicant_bridge.cljs:14` keeps handler-table `dispatch!` API per decision)
   - Implement `defrc.clj` macro.
   - Implement `replicant_bridge.cljs` with `add-watch`, `denorm/db->tree`, and `dispatch!` (handler-table variant).
3. **Port Single Page (`OrgChart`)** (2.5h) — ✅ Completed (`components/frontend/src/cljs/com/ozimos/workforce/frontend/ui/pages/org_chart_replicant.cljs:1` via `defrc` pure hiccup; converted `dom/*` to `[:div ...]`/`[:button ...]`; handlers as `{:on {:click [::toggle-collapse id]}}` and `[::navigate "/dept-dashboard?unit-id=..."]`; UI state lifted to `defmutation toggle-collapse`/`expand-all`/`collapse-all`/`set-search-term` in Fulcro DB)
   - Create parallel namespace `com.ozimos.workforce.frontend.ui.pages.org-chart-replicant`.
   - Convert `dom/div`, `dom/button` tags to Hiccup vectors `[:div ...]`, `[:button ...]`.
   - Convert click handlers to pure data vectors: `{:on {:click [::toggle-collapse unit-id]}}`.
   - Lift UI state (e.g., `:collapsed-nodes`, `:search-term`) into Fulcro DB mutations.
4. **Headless Unit Tests (Zero Browser / Zero DOM)** (1.5h) — ✅ Completed (`components/frontend/test/cljs/com/ozimos/workforce/frontend/ui/pages/org_chart_replicant_test.cljs:1` with `replicant.string/render` + pure `=`; hierarchy, collapsed-nodes toggle, leaf `::navigate` event maps, search highlight; `defrc` metadata; 15 tests/78 assertions `npx shadow-cljs compile test` 0 failures)
   - Create `org_chart_replicant_test.cljc` asserting on:
     - Rendered hierarchy given mock unit list.
     - Child units presence/absence based on `:ui/collapsed-nodes` set.
     - Action event maps on leaf cards (`[::navigate "/dept-dashboard?unit-id=..."]`).
5. **Integration & Visual Verification** (1.5h) — ✅ Completed (host `components/frontend/src/cljs/com/ozimos/workforce/frontend/ui/pages/org_chart_replicant_host.cljs:1` mounts via `bridge/install-replicant-root!` at `/org-chart-replicant`; `ui/root.cljs:29,53,64,105` routing, `test` + `app` builds green `npx shadow-cljs compile test|app` verified; manual `bb fe-watch` + Jetty `:8080/org-chart-replicant` shows toggle/search/deep-link pure data dispatch)
   - Mount at new dev route `/org-chart-replicant`.
   - Verify interactive toggle, search filter, and deep-link navigation.

---

## 6. Success Criteria

- [x] `defrc` component renders without React DOM. — `defrc` in `defrc.clj:4` pure fn + `org_chart_replicant.cljs:1` view + `replicant.string/render` 0 DOM.
- [x] Fulcro `df/load!` populates the Replicant view through `db->tree`. — `replicant_bridge.cljs:51-58` `denorm/db->tree` over `OrgChartReplicant` query; `app/headless-synchronous-app` + `install-replicant-root!` test proves path.
- [x] State mutations (`transact!`) update the normalized DB and trigger smooth Replicant re-renders. — `replicant_bridge_test.cljs:36` + `org_chart_replicant.cljs:toggle-collapse` `swap!` via `transact!` + watch re-render; `bridge-renders-root-tree-and-rerenders-on-transact` green.
- [x] 100% of UI test assertions execute as pure data checks without requiring browser or JSDOM. — `org_chart_replicant_test.cljs:1` + `replicant_bridge_test.cljs:1` all `=` on hiccup, `replicant.string` HTML, `npx shadow-cljs compile test` 15/78 0 failures, no JSDOM/browser.

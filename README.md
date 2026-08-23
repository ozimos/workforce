# workforce

A multi-tenant workforce and organization management system built with Clojure, Red Planet Labs Rama, Polylith, Pathom 3, Fulcro, Buddy, and the `omni-auth` security engine.

## Prerequisites

- **JDK 21+** (Temurin 21 or 25 recommended)
- **Babashka** (`bb`) — task runner and dev launchpad
- **Clojure CLI** (`clojure`) — `tools.deps`
- **Node.js** (v18+) — for frontend build and SSR server

## Quick Start

```bash
# Start the dev REPL and Launchpad (nREPL + Shadow-CLJS + SSR server)
bb repl

# Or launch directly with bin/launchpad:
bb bin/launchpad +default dev +rama test
```

> **Note:** The `+default` alias defines all workspace and core components (`omni-auth/*`, `poly/*`).

Launchpad starts a JVM, boots an nREPL server (default port `4005`), compiles ClojureScript via Shadow-CLJS, starts the Node SSR server on port `3000` (proxying `/api/*` to Jetty dev port `8100`), and loads `user.clj` which:

1. Initializes **Integrant** system lifecycle (`user/go`, `user/halt`, `user/reset`)
2. Activates **clj-reload** for automatic namespace reloading
3. Automatically mounts the Rama cluster and workforce organization topologies

In your connected editor or REPL:

```clojure
(go)     ;; Start the system (Jetty on port 8100)
(halt)   ;; Stop the system
(reset)  ;; Reload changed namespaces + restart system
```

## Verification

```bash
# Verify backend API (Jetty dev port 8100)
curl http://localhost:8100/api/health
# {"status":"ok"}

# Verify SSR frontend server (port 3000)
curl http://localhost:3000/api/health
# {"status":"ok"}
```

## Testing

`workforce` provides a tiered testing setup for rapid in-REPL feedback (<0.5s) and multi-runtime verification:

| Command | Environment | Description | Speed |
|---|---|---|---|
| `bb test-fast` | Warm Dev REPL | Runs all backend test suites against active dev system | **< 0.5s** |
| `bb test-fast <ns>` | Warm Dev REPL | Runs a single test namespace | **~ 50ms** |
| `bb test-fast-clean` | Warm Dev REPL | Runs test suite on a fresh ephemeral in-memory Rama cluster | **~ 1.0s** |
| `bb fe-test` | Node.js (`shadow-cljs`) | Headless ClojureScript Fulcro unit tests | **~ 5s** |
| `bb test-all` | JVM + Node + Proxy | Full multi-tier test suites sequentially | **~ 25s** |
| `bb test` | Standalone JVM | Cold Polylith test runner (`poly test`) | **~ 35s** |

### In-REPL Testing

```clojure
;; Run all workforce test suites (30 tests, 225 assertions):
(user/test-all)

;; Run workforce organization resolvers & IPC test suites:
(user/test-ns 'com.ozimos.workforce.org.resolvers-test)
(user/test-ns 'com.ozimos.workforce.org.ipc-test)

;; Run web integration tests:
(user/test-ns 'com.ozimos.workforce.web.integration-test)

;; Run tests against an isolated ephemeral Rama cluster:
(user/test-clean)
```

## Production Build

```bash
# Build production uberjar
clojure -T:build uberjar

# Run production service
java --enable-native-access=ALL-UNNAMED -jar target/auth-service.jar
```

## Project Structure

```
workforce/
├── workspace.edn              # Polylith configuration
├── deps.edn                  # Root dependencies & aliases (:dev, :test, :+rama, :+default, :poly)
├── bb.edn                    # Babashka dev tasks & port management
├── bin/launchpad             # Development launchpad script
├── shadow-cljs.edn           # Frontend build (:app, :ssr, :test)
├── ssr-server/               # Express SSR & API reverse proxy (port 3000)
│
├── components/               # Polylith components
│   ├── org-rama/             # Rama OrgModule, depots, PStates, and Pathom 3 resolvers
│   │                         # (Organizations, Members, Invitations, Roles, Teams)
│   ├── schema/               # Malli schemas & validation
│   ├── config/               # Aero configuration loader
│   ├── rama/                 # Rama cluster lifecycle & IPC fixtures
│   ├── security/             # Buddy authentication & authorization middleware
│   ├── token/                # JWT issuance & verification (Buddy Sign)
│   ├── user-rama/            # User depot, PState & password hashing (Buddy Hashers)
│   ├── session-rama/         # Session lifecycle management & depot
│   ├── revocation-rama/      # Token revocation depot & Bloom filter PState
│   └── frontend/             # Fulcro client UI components & organization views
│
├── bases/
│   └── web/                  # Ring HTTP API, Reitit routes, Jetty 12 adapter, SSR bridge
│
├── development/
│   ├── resources/config.edn  # Dev Integrant configuration
│   └── src/clojure/user.clj  # Dev REPL entry point
│
└── projects/
    └── auth-service/         # Production deployment uberjar configuration
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Clojure 1.12 on JDK 21+ |
| Architecture | Polylith Architecture |
| Data Layer | Red Planet Labs Rama (Depots, PStates, Topologies) |
| Resolvers & Graph | Pathom 3 (EQL Attribute Resolution) |
| HTTP & Routing | Ring + Jetty 12 (Virtual Threads) + Reitit |
| Authentication | Buddy (buddy-auth, buddy-sign, buddy-hashers) |
| Schemas | Malli |
| Frontend | ClojureScript + Fulcro 3 + Shadow-CLJS |
| SSR Gateway | Node.js Express SSR proxy |
| System Lifecycle | Integrant + integrant-repl |
| Hot Reloading | clj-reload + Launchpad |
## License

MIT
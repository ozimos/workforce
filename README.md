# workforce

A modern multi-tenant workforce and organization management application built with Clojure, Rama, Fulcro, and the `omni-auth` core engine.

## Prerequisites

- **JDK 25** (Temurin 25+)
- **Babashka** (`bb`) — for the launchpad dev script
- **Clojure CLI** (`clojure`) — tools.deps

Install Babashka:

```bash
brew install babashka
```

## Quick Start

```bash
# Start the dev REPL (defaults to +default dev +rama test via launchpad)
bb repl

# Or start directly with bin/launchpad:
bb bin/launchpad --cider-nrepl +default dev +rama
```

> **Note:** The `+default` alias defines all workspace and core components (`omni-auth/*`, `poly/*`). It is required alongside `dev` for development and REPL sessions.

Launchpad starts a JVM with Java 25, boots an nREPL server (dynamic port, printed to the terminal), and loads `user.clj` which:

1. Initializes **Integrant** config via `integrant-repl`
2. Starts **Virgil** Java hot-reload watcher (watches `components/security/src/java`)
3. Initializes **clj-reload** (Clojure namespace reload on Java recompile)

You'll see output like:

```
Compiling 1 Java source files in [components/security/src/java] ...
Java hot-reloading active via Virgil + clj-reload! [components/security/src/java]
nREPL server started on port 50872 on host localhost - nrepl://localhost:50872
```

Connect your editor to the nREPL port, then in the REPL:

```clojure
(go)     ;; Start the system (Jetty on port 8080)
(halt)   ;; Stop the system
(reset)  ;; Reload changed namespaces + restart system
```

## Profiles

| Profile | Flag | User store | Use case |
|---|---|---|---|---|
| `+rama` | `--` | Rama-backed (`user`) | Full integration testing / production |

## Verification

After `(go)` in the REPL:

```bash
curl http://localhost:8080/api/health
# {"status" "ok"}
```

## Hot Reloading

### Clojure

- Edit any `.clj` file and call `(reset)` in the REPL
- `clj-reload` unloads changed namespaces and their dependents, then reloads in topological order
- Integrant halts/reinits affected components

### Java

- Edit `SecurityConfig.java` (or any `.java` file under watched dirs) and save
- **Virgil** automatically recompiles the Java source and loads the new bytecode into the JVM
- **clj-reload** then refreshes all loaded Clojure namespaces so `:import` forms pick up the new class definitions
- No REPL restart needed

### deps.edn

- Launchpad watches `deps.edn` and `deps.local.edn` for changes
- Adding/upgrading dependencies or activating aliases happens without restarting the JVM

## Polylith

This is a [Polylith](https://cldoc.org/d/com.ozimos.workforce/doc/user-poly/welcome) workspace. Use the `:poly` alias:

```bash
clojure -A:poly check
clojure -A:poly info
```

## Testing

`best_auth` provides a tiered testing architecture designed for instant in-REPL feedback (<0.5s), isolated ephemeral test clusters, and complete multi-runtime verification.

### Testing Methods & Decision Matrix

| Method | Execution Environment | State Isolation | Speed | When Best to Use |
|---|---|---|---|---|
| **`(user/test-all)`**<br>`bb test-fast` | Warm REPL | Runs against active dev state (`irs/system`) | **< 0.5s** | **Inner-loop TDD**: Run constantly while editing code or resolvers. Instant feedback with zero boot overhead. |
| **`(user/test-ns 'ns)`**<br>`bb test-fast <ns>` | Warm REPL | Runs single test namespace | **~ 50ms** | **Focused feature debugging**: Test a single component/namespace in isolation while writing new features. |
| **`(user/test-clean)`**<br>`bb test-fast-clean` | Warm REPL | Ephemeral in-memory Rama IPC cluster (auto-mounted & torn down) | **~ 1.0s** | **Clean-slate integration check**: Verifies clean database behavior without restarting the JVM or polluting dev state. |
| **`bb fe-test`** | Node.js (`shadow-cljs`) | Headless Node test runner | **~ 5s** | **Frontend validation**: Tests ClojureScript Fulcro/UI client logic and state machines. |
| **`bb test-all`** | JVM + Node + Proxy | Multi-runtime test suites | **~ 30s** | **Pre-commit / CI verification**: Runs Clojure JVM backend, Frontend CLJS, and Node SSR proxy tests sequentially. |
| **`bb test`** | Cold Polylith JVM | Isolated process (`poly test`) | **~ 40s** | Standalone Polylith component validation without an active dev REPL. |

---

### 1. In-REPL Testing (Recommended for Active Development)

Connect your editor (Calva, CIDER, Conjure, etc.) to the running nREPL server:

```clojure
;; Run all 10 backend unit, IPC, and integration test suites:
(user/test-all)

;; Run a specific test namespace:
(user/test-ns 'com.ozimos.workforce.oauth.ipc-test)

;; Run all tests against a pristine, temporary in-memory Rama cluster:
(user/test-clean)
```

---

### 2. Fast CLI Testing via Active REPL

If your development REPL is running, Babashka connects via nREPL to execute tests with zero cold-boot penalty:

```bash
# Run all backend tests instantly inside the warm REPL (< 0.5s)
bb test-fast

# Run a specific test namespace
bb test-fast com.ozimos.workforce.oauth.ipc-test

# Run tests against a fresh ephemeral Rama IPC cluster in the REPL (~ 1s)
bb test-fast-clean
```

---

### 3. Full Multi-Runtime & CI Testing

```bash
# Run frontend ClojureScript tests
bb fe-test

# Run complete multi-tier test suite (Backend JVM + Frontend CLJS + Node SSR Proxy)
bb test-all

# Run standalone backend Polylith test runner in a cold JVM
bb test
```

## Production Uberjar

```bash
clojure -T:build uberjar
java --enable-native-access=ALL-UNNAMED -jar target/auth-service.jar
```

## Project Structure

```
best_auth/
├── workspace.edn              # Polylith config
├── deps.edn                  # Root deps (dev/test/profiles/poly/build aliases)
├── bb.edn                    # Launchpad dep
├── bin/launchpad             # Dev REPL launcher (babashka)
├── plan.md                   # Architecture & milestone documentation
│
├── components/               # Polylith components (8 interfaces)
│   ├── schema/               # Malli validation schemas
│   ├── config/               # Aero-based config loading
│   ├── rama/                 # Rama cluster + AuthModule (defmodule)
│   ├── user/                 # Rama-backed user store + BCrypt
│   ├── session/              # Session lifecycle management
│   ├── revocation/           # Token revocation (OAuth2TokenValidator)
│   ├── token/                # JWT issuance + validation (Nimbus)
│   └── security/             # Spring Security filter chain (Java + Clojure)
│
├── bases/
│   └── auth-api/             # HTTP API (Ring + Jetty + Reitit + Malli)
│
├── development/
│   ├── resources/config.edn  # Dev Integrant config
│   └── src/clojure/user.clj  # REPL bridge (integrant-repl + Virgil + clj-reload)
│
└── projects/
    └── auth-service/         # Production project (uberjar target)
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Clojure 1.12.4 on JDK 25 |
| Code organization | Polylith |
| Data layer | Rama (Red Planet Labs) |
| HTTP | Ring + Jetty 12 (virtual threads) + Reitit |
| Validation | Malli |
| Security | Spring Security 6 (JWT, OAuth2 Resource Server) |
| Lifecycle | Integrant |
| Config | Aero |
| Dev workflow | Launchpad + integrant-repl + Virgil + clj-reload |
| Build | tools.build |

## License

MIT
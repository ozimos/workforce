# best_auth

A reusable Clojure authentication template built with Rama, Polylith, Spring Security, Integrant, Ring/Jetty/Reitit, Malli, and Launchpad.

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
# Start the dev REPL with the default (in-memory) profile
bb bin/launchpad  --cider-nrepl dev +default

# Or with the Rama-backed profile
bb bin/launchpad --cider-nrepl dev +rama
```

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
|---|---|---|---|
| `+default` | `--` | Atom-backed (`user-memory`) | Fast dev iteration without Rama |
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

This is a [Polylith](https://cldoc.org/d/com.ozimos.auth/doc/user-poly/welcome) workspace. Use the `:poly` alias:

```bash
clojure -A:poly check
clojure -A:poly info
```

## Testing

```bash
clojure -A:dev:+default:test
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
│   ├── user-memory/          # Atom-backed user store + BCrypt (dev profile)
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
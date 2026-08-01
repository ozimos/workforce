# best_auth — Clojure Authentication Template

## Overview

A reusable authentication application template built with:
- **Clojure** (JVM 21)
- **Rama** (Red Planet Labs) — full data layer
- **Polylith** — code organization
- **Spring Security** — JWT validation, servlet filter chain
- **Integrant** — lifecycle management (config-as-data)
- **Ring + Jetty 12 + Reitit** — HTTP server with Java 21 virtual threads
- **Malli** — data validation and route coercion
- **Launchpad + integrant-repl** — REPL-first development workflow

## Authentication Strategy

Hybrid stateless JWT with Rama-backed revocation index:

```
Incoming Request
    |
    v
1. Spring Security JWT Validation (in-memory, RSA public key)
    |
    v
2. OAuth2TokenValidator checks Rama $$revoked-tokens PState by jti
    |
    +---[ revoked ]---> 401 Unauthorized
    +---[ valid ]-----> 200 OK (pass to Ring handler)
```

Login issues a short-lived Access JWT (15 min) with a unique `jti` claim and a long-lived Refresh Token.
Logout appends a revocation event to a Rama depot; the ETL topology writes the `jti` to the `$$revoked-tokens` PState.
"Logout everywhere" revokes all active JTIs for a user via the `$$user-active-jtis` PState.

Password encoding (BCrypt) lives in the `user` component — Spring Security's `DaoAuthenticationProvider` is not used; login is handled by a custom Ring handler that verifies credentials directly via `user/matches-password?`.

## Architecture

```
                     Integrant (config.edn)
                     config-as-data, #ig/ref dependencies
                     init = leaf-first, halt = reverse order
                              |
                              v
  :rama/cluster --> :token/encoder ---+
  :token/decoder -->                  +--> :security/app-context
  :revocation/validator -------------+         |
  :user/store  :session/store               v
                                         FilterChainProxy
                                              |
                                              v
                                        :adapter/jetty
                                        (ring-jetty-adapter
                                         + virtual threads
                                         + DelegatingFilterProxy)
                                              |
                                              v
                                        Ring Handler
                                        (Reitit + Malli)
                                              |
                                              v
                                        Component interfaces
                                        (user, session, token, etc.)
                                              |
                                              v
                                        Rama PStates/Depots
```

### Request Flow

1. Jetty (virtual threads) receives HTTP request
2. `ServletContextHandler` routes to `DelegatingFilterProxy` → `FilterChainProxy`
3. `BearerTokenAuthenticationFilter` validates JWT signature via `JwtDecoder`
4. `OAuth2TokenValidator` checks Rama `$$revoked-tokens` PState by `jti`
5. On success, `SecurityContextHolder` is populated
6. Ring handler (Reitit route match) executes
7. Handler calls component interfaces (user, session, token, etc.)
8. Component interfaces query Rama PStates or append to depots
9. JSON response returned

## Polylith Workspace Structure

Top namespace: `com.ozimos.auth`
Source paths: `src/clojure` (not `src`)

```
best_auth/
├── workspace.edn
├── deps.edn                          # dev alias, :+default/:+rama profiles, :poly alias
├── bb.edn                            # Launchpad babashka deps
├── bin/launchpad                     # executable bb script
├── build.clj                          # tools.build for uberjars
│
├── components/
│   ├── schema/                        # Malli validation schemas
│   │   ├── deps.edn                   # metosin/malli
│   │   └── src/clojure/com/ozimos/auth/schema/
│   │       ├── interface.clj          # email, username, password, role schemas
│   │       └── interface/registration.clj  # register-request, login-request, etc.
│   │
│   ├── config/                        # Aero-based config loading
│   │   ├── deps.edn                   # aero, integrant
│   │   └── src/clojure/com/ozimos/auth/config/
│   │       ├── interface.clj          # load-config
│   │       └── core.clj               # Aero reader, #profile + #ig/ref resolution
│   │
│   ├── rama/                          # Rama cluster + AuthModule
│   │   ├── deps.edn                   # com.rpl/rama, rama-helpers, integrant
│   │   └── src/clojure/com/ozimos/auth/rama/
│   │       ├── interface.clj          # pstate, depot, cluster-manager, module-name
│   │       ├── module.clj             # defmodule AuthModule (depots, PStates, topologies)
│   │       └── core.clj               # ig/init-key :rama/cluster (IPC/cluster)
│   │
│   ├── user/                          # Rama-backed user store (+ BCrypt password encoding)
│   │   ├── deps.edn                   # integrant, spring-security-core (BCrypt)
│   │   └── src/clojure/com/ozimos/auth/user/
│   │       ├── interface.clj          # register!, find-by-username, find-by-id, verify!,
│   │       │                           # change-password!, encode-password, matches-password?
│   │       └── core.clj               # uses rama + schema interfaces; BCryptPasswordEncoder
│   │

│   │
│   ├── session/                       # Session management
│   │   ├── deps.edn                   # integrant
│   │   └── src/clojure/com/ozimos/auth/session/
│   │       ├── interface.clj          # create!, verify, revoke!, revoke-all!, list-for-user
│   │       └── core.clj               # uses rama interface
│   │
│   ├── revocation/                    # Token revocation check
│   │   ├── deps.edn                   # spring-security-oauth2-jose, integrant
│   │   └── src/clojure/com/ozimos/auth/revocation/
│   │       ├── interface.clj          # is-revoked?, revoke!, revoke-all-for-user!, validator
│   │       └── core.clj               # OAuth2TokenValidator<Jwt> backed by Rama PState or atom
│   │
│   ├── token/                         # JWT issuance + validation
│   │   ├── deps.edn                   # spring-security-oauth2-jose, nimbus-jose-jwt, integrant
│   │   └── src/clojure/com/ozimos/auth/token/
│   │       ├── interface.clj          # issue-access-token, issue-refresh-token, decode, rsa-key
│   │       └── core.clj               # NimbusJwtEncoder, NimbusJwtDecoder, RSAKey, ig/init-key
│   │
│   └── security/                      # Spring Security filter chain
│       ├── deps.edn                   # spring-security-web/config/oauth2-*, spring-context, integrant
│       └── src/
│           ├── java/com/ozimos/auth/security/
│           │   └── SecurityConfig.java    # @Configuration @EnableWebSecurity @Bean SecurityFilterChain
│           └── clojure/com/ozimos/auth/security/
│               ├── interface.clj      # filter-chain-proxy, application-context
│               └── core.clj           # ig/init-key :security/app-context
│
├── bases/
│   └── auth-api/                      # HTTP API entry point
│       ├── deps.edn                   # ring-jetty-adapter, reitit-ring/malli, muuntaja, spring-web
│       └── src/clojure/com/ozimos/auth/auth_api/
│           ├── main.clj              # (:gen-class) -main → load config → ig/init
│           ├── routes.clj            # reitit router with Malli coercion
│           ├── handlers.clj          # Ring handlers calling component interfaces
│           ├── middleware.clj         # wrap-authenticated (checks SecurityContext)
│           └── system.clj            # ig/init-key :adapter/jetty (ring-jetty + configurator),
│                                       # :handler/app, :handler/routes, config loading
│       └── resources/
│           └── config.edn             # Integrant config wiring (all components)
│
├── development/
│   ├── resources/config.edn          # dev-specific overrides
│   └── src/clojure/dev/
│       └── user.clj                   # integrant-repl bridge (go/halt/reset)
│
└── projects/
    └── auth-service/
        └── deps.edn                   # production deployment (all components + auth-api base)
```

## Component Dependency Graph

```
auth-api (base)
  +-> schema.interface (route coercion + validation)
  +-> user.interface (register, login, verify, encode-password, matches-password?)
  +-> session.interface (session lifecycle)
  +-> token.interface (issue/decode JWTs)
  +-> revocation.interface (revoke tokens)
  +-> security.interface (FilterChainProxy for Jetty)

user --> rama.interface (PStates/depots)
user --> schema.interface (validate inputs)
user --> BCryptPasswordEncoder (inline, not a separate component)

session --> rama.interface (PStates/depots)
revocation --> rama.interface ($$revoked-tokens PState)
token --> revocation.interface (OAuth2TokenValidator in decoder)
security --> user.interface (UserDetailsService)
security --> token.interface (JwtDecoder bean)
```

## Integrant Configuration

`config.edn` (base resources) defines the system as data:

```clojure
{:rama/cluster          {:mode :ipc :tasks 4 :threads 2}
 :token/encoder         {:rsa-key-id "auth-template-key-1"}
 :token/decoder         {:rsa-key-id "auth-template-key-1"
                         :revocation-validator #ig/ref :revocation/validator}
 :revocation/validator  {:rama #ig/ref :rama/cluster}
 :security/app-context  {:jwt-decoder #ig/ref :token/decoder
                         :user-service #ig/ref :user/store}
 :user/store            {:rama #ig/ref :rama/cluster}
 :session/store         {:rama #ig/ref :rama/cluster}
 :adapter/jetty         {:port 8080 :host "0.0.0.0"
                         :filter-chain-proxy #ig/ref :security/app-context
                         :handler #ig/ref :handler/app}
 :handler/app           {:routes #ig/ref :handler/routes}
 :handler/routes        {:user-store #ig/ref :user/store
                         :session-store #ig/ref :session/store
                         :token-encoder #ig/ref :token/encoder
                         :token-decoder #ig/ref :token/decoder
                         :revocation-validator #ig/ref :revocation/validator}}
```

Init order (leaf-first): rama → revocation → token → user → session → security → handler → jetty
Halt order (reverse): jetty → handler → security → session → user → token → revocation → rama

## Rama AuthModule

### Depots (event logs)
| Depot | Partitioning | Purpose |
|---|---|---|
| `*registration-depot` | hash-by :username | Registration events |
| `*verification-depot` | hash-by :user-id | Account verification events |
| `*password-change-depot` | hash-by :user-id | Password change events |
| `*session-depot` | hash-by :user-id | Session creation events |
| `*session-end-depot` | hash-by :session-id | Session termination events |
| `*revoke-all-depot` | hash-by :user-id | Batch revocation events |
| `*revocation-depot` | hash-by :jti | Token revocation events |

### PStates (materialized views)
| PState | Schema | Purpose |
|---|---|---|
| `$$username->id` | `{String Long}` | Unique username → user-id index |
| `$$email->id` | `{String Long}` | Unique email → user-id index |
| `$$profiles` | `{Long {:username String :pwd-hash String :email String :verified Boolean :roles (set-schema String {:subindex? true})}}` | User profiles |
| `$$sessions` | `{String {:user-id Long :jti String :expires-at Long}}` | Active sessions by session-id |
| `$$user-sessions` | `{Long (set-schema String {:subindex? true})}` | User-id → set of session-ids |
| `$$revoked-tokens` | `{String Long}` | jti → expiry timestamp |
| `$$user-active-jtis` | `{Long (set-schema String {:subindex? true})}` | User-id → set of active JTIs |

### ETL Topology (`auth` stream topology)
Processes depot events to materialize PStates:
- Registration → creates profile in `$$profiles`, unique indices in `$$username->id`/`$$email->id`
- Verification → sets `:verified true` in `$$profiles`
- Password change → updates `:pwd-hash` in `$$profiles`
- Session start → creates entry in `$$sessions` + `$$user-sessions` + `$$user-active-jtis`
- Session end → removes from `$$sessions`
- Revoke all → removes all sessions for user, revokes all JTIs
- Token revocation → adds jti to `$$revoked-tokens`

## Spring Security Integration

### Java SecurityConfig.java
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           UserDetailsService userDetailsService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
            .userDetailsService(userDetailsService)
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/auth/login", "/api/auth/register",
                                 "/api/auth/verify", "/api/auth/forgot-password",
                                 "/api/auth/refresh", "/api/auth/reset-password",
                                 "/actuator/**").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }
}
```

Note: `PasswordEncoder` is NOT a Spring bean. Password verification is handled by the custom login Ring handler via `user/matches-password?`. Spring Security only handles JWT validation + authorization.

### Integrant ownership
- Integrant constructs all Java objects (JwtDecoder, UserDetailsService) via `ig/init-key`
- Integrant registers them as singletons in `AnnotationConfigApplicationContext`
- Spring context refreshes, `SecurityConfig` auto-wires the beans, builds `SecurityFilterChain`
- Integrant extracts `FilterChainProxy` (bean named `springSecurityFilterChain`)
- `auth-api` base's `:adapter/jetty` init-key uses `ring-jetty-adapter`'s `:configurator` to inject `DelegatingFilterProxy` into the `ServletContextHandler`

### Revocation via OAuth2TokenValidator
Instead of a custom servlet filter, the revocation check is folded into `JwtDecoder`:
- `revocation/core.clj` implements `OAuth2TokenValidator<Jwt>` via `reify`
- The validator queries Rama `$$revoked-tokens` PState by `jti` (or an atom set in dev)
- `NimbusJwtDecoder` uses `DelegatingOAuth2TokenValidator` (default timestamp/issuer validators + custom revocation validator)
- Revocation check runs during JWT decoding — single-stage, no separate filter

## REPL-First Development Workflow

### Launchpad (outer layer)
- Launches JVM with Java 21, starts nREPL, watches `deps.edn`/`.env` for changes
- Hot-reloads classpath when dependencies change (no JVM restart needed)
- Invoked via `bin/launchpad` (babashka script)

### integrant-repl (inner layer)
- `(go)` — Integrant `ig/init` the entire system: Rama IPC launches, Spring context builds, Jetty starts
- `(halt)` — Integrant `ig/halt!` everything in reverse order
- `(reset)` — `tools.namespace` reloads changed namespaces, Integrant halts/reinits affected keys
- `(reset-all)` — Reload ALL namespaces + rebuild system

### Hot-reload of SecurityFilterChain
When you `(reset)` after changing security config:
1. `tools.namespace` reloads changed Clojure namespaces
2. Integrant halts `:security/app-context` (closes Spring context)
3. Integrant reinits `:security/app-context` (rebuilds Spring context, new SecurityFilterChain)
4. Integrant reinits `:adapter/jetty` (rebuilds Jetty configurator with new FilterChainProxy)
5. Ring-Jetty adapter keeps the same port — no socket unbind

## API Endpoints (Phase 1 scope)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register a new user |
| POST | `/api/auth/login` | public | Login with username/password, returns access + refresh tokens |
| POST | `/api/auth/refresh` | public | Refresh access token using refresh token |
| POST | `/api/auth/logout` | authenticated | Revoke current session's JWT |
| POST | `/api/auth/logout-everywhere` | authenticated | Revoke all sessions + tokens for user |
| POST | `/api/auth/verify` | public | Verify account with token |
| POST | `/api/auth/forgot-password` | public | Request password reset email (stubbed) |
| POST | `/api/auth/reset-password` | public | Reset password with token |
| GET | `/actuator/health` | public | Health check |

## Profiles

| Profile | User store | Use case |
|---|---|---|---|
| `+rama` | `user` (Rama-backed) | Full integration testing / production |

## Key Decisions

1. **Malli over clojure.spec** — data-driven schemas, first-class Reitit integration via `reitit-malli`
2. **Java `@Configuration` over `gen-class`** — cleaner, rarely changes, doesn't interfere with `tools.namespace` hot reloading
3. **`ring-jetty-adapter` over custom Jetty setup** — uses `:configurator` option to inject Spring Security filters into the internal `ServletContextHandler`
4. **`OAuth2TokenValidator` for revocation** — folded into `JwtDecoder`, single-stage validation, no custom filter needed
5. **Integrant owns lifecycle, Spring is a consumer** — all objects constructed by Integrant, registered as Spring singletons
6. **Rama as sole data store** — users, sessions, revocation, audit all in Rama depots/PStates
7. **`src/clojure` source paths** — separates Clojure sources from Java sources within `src/`
8. **Password encoding merged into user component** — BCrypt is a thin wrapper, not a separate component. Spring's `DaoAuthenticationProvider` is not used; login is a custom Ring handler.
9. **No `last-active` tracking** — JWT `exp` claim bounds validity. No per-request writes to Rama.

## Execution Phases

### Phase 1: Workspace Scaffold + REPL Infrastructure [DONE]
- Polylith workspace, all components/bases/projects
- `deps.edn` with `:dev`, `:+default`, `:+rama`, `:poly` aliases
- Launchpad (`bb.edn`, `bin/launchpad`)
- `integrant-repl` in `development/src/clojure/dev/user.clj`
- `config.edn` (Integrant wiring with Aero `#profile` + `#ig/ref` tags)
- `config` component (Aero-based config loading)
- Password merged into user component
- `poly check` passes (9 components, 8 interfaces)

### Phase 2: Incremental Milestones to Working `(go)` [DONE]

The system was built up in 6 incremental milestones, each ending with a working system verified via `(go)` in the REPL. All milestones complete.

#### Milestone A: Integrant + Jetty stub handler [DONE]
| What's in `config.edn` | What's stubbed | Verify |
|---|---|---|
| `:adapter/jetty`, `:handler/app`, `:handler/routes` | Handler returns `{:status 200 :body {:ok true}}` | `curl localhost:8080/` returns 200 |

Risks surfaced: Jetty 12 ee9 + JDK 21 virtual threads; `ring-jetty-adapter` `:configurator` invocable; Aero `#ig/ref` reader dispatch via `aero/reader` multimethod.

#### Milestone B: Reitit + Malli routes [DONE]
| What's in `config.edn` | What's stubbed | Verify |
|---|---|---|
| Same | Reitit routes with Malli coercion, handlers echo `:body-params` | `POST /api/auth/login` with valid body returns 200; invalid returns 422 |

Risks surfaced: Malli schema syntax (esp. `[:re ...]`); `reitit-malli` coercion setup; Muuntaja JSON negotiation.

#### Milestone C: Spring Security filter chain (stub JWT) [DONE]
| What's in `config.edn` | What's stubbed | Verify |
|---|---|---|
| Add `:security/app-context`, `:token/decoder` (stub) | Stub `JwtDecoder` (always succeeds), stub `UserDetailsService` | `GET /actuator/health` returns 200; `GET` protected returns 401 |

Risks surfaced: Spring 6.x + Jakarta Servlet + Jetty ee9 on same classpath; `DelegatingFilterProxy` actually finds the bean; `WebApplicationContext` attribute set on actual `ServletContextHandler` instance from ring-jetty-adapter.

#### Milestone E: Token issuance + revocation (in-memory atom) [DONE]
| What's in `config.edn` | What's stubbed | Verify |
|---|---|---|
| Add `:token/encoder`, real `:token/decoder`, `:revocation/validator` (atom set) | Revocation stored in atom (no Rama) | Full login → bearer-protected request → 200; logout → same token → 401 |

Risks surfaced: Nimbus JOSE key gen + RS256 sign + verify round-trip; `OAuth2TokenValidator` `reify` returns correct `OAuth2TokenValidatorResult`; JWT claim `getId` extracts `jti` correctly.

#### Milestone F: Rama IPC + AuthModule [DONE]
| What's in `config.edn` | What's stubbed | Verify |
|---|---|---|
| Swap atom revocation for Rama `$$revoked-tokens`, enable `+rama` profile | Nothing — full system | Full register → login → protected request → logout → revoked request flow end-to-end against Rama IPC |

Risks surfaced: Rama `defmodule` compiles; IPC launches; depot appends produce ack-return-values; PState subindex schemas work; `foreign-select-one` with `:pkey` directive for non-keypath-matching lookups.

### Phase 3: Development Workflow Polish [DONE]
- `(reset)` hot-reloads SecurityFilterChain without dropping port (Java CGLIB fix: `proxyBeanMethods = false`)
- Integration tests in IPC mode (register, login, refresh, logout, verify, reset-password)
- Tests runnable from both REPL (`binding [*use-fixture* false]`) and `poly test :project auth-service` (self-contained fixture)

### Working Agreement (Applies to Phases 4+)

All subsequent phases follow two non-negotiable practices:

#### Test-first development (TDD)
- **Before implementing any function or handler**, write a failing test in the relevant `ipc_test.clj` or `integration_test.clj`
- For Rama PState/depot work, write an IPC test that appends to a depot and asserts the materialized PState shape
- For HTTP handlers, write an integration test that exercises the endpoint end-to-end (request → response)
- For pure functions (e.g., scope validation, code generation), write a unit test first
- Only after tests fail with the expected "not implemented" error, implement the code to make them pass
- Run `bb test` after each phase to verify all tests pass; never commit with failing tests

#### REPL exploration for uncertainty
- **When unsure how a Rama macro, Java interop method, or library function behaves**, experiment in the running REPL before writing code
- Use the `?<-` macro to evaluate and pretty-print results inline (per `AGENTS.md`)
- For Rama PState schema questions: spin up `(user/go)`, append test data to a depot, then `foreign-select-one` to inspect the materialized shape
- For Spring Security Java interop: evaluate `(.someMethod obj args)` in the REPL to confirm return types before committing to a code path
- For malli schema questions: `(m/validate my-schema sample-data)` in the REPL before wiring into routes
- Never guess at API behavior — confirm in the REPL first; this avoids debug cycles later

### Phase 4: Deferred Feature Work [DONE]
Polish outstanding items deferred from Phase 1-3 reviews.

#### 4.1 Password reset — replace file-based stub
- **Tests first**: write integration tests for the full forgot-password → reset-password flow before any implementation
  - Test: forgot-password with valid email records a token (assert via Rama PState read)
  - Test: forgot-password with unknown email returns 200 (no enumeration)
  - Test: reset-password with valid token + new password updates pwd-hash
  - Test: reset-password with valid token twice fails second time (one-time use)
  - Test: reset-password with expired token fails
  - Test: reset-password with unknown token fails
- **Implementation**:
  - Add `*reset-token-depot` (hash-by :token) and `$$reset-tokens {String {:user-id Long :expires-at Long}}` PState to AuthModule
  - Topology: append to depot → materialize token → user-id + expiry into PState
  - On reset: foreign-select-one to validate, foreign-append! a PasswordChange event, then clear the token entry
  - Token: `random-uuid` string, TTL 15 minutes
- **REPL checkpoint**: before wiring, append a test event to `*reset-token-depot` in the REPL and `foreign-select-one` from `$$reset-tokens` to confirm materialized shape matches expectations

#### 4.2 Verify endpoint — error handling
- **Test first**: integration test that POST `/api/auth/verify` with non-numeric `user-id` returns 400 (not 500)
- **Implementation**: wrap `Long/parseLong` in `verify` handler with try-catch, return 400 on parse failure
- Consider extracting a shared `parse-user-id` helper if pattern repeats

#### 4.3 Consolidate Long/parseLong patterns
- Audit all `Long/parseLong` usages across handlers (get-auth-user, refresh, verify)
- **Test first**: write tests for malformed subject in each handler path
- **Implementation**: extract `parse-user-id` helper in handlers.clj, returns nil on failure; all callers handle nil explicitly

### Phase 5: Frontend Scaffold [DONE]
Set up the CLJS/Fulcro frontend build pipeline within the Polylith workspace.

#### 5.1 Polylith CLJS dialect [DONE]
- Add `:dialects ["clj" "cljs"]` to `workspace.edn`
- Verify `poly check` passes with both dialects

#### 5.2 Frontend component [DONE]
- Create `components/frontend/` with:
  - `deps.edn` (fulcro, com.rpl/rama, shadow-cljs deps)
  - `src/cljs/com/ozimos/auth/frontend/` — CLJS source tree
  - `test/cljs/` — CLJS test tree
- Register in `workspace.edn` as component

#### 5.3 shadow-cljs + package.json [DONE]
- Create `shadow-cljs.edn` at root:
  - `:builds {:app {:target :browser
                    :output-dir "bases/auth-api/resources/public/js"
                    :modules {:main {:entries [frontend.core]}}}}`
  - Module paths reference the CLJS source via Polylith settings
- Create `package.json` at root with `shadow-cljs` dependency
- Verify `npx shadow-cljs compile app` produces JS in output dir

#### 5.4 Static file serving [DONE]
- Add `resources/public/` to `bases/auth-api` with `index.html`
- Add Ring `wrap-resource` or Reitit route to serve `/js/*`, `/index.html`
- Update Integrant config: `:static/resources` key or inline in `:adapter/jetty`
- Verify `curl localhost:8080/index.html` returns the HTML

#### 5.5 Pathom query endpoint stub [DONE]
- Add `POST /api/query` route in auth-api (Pathom resolver + mutation handler)
- Stub: returns `{:ok true}` for any valid Pathom query
- Update Integrant config with `:handler/pathom` key
- This route handles all future app queries (not auth)

#### 5.6 REPL checkpoint
- Start shadow-cljs watch mode: `npx shadow-cljs watch app`
- Connect CLJS REPL via `shadow-connect` from the Clojure REPL
- Verify Fulcro can render a stub component served from auth-api

### Phase 6: Fulcro Auth Pages [DONE]
Build login, register, forgot/reset-password, and verify pages using Fulcro + REST remote for auth endpoints.

#### 6.1 Fulcro application skeleton
- Install Fulcro + routing deps in `package.json` (fulcro, fulcro-css, etc.)
- Create `frontend/core.cljs` — Fulcro application mount with router
- Create `frontend/ui/root.cljs` — root UI with route-based rendering
- Verify: page renders basic "Welcome" text in the browser

#### 6.2 REST remote for auth
- Create `frontend/remote.cljs` — Fulcro REST remote targeting `POST /api/auth/*`
- Token interceptor: attach `Authorization: Bearer <jwt>` from localStorage
- Error interceptor: handle 401 by redirecting to login
- Auth events store in localStorage (access-token, refresh-token)

#### 6.3 Login page
- Form: username + password fields, submit button
- Mutation: `POST /api/auth/login` via REST remote
- On success: store access/refresh tokens in localStorage, redirect to home
- On error: display error message
- Route: `/login`

#### 6.4 Register page
- Form: email, username, password, confirm-password fields
- Mutation: `POST /api/auth/register` via REST remote
- On success: redirect to "please verify your email" page
- On error: display server validation errors (409 for duplicate, 422 for invalid)
- Route: `/register`

#### 6.5 Forgot password page
- Form: email field only
- Mutation: `POST /api/auth/forgot-password`
- On success: display confirmation message ("check your email")
- Always returns 200 (no user enumeration)
- Route: `/forgot-password`

#### 6.6 Reset password page
- Extract token from URL query param `?token=...`
- Form: new password + confirm password fields
- Mutation: `POST /api/auth/reset-password`
- On success: display success + link to login
- On error: expired/invalid token → error message
- Route: `/reset-password?token=...`

#### 6.7 Verify account page
- Extract token from URL query param `?token=...`
- On mount: `POST /api/auth/verify` with the token
- On success: display "Account verified!" + link to login
- On error: invalid/expired token → error message
- Route: `/verify-account?token=...`

#### 6.8 Token management
- On app mount: check localStorage for existing JWT
- If valid (not expired): set in Fulcro app state as `:auth/token`
- If expired: attempt refresh via `POST /api/auth/refresh`
- If refresh fails: clear tokens, redirect to login
- Logout: clear localStorage, redirect to login

#### 6.9 Navigation + layout
- App shell with nav bar: conditional links (logged-in vs logged-out)
- Logged-out: Login, Register
- Logged-in: Logout
- Loading state: spinner while auth status resolves
- Use Fulcro CSS-in-JS or plain CSS imported in `index.html`

#### 6.10 Profile page — username update [DONE]
Allow authenticated users to change their display username from a dedicated `/profile` page.
- **Rama module**: `UsernameChange` record, `*username-change-depot` (hash-by :user-id), dataflow with `|hash` partition-switching for uniqueness check against `$$username->id`, then atomic update of `$$username->id` and `$$profiles`
- **Schema**: `update-username-request` (`:new-username`) and `update-username-response` (`:username`) malli schemas
- **User component**: `update-username!` with structured validation (non-throwing, returns `[ok? result]`)
- **Pathom mutation**: `user/update-username` in pathom `core.clj` (registered in registry)
- **Frontend**: `Profile` component (`profile.cljs`) with current-username display, new-username form, loading state, success/error messages; uses Pathom `/api/query` endpoint with EQL mutation
- **Frontend routing**: `/profile` route in `root.cljs` (`current-page`, `route-for-page`, `profile-factory`, `case` clause)
- **Frontend SSR**: page-title and page-description for `/profile` in `ssr.cljs`
- **NavBar**: Single user tab in `nav.cljs` — inline SVG user icon (heroicons outline) + username from `localStorage`, linked to `/profile`
- **Tests**: Rama IPC (`username-change-test`), user IPC (`update-username-test`), integration (`username-update-test` via Pathom mutation) — all passing

### Phase 7: MFA (TOTP, Passkeys/WebAuthn, Backup Codes)
Multi-factor authentication with step-up challenges.

#### 7.1 TOTP (RFC 6238) — primary MFA [DONE]
- **Component**: Created `components/mfa` with RFC 4648 Base32 encoding/decoding, RFC 6238 TOTP computation (6-digit, 30s step, HMAC-SHA1, clock drift tolerance [-1, 0, +1]), `otpauth://` QR URI generation, AES-GCM secret encryption at rest, and 10 single-use BCrypt hashed recovery backup codes.
- **Rama Depots & PStates**:
  - `*mfa-setup-depot`, `*mfa-disable-depot`, `*mfa-consume-backup-code-depot`
  - `$$mfa-secrets {Long String}` (encrypted Base32 secret at rest)
  - `$$mfa-enabled {Long Boolean}` (MFA status flag)
  - `$$mfa-backup-codes {Long (set-schema String {:subindex? true})}` (hashed single-use backup codes)
- **Token Component**: Added `issue-mfa-challenge-token` (5-minute TTL challenge token with `"type" "mfa-challenge"`).
- **API Endpoints**:
  - `POST /api/auth/login`: When MFA is enabled, returns `200` with `{:mfa-required true :mfa-token challenge-token}`.
  - `POST /api/auth/mfa/setup`: Generates secret, QR URI, and 10 recovery backup codes.
  - `POST /api/auth/mfa/verify-setup`: Verifies 6-digit TOTP code and activates MFA.
  - `POST /api/auth/mfa/login`: Validates 2FA challenge token + TOTP or single-use backup code to issue final JWT tokens.
  - `POST /api/auth/mfa/disable`: Validates TOTP/backup code and disables MFA.
- **Tests & Verification**: Unit tests (`com.ozimos.auth.mfa.core-test`), Rama IPC tests, and full E2E HTTP integration tests (`totp-mfa-integration-test`) passing via `bb test`.

#### 7.2 WebAuthn / Passkeys — public-key credentials [DONE]
- **Component**: Created `components/webauthn` wrapping Yubico `com.yubico/webauthn-server-core:2.9.0` with `RelyingParty` configuration, `CredentialRepository` interop, registration creation options generation (`start-registration-options`), attestation verification (`finish-registration`), assertion options generation (`start-assertion-options`), and assertion verification (`finish-assertion`).
- **Rama Depots & PStates**:
  - `*webauthn-register-depot`, `*webauthn-sign-count-depot`, `*webauthn-remove-depot`
  - `$$webauthn-credentials {Long {String (fixed-keys-schema {:public-key String :sign-count Long :user-handle String :nickname String :created-at Long})}}`
- **User Helper Functions**: `register-passkey!`, `update-passkey-sign-count!`, `remove-passkey!`, `list-passkeys-for-user`.
- **API Endpoints**:
  - `POST /api/auth/passkeys/register/begin`: Generates WebAuthn registration challenge options.
  - `POST /api/auth/passkeys/register/finish`: Validates attestation response and persists credential.
  - `POST /api/auth/passkeys/authenticate/begin`: Generates WebAuthn authentication challenge options.
  - `GET /api/auth/passkeys`: Lists user's registered passkeys.
  - `DELETE /api/auth/passkeys/:credential-id`: Removes a passkey credential.
- **Tests & Verification**: Unit tests (`com.ozimos.auth.webauthn.core-test`), Rama IPC tests, and HTTP integration tests (`webauthn-integration-test`) passing via `bb test`.

#### 7.3 Backup codes — single-use recovery [DONE]
- **Implementation**:
  - `MfaRegenerateBackupCodes` record & `*mfa-regenerate-backup-codes-depot` in Rama.
  - User store helpers: `regenerate-mfa-backup-codes!` and `count-mfa-backup-codes`.
  - API endpoints:
    - `GET /api/auth/mfa/backup-codes`: Returns remaining count of valid backup codes (`{:remaining cnt}`).
    - `POST /api/auth/mfa/backup-codes`: Verifies TOTP or existing backup code, replaces `$$mfa-backup-codes` set in Rama, and returns 10 new plaintext recovery codes.
- **Tests & Verification**: IPC tests (`mfa-backup-codes-ipc-test`) and HTTP integration tests (`mfa-backup-codes-http-test`) passing cleanly via `bb test`.

#### 7.4 Security config integration [DONE]
- Standardized step-up challenge token issuance (`issue-mfa-challenge-token`) and step-up login flow across TOTP, Passkeys, and single-use recovery codes.
- Verified 50 tests (334 assertions) passing cleanly. All of Phase 7 (Multi-Factor Authentication & Advanced Auth) is now 100% complete!

### Phase 8: Federated Auth
OAuth2/OIDC social login + SAML SSO with account linking.

#### 8.1 OAuth2 / OIDC social login
- **Tests first**:
  - Integration test: mock OAuth2 provider returns code → callback exchanges for user info → JWT issued
  - IPC test: account linking — same email as existing user → links to existing account
  - IPC test: new user via OAuth → account created with `$$oauth-link` PState entry
- **Implementation**:
  - New `oauth` component using Spring Security OAuth2 Client (`spring-security-oauth2-client`)
  - Endpoints: `GET /api/auth/oauth/{provider}/authorize`, `GET /api/auth/oauth/{provider}/callback`
  - Account linking: PState `$$oauth-link {String {String Long}}` keyed by provider → provider-user-id → local-user-id
  - New depot `*oauth-link-depot` (hash-by :provider+provider-user-id) to record links
  - On callback: validate state, exchange code, fetch user info, find-or-create local user, issue JWT
- **REPL checkpoint**: before wiring Spring OAuth2 client, evaluate `OAuth2ClientConfiguration` beans in REPL — confirm registration bean shape, callback URL resolution

#### 8.2 SAML SSO
- **Tests first**:
  - Integration test: SP-initiated SAML auth → IdP mock returns assertion → JWT issued
  - IPC test: account link recorded on first SAML login
- **Implementation**:
  - New `saml` component using Spring Security SAML2 (`spring-security-saml2-service-provider`)
  - Endpoints: `GET /api/auth/saml/authenticate`, `POST /api/auth/saml/acs`
  - IdP metadata configured in `config.edn` under `:saml/idp-metadata-url`
  - Spring config: `saml2Login()` chain extension
- **REPL checkpoint**: load IdP metadata in REPL, confirm `RelyingPartyRegistration` bean construction

#### 8.3 Post-federated login JWT issuance
- After OAuth2/SAML assertion validated, exchange for app's own JWT via existing `token.interface`
- Subject = local user-id; claim `auth-method=oauth2|saml` for audit

### Phase 9: Machine-to-Machine Auth
Client credentials + device authorization grants for non-human clients.

#### 9.1 Client Credentials (RFC 6749 §4.4)
- **Tests first**:
  - IPC test: register client → `$$clients` contains secret hash + scopes
  - Integration test: `POST /api/auth/token` with valid client_credentials → JWT with `type=client-credentials`
  - Integration test: invalid client secret → 401
  - Integration test: scope-restricted resource rejects out-of-scope request
- **Implementation**:
  - New `client` component: register-client!, validate-client!, issue-client-token
  - PState `$$clients {String {:client-secret-hash String :scopes (set-schema String {:subindex? true})}}`
  - Depot `*client-register-depot` (hash-by :client-id)
  - Endpoint `POST /api/auth/token` (client_credentials grant)
  - Token issued with `type=client-credentials` claim and `scope` claim
  - Revocation validator: enforce scopes on incoming client-credentials JWT for protected resources

#### 9.2 Device Authorization Grant (RFC 8628)
- **Tests first**:
  - IPC test: `POST /device/authorize` creates device code + user code → `$$device-codes` contains both
  - Integration test: poll `POST /device/token` with pending status → returns `authorization_pending`
  - Integration test: user approves device → poll succeeds → JWT issued
  - Integration test: expired device code → returns `expired_token`
- **Implementation**:
  - Endpoints: `POST /api/auth/device/authorize`, `POST /api/auth/device/token` (polling)
  - PState `$$device-codes {String {:user-code String :status String :expires-at Long :client-id String}}`
  - User-facing approval endpoint: `POST /api/auth/device` (requires user JWT to approve)
  - Short-polling interval per RFC (5s recommended)
- **REPL checkpoint**: experiment with the polling drift tolerance — confirm Rama PState read latency under IPC mode is acceptable for 5s polling

#### 9.3 Scope enforcement
- Extend revocation validator to check `scope` claim against required scope for resource
- Per-route scope metadata in reitit route data: `:scopes ["read"]`

### Phase 10: Passwordless Auth
Magic links + OTP for password-free login.

#### 10.1 Magic Links
- **Tests first**:
  - IPC test: request magic link → `$$magic-links` contains token + user-id + expiry
  - Integration test: `GET /magic-link/verify?token=...` with valid token → JWT issued, token invalidated
  - Integration test: expired token → 400
  - Integration test: reused token → 400 (one-time use)
- **Implementation**:
  - Reuse token infrastructure from Phase 4 password reset
  - Endpoints: `POST /api/auth/magic-link/request`, `GET /api/auth/magic-link/verify?token=…`
  - PState `$$magic-links {String {:user-id Long :expires-at Long}}`
  - Token copy sent via `notification` component (see 8.3)

#### 10.2 OTP (email/SMS)
- **Tests first**:
  - IPC test: request OTP → `$$otps` contains hashed code + user-id + expiry
  - Integration test: verify with correct 6-digit code → JWT issued
  - Integration test: expired OTP → 400
  - Integration test: wrong code 3 times → rate-limited
- **Implementation**:
  - Endpoints: `POST /api/auth/otp/request`, `POST /api/auth/otp/verify`
  - PState `$$otps {String {:user-id Long :code-hash String :expires-at Long :attempts Int}}`
  - 6-digit numeric code, 5-minute expiry, max 3 attempts

#### 10.3 Notification component (email/SMS)
- New `notification` component abstraction: `send!` dispatches via configured providers
  - SMTP email provider (via `com.sun.mail`)
  - SMS provider (Twilio via REST)
- Configured via `config.edn` — providers enabled per environment
- **Test first**: unit test `send!` with mock provider — asserts provider receives message

#### 10.4 Rate limiting
- Protect magic-link and OTP endpoints from abuse
- Per-IP and per-email limits via token bucket in `$$rate-limits {String {:tokens Int :last-refill Long}}` PState
- **Test first**: integration test showing 6th request within 1 minute returns 429

### Phase 11: Single Sign-Out (SLO)
Propagate logout across all authentication mechanisms and linked SPs.

#### 11.1 SAML SLO
- **Tests first**:
  - Integration test: SP-initiated SLO → all linked SPs receive logout notification
  - Integration test: IdP-initiated SLO → local sessions revoked
- **Implementation**:
  - Endpoints: `GET /api/auth/saml/logout`, `POST /api/auth/saml/slo`
  - Use `SAMLMessageManager` (or OpenSAML) to send `LogoutRequest` / `LogoutResponse`
  - On logout: revoke all user JWT jtis via existing `revoke-all-for-user!`

#### 11.2 OIDC RP-Initiated Logout (RFC 4628)
- **Tests first**:
  - Integration test: `POST /api/auth/oauth/logout` with valid `id_token_hint` → revokes local session → returns 200
  - Integration test: invalid `id_token_hint` → 400
- **Implementation**:
  - Validate `id_token_hint` via OAuth2 client's `JwtDecoder`
  - Front-channel logout endpoint: `GET /api/auth/oauth/logout-callback` (iframe-based)
  - Back-channel logout endpoint: `POST /api/auth/oauth/backchannel-logout` (per RFC)

#### 11.3 Token revocation propagation
- When SLO fires (SAML or OIDC), invoke existing `session/revoke-all!` and `revocation/revoke-all-for-user!`
- Audit log entry: PState `$$audit-events {Long {:event String :provider String :timestamp Long}}` appended via `*audit-depot`

### Phase 12: Technical Debt Cleanup
Resolve 31 lint warnings and clean up code contracts.

#### 12.1 Unused namespaces
- `components/user/src/clojure/com/ozimos/auth/user/core.clj` — remove `com.ozimos.auth.schema.interface`
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/handlers.clj` — remove `com.ozimos.auth.schema.interface`, `com.ozimos.auth.schema.interface.registration`, `malli.core`
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/middleware.clj` — remove `clojure.walk`
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/system.clj` — remove `com.ozimos.auth.security.interface`
- `components/schema/src/clojure/com/ozimos/auth/schema/interface.clj` — remove `malli.util`
- `components/rama/src/clojure/com/ozimos/auth/rama/module.clj` — remove `com.rpl.rama.aggs`
- `development/src/clojure/user.clj` — remove `integrant.repl.state` (and `:refer`s)

#### 12.2 Unused imports
- `components/rama/src/clojure/com/ozimos/auth/rama/core.clj` — remove `InProcessCluster`
- `components/rama/test/clojure/com/ozimos/auth/rama/ipc_test.clj` — remove `InProcessCluster`, `ALL`
- `components/token/src/clojure/com/ozimos/auth/token/core.clj` — remove `SecurityContext`, `UUID`, `JwtValidators`

#### 12.3 Unused bindings
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/handlers.clj` — `e` in refresh catch (rename to `_`)
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/middleware.clj` — `deps` in fn (rename to `_`)
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/system.clj` — `routes` in handler/routes init
- `components/session/src/clojure/com/ozimos/auth/session/core.clj` — 5 `deps` bindings (rename to `_`)
- `components/token/src/clojure/com/ozimos/auth/token/core.clj` — `ctx` in `reify` (rename to `_`)
- `components/user/src/clojure/com/ozimos/auth/user/core.clj` — `deps` in find-by-username, find-by-id, verify!, change-password! (rename to `_`)
- `components/rama/src/clojure/com/ozimos/auth/rama/module.clj` — `*existing-reg-uuid` (remove)

#### 12.4 Formatting and consistency
- Run `bb fmt-fix` (standard-clj fix) across all sources
- Verify `bb lint` shows 0 errors, 0 warnings

#### 12.5 Optional refinements
- Consider `clj-kondo` `:type-checking` config drift fixes
- Consider adding `:hashp` or `:flow-storm` for better REPL debugging

### Phase 13: Project Assembly + Uberjar
Package production uberjar and verify standalone execution.

#### 13.1 Verify uberjar builds
- `clojure -T:build uber` produces `target/auth-service-0.1.0-SNAPSHOT-standalone.jar`
- Confirm Java security config (`SecurityConfig.java`) compiles into uberjar
- Confirm `config.edn` resources bundled correctly

#### 13.2 Verify uberjar runs standalone
- **Test first**: write `bb uber-test` that:
  1. Builds the uberjar (`clojure -T:build uber`)
  2. Starts it in background (`java -jar target/...standalone.jar &`)
  3. Waits for `/api/health` to return 200 (poll with retry)
  4. Runs minimal integration test (register → login → logout)
  5. Kills the process
  6. Asserts all checks pass
- Targets JVM 21; documents required Java version in README

#### 13.3 Deployment documentation
- Document deployment requirements (JVM 21, no external deps for IPC mode)
- Add `Dockerfile` (optional stretch)
- Add `POSTS.md` or section in README for prod-mode config (Rama cluster conn)

### Phase 14: CI Integration
GitHub Actions (or equivalent) pipeline as gating check.

#### 14.1 Job stages
For each PR / push to main:
1. **Checkout** + setup Java 21 + Clojure CLI + Babashka
2. `bb lint` — zero errors required, warnings allowed (until Phase 12 lands)
3. `bb fmt-check` — standard-clj check (formatting consistency)
4. `poly check` — workspace consistency (interfaces align to components)
5. `bb test` — full integration + IPC tests as gating check
6. `bb uber-test` (post Phase 13) — verify uberjar builds and runs

#### 14.2 Branch protection
- Require green CI on PR merge to `main`
- Status badge in README pointing to latest CI run
- Fail-fast on lint errors; fail-fast on test failures; allow warnings

#### 14.3 Scheduled runs
- Nightly full test run including uberjar build + smoke test
- Weekly `deps.edn` dependency freshness report (via `antq` or similar)
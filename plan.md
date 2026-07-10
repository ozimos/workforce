# best_auth — Clojure Authentication Template

## Overview

A reusable authentication application template built with:
- **Clojure** (JVM 21)
- **Rama** (Red Planet Labs) — full data layer
- **Polylith** — code organization
- **Spring Security** — JWT validation, password encoding, servlet filter chain
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

## Architecture

```
                     Integrant (config.edn)
                     config-as-data, #ig/ref dependencies
                     init = leaf-first, halt = reverse order
                              |
                              v
  :rama/cluster --> :token/encoder ---+
  :token/decoder -->                  +--> :security/app-context
  :password/encoder -->               |         |
  :revocation/validator -------------+          v
  :user/store  :session/store              FilterChainProxy
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
│   ├── user/                          # Rama-backed user store
│   │   ├── deps.edn                   # integrant
│   │   └── src/clojure/com/ozimos/auth/user/
│   │       ├── interface.clj          # register!, find-by-username, find-by-id, verify!, change-password!
│   │       └── core.clj               # uses rama + password + schema interfaces
│   │
│   ├── user-memory/                   # Atom-backed store (for +default dev profile)
│   │   ├── deps.edn
│   │   └── src/clojure/com/ozimos/auth/user/
│   │       ├── interface.clj          # SAME interface path, atom-backed
│   │       └── core.clj
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
│   │       └── core.clj               # OAuth2TokenValidator<Jwt> backed by Rama PState
│   │
│   ├── token/                         # JWT issuance + validation
│   │   ├── deps.edn                   # spring-security-oauth2-jose, nimbus-jose-jwt, integrant
│   │   └── src/clojure/com/ozimos/auth/token/
│   │       ├── interface.clj          # issue-access-token, issue-refresh-token, decode, rsa-key
│   │       └── core.clj               # NimbusJwtEncoder, NimbusJwtDecoder, RSAKey, ig/init-key
│   │
│   ├── password/                      # Password encoding
│   │   ├── deps.edn                   # spring-security-core, integrant
│   │   └── src/clojure/com/ozimos/auth/password/
│   │       ├── interface.clj          # encode, matches?
│   │       └── core.clj               # BCryptPasswordEncoder, ig/init-key :password/encoder
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
  +-> user.interface (register, login, verify)
  +-> session.interface (session lifecycle)
  +-> token.interface (issue/decode JWTs)
  +-> password.interface (encode/verify passwords)
  +-> revocation.interface (revoke tokens)
  +-> security.interface (FilterChainProxy for Jetty)

user --> rama.interface (PStates/depots)
user --> password.interface (hash passwords)
user --> schema.interface (validate inputs)

session --> rama.interface (PStates/depots)
revocation --> rama.interface ($$revoked-tokens PState)
token --> revocation.interface (OAuth2TokenValidator in decoder)
security --> user.interface (UserDetailsService)
security --> token.interface (JwtDecoder bean)
security --> password.interface (PasswordEncoder bean)
```

## Integrant Configuration

`config.edn` (base resources) defines the system as data:

```clojure
{:rama/cluster          {:mode :ipc :tasks 4 :threads 2}
 :password/encoder      {:strength 12}
 :token/encoder         {:rsa-key-id "auth-template-key-1"}
 :token/decoder         {:rsa-key-id "auth-template-key-1"
                         :revocation-validator #ig/ref :revocation/validator}
 :revocation/validator  {:rama #ig/ref :rama/cluster}
 :security/app-context  {:jwt-decoder #ig/ref :token/decoder
                         :user-service #ig/ref :user/store
                         :password-encoder #ig/ref :password/encoder}
 :user/store            {:rama #ig/ref :rama/cluster}
 :session/store         {:rama #ig/ref :rama/cluster}
 :adapter/jetty         {:port 8080 :host "0.0.0.0"
                         :filter-chain-proxy #ig/ref :security/app-context
                         :handler #ig/ref :handler/app}
 :handler/app           {:routes #ig/ref :handler/routes}
 :handler/routes        {:user-store #ig/ref :user/store
                         :session-store #ig/ref :session/store
                         :password-encoder #ig/ref :password/encoder
                         :token-encoder #ig/ref :token/encoder
                         :token-decoder #ig/ref :token/decoder
                         :revocation-validator #ig/ref :revocation/validator}}
```

Init order (leaf-first): rama → password → revocation → token → user → session → security → handler → jetty
Halt order (reverse): jetty → handler → security → session → user → token → revocation → password → rama

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
                                           UserDetailsService userDetailsService,
                                           PasswordEncoder passwordEncoder) throws Exception {
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

### Integrant ownership
- Integrant constructs all Java objects (JwtDecoder, PasswordEncoder, UserDetailsService) via `ig/init-key`
- Integrant registers them as singletons in `AnnotationConfigApplicationContext`
- Spring context refreshes, `SecurityConfig` auto-wires the beans, builds `SecurityFilterChain`
- Integrant extracts `FilterChainProxy` (bean named `springSecurityFilterChain`)
- `auth-api` base's `:adapter/jetty` init-key uses `ring-jetty-adapter`'s `:configurator` to inject `DelegatingFilterProxy` into the `ServletContextHandler`

### Revocation via OAuth2TokenValidator
Instead of a custom servlet filter, the revocation check is folded into `JwtDecoder`:
- `revocation/core.clj` implements `OAuth2TokenValidator<Jwt>` via `reify`
- The validator queries Rama `$$revoked-tokens` PState by `jti`
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
|---|---|---|
| `+default` | `user-memory` (atom-backed) | Fast dev iteration without Rama |
| `+rama` | `user` (Rama-backed) | Full integration testing / production |

## Key Decisions

1. **Malli over clojure.spec** — data-driven schemas, first-class Reitit integration via `reitit-malli`
2. **Java `@Configuration` over `gen-class`** — cleaner, rarely changes, doesn't interfere with `tools.namespace` hot reloading
3. **`ring-jetty-adapter` over custom Jetty setup** — uses `:configurator` option to inject Spring Security filters into the internal `ServletContextHandler`
4. **`OAuth2TokenValidator` for revocation** — folded into `JwtDecoder`, single-stage validation, no custom filter needed
5. **Integrant owns lifecycle, Spring is a consumer** — all objects constructed by Integrant, registered as Spring singletons
6. **Rama as sole data store** — users, sessions, revocation, audit all in Rama depots/PStates
7. **`src/clojure` source paths** — separates Clojure sources from Java sources within `src/`

## Execution Phases

### Phase 1: Workspace Scaffold + REPL Infrastructure [DONE]
- Polylith workspace, all components/bases/projects
- `deps.edn` with `:dev`, `:+default`, `:+rama`, `:poly` aliases
- Launchpad (`bb.edn`, `bin/launchpad`)
- `integrant-repl` in `development/src/clojure/dev/user.clj`
- `config.edn` (Integrant wiring with Aero `#profile` + `#ig/ref` tags)
- `config` component (Aero-based config loading)
- `poly check` passes

### Phase 2: Rama AuthModule + IPC Testing [NEXT]
- Implement `rama/module.clj` — AuthModule with all depots, PStates, stream topology
- Implement `rama/core.clj` — IPC setup for dev, cluster manager for prod
- Test via REPL: launch module, append events, query PStates

### Phase 3: Password + Token + Revocation
- BCryptPasswordEncoder wrapper (`password/core.clj`)
- NimbusJwtEncoder/Decoder with RSAKey (`token/core.clj`)
- `OAuth2TokenValidator` checking Rama `$$revoked-tokens` (`revocation/core.clj`)
- Test via REPL: encode/match passwords, issue/decode JWTs, validate revocation

### Phase 4: Spring Security Component
- Java `SecurityConfig.java` (already scaffolded)
- Integrant `init-key :security/app-context` (register beans, refresh, extract FilterChainProxy)
- `UserDetailsService` via `reify` (backed by user interface)
- Test via REPL: build context, verify FilterChainProxy bean

### Phase 5: User + Session Components
- Rama-backed user operations (`user/core.clj`)
- Session management (`session/core.clj`)
- `user-memory` atom impl (`user-memory/core.clj`)
- Test via REPL: register → login → session → revocation flow

### Phase 6: HTTP Base
- Reitit routes with Malli coercion (`routes.clj`)
- Ring handlers calling component interfaces (`handlers.clj`)
- `ring-jetty-adapter` with `:configurator` for Spring Security filters (`system.clj`)
- Virtual thread pool
- Test via REPL: `(go)` starts server, HTTP client hits endpoints

### Phase 7: Development Workflow Polish
- `(reset)` hot-reloads SecurityFilterChain without dropping port
- Integration tests in IPC mode (register, login, refresh, logout, verify, reset-password)

### Phase 8: Project Assembly + Uberjar
- `auth-service` project `deps.edn` (production)
- `build.clj` for uberjar packaging
- Verify: uberjar runs standalone

## Future Phases (out of scope for Phase 1)

- MFA (TOTP, Passkeys/WebAuthn, backup codes)
- Federated auth (OAuth2/OIDC social login, SAML SSO)
- Machine-to-machine (client credentials, device authorization)
- Passwordless (magic links, OTP)
- MFA step-up challenges
- Single Sign-Out (SLO)
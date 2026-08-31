# workforce & best_auth — Architecture & Roadmap Plan

## 1. Executive Summary & Core Stack

**workforce** is a high-performance, multi-tenant workforce and organization management system paired with the **omni-auth** security engine, built entirely on:
- **Clojure 1.12** on **JDK 21+** (leveraging Java Virtual Threads / Project Loom).
- **Red Planet Labs Rama** — primary data layer, event sourcing engine, stream topologies, and materialized partitioned PStates (replacing traditional RDBMS/SQL).
- **Polylith Architecture** — clean component boundaries and testable Lego-brick interfaces.
- **Buddy** (`buddy-auth`, `buddy-sign`, `buddy-hashers`) — JWT issuance, RS256 token verification, password hashing (Argon2 / BCrypt), and RBAC middleware.
- **Replicant + Fulcro DB** — high-performance declarative UI rendering (zero React DOM overhead) backed by Fulcro normalized client graph state, denormalized EQL queries, and `defmutation`s.
- **Pathom 3** — attribute resolution engine and EQL graph processing.
- **Transactional Notification Engine** — MJML responsive templates with pure HTTP email delivery supporting presets (`:mailpit`, `:resend`, `:postmark`, `:sendgrid`).
- **Integrant + clj-reload + Launchpad** — sub-second REPL feedback loop and port orchestration.

---

## 2. Completed Architecture & Milestones

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
     └────────────┬───────────┘                                   └────────────┬───────────┘
                  │                                                            │
                  └──────────────────────────────┬─────────────────────────────┘
                                                 ▼
                                ┌─────────────────────────────────┐
                                │       Red Planet Labs Rama      │
                                │   - Module Depots (Append-Only) │
                                │   - Stream & Microbatch ETL     │
                                │   - Materialized PState Views   │
                                └─────────────────────────────────┘
```

### Key Completed Modules:
1. **Multi-Tenant Org & Hierarchy Topologies**: Recursive parent-child Org Units (Divisions, Departments, Teams) with instant tree lookups and aggregation stats (`$$unit-headcount-stats`).
2. **Headcount Requisition Workflows**: Multi-step approvals with dynamic condition evaluation, audit timelines, and SLA tracking.
3. **Pure Replicant UI Migration**: Zero React DOM runtime with Fulcro normalized client state, UI mutations, and real-time DOM reconciliation.
4. **Transactional MJML Email System**: Responsive verification, password reset, and org invitation templates delivered over HTTP Send API with local Mailpit integration.

## 3. Security & Token Architecture

The security architecture utilizes **Buddy** (`buddy-auth`, `buddy-sign`, `buddy-hashers`) and Rama:

1. **Password Hashing**: Argon2 / BCrypt via `com.ozimos.omni-auth.security.core`.
2. **Stateless JWT + Revocation Check**:
   - Access tokens (15m expiry) signed via RS256 with key ID.
   - `wrap-authentication` middleware decodes JWT and verifies `jti` against Rama `$$revoked-tokens` PState.
   - Tokens can be instantly revoked individually (`/api/auth/logout`) or across all devices (`/api/auth/logout-everywhere`).
3. **MFA & WebAuthn / Passkeys**:
   - TOTP with encrypted secrets.
   - FIDO2 / Passkeys WebAuthn credentials via COSE public key verification.
   - Single-use recovery backup codes.

## 4. REPL-First Development Workflow

- **Launchpad (Babashka)**: Automatically provisions nREPL, manages dynamic ports, spawns Mailpit, and starts Shadow-CLJS & SSR server.
- **integrant-repl + clj-reload**:
  - `(user/go)` / `(user/start-and-seed!)` — mounts Rama topologies, loads seed data, starts Jetty on virtual threads.
  - `(user/halt)` — gracefully tears down system.
  - `(user/reset)` — hot-reloads modified namespaces in < 1s.
  - `(user/test-all)` — runs all backend tests against running state (< 0.5s).

## 5. Core API & Graph Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register user & send verification email |
| POST | `/api/auth/login` | public | Authenticate credentials & return JWT pair |
| POST | `/api/auth/verify` | public | Verify account token |
| POST | `/api/auth/forgot-password` | public | Request password reset email |
| POST | `/api/auth/reset-password` | public | Reset password with token |
| POST | `/api/auth/refresh` | public | Issue new access token from refresh token |
| POST | `/api/auth/logout` | authenticated | Revoke session & active JTI |
| POST | `/api/auth/logout-everywhere` | authenticated | Revoke all active JTIs for user |
| POST | `/api/mfa/setup` | authenticated | Generate TOTP secret & QR code |
| POST | `/api/mfa/verify` | authenticated | Confirm and activate TOTP |
| POST | `/api/webauthn/register/options` | authenticated | Get WebAuthn registration options |
| POST | `/api/webauthn/register/verify` | authenticated | Register WebAuthn credential |
| POST | `/api/v1/graphql` / `/api/eql` | mixed | Pathom 3 EQL attribute & mutation endpoint |
| GET | `/api/health` | public | Health check endpoint |

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
- **Tests & Verification**: Unit tests (`com.ozimos.workforce.mfa.core-test`), Rama IPC tests, and full E2E HTTP integration tests (`totp-mfa-integration-test`) passing via `bb test`.

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
- **Tests & Verification**: Unit tests (`com.ozimos.workforce.webauthn.core-test`), Rama IPC tests, and HTTP integration tests (`webauthn-integration-test`) passing via `bb test`.

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
- `components/user/src/clojure/com/ozimos/auth/user/core.clj` — remove `com.ozimos.workforce.schema.interface`
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/handlers.clj` — remove `com.ozimos.workforce.schema.interface`, `com.ozimos.workforce.schema.interface.registration`, `malli.core`
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/middleware.clj` — remove `clojure.walk`
- `bases/auth-api/src/clojure/com/ozimos/auth/auth_api/system.clj` — remove `com.ozimos.workforce.security.interface`
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

---

## 3. Future Directions & Next Evolution

### Phase 15: Workforce Employment, Compensation, Transfers & Terminations

To support enterprise-grade headcount cost tracking, organizational changes, and historical auditing, the domain model will formalize the distinction between **Employee** (Worker Identity) and **Employment** (Temporal Position Assignment).

```
              1 : N
  [Organization] ─────────► [OrgUnit (Dept/Team)]
         │                          │
         │ 1 : N                    │ 1 : N
         ▼                          ▼
    [Employee] ─── (1 : N) ───► [Employment] ◄─── (0..1 : 1) ─── [Headcount Requisition]
  (Person / Worker)          (Assignment / Position)              (Approved Slot)
```

#### 15.1 Domain Model & Entities in Rama
- **`Employee` (Identity Entity)**:
  - Immutable worker identity in the org (`employee-id`, `user-id`, `name`, `personal-email`, `hire-date`, `status`: `:active`, `:on_leave`, `:terminated`).
  - Materialized in `$$employees {Long {:employee-id ... :status ... :current-employment-id ...}}`.
- **`Employment` (Assignment / Placement Entity)**:
  - Temporal position record representing job title, level, department unit, compensation, and start/end dates.
  - Fields: `employment-id`, `employee-id`, `org-id`, `unit-id`, `job-title`, `job-level`, `base-salary`, `bonus-target`, `currency`, `start-date`, `end-date`, `status` (`:active`, `:past`, `:scheduled`).
  - Materialized in `$$employments` and indexed by history `$$employee->employment-history`.
- **`HeadcountRequisition` (Budgeted Slot)**:
  - Tracks planned/committed cost and approval workflows. Filled requisitions link to the resulting `Employment` assignment.

#### 15.2 Event Depots & Stream Topologies
- `*employee-hire-depot`: Creates new `Employee` and initial active `Employment`. Marks linked `HeadcountRequisition` as `:filled`.
- `*employee-transfer-depot`: Handles lateral department transfers and promotions. Closes previous `Employment` (`end-date = effective-date`) and activates the new `Employment` in the target `OrgUnit`.
- `*employment-comp-revision-depot`: Records compensation/raise adjustments with effective dates.
- `*employee-terminate-depot`: Updates employee status to `:terminated`, ends active employment, updates unit headcount metrics, and triggers security token/session revocations via `omni-auth`.

#### 15.3 Tenant-Defined Custom Attribute Schemas & Financial Modifiers
Each organization can extend the standard schema with custom operational metadata and financial cost modifiers:
- **`TenantAttributeDefine` Record**:
  ```clojure
  (defrecord TenantAttributeDefine
    [org-id attribute-id target-entity label data-type cost-modifier? cost-cadence currency options required? default-value updated-at])
  ```
- **Cost Modifiers in `$$unit-cost-stats`**:
  Custom financial attributes (e.g. Health Benefit Tiers, Signing Bonuses, Relocation, Hardware Stipends) are automatically factored into the total annualized cost calculation and rolled up across the hierarchy tree:
  $$\text{Total Unit Cost} = \text{Base Payroll} + \text{Bonus Pool} + \sum \text{Custom Financial Modifiers}$$

#### 15.4 Cost Aggregations & Rollups
- **`$$unit-cost-stats`**: Pre-materialized financial and headcount metrics per Org Unit and recursively rolled up along the `$$org-hierarchy` tree:
  ```clojure
  {:total-base-payroll 1450000
   :total-planned-payroll 1800000  ;; includes open approved requisitions
   :active-headcount 8
   :open-requisitions 2
   :custom-cost-modifiers {:health-tier 126000 :signing-bonus 50000}
   :avg-tenure-months 18.4}
  ```
- **Realized vs. Planned Budget Variance**: Enables real-time variance analysis without heavy relational SQL `JOIN` operations.

### Phase 16: Disparate Data Ingestion & Strategic Headcount Decision Support

`workforce` functions as an **Aggregation & Decision Engine** above systems of record (HRIS, ATS, and Financial Models), uniting siloed data to guide hiring, budget, and offer decisions.

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

#### 16.1 Ingestion Depots & Normalized PStates
- `*hris-roster-sync-depot`: Ingests worker status and current placement from Workday/BambooHR webhooks.
  - Materializes `$$hris-roster {Long {:employee-id ... :job-level ... :comp ... :dept-id ...}}`.
- `*ats-pipeline-sync-depot`: Ingests open job requisitions, candidate interview stages, and active offers from Greenhouse/Ashby.
  - Materializes `$$ats-candidates {String {:candidate-id ... :stage ... :target-req-id ... :offer-details ...}}`.
- `*comp-benchmark-sync-depot`: Ingests market salary bands (P25, P50, P75) by role, level, and geography.
  - Materializes `$$comp-benchmarks {String {:role ... :level ... :p50-salary ... :p75-salary ...}}`.

#### 16.2 Strategic Decision Simulation Engine
- **Offer Impact & Runway Simulator**: Simulates proposed offer packages against remaining department budget and total company runway before the offer is extended in the ATS.
- **Internal Equity & Compression Detector**: Automatically flags when an incoming candidate offer exceeds the compensation of existing high-performing team members in the same job level/department.
- **Requisition Prioritization & Bottleneck Radar**: Calculates hiring velocity, time-in-stage delays across the interview funnel, and approval SLA adherence.

#### 16.3 Bi-Directional Orchestration
- Approved requisitions in `workforce` trigger automated job openings in the target ATS via outbound webhooks.
- Accepted candidate offers in the ATS trigger requisition closure and pre-onboarding in the HRIS.

### Phase 17: Schema-Driven Dynamic CSV Ingestion & Pre-Flight Validation Engine

To enable fast tenant onboarding and historical data migration for pre-existing Headcounts, Employees, and Employments:

```
  Tenant Custom Schema (Rama) ──► Dynamic CSV Template Gen ──► Client/Server Pre-Flight Validator ──► Atomic Ingestion Depot
```

#### 17.1 Dynamic CSV Template Generation
- Auto-generates downloadable CSV templates reflecting the tenant's current schema:
  - Standard headers: `first_name`, `last_name`, `email`, `hire_date`, `unit_name`, `job_title`, `job_level`, `base_salary`, `currency`.
  - Dynamic headers: auto-appends tenant-defined custom attributes (e.g. `attr_cost_center`, `attr_health_tier`, `attr_signing_bonus`).
- Endpoint: `GET /api/org/:org-id/import/template.csv?type=:employees|:headcounts`.

#### 17.2 Multi-Stage Pre-Flight Validation
- **Structural Header Verification**: Confirms mandatory columns and flags unrecognized headers with fuzzy-match suggestions.
- **Row-Level Semantic Validation**:
  - Currency & number parsing (handles symbols `$`, `€`, `,`).
  - Org unit reference check: verifies whether department/unit exists or needs automatic hierarchy creation.
  - Date format parsing (`YYYY-MM-DD`).
- **Pre-Flight Validation Response**: Returns detailed row-and-column error breakdowns before committing changes.

#### 17.3 Atomic Bulk Ingestion Depot
- Ingestion record: `(defrecord OrgBulkImport [import-id org-id imported-by entity-type rows timestamp])`.
- Stream topology creates `Employee`, `Employment`, and `HeadcountRequisition` records in bulk and immediately recalculates all hierarchy cost statistics (`$$unit-cost-stats`).

### Phase 18: Unified Integration Layer (Merge.dev / Finch / Kombo)

Following the spreadsheet import engine, `workforce` connects directly to customer information systems via unified API integrations:
- **HRIS Sync**: Continuous synchronization with Workday, BambooHR, Rippling, and Hibob for active rosters, promotions, and transfers.
- **ATS Sync**: Continuous synchronization with Greenhouse, Ashby, and Lever for active job requisitions and candidate offer stages.
- **Custom Field Mapping**: Maps remote HRIS/ATS custom fields directly into `$$tenant-attribute-definitions` without code changes.
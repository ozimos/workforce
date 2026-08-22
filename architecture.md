# Architecture Plan: Reusable Auth Core with Extensible Rama Module

## Objective
Create a separately git-versioned auth core repo `ozimos/complete-auth` that is reusable across app ideas, while allowing the hiring-and-org app to later re-introduce org domain logic into the same Rama module for performance.

## Repo Folder & Dependency Transition

### Current Layout
Monolithic repository with bare Git store and single worktree:
```text
/Users/ozimos/projects/best_auth/
├── main.git/                (bare repo)
└── main/                    (worktree: auth core + org domain mixed in components/*)
```

### Target Layout (Parent Project with Dual Worktrees)
Both repositories are housed within the `/Users/ozimos/projects/best_auth/` parent project, allowing Antigravity Desktop to manage, refactor, and test both projects in a single workspace:

```text
/Users/ozimos/projects/best_auth/
├── main.git/                (bare repo for hiring-and-org app)
├── hiring-and-org/          (worktree: app fork containing domain logic)
│   ├── components/org/      (app-specific domain components)
│   ├── bases/               (app API bases)
│   ├── projects/            (app deployable project)
│   └── deps.edn             (references complete-auth via local/root or git coord)
│
├── complete-auth.git/       (bare repo for reusable auth core)
└── complete-auth/           (worktree: reusable core auth repository)
    ├── components/          (config, schema, token, security, session-rama,
    │                         revocation-rama, mfa, webauthn, oauth, saml,
    │                         pathom, auth-ui, rama core-only, user-rama core-only)
    ├── bases/auth-api/      (generic auth API base)
    ├── projects/auth-service/
    └── deps.edn
```

### Transition Steps

1. **Rename Current Worktree to `hiring-and-org`**:
   ```bash
   cd /Users/ozimos/projects/best_auth
   git worktree move main hiring-and-org
   ```

2. **Initialize `complete-auth` Bare Repo & Worktree**:
   ```bash
   cd /Users/ozimos/projects/best_auth
   git init --bare complete-auth.git
   git worktree add -B main complete-auth
   ```

3. **Populate Core & Decouple**:
   - Seed `complete-auth/` with the reusable auth components and base from `hiring-and-org/`.
   - Strip out org domain records, depots, PStates, and resolvers from `complete-auth/`.
   - Add `RamaModuleExtension` protocol and plugin registry to `complete-auth/components/rama`.
   - Update `hiring-and-org/deps.edn` to import `complete-auth` via `{:local/root "../complete-auth"}` (local dev) or git coords (release).
   - In `hiring-and-org/`, create `components/org` implementing `RamaModuleExtension` to inject domain depots/topology into the single Rama module stream.

### Antigravity Desktop Workspace Benefits
- Opening `/Users/ozimos/projects/best_auth` in Antigravity Desktop gives unified access to both `complete-auth/` and `hiring-and-org/`.
- Cross-repository refactoring, testing (`bb test`), and verification can be performed seamlessly within the same conversation without switching workspace windows.

## Current State Analysis

### Existing AuthModule
`components/rama/src/clojure/com/ozimos/auth/rama/module.clj` defines a single Rama module `AuthModule`.

Core auth records:
- `Registration`, `Verification`, `PasswordChange`, `UsernameChange`, `SessionStart`, `SessionEnd`, `Revocation`, `RevokeAllForUser`, `ClearRevocation`, `ResetToken`, `ClearResetToken`, `MfaSetup`, `MfaDisable`, `MfaConsumeBackupCode`, `MfaRegenerateBackupCodes`, `WebAuthnRegister`, `WebAuthnUpdateSignCount`, `WebAuthnRemoveCredential`, `OAuthLink`

Org domain records currently mixed in:
- `OrgCreate`, `OrgInvite`, `OrgJoin`, `OrgSwitch`, `OrgMemberUpdate`, `OrgMemberRemove`, `InvitationAccept`

Core auth depots: `*registration-depot`, `*verification-depot`, `*password-change-depot`, `*username-change-depot`, `*session-depot`, `*session-end-depot`, `*revoke-all-depot`, `*revocation-depot`, `*clear-revocation-depot`, `*reset-token-depot`, `*clear-reset-token-depot`, `*mfa-setup-depot`, `*mfa-disable-depot`, `*mfa-consume-backup-code-depot`, `*mfa-regenerate-backup-codes-depot`, `*webauthn-register-depot`, `*webauthn-sign-count-depot`, `*webauthn-remove-depot`, `*oauth-link-depot`

Org depots mixed in:
- `*org-create-depot`, `*org-invite-depot`, `*org-join-depot`, `*org-switch-depot`, `*org-member-update-depot`, `*org-member-remove-depot`

Core auth PStates:
- `$$username->id`, `$$email->id`, `$$registration-ids`, `$$profiles`, `$$sessions`, `$$user-sessions`, `$$user-active-jtis`, `$$all-session-ids`, `$$all-revoked-jtis`, `$$revoked-tokens`, `$$reset-tokens`, `$$mfa-secrets`, `$$mfa-enabled`, `$$mfa-backup-codes`, `$$webauthn-credentials`, `$$oauth-link`

Org PStates mixed in:
- `$$orgs`, `$$org-name->id`, `$$org-create-ids`, `$$memberships`, `$$org-members`, `$$user-active-org`, `$$user-orgs`, `$$org-users`, `$$invitations`, `$$email->invitations`, `$$org-invitations`

Topology branches for org creation, invitation, join, switch, member update, member remove are present in the same `stream-topology`.

### Problem
Org domain is tightly coupled to auth core. Reuse requires extraction.

## Target Architecture

### Core Repo: `ozimos/complete-auth`

Components in core:
- `components/config`, `components/schema`, `components/token`, `components/security`, `components/session-rama`, `components/revocation-rama`, `components/mfa`, `components/webauthn`, `components/oauth`, `components/saml`, `components/pathom` core resolvers, `components/auth-ui`
- `components/rama` – core auth module only, no org code
- `components/user-rama` – core user lifecycle only, no org functions
- `bases/auth-api` – generic auth base with Integrant wiring and core routes/handlers

Extensible PStates:
- `$$profiles` – extensible map, core keys required, extensions allowed
- `$$sessions` – extensible map
- `$$user-active-jtis` – extensible map

Extensible PState schema definitions:

```clojure
;; $$profiles
(declare-pstate s $$profiles
  {Long (map-schema
         {:closed? false
          :key-schema {:required [:username :pwd-hash :email :verified :roles]}
          :value-schema {:username String
                         :pwd-hash String
                         :email String
                         :verified Boolean
                         :roles (vector-schema String)}})})

;; $$sessions
(declare-pstate s $$sessions
  {String (map-schema
           {:closed? false
            :key-schema {:required [:user-id :jti :expires-at]}
            :value-schema {:user-id Long
                           :jti String
                           :expires-at Long}})})

;; $$user-active-jtis
(declare-pstate s $$user-active-jtis
  {Long (map-schema
         {:closed? false
          :key-schema {:required []}
          :value-schema String})})
```

Core keys remain required, app extensions can add optional keys to the maps without changing core module definition.

### Plugin Registry & Lifecycle Extension for Rama Module

To allow later inclusion of org back into the same module, the core module must support modular extensions rather than a static monolithic `defmodule` body.

#### Technical Nuance: Rama Macro & Lexical Scoping
In Red Planet Labs Rama, `declare-depot`, `declare-pstate`, `source>`, `local-select>`, and `local-transform>` are **macros** that compile within the lexical context of `(defmodule ...)` or `(stream-topology setup ...)`. Depots and PStates are lexically bound module variables rather than dynamic runtime Clojure vars.

Therefore, extension modules should be defined via builder functions or a protocol that receives the module's `setup` and `topology` contexts during module definition.

#### Module Extension Protocol & Registry API:

```clojure
(ns com.ozimos.auth.rama.extension
  (:require [com.rpl.rama :as rama]))

(defprotocol RamaModuleExtension
  "Protocol for domain modules (e.g. Org, Billing) extending the core AuthModule."
  (declare-depots [this setup]
    "Declare extension depots on the Rama setup object.")
  (declare-pstates [this setup]
    "Declare extension PStates on the Rama setup object.")
  (build-topology [this setup topology]
    "Build stream topology branches for extension depots."))

(ns com.ozimos.auth.rama.registry)

(defonce ^:private *extensions (atom []))

(defn register-extension!
  "Register a RamaModuleExtension instance or map."
  [extension]
  (swap! *extensions conj extension))

(defn reset-registry!
  "Reset all registered extensions (essential for test fixtures & clean reloads)."
  []
  (reset! *extensions []))

(defn get-registered-extensions
  "Retrieve all registered extensions."
  []
  @*extensions)
```

#### Core Module Definition (`module.clj`):

```clojure
(ns com.ozimos.auth.rama.module
  (:require
    [com.ozimos.auth.rama.extension :as ext]
    [com.ozimos.auth.rama.registry :as reg]
    [com.rpl.rama :as rama]))

(defn build-auth-module
  "Constructs the AuthModule class/instance with core definitions and registered extensions."
  ([] (build-auth-module (reg/get-registered-extensions)))
  ([extensions]
   (rama/module
     [setup]
     ;; 1. Declare Core Depots
     (declare-core-depots! setup)

     ;; 2. Declare Core PStates (including extensible open maps)
     (declare-core-pstates! setup)

     ;; 3. Declare Extension Depots & PStates
     (doseq [extension extensions]
       (ext/declare-depots extension setup)
       (ext/declare-pstates extension setup))

     ;; 4. Stream Topologies
     (let [s (rama/stream-topology setup "auth-stream")]
       (declare-core-topologies! s)
       (doseq [extension extensions]
         (ext/build-topology extension setup s))))))
```

Required capabilities in plugin architecture:

1. **Depot & PState registration via Setup Context**
   - Extensions declare their own depots and PStates directly against `setup`, ensuring proper Rama partition hashing (`hash-by`) and schema registration.

2. **Topology extension via Topology Context**
   - Extensions receive the `stream-topology` instance `s` and attach `source>` processing pipelines with `local-select>`, `local-transform>`, and `ack-return>` that execute atomically in the same micro-batch loop.

3. **Extension ordering & Invariant Guarantees**
   - Core depots and PStates are declared first, establishing foundational auth state before domain extensions run.

4. **Schema compatibility**
   - Extensible PStates (`$$profiles`, `$$sessions`) use open map schemas so domain extensions can attach optional metadata without breaking core invariants.

5. **Pathom 3 / EQL Resolver Composition**
   - Core `components/pathom/src/clojure/com/ozimos/auth/pathom/core.clj` accepts optional `extra-resolvers` in `build-env`, allowing the application to register its domain resolvers seamlessly:
   ```clojure
   (defn build-env
     ([deps] (build-env deps nil nil))
     ([deps auth] (build-env deps auth nil))
     ([deps auth extra-resolvers]
      (-> (pci/register (concat core-registry extra-resolvers))
          (assoc :deps deps)
          (cond-> auth (assoc :auth auth)))))
   ```

---

### Example Extension Registration for Hiring-and-Org

App fork `ozimos/hiring-and-org` implements `RamaModuleExtension`:

```clojure
(ns com.ozimos.hiring-and-org.rama.org-extension
  (:require
    [com.ozimos.auth.rama.extension :as ext]
    [com.rpl.rama :as rama :refer :all]
    [com.rpl.rama.path :as path :refer :all]))

;; Records defined in app namespace
(defrecord OrgCreate [uuid name owner-user-id created-at])
(defrecord OrgInvite [invitation-id org-id email role invited-by created-at expires-at])
(defrecord OrgJoin [user-id invitation-id])
(defrecord OrgSwitch [user-id org-id])
(defrecord OrgMemberUpdate [org-id user-id role])
(defrecord OrgMemberRemove [org-id user-id])

(defrecord OrgExtension []
  ext/RamaModuleExtension
  (declare-depots [_ setup]
    (declare-depot setup *org-create-depot (hash-by :owner-user-id))
    (declare-depot setup *org-invite-depot (hash-by :org-id))
    (declare-depot setup *org-join-depot (hash-by :user-id))
    (declare-depot setup *org-switch-depot (hash-by :user-id))
    (declare-depot setup *org-member-update-depot (hash-by :org-id))
    (declare-depot setup *org-member-remove-depot (hash-by :org-id)))

  (declare-pstates [_ setup]
    (declare-pstate setup $$orgs {Long {:name String :owner-user-id Long :created-at Long}})
    (declare-pstate setup $$org-name->id {String Long})
    (declare-pstate setup $$memberships {Long {Long {:role String :status String :joined-at Long :invited-by Long}}})
    (declare-pstate setup $$org-members {Long (set-schema Long {:subindex? true})})
    (declare-pstate setup $$user-active-org {Long Long})
    (declare-pstate setup $$user-orgs {Long (set-schema Long {:subindex? true})})
    (declare-pstate setup $$org-users {Long (set-schema Long {:subindex? true})})
    (declare-pstate setup $$invitations {String {:org-id Long :email String :role String :invited-by Long :status String :created-at Long :expires-at Long}})
    (declare-pstate setup $$email->invitations {String (set-schema String {:subindex? true})})
    (declare-pstate setup $$org-invitations {Long (set-schema String {:subindex? true})}))

  (build-topology [_ setup s]
    (source> *org-create-depot :> {:keys [*uuid *name *owner-user-id *created-at]})
    (local-select> (keypath *name) $$org-name->id :> *existing-org-id)
    (<<if (nil? *existing-org-id)
          ;; Atomic org creation, membership, active-org assignment in same stream
          (local-select> (keypath *uuid) $$org-create-ids :> *generated-id)
          (local-transform> [(keypath *name) (termval *generated-id)] $$org-name->id)
          (local-transform> [(keypath *generated-id) (termval {:name *name :owner-user-id *owner-user-id :created-at *created-at})] $$orgs)
          (ack-return> :ok)
          (else>)
          (ack-return> :conflict))))
```

### Integrant Wiring (System-Config Driven)

To prevent test state pollution and support clean multi-system instantiation:

```clojure
;; config.edn / system.edn in hiring-and-org
{:com.ozimos.hiring-and-org.rama/org-extension {}

 :com.ozimos.auth.rama/cluster
 {:extensions [#ig/ref :com.ozimos.hiring-and-org.rama/org-extension]}}
```

---

### File Move & Decoupling Plan

#### Core Removal from `ozimos/complete-auth`:
- Remove org records, depots, PStates, and topology branches from `components/rama/src/clojure/com/ozimos/auth/rama/module.clj`
- Remove org exports from `components/rama/src/clojure/com/ozimos/auth/rama/interface.clj`
- Remove org API from `components/user-rama/src/clojure/com/ozimos/auth/user/interface.clj` and `core.clj`
- Remove org resolvers/mutations from `components/pathom/src/clojure/com/ozimos/auth/pathom/core.clj`

#### App Fork Integration in `ozimos/hiring-and-org`:
- Create domain component `components/org` (or `components/org-rama`)
- Register `OrgExtension` via Integrant system wiring
- Pass org Pathom resolvers into `pathom/build-env` via `extra-resolvers`
- Keep `owner-user-id` and `member-user-id` as simple `Long` foreign keys pointing to core `user-id`

---

## Architectural Review Findings & Recommendations

1. **Rama Macro Compilation Safety**:
   - *Finding*: `source>`, `local-select>`, and depot/pstate tokens are Rama compiler macros that require lexical context inside `(stream-topology ...)`.
   - *Recommendation*: Use the `RamaModuleExtension` protocol/builder pattern passing `setup` and `s` so the Rama compiler processes topology ASTs cleanly.

2. **Test Isolation & State Management**:
   - *Finding*: Global mutable atoms (`defonce *registry (atom [])`) leak across test namespaces and parallel test runners.
   - *Recommendation*: Wire extensions via Integrant configuration keys (`:com.ozimos.auth.rama/cluster {:extensions [...]}`) and provide `reset-registry!` in test fixtures (`test-clean` / `system_fixture`).

3. **PState Schema Extensibility**:
   - *Finding*: Core PStates (`$$profiles`, `$$sessions`) must allow supplemental domain keys without schema validation rejection.
   - *Recommendation*: Standardize on open map schemas (`{Long clojure.lang.IPersistentMap}` or open Rama map schemas) with required core keys.

4. **Pathom 3 Schema Concatenation**:
   - *Finding*: Downstream apps need to extend the EQL graph without modifying core pathom resolvers.
   - *Recommendation*: Add `extra-resolvers` support to `pathom/build-env` leveraging Pathom 3's native `(pci/register (concat core-registry extra-resolvers))`.

5. **Foreign Key Integrity**:
   - *Finding*: Multi-tenant orgs require user references without creating circular dependencies.
   - *Recommendation*: Org domain components depend on core `complete-auth` (one-way dependency), referencing `user-id` as a primitive `Long`.

---

## Next Steps
- Implement `RamaModuleExtension` protocol in core Rama component
- Convert `$$profiles`, `$$sessions`, `$$user-active-jtis` to open extensible map schemas
- Remove org code and dependencies from core auth components
- Add `extra-resolvers` parameter to `pathom/build-env`
- Provide standalone example extension test in core suite verifying modular compilation and atomic execution

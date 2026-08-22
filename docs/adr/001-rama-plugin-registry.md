# ADR 001: Rama Plugin Registry for Extensible Auth Core

Date: 2026-08-22
Status: Accepted

## Context
Auth core in `ozimos/complete-auth` needs to be reusable across app ideas while allowing apps to extend the Rama module for domain-specific data like org management. Current `AuthModule` mixes core auth and org domain code.

## Decision
Implement a runtime dynamic plugin registry for the core Rama module using a protocol-based extension model.

### Extension Protocol
```clojure
(defprotocol RamaModuleExtension
  (declare-depots [this setup])
  (declare-pstates [this setup])
  (build-topology [this setup topology]))
```

Extensions are implemented as records implementing `RamaModuleExtension` and registered via Integrant config:
`:com.ozimos.auth.rama/cluster {:extensions [#ig/ref :app/extension]}`

### Registry API
Registry lives in `components/rama` as `com.ozimos.auth.rama.registry` and provides:
* `register-extension!` – register a `RamaModuleExtension` instance
* `reset-registry!` – reset for test isolation
* `build-auth-module` – builds `AuthModule` with core definitions first, then extensions

### Extensible PStates
`$$profiles`, `$$sessions`, `$$user-active-jtis` are defined as open `map-schema` with required core keys, allowing app extensions to add optional domain keys without schema errors.

### Integration
Extensions are instantiated via Integrant and passed to `:rama/cluster` config. Core module is built at Integrant init time, after extensions are registered but before cluster start, ensuring fail-fast validation and proper Rama macro compilation context.

### Consequences
* Core remains reusable
* Apps can extend module for atomic user+domain updates
* Validation at registration time, duplicate names rejected
* No semver guarantees initially – internal repo

## Status
Accepted

# Issue tracker: beads / bd

Issues and specs for this repo are tracked in **bd / beads**, a lightweight issue tracker with first-class dependency support. Issues are chained together like beads. Use the `bd` CLI for all operations.

## Conventions

- **Create an issue**: `bd create` or `bd create-form`. Batch creation from markdown/graph JSON is supported via `bd create`.
- **Read an issue**: `bd show <id>` . Use `bd query` for filtered reads.
- **List issues**: `bd list` with filters. `bd query` supports a simple query language.
- **Comment on an issue**: `bd comment <id> --body "..."` or `bd note <id>`.
- **Apply / remove labels**: `bd label <id>` / `bd tag <id>`.
- **Close / reopen**: `bd close <id>` / `bd reopen <id>`.
- **Link dependencies**: `bd link <id> <id>` or `bd dep` for dependency management.

Database location is auto-discovered under `.beads/*.db` or via `BEADS_DIR` env var, with a global fallback `beads_global`. As of exploration, no active `.beads` directory exists – `bd where` reports no active beads workspace – initialization with `bd init` is required before write operations.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## When a skill says "publish to the issue tracker"

Create a beads issue via `bd create`.

## When a skill says "fetch the relevant ticket"

Run `bd show <id>` or `bd query` with appropriate filters.

## Wayfinding operations

Used by `/wayfinder`. The map is a single bead with child beads as tickets.

- **Map**: a bead labelled `wayfinder:map`, holding Notes / Decisions-so-far / Fog.
- **Child ticket**: a bead linked to the map via `bd link`. Labels: `wayfinder:<type>` (`research`/`prototype`/`grilling`/`task`). Once claimed, assign via `bd assign`.
- **Blocking**: beads native dependency graph via `bd dep`. A ticket is unblocked when every blocker is closed.
- **Frontier query**: list open children of the map via `bd query`, drop any with open blockers or assignee; first in map order wins.
- **Claim**: `bd assign <id> @me`.
- **Resolve**: `bd comment <id> --body "<answer>"`, then `bd close <id>`, then append a context pointer to the map's Decisions-so-far.

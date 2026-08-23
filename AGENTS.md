## Testing & REPL Execution Directives

1. **Never Kill the REPL**: Do not terminate or restart the JVM when debugging or testing. Use `clj-nrepl-eval` or the live REPL.
2. **Fast Testing Commands**:
   - **`bb test-fast`**: Runs all backend unit/IPC/integration tests in the warm REPL in **< 0.5s**.
   - **`bb test-fast <namespace>`**: Runs a single test namespace (e.g. `bb test-fast com.ozimos.workforce.oauth.ipc-test`) in **~50ms**.
   - **`bb test-fast-clean`**: Mounts an ephemeral in-memory Rama IPC cluster, runs all tests, and halts it cleanly in **~1s** without polluting dev state.
   - **`bb test-all`**: Runs the complete multi-runtime suite (JVM + Frontend CLJS + Node SSR Proxy). Use only for pre-commit verification.
   - **Avoid `bb test`** during active development (spawns a cold JVM and takes 40+ seconds).
3. **In-REPL Test Helpers**:
   - `(user/test-all)` — Runs all tests in active REPL.
   - `(user/test-ns 'ns-sym)` — Hot-reloads and tests a single namespace.
   - `(user/test-clean)` — Runs tests against an isolated ephemeral cluster.
4. **Idempotent Test Fixtures**:
   - All tests MUST generate unique randomized credentials (e.g. `(str "user-" (random-uuid) "@example.com")`). Never hardcode fixed emails or usernames, as Rama PStates persist them in memory.
5. **REPL Port Discovery**:
   - REPL port is read from `.nrepl-port` (or `.shadow-cljs/nrepl.port`, or `deps.local.edn`, or `clj-nrepl-eval --discover-ports`).
6. **Polylith Tool**:
   - Only use `clojure -M:poly` alias if the native `poly` tool is not installed on the machine.
7. **System Initialization**:
   - No need to reset the system if this is the first run after JVM start. Just use `(go)`.
   - If you modify code, run `(reset)` in the REPL (reloads all 14 components in <1s via `clj-reload` + Virgil).
8. **Temp Files**:
   - Create temp files (if needed) in the project directory and delete them after use.
9. **Never Stop shadow-cljs**:
   - Never run `shadow-cljs stop` or `pnpm exec shadow-cljs stop`. Launchpad embeds the shadow-cljs server inside the main Clojure JVM process. Stopping it terminates the embedded server and breaks frontend watching for the rest of the session.
10. **Rama REPL Inspection**:
    - Use the `?<-` macro to test Rama queries and expressions in the REPL. Docs: https://redplanetlabs.com/clojuredoc/com.rpl.rama.html
11. **`+default` Profile Alias**:
    - All workspace and core component dependencies (`com.ozimos.omni-auth/*`, `poly/*`) and base test paths reside in `:+default`. Whenever invoking Clojure CLI or Launchpad, `+default` is required alongside `dev` (e.g. `bb bin/launchpad +default dev +rama test` or `clojure -M:+default:dev`).

do not write code like this

```(or (get-in body [:data :user/update-username])
                                 (get-in body [:data 'user/update-username])
                                 (get-in body [:data "user/update-username"]))```

identify the particular path that the data actually flows in (if necessary by using the repl) and then only include that path

## Frontend / SSR topology

Jetty (:8080) is the **primary** server: serves `/api/*` and the SPA (static
`index.html` + `main.js`) via `wrap-spa` for non-API paths. The browser points
here.

The Node SSR server on :3000 (`ssr-server/server.js`) is a **validation/agent
harness only** — not the user-facing entry. It renders the Fulcro Root to an
HTML string via `react-dom/server` so non-vision LLM agents can hit
`localhost:3000/<route>` and inspect the rendered DOM (form fields, nav, etc.)
plus any `<div id="ssr-error">` divs to validate the app's structure without a
real browser. The rendered markup is injected into `<div id="app">` so agents
see the same DOM the browser would produce.

### Build tasks
- `bb fe-watch` — shadow `:app` build (client bundle served by Jetty)
- `bb fe-ssr-watch` — shadow `:ssr` build (compiles `ssr-output/ssr.js`)
- `bb ssr-start` — standalone Node SSR server on :3000

With `:ssr-server {:enabled true}` in `deps.local.edn` (default), `bb repl`
starts the Node SSR server automatically alongside the Clojure REPL and the
shadow `:app` + `:ssr` watches. All three processes are launchpad-managed:
SIGTERM cleans them up together.

### Hot-reload caveat
`ssr.cljs` edits require restarting the Node SSR server. The shadow `:ssr`
watch recompiles `ssr-output/ssr.js`, but the Node server requires the fresh
file on launch (the broken in-reload `fs.watch` was removed because it
corrupted ClojureScript protocol definitions — `ISwap.-swap! ... Atom`).

### Authenticated SSR
By default the SSR harness renders the **unauthenticated** view (matches a fresh
browser — no `localStorage` tokens). Set `SSR_AUTHENTICATED=true` when starting
the Node server to shim `localStorage` with a fake access-token so Root's
`logged-in?` returns true and NavBar renders. Useful for validating the
authenticated branch.

## Agent skills

### Issue tracker

Issues are tracked in beads / bd. See `docs/agents/issue-tracker.md`.

### Triage labels

Label vocabulary uses the five canonical triage roles. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: `CONTEXT.md` at repo root and `docs/adr/` at repo root. See `docs/agents/domain.md`.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->

<!-- BEGIN BEADS CODEX SETUP: generated by bd setup codex -->
## Beads Issue Tracker

Use Beads (`bd`) for durable task tracking in repositories that include it. Use the `beads` skill at `.agents/skills/beads/SKILL.md` (project install) or `~/.agents/skills/beads/SKILL.md` (global install) for Beads workflow guidance, then use the `bd` CLI for issue operations.

### Quick Reference

```bash
bd ready                # Find available work
bd show <id>            # View issue details
bd update <id> --claim  # Claim work
bd close <id>           # Complete work
bd prime                # Refresh Beads context
```

### Rules

- Use `bd` for all task tracking; do not create markdown TODO lists.
- Run `bd prime` when Beads context is missing or stale. Codex 0.129.0+ can load Beads context automatically through native hooks; use `/hooks` to inspect or toggle them.
- Keep persistent project memory in Beads via `bd remember`; do not create ad hoc memory files.

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.
<!-- END BEADS CODEX SETUP -->

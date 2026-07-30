Do not start a new clojure process when debugging 
use `clj-nrepl-eval -h` to evaluate clojure code in the running repl

repl port is in deps.local.edn or 4005 or  `clj-nrepl-eval --discover-ports` 
only use clojure poly alias if the poly tool is not installed on the machine

no need to reset the system if this is the first run after jvm start. just use "go"

create temp files (if needed) in project directory. you should delete them after you are done

if you kill the jvm, start a new one in the background using bb repl, then initialize with "(user/go)"

use the ?<- macro to test expressions in the repl

docs on rama clojure api can be found at https://redplanetlabs.com/clojuredoc/com.rpl.rama.html

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
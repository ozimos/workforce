(ns com.ozimos.workforce.frontend.replicant-bridge
  "Bridges Fulcro's normalized state atom to a Replicant render target.

   A single `add-watch` on the app's state atom denormalizes the root query
   via `denorm/db-\u0026gt;tree` and re-renders the root `defrc` view into the mount
   node, replacing Fulcro's React reconciler loop.

   Event handlers emitted as pure data (`{:on {:click [::action args]}}`) are
   routed through `dispatch!`. In this version of Replicant (2026.07.1),
   `*dispatch*` receives two arguments:

     (fn [event-map handler-data])

   where `event-map` is `{:replicant/dom-event <Event>, :replicant/js-event
   <Event>, :replicant/trigger :replicant.trigger/dom-event, ...}` and
   `handler-data` is the pure data value from the `:on` map (e.g.
   `[::toggle id]`).

   `install-replicant-root!` registers the dispatch table globally via
   `replicant.dom/set-dispatch!` so it is available for asynchronous DOM
   events, then sets up the add-watch render loop."
  (:require
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [replicant.dom :as r]))

(defn dispatch!
  "Builds a `replicant.core/*dispatch*` adapter over `handlers`, a table of
   `{action-keyword (fn [event-map & args])}`.

   Replicant (2026.07.1) calls the bound dispatch as
   `(event-map (*dispatch* handler-data))` — i.e. the bound fn receives the
   Replicant event map first, then the pure data handler value from `:on`.

   The adapter:
     1. Extracts the action keyword from the head of `handler-data`.
     2. Looks up the registered handler fn.
     3. Calls `(handler event-map & (rest handler-data))` so handlers can
        optionally read `:replicant/js-event` from the event map (e.g. for
        input values).

   Handlers that don't need the event may ignore the first argument with `_`."
  [handlers]
  (fn [event-map handler-data]
    (when (vector? handler-data)
      (let [handler (get handlers (first handler-data))]
        (if handler
          (apply handler event-map (rest handler-data))
          (js/console.warn "[replicant-bridge] no handler for" (pr-str handler-data)))))))

(defn install-replicant-root!
  "Mounts Replicant as the rendering engine for the given Fulcro application.

   `fulcro-app`     - a Fulcro application (its `:state` atom is watched)
   `root-component` - a `defrc` root view whose `:query` metadata drives
                      denormalization
   `mount-node`     - the render target (a DOM element by default)
   `handlers`       - `{action-keyword (fn [event-map & args])}` table for
                      `:on` data events. `event-map` carries
                      `:replicant/js-event` (the raw DOM Event) and other
                      Replicant trigger metadata.
   `renderer`       - `(fn [mount-node hiccup])`, defaults to
                      `replicant.dom/render`

   Registers the dispatch table globally (via `replicant.dom/set-dispatch!`)
   then renders once immediately and on every subsequent state change.
   Returns the watch key `::replicant-root`."
  ([fulcro-app root-component mount-node handlers]
   (install-replicant-root! fulcro-app root-component mount-node handlers r/render))
  ([fulcro-app root-component mount-node handlers renderer]
   (let [query      (:query (meta root-component))
         state-atom (:com.fulcrologic.fulcro.application/state-atom fulcro-app)
         render     (fn [db]
                      (renderer mount-node (root-component (denorm/db->tree query db db))))]
     ;; Register dispatch globally so async DOM events (fired after render)
     ;; are still routed.
     (r/set-dispatch! (dispatch! handlers))
     (render @state-atom)
     (add-watch state-atom ::replicant-root
                (fn [_atom _old _new db] (render db))))))

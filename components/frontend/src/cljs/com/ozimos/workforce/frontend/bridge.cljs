(ns com.ozimos.workforce.frontend.bridge
  "Bridges Fulcro's normalized state atom to a Replicant render target.

   A single `add-watch` on the app's state atom denormalizes the root query
   via `denorm/db->tree` and re-renders the root `defrc` view into the mount
   node, replacing Fulcro's React reconciler loop.

   Event handlers emitted as pure data (`{:on {:click [::action args]}}` or
   `{:on {:click [(my-mutation {:param 1})]}}`) are routed through `dispatch!`.
   In this version of Replicant (2026.07.1), `*dispatch*` receives two arguments:

     (fn [event-map handler-data])

   where `event-map` is `{:replicant/dom-event <Event>, :replicant/js-event
   <Event>, :replicant/trigger :replicant.trigger/dom-event, ...}` and
   `handler-data` is the pure data value from the `:on` map (e.g.
   `[::toggle id]` or mutation list `[(toggle-collapse {:id id})]`).

   `install-replicant-root!` registers the dispatch table globally via
   `replicant.dom/set-dispatch!` so it is available for asynchronous DOM
   events, then sets up the add-watch render loop."
  (:require
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.components :as comp]
   [replicant.dom :as r]))

(defn dispatch!
  "Builds a `replicant.core/*dispatch*` adapter over `handlers`, a table of
   `{action-keyword (fn [event-map & args])}` or a Fulcro `app-inst`.

   Replicant (2026.07.1) calls the bound dispatch as
   `(event-map (*dispatch* handler-data))` — i.e. the bound fn receives the
   Replicant event map first, then the pure data handler value from `:on`.

   The adapter:
     1. If `handler-data` is a vector starting with a keyword:
        - Looks up the registered handler in `handlers`.
        - If found, calls `(handler event-map & (rest handler-data))`.
     2. If `handler-data` is a vector/list containing Fulcro mutation expressions (lists):
        - Automatically executes `(comp/transact! app-inst handler-data)`."
  ([handlers]
   (dispatch! nil handlers))
  ([app-inst handlers]
   (let [dispatch-single!
         (fn [event-map action-vec]
           (cond
             (and (vector? action-vec) (keyword? (first action-vec)))
             (let [k (first action-vec)
                   handler (or (get handlers k)
                               (when (= "navigate" (name k))
                                 (get handlers :navigate)))]
               (if handler
                 (apply handler event-map (rest action-vec))
                 (js/console.warn "[bridge] no handler for" (pr-str action-vec))))

             (and (sequential? action-vec) (seq action-vec) (list? (first action-vec)) app-inst)
             (comp/transact! app-inst (vec action-vec))

             :else
             (js/console.warn "[bridge] unrecognized handler data" (pr-str action-vec))))]
     (fn [event-map handler-data]
       (cond
         ;; Standard single action vector: [::action-key & args]
         (and (vector? handler-data) (keyword? (first handler-data)))
         (dispatch-single! event-map handler-data)

         ;; Sequence of action vectors: e.g. [[:action-1] [:action-2]]
         (and (sequential? handler-data)
              (seq handler-data)
              (vector? (first handler-data))
              (keyword? (first (first handler-data))))
         (doseq [action-vec handler-data]
           (dispatch-single! event-map action-vec))

         ;; Direct Fulcro mutation transaction: [(my-mutation {:id 1})]
         (and (sequential? handler-data) (seq handler-data) (list? (first handler-data)) app-inst)
         (comp/transact! app-inst (vec handler-data))

         :else
         (js/console.warn "[bridge] unrecognized handler data" (pr-str handler-data)))))))

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

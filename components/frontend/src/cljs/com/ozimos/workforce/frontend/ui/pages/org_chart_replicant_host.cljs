(ns com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-host
  "Fulcro host for the Replicant OrgChart page.

   Mounts the pure Replicant view (`org-chart-replicant/OrgChartReplicant`)
   into a dedicated DOM node via `replicant-bridge/install-replicant-root!`,
   wiring Replicant's pure `:on` data events to Fulcro `transact!` mutations.

   This satisfies Spike Task 5: `/org-chart-replicant` dev route with interactive
   toggle/search/deep-link verification, while retaining Fulcro for data
   management (df/load!, Pathom, Rama)."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.views.org-chart :as cr]
   [goog.dom :as gdom]))

;; Demo data seeded directly into the Fulcro DB atom so db->tree can read it.
(def ^:private demo-seed-data
  {:units     {"eng"  {:unit/id "eng"  :unit/name "Engineering" :unit/division-id "ENG" :unit/dept-id "ALL"
                       :unit/parent-id nil  :unit/budget 10 :unit/filled 8 :unit/open 2 :unit/pending 0}
               "plat" {:unit/id "plat" :unit/name "Platform"    :unit/division-id "ENG" :unit/dept-id "PLAT"
                       :unit/parent-id "eng" :unit/budget 5  :unit/filled 3 :unit/open 1 :unit/pending 1}
               "mob"  {:unit/id "mob"  :unit/name "Mobile"      :unit/division-id "ENG" :unit/dept-id "MOB"
                       :unit/parent-id "eng" :unit/budget 4  :unit/filled 2 :unit/open 2 :unit/pending 0}}
   :hierarchy {nil ["eng"] "eng" ["plat" "mob"]}
   :collapsed-nodes #{}
   :search-term ""
   :loading false
   :active-org {:org/name "Demo Co"}})

(defsc OrgChartReplicantHost [this _props]
  {:query         [:loading :error :active-org :units :hierarchy :search-term :collapsed-nodes]
   :initial-state {:loading false :error nil :active-org nil :units {} :hierarchy {}
                   :search-term "" :collapsed-nodes #{}}
   :componentDidMount
   (fn [this]
     (let [app-inst   (comp/any->app this)
           state-atom (::app/state-atom app-inst)
           node       (gdom/getElement "replicant-org-chart")]
       ;; Seed demo data directly into the Fulcro DB atom so db->tree
       ;; (used by install-replicant-root!) can read it on the first render.
       (when (empty? (:units @state-atom))
         (swap! state-atom merge demo-seed-data))
       (when node
         (let [handlers
               ;; Replicant 2026.07.1 dispatches as (fn [event-map & args]).
               ;; event-map carries :replicant/js-event (raw DOM Event), etc.
               {::cr/toggle-collapse   (fn [_ id]   (comp/transact! app-inst [(cr/toggle-collapse {:id id})]))
                ::cr/expand-all        (fn [_]      (comp/transact! app-inst [(cr/expand-all {})]))
                ::cr/collapse-all      (fn [_]      (comp/transact! app-inst [(cr/collapse-all {})]))
                ::cr/set-search-term   (fn [ev]
                                         (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                           (comp/transact! app-inst [(cr/set-search-term {:value v})])))
                ::cr/navigate          (fn [_ path] (set! js/window.location.href path))
                ::cr/refresh           (fn [_]      (js/console.log "[org-chart-replicant] refresh"))
                ::cr/open-create-modal (fn [_ _id]  (js/console.log "[org-chart-replicant] open-create"))
                ::cr/open-budget-modal (fn [_ _id]  (js/console.log "[org-chart-replicant] open-budget"))
                ::cr/noop              (fn [& _]    nil)}]
           (bridge/install-replicant-root! app-inst cr/OrgChartReplicant node handlers)))))
   :componentDidUpdate
   (fn [_this _prev _snapshot]
     ;; Replicant bridge's add-watch handles re-renders automatically.
     nil)}
  (dom/div {:id "replicant-org-chart-host" :className "min-h-full"}
    (dom/div {:id "replicant-org-chart"} "Loading Replicant OrgChart…")
    (dom/p {:className "text-xs text-gray-400 mt-4 text-center"}
      "Replicant rendering via `defrc` — pure hiccup, zero React DOM. Toggle, search, and deep-link events are pure data vectors.")))

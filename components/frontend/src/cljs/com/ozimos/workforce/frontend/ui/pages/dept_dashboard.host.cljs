(ns com.ozimos.workforce.frontend.ui.pages.dept-dashboard.host
  "Host for Replicant DeptDashboard."
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard :as cr]
   [goog.dom :as gdom]))

(defsc DeptDashboardHost [_this _props]
  {:query [:loading :error :unit-id :dashboard :active-org :available-units]
   :initial-state {:loading false :error nil :unit-id "eng" :dashboard {:unit/id "eng" :unit/budget 10 :unit/filled 5 :unit/open 3 :unit/pending 2 :unit/avg-sla-ms 7200000 :unit/actors {:hiring-manager "alice"}} :active-org {:org/name "Demo Co"} :available-units [{:unit/id "eng" :unit/name "Eng"} {:unit/id "plat" :unit/name "Plat"}]}
   :componentDidMount
   (fn [this]
     (let [app (comp/any->app this)
           node (gdom/getElement "replicant-dept-dashboard")
           handlers
           {::cr/set-unit-id (fn [ev _field] (let [v (some-> ev :replicant/js-event .-target .-value)] (comp/transact! app [(cr/set-unit-id {:value (or v "")})])))
            ::cr/load (fn [_ev _id] (comp/transact! app [(cr/set-loading {:v true})]))}]
       (when node (bridge/install-replicant-root! app cr/DeptDashboard node handlers))))}
  (dom/div {:id "replicant-dept-dashboard-host" :className "min-h-full"}
    (dom/div {:id "replicant-dept-dashboard"} "Loading Replicant DeptDashboard…")
    (dom/p {:className "text-xs text-gray-400 mt-4 text-center"} "Replicant dept-dashboard via defrc.")))

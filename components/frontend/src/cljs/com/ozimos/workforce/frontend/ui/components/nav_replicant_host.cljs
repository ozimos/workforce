(ns com.ozimos.workforce.frontend.ui.components.nav-replicant-host
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant :as cr]
   [goog.dom :as gdom]))
(defsc NavReplicantHost [_this _props]
  {:query [:fetched :active-org :orgs :dropdown-open]
   :initial-state {:fetched false :active-org {:org/name "Demo Co" :org/role "ADMIN"} :orgs [{:org/id "1" :org/name "Demo Co"}] :dropdown-open false}
   :componentDidMount (fn [this] (let [app (comp/any->app this) node (gdom/getElement "replicant-nav") handlers {::cr/toggle-dropdown (fn [_] (comp/transact! app [(cr/toggle-dropdown {})])) ::cr/switch-org (fn [_ id] (js/console.log "switch" id)) ::cr/logout (fn [_] (js/console.log "logout"))}] (when node (bridge/install-replicant-root! app cr/NavBarReplicant node handlers))))}
  (dom/div {:id "replicant-nav-host"} (dom/div {:id "replicant-nav"} "Loading Nav…")))

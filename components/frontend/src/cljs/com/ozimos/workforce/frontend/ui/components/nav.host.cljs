(ns com.ozimos.workforce.frontend.ui.components.nav.host
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.components.nav :as cr]
   [goog.dom :as gdom]))
(defsc NavHost [_this _props]
  {:query [:fetched :active-org :orgs :dropdown-open]
   :initial-state {:fetched false :active-org {:org/name "Demo Co" :org/role "ADMIN"} :orgs [{:org/id "1" :org/name "Demo Co"}] :dropdown-open false}
   :componentDidMount (fn [this] (let [app (comp/any->app this) node (gdom/getElement "nav") handlers {::cr/toggle-dropdown (fn [_] (comp/transact! app [(cr/toggle-dropdown {})])) ::cr/switch-org (fn [_ id] (js/console.log "switch" id)) ::cr/logout (fn [_] (js/console.log "logout"))}] (when node (bridge/install-replicant-root! app cr/NavBar node handlers))))}
  (dom/div {:id "nav-host"} (dom/div {:id "nav"} "Loading Nav…")))

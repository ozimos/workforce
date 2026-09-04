(ns com.ozimos.workforce.frontend.ui.pages.org-dashboard.host
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard :as cr]
   [goog.dom :as gdom]))
(defsc OrgDashboardHost [_this _props]
  {:query [:loading :error-msg :active-org :orgs :members :members-loading :members-error :invite-email :invite-role :invite-loading :invite-msg]
   :initial-state {:loading false :error-msg nil :active-org {:org/name "Demo Co"} :orgs [{:org/id "1" :org/name "Demo Co"} {:org/id "2" :org/name "Acme"}] :members [{:user/id "u1" :membership/role "ADMIN" :membership/status "active"}] :members-loading false :members-error nil :invite-email "" :invite-role "MEMBER" :invite-loading false :invite-msg nil}
   :componentDidMount (fn [this] (let [app (comp/any->app this) node (gdom/getElement "replicant-org-dashboard") handlers {::cr/set-invite-email (fn [ev] (let [v (some-> ev :replicant/js-event .-target .-value)] (comp/transact! app [(cr/set-invite-email {:value (or v "")})]))) ::cr/set-invite-role (fn [ev] (let [v (some-> ev :replicant/js-event .-target .-value)] (comp/transact! app [(cr/set-invite-role {:value (or v "MEMBER")})]))) ::cr/switch-org (fn [_ev id] (js/console.log "switch" id)) ::cr/send-invite (fn [_ev] (comp/transact! app [(cr/set-invite-msg {:msg "Invitation sent!"})]))}] (when node (bridge/install-replicant-root! app cr/OrgDashboard node handlers))))}
  (dom/div {:id "replicant-org-dashboard-host" :className "min-h-full"} (dom/div {:id "replicant-org-dashboard"} "Loading Replicant Org Dashboard…")))

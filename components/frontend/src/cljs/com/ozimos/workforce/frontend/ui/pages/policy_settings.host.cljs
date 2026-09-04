(ns com.ozimos.workforce.frontend.ui.pages.policy-settings.host
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings :as cr]
   [goog.dom :as gdom]))
(defsc PolicySettingsHost [_this _props]
  {:query [:loading :error :active-org :permissions :rules]
   :initial-state {:loading false :error nil :active-org {:org/name "Demo Co"} :permissions {} :rules []}
   :componentDidMount (fn [this] (let [app (comp/any->app this) node (gdom/getElement "replicant-policy-settings") handlers {}] (when node (bridge/install-replicant-root! app cr/PolicySettings node handlers))))}
  (dom/div {:id "replicant-policy-settings-host" :className "min-h-full"} (dom/div {:id "replicant-policy-settings"} "Loading Replicant Policy Settings…")))

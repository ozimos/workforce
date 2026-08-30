(ns com.ozimos.workforce.frontend.ui.pages.home-replicant-host
  "Fulcro host mounting the pure Replicant Home view."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.home-replicant :as hr]
   [goog.dom :as gdom]))

(defsc HomeReplicantHost [this _props]
  {:query [:active-org]
   :initial-state {:active-org nil}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-home")]
       (when node
         (let [handlers {::hr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst hr/HomeReplicant node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-home-host" :className "min-h-full"}
    (dom/div {:id "replicant-home"} "Loading Home Dashboard…")))

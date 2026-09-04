(ns com.ozimos.workforce.frontend.ui.pages.home.host
  "Fulcro host mounting the pure Replicant Home view."
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.home :as hr]
   [goog.dom :as gdom]))

(defsc HomeHost [_this _props]
  {:query [:active-org]
   :initial-state {:active-org nil}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-home")]
       (when node
         (let [handlers {::hr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst hr/Home node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-home-host" :className "min-h-full"}
    (dom/div {:id "replicant-home"} "Loading Home Dashboard…")))

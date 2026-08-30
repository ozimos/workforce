(ns com.ozimos.workforce.frontend.ui.pages.create-org-replicant-host
  "Fulcro host for CreateOrg Replicant page."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.transit :as transit]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant :as cor]
   [goog.dom :as gdom]))

(defmutation set-name [{:keys [name]}]
  (action [{:keys [state]}]
    (swap! state cor/set-name-state name)))

(defmutation set-loading [{:keys [loading]}]
  (action [{:keys [state]}]
    (swap! state cor/set-loading-state loading)))

(defmutation set-error-msg [{:keys [msg]}]
  (action [{:keys [state]}]
    (swap! state cor/set-error-msg-state msg)))

(defmutation set-success [{:keys [org-name]}]
  (action [{:keys [state]}]
    (swap! state cor/set-success-state org-name)))

(defn- submit-create! [app-inst]
  (let [state-atom (::app/state-atom app-inst)
        org-name   (:name @state-atom)]
    (comp/transact! app-inst [(set-loading {:loading true})])
    (let [query [(list 'org/create {:org/name org-name})]]
      (-> (transit/fetch-transit "/api/query" query)
          (.then (fn [{:keys [body]}]
                   (let [org-data (get body 'org/create)]
                     (if (or (:org/errors org-data) (get body :errors))
                       (comp/transact! app-inst
                         [(set-error-msg {:msg (or (-> org-data :org/errors :name first)
                                                   (-> body :errors :auth first)
                                                   "Failed to create organization")})])
                       (comp/transact! app-inst
                         [(set-success {:org-name (:org/name org-data)})])))))
          (.catch (fn [_]
                    (comp/transact! app-inst [(set-error-msg {:msg "Network error"})])))))))

(defsc CreateOrgReplicantHost [this _props]
  {:query [:name :error-msg :loading :success :org-name]
   :initial-state {:name "" :error-msg nil :loading false :success false :org-name nil}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-create-org")]
       (when node
         (let [handlers {::cor/set-name (fn [ev]
                                          (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                            (comp/transact! app-inst [(set-name {:name v})])))
                         ::cor/submit (fn [_] (submit-create! app-inst))
                         ::cor/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst cor/CreateOrgReplicant node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-create-org-host" :className "min-h-full"}
    (dom/div {:id "replicant-create-org"} "Loading Create Organization…")))

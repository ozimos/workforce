(ns com.ozimos.workforce.frontend.ui.pages.join-org.host
  "Fulcro host for JoinOrg Replicant page."
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.transit :as transit]
   [com.ozimos.workforce.frontend.ui.pages.join-org :as jor]
   [goog.dom :as gdom]))

(defn- load-invitations! [app-inst]
  (let [query [{:user/invitations
                [:invitation/id :invitation/org-id
                 :invitation/org-name :invitation/role
                 :invitation/status :invitation/expires-at]}]]
    (-> (transit/fetch-transit "/api/query" query)
        (.then (fn [{:keys [body]}]
                 (let [invitations (get body :user/invitations)]
                   (comp/transact! app-inst [(jor/set-invitations {:invitations (or invitations [])})]))))
        (.catch (fn [_]
                  (comp/transact! app-inst [(jor/set-error-msg {:msg "Failed to load invitations"})]))))))

(defn- accept-invitation! [app-inst invitation-id]
  (let [mut (list 'org/join {:invitation/id invitation-id})
        query [{mut [:org/id :invitation/errors]}]]
    (comp/transact! app-inst [(jor/set-accepting {:invitation-id invitation-id})])
    (-> (transit/fetch-transit "/api/query" query)
        (.then (fn [{:keys [body]}]
                 (let [join-result (-> body first val)]
                   (if (:invitation/errors join-result)
                     (comp/transact! app-inst
                       [(jor/set-error-msg {:msg (or (-> join-result :invitation/errors first second first)
                                                     "Failed to accept invitation")})])
                     (do
                       (comp/transact! app-inst [(jor/set-accepted {})])
                       (set! js/window.location.pathname "/"))))))
        (.catch (fn [_]
                  (comp/transact! app-inst [(jor/set-error-msg {:msg "Network error"})]))))))

(defsc JoinOrgHost [_this _props]
  {:query [:invitations :loading :error-msg :accepting :accepted]
   :initial-state {:invitations [] :loading true :error-msg nil :accepting nil :accepted false}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-join-org")]
       (when node
         (let [handlers {::jor/accept-invitation (fn [_ id] (accept-invitation! app-inst id))
                         ::jor/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst jor/JoinOrg node handlers)))
       (load-invitations! app-inst)))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-join-org-host" :className "min-h-full"}
    (dom/div {:id "replicant-join-org"} "Loading Join Organization…")))

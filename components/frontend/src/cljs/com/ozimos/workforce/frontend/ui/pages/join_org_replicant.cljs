(ns com.ozimos.workforce.frontend.ui.pages.join-org-replicant
  "Replicant view for the Join Organization page.
   Pure props -> hiccup via defrc with pure state transitions."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (fn [db params] -> db)
;; -----------------------------------------------------------------------------

(defn set-invitations-state [db invs]
  (assoc db :invitations (or invs []) :loading false))

(defn set-error-msg-state [db msg]
  (assoc db :error-msg msg :loading false :accepting nil))

(defn set-accepting-state [db invitation-id]
  (assoc db :accepting invitation-id :error-msg nil))

(defn set-accepted-state [db]
  (assoc db :accepting nil :accepted true :error-msg nil))

;; -----------------------------------------------------------------------------
;; Fulcro Mutations (cljs web)
;; -----------------------------------------------------------------------------

(defmutation set-invitations [{:keys [invitations]}]
  (action [{:keys [state]}]
    (swap! state set-invitations-state invitations)))

(defmutation set-error-msg [{:keys [msg]}]
  (action [{:keys [state]}]
    (swap! state set-error-msg-state msg)))

(defmutation set-accepting [{:keys [invitation-id]}]
  (action [{:keys [state]}]
    (swap! state set-accepting-state invitation-id)))

(defmutation set-accepted [_]
  (action [{:keys [state]}]
    (swap! state set-accepted-state)))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defn- render-invitation-card [accepting inv]
  (let [inv-id (:invitation/id inv)]
    [:div {:key (str inv-id) :class "rounded-lg border border-gray-200 bg-white p-4 shadow-sm"}
     [:div {:class "flex items-center justify-between"}
      [:div
       [:p {:class "text-sm font-semibold text-gray-900"} (:invitation/org-name inv)]
       [:p {:class "text-xs text-gray-500"} (str "Role: " (:invitation/role inv))]]
      [:button {:type "button"
                :disabled (= accepting inv-id)
                :class "rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50"
                :on {:click [::accept-invitation inv-id]}}
       (if (= accepting inv-id) "Accepting..." "Accept")]]]))

(defrc JoinOrgReplicant
  {:query [:invitations :loading :error-msg :accepting :accepted]
   :ident :join-org-replicant/root
   :ident-key :join-org-replicant/root
   :route-segment ["join-org"]}
  [{:keys [invitations loading error-msg accepting accepted]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-md"}
    [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
     "Join an Organization"]]
   [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-md"}
    (when error-msg
      [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
       [:p {:class "text-sm text-red-700"} error-msg]])
    (cond
      (and accepted (empty? invitations)) nil
      loading
      [:div {:class "text-center"}
       [:p {:class "text-gray-500"} "Loading invitations..."]]

      (empty? invitations)
      [:div {:class "text-center"}
       [:p {:class "text-gray-500"} "You have no pending invitations."]
       [:div {:class "mt-4"}
        [:a {:href "/create-org"
             :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
             :on {:click [::navigate "/create-org"]}}
         "Create an organization instead"]]]

      :else
      (into [:div {:class "space-y-4"}]
            (map (partial render-invitation-card accepting) invitations)))]
   [:div {:class "mt-6 text-center"}
    [:a {:href "/"
         :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
         :on {:click [::navigate "/"]}}
     "Skip for now"]]])

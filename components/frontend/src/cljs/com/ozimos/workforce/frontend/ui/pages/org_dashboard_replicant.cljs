(ns com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

(defn set-invite-email-state [db v] (assoc db :invite-email v))
(defn set-invite-role-state [db v] (assoc db :invite-role v))
(defn set-invite-msg-state [db v] (assoc db :invite-msg v))
(defn set-invite-loading-state [db v] (assoc db :invite-loading v))
(defn set-members-state [db v] (assoc db :members v))

(defmutation set-invite-email [{:keys [value]}] (action [{:keys [state]}] (swap! state set-invite-email-state value)))
(defmutation set-invite-role [{:keys [value]}] (action [{:keys [state]}] (swap! state set-invite-role-state value)))
(defmutation set-invite-msg [{:keys [msg]}] (action [{:keys [state]}] (swap! state set-invite-msg-state msg)))
(defmutation set-invite-loading [{:keys [v]}] (action [{:keys [state]}] (swap! state set-invite-loading-state v)))
(defmutation set-members [{:keys [members]}] (action [{:keys [state]}] (swap! state set-members-state members)))

(defrc OrgDashboardReplicant
  {:query [:loading :error-msg :active-org :orgs :members :members-loading :members-error :invite-email :invite-role :invite-loading :invite-msg]
   :ident :org-dashboard-replicant/root
   :ident-key :org-dashboard-replicant/root
   :route-segment ["org-dashboard"]}
  [{:keys [loading active-org orgs members members-loading members-error invite-email invite-role invite-loading invite-msg]}]
  (let [members (or members []) orgs (or orgs [])]
    [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
     [:div {:class "border-b border-gray-200 pb-6"}
      [:h1 {:class "text-3xl font-bold leading-tight text-gray-900"} (or (:org/name active-org) "Dashboard")]
      [:p {:class "mt-2 text-sm text-gray-500"} "Manage your organization"]]
     (if loading
       [:div {:class "mt-8 text-center"} [:p {:class "text-gray-500"} "Loading..."]]
       [:div {:class "mt-8 space-y-8"}
        (when (> (count orgs) 1)
          [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
           [:h3 {:class "text-base font-semibold text-gray-900"} "Switch Organization"]
           (into [:div {:class "mt-4 flex flex-wrap gap-2"}]
                 (map (fn [org]
                        [:button {:replicant/key (str (:org/id org))
                                  :class (str "rounded-md px-3 py-1.5 text-sm font-semibold " (if (= (:org/id org) (:org/id active-org)) "bg-indigo-600 text-white" "bg-white text-gray-700 ring-1 ring-inset ring-gray-300 hover:bg-gray-50"))
                                  :on {:click [::switch-org (:org/id org)]}} (:org/name org)])
                      orgs))])
        [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
         [:h3 {:class "text-base font-semibold text-gray-900"} "Invite Member"]
         (when invite-msg [:p {:class "mt-2 text-sm text-green-600"} invite-msg])
         [:div {:class "mt-4 flex gap-3"}
          [:input {:type "email" :placeholder "Email address" :value (or invite-email "") :on {:input [::set-invite-email]} :class "flex-1 rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-indigo-600 sm:text-sm"}]
          (into [:select {:value (or invite-role "MEMBER") :on {:change [::set-invite-role]} :class "rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}]
                (map (fn [r] [:option {:replicant/key r :value r} r]) ["MEMBER" "ADMIN"]))
          [:button {:class "rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50" :disabled invite-loading :on {:click [::send-invite]}} (if invite-loading "Sending..." "Send Invite")]]]
        [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
         [:h3 {:class "text-base font-semibold text-gray-900"} "Members"]
         (cond
           members-loading [:p {:class "mt-4 text-gray-500"} "Loading members..."]
           members-error [:p {:class "mt-4 text-red-600"} members-error]
           :else [:table {:class "min-w-full divide-y divide-gray-200"}
                  [:thead [:tr
                           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "User ID"]
                           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Role"]
                           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Status"]]]
                  (into [:tbody]
                        (map (fn [m] [:tr {:replicant/key (str (:user/id m)) :class "border-t border-gray-100"}
                                      [:td {:class "px-3 py-2 text-sm text-gray-900"} (:user/id m)]
                                      [:td {:class "px-3 py-2 text-sm text-gray-700"} (:membership/role m)]
                                      [:td {:class "px-3 py-2 text-sm text-gray-700"} (:membership/status m)]])
                             members))])]])]))

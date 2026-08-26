(ns com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

(defn set-permissions-state [db v] (assoc db :permissions v))
(defn set-rules-state [db v] (assoc db :rules v))
(defn set-loading-state [db v] (assoc db :loading v))
(defn set-error-state [db v] (assoc db :error v))

(defmutation set-permissions [{:keys [permissions]}] (action [{:keys [state]}] (swap! state set-permissions-state permissions)))
(defmutation set-rules [{:keys [rules]}] (action [{:keys [state]}] (swap! state set-rules-state rules)))
(defmutation set-loading [{:keys [v]}] (action [{:keys [state]}] (swap! state set-loading-state v)))
(defmutation set-error [{:keys [error]}] (action [{:keys [state]}] (swap! state set-error-state error)))

(defrc PolicySettingsReplicant
  {:query [:loading :error :active-org :permissions :rules]
   :ident :policy-settings-replicant/root}
  [{:keys [loading error active-org permissions rules]}]
  [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-8"}
   [:div {:class "border-b border-gray-200 pb-5"}
    [:h1 {:class "text-2xl font-bold leading-7 text-gray-900"} "Permissions & Approval Routing Policies"]
    [:p {:class "mt-1 text-sm text-gray-500"} (str "Configure granular RBAC permissions and approval routing chains for " (or (:org/name active-org) "your organization"))]]
   (cond
     loading [:p {:class "text-gray-500"} "Loading policies..."]
     error [:div {:class "rounded-md bg-red-50 p-4"} [:p {:class "text-sm text-red-700"} error]]
     :else [:div {:class "space-y-8"}
            [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
             [:h3 {:class "text-base font-semibold text-gray-900 mb-4"} "Role Permission Matrix (Field-Level Masking)"]
             [:p {:class "text-sm text-gray-500"} (str "Permissions: " (pr-str permissions))]]
            [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
             [:h3 {:class "text-base font-semibold text-gray-900 mb-4"} "Dynamic Approval Routing Rule Chains"]
             (if (seq rules)
               [:table {:class "min-w-full divide-y divide-gray-200"}
                [:thead [:tr
                         [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Priority"]
                         [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Rule Name"]]]
                (into [:tbody]
                      (map (fn [rule]
                             [:tr {:replicant/key (or (:rule-id rule) (str (:priority rule)))}
                              [:td {:class "px-3 py-2 text-sm"} (str (:priority rule))]
                              [:td {:class "px-3 py-2 text-sm"} (:name rule)]])
                           rules))]
               [:div {:class "text-center py-6 bg-gray-50 rounded-lg border border-dashed border-gray-200"}
                [:p {:class "text-sm text-gray-500"} "Default routing in effect: Step 1 (Hiring Manager) → Step 2 (Dept Head)."]])]])])

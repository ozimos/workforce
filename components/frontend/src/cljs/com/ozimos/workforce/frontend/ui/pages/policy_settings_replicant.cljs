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

;; -----------------------------------------------------------------------------
;; Sub-components with Query & Ident
;; -----------------------------------------------------------------------------

(defrc ApprovalRule
  {:query [:rule/id :rule/name :rule/priority :rule/trigger :rule/steps]
   :ident :rule/id}
  [props]
  [:tr {:replicant/key (str (or (:rule/id props) (:rule/priority props)))}
   [:td {:class "px-3 py-2 text-sm"} (str (:rule/priority props))]
   [:td {:class "px-3 py-2 text-sm"} (or (:rule/name props) (:name props))]])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defrc PolicySettingsReplicant
  {:query [:loading :error :active-org :permissions
           {:rules (:query (meta ApprovalRule))}]
   :ident :policy-settings-replicant/root
   :ident-key :policy-settings-replicant/root
   :route-segment ["policies"]}
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
                [:p {:class "text-sm text-gray-500"} "Default routing in effect: Step 1 (Hiring Manager) → Step 2 (Dept Head)."]])]
            [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm space-y-4"}
             [:div
              [:h3 {:class "text-base font-semibold text-gray-900"} "Organization Chart Root Hierarchy Configuration"]
              [:p {:class "text-xs text-gray-500 mt-0.5"}
               "Specify the visual root of the full organization hierarchy or configure co-equal executive leadership."]]
             [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-4 pt-2"}
              [:div {:class "border border-indigo-200 bg-indigo-50/40 rounded-lg p-4 space-y-2"}
               [:div {:class "flex items-center gap-2 font-semibold text-xs text-indigo-900"}
                [:span "🌟"] [:span "Automatic (Default)"]]
               [:p {:class "text-xs text-gray-600"}
                "Identifies employee with job title 'CEO'. If absent, gracefully falls back to the employee with the highest number of subordinate nodes."]]
              [:div {:class "border border-gray-200 rounded-lg p-4 space-y-2"}
               [:div {:class "flex items-center gap-2 font-semibold text-xs text-gray-900"}
                [:span "🎯"] [:span "Custom Single Root"]]
               [:p {:class "text-xs text-gray-600"}
                "Set an explicit employee ID as the top node of the full org chart."]]
              [:div {:class "border border-gray-200 rounded-lg p-4 space-y-2"}
               [:div {:class "flex items-center gap-2 font-semibold text-xs text-gray-900"}
                [:span "👥"] [:span "Co-Equal Leadership"]]
               [:p {:class "text-xs text-gray-600"}
                "Combines 2+ top-level co-equals under a synthetic visual root node (e.g. 'Office of the CEO' or 'Executive Board')."]]]]])])

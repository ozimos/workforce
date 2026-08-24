(ns com.ozimos.workforce.frontend.ui.pages.policy-settings
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [button div h1 h2 h3 input label p select span table tbody td th thead tr]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- fetch-policies! [this]
  (comp/set-state! this {:loading true :error nil})
  (-> (transit/fetch-transit "/api/query"
        [{:user/active-org [:org/id :org/name]}])
      (.then (fn [{:keys [body]}]
               (if-let [active-org (:user/active-org body)]
                 (let [org-id (:org/id active-org)]
                   (transit/fetch-transit "/api/query"
                     [{[:org/id org-id]
                       [:org/role-permissions
                        {:org/approval-rules [:rule-id :priority :name :conditions :chain]}]}])
                   (.then (fn [{:keys [body]}]
                            (let [org-data (get body [:org/id org-id])]
                              (comp/set-state! this {:active-org active-org
                                                     :permissions (:org/role-permissions org-data {})
                                                     :rules (:org/approval-rules org-data [])
                                                     :loading false})))))
                 (comp/set-state! this {:loading false :error "No active organization"}))))
      (.catch (fn [err]
                (comp/set-state! this {:loading false :error (str "Failed to load policies: " err)})))))

(defsc PolicySettings [this _props]
  {:query [:loading :error :active-org :permissions :rules]
   :initial-state {:loading true :error nil :active-org nil :permissions {} :rules []}
   :componentDidMount (fn [this] (fetch-policies! this))}
  (let [{:keys [loading error active-org permissions rules]} (comp/get-state this)]
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-8"}
      (div {:className "border-b border-gray-200 pb-5"}
        (h1 {:className "text-2xl font-bold leading-7 text-gray-900"} "Permissions & Approval Routing Policies")
        (p {:className "mt-1 text-sm text-gray-500"}
          (str "Configure granular RBAC permissions and approval routing chains for " (or (:org/name active-org) "your organization"))))

      (cond
        loading
        (p {:className "text-gray-500"} "Loading policies...")

        error
        (div {:className "rounded-md bg-red-50 p-4"}
          (p {:className "text-sm text-red-700"} error))

        :else
        (div {:className "space-y-8"}
          ;; Role Permissions Matrix
          (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
            (h3 {:className "text-base font-semibold text-gray-900 mb-4"} "Role Permission Matrix (Field-Level Masking)")
            (table {:className "min-w-full divide-y divide-gray-200"}
              (thead nil
                (tr nil
                  (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Role")
                  (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Headcount Scope")
                  (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "View Comp?")
                  (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "View Bonus?")
                  (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "View RSU?")))
              (tbody nil
                (mapv (fn [[role perms]]
                        (tr {:key (str role) :className "border-t border-gray-100"}
                          (td {:className "px-3 py-2 text-sm font-medium text-gray-900"} (name role))
                          (td {:className "px-3 py-2 text-sm text-gray-600"} (str (:view-headcount perms :view-own)))
                          (td {:className "px-3 py-2 text-sm text-gray-600"} (if (:view-comp perms) "✅ Yes" "❌ No"))
                          (td {:className "px-3 py-2 text-sm text-gray-600"} (if (:view-bonus perms) "✅ Yes" "❌ No"))
                          (td {:className "px-3 py-2 text-sm text-gray-600"} (if (:view-rsu perms) "✅ Yes" "❌ No"))))
                      (if (seq permissions)
                        permissions
                        {:admin {:view-headcount :view-all :view-comp true :view-bonus true :view-rsu true}
                         :hr {:view-headcount :view-all :view-comp true :view-bonus true :view-rsu false}
                         :dept-head {:view-headcount :view-tree :view-comp true :view-bonus false :view-rsu false}
                         :hiring-manager {:view-headcount :view-own :view-comp true :view-bonus false :view-rsu false}
                         :employee {:view-headcount :view-own :view-comp false :view-bonus false :view-rsu false}})))))

          ;; Approval Routing Rules
          (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
            (h3 {:className "text-base font-semibold text-gray-900 mb-4"} "Dynamic Approval Routing Rule Chains")
            (if (seq rules)
              (table {:className "min-w-full divide-y divide-gray-200"}
                (thead nil
                  (tr nil
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Priority")
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Rule Name")
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Conditions")
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Approval Chain")))
                (tbody nil
                  (mapv (fn [rule]
                          (tr {:key (or (:rule-id rule) (str (random-uuid))) :className "border-t border-gray-100"}
                            (td {:className "px-3 py-2 text-sm font-semibold text-gray-900"} (str (:priority rule)))
                            (td {:className "px-3 py-2 text-sm text-gray-900"} (:name rule))
                            (td {:className "px-3 py-2 text-sm text-gray-500 font-mono"} (pr-str (:conditions rule)))
                            (td {:className "px-3 py-2 text-sm text-gray-700"} (pr-str (:chain rule)))))
                        rules)))
              (div {:className "text-center py-6 bg-gray-50 rounded-lg border border-dashed border-gray-200"}
                (p {:className "text-sm text-gray-500"} "Default routing in effect: Step 1 (Hiring Manager) → Step 2 (Dept Head).")))))))))

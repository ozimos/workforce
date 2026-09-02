(ns com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant
  "Replicant rendering of DeptDashboard: pure props->hiccup via defrc.
   Chained fetch logic lifted to host/transit, view is pure."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

(defn- format-role-name [role-kw]
  (case role-kw
    :hiring-manager "Hiring Manager"
    :dept-head "Department Head"
    :vp "VP / Executive"
    :recruiter "Lead Recruiter"
    :hr "HR Partner"
    (str/capitalize (str/replace (name role-kw) "-" " "))))

;; Pure state transitions (shared)
(defn set-unit-id-state [db v] (assoc db :unit-id v))
(defn set-dashboard-state [db v] (assoc db :dashboard v))
(defn set-loading-state [db v] (assoc db :loading v))
(defn set-error-state [db v] (assoc db :error v))
(defn set-active-org-state [db v] (assoc db :active-org v))
(defn set-available-units-state [db v] (assoc db :available-units v))

(defmutation set-unit-id [{:keys [value]}] (action [{:keys [state]}] (swap! state set-unit-id-state value)))
(defmutation set-dashboard [{:keys [dashboard]}] (action [{:keys [state]}] (swap! state set-dashboard-state dashboard)))
(defmutation set-loading [{:keys [v]}] (action [{:keys [state]}] (swap! state set-loading-state v)))
(defmutation set-error [{:keys [error]}] (action [{:keys [state]}] (swap! state set-error-state error)))
(defmutation set-active-org [{:keys [active-org]}] (action [{:keys [state]}] (swap! state set-active-org-state active-org)))
(defmutation set-available-units [{:keys [units]}] (action [{:keys [state]}] (swap! state set-available-units-state units)))

(defrc DeptDashboardReplicant
  {:query [:loading :error :unit-id :dashboard :active-org :available-units]
   :ident :dept-dashboard-replicant/root
   :ident-key :dept-dashboard-replicant/root
   :route-segment ["dept-dashboard"]}
  [{:keys [loading error unit-id dashboard active-org available-units]}]
  (let [available-units (or available-units [])]
    [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-6"}
     [:div {:class "border-b border-gray-200 pb-5 flex flex-col md:flex-row md:items-center md:justify-between gap-4"}
      [:div
       [:div {:class "flex items-center gap-2"}
        [:a {:href "/org-chart" :class "text-xs font-semibold text-indigo-600 hover:text-indigo-500"} "← Org Chart"]
        [:span {:class "text-gray-300"} "/"]
        [:h1 {:class "text-2xl font-bold leading-7 text-gray-900"} "Department Headcount Analytics"]]
       [:p {:class "mt-1 text-sm text-gray-500"}
        (str "Real-time headcount metrics, budget tracking, and approval turnaround for "
             (or (:org/name active-org) "organization"))]]
      [:div {:class "flex items-center gap-3"}
       (when (seq available-units)
         (into [:select {:value (or unit-id "")
                         :on {:change [::set-unit-id]}
                         :class "rounded-lg border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700 shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}]
               (map (fn [u] [:option {:replicant/key (:unit/id u) :value (:unit/id u)} (str (:unit/name u) " (" (:unit/id u) ")")])
                    available-units)))
       [:div {:class "flex items-center gap-1.5"}
        [:input {:type "text" :placeholder "Unit ID"
                 :value (or unit-id "")
                 :on {:input [::set-unit-id]}
                 :class "w-36 rounded-lg border border-gray-300 px-3 py-1.5 text-xs shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}]
        [:button {:class "rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 transition"
                  :on {:click [::load unit-id]}} "Load"]]]]

     (cond
       loading
       [:div {:class "rounded-2xl bg-white p-12 text-center border border-gray-200 shadow-sm space-y-3"}
        [:div {:class "mx-auto h-8 w-8 animate-spin rounded-full border-3 border-indigo-600 border-t-transparent"}]
        [:p {:class "text-sm font-medium text-gray-600"} "Loading department metrics..."]]

       error
       [:div {:class "rounded-xl bg-red-50 p-6 border border-red-200 shadow-2xs space-y-2"}
        [:h3 {:class "text-sm font-bold text-red-800"} "Failed to Load Department"]
        [:p {:class "text-xs text-red-700"} error]
        [:button {:class "mt-2 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-500 transition"
                  :on {:click [::load unit-id]}} "Try Again"]]

       dashboard
       [:div {:class "space-y-6"}
        [:div {:class "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4"}
         [:div {:class "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
          [:p {:class "truncate text-xs font-semibold text-purple-700"} "Total Budget"]
          [:p {:class "mt-2 text-3xl font-extrabold tracking-tight text-gray-900"} (str (:unit/budget dashboard 0))]
          [:p {:class "text-xs text-gray-400 mt-1"} "Target approved positions"]]
         [:div {:class "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
          [:p {:class "truncate text-xs font-semibold text-emerald-700"} "Filled Positions"]
          [:p {:class "mt-2 text-3xl font-extrabold tracking-tight text-emerald-700"} (str (:unit/filled dashboard 0))]
          [:p {:class "text-xs text-emerald-500 mt-1"} "Active hires on staff"]]
         [:div {:class "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
          [:p {:class "truncate text-xs font-semibold text-indigo-700"} "Open Headcount"]
          [:p {:class "mt-2 text-3xl font-extrabold tracking-tight text-indigo-700"} (str (:unit/open dashboard 0))]
          [:p {:class "text-xs text-indigo-500 mt-1"} "Ready to hire"]]
         [:div {:class "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
          [:p {:class "truncate text-xs font-semibold text-amber-700"} "In Approval"]
          [:p {:class "mt-2 text-3xl font-extrabold tracking-tight text-amber-700"} (str (:unit/pending dashboard 0))]
          [:p {:class "text-xs text-amber-500 mt-1"} "Active pipeline requisitions"]]]
        [:div {:class "rounded-xl bg-white p-6 shadow-2xs border border-gray-200"}
         [:h3 {:class "text-base font-bold text-gray-900 mb-1"} "Approval SLA Latency"]
         [:p {:class "text-xs text-gray-500 mb-4"} "Average turnaround time for completed requisition approvals in this unit."]
         (let [avg-ms (:unit/avg-sla-ms dashboard 0)
               hours (if (pos? avg-ms) (.toFixed (/ avg-ms (* 1000 60 60)) 1) "0.0")]
           [:div {:class "flex items-baseline gap-2"}
            [:span {:class "text-3xl font-extrabold text-indigo-600"} (str hours)]
            [:span {:class "text-sm font-semibold text-gray-600"} "hours average turnaround"]])]
        [:div {:class "rounded-xl bg-white p-6 shadow-2xs border border-gray-200"}
         [:h3 {:class "text-base font-bold text-gray-900 mb-1"} "Assigned Scoped Actors"]
         [:p {:class "text-xs text-gray-500 mb-4"} "Designated approval role assignees for this unit."]
         (let [actors (:unit/actors dashboard {})]
           (if (seq actors)
             [:div {:class "overflow-hidden rounded-lg border border-gray-100"}
              [:table {:class "min-w-full divide-y divide-gray-200"}
               [:thead {:class "bg-gray-50"}
                [:tr
                 [:th {:class "px-4 py-2.5 text-left text-xs font-semibold text-gray-600"} "Role"]
                 [:th {:class "px-4 py-2.5 text-left text-xs font-semibold text-gray-600"} "Assigned User ID"]]]
               (into [:tbody {:class "divide-y divide-gray-100 bg-white"}]
                     (map (fn [[role uid]]
                            [:tr {:replicant/key (str role)}
                             [:td {:class "px-4 py-2.5 text-xs font-bold text-indigo-700"} (format-role-name role)]
                             [:td {:class "px-4 py-2.5 text-xs font-mono text-gray-700"} (str uid)]])
                          actors))]]
             [:p {:class "text-xs text-gray-500 italic"} "No scoped actors assigned to this unit yet."]))]]

       :else
       [:div {:class "text-center py-12 bg-gray-50 rounded-xl border border-dashed border-gray-300"}
        [:p {:class "text-sm text-gray-500"} "Select a unit ID above to view real-time department analytics."]])]))

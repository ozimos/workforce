(ns com.ozimos.workforce.frontend.ui.pages.dept-dashboard
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [button div h1 h2 h3 input p select span table tbody td th thead tr]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- fetch-dept-dashboard! [this unit-id]
  (comp/set-state! this {:loading true :error nil})
  (-> (transit/fetch-transit "/api/query"
        [{[:unit/id unit-id]
          [{:dept/dashboard [:unit/id :unit/budget :unit/filled :unit/open
                             :unit/pending :unit/avg-sla-ms :unit/actors]}]}])
      (.then (fn [{:keys [body]}]
               (let [dash (get-in body [[:unit/id unit-id] :dept/dashboard])]
                 (comp/set-state! this {:dashboard dash :loading false}))))
      (.catch (fn [err]
                (comp/set-state! this {:loading false :error (str "Failed to load dashboard: " err)})))))

(defsc DeptDashboard [this _props]
  {:query [:loading :error :unit-id :dashboard]
   :initial-state {:loading false :error nil :unit-id "eng-dept" :dashboard nil}
   :componentDidMount (fn [this]
                        (let [{:keys [unit-id]} (comp/get-state this)]
                          (when unit-id (fetch-dept-dashboard! this unit-id))))}
  (let [{:keys [loading error unit-id dashboard]} (comp/get-state this)]
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
      (div {:className "border-b border-gray-200 pb-5 mb-6 flex justify-between items-center"}
        (div nil
          (h1 {:className "text-2xl font-bold leading-7 text-gray-900"} "Department Headcount Analytics")
          (p {:className "mt-1 text-sm text-gray-500"} "Real-time budget, filled positions, pending requisitions, and SLA"))
        (div {:className "flex items-center gap-2"}
          (input {:type "text" :placeholder "Enter Unit ID (e.g. dept-1)"
                  :value (or unit-id "")
                  :onChange #(comp/set-state! this {:unit-id (.. % -target -value)})
                  :className "rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"})
          (button {:onClick #(fetch-dept-dashboard! this unit-id)
                   :className "rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500"}
            "Load")))

      (cond
        loading
        (div {:className "text-center py-12"}
          (p {:className "text-gray-500"} "Loading department metrics..."))

        error
        (div {:className "rounded-md bg-red-50 p-4"}
          (p {:className "text-sm text-red-700"} error))

        dashboard
        (div {:className "space-y-6"}
          ;; KPI Cards Grid
          (div {:className "grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4"}
            ;; Budget
            (div {:className "overflow-hidden rounded-lg bg-white px-4 py-5 shadow border border-gray-100 sm:p-6"}
              (p {:className "truncate text-sm font-medium text-gray-500"} "Total Budget")
              (p {:className "mt-1 text-3xl font-semibold tracking-tight text-gray-900"}
                (str (:unit/budget dashboard 0))))

            ;; Filled
            (div {:className "overflow-hidden rounded-lg bg-white px-4 py-5 shadow border border-gray-100 sm:p-6"}
              (p {:className "truncate text-sm font-medium text-emerald-600"} "Filled Seats")
              (p {:className "mt-1 text-3xl font-semibold tracking-tight text-emerald-700"}
                (str (:unit/filled dashboard 0))))

            ;; Open
            (div {:className "overflow-hidden rounded-lg bg-white px-4 py-5 shadow border border-gray-100 sm:p-6"}
              (p {:className "truncate text-sm font-medium text-indigo-600"} "Open Headcount")
              (p {:className "mt-1 text-3xl font-semibold tracking-tight text-indigo-700"}
                (str (:unit/open dashboard 0))))

            ;; In-Approval Pending
            (div {:className "overflow-hidden rounded-lg bg-white px-4 py-5 shadow border border-gray-100 sm:p-6"}
              (p {:className "truncate text-sm font-medium text-amber-600"} "In-Approval Requisitions")
              (p {:className "mt-1 text-3xl font-semibold tracking-tight text-amber-700"}
                (str (:unit/pending dashboard 0)))))

          ;; SLA Turnaround Latency Card
          (div {:className "rounded-lg bg-white p-6 shadow border border-gray-100"}
            (h3 {:className "text-base font-semibold text-gray-900 mb-2"} "Approval SLA Latency")
            (let [avg-ms (:unit/avg-sla-ms dashboard 0)
                  hours (if (pos? avg-ms) (.toFixed (/ avg-ms (* 1000 60 60)) 1) 0)]
              (p {:className "text-2xl font-bold text-gray-900"} (str hours " hrs average turnaround"))))

          ;; Scoped Actors
          (div {:className "rounded-lg bg-white p-6 shadow border border-gray-100"}
            (h3 {:className "text-base font-semibold text-gray-900 mb-4"} "Assigned Scoped Actors")
            (let [actors (:unit/actors dashboard {})]
              (if (seq actors)
                (table {:className "min-w-full divide-y divide-gray-200"}
                  (thead nil
                    (tr nil
                      (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Role")
                      (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "User IDs")))
                  (tbody nil
                    (mapv (fn [[role uids]]
                            (tr {:key (str role) :className "border-t border-gray-100"}
                              (td {:className "px-3 py-2 text-sm font-medium text-gray-900"} (name role))
                              (td {:className "px-3 py-2 text-sm text-gray-600"} (pr-str uids))))
                          actors)))
                (p {:className "text-sm text-gray-500"} "No scoped actors assigned to this unit yet.")))))

        :else
        (div {:className "text-center py-12 bg-gray-50 rounded-lg border border-dashed border-gray-300"}
          (p {:className "text-sm text-gray-500"} "Select a unit ID above to view real-time department analytics."))))))

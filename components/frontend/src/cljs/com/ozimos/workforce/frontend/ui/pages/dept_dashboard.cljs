(ns com.ozimos.workforce.frontend.ui.pages.dept-dashboard
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div h1 h3 input option p select span table tbody td th thead tr]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- get-url-param [k]
  (when (and (exists? js/window) (exists? js/window.location))
    (let [params (js/URLSearchParams. js/window.location.search)]
      (.get params k))))

(defn- format-role-name [role-kw]
  (case role-kw
    :hiring-manager "Hiring Manager"
    :dept-head "Department Head"
    :vp "VP / Executive"
    :recruiter "Lead Recruiter"
    :hr "HR Partner"
    (str/capitalize (str/replace (name role-kw) "-" " "))))

(defn- fetch-dept-dashboard! [this unit-id]
  (when (seq unit-id)
    (comp/set-state! this {:loading true :error nil :unit-id unit-id})
    (-> (transit/fetch-transit "/api/query"
          [{[:unit/id unit-id]
            [{:dept/dashboard [:unit/id :unit/budget :unit/filled :unit/open
                               :unit/pending :unit/avg-sla-ms :unit/actors]}]}])
        (.then (fn [{:keys [body]}]
                 (let [dash (get-in body [[:unit/id unit-id] :dept/dashboard])]
                   (comp/set-state! this {:dashboard dash :loading false}))))
        (.catch (fn [err]
                  (comp/set-state! this {:loading false :error (str "Failed to load dashboard: " err)}))))))

(defn- init-page-data! [this]
  (let [url-unit (or (get-url-param "unit-id") (get-url-param "unit"))]
    (-> (transit/fetch-transit "/api/query"
          [{:user/active-org [:org/id :org/name]}])
        (.then (fn [{:keys [body]}]
                 (if-let [active-org (:user/active-org body)]
                   (let [org-id (:org/id active-org)]
                     (-> (transit/fetch-transit "/api/query"
                           [{[:org/id org-id]
                             [{:org/chart [{:org/units [:unit/id :unit/name :unit/division-id :unit/dept-id]}]}]}])
                         (.then (fn [{:keys [body]}]
                                  (let [units (get-in body [[:org/id org-id] :org/chart :org/units] [])
                                        target-unit (or (when (seq url-unit) url-unit)
                                                        (:unit/id (first units))
                                                        "dept-acme-frontend")]
                                    (comp/set-state! this {:active-org active-org
                                                           :available-units units
                                                           :unit-id target-unit})
                                    (fetch-dept-dashboard! this target-unit))))
                         (.catch (fn [_]
                                   (fetch-dept-dashboard! this (or url-unit "dept-acme-frontend"))))))
                   (fetch-dept-dashboard! this (or url-unit "dept-acme-frontend")))))
        (.catch (fn [_]
                  (fetch-dept-dashboard! this (or url-unit "dept-acme-frontend")))))))

(defsc DeptDashboard [this _props]
  {:query [:loading :error :unit-id :dashboard :active-org :available-units]
   :initial-state {:loading false :error nil :unit-id "eng-dept" :dashboard nil :active-org nil :available-units []}
   :componentDidMount (fn [this] (init-page-data! this))}
  (let [{:keys [loading error unit-id dashboard active-org available-units]} (comp/get-state this)]
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-6"}
      ;; Page Header
      (div {:className "border-b border-gray-200 pb-5 flex flex-col md:flex-row md:items-center md:justify-between gap-4"}
        (div nil
          (div {:className "flex items-center gap-2"}
            (a {:href "/org-chart" :className "text-xs font-semibold text-indigo-600 hover:text-indigo-500"} "← Org Chart")
            (span {:className "text-gray-300"} "/")
            (h1 {:className "text-2xl font-bold leading-7 text-gray-900"} "Department Headcount Analytics"))
          (p {:className "mt-1 text-sm text-gray-500"}
            (str "Real-time headcount metrics, budget tracking, and approval turnaround for "
                 (or (:org/name active-org) "organization"))))

        ;; Department Switcher Toolbar
        (div {:className "flex items-center gap-3"}
          (when (seq available-units)
            (select {:value (or unit-id "")
                     :onChange #(let [v (.. % -target -value)]
                                  (fetch-dept-dashboard! this v))
                     :className "rounded-lg border border-gray-300 px-3 py-1.5 text-xs font-medium text-gray-700 shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}
              (mapv (fn [u]
                      (option {:key (:unit/id u) :value (:unit/id u)}
                        (str (:unit/name u) " (" (:unit/id u) ")")))
                    available-units)))

          (div {:className "flex items-center gap-1.5"}
            (input {:type "text" :placeholder "Unit ID"
                    :value (or unit-id "")
                    :onChange #(comp/set-state! this {:unit-id (.. % -target -value)})
                    :className "w-36 rounded-lg border border-gray-300 px-3 py-1.5 text-xs shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"})
            (button {:onClick #(fetch-dept-dashboard! this unit-id)
                     :className "rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 transition"}
              "Load"))))

      (cond
        loading
        (div {:className "rounded-2xl bg-white p-12 text-center border border-gray-200 shadow-sm space-y-3"}
          (div {:className "mx-auto h-8 w-8 animate-spin rounded-full border-3 border-indigo-600 border-t-transparent"})
          (p {:className "text-sm font-medium text-gray-600"} "Loading department metrics..."))

        error
        (div {:className "rounded-xl bg-red-50 p-6 border border-red-200 shadow-2xs space-y-2"}
          (h3 {:className "text-sm font-bold text-red-800"} "Failed to Load Department")
          (p {:className "text-xs text-red-700"} error)
          (button {:onClick #(fetch-dept-dashboard! this unit-id)
                   :className "mt-2 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-500 transition"}
            "Try Again"))

        dashboard
        (div {:className "space-y-6"}
          ;; KPI Cards Grid
          (div {:className "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4"}
            ;; Budget
            (div {:className "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
              (p {:className "truncate text-xs font-semibold text-purple-700"} "Total Budget")
              (p {:className "mt-2 text-3xl font-extrabold tracking-tight text-gray-900"}
                (str (:unit/budget dashboard 0)))
              (p {:className "text-xs text-gray-400 mt-1"} "Target approved positions"))

            ;; Filled
            (div {:className "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
              (p {:className "truncate text-xs font-semibold text-emerald-700"} "Filled Positions")
              (p {:className "mt-2 text-3xl font-extrabold tracking-tight text-emerald-700"}
                (str (:unit/filled dashboard 0)))
              (p {:className "text-xs text-emerald-500 mt-1"} "Active hires on staff"))

            ;; Open
            (div {:className "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
              (p {:className "truncate text-xs font-semibold text-indigo-700"} "Open Headcount")
              (p {:className "mt-2 text-3xl font-extrabold tracking-tight text-indigo-700"}
                (str (:unit/open dashboard 0)))
              (p {:className "text-xs text-indigo-500 mt-1"} "Ready to hire"))

            ;; In-Approval Pending
            (div {:className "rounded-xl bg-white p-5 shadow-2xs border border-gray-200"}
              (p {:className "truncate text-xs font-semibold text-amber-700"} "In Approval")
              (p {:className "mt-2 text-3xl font-extrabold tracking-tight text-amber-700"}
                (str (:unit/pending dashboard 0)))
              (p {:className "text-xs text-amber-500 mt-1"} "Active pipeline requisitions")))

          ;; SLA Turnaround Latency Card
          (div {:className "rounded-xl bg-white p-6 shadow-2xs border border-gray-200"}
            (h3 {:className "text-base font-bold text-gray-900 mb-1"} "Approval SLA Latency")
            (p {:className "text-xs text-gray-500 mb-4"} "Average turnaround time for completed requisition approvals in this unit.")
            (let [avg-ms (:unit/avg-sla-ms dashboard 0)
                  hours (if (pos? avg-ms) (.toFixed (/ avg-ms (* 1000 60 60)) 1) "0.0")]
              (div {:className "flex items-baseline gap-2"}
                (span {:className "text-3xl font-extrabold text-indigo-600"} (str hours))
                (span {:className "text-sm font-semibold text-gray-600"} "hours average turnaround"))))

          ;; Scoped Actors
          (div {:className "rounded-xl bg-white p-6 shadow-2xs border border-gray-200"}
            (h3 {:className "text-base font-bold text-gray-900 mb-1"} "Assigned Scoped Actors")
            (p {:className "text-xs text-gray-500 mb-4"} "Designated approval role assignees for this unit.")
            (let [actors (:unit/actors dashboard {})]
              (if (seq actors)
                (div {:className "overflow-hidden rounded-lg border border-gray-100"}
                  (table {:className "min-w-full divide-y divide-gray-200"}
                    (thead {:className "bg-gray-50"}
                      (tr nil
                        (th {:className "px-4 py-2.5 text-left text-xs font-semibold text-gray-600"} "Role")
                        (th {:className "px-4 py-2.5 text-left text-xs font-semibold text-gray-600"} "Assigned User ID")))
                    (tbody {:className "divide-y divide-gray-100 bg-white"}
                      (mapv (fn [[role uid]]
                              (tr {:key (str role)}
                                (td {:className "px-4 py-2.5 text-xs font-bold text-indigo-700"} (format-role-name role))
                                (td {:className "px-4 py-2.5 text-xs font-mono text-gray-700"} (str uid))))
                            actors))))
                (p {:className "text-xs text-gray-500 italic"} "No scoped actors assigned to this unit yet.")))))

        :else
        (div {:className "text-center py-12 bg-gray-50 rounded-xl border border-dashed border-gray-300"}
          (p {:className "text-sm text-gray-500"} "Select a unit ID above to view real-time department analytics."))))))

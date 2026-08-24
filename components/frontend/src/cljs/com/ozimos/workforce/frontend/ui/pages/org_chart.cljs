(ns com.ozimos.workforce.frontend.ui.pages.org-chart
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [button div h1 h2 h3 p span]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- fetch-chart-data! [this]
  (comp/set-state! this {:loading true :error nil})
  (-> (transit/fetch-transit "/api/query"
        [{:user/active-org [:org/id :org/name]}])
      (.then (fn [{:keys [body]}]
               (if-let [active-org (:user/active-org body)]
                 (let [org-id (:org/id active-org)]
                   (transit/fetch-transit "/api/query"
                     [{[:org/id org-id]
                       [{:org/chart [:org/id :org/hierarchy]}]}])
                   (.then (fn [{:keys [body]}]
                            (let [chart-data (get-in body [[:org/id org-id] :org/chart])]
                              (comp/set-state! this {:active-org active-org
                                                     :hierarchy (:org/hierarchy chart-data)
                                                     :loading false})))))
                 (comp/set-state! this {:loading false :error "No active organization selected"}))))
      (.catch (fn [err]
                (comp/set-state! this {:loading false :error (str "Failed to load org chart: " err)})))))

(defn- render-tree-node [parent-id hierarchy depth]
  (let [children (get hierarchy parent-id [])]
    (div {:key (str parent-id) :className (str "ml-" (* depth 4) " my-2")}
      (div {:className "flex items-center gap-3 p-3 bg-white rounded-lg border border-gray-200 shadow-sm hover:border-indigo-400 transition"}
        (div {:className "w-3 h-3 rounded-full bg-indigo-600"})
        (div nil
          (p {:className "text-sm font-semibold text-gray-900"} (or parent-id "Root Unit"))
          (p {:className "text-xs text-gray-500"} (str (count children) " sub-units / departments"))))
      (when (seq children)
        (div {:className "border-l-2 border-indigo-100 ml-4 pl-2 mt-1 space-y-2"}
          (mapv (fn [child-id]
                  (render-tree-node child-id hierarchy (inc depth)))
                children))))))

(defsc OrgChart [this _props]
  {:query [:loading :error :active-org :hierarchy]
   :initial-state {:loading true :error nil :active-org nil :hierarchy {}}
   :componentDidMount (fn [this] (fetch-chart-data! this))}
  (let [{:keys [loading error active-org hierarchy]} (comp/get-state this)
        root-units (get hierarchy nil [])]
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
      (div {:className "border-b border-gray-200 pb-5 mb-6"}
        (h1 {:className "text-2xl font-bold leading-7 text-gray-900"} "Organization Chart & Hierarchy")
        (p {:className "mt-1 text-sm text-gray-500"}
          (str "Interactive hierarchy tree for " (or (:org/name active-org) "current organization"))))
      (cond
        loading
        (div {:className "text-center py-12"}
          (p {:className "text-gray-500"} "Loading organization structure..."))

        error
        (div {:className "rounded-md bg-red-50 p-4"}
          (p {:className "text-sm text-red-700"} error))

        (empty? hierarchy)
        (div {:className "text-center py-12 bg-gray-50 rounded-lg border border-dashed border-gray-300"}
          (h3 {:className "text-sm font-medium text-gray-900"} "No Org Units Found")
          (p {:className "mt-1 text-sm text-gray-500"} "Create divisions and departments to visualize your org hierarchy."))

        :else
        (div {:className "bg-slate-50 p-6 rounded-xl border border-gray-200"}
          (div {:className "space-y-4"}
            (if (seq root-units)
              (mapv (fn [root-id] (render-tree-node root-id hierarchy 0)) root-units)
              (mapv (fn [[parent _]] (render-tree-node parent hierarchy 0)) (take 5 hierarchy)))))))))

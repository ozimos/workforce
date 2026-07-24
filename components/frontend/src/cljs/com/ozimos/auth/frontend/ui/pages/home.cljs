(ns com.ozimos.auth.frontend.ui.pages.home
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [div h1 h3 p]]))

(defsc Home [_ _props]
  {:query []
   :initial-state {}}
  (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
    (div {:className "border-b border-gray-200 pb-6"}
      (h1 {:className "text-3xl font-bold leading-tight text-gray-900"}
        "Dashboard")
      (p {:className "mt-2 text-sm text-gray-500"}
        "Welcome to the Hiring Approval app."))
    (div {:className "mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3"}
      (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
        (h3 {:className "text-base font-semibold text-gray-900"} "Org Chart")
        (p {:className "mt-2 text-sm text-gray-500"} "View your organization hierarchy"))
      (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
        (h3 {:className "text-base font-semibold text-gray-900"} "Headcount Requests")
        (p {:className "mt-2 text-sm text-gray-500"} "Create and manage hiring requests"))
      (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
        (h3 {:className "text-base font-semibold text-gray-900"} "Pending Approvals")
        (p {:className "mt-2 text-sm text-gray-500"} "Approvals awaiting your decision")))))

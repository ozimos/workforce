(ns com.ozimos.auth.frontend.ui.pages.home)

(defn ui-home
  []
  [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
   [:div {:class "border-b border-gray-200 pb-6"}
    [:h1 {:class "text-3xl font-bold leading-tight text-gray-900"}
     "Dashboard"]
    [:p {:class "mt-2 text-sm text-gray-500"}
     "Welcome to the Hiring Approval app."]]
   [:div {:class "mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3"}
    [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
     [:h3 {:class "text-base font-semibold text-gray-900"} "Org Chart"]
     [:p {:class "mt-2 text-sm text-gray-500"} "View your organization hierarchy"]]
    [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
     [:h3 {:class "text-base font-semibold text-gray-900"} "Headcount Requests"]
     [:p {:class "mt-2 text-sm text-gray-500"} "Create and manage hiring requests"]]
    [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
     [:h3 {:class "text-base font-semibold text-gray-900"} "Pending Approvals"]
     [:p {:class "mt-2 text-sm text-gray-500"} "Approvals awaiting your decision"]]]])

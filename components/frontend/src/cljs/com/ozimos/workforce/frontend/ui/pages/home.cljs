(ns com.ozimos.workforce.frontend.ui.pages.home
  "Replicant view for the Home dashboard page.
   Pure props -> hiccup via defrc with pure navigation event vectors."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]]))

(def dashboard-cards
  [{:id :org-chart
    :title "Org Chart"
    :desc "Explore organization hierarchy, division trees, headcount allocations, and assigned actors."
    :href "/org-chart"
    :cta "Open →"
    :bg-color "bg-indigo-50"
    :text-color "text-indigo-600"
    :hover-bg "group-hover:bg-indigo-600"
    :svg-d "M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5m3 0v-4a1 1 0 011-1h2a1 1 0 011 1v4m-4 0h4"}
   {:id :headcount
    :title "Headcount Requisitions"
    :desc "Submit new requisition requests and track approvals through the multi-step chain."
    :href "/headcount"
    :cta "Open →"
    :bg-color "bg-emerald-50"
    :text-color "text-emerald-600"
    :hover-bg "group-hover:bg-emerald-600"
    :svg-d "M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"}
   {:id :approvals
    :title "Pending Approvals"
    :desc "Review, approve, or reject requisitions currently awaiting your decision."
    :href "/headcount"
    :cta "Review →"
    :bg-color "bg-amber-50"
    :text-color "text-amber-600"
    :hover-bg "group-hover:bg-amber-600"
    :svg-d "M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"}
   {:id :dept-analytics
    :title "Department Analytics"
    :desc "Monitor filled positions, open headcounts, budget variance, and SLA latencies."
    :href "/dept-dashboard"
    :cta "View →"
    :bg-color "bg-purple-50"
    :text-color "text-purple-600"
    :hover-bg "group-hover:bg-purple-600"
    :svg-d "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"}
   {:id :policies
    :title "Approval Policies & RBAC"
    :desc "Configure custom approval routing chains, threshold rules, and role permissions."
    :href "/policies"
    :cta "Configure →"
    :bg-color "bg-blue-50"
    :text-color "text-blue-600"
    :hover-bg "group-hover:bg-blue-600"
    :svg-d "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37.996.608 2.296.07 2.572-1.065z"}
   {:id :org-dashboard
    :title "Organization & Members"
    :desc "Manage organization members, send email invitations, and switch organizations."
    :href "/org-dashboard"
    :cta "Manage →"
    :bg-color "bg-slate-50"
    :text-color "text-slate-600"
    :hover-bg "group-hover:bg-slate-700"
    :svg-d "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"}])

(defn- render-card [{:keys [id title desc href cta bg-color text-color hover-bg svg-d]}]
  [:a {:key (name id)
       :href href
       :class "group block rounded-xl border border-gray-200 bg-white p-6 shadow-sm hover:border-indigo-500 hover:shadow-md transition-all duration-200 cursor-pointer"
       :on {:click [::navigate href]}}
   [:div {:class "flex items-center justify-between"}
    [:div {:class (str "flex h-10 w-10 items-center justify-center rounded-lg " bg-color " " text-color " " hover-bg " group-hover:text-white transition-colors duration-200")}
     [:svg {:xmlns "http://www.w3.org/2000/svg" :class "h-6 w-6" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
      [:path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d svg-d}]]]
    [:span {:class (str "text-xs font-semibold " text-color " group-hover:translate-x-0.5 transition-transform duration-200")} cta]]
   [:h3 {:class (str "mt-4 text-base font-bold text-gray-900 group-hover:" text-color " transition-colors")} title]
   [:p {:class "mt-1.5 text-sm text-gray-500"} desc]])

(defrc Home
  {:query [:active-org]
   :ident :home/root
   :ident-key :home/root
   :route-segment ["home"]}
  [_props]
  [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
   [:div {:class "border-b border-gray-200 pb-6"}
    [:h1 {:class "text-3xl font-extrabold tracking-tight text-gray-900"} "Workforce Dashboard"]
    [:p {:class "mt-2 text-sm text-gray-500"} "Welcome to the Workforce Management and Headcount Approval platform."]]
   (into [:div {:class "mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3"}]
         (map render-card dashboard-cards))])

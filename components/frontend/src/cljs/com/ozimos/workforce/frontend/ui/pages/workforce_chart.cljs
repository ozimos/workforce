(ns com.ozimos.workforce.frontend.ui.pages.workforce-chart
  "Workforce Organization Chart Replicant page.
   Renders the interactive workforce hierarchy tree with expand/collapse controls,
   search filtering, role badges, headcount integration with app-level ABAC,
   and field-level RBAC compensation masking."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.abac :as abac]))

;; -----------------------------------------------------------------------------
;; Event Action Creators (Data-Driven Replicant DOM Dispatch)
;; -----------------------------------------------------------------------------

(defn toggle-workforce-collapse [data]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/toggle-collapse data])

(defn set-workforce-search [data]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-search-term data])

(defn expand-all-workforce []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-all {}])

(defn collapse-all-workforce []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/collapse-all {}])

(defn refresh-workforce []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/refresh {}])

;; -----------------------------------------------------------------------------
;; Formatting Helpers
;; -----------------------------------------------------------------------------

(defn- initials [name-str]
  (if (str/blank? name-str)
    "?"
    (let [parts (str/split (str/trim name-str) #"\s+")]
      (str/upper-case
       (str (first (first parts))
            (when (> (count parts) 1)
              (first (last parts))))))))

(defn- format-currency [amount currency]
  (let [cur (or currency "USD")
        sym (case cur "USD" "$" "GBP" "£" "EUR" "€" (str cur " "))]
    (str sym (.toLocaleString (js/Number. (or amount 0))))))

;; -----------------------------------------------------------------------------
;; Visual Badges
;; -----------------------------------------------------------------------------

(defn- role-badge-color [role]
  (case role
    :admin   "bg-purple-100 text-purple-700 ring-purple-600/20"
    :vp      "bg-indigo-100 text-indigo-700 ring-indigo-600/20"
    :dept-head "bg-blue-100 text-blue-700 ring-blue-600/20"
    :hiring-manager "bg-emerald-100 text-emerald-700 ring-emerald-600/20"
    :recruiter "bg-amber-100 text-amber-700 ring-amber-600/20"
    "bg-gray-100 text-gray-700 ring-gray-500/20"))

(defn- avatar-badge [name role]
  [:div {:class (str "w-10 h-10 rounded-full flex items-center justify-center font-bold text-xs shadow-sm ring-2 "
                     (role-badge-color role))}
   (initials name)])

(defn- headcount-badge [hc]
  (let [title (or (:headcount/title hc) "Open Position")
        level (or (:headcount/job-level hc) "L1")
        loc   (or (:headcount/location hc) "Remote")]
    [:div {:replicant/key (str "hc-" (:headcount/id hc))
           :class "flex items-center justify-between px-2.5 py-1.5 rounded-lg bg-amber-50 border border-amber-200/80 text-xs shadow-xs hover:border-amber-400 transition"}
     [:div {:class "flex items-center gap-1.5 min-w-0"}
      [:span {:class "w-2 h-2 rounded-full bg-amber-500 shrink-0"}]
      [:span {:class "font-medium text-amber-900 truncate"} title]]
     [:div {:class "flex items-center gap-1.5 shrink-0 ml-2"}
      [:span {:class "px-1.5 py-0.5 rounded text-[10px] font-semibold bg-amber-100/80 text-amber-800"} level]
      [:span {:class "text-[10px] text-amber-600"} loc]]]))

;; -----------------------------------------------------------------------------
;; Sub-Components with Fulcro Query & Ident for Normalization
;; -----------------------------------------------------------------------------

(defrc WorkforceNode
  {:query [:person/id :person/name :person/title :person/email :person/unit-id
           :person/department-name :person/division-name :person/role
           :person/job-level :person/location :person/manager-id :person/compensation]
   :ident :person/id}
  [{:keys [person/id person/name person/title person/department-name
           person/division-name person/role]}]
  [:div {:replicant/key (str "node-" id)
         :class "p-4 bg-white rounded-xl border border-gray-200 shadow-sm"}
   [:div {:class "flex items-center gap-3"}
    (avatar-badge (or name (str id)) role)
    [:div
     [:h4 {:class "font-bold text-sm text-gray-900"} (or name (str id))]
     [:p {:class "text-xs text-gray-500 font-medium"} (or title "Employee")]
     (when (or department-name division-name)
       [:p {:class "text-xs text-indigo-600 mt-0.5"} (or department-name division-name)])]]])

(defrc HeadcountCard
  {:query [:headcount/id :headcount/title :headcount/job-level :headcount/location
           :headcount/division-id :headcount/dept-id :headcount/status]
   :ident :headcount/id}
  [props]
  (headcount-badge props))

;; -----------------------------------------------------------------------------
;; Recursive Workforce Tree Node Renderer
;; -----------------------------------------------------------------------------

(defn- render-workforce-tree-node
  [person-id workforce-map hierarchy collapsed-nodes search-term can-view-comp?
   headcounts-by-manager abac-policy]
  (let [person (get workforce-map person-id {:person/id person-id :person/name (str person-id)})
        children (get hierarchy person-id [])
        has-children? (pos? (count children))
        collapsed? (contains? collapsed-nodes person-id)
        name (or (:person/name person) (str person-id))
        title (or (:person/title person) "Employee")
        dept (or (:person/department-name person) (:person/division-name person) (:person/unit-id person))
        comp (:person/compensation person)
        role (:person/role person)
        search-term (some-> search-term str/lower-case str/trim)
        matches-search? (and (seq search-term)
                             (or (str/includes? (str/lower-case name) search-term)
                                 (str/includes? (str/lower-case title) search-term)
                                 (when dept (str/includes? (str/lower-case (str dept)) search-term))))
        ;; ABAC-filtered headcounts for this manager
        raw-hcs (get headcounts-by-manager person-id [])
        visible-hcs (abac/filter-accessible-headcounts raw-hcs abac-policy)]
    [:div {:replicant/key (str person-id)
           :class "flex flex-col items-center"}
     ;; Person Card Node
     [:div {:class (str "w-72 bg-white rounded-xl border p-4 shadow-sm transition-all duration-200 hover:shadow-md relative "
                        (if matches-search?
                          "border-indigo-500 ring-2 ring-indigo-400 bg-indigo-50/20"
                          "border-gray-200 hover:border-indigo-300"))}
      [:div {:class "flex items-start gap-3"}
       (avatar-badge name role)
       [:div {:class "flex-1 min-w-0"}
        [:div {:class "flex items-center justify-between"}
         [:h4 {:class "font-bold text-sm text-gray-900 truncate"} name]
         (when role
           [:span {:class "inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-semibold bg-gray-100 text-gray-700"}
            (str/upper-case (clojure.core/name role))])]
        [:p {:class "text-xs font-medium text-gray-600 truncate mt-0.5"} title]
        (when dept
          [:p {:class "text-[11px] text-indigo-600 truncate font-mono mt-0.5"} (str "📁 " dept)])

        ;; Compensation details (Field-Level RBAC Protected)
        (if can-view-comp?
          (if comp
            [:div {:class "mt-2 pt-2 border-t border-gray-100 flex items-center justify-between text-[11px] text-gray-500"}
             [:span "Base Comp:"]
             [:span {:class "font-semibold text-emerald-700 bg-emerald-50 px-1.5 py-0.5 rounded ring-1 ring-emerald-600/20"}
              (format-currency (:salary comp) (:currency comp "USD"))]]
            [:div {:class "mt-2 pt-2 border-t border-gray-100 text-[11px] text-gray-400 italic"} "Comp: Not configured"])
          [:div {:class "mt-2 pt-2 border-t border-gray-100 flex items-center gap-1 text-[11px] text-gray-400"}
           [:span "🔒 Comp restricted"]])]]

      ;; ABAC-Filtered Headcounts for this manager
      (when (seq visible-hcs)
        [:div {:class "mt-3 pt-2 border-t border-amber-100"}
         [:p {:class "text-[10px] font-semibold text-amber-700 mb-1.5 uppercase tracking-wide"} "Open Headcounts"]
         (into [:div {:class "flex flex-col gap-1"}]
               (map headcount-badge visible-hcs))])

      ;; Expand / Collapse Toggle if has direct reports
      (when has-children?
        [:div {:class "mt-3 pt-2 border-t border-gray-100 flex justify-between items-center"}
         [:span {:class "text-[11px] text-gray-400 font-medium"}
          (str (count children) " Direct Report" (when (> (count children) 1) "s"))]
         [:button {:class "inline-flex items-center gap-1 rounded bg-gray-50 px-2 py-0.5 text-xs font-medium text-gray-700 hover:bg-indigo-50 hover:text-indigo-600 transition"
                    :on {:click [(toggle-workforce-collapse {:id person-id})]}}
          (if collapsed? "▼ Expand" "▲ Collapse")]])]

     ;; Connecting Vertical Line & Children Branch
     (when (and has-children? (not collapsed?))
       [:div {:class "flex flex-col items-center w-full mt-2"}
        ;; Stem connector
        [:div {:class "h-5 w-0.5 bg-gray-300"}]
        ;; Child nodes row with crossbar
        (into [:div {:class "flex justify-center gap-8 relative pt-2"}]
              (concat
               (when (> (count children) 1)
                 [[:div {:class "absolute top-0 left-12 right-12 h-0.5 bg-gray-300"}]])
               (map (fn [cid]
                      (render-workforce-tree-node cid workforce-map hierarchy collapsed-nodes search-term can-view-comp?
                                                 headcounts-by-manager abac-policy))
                    children)))])]))

;; -----------------------------------------------------------------------------
;; Root Workforce Org Chart View
;; -----------------------------------------------------------------------------

(defrc WorkforceChart
  {:query [:loading :error :active-org :workforce :workforce-hierarchy :workforce-search
           :collapsed-workforce :permissions :headcounts-by-manager :abac/policy
           {:workforce/list (:query (meta WorkforceNode))}
           {:headcounts/list (:query (meta HeadcountCard))}]
   :ident :workforce-chart/root
   :ident-key :workforce-chart/root
   :route-segment ["org-chart"]}
  [{:keys [loading error active-org workforce workforce-hierarchy workforce-search
           collapsed-workforce permissions headcounts-by-manager] :as props}]
  (let [abac-policy (get props :abac/policy)
        workforce-map (or workforce {})
        hierarchy (or workforce-hierarchy {})
        collapsed-nodes (or collapsed-workforce #{})
        can-view-comp? (if (contains? permissions :view-comp)
                         (true? (:view-comp permissions))
                         (contains? #{:admin :hr :vp :dept-head :hiring-manager} (:role permissions)))
        root-ids (get hierarchy nil [])
        total-members (count workforce-map)
        total-headcounts (count (abac/filter-accessible-headcounts
                                 (apply concat (vals (or headcounts-by-manager {})))
                                 abac-policy))]
    [:div {:class "min-h-screen bg-slate-50 flex flex-col"}
     ;; Header & Controls
     [:header {:class "sticky top-0 z-30 bg-white/95 backdrop-blur-md border-b border-gray-200 px-6 py-4 shadow-xs"}
      [:div {:class "max-w-7xl mx-auto flex flex-col md:flex-row items-center justify-between gap-4"}
       [:div {:class "flex items-center gap-3"}
        [:div {:class "p-2 bg-indigo-600 text-white rounded-xl shadow-xs"}
         [:svg {:class "w-6 h-6" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
          [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
                  :d "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"}]]]
        [:div
         [:h1 {:class "text-xl font-bold text-gray-900 tracking-tight"}
          (if active-org (str (:name active-org) " — Workforce Chart") "Workforce Chart")]
         [:p {:class "text-xs text-gray-500 font-medium"}
          "Organizational reporting hierarchy, leadership structure, and open headcount requisitions."]]]

       ;; Search, Filter & Action Buttons
       [:div {:class "flex flex-wrap items-center gap-3 w-full md:w-auto justify-end"}
        [:div {:class "relative w-full sm:w-64"}
         [:input {:type "text"
                  :value (or workforce-search "")
                  :placeholder "Search workforce by name or title..."
                  :class "w-full pl-9 pr-3 py-1.5 text-xs bg-gray-50 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:bg-white transition"
                  :on {:input [(set-workforce-search {:term :event.target/value})]}}]]

        [:button {:class "inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 transition shadow-xs"
                  :on {:click [(expand-all-workforce)]}}
         "Expand All"]

        [:button {:class "inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 transition shadow-xs"
                  :on {:click [(collapse-all-workforce)]}}
         "Collapse All"]

        [:button {:class "inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-indigo-50 text-indigo-600 hover:bg-indigo-100 transition shadow-xs"
                  :on {:click [(refresh-workforce)]}}
         "↺ Refresh"]]]]

     ;; Stat Badges Row
     [:div {:class "max-w-7xl mx-auto w-full px-6 pt-4"}
      [:div {:class "flex flex-wrap gap-4"}
       [:div {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg shadow-xs text-xs"}
        [:span {:class "font-medium text-gray-500"} "👥 Total Workforce:"]
        [:span {:class "font-bold text-gray-900 font-mono"} total-members]]

       [:div {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg shadow-xs text-xs"}
        [:span {:class "font-medium text-amber-600"} "📋 Open Headcounts:"]
        [:span {:class "font-bold text-amber-700 font-mono"} total-headcounts]]

       [:div {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg shadow-xs text-xs"}
        [:span {:class "font-medium text-indigo-600"} "🔒 Comp Visibility:"]
        [:span {:class (str "font-bold font-mono " (if can-view-comp? "text-emerald-700" "text-gray-500"))}
         (if can-view-comp? "Full Access" "Restricted (Field-Level RBAC)")]]]]

     ;; Main Chart Interactive Canvas
     [:main {:class "flex-1 overflow-auto p-8 flex justify-center items-start"}
      (cond
        loading
        [:div {:class "flex flex-col items-center justify-center h-64 gap-3 text-gray-500"}
         [:div {:class "animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"}]
         [:p {:class "text-sm font-medium"} "Loading workforce hierarchy..."]]

        error
        [:div {:class "p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm max-w-md"}
         [:p {:class "font-bold"} "Failed to load workforce chart"]
         [:p {:class "text-xs mt-1"} (str error)]]

        (empty? root-ids)
        [:div {:class "flex flex-col items-center justify-center h-64 text-gray-400 gap-2"}
         [:svg {:class "w-12 h-12 text-gray-300" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
          [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "1.5"
                  :d "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"}]]
         [:p {:class "text-sm font-medium text-gray-600"} "No workforce members registered in this organization."]
         [:p {:class "text-xs text-gray-400"} "When employees and managers are added, their hierarchy will render here."]]

        :else
        (into [:div {:class "flex flex-col items-center gap-12 min-w-max pb-16"}]
              (map (fn [rid]
                     (render-workforce-tree-node rid workforce-map hierarchy collapsed-nodes
                                                workforce-search can-view-comp?
                                                headcounts-by-manager abac-policy))
                   root-ids)))]]))

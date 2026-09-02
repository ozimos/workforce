(ns com.ozimos.workforce.frontend.ui.pages.org-chart-replicant
  "Replicant rendering of the OrgChart page: pure `props -> hiccup` via `defrc`.

   Unlike `org-chart` (defsc + React DOM), this namespace produces plain Hiccup
   data (vectors/maps) with event handlers as pure data (`{:on {:click [::event ...]}}`).
   UI state (`:collapsed-nodes`, `:search-term`) is lifted into the Fulcro DB
   and routed through `replicant-bridge`'s handler table, not `comp/set-state!`.

   Cross-runtime (.cljc): compiles for the browser (Replicant DOM), the Node
   SSR harness, and the JVM (SSR rendering + headless tests)."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

;; -----------------------------------------------------------------------------
;; Helpers (pure, no React)
;; -----------------------------------------------------------------------------

(defn- format-role-name [role-kw]
  (case role-kw
    :hiring-manager "Hiring Manager"
    :dept-head "Department Head"
    :vp "VP / Executive"
    :recruiter "Lead Recruiter"
    :hr "HR Partner"
    (str/capitalize (str/replace (name role-kw) "-" " "))))

(defn- kpi-badge [label val color-scheme]
  (let [classes (case color-scheme
                  :emerald "bg-emerald-50 text-emerald-700 ring-emerald-600/20"
                  :indigo "bg-indigo-50 text-indigo-700 ring-indigo-600/20"
                  :amber "bg-amber-50 text-amber-700 ring-amber-600/20"
                  :purple "bg-purple-50 text-purple-700 ring-purple-600/20"
                  "bg-gray-50 text-gray-700 ring-gray-600/20")]
    [:div {:class (str "inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold ring-1 ring-inset " classes)}
     [:span {:class "font-normal text-gray-500"} (str label ":")]
     [:span {:class "font-bold"} (str (or val 0))]]))

(defn- render-unit-card [unit children-count collapsed? search-match?]
  (let [unit-id (:unit/id unit)
        is-div? (nil? (:unit/parent-id unit))
        actors (:unit/actors unit {})
        has-children? (pos? children-count)]
    [:div {:replicant/key (str unit-id)
           :class (str "relative rounded-xl border p-5 shadow-sm transition-all duration-200 cursor-pointer select-none "
                       (if is-div?
                         "bg-white border-indigo-100 hover:border-indigo-400 hover:shadow-md ring-1 ring-indigo-50"
                         "bg-white border-gray-200 hover:border-indigo-300 hover:shadow-md")
                       (when search-match? " ring-2 ring-indigo-500 bg-indigo-50/20"))
           :on {:click (if has-children?
                         [::toggle-collapse unit-id]
                         [::navigate (str "/dept-dashboard?unit-id=" unit-id)])}}
     [:div {:class "flex items-start justify-between gap-4"}
      [:div {:class "flex items-center gap-3"}
       (if has-children?
         [:button {:class "flex h-5.5 w-5.5 items-center justify-center rounded-md bg-gray-100 text-gray-500 hover:bg-indigo-50 hover:text-indigo-600 transition focus:outline-none shrink-0"
                   :title (if collapsed? "Expand child units" "Collapse child units")
                   :on {:click [::toggle-collapse unit-id]}}
          [:svg {:xmlns "http://www.w3.org/2000/svg"
                 :class (str "h-3 w-3 shrink-0 transition-transform duration-200 " (if collapsed? "-rotate-90" "rotate-0"))
                 :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
           [:path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2.5" :d "M19 9l-7 7-7-7"}]]]
         [:div {:class "h-5.5 w-5.5 flex items-center justify-center shrink-0"}
          [:div {:class (str "h-1.5 w-1.5 rounded-full " (if is-div? "bg-purple-500" "bg-indigo-500"))}]])
       [:div
        [:div {:class "flex items-center gap-2"}
         [:h3 {:class "text-base font-bold text-gray-900"} (or (:unit/name unit) unit-id)]
         [:span {:class (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold "
                             (if is-div?
                               "bg-purple-100 text-purple-700 ring-1 ring-inset ring-purple-700/10"
                               "bg-blue-100 text-blue-700 ring-1 ring-inset ring-blue-700/10"))}
          (if is-div? "Division" "Department")]]
        [:p {:class "text-xs font-mono text-gray-400 mt-0.5"}
         (str unit-id " • " (or (:unit/division-id unit) "ORG") "/" (or (:unit/dept-id unit) "ALL"))]]]
      [:div {:class "flex items-center gap-1.5"
             :on {:click [::noop]}}
       [:button {:class "rounded-md bg-gray-50 px-2.5 py-1 text-xs font-semibold text-gray-700 hover:bg-indigo-50 hover:text-indigo-700 ring-1 ring-inset ring-gray-200 transition"
                 :title "Add sub-department under this unit"
                 :on {:click [::open-create-modal unit-id]}}
        "+ Sub-unit"]
       [:button {:class "rounded-md bg-gray-50 px-2.5 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-100 ring-1 ring-inset ring-gray-200 transition"
                 :title "Edit headcount budget"
                 :on {:click [::open-budget-modal unit-id]}}
        "Budget"]
       [:a {:href (str "/dept-dashboard?unit-id=" unit-id)
            :class "rounded-md bg-indigo-50 px-2.5 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-100 ring-1 ring-inset ring-indigo-700/10 transition inline-block"
            :on {:click [::navigate (str "/dept-dashboard?unit-id=" unit-id)]}}
        "Analytics →"]]]
     [:div {:class "mt-4 flex flex-wrap items-center gap-2 pt-3 border-t border-gray-100"}
      (kpi-badge "Budget" (:unit/budget unit 0) :purple)
      (kpi-badge "Filled" (:unit/filled unit 0) :emerald)
      (kpi-badge "Open" (:unit/open unit 0) :indigo)
      (kpi-badge "In Approval" (:unit/pending unit 0) :amber)
      (when has-children?
        [:span {:class "text-xs font-medium text-gray-400 ml-auto"}
         (str children-count (if (= 1 children-count) " child unit" " child units"))])]
     (when (seq actors)
       (into [:div {:class "mt-3 flex flex-wrap items-center gap-2 pt-2 text-xs text-gray-600 bg-slate-50/70 p-2.5 rounded-lg border border-slate-100"}
              [:span {:class "font-semibold text-gray-500"} "Actors:"]]
             (map (fn [[role uid]]
                    [:span {:replicant/key (str role) :class "inline-flex items-center gap-1 bg-white px-2 py-0.5 rounded border border-gray-200 shadow-2xs"}
                     [:span {:class "font-medium text-indigo-700"} (format-role-name role)]
                     [:span {:class "text-gray-400"} "→"]
                     [:span {:class "font-mono font-semibold text-gray-700"} (str uid)]])
                  actors)))]))

(defn- render-tree-branch [units hierarchy depth search-term collapsed-nodes node-id]
  (let [unit (get units node-id {:unit/id node-id :unit/name node-id})
        children (get hierarchy node-id [])
        is-collapsed? (contains? collapsed-nodes node-id)
        search-clean (when (seq search-term) (str/lower-case search-term))
        matches-search? (and (seq search-clean)
                             (or (str/includes? (str/lower-case (or (:unit/name unit) "")) search-clean)
                                 (str/includes? (str/lower-case (or (:unit/id unit) "")) search-clean)
                                 (str/includes? (str/lower-case (or (:unit/division-id unit) "")) search-clean)
                                 (str/includes? (str/lower-case (or (:unit/dept-id unit) "")) search-clean)))]
    [:div {:replicant/key (str node-id) :class (str "relative " (when (pos? depth) "ml-8 mt-3"))}
     (when (pos? depth)
       [:div {:class "absolute -left-6 top-6 w-5 border-t-2 border-indigo-200"}])
     (render-unit-card unit (count children) is-collapsed? matches-search?)
     (when (and (seq children) (not is-collapsed?))
       (into [:div {:class "relative border-l-2 border-indigo-200 ml-4 pl-2 space-y-3 mt-1"}]
             (map (fn [child-id]
                    (render-tree-branch units hierarchy (inc depth) search-term collapsed-nodes child-id))
                  children)))]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (100% Pure, .cljc — shared by Web + Mobile)
;; A Mutation is just (fn [db params] -> updated-db). Frontend-ui stays
;; zero-DOM/zero-React; each platform invokes via its native atom mechanism.
;; -----------------------------------------------------------------------------

(defn toggle-collapse-state
  "Pure: toggle `id` in :collapsed-nodes set."
  [db id]
  (update db :collapsed-nodes
          (fn [s]
            (let [s (or s #{})]
              (if (contains? s id) (disj s id) (conj s id))))))

(defn expand-all-state
  "Pure: clear all collapsed nodes."
  [db]
  (assoc db :collapsed-nodes #{}))

(defn collapse-all-state
  "Pure: collapse all units (set = all unit ids)."
  [db]
  (assoc db :collapsed-nodes (set (keys (:units db {})))))

(defn set-search-term-state
  "Pure: set search term."
  [db value]
  (assoc db :search-term value))

;; -----------------------------------------------------------------------------
;; Fulcro Wrappers (Web-only thin layer over pure functions)
;; Mobile (ClojureDart) uses plain atom + swap! with the pure functions above
;; and does NOT depend on Fulcro's defmutation macro.
;; -----------------------------------------------------------------------------

(defmutation toggle-collapse
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state toggle-collapse-state id)))

(defmutation expand-all
  [_]
  (action [{:keys [state]}]
    (swap! state expand-all-state)))

(defmutation collapse-all
  [_]
  (action [{:keys [state]}]
    (swap! state collapse-all-state)))

(defmutation set-search-term
  [{:keys [value]}]
  (action [{:keys [state]}]
    (swap! state set-search-term-state value)))

;; -----------------------------------------------------------------------------
;; Composed Component Queries for Query-Driven Automatic Normalization
;; -----------------------------------------------------------------------------

(defrc DivisionItem
  {:query [:division/id :division/name]
   :ident :division/id}
  [props]
  [:span {:class "font-medium text-purple-700"} (or (:division/name props) (:division/id props))])

(defrc DeptItem
  {:query [:dept/id :dept/name]
   :ident :dept/id}
  [props]
  [:span {:class "font-medium text-blue-700"} (or (:dept/name props) (:dept/id props))])

(defrc OrgUnit
  {:query [:unit/id :unit/name :unit/division-id :unit/dept-id :unit/parent-id
           :unit/budget :unit/filled :unit/open :unit/pending :unit/actors :unit/children
           {:unit/division (:query (meta DivisionItem))}
           {:unit/dept (:query (meta DeptItem))}]
   :ident :unit/id}
  [props]
  (render-unit-card props 0 false false))

(defrc OrgChartReplicant
  {:query [:loading :error :active-org :units :hierarchy :search-term :collapsed-nodes
           {:org/chart [:org/id :org/hierarchy
                        {:org/units (:query (meta OrgUnit))}]}]
   :ident :org-chart-replicant/root
   :ident-key :org-chart-replicant/root
   :route-segment ["org-chart-2"]}
  [{:keys [loading error active-org units hierarchy search-term collapsed-nodes]}]
  (let [unit-list (vals (or units {}))
        root-units (or (get hierarchy nil)
                       (mapv :unit/id (filter #(nil? (:unit/parent-id %)) unit-list)))
        total-budget (reduce + 0 (map #(:unit/budget % 0) unit-list))
        total-filled (reduce + 0 (map #(:unit/filled % 0) unit-list))
        total-open (reduce + 0 (map #(:unit/open % 0) unit-list))
        total-pending (reduce + 0 (map #(:unit/pending % 0) unit-list))
        total-divisions (count (filter #(nil? (:unit/parent-id %)) unit-list))
        total-depts (count (filter #(some? (:unit/parent-id %)) unit-list))
        collapsed-nodes (or collapsed-nodes #{})]
    [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-6"}
     [:div {:class "border-b border-gray-200 pb-6 flex flex-col md:flex-row md:items-center md:justify-between gap-4"}
      [:div
       [:div {:class "flex items-center gap-2"}
        [:h1 {:class "text-3xl font-extrabold tracking-tight text-gray-900"} "Divisions & Departments Chart"]
        (when active-org
          [:span {:class "inline-flex items-center rounded-md bg-indigo-50 px-2.5 py-0.5 text-xs font-bold text-indigo-700 ring-1 ring-inset ring-indigo-700/10"}
           (:org/name active-org)])]
       [:p {:class "mt-1.5 text-sm text-gray-500"}
        "Interactive organizational hierarchy, headcount metrics, and scoped actor coverage."]]
      [:div {:class "flex items-center gap-3"}
       [:button {:class "inline-flex items-center gap-1.5 rounded-lg bg-white px-3 py-1.5 text-xs font-semibold text-gray-700 shadow-2xs ring-1 ring-inset ring-gray-300 hover:bg-gray-50 transition"
                 :on {:click [::refresh]}}
        "Refresh"]
       [:button {:class "inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3.5 py-1.5 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 transition"
                 :on {:click [::open-create-modal nil]}}
        "+ Add Division / Dept"]]]
     (when (seq unit-list)
       [:div {:class "grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6"}
        [:div {:class "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
         [:p {:class "text-xs font-medium text-gray-500 truncate"} "Total Units"]
         [:p {:class "mt-1 text-2xl font-bold tracking-tight text-gray-900"} (str (count unit-list))]
         [:p {:class "text-xs text-gray-400 mt-0.5"} (str total-divisions " div, " total-depts " dept")]]
        [:div {:class "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
         [:p {:class "text-xs font-medium text-gray-500 truncate"} "Allocated Budget"]
         [:p {:class "mt-1 text-2xl font-bold tracking-tight text-purple-700"} (str total-budget)]
         [:p {:class "text-xs text-purple-400 mt-0.5"} "Target seats"]]
        [:div {:class "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
         [:p {:class "text-xs font-medium text-gray-500 truncate"} "Filled Seats"]
         [:p {:class "mt-1 text-2xl font-bold tracking-tight text-emerald-700"} (str total-filled)]
         [:p {:class "text-xs text-emerald-500 mt-0.5"} (str (if (pos? total-budget) (js/Math.round (* 100 (/ total-filled total-budget))) 0) "% filled")]]
        [:div {:class "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
         [:p {:class "text-xs font-medium text-gray-500 truncate"} "Open Headcount"]
         [:p {:class "mt-1 text-2xl font-bold tracking-tight text-indigo-700"} (str total-open)]
         [:p {:class "text-xs text-indigo-400 mt-0.5"} "Available for hire"]]
        [:div {:class "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
         [:p {:class "text-xs font-medium text-gray-500 truncate"} "In Approval"]
         [:p {:class "mt-1 text-2xl font-bold tracking-tight text-amber-700"} (str total-pending)]
         [:p {:class "text-xs text-amber-400 mt-0.5"} "Pipeline requisitions"]]
        [:div {:class "rounded-xl bg-gradient-to-br from-indigo-50 to-slate-50 p-4 shadow-2xs border border-indigo-100 flex flex-col justify-between"}
         [:p {:class "text-xs font-medium text-indigo-900"} "Analytics & SLA"]
         [:a {:href "/dept-dashboard" :class "text-xs font-bold text-indigo-600 hover:text-indigo-700 mt-1 inline-flex items-center gap-1"} "View Dashboards →"]]])
     (when (seq unit-list)
       [:div {:class "flex flex-col sm:flex-row items-center justify-between gap-3 bg-white p-4 rounded-xl border border-gray-200 shadow-2xs"}
        [:div {:class "relative w-full sm:w-80"}
         [:input {:type "text" :placeholder "Filter by unit name, code, division..."
                  :value (or search-term "")
                  :on {:input [::set-search-term]}}]
         [:div {:class "absolute inset-y-0 left-0 flex items-center pl-2.5 pointer-events-none text-gray-400"}
          [:svg {:xmlns "http://www.w3.org/2000/svg" :class "h-3.5 w-3.5 shrink-0" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
           [:path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2.5" :d "M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]]
        [:div {:class "flex items-center gap-2 w-full sm:w-auto justify-end"}
         [:button {:class "rounded-lg px-3 py-1.5 text-xs font-semibold text-gray-600 hover:bg-gray-100 ring-1 ring-inset ring-gray-200 transition"
                   :on {:click [::expand-all]}}
          "Expand All"]
         [:button {:class "rounded-lg px-3 py-1.5 text-xs font-semibold text-gray-600 hover:bg-gray-100 ring-1 ring-inset ring-gray-200 transition"
                   :on {:click [::collapse-all]}}
          "Collapse All"]]])
     (cond
       loading
       [:div {:class "rounded-2xl bg-white p-12 text-center border border-gray-200 shadow-sm space-y-3"}
        [:div {:class "mx-auto h-8 w-8 animate-spin rounded-full border-3 border-indigo-600 border-t-transparent"}]
        [:p {:class "text-sm font-medium text-gray-600"} "Loading organization hierarchy..."]]
       error
       [:div {:class "rounded-xl bg-red-50 p-6 border border-red-200 shadow-2xs space-y-2"}
        [:h3 {:class "text-sm font-bold text-red-800"} "Unable to Load Org Chart"]
        [:p {:class "text-xs text-red-700"} error]
        [:button {:class "mt-2 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-500 transition"
                  :on {:click [::refresh]}}
         "Try Again"]]
       (empty? unit-list)
       [:div {:class "rounded-2xl bg-white p-12 text-center border-2 border-dashed border-gray-200 space-y-4"}
        [:div {:class "mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-indigo-50 text-indigo-600"}
         [:svg {:xmlns "http://www.w3.org/2000/svg" :class "h-6 w-6" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
          [:path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5m3 0v-4a1 1 0 011-1h2a1 1 0 011 1v4m-4 0h4"}]]]
        [:div
         [:h3 {:class "text-base font-bold text-gray-900"} "No Organizational Units Found"]
         [:p {:class "text-xs text-gray-500 mt-1 max-w-sm mx-auto"}
          "Get started by creating your first Division (such as Engineering, Product, or Sales) to begin tracking headcount."]]
        [:button {:class "rounded-lg bg-indigo-600 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 transition"
                  :on {:click [::open-create-modal nil]}}
         "+ Create First Division"]]
       :else
       (into [:div {:class "space-y-6"}]
             (if (seq root-units)
               (map (fn [root-id]
                      (render-tree-branch units hierarchy 0 search-term collapsed-nodes root-id))
                    root-units)
               (map (fn [u]
                      (render-tree-branch units hierarchy 0 search-term collapsed-nodes (:unit/id u)))
                    unit-list))))]))

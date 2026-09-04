(ns com.ozimos.workforce.frontend.ui.pages.workforce-chart
  "Workforce Organization Chart Replicant page.
   Renders the interactive workforce hierarchy tree with expand/collapse controls,
   search filtering, role badges, headcount integration with app-level ABAC,
   and field-level RBAC compensation masking."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.abac :as abac]
   [com.ozimos.workforce.frontend.ui.pages.unconnected-side-tree :as unconnected-side-tree]))

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

(defn set-active-tab [data]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-active-tab data])

(defn set-custom-root [data]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/set-custom-root data])

(defn reset-custom-root []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/reset-custom-root {}])

(defn expand-or-fetch-branch [data]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/expand-or-fetch data])

(defn select-search-result [data]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/select-search-result data])

(defn zoom-in []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/zoom-in {}])

(defn zoom-out []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/zoom-out {}])

(defn zoom-reset []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/zoom-reset {}])

(defn zoom-wheel [ev]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/zoom-wheel ev])

(defn pan-start [ev]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/pan-start ev])

(defn pan-move [ev]
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/pan-move ev])

(defn pan-end []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/pan-end {}])

(defn toggle-unconnected-drawer []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/toggle-unconnected-drawer {}])

(defn close-unconnected-drawer []
  [:com.ozimos.workforce.frontend.ui.pages.workforce-chart/close-unconnected-drawer {}])

;; -----------------------------------------------------------------------------
;; Full Org Root Resolution Algorithm
;; -----------------------------------------------------------------------------

(defn count-descendants
  "Calculates the total number of transitive descendants under node-id in hierarchy."
  [hierarchy node-id]
  (loop [queue (into #queue [] (get hierarchy node-id []))
         visited #{node-id}
         cnt 0]
    (if (empty? queue)
      cnt
      (let [curr (peek queue)
            q' (pop queue)]
        (if (contains? visited curr)
          (recur q' visited cnt)
          (let [children (get hierarchy curr [])]
            (recur (into q' children)
                   (conj visited curr)
                   (inc cnt))))))))

(defn ceo-title?
  "Checks if a title string represents a Chief Executive Officer."
  [title]
  (boolean (and title (re-find #"(?i)\bceo\b" (str title)))))

(defn resolve-full-org-root
  "Resolves the root of the full organization hierarchy according to:
   1. Configured app setting (:root-id or :co-equal-ids)
   2. Employee with job title matching 'CEO'
   3. Graceful fallback: Employee with the highest number of transitive descendants."
  [workforce-list hierarchy chart-settings]
  (let [emp-ids (set (map :person/id workforce-list))
        setting-root-id (:root-id chart-settings)
        co-equal-ids (filterv emp-ids (:co-equal-ids chart-settings))]
    (cond
      ;; 1a. Explicit configured root ID
      (and setting-root-id (contains? emp-ids setting-root-id))
      {:root-id setting-root-id :synthetic-node nil}

      ;; 1b. Configured co-equal leaders (>= 2)
      (>= (count co-equal-ids) 2)
      (let [visual-title (or (:visual-root-title chart-settings) "Executive Leadership")
            synth-node {:person/id "__visual_root__"
                        :person/name visual-title
                        :person/title "Co-Equal Leadership"
                        :person/role :admin
                        :person/department-name "Executive Office"
                        :person/is-synthetic? true}]
        {:root-id "__visual_root__"
         :synthetic-node synth-node
         :co-equal-ids co-equal-ids})

      ;; 2. Employee with title 'CEO'
      :else
      (if-let [ceo-emp (first (filter #(ceo-title? (:person/title %)) workforce-list))]
        {:root-id (:person/id ceo-emp) :synthetic-node nil}

        ;; 3. Graceful fallback: Employee with maximum descendants
        (if (seq workforce-list)
          (let [scored (map (fn [emp]
                              {:id (:person/id emp)
                               :descendants (count-descendants hierarchy (:person/id emp))})
                            workforce-list)
                top (apply max-key :descendants scored)]
            {:root-id (:id top) :synthetic-node nil})
          {:root-id nil :synthetic-node nil})))))

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
           :headcount/division-id :headcount/dept-id :headcount/status
           :headcount/owner :headcount/hiring-manager :headcount/reporting-manager
           :headcount/acting-reporting-manager? :headcount/acting-reporting-manager-id
           :headcount/headcount-reporting-manager-id
           :headcount/recruiters :headcount/approvers :headcount/collaborators :headcount/sourcers]
   :ident :headcount/id}
  [props]
  (headcount-badge props))

;; -----------------------------------------------------------------------------
;; Recursive Workforce Tree Node Renderer (Employees & Headcounts)
;; -----------------------------------------------------------------------------

(defn- render-headcount-tree-card
  [hc node-id children has-children? collapsed? loading? matches-search?]
  (let [title (or (:headcount/title hc) "Open Position")
        level (or (:headcount/job-level hc) "L1")
        loc   (or (:headcount/location hc) "Remote")
        dept  (or (:headcount/dept-id hc) (:headcount/division-id hc))
        status (or (:headcount/status hc) "open")
        hiring-mgr (or (:headcount/hiring-manager hc) (:hiring-manager hc))
        rep-mgr (or (:headcount/reporting-manager hc) (:reporting-manager hc))
        acting? (true? (or (:headcount/acting-reporting-manager? hc) (:acting-reporting-manager? hc)))
        recruiters (or (:headcount/recruiters hc) (:recruiters hc) [])
        approvers  (or (:headcount/approvers hc) (:approvers hc) [])
        collaborators (or (:headcount/collaborators hc) (:collaborators hc) [])
        sourcers   (or (:headcount/sourcers hc) (:sourcers hc) [])
        owner      (or (:headcount/owner hc) (:owner hc))]
    [:div {:class (str "w-72 bg-amber-50/40 rounded-xl border-2 border-dashed p-4 shadow-sm transition-all duration-200 hover:shadow-md relative "
                       (if matches-search?
                         "border-indigo-500 ring-2 ring-indigo-400 bg-amber-50/80"
                         "border-amber-300 hover:border-amber-400"))}
     ;; Header: Requisition pill + status badge
     [:div {:class "flex items-center justify-between gap-2 mb-2"}
      [:span {:class "inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider bg-amber-100 text-amber-900 ring-1 ring-amber-400/30"}
       [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0"}]
       "Open Headcount"]
      [:span {:class (str "inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-bold uppercase "
                          (case (str/lower-case (str status))
                            ("open" ":open") "bg-emerald-100 text-emerald-800"
                            ("in-approval" ":in-approval") "bg-amber-100 text-amber-800"
                            ("approved" ":approved") "bg-indigo-100 text-indigo-800"
                            "bg-gray-100 text-gray-700"))}
       (str/replace (str status) #"^:" "")]]

     ;; Title and Level/Location
     [:div {:class "mb-2"}
      [:h4 {:class "font-bold text-sm text-gray-900 truncate"} title]
      [:div {:class "flex items-center gap-2 mt-1 text-xs text-gray-600"}
       [:span {:class "px-1.5 py-0.5 rounded text-[10px] font-semibold bg-gray-100 text-gray-700"} level]
       [:span {:class "text-[11px] text-gray-500"} loc]
       (when dept
         [:span {:class "text-[11px] text-indigo-600 font-mono truncate"} (str "📁 " dept)])]]

     ;; Actors Section
     [:div {:class "mt-2.5 pt-2 border-t border-amber-200/60 flex flex-col gap-1 text-[11px] text-gray-600"}
      (when hiring-mgr
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"} "Hiring Mgr:"]
         [:span {:class "font-semibold text-gray-800 truncate ml-2"} (str hiring-mgr)]])

      (when rep-mgr
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"}
          (if acting? "Acting Reporting Mgr:" "Reporting Mgr:")]
         [:span {:class (str "font-semibold truncate ml-2 " (if acting? "text-amber-800 font-bold" "text-gray-800"))}
          (cond
            (string? rep-mgr) rep-mgr
            (:id rep-mgr) (str (:id rep-mgr) (when (= (:type rep-mgr) :headcount) " (HC)"))
            (:employee-id rep-mgr) (str (:employee-id rep-mgr))
            :else (str rep-mgr))]])

      (when (seq recruiters)
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"} "Recruiter:"]
         [:span {:class "text-gray-700 truncate ml-2"} (str/join ", " recruiters)]])

      (when (seq approvers)
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"} "Approvers:"]
         [:span {:class "text-gray-700 truncate ml-2"}
          (str/join ", " (map #(if (map? %) (or (:approver-user-id %) (:user-id %)) (str %)) approvers))]])

      (when (seq collaborators)
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"} "Collaborators:"]
         [:span {:class "text-gray-700 truncate ml-2"} (str/join ", " collaborators)]])

      (when (seq sourcers)
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"} "Sourcers:"]
         [:span {:class "text-gray-700 truncate ml-2"} (str/join ", " sourcers)]])

      (when owner
        [:div {:class "flex items-center justify-between"}
         [:span {:class "text-gray-500 font-medium"} "Owner:"]
         [:span {:class "text-gray-700 truncate ml-2"} (str owner)]])]

     ;; Expand / Collapse Toggle if Headcount has direct reports
     (when has-children?
       [:div {:class "mt-3 pt-2 border-t border-amber-200/60 flex items-center justify-between gap-2"}
        [:span {:class "text-[10px] text-amber-800 font-semibold"}
         (str (count children) " Direct Report" (when (> (count children) 1) "s"))]
        [:button {:class "inline-flex items-center gap-1 rounded bg-white px-2 py-0.5 text-xs font-medium text-gray-700 hover:bg-amber-100 hover:text-amber-900 transition ring-1 ring-inset ring-amber-300 shadow-2xs"
                  :disabled loading?
                  :on {:click [(expand-or-fetch-branch {:id node-id :all-loaded? true})]}}
         (cond
           loading? "⌛ Loading..."
           collapsed? "▼ Expand"
           :else "▲ Collapse")]])]))

(defn- render-workforce-tree-node
  ([node-id workforce-map headcounts-map hierarchy collapsed-nodes search-term can-view-comp?
    custom-root-id]
   (render-workforce-tree-node node-id workforce-map headcounts-map hierarchy collapsed-nodes search-term can-view-comp?
                              custom-root-id #{}))
  ([node-id workforce-map headcounts-map hierarchy collapsed-nodes search-term can-view-comp?
    custom-root-id loading-branches]
   (let [is-hc? (or (contains? headcounts-map node-id)
                    (str/starts-with? (str node-id) "req-"))]
     (when-not (and is-hc? (not (contains? headcounts-map node-id)))
       (let [raw-children (get hierarchy node-id [])
             children (filterv (fn [cid]
                                 (if (or (str/starts-with? (str cid) "req-")
                                         (contains? headcounts-map cid))
                                   (contains? headcounts-map cid)
                                   true))
                               raw-children)
             has-children? (pos? (count children))
             collapsed? (contains? collapsed-nodes node-id)
             loading? (contains? (or loading-branches #{}) node-id)
             search-term (some-> search-term str/lower-case str/trim)]
         [:div {:replicant/key (str node-id)
                :class "flex flex-col items-center"}
          (if is-hc?
            ;; Headcount Node Card
            (let [hc (get headcounts-map node-id)
                  matches-search? (and (seq search-term)
                                       (or (str/includes? (str/lower-case (or (:headcount/title hc) "")) search-term)
                                           (str/includes? (str/lower-case (or (:headcount/dept-id hc) "")) search-term)))]
              (render-headcount-tree-card hc node-id children has-children? collapsed? loading? matches-search?))

            ;; Person Card Node
            (let [person (get workforce-map node-id {:person/id node-id :person/name (str node-id)})
                  all-loaded? (every? #(or (contains? workforce-map %) (contains? headcounts-map %)) (remove nil? children))
                  name (or (:person/name person) (str node-id))
                  title (or (:person/title person) "Employee")
                  dept (or (:person/department-name person) (:person/division-name person) (:person/unit-id person))
                  comp (:person/compensation person)
                  role (:person/role person)
                  synthetic? (true? (:person/is-synthetic? person))
                  acting-mgr? (or (true? (:person/acting-reporting-manager? person))
                                  (true? (:person/is-acting-reporting-manager? person)))
                  matches-search? (and (seq search-term)
                                       (or (str/includes? (str/lower-case name) search-term)
                                           (str/includes? (str/lower-case title) search-term)
                                           (when dept (str/includes? (str/lower-case (str dept)) search-term))))]
              [:div {:class (str "w-72 bg-white rounded-xl border p-4 shadow-sm transition-all duration-200 hover:shadow-md relative "
                                 (cond
                                   synthetic? "border-purple-300 bg-purple-50/20 ring-1 ring-purple-200"
                                   matches-search? "border-indigo-500 ring-2 ring-indigo-400 bg-indigo-50/20"
                                   :else "border-gray-200 hover:border-indigo-300"))}
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

                 ;; Acting Reporting Manager Badge
                 (when acting-mgr?
                   [:div {:class "mt-1.5 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-amber-50 text-amber-800 ring-1 ring-amber-400/30"}
                    "⚡ Acting Reporting Manager"])

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

               ;; Action Buttons: Set as Root in My Org + Expand / Collapse Toggle
               [:div {:class "mt-3 pt-2 border-t border-gray-100 flex items-center justify-between gap-2"}
                (if (not synthetic?)
                  (if (= node-id custom-root-id)
                    [:span {:class "inline-flex items-center gap-1 text-[11px] font-semibold text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded ring-1 ring-inset ring-indigo-700/20"}
                     "⭐ Current Root"]
                    [:button {:class "inline-flex items-center gap-1 rounded bg-gray-50 px-2 py-0.5 text-xs font-medium text-gray-700 hover:bg-indigo-50 hover:text-indigo-600 transition ring-1 ring-inset ring-gray-200 hover:ring-indigo-300 shadow-2xs"
                              :title "Set this employee as the root node in My Org"
                              :on {:click [(set-custom-root {:id node-id})]}}
                     "🎯 Set as Root"])
                  [:span {:class "text-[11px] text-purple-600 font-semibold italic"} "Co-Equal Leadership"])

                (when has-children?
                  [:button {:class "inline-flex items-center gap-1 rounded bg-gray-50 px-2 py-0.5 text-xs font-medium text-gray-700 hover:bg-indigo-50 hover:text-indigo-600 transition disabled:opacity-50"
                            :disabled loading?
                            :on {:click [(expand-or-fetch-branch {:id node-id :all-loaded? all-loaded?})]}}
                   (cond
                     loading? "⌛ Loading..."
                     collapsed? "▼ Expand"
                     :else "▲ Collapse")])]

               ;; Child count helper
               (when has-children?
                 [:div {:class "mt-1.5 text-right"}
                  [:span {:class "text-[10px] text-gray-400 font-medium"}
                   (str (count children) " Direct Report" (when (> (count children) 1) "s"))]])]))

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
                           (render-workforce-tree-node cid workforce-map headcounts-map hierarchy collapsed-nodes search-term can-view-comp?
                                                      custom-root-id loading-branches))
                         children)))])])))))

;; -----------------------------------------------------------------------------
;; Root Workforce Org Chart View
;; -----------------------------------------------------------------------------

(defrc WorkforceChart
  {:query [:loading :error :active-org :workforce :workforce-hierarchy :workforce-search
           :collapsed-workforce :permissions :headcounts-by-manager :abac/policy
           :active-chart-tab :custom-root-id :current-user/email :org/chart-settings
           :loading-branches :server-search-results :searching? :total-workforce-count
           :chart/pan :chart/zoom :chart/panning?
           :unconnected/workforce :unconnected/headcounts :unconnected/hierarchy
           :unconnected/roots :unconnected/count :unconnected-drawer-open?
           {:workforce/list (:query (meta WorkforceNode))}
           {:headcounts/list (:query (meta HeadcountCard))}]
   :ident :workforce-chart/root
   :ident-key :workforce-chart/root
   :route-segment ["org-chart"]}
  [{:keys [loading error active-org workforce workforce-hierarchy workforce-search
           collapsed-workforce permissions headcounts-by-manager active-chart-tab
           custom-root-id loading-branches server-search-results total-workforce-count] :as props}]
  (let [pan (get props :chart/pan {:x 0 :y 0})
        zoom (get props :chart/zoom 1.0)
        panning? (get props :chart/panning? false)
        unconnected-workforce (get props :unconnected/workforce [])
        unconnected-headcounts (get props :unconnected/headcounts [])
        unconnected-hierarchy (get props :unconnected/hierarchy {})
        unconnected-roots (get props :unconnected/roots [])
        unconnected-count (get props :unconnected/count 0)
        unconnected-drawer-open? (get props :unconnected-drawer-open? false)
        abac-policy (get props :abac/policy)
        chart-settings (or (get props :org/chart-settings) (get props :chart-settings) {})
        current-user-email (or (get props :current-user/email)
                               (when (exists? js/localStorage)
                                 (.getItem js/localStorage "email")))
        raw-workforce-map (or workforce {})
        raw-hierarchy (or workforce-hierarchy {})
        collapsed-nodes (or collapsed-workforce #{})
        can-view-comp? (if (contains? permissions :view-comp)
                         (true? (:view-comp permissions))
                         (contains? #{:admin :hr :vp :dept-head :hiring-manager} (:role permissions)))

        ;; Resolve full org root (Settings -> CEO title -> Max descendants)
        resolved-full (resolve-full-org-root (vals raw-workforce-map) raw-hierarchy chart-settings)
        synth-node (:synthetic-node resolved-full)
        workforce-map (if synth-node
                        (assoc raw-workforce-map (:person/id synth-node) synth-node)
                        raw-workforce-map)
        hierarchy (cond
                    synth-node
                    (assoc (dissoc raw-hierarchy nil)
                           nil ["__visual_root__"]
                           "__visual_root__" (:co-equal-ids resolved-full))

                    (:root-id resolved-full)
                    (assoc raw-hierarchy nil [(:root-id resolved-full)])

                    :else
                    raw-hierarchy)
        full-org-root-id (or (:root-id resolved-full) (first (get hierarchy nil [])))

        ;; Current user employee match (by email)
        my-employee (when (seq current-user-email)
                      (some (fn [[_ p]]
                              (when (= (some-> (:person/email p) str/lower-case)
                                       (str/lower-case current-user-email))
                                p))
                            workforce-map))

        ;; "My org" availability and effective root
        my-org-root-id (or custom-root-id (:person/id my-employee))
        my-org-available? (boolean (or (some? my-employee)
                                       (and (some? custom-root-id)
                                            (not= custom-root-id full-org-root-id))))
        effective-tab (if (and (= active-chart-tab :tab/my-org) my-org-available?)
                        :tab/my-org
                        :tab/full-org)
        active-root-ids (if (= effective-tab :tab/my-org)
                          (if my-org-root-id [my-org-root-id] [])
                          (if full-org-root-id [full-org-root-id] (get hierarchy nil [])))

        total-workforce-nodes (or total-workforce-count (count (remove :person/is-synthetic? (vals workforce-map))))
        loaded-members (count (remove :person/is-synthetic? (vals workforce-map)))
        raw-hcs-list (or (:headcounts/list props) [])
        raw-hcs-map (merge
                     (into {} (map (fn [h] [(:headcount/id h) h])) (apply concat (vals (or headcounts-by-manager {}))))
                     (into {} (map (fn [h] [(:headcount/id h) h])) raw-hcs-list)
                     (or (:headcounts props) {}))
        headcounts-map (into {}
                             (filter (fn [[_ h]] (abac/accessible-headcount? h abac-policy))
                                     raw-hcs-map))
        total-headcounts (count (vals headcounts-map))]
    [:div {:class "h-[calc(100vh-4rem)] bg-gray-50 flex flex-col font-sans overflow-hidden relative"}
     ;; Top Header Bar
     [:header {:class "bg-white border-b border-gray-200 sticky top-0 z-30 shadow-2xs"}
      [:div {:class "max-w-7xl mx-auto px-6 py-4 flex flex-col md:flex-row md:items-center md:justify-between gap-4"}
       [:div {:class "flex items-center gap-3"}
        [:div {:class "w-10 h-10 rounded-xl bg-indigo-600 text-white flex items-center justify-center shadow-xs"}
         [:svg {:class "w-5 h-5" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
          [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
                  :d "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"}]]]
        [:div
         [:h1 {:class "text-xl font-bold text-gray-900 tracking-tight"}
          (if active-org (str (:name active-org) " — Workforce Chart") "Workforce Chart")]
         [:p {:class "text-xs text-gray-500 font-medium"}
          "Organizational reporting hierarchy, leadership structure, and open headcount requisitions."]]]

       ;; Search, Filter & Action Buttons
       [:div {:class "flex flex-wrap items-center gap-3 w-full md:w-auto justify-end"}
        [:div {:class "relative w-full sm:w-72"}
         [:input {:type "text"
                  :value (or workforce-search "")
                  :placeholder "Search 10k workforce by name, title..."
                  :class "w-full pl-9 pr-3 py-1.5 text-xs bg-gray-50 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:bg-white transition"
                  :on {:input [(set-workforce-search {:term :event.target/value})]}}]
         (when (seq server-search-results)
           [:div {:class "absolute left-0 right-0 top-full mt-1 bg-white border border-gray-200 rounded-lg shadow-xl z-50 max-h-60 overflow-y-auto divide-y divide-gray-100"}
            (map (fn [res]
                   [:div {:class "px-3 py-2 hover:bg-indigo-50 cursor-pointer flex items-center justify-between transition text-xs"
                          :on {:click [(select-search-result {:result res})]}}
                    [:div {:class "flex flex-col min-w-0 pr-2"}
                     [:span {:class "font-bold text-gray-900 truncate"} (:person/name res)]
                     [:span {:class "text-[11px] text-gray-500 truncate"}
                      (str (:person/title res) (when-let [d (:person/department-name res)] (str " • " d)))]]
                    [:span {:class "shrink-0 text-[10px] text-indigo-600 font-semibold bg-indigo-50 px-1.5 py-0.5 rounded ring-1 ring-indigo-200"}
                     "Jump ➔"]])
                 server-search-results)])]

        [:button {:class "inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 transition shadow-xs"
                  :on {:click [(expand-all-workforce)]}}
         "Expand All"]

        [:button {:class "inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200 transition shadow-xs"
                  :on {:click [(collapse-all-workforce)]}}
         "Collapse All"]

        [:button {:class "inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-indigo-50 text-indigo-600 hover:bg-indigo-100 transition shadow-xs"
                  :on {:click [(refresh-workforce)]}}
         "↺ Refresh"]]]]

     ;; Navigation Tabs ("Full org" / "My org")
     [:div {:class "max-w-7xl mx-auto w-full px-6 pt-3 flex items-center justify-between border-b border-gray-200"}
      [:nav {:class "flex space-x-6"}
       [:button {:class (str "pb-3 text-sm font-semibold border-b-2 transition flex items-center gap-1.5 "
                             (if (= effective-tab :tab/full-org)
                               "border-indigo-600 text-indigo-600"
                               "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300"))
                 :on {:click [(set-active-tab {:tab :tab/full-org})]}}
        "🌐 Full org"]
       (when my-org-available?
         [:button {:class (str "pb-3 text-sm font-semibold border-b-2 transition flex items-center gap-1.5 "
                               (if (= effective-tab :tab/my-org)
                                 "border-indigo-600 text-indigo-600"
                                 "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300"))
                   :on {:click [(set-active-tab {:tab :tab/my-org})]}}
          "👤 My org"
          (when (and custom-root-id (not= custom-root-id (:person/id my-employee)))
            [:span {:class "ml-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-indigo-100 text-indigo-800"} "Custom Root"])])]]

     ;; Subtree banner when viewing "My org"
     (when (= effective-tab :tab/my-org)
       (let [root-person (get workforce-map my-org-root-id)]
         [:div {:class "max-w-7xl mx-auto w-full px-6 pt-3"}
          [:div {:class "flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 px-4 py-2.5 bg-indigo-50 border border-indigo-200 rounded-xl text-xs text-indigo-900 shadow-2xs"}
           [:div {:class "flex items-center gap-2"}
            [:span {:class "text-base"} "🎯"]
            [:div
             [:span {:class "font-bold"} "Viewing My Org: "]
             [:span {:class "font-medium"} (str (or (:person/name root-person) my-org-root-id)
                                                (when-let [t (:person/title root-person)] (str " — " t)))]]]
           [:div {:class "flex items-center gap-2"}
            (when (and custom-root-id my-employee (not= custom-root-id (:person/id my-employee)))
              [:button {:class "px-2.5 py-1 text-xs font-semibold rounded-lg bg-white border border-indigo-300 text-indigo-700 hover:bg-indigo-100 transition shadow-2xs"
                        :on {:click [(reset-custom-root)]}}
               "↺ Reset to My Profile"])
            [:button {:class "px-2.5 py-1 text-xs font-semibold rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 transition shadow-2xs"
                      :on {:click [(set-active-tab {:tab :tab/full-org})]}}
             "View Full Org"]]]]))

     ;; Stat Badges Row
     [:div {:class "max-w-7xl mx-auto w-full px-6 pt-4"}
      [:div {:class "flex flex-wrap gap-4"}
       [:div {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg shadow-xs text-xs"}
        [:span {:class "font-medium text-gray-500"} "👥 Total Workforce:"]
        [:span {:class "font-bold text-gray-900 font-mono"}
         (if (> total-workforce-nodes loaded-members)
           (str total-workforce-nodes " (" loaded-members " loaded)")
           total-workforce-nodes)]]

       [:div {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg shadow-xs text-xs"}
        [:span {:class "font-medium text-amber-600"} "📋 Open Headcounts:"]
        [:span {:class "font-bold text-amber-700 font-mono"} total-headcounts]]

       [:div {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-gray-200 rounded-lg shadow-xs text-xs"}
        [:span {:class "font-medium text-indigo-600"} "🔒 Comp Visibility:"]
        [:span {:class (str "font-bold font-mono " (if can-view-comp? "text-emerald-700" "text-gray-500"))}
         (if can-view-comp? "Full Access" "Restricted (Field-Level RBAC)")]]

       (when (> (or unconnected-count 0) 0)
         [:button {:class "flex items-center gap-2 px-3 py-1.5 bg-white border border-amber-300 hover:border-amber-500 rounded-lg shadow-xs text-xs cursor-pointer transition hover:bg-amber-50"
                   :title "View disconnected nodes"
                   :on {:click [(toggle-unconnected-drawer)]}}
          [:span {:class "font-medium text-amber-600"} "⚠️ Disconnected:"]
          [:span {:class "font-bold text-amber-800 font-mono"} (str unconnected-count)]])]]

     ;; Main Chart Interactive Canvas Viewport (GPU-Accelerated Pan & Zoom)
     [:main {:class "flex-1 relative overflow-hidden bg-gray-50/50"
             :style {:cursor (if panning? "grabbing" "grab")
                     :user-select "none"}
             :on {:pointerdown [(pan-start {})]
                  :pointermove [(pan-move {})]
                  :pointerup   [(pan-end)]
                  :pointercancel [(pan-end)]
                  :wheel       [(zoom-wheel {})]}}

      ;; Transform Plane Container
      [:div {:class "w-full h-full p-8 flex justify-center items-start"
             :style {:transform (str "translate3d(" (:x pan 0) "px, " (:y pan 0) "px, 0) scale(" (or zoom 1.0) ")")
                     :transform-origin "50% 0"
                     :will-change "transform"}}
       (cond
         loading
         [:div {:class "flex flex-col items-center justify-center h-64 gap-3 text-gray-500"}
          [:div {:class "animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"}]
          [:p {:class "text-sm font-medium"} "Loading workforce hierarchy..."]]

         error
         [:div {:class "p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm max-w-md"}
          [:p {:class "font-bold"} "Failed to load workforce chart"]
          [:p {:class "text-xs mt-1"} (str error)]]

         (empty? active-root-ids)
         [:div {:class "flex flex-col items-center justify-center h-64 text-gray-400 gap-2"}
          [:svg {:class "w-12 h-12 text-gray-300" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
           [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "1.5"
                   :d "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"}]]
          [:p {:class "text-sm font-medium text-gray-600"} "No workforce members registered in this organization."]
          [:p {:class "text-xs text-gray-400"} "When employees and managers are added, their hierarchy will render here."]]

         :else
         (into [:div {:class "flex flex-col items-center gap-12 min-w-max pb-16"}]
               (map (fn [rid]
                      (render-workforce-tree-node rid workforce-map headcounts-map hierarchy collapsed-nodes
                                                 workforce-search can-view-comp?
                                                 custom-root-id loading-branches))
                    active-root-ids)))]

      ;; Floating Zoom HUD Controls (Bottom Right)
      [:div {:class "absolute bottom-6 right-6 z-20 flex items-center gap-1.5 bg-white/95 backdrop-blur-xs border border-gray-200 shadow-md rounded-xl p-1.5"}
       [:button {:class "p-1.5 rounded-lg hover:bg-gray-100 text-gray-700 transition cursor-pointer"
                 :title "Zoom In (+15%)"
                 :on {:click [(zoom-in)]}}
        "➕"]
       [:button {:class "px-2 py-1 text-xs font-mono font-semibold text-gray-700 hover:bg-gray-100 rounded-lg transition cursor-pointer"
                 :title "Reset Zoom to 100%"
                 :on {:click [(zoom-reset)]}}
        (str (Math/round (* (or zoom 1.0) 100)) "%")]
       [:button {:class "p-1.5 rounded-lg hover:bg-gray-100 text-gray-700 transition cursor-pointer"
                 :title "Zoom Out (-15%)"
                 :on {:click [(zoom-out)]}}
        "➖"]
       [:div {:class "w-px h-5 bg-gray-200 mx-0.5"}]
       [:button {:class "px-2 py-1 text-xs font-medium text-gray-600 hover:bg-gray-100 rounded-lg transition cursor-pointer"
                 :title "Fit to View / Center"
                 :on {:click [(zoom-reset)]}}
        "⛶ Fit"]]

      ;; Unconnected Side Tree Drawer / Floating Trigger
      (when (> (or unconnected-count 0) 0)
        (unconnected-side-tree/render-unconnected-drawer
         {:open? unconnected-drawer-open?
          :unconnected-workforce unconnected-workforce
          :unconnected-headcounts unconnected-headcounts
          :unconnected-hierarchy unconnected-hierarchy
          :unconnected-roots unconnected-roots
          :unconnected-count unconnected-count}))]]))

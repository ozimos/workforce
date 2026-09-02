(ns com.ozimos.workforce.frontend.ui.pages.people-chart-replicant
  "Pure Replicant People Org Chart: renders human reporting trees (manager -> reports),
   avatar badges, job titles, department tags, and conditional compensation figures
   based on role-based access control (:view-comp permission).

   ABAC Headcount Filtering
   ========================
   Employees (people nodes) are always shown — no ABAC applies to person visibility.
   Headcounts (open/in-approval positions) ARE subject to ABAC. A user's
   :abac/headcount-policy map specifies which dimension combinations they may see:

     {:allowed-divisions  #{\"ENG\" \"SALES\"}   ;; nil = unrestricted
      :allowed-depts      #{\"FE\" \"BE\"}        ;; nil = unrestricted
      :allowed-levels     #{\"L3\" \"L4\" \"L5\"}  ;; nil = unrestricted
      :allowed-locations  #{\"US\" \"EU\"}}       ;; nil = unrestricted

   A headcount is visible iff ALL provided allow-sets admit its attributes.
   Missing allow-set (nil) means the dimension is unrestricted."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

;; -----------------------------------------------------------------------------
;; ABAC: Pure Headcount Access Predicate (shared Web / Mobile / SSR)
;; -----------------------------------------------------------------------------

(defn accessible-headcount?
  "Returns true iff `headcount` is visible to a user with `policy`.
   `policy` is the :abac/headcount-policy map from app state (may be nil).

   A nil policy (e.g. admin / unrestricted user) grants access to all headcounts.
   Each non-nil set in the policy restricts access: the headcount must match at
   least one value in the set for that dimension. All dimensions must pass.

   Headcount attributes checked:
     :headcount/division-id  vs :allowed-divisions
     :headcount/dept-id      vs :allowed-depts
     :headcount/job-level    vs :allowed-levels
     :headcount/location     vs :allowed-locations"
  [headcount policy]
  (if (nil? policy)
    true
    (let [{:keys [allowed-divisions allowed-depts allowed-levels allowed-locations]} policy
          div    (:headcount/division-id headcount)
          dept   (:headcount/dept-id headcount)
          level  (:headcount/job-level headcount)
          loc    (:headcount/location headcount)]
      (and (or (nil? allowed-divisions) (contains? allowed-divisions div))
           (or (nil? allowed-depts)     (contains? allowed-depts dept))
           (or (nil? allowed-levels)    (contains? allowed-levels level))
           (or (nil? allowed-locations) (contains? allowed-locations loc))))))

(defn filter-accessible-headcounts
  "Pure: returns the subset of `headcounts` (a seq of headcount maps) that the
   user can access according to `abac-policy`. If `abac-policy` is nil,
   all headcounts are returned."
  [headcounts abac-policy]
  (if (nil? abac-policy)
    (vec headcounts)
    (filterv #(accessible-headcount? % abac-policy) headcounts)))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (shared Web / Mobile)
;; -----------------------------------------------------------------------------

(defn toggle-person-collapse-state
  "Pure: toggle `person-id` in :collapsed-people set."
  [db person-id]
  (update db :collapsed-people
          (fn [s]
            (let [s (or s #{})]
              (if (contains? s person-id)
                (disj s person-id)
                (conj s person-id))))))

(defn expand-all-people-state
  "Pure: expand all nodes in the people tree."
  [db]
  (assoc db :collapsed-people #{}))

(defn collapse-all-people-state
  "Pure: collapse all people nodes."
  [db]
  (let [people-map (:people db {})
        all-ids (set (keys people-map))]
    (assoc db :collapsed-people all-ids)))

(defn set-people-search-state
  "Pure: update search filter string."
  [db search-str]
  (assoc db :people-search search-str))

;; -----------------------------------------------------------------------------
;; Fulcro Mutations
;; -----------------------------------------------------------------------------

(defmutation toggle-person-collapse
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state toggle-person-collapse-state id)))

(defmutation expand-all-people
  [_]
  (action [{:keys [state]}]
    (swap! state expand-all-people-state)))

(defmutation collapse-all-people
  [_]
  (action [{:keys [state]}]
    (swap! state collapse-all-people-state)))

(defmutation set-people-search
  [{:keys [value]}]
  (action [{:keys [state]}]
    (swap! state set-people-search-state value)))

;; -----------------------------------------------------------------------------
;; View Sub-components & Helpers
;; -----------------------------------------------------------------------------

(defn- format-currency [amount currency]
  (if (and amount (number? amount))
    (str (case currency
           "USD" "$"
           "GBP" "£"
           "EUR" "€"
           "$")
         (js/Math.round amount))
    (str (or amount ""))))

(defn- get-avatar-initials [name]
  (if (seq name)
    (let [parts (str/split (str/trim name) #"\s+")]
      (if (>= (count parts) 2)
        (str (first (first parts)) (first (second parts)))
        (subs (first parts) 0 (min 2 (count (first parts))))))
    "U"))

(defn- avatar-badge [name role-kw]
  (let [initials (str/upper-case (get-avatar-initials name))
        bg-color (case role-kw
                   :admin "bg-purple-600 text-white"
                   :dept-head "bg-indigo-600 text-white"
                   :hiring-manager "bg-blue-600 text-white"
                   :vp "bg-amber-600 text-white"
                   "bg-emerald-600 text-white")]
    [:div {:class (str "flex h-10 w-10 shrink-0 items-center justify-center rounded-full font-bold text-xs shadow-sm " bg-color)}
     initials]))

(defn- headcount-badge
  "Renders a compact badge for a visible headcount under a person node."
  [hc]
  [:div {:replicant/key (str (:headcount/id hc))
         :class "flex items-center gap-1.5 px-2 py-1 bg-amber-50 rounded-md border border-amber-200 text-[11px]"}
   [:span {:class "inline-flex items-center justify-center h-4 w-4 rounded-full bg-amber-200 text-amber-800 font-bold text-[10px]"} "HC"]
   [:span {:class "font-medium text-amber-800 truncate max-w-[120px]"} (or (:headcount/title hc) "Open Position")]
   (when-let [level (:headcount/job-level hc)]
     [:span {:class "text-amber-600 font-mono"} level])
   (when-let [loc (:headcount/location hc)]
     [:span {:class "text-amber-500"} (str "· " loc)])])

(defn- headcounts-for-person
  "Returns visible headcounts (after ABAC filtering) for `person-id`.
   `headcounts-by-manager` is a map of manager-id -> [headcount ...].
   `abac-policy` is the :abac/headcount-policy from app state (nil = all visible)."
  [person-id headcounts-by-manager abac-policy]
  (let [hcs (get headcounts-by-manager person-id [])]
    (filter-accessible-headcounts hcs abac-policy)))

(defrc PersonCard
  {:query [:person/id :person/name :person/title :person/email :person/unit-id
           :person/department-name :person/division-name :person/role
           :person/manager-id :person/direct-reports-count :person/compensation]
   :ident :person/id}
  [{:keys [person/id person/name person/title person/department-name
           person/division-name person/role]}]
  [:div {:replicant/key (str id)
         :class "p-4 bg-white rounded-xl border border-gray-200 shadow-sm"}
   [:div {:class "flex items-center gap-3"}
    (avatar-badge (or name (str id)) role)
    [:div
     [:h4 {:class "font-bold text-sm text-gray-900"} (or name (str id))]
     [:p {:class "text-xs text-gray-500 font-medium"} (or title "Employee")]
     (when (or department-name division-name)
       [:p {:class "text-xs text-indigo-600 mt-0.5"} (or department-name division-name)])]]])

(defn- render-person-tree-node
  [person-id people-map hierarchy collapsed-nodes search-term can-view-comp?
   headcounts-by-manager abac-policy]
  (let [person (get people-map person-id {:person/id person-id :person/name (str person-id)})
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
        ;; ABAC-filtered headcounts for this person (as a manager)
        visible-hcs (headcounts-for-person person-id headcounts-by-manager abac-policy)]
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
        ;; Compensation details (Field-Level RBAC Protected — view-comp permission)
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
      ;; Note: employees are always shown; only headcounts are ABAC-filtered.
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
                   :on {:click [(toggle-person-collapse {:id person-id})]}}
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
                      (render-person-tree-node cid people-map hierarchy collapsed-nodes search-term can-view-comp?
                                               headcounts-by-manager abac-policy))
                    children)))])]))

;; -----------------------------------------------------------------------------
;; Root People Org Chart View
;; -----------------------------------------------------------------------------

(defrc PeopleChartReplicant
  {:query [:loading :error :active-org :people :people-hierarchy :people-search
           :collapsed-people :permissions :headcounts-by-manager :abac/headcount-policy
           {:people/list (:query (meta PersonCard))}]
   :ident :people-chart-replicant/root
   :ident-key :people-chart-replicant/root
   :route-segment ["org-chart"]}
  [{:keys [loading error active-org people people-hierarchy people-search
           collapsed-people permissions headcounts-by-manager] :as props}]
  (let [abac-policy (get props :abac/headcount-policy)
        people-map (or people
                       {"u-alice" {:person/id "u-alice" :person/name "Alice Smith" :person/title "Chief Executive Officer & Founder" :person/role :admin :person/department-name "Executive" :person/compensation {:salary 320000 :currency "USD"}}
                        "u-frank-vp" {:person/id "u-frank-vp" :person/name "Frank Miller" :person/title "VP of Engineering" :person/role :vp :person/department-name "Engineering Division" :person/manager-id "u-alice" :person/compensation {:salary 240000 :currency "USD"}}
                        "u-carol" {:person/id "u-carol" :person/name "Carol White" :person/title "Head of Core Systems" :person/role :dept-head :person/department-name "Backend Systems" :person/manager-id "u-frank-vp" :person/compensation {:salary 195000 :currency "USD"}}
                        "u-dan-mgr" {:person/id "u-dan-mgr" :person/name "Dan Johnson" :person/title "Engineering Manager (Distributed)" :person/role :hiring-manager :person/department-name "Backend Systems" :person/manager-id "u-carol" :person/compensation {:salary 175000 :currency "USD"}}
                        "u-eva-lead" {:person/id "u-eva-lead" :person/name "Eva Davis" :person/title "Staff Frontend Lead" :person/role :hiring-manager :person/department-name "Frontend & Web" :person/manager-id "u-frank-vp" :person/compensation {:salary 180000 :currency "USD"}}
                        "u-ian-eng" {:person/id "u-ian-eng" :person/name "Ian Taylor" :person/title "Senior Systems Engineer" :person/role :employee :person/department-name "Backend Systems" :person/manager-id "u-dan-mgr" :person/compensation {:salary 165000 :currency "USD"}}
                        "u-jane-eng" {:person/id "u-jane-eng" :person/name "Jane Wilson" :person/title "Frontend Engineer" :person/role :employee :person/department-name "Frontend & Web" :person/manager-id "u-eva-lead" :person/compensation {:salary 145000 :currency "USD"}}})
        hierarchy (or people-hierarchy
                      {nil ["u-alice"]
                       "u-alice" ["u-frank-vp"]
                       "u-frank-vp" ["u-carol" "u-eva-lead"]
                       "u-carol" ["u-dan-mgr"]
                       "u-dan-mgr" ["u-ian-eng"]
                       "u-eva-lead" ["u-jane-eng"]})
        root-people (or (get hierarchy nil) ["u-alice"])
        collapsed-nodes (or collapsed-people #{})
        user-role (keyword (or (:org/role active-org) "employee"))
        can-view-comp? (or (= user-role :admin)
                           (= user-role :hr)
                           (= user-role :dept-head)
                           (get-in permissions [:view-comp] false)
                           (get-in permissions [user-role :view-comp] false))
        ;; headcounts-by-manager: map of manager-id -> [headcount ...]
        ;; nil = no headcount data loaded (show nothing)
        hcs-by-mgr (or headcounts-by-manager {})
        total-people (count people-map)]
    [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-6"}
     ;; Page Header & Navigation to Alternate View
     [:div {:class "border-b border-gray-200 pb-6 flex flex-col md:flex-row md:items-center md:justify-between gap-4"}
      [:div
       [:div {:class "flex items-center gap-2"}
        [:h1 {:class "text-2xl font-bold leading-7 text-gray-900"} "People Organization Chart"]
        (when active-org
          [:span {:class "inline-flex items-center rounded-md bg-indigo-50 px-2.5 py-0.5 text-xs font-semibold text-indigo-700 ring-1 ring-inset ring-indigo-700/10"}
           (:org/name active-org)])]
       [:p {:class "mt-1 text-sm text-gray-500"}
        "Human reporting hierarchy, managers, direct reports, and team leadership"]]

      [:div {:class "flex items-center gap-3"}
       ;; Link to Divisions/Departments Chart 2
       [:a {:href "/org-chart-2"
            :class "inline-flex items-center rounded-md bg-white px-3 py-2 text-xs font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"}
        "🏢 View Divisions & Depts (/org-chart-2)"]
       [:button {:class "inline-flex items-center rounded-md bg-white px-3 py-2 text-xs font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
                 :on {:click [(expand-all-people {})]}}
        "Expand All"]
       [:button {:class "inline-flex items-center rounded-md bg-white px-3 py-2 text-xs font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
                 :on {:click [(collapse-all-people {})]}}
        "Collapse All"]]]

     ;; Controls, KPIs, and Search Bar
     [:div {:class "grid grid-cols-1 md:grid-cols-4 gap-4 bg-white p-4 rounded-xl border border-gray-200 shadow-sm items-center"}
      [:div {:class "md:col-span-2"}
       [:input {:type "text"
                :placeholder "Search people by name, title, or department..."
                :value (or people-search "")
                :on {:input [::set-people-search]}
                :class "w-full rounded-md border-0 px-3 py-2 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm"}]]

      [:div {:class "flex items-center gap-4 text-xs font-medium text-gray-600 md:col-span-2 justify-end"}
       [:div {:class "flex items-center gap-1.5 bg-indigo-50 text-indigo-700 px-3 py-1.5 rounded-md font-semibold"}
        [:span "👥 Total Members:"]
        [:span {:class "font-bold"} total-people]]
       (if can-view-comp?
         [:div {:class "flex items-center gap-1 text-emerald-700 bg-emerald-50 px-2.5 py-1.5 rounded-md text-xs"}
          "🔓 Comp Visible"]
         [:div {:class "flex items-center gap-1 text-gray-500 bg-gray-50 px-2.5 py-1.5 rounded-md text-xs"}
          "🔒 Comp Masked"])
       ;; ABAC policy indicator
       (if abac-policy
         [:div {:class "flex items-center gap-1 text-amber-700 bg-amber-50 px-2.5 py-1.5 rounded-md text-xs"}
          "⚙️ HC Scoped"]
         [:div {:class "flex items-center gap-1 text-gray-400 bg-gray-50 px-2.5 py-1.5 rounded-md text-xs"}
          "HC Unrestricted"])]]

     ;; People Tree Canvas
     [:div {:class "rounded-xl border border-gray-200 bg-gray-50/50 p-8 shadow-inner overflow-x-auto min-h-[480px]"}
      (cond
        loading
        [:div {:class "flex items-center justify-center py-20"}
         [:p {:class "text-sm text-gray-500 animate-pulse"} "Loading people organization tree..."]]

        error
        [:div {:class "rounded-md bg-red-50 p-4"}
         [:p {:class "text-sm text-red-700"} error]]

        :else
        [:div {:class "flex justify-center min-w-max py-4"}
         (into [:div {:class "flex flex-col items-center gap-8"}]
               (map (fn [root-id]
                      (render-person-tree-node root-id people-map hierarchy collapsed-nodes people-search can-view-comp?
                                               hcs-by-mgr abac-policy))
                    root-people))])]]))

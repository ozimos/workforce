(ns com.ozimos.workforce.frontend.ui.pages.org-chart
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div h1 h2 h3 input label option p select span table tbody td th thead tr]]
   [com.ozimos.workforce.frontend.transit :as transit]))

;; -----------------------------------------------------------------------------
;; Data Fetching & Mutations
;; -----------------------------------------------------------------------------

(defn- fetch-chart-data! [this]
  (comp/set-state! this {:loading true :error nil})
  (-> (transit/fetch-transit "/api/query"
        [{:user/active-org [:org/id :org/name :org/role]}])
      (.then (fn [{:keys [body]}]
               (if-let [active-org (:user/active-org body)]
                 (let [org-id (:org/id active-org)]
                   (-> (transit/fetch-transit "/api/query"
                         [{[:org/id org-id]
                           [{:org/chart [:org/id :org/hierarchy
                                         {:org/units [:unit/id :unit/name :unit/division-id
                                                      :unit/dept-id :unit/parent-id :unit/budget
                                                      :unit/filled :unit/open :unit/pending
                                                      :unit/actors :unit/children]}]}]}])
                       (.then (fn [{:keys [body]}]
                                (let [chart (get-in body [[:org/id org-id] :org/chart])
                                      unit-list (:org/units chart)
                                      unit-map (into {} (map (fn [u] [(:unit/id u) u])) unit-list)
                                      hier (or (:org/hierarchy chart)
                                               (reduce (fn [acc u]
                                                         (let [p (:unit/parent-id u)]
                                                           (update acc p (fnil conj []) (:unit/id u))))
                                                       {}
                                                       unit-list))]
                                  (comp/set-state! this {:active-org active-org
                                                         :units unit-map
                                                         :hierarchy hier
                                                         :loading false}))))
                       (.catch (fn [err]
                                 (comp/set-state! this {:loading false :error (str "Failed to load org chart: " err)})))))
                 (comp/set-state! this {:loading false :error "No active organization selected"}))))
      (.catch (fn [err]
                (comp/set-state! this {:loading false :error (str "Failed to load organization info: " err)})))))

(defn- submit-create-unit! [this]
  (let [{:keys [active-org create-form]} (comp/get-state this)
        org-id (:org/id active-org)
        unit-id (str/trim (or (:id create-form) ""))
        name (str/trim (or (:name create-form) ""))
        div (str/trim (or (:division-id create-form) ""))
        dept (str/trim (or (:dept-id create-form) ""))
        parent-id (:parent-id create-form)
        budget (js/parseInt (str (or (:budget create-form) 0)) 10)]
    (if (or (empty? unit-id) (empty? name))
      (comp/set-state! this {:create-error "Unit ID and Name are required."})
      (do
        (comp/set-state! this {:create-loading true :create-error nil})
        (let [mut (list 'unit/create {:unit/org-id org-id
                                      :unit/id unit-id
                                      :unit/name name
                                      :unit/division-id (if (seq div) div "ORG")
                                      :unit/dept-id (if (seq dept) dept "ALL")
                                      :unit/parent-id (if (and parent-id (not= parent-id "none") (not= parent-id ""))
                                                        parent-id
                                                        nil)
                                      :unit/budget (if (js/isNaN budget) 0 budget)})]
          (-> (transit/fetch-transit "/api/query" [{mut [:unit/id :unit/name :error]}])
              (.then (fn [{:keys [body]}]
                       (let [res (first (vals body))]
                         (if-let [err (:error res)]
                           (comp/set-state! this {:create-loading false
                                                  :create-error (or (:message err) "Failed to create unit")})
                           (do
                             (comp/set-state! this {:show-create-modal false
                                                    :create-loading false
                                                    :create-form {:id "" :name "" :division-id "" :dept-id "" :parent-id nil :budget 5}})
                             (fetch-chart-data! this))))))
              (.catch (fn [err]
                        (comp/set-state! this {:create-loading false
                                               :create-error (str "Network error: " err)})))))))))

(defn- submit-update-budget! [this]
  (let [{:keys [active-org budget-unit budget-val]} (comp/get-state this)
        org-id (:org/id active-org)
        unit-id (:unit/id budget-unit)
        parsed-budget (js/parseInt (str budget-val) 10)]
    (if (or (js/isNaN parsed-budget) (< parsed-budget 0))
      (comp/set-state! this {:budget-error "Please enter a valid positive number for budget."})
      (do
        (comp/set-state! this {:budget-loading true :budget-error nil})
        (let [mut (list 'unit/set-budget {:unit/org-id org-id
                                          :unit/id unit-id
                                          :unit/budget parsed-budget})]
          (-> (transit/fetch-transit "/api/query" [{mut [:unit/id :unit/budget :error]}])
              (.then (fn [{:keys [body]}]
                       (let [res (first (vals body))]
                         (if-let [err (:error res)]
                           (comp/set-state! this {:budget-loading false
                                                  :budget-error (or (:message err) "Failed to update budget")})
                           (do
                             (comp/set-state! this {:show-budget-modal false
                                                    :budget-loading false
                                                    :budget-unit nil})
                             (fetch-chart-data! this))))))
              (.catch (fn [err]
                        (comp/set-state! this {:budget-loading false
                                               :budget-error (str "Network error: " err)})))))))))

;; -----------------------------------------------------------------------------
;; Sub-Components & UI Cards
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
    (div {:className (str "inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold ring-1 ring-inset " classes)}
      (span {:className "font-normal text-gray-500"} (str label ":"))
      (span {:className "font-bold"} (str (or val 0))))))

(defn- toggle-node-collapsed! [this unit-id]
  (let [s (comp/get-state this)
        c (or (:collapsed-nodes s) #{})
        next-c (if (contains? c unit-id)
                 (disj c unit-id)
                 (conj c unit-id))]
    (comp/set-state! this (assoc s :collapsed-nodes next-c))))

(defn- render-unit-card [this unit is-root? children-count collapsed? search-match?]
  (let [unit-id (:unit/id unit)
        is-div? (or is-root? (nil? (:unit/parent-id unit)))
        actors (:unit/actors unit {})
        has-children? (pos? children-count)]
    (div {:onClick (fn [e]
                     (if has-children?
                       (toggle-node-collapsed! this unit-id)
                       (set! js/window.location.href (str "/dept-dashboard?unit-id=" unit-id))))
          :className (str "relative rounded-xl border p-5 shadow-sm transition-all duration-200 cursor-pointer select-none "
                          (if is-div?
                            "bg-white border-indigo-100 hover:border-indigo-400 hover:shadow-md ring-1 ring-indigo-50"
                            "bg-white border-gray-200 hover:border-indigo-300 hover:shadow-md")
                          (when search-match? " ring-2 ring-indigo-500 bg-indigo-50/20"))}
      ;; Header row
      (div {:className "flex items-start justify-between gap-4"}
        (div {:className "flex items-center gap-3"}
          ;; Expand / Collapse toggle button for parents
          (if has-children?
            (button {:onClick (fn [e]
                                (.stopPropagation e)
                                (toggle-node-collapsed! this unit-id))
                     :className "flex h-5.5 w-5.5 items-center justify-center rounded-md bg-gray-100 text-gray-500 hover:bg-indigo-50 hover:text-indigo-600 transition focus:outline-none shrink-0"
                     :title (if collapsed? "Expand child units" "Collapse child units")}
              (dom/svg {:xmlns "http://www.w3.org/2000/svg"
                        :className (str "h-3 w-3 shrink-0 transition-transform duration-200 " (if collapsed? "-rotate-90" "rotate-0"))
                        :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2.5" :d "M19 9l-7 7-7-7"})))
            (div {:className "h-5.5 w-5.5 flex items-center justify-center shrink-0"}
              (div {:className (str "h-1.5 w-1.5 rounded-full " (if is-div? "bg-purple-500" "bg-indigo-500"))})))

          (div nil
            (div {:className "flex items-center gap-2"}
              (h3 {:className "text-base font-bold text-gray-900"} (or (:unit/name unit) unit-id))
              (span {:className (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold "
                                     (if is-div?
                                       "bg-purple-100 text-purple-700 ring-1 ring-inset ring-purple-700/10"
                                       "bg-blue-100 text-blue-700 ring-1 ring-inset ring-blue-700/10"))}
                (if is-div? "Division" "Department")))
            (p {:className "text-xs font-mono text-gray-400 mt-0.5"}
              (str unit-id " • " (or (:unit/division-id unit) "ORG") "/" (or (:unit/dept-id unit) "ALL")))))

        ;; Actions Button Bar
        (div {:className "flex items-center gap-1.5"
              :onClick #(.stopPropagation %)}
          (button {:onClick (fn [e]
                              (.stopPropagation e)
                              (comp/set-state! this {:show-create-modal true
                                                     :create-form {:id "" :name ""
                                                                   :division-id (or (:unit/division-id unit) "")
                                                                   :dept-id ""
                                                                   :parent-id unit-id
                                                                   :budget 5}
                                                     :create-error nil}))
                   :className "rounded-md bg-gray-50 px-2.5 py-1 text-xs font-semibold text-gray-700 hover:bg-indigo-50 hover:text-indigo-700 ring-1 ring-inset ring-gray-200 transition"
                   :title "Add sub-department under this unit"}
            "+ Sub-unit")
          (button {:onClick (fn [e]
                              (.stopPropagation e)
                              (comp/set-state! this {:show-budget-modal true
                                                     :budget-unit unit
                                                     :budget-val (:unit/budget unit 0)
                                                     :budget-error nil}))
                   :className "rounded-md bg-gray-50 px-2.5 py-1 text-xs font-semibold text-gray-700 hover:bg-gray-100 ring-1 ring-inset ring-gray-200 transition"
                   :title "Edit headcount budget"}
            "Budget")
          (a {:href (str "/dept-dashboard?unit-id=" unit-id)
              :onClick #(.stopPropagation %)
              :className "rounded-md bg-indigo-50 px-2.5 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-100 ring-1 ring-inset ring-indigo-700/10 transition inline-block"}
            "Analytics →")))

      ;; KPI Metrics Badges Row
      (div {:className "mt-4 flex flex-wrap items-center gap-2 pt-3 border-t border-gray-100"}
        (kpi-badge "Budget" (:unit/budget unit 0) :purple)
        (kpi-badge "Filled" (:unit/filled unit 0) :emerald)
        (kpi-badge "Open" (:unit/open unit 0) :indigo)
        (kpi-badge "In Approval" (:unit/pending unit 0) :amber)
        (when has-children?
          (span {:className "text-xs font-medium text-gray-400 ml-auto"}
            (str children-count (if (= 1 children-count) " child unit" " child units")))))

      ;; Scoped Actors Row (if present)
      (when (seq actors)
        (div {:className "mt-3 flex flex-wrap items-center gap-2 pt-2 text-xs text-gray-600 bg-slate-50/70 p-2.5 rounded-lg border border-slate-100"}
          (span {:className "font-semibold text-gray-500"} "Actors:")
          (mapv (fn [[role uid]]
                  (span {:key (str role) :className "inline-flex items-center gap-1 bg-white px-2 py-0.5 rounded border border-gray-200 shadow-2xs"}
                    (span {:className "font-medium text-indigo-700"} (format-role-name role))
                    (span {:className "text-gray-400"} "→")
                    (span {:className "font-mono font-semibold text-gray-700"} (str uid))))
                actors))))))

(defn- render-tree-branch [this node-id units hierarchy depth search-term collapsed-nodes]
  (let [unit (get units node-id {:unit-id node-id :name node-id})
        children (get hierarchy node-id [])
        is-collapsed? (contains? collapsed-nodes node-id)
        search-clean (when (seq search-term) (str/lower-case search-term))
        matches-search? (and (seq search-clean)
                             (or (str/includes? (str/lower-case (or (:unit/name unit) "")) search-clean)
                                 (str/includes? (str/lower-case (or (:unit/id unit) "")) search-clean)
                                 (str/includes? (str/lower-case (or (:unit/division-id unit) "")) search-clean)
                                 (str/includes? (str/lower-case (or (:unit/dept-id unit) "")) search-clean)))]
    (div {:key (str node-id) :className (str "relative " (when (pos? depth) "ml-8 mt-3"))}
      ;; Tree connector vertical & horizontal line for child branches
      (when (pos? depth)
        (div {:className "absolute -left-6 top-6 w-5 border-t-2 border-indigo-200"}))

      (render-unit-card this unit (zero? depth) (count children) is-collapsed? matches-search?)

      ;; Render children recursively if not collapsed
      (when (and (seq children) (not is-collapsed?))
        (div {:className "relative border-l-2 border-indigo-200 ml-4 pl-2 space-y-3 mt-1"}
          (mapv (fn [child-id]
                  (render-tree-branch this child-id units hierarchy (inc depth) search-term collapsed-nodes))
                children))))))

;; -----------------------------------------------------------------------------
;; Modals (Create Unit & Edit Budget)
;; -----------------------------------------------------------------------------

(defn- render-create-unit-modal [this active-org units create-form create-loading create-error]
  (let [unit-options (sort-by :name (vals units))]
    (div {:className "fixed inset-0 z-50 flex items-center justify-center bg-gray-900/50 backdrop-blur-xs p-4"}
      (div {:className "w-full max-w-lg rounded-2xl bg-white p-6 shadow-2xl border border-gray-100 space-y-5 animate-in fade-in zoom-in-95 duration-150"}
        (div {:className "flex items-center justify-between border-b border-gray-100 pb-4"}
          (div nil
            (h2 {:className "text-lg font-bold text-gray-900"} "Add Organization Unit")
            (p {:className "text-xs text-gray-500 mt-0.5"}
              (str "Create a new division or department in " (or (:org/name active-org) "organization"))))
          (button {:onClick #(comp/set-state! this {:show-create-modal false})
                   :className "rounded-lg p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-500"}
            (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "h-4 w-4 shrink-0" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
              (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M6 18L18 6M6 6l12 12"}))))

        (when create-error
          (div {:className "rounded-lg bg-red-50 p-3 text-xs font-medium text-red-700 border border-red-200"}
            create-error))

        (div {:className "space-y-4 text-sm"}
          ;; Unit ID & Name
          (div {:className "grid grid-cols-1 sm:grid-cols-2 gap-3"}
            (div nil
              (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Unit ID (Code)*")
              (input {:type "text" :placeholder "e.g. dept-platform"
                      :value (or (:id create-form) "")
                      :onChange (fn [e] (let [v (.. e -target -value) s (comp/get-state this)]
                                          (comp/set-state! this (assoc-in s [:create-form :id] v))))
                      :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}))
            (div nil
              (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Unit Name*")
              (input {:type "text" :placeholder "e.g. Platform Infrastructure"
                      :value (or (:name create-form) "")
                      :onChange (fn [e] (let [v (.. e -target -value) s (comp/get-state this)]
                                          (comp/set-state! this (assoc-in s [:create-form :name] v))))
                      :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"})))

          ;; Division Code & Dept Code
          (div {:className "grid grid-cols-1 sm:grid-cols-2 gap-3"}
            (div nil
              (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Division Code")
              (input {:type "text" :placeholder "e.g. ENG"
                      :value (or (:division-id create-form) "")
                      :onChange (fn [e] (let [v (.. e -target -value) s (comp/get-state this)]
                                          (comp/set-state! this (assoc-in s [:create-form :division-id] v))))
                      :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}))
            (div nil
              (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Department Code")
              (input {:type "text" :placeholder "e.g. PLAT"
                      :value (or (:dept-id create-form) "")
                      :onChange (fn [e] (let [v (.. e -target -value) s (comp/get-state this)]
                                          (comp/set-state! this (assoc-in s [:create-form :dept-id] v))))
                      :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"})))

          ;; Parent Unit Selector
          (div nil
            (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Parent Organizational Unit")
            (select {:value (or (:parent-id create-form) "none")
                     :onChange (fn [e]
                                 (let [v (.. e -target -value)
                                       s (comp/get-state this)]
                                   (comp/set-state! this (assoc-in s [:create-form :parent-id] (if (= v "none") nil v)))))
                     :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}
              (option {:value "none"} "None (Root Division)")
              (mapv (fn [u]
                      (option {:key (str (:unit/id u)) :value (str (:unit/id u))}
                        (str (:unit/name u) " (" (:unit/id u) ")")))
                    unit-options)))

          ;; Initial Headcount Target
          (div nil
            (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Initial Headcount Budget Target")
            (input {:type "number" :min "0" :max "500"
                    :value (or (:budget create-form) 5)
                    :onChange (fn [e] (let [v (.. e -target -value) s (comp/get-state this)]
                                        (comp/set-state! this (assoc-in s [:create-form :budget] v))))
                    :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"})))

        ;; Modal Footer Actions
        (div {:className "flex items-center justify-end gap-3 pt-3 border-t border-gray-100"}
          (button {:onClick #(comp/set-state! this {:show-create-modal false})
                   :className "rounded-lg border border-gray-300 bg-white px-4 py-2 text-xs font-semibold text-gray-700 shadow-2xs hover:bg-gray-50 transition"}
            "Cancel")
          (button {:onClick #(submit-create-unit! this)
                   :disabled create-loading
                   :className "inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50 transition"}
            (when create-loading
              (div {:className "h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent"}))
            "Create Unit"))))))

;; -----------------------------------------------------------------------------
;; Budget Modal
;; -----------------------------------------------------------------------------

(defn- render-budget-modal [this unit budget-val budget-loading budget-error]
  (div {:className "fixed inset-0 z-50 flex items-center justify-center bg-gray-900/50 backdrop-blur-xs p-4"}
    (div {:className "w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl border border-gray-100 space-y-4 animate-in fade-in zoom-in-95 duration-150"}
      (div {:className "flex items-center justify-between border-b border-gray-100 pb-3"}
        (div nil
          (h2 {:className "text-base font-bold text-gray-900"} "Update Headcount Budget")
          (p {:className "text-xs text-gray-500 mt-0.5"}
            (str "Set target headcount seats for " (or (:unit/name unit) (:unit/id unit)))))
        (button {:onClick #(comp/set-state! this {:show-budget-modal false})
                 :className "rounded-lg p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-500"}
          (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "h-4 w-4 shrink-0" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
            (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M6 18L18 6M6 6l12 12"}))))

      (when budget-error
        (div {:className "rounded-lg bg-red-50 p-3 text-xs font-medium text-red-700 border border-red-200"}
          budget-error))

      (div {:className "space-y-3 text-sm"}
        (label {:className "block text-xs font-semibold text-gray-700 mb-1"} "Target Headcount Budget (Seats)")
        (input {:type "number" :min "0" :max "500"
                :value (or budget-val 0)
                :onChange (fn [e] (let [v (.. e -target -value) s (comp/get-state this)]
                                    (comp/set-state! this (assoc s :budget-val v))))
                :className "w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"}))

      (div {:className "flex items-center justify-end gap-3 pt-3 border-t border-gray-100"}
        (button {:onClick #(comp/set-state! this {:show-budget-modal false})
                 :className "rounded-lg border border-gray-300 bg-white px-4 py-2 text-xs font-semibold text-gray-700 shadow-2xs hover:bg-gray-50 transition"}
          "Cancel")
        (button {:onClick #(submit-update-budget! this)
                 :disabled budget-loading
                 :className "inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50 transition"}
          (when budget-loading
            (div {:className "h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent"}))
          "Save Changes")))))

;; -----------------------------------------------------------------------------
;; Main Org Chart Page Component
;; -----------------------------------------------------------------------------

(defsc OrgChart [this _props]
  {:query [:loading :error :active-org :units :hierarchy :search-term
           :collapsed-nodes :show-create-modal :create-form :create-loading
           :create-error :show-budget-modal :budget-unit :budget-val
           :budget-loading :budget-error]
  :initial-state {:loading true :error nil :active-org nil :units {} :hierarchy {}
                  :search-term "" :collapsed-nodes #{} :show-create-modal false
                  :create-form {:id "" :name "" :division-id "" :dept-id "" :parent-id nil :budget 5}
                  :create-loading false :create-error nil :show-budget-modal false
                  :budget-unit nil :budget-val 0 :budget-loading false :budget-error nil}
  :componentDidMount (fn [this] (fetch-chart-data! this))}
  (let [{:keys [loading error active-org units hierarchy search-term
                collapsed-nodes show-create-modal create-form create-loading
                create-error show-budget-modal budget-unit budget-val
                budget-loading budget-error]} (comp/get-state this)
        unit-list (vals (or units {}))
        root-units (or (get hierarchy nil)
                       (mapv :unit/id (filter #(nil? (:unit/parent-id %)) unit-list)))
        ;; Aggregate rollup KPI numbers
        total-budget (reduce + 0 (map #(:unit/budget % 0) unit-list))
        total-filled (reduce + 0 (map #(:unit/filled % 0) unit-list))
        total-open (reduce + 0 (map #(:unit/open % 0) unit-list))
        total-pending (reduce + 0 (map #(:unit/pending % 0) unit-list))
        total-divisions (count (filter #(nil? (:unit/parent-id %)) unit-list))
        total-depts (count (filter #(some? (:unit/parent-id %)) unit-list))]

    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-6"}
      ;; Page Header
      (div {:className "border-b border-gray-200 pb-6 flex flex-col md:flex-row md:items-center md:justify-between gap-4"}
        (div nil
          (div {:className "flex items-center gap-2"}
            (h1 {:className "text-3xl font-extrabold tracking-tight text-gray-900"} "Organization Chart")
            (when active-org
              (span {:className "inline-flex items-center rounded-md bg-indigo-50 px-2.5 py-0.5 text-xs font-bold text-indigo-700 ring-1 ring-inset ring-indigo-700/10"}
                (:org/name active-org))))
          (p {:className "mt-1.5 text-sm text-gray-500"}
            "Interactive organizational hierarchy, headcount metrics, and scoped actor coverage."))

        ;; Header Actions
        (div {:className "flex items-center gap-3"}
          (button {:onClick #(fetch-chart-data! this)
                   :className "inline-flex items-center gap-1.5 rounded-lg bg-white px-3 py-1.5 text-xs font-semibold text-gray-700 shadow-2xs ring-1 ring-inset ring-gray-300 hover:bg-gray-50 transition"}
            (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "h-3 w-3 text-gray-500 shrink-0" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
              (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2.5" :d "M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"}))
            "Refresh")
          (button {:onClick #(comp/set-state! this {:show-create-modal true
                                                    :create-form {:id "" :name "" :division-id "" :dept-id "" :parent-id nil :budget 5}
                                                    :create-error nil})
                   :className "inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-3.5 py-1.5 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 transition"}
            "+ Add Division / Dept")))

      ;; Summary KPI Metrics Banner
      (when (seq unit-list)
        (div {:className "grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6"}
          ;; Total Units
          (div {:className "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
            (p {:className "text-xs font-medium text-gray-500 truncate"} "Total Units")
            (p {:className "mt-1 text-2xl font-bold tracking-tight text-gray-900"} (str (count unit-list)))
            (p {:className "text-xs text-gray-400 mt-0.5"} (str total-divisions " div, " total-depts " dept")))

          ;; Total Budget
          (div {:className "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
            (p {:className "text-xs font-medium text-gray-500 truncate"} "Allocated Budget")
            (p {:className "mt-1 text-2xl font-bold tracking-tight text-purple-700"} (str total-budget))
            (p {:className "text-xs text-purple-400 mt-0.5"} "Target seats"))

          ;; Filled
          (div {:className "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
            (p {:className "text-xs font-medium text-gray-500 truncate"} "Filled Seats")
            (p {:className "mt-1 text-2xl font-bold tracking-tight text-emerald-700"} (str total-filled))
            (p {:className "text-xs text-emerald-500 mt-0.5"} (str (if (pos? total-budget) (Math/round (* 100 (/ total-filled total-budget))) 0) "% filled")))

          ;; Open Headcount
          (div {:className "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
            (p {:className "text-xs font-medium text-gray-500 truncate"} "Open Headcount")
            (p {:className "mt-1 text-2xl font-bold tracking-tight text-indigo-700"} (str total-open))
            (p {:className "text-xs text-indigo-400 mt-0.5"} "Available for hire"))

          ;; In Approval
          (div {:className "rounded-xl bg-white p-4 shadow-2xs border border-gray-100"}
            (p {:className "text-xs font-medium text-gray-500 truncate"} "In Approval")
            (p {:className "mt-1 text-2xl font-bold tracking-tight text-amber-700"} (str total-pending))
            (p {:className "text-xs text-amber-400 mt-0.5"} "Pipeline requisitions"))

          ;; SLA Link
          (div {:className "rounded-xl bg-gradient-to-br from-indigo-50 to-slate-50 p-4 shadow-2xs border border-indigo-100 flex flex-col justify-between"}
            (p {:className "text-xs font-medium text-indigo-900"} "Analytics & SLA")
            (a {:href "/dept-dashboard" :className "text-xs font-bold text-indigo-600 hover:text-indigo-700 mt-1 inline-flex items-center gap-1"}
              "View Dashboards →"))))

      ;; Toolbar: Search & Expand Controls
      (when (seq unit-list)
        (div {:className "flex flex-col sm:flex-row items-center justify-between gap-3 bg-white p-4 rounded-xl border border-gray-200 shadow-2xs"}
          (div {:className "relative w-full sm:w-80"}
            (input {:type "text" :placeholder "Filter by unit name, code, division..."
                    :value (or search-term "")
                    :onChange #(comp/set-state! this {:search-term (.. % -target -value)})
                    :className "w-full rounded-lg border border-gray-300 pl-8 pr-3 py-1.5 text-xs shadow-2xs focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500"})
            (div {:className "absolute inset-y-0 left-0 flex items-center pl-2.5 pointer-events-none text-gray-400"}
              (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "h-3.5 w-3.5 shrink-0" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2.5" :d "M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}))))

          (div {:className "flex items-center gap-2 w-full sm:w-auto justify-end"}
            (button {:onClick #(comp/set-state! this {:collapsed-nodes #{}})
                     :className "rounded-lg px-3 py-1.5 text-xs font-semibold text-gray-600 hover:bg-gray-100 ring-1 ring-inset ring-gray-200 transition"}
              "Expand All")
            (button {:onClick #(comp/set-state! this {:collapsed-nodes (set (keys units))})
                     :className "rounded-lg px-3 py-1.5 text-xs font-semibold text-gray-600 hover:bg-gray-100 ring-1 ring-inset ring-gray-200 transition"}
              "Collapse All"))))

      ;; Main Tree Content Area
      (cond
        loading
        (div {:className "rounded-2xl bg-white p-12 text-center border border-gray-200 shadow-sm space-y-3"}
          (div {:className "mx-auto h-8 w-8 animate-spin rounded-full border-3 border-indigo-600 border-t-transparent"})
          (p {:className "text-sm font-medium text-gray-600"} "Loading organization hierarchy..."))

        error
        (div {:className "rounded-xl bg-red-50 p-6 border border-red-200 shadow-2xs space-y-2"}
          (h3 {:className "text-sm font-bold text-red-800"} "Unable to Load Org Chart")
          (p {:className "text-xs text-red-700"} error)
          (button {:onClick #(fetch-chart-data! this)
                   :className "mt-2 rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-500 transition"}
            "Try Again"))

        (empty? unit-list)
        (div {:className "rounded-2xl bg-white p-12 text-center border-2 border-dashed border-gray-200 space-y-4"}
          (div {:className "mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-indigo-50 text-indigo-600"}
            (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "h-6 w-6" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
              (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5m3 0v-4a1 1 0 011-1h2a1 1 0 011 1v4m-4 0h4"})))
          (div nil
            (h3 {:className "text-base font-bold text-gray-900"} "No Organizational Units Found")
            (p {:className "text-xs text-gray-500 mt-1 max-w-sm mx-auto"}
              "Get started by creating your first Division (such as Engineering, Product, or Sales) to begin tracking headcount."))
          (button {:onClick #(comp/set-state! this {:show-create-modal true
                                                    :create-form {:id "div-eng" :name "Engineering Division" :division-id "ENG" :dept-id "ALL" :parent-id nil :budget 10}
                                                    :create-error nil})
                   :className "rounded-lg bg-indigo-600 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-indigo-500 transition"}
            "+ Create First Division"))

        :else
        (div {:className "space-y-6"}
          (if (seq root-units)
            (mapv (fn [root-id]
                    (render-tree-branch this root-id units hierarchy 0 search-term collapsed-nodes))
                  root-units)
            (mapv (fn [u]
                    (render-tree-branch this (:unit/id u) units hierarchy 0 search-term collapsed-nodes))
                  unit-list))))

      ;; Modals
      (when show-create-modal
        (render-create-unit-modal this active-org units create-form create-loading create-error))

      (when (and show-budget-modal budget-unit)
        (render-budget-modal this budget-unit budget-val budget-loading budget-error)))))

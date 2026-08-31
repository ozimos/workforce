(ns com.ozimos.workforce.org.generator
  "Enterprise Org Hierarchy & Workforce Seed Data Generator.
   Generates 10,000-person tree hierarchies, division/dept combinations,
   80/20 employee vs headcount distribution, and Malli-powered attributes."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [malli.generator :as mg]))

;; =============================================================================
;; 1. Divisions & Departments Definitions (~40 Combinations)
;; =============================================================================

(def canonical-divisions
  [{:id "div-eng" :name "Engineering"}
   {:id "div-prod" :name "Product & Design"}
   {:id "div-sales" :name "Sales & Commercial"}
   {:id "div-mktg" :name "Marketing & Growth"}
   {:id "div-ops" :name "Customer Operations & Support"}
   {:id "div-fin" :name "Finance, Accounting & Legal"}
   {:id "div-people" :name "People, Talent & Workplace"}])

(def division-departments
  {"div-eng"
   [{:id "dept-eng-platform" :name "Platform & Infrastructure Engineering"}
    {:id "dept-eng-backend" :name "Core Distributed Backend Systems"}
    {:id "dept-eng-frontend" :name "Web Platform & Frontend Apps"}
    {:id "dept-eng-mobile" :name "Mobile Engineering (iOS & Android)"}
    {:id "dept-eng-ai" :name "Applied AI & Data Intelligence"}
    {:id "dept-eng-sec" :name "Security, Risk & Privacy Engineering"}
    {:id "dept-eng-qa" :name "Quality Engineering & Release Ops"}]

   "div-prod"
   [{:id "dept-prod-core" :name "Core Product Management"}
    {:id "dept-prod-growth" :name "Growth Product & Monetization"}
    {:id "dept-prod-design" :name "Product Design & UX Research"}
    {:id "dept-prod-techwriting" :name "Technical Writing & Docs"}]

   "div-sales"
   [{:id "dept-sales-ent-na" :name "Enterprise Sales - North America"}
    {:id "dept-sales-ent-emea" :name "Enterprise Sales - EMEA"}
    {:id "dept-sales-ent-apac" :name "Enterprise Sales - APAC"}
    {:id "dept-sales-midmkt" :name "Mid-Market & Commercial Sales"}
    {:id "dept-sales-eng" :name "Solutions Architecture & Sales Eng"}
    {:id "dept-sales-enable" :name "Sales Enablement & RevOps"}]

   "div-mktg"
   [{:id "dept-mktg-brand" :name "Brand Strategy & Communications"}
    {:id "dept-mktg-demand" :name "Demand Generation & Paid Ads"}
    {:id "dept-mktg-content" :name "Content & Product Marketing"}
    {:id "dept-mktg-events" :name "Developer Relations & Events"}]

   "div-ops"
   [{:id "dept-ops-support" :name "Customer Support & Triage"}
    {:id "dept-ops-success" :name "Customer Success Management"}
    {:id "dept-ops-trust" :name "Trust, Safety & Compliance"}
    {:id "dept-ops-it" :name "Enterprise IT & Helpdesk"}
    {:id "dept-ops-profserv" :name "Professional Implementation Services"}]

   "div-fin"
   [{:id "dept-fin-fp-a" :name "Financial Planning & Analysis"}
    {:id "dept-fin-acct" :name "Accounting & Treasury Operations"}
    {:id "dept-fin-legal" :name "Corporate Legal & Contracts"}
    {:id "dept-fin-proc" :name "Procurement & Vendor Management"}]

   "div-people"
   [{:id "dept-people-talent" :name "Talent Acquisition & Recruiting"}
    {:id "dept-people-hrbp" :name "People Operations & HRBPs"}
    {:id "dept-people-comp" :name "Total Rewards & Compensation"}
    {:id "dept-people-ld" :name "Learning, Development & Culture"}
    {:id "dept-people-facil" :name "Workplace Operations & Facilities"}]})

(defn generate-org-units
  "Combines canonical divisions and departments into ~40 org units."
  ([] (generate-org-units "org-1"))
  ([org-id]
   (let [divisions (mapv (fn [div]
                           {:unit-id (str org-id "-" (:id div))
                            :division-id (:id div)
                            :name (:name div)
                            :parent-id nil
                            :type :division})
                         canonical-divisions)
         departments (vec (mapcat (fn [div]
                                    (let [div-uid (str org-id "-" (:id div))
                                          depts (get division-departments (:id div) [])]
                                      (mapv (fn [dept]
                                              {:unit-id (str org-id "-" (:id dept))
                                               :division-id (:id div)
                                               :dept-id (:id dept)
                                               :name (:name dept)
                                               :parent-id div-uid
                                               :type :department})
                                            depts)))
                                  canonical-divisions))]
     (into divisions departments))))

;; =============================================================================
;; 2. Tree Topology Generation with Increasing Branching Factor
;; =============================================================================

(defn- branching-bounds
  "Returns [min-branches max-branches] for a given depth.
   The branching factor increases as we go deeper in the tree."
  [depth]
  (case (int depth)
    0 [6 8]    ;; CEO -> SVPs / Execs
    1 [4 7]    ;; SVPs -> VPs
    2 [5 8]    ;; VPs -> Directors / Senior Mgrs
    3 [6 10]   ;; Directors -> Managers
    4 [7 12]   ;; Managers -> Team Leads / Staff ICs
    5 [8 15]   ;; Team Leads -> ICs
    6 [10 18]  ;; Broad IC pool
    [10 20]))

(defn generate-org-tree
  "Generates a tree with `total-nodes` where root is at top and
   branching factor increases with depth.
   Returns a map with :root-id, :nodes (indexed by node-id), and :children (parent-id -> vector of child-ids)."
  [{:keys [total-nodes seed prefix]
    :or {total-nodes 10000 seed 42 prefix "n"}}]
  (let [rng (java.util.Random. (long seed))
        root-id (str prefix "-00001")
        root-node {:node-id root-id :parent-id nil :depth 0}
        nodes (java.util.HashMap. (int (* total-nodes 1.3)))
        children (java.util.HashMap. (int (* total-nodes 1.3)))
        queue (java.util.ArrayDeque.)]

    (.put nodes root-id root-node)
    (.put children root-id (java.util.ArrayList.))
    (.add queue root-node)

    (let [counter (atom 1)]
      (while (and (not (.isEmpty queue)) (< @counter total-nodes))
        (let [curr-node (.poll queue)
              curr-id (:node-id curr-node)
              curr-depth (:depth curr-node)
              [min-b max-b] (branching-bounds curr-depth)
              span (max 1 (inc (- max-b min-b)))
              target-branches (+ min-b (.nextInt rng span))
              curr-children (.get children curr-id)]

          (dotimes [_ target-branches]
            (when (< @counter total-nodes)
              (let [child-idx (swap! counter inc)
                    child-id (format "%s-%05d" prefix child-idx)
                    child-node {:node-id child-id
                                :parent-id curr-id
                                :depth (inc curr-depth)}]
                (.put nodes child-id child-node)
                (.put children child-id (java.util.ArrayList.))
                (.add curr-children child-id)
                (.add queue child-node))))))

      ;; Convert java collections to persistent Clojure structures
      (let [clj-nodes (into {} (map (fn [[k v]] [k v])) nodes)
            clj-children (into {} (map (fn [[k v]] [k (vec v)])) children)]
        {:root-id root-id
         :total-nodes (count clj-nodes)
         :nodes clj-nodes
         :children clj-children}))))

;; =============================================================================
;; 3. Assigning Divisions & Departments with Regional Locality
;; =============================================================================

(defn assign-units-to-tree
  "Assigns division and department units to tree nodes.
   Maintains spatial/subtree clustering so nodes in the same division remain connected,
   while allowing occasional (e.g. 4%) cross-cutting matrixed allocations."
  [{:keys [tree org-units seed org-id]
    :or {seed 42 org-id "org-1"}}]
  (let [rng (java.util.Random. (long seed))
        {:keys [root-id children]} tree
        div-ids (mapv :id canonical-divisions)
        root-children (get children root-id [])
        div-count (count div-ids)

        ;; Assign each top child of root to a division
        top-div-map (into {}
                          (map-indexed
                           (fn [idx cid]
                             [cid (nth div-ids (mod idx div-count))])
                           root-children))

        unit-assignment (atom {})]

    ;; Assign CEO / Root to Engineering or Corporate
    (swap! unit-assignment assoc root-id {:division-id "div-eng"
                                         :unit-id (str org-id "-dept-eng-platform")})

    ;; Breadth-first pass to propagate division & select departments
    (let [queue (java.util.ArrayDeque.)]
      (doseq [cid root-children]
        (let [div-id (get top-div-map cid (first div-ids))
              depts (get division-departments div-id (first (vals division-departments)))
              chosen-dept (nth depts (.nextInt rng (count depts)))
              assigned {:division-id div-id
                        :unit-id (str org-id "-" (:id chosen-dept))}]
          (swap! unit-assignment assoc cid assigned)
          (.add queue cid)))

      (while (not (.isEmpty queue))
        (let [curr-id (.poll queue)
              curr-assigned (get @unit-assignment curr-id)
              parent-div (:division-id curr-assigned)
              curr-children (get children curr-id [])]

          (doseq [child-id curr-children]
            (let [cross-cut? (< (.nextDouble rng) 0.04)
                  div-id (if cross-cut?
                           (nth div-ids (.nextInt rng div-count))
                           parent-div)
                  depts (get division-departments div-id)
                  ;; 75% chance stay in same department as parent if same division, else pick department
                  dept-chosen (if (and (not cross-cut?)
                                       (< (.nextDouble rng) 0.75)
                                       (:unit-id curr-assigned))
                                (:unit-id curr-assigned)
                                (let [d (nth depts (.nextInt rng (count depts)))]
                                  (str org-id "-" (:id d))))
                  child-assigned {:division-id div-id
                                  :unit-id dept-chosen}]
              (swap! unit-assignment assoc child-id child-assigned)
              (.add queue child-id))))))

    @unit-assignment))

;; =============================================================================
;; 4. Malli Schema Generators for Workforce Node Attributes
;; =============================================================================

(def LocationSchema
  [:enum "US-CA" "US-NY" "US-TX" "US-WA" "GB" "DE" "FR" "SG" "CA" "AU"])

(def CurrencySchema
  [:enum "USD" "GBP" "EUR" "CAD" "AUD" "SGD"])

(def JobLevelSchema
  [:enum "L1" "L2" "L3" "L4" "L5" "L6" "L7" "L8"])

(def EmployeeTypeSchema
  [:enum :full-time :part-time :intern])

(def JobTitlesByDivision
  {"div-eng" ["Software Engineer" "Senior Systems Engineer" "Staff Platform Engineer" "Principal Architect" "Engineering Manager" "QA Automation Lead"]
   "div-prod" ["Product Manager" "Senior Technical Product Manager" "Lead Product Designer" "Design Systems Architect" "Director of Product"]
   "div-sales" ["Account Executive" "Enterprise Sales Director" "Solutions Architect" "Sales Development Rep" "Strategic Account Manager"]
   "div-mktg" ["Growth Marketing Specialist" "Brand Marketing Lead" "Content Strategist" "Product Marketing Director" "DevRel Advocate"]
   "div-ops" ["Customer Support Specialist" "Tier 3 Operations Engineer" "Implementation Consultant" "Trust & Safety Specialist" "IT Ops Lead"]
   "div-fin" ["Senior Financial Analyst" "Corporate Controller" "Legal Counsel" "Procurement Manager" "FP&A Director"]
   "div-people" ["Technical Recruiter" "Senior People Partner" "Compensation Analyst" "HR Operations Specialist" "Head of Talent"]})

(defn- base-salary-for-level [level rng]
  (let [base (case level
               "L1" 65000.0
               "L2" 85000.0
               "L3" 115000.0
               "L4" 145000.0
               "L5" 185000.0
               "L6" 235000.0
               "L7" 295000.0
               "L8" 380000.0
               120000.0)
        jitter (* (- (.nextDouble rng) 0.5) 20000.0)]
    (double (+ base jitter))))

(defn- bonus-for-level [level]
  (case level
    "L1" 0.05
    "L2" 0.08
    "L3" 0.10
    "L4" 0.15
    "L5" 0.20
    "L6" 0.25
    "L7" 0.30
    "L8" 0.40
    0.10))

(defn- level-for-depth [depth rng]
  (let [r (.nextDouble rng)]
    (case (int depth)
      0 "L8" ;; CEO
      1 (if (< r 0.8) "L8" "L7") ;; EVP/SVP
      2 (if (< r 0.7) "L7" "L6") ;; VP
      3 (if (< r 0.6) "L6" "L5") ;; Director / Senior Mgr
      4 (if (< r 0.5) "L5" "L4") ;; Lead / Staff
      5 (if (< r 0.5) "L4" "L3") ;; Senior IC / IC
      (if (< r 0.4) "L3" (if (< r 0.8) "L2" "L1")))))

;; =============================================================================
;; 5. 80/20 Employee vs Headcount Split and Dataset Assembly
;; =============================================================================

(def first-names
  ["James" "Mary" "Robert" "Patricia" "John" "Jennifer" "Michael" "Linda"
   "David" "Elizabeth" "William" "Barbara" "Richard" "Susan" "Joseph" "Jessica"
   "Thomas" "Sarah" "Charles" "Karen" "Daniel" "Nancy" "Matthew" "Lisa"
   "Anthony" "Betty" "Mark" "Margaret" "Alexander" "Sandra" "Ethan" "Ashley"
   "Liam" "Emma" "Noah" "Olivia" "Oliver" "Ava" "Lucas" "Sophia" "Mason" "Isabella"])

(def last-names
  ["Smith" "Johnson" "Williams" "Brown" "Jones" "Garcia" "Miller" "Davis"
   "Rodriguez" "Martinez" "Hernandez" "Lopez" "Gonzalez" "Wilson" "Anderson"
   "Thomas" "Taylor" "Moore" "Jackson" "Martin" "Lee" "Perez" "Thompson" "White"
   "Harris" "Sanchez" "Clark" "Ramirez" "Lewis" "Robinson" "Walker" "Young"])

(defn generate-10k-workforce-nodes
  "Generates 10,000 workforce nodes assigned to tree structure and units with:
   - 80% Employees (Active with Employment)
   - 20% Headcounts (Open / In-Approval Requisitions)
   - Malli-compatible attributes, custom attributes, deterministic constants."
  [{:keys [org-id total-nodes seed]
    :or {org-id "org-1" total-nodes 10000 seed 42}}]
  (let [rng (java.util.Random. (long seed))
        tree (generate-org-tree {:total-nodes total-nodes :seed seed :prefix org-id})
        org-units (generate-org-units org-id)
        unit-assignments (assign-units-to-tree {:tree tree :org-units org-units :seed seed :org-id org-id})
        nodes-map (:nodes tree)
        sorted-nodes (sort-by :node-id (vals nodes-map))

        employees (atom [])
        employments (atom [])
        headcounts (atom [])]

    (doseq [node sorted-nodes]
      (let [nid (:node-id node)
            depth (:depth node)
            assigned-unit (get unit-assignments nid)
            unit-id (:unit-id assigned-unit)
            div-id (:division-id assigned-unit)
            level (level-for-depth depth rng)
            titles (get JobTitlesByDivision div-id ["Workforce Specialist"])
            title (nth titles (.nextInt rng (count titles)))
            loc (nth ["US-CA" "US-NY" "US-TX" "GB" "DE" "FR" "SG" "CA"] (.nextInt rng 8))
            curr (case loc
                   "GB" "GBP"
                   ("DE" "FR") "EUR"
                   "CA" "CAD"
                   "SG" "SGD"
                   "USD")
            base-sal (base-salary-for-level level rng)
            bonus-tgt (bonus-for-level level)
            custom-attrs {:health-benefit (+ 4000.0 (* (.nextInt rng 8) 1000.0))
                          :signing-bonus (if (> (.nextDouble rng) 0.7) 15000.0 0.0)}

            ;; 80% Employees vs 20% Headcounts (Root is always Employee 1)
            is-headcount? (and (not= nid (:root-id tree))
                               (< (.nextDouble rng) 0.20))]

        (if is-headcount?
          ;; Generate Headcount Requisition
          (let [status-roll (.nextDouble rng)
                status (cond
                         (< status-roll 0.40) :open
                         (< status-roll 0.75) :in-approval
                         (< status-roll 0.90) :approved
                         :else :rejected)
                hc {:request-id (str "req-" nid)
                    :org-id org-id
                    :unit-id unit-id
                    :division-id div-id
                    :title title
                    :job-level level
                    :employee-type :full-time
                    :location loc
                    :salary-band base-sal
                    :bonus-target bonus-tgt
                    :status status
                    :created-at 1788200000000}]
            (swap! headcounts conj hc))

          ;; Generate Employee and Employment Placement
          (let [fn-idx (.nextInt rng (count first-names))
                ln-idx (.nextInt rng (count last-names))
                fname (if (= nid (:root-id tree)) "Alice" (nth first-names fn-idx))
                lname (if (= nid (:root-id tree)) "Smith" (nth last-names ln-idx))
                email (format "%s.%s.%s@example.com" (str/lower-case fname) (str/lower-case lname) nid)
                emp-id (str "emp-" nid)
                employment-id (str "empmt-" nid)
                emp {:employee-id emp-id
                     :org-id org-id
                     :first-name fname
                     :last-name lname
                     :personal-email email
                     :hire-date "2024-01-15"
                     :status :active
                     :current-employment-id employment-id}
                empmt {:employment-id employment-id
                       :employee-id emp-id
                       :org-id org-id
                       :unit-id unit-id
                       :job-title title
                       :job-category (keyword (str/replace div-id #"div-" ""))
                       :job-level level
                       :employee-type :full-time
                       :location loc
                       :base-salary base-sal
                       :currency curr
                       :bonus-target bonus-tgt
                       :custom-attributes custom-attrs
                       :status :active}]
            (swap! employees conj emp)
            (swap! employments conj empmt)))))

    {:tree tree
     :org-units org-units
     :employees @employees
     :employments @employments
     :headcounts @headcounts
     :total-employees (count @employees)
     :total-headcounts (count @headcounts)}))

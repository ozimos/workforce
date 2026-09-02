(ns com.ozimos.workforce.frontend.abac
  "App-level Attribute-Based Access Control (ABAC) engine.
   Provides pure, cross-platform (.cljc) evaluation functions for records
   (headcounts, requisitions, employees, and reports) against multidimensional
   access policies (division, department, job level, location).")

(defn policy-active?
  "Checks whether an ABAC policy has any active constraints.
   Returns false if policy is nil or all dimension allow-sets are nil."
  [policy]
  (boolean
   (and (map? policy)
        (or (some? (:allowed-divisions policy))
            (some? (:allowed-depts policy))
            (some? (:allowed-levels policy))
            (some? (:allowed-locations policy))))))

(defn accessible-headcount?
  "Evaluates whether a headcount requisition is accessible under the given ABAC policy.
   If policy is nil or a dimension's allow-set is nil, that dimension is unrestricted.
   If an allow-set is provided, the record's attribute must be a member of that set."
  [headcount policy]
  (if-not (policy-active? policy)
    true
    (let [{:keys [allowed-divisions allowed-depts allowed-levels allowed-locations]} policy
          div-id   (or (:headcount/division-id headcount) (:division-id headcount))
          dept-id  (or (:headcount/dept-id headcount) (:dept-id headcount))
          level    (or (:headcount/job-level headcount) (:job-level headcount))
          location (or (:headcount/location headcount) (:location headcount))]
      (and (or (nil? allowed-divisions) (contains? allowed-divisions div-id))
           (or (nil? allowed-depts) (contains? allowed-depts dept-id))
           (or (nil? allowed-levels) (contains? allowed-levels level))
           (or (nil? allowed-locations) (contains? allowed-locations location))))))

(defn accessible-employee?
  "Evaluates whether an employee record is accessible under the given ABAC policy
   (for report screens like budget tables, headcount tables, rosters).
   Note: Org tree view preserves visual tree connectivity, but report views
   enforce ABAC across both employees and headcounts."
  [employee policy]
  (if-not (policy-active? policy)
    true
    (let [{:keys [allowed-divisions allowed-depts allowed-levels allowed-locations]} policy
          div-id   (or (:person/division-id employee) (:division-id employee) (:unit/division-id employee))
          dept-id  (or (:person/dept-id employee) (:dept-id employee) (:unit/dept-id employee))
          level    (or (:person/job-level employee) (:job-level employee))
          location (or (:person/location employee) (:location employee))]
      (and (or (nil? allowed-divisions) (nil? div-id) (contains? allowed-divisions div-id))
           (or (nil? allowed-depts) (nil? dept-id) (contains? allowed-depts dept-id))
           (or (nil? allowed-levels) (nil? level) (contains? allowed-levels level))
           (or (nil? allowed-locations) (nil? location) (contains? allowed-locations location))))))

(defn filter-accessible-headcounts
  "Filters a collection of headcounts against an ABAC policy."
  [headcounts policy]
  (if-not (policy-active? policy)
    (vec headcounts)
    (filterv #(accessible-headcount? % policy) headcounts)))

(defn filter-accessible-employees
  "Filters a collection of employees against an ABAC policy (for report/table screens)."
  [employees policy]
  (if-not (policy-active? policy)
    (vec employees)
    (filterv #(accessible-employee? % policy) employees)))

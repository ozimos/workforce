(ns com.ozimos.workforce.org.rbac
  "Granular Role-Based Access Control (RBAC) and field-level visibility engine
   for headcount requisitions and organizational tree attributes."
  (:require
   [clojure.string :as str]))

(defn descendant-unit?
  "Checks if target-unit-id is equal to root-unit-id or a descendant of root-unit-id
   in the org hierarchy graph ({parent-id #{child-ids}})."
  [org-hierarchy root-unit-id target-unit-id]
  (cond
    (nil? root-unit-id) false
    (= root-unit-id target-unit-id) true
    :else
    (loop [queue (into clojure.lang.PersistentQueue/EMPTY (get org-hierarchy root-unit-id #{}))
           visited #{root-unit-id}]
      (if (empty? queue)
        false
        (let [curr (peek queue)
              rest-q (pop queue)]
          (cond
            (= curr target-unit-id) true
            (contains? visited curr) (recur rest-q visited)
            :else
            (let [children (get org-hierarchy curr #{})]
              (recur (into rest-q children) (conj visited curr)))))))))

(defn valid-reporting-managers?
  "Validates reporting managers for a worker (employee or headcount).
   Rules:
   1. Max 1 reporting manager of type :employee
   2. Max 1 reporting manager of type :headcount
   3. Total max reporting manager count of 2.
   Accepts either a seq of manager maps (e.g. [{:type :employee :id ...}])
   or a map with :employee-id and/or :headcount-id."
  [managers]
  (cond
    (nil? managers) true
    (map? managers)
    (let [emp-count (if (:employee-id managers) 1 0)
          hc-count  (if (:headcount-id managers) 1 0)]
      (and (<= emp-count 1)
           (<= hc-count 1)
           (<= (+ emp-count hc-count) 2)))
    (coll? managers)
    (let [emp-count (count (filter #(= (:type %) :employee) managers))
          hc-count  (count (filter #(= (:type %) :headcount) managers))
          total     (count managers)]
      (and (<= emp-count 1)
           (<= hc-count 1)
           (<= total 2)
           (= total (+ emp-count hc-count))))
    :else false))

(defn resolve-effective-reporting
  "Resolves the effective reporting structure and acting manager status for a worker.
   When both an employee and a headcount are reporting managers:
     - The employee is shown on the org chart tree as the immediate parent
     - The employee is marked as :acting-reporting-manager? true
     - The headcount manager is retained in :headcount-reporting-manager-id
   When only an employee exists:
     - The employee is shown on the org chart tree as the immediate parent
     - :acting-reporting-manager? is false
   When only a headcount exists:
     - The headcount is shown on the org chart tree as the immediate parent
     - :acting-reporting-manager? is false."
  [managers]
  (let [mgr-list (cond
                   (map? managers)
                   (concat (when-let [eid (:employee-id managers)] [{:type :employee :id eid}])
                           (when-let [hid (:headcount-id managers)] [{:type :headcount :id hid}]))
                   (coll? managers) managers
                   :else [])
        emp-mgr (first (filter #(= (:type %) :employee) mgr-list))
        hc-mgr  (first (filter #(= (:type %) :headcount) mgr-list))]
    (cond
      ;; Both employee and headcount reporting managers
      (and emp-mgr hc-mgr)
      {:tree-parent-id (:id emp-mgr)
       :acting-reporting-manager? true
       :employee-reporting-manager-id (:id emp-mgr)
       :headcount-reporting-manager-id (:id hc-mgr)
       :reporting-managers [emp-mgr hc-mgr]}

      ;; Employee only
      emp-mgr
      {:tree-parent-id (:id emp-mgr)
       :acting-reporting-manager? false
       :employee-reporting-manager-id (:id emp-mgr)
       :headcount-reporting-manager-id nil
       :reporting-managers [emp-mgr]}

      ;; Headcount only
      hc-mgr
      {:tree-parent-id (:id hc-mgr)
       :acting-reporting-manager? false
       :employee-reporting-manager-id nil
       :headcount-reporting-manager-id (:id hc-mgr)
       :reporting-managers [hc-mgr]}

      :else
      {:tree-parent-id nil
       :acting-reporting-manager? false
       :employee-reporting-manager-id nil
       :headcount-reporting-manager-id nil
       :reporting-managers []})))

(defn is-actor-on-request?
  "Checks if the viewer user-id is an explicit actor on the headcount request.
   Recognizes actors:
   - owner
   - hiring-manager (in charge of hiring process)
   - reporting-manager (employee or headcount)
   - recruiters (vector of user IDs)
   - approvers / approved-by (vector of user IDs)
   - collaborators (vector of user IDs)
   - sourcers (vector of user IDs)
   - requester / legacy assigned actors."
  [viewer target-req]
  (let [viewer-id (:user-id viewer)
        owner-id (or (:owner target-req) (:owner-id target-req) (:requester-id target-req))
        hiring-mgr-id (or (:hiring-manager target-req) (:hiring-manager-id target-req))
        rep-mgr (:reporting-manager target-req)
        rep-mgr-id (cond (string? rep-mgr) rep-mgr
                         (map? rep-mgr) (or (:id rep-mgr) (:employee-id rep-mgr) (:headcount-id rep-mgr)))
        approved-by (set (:approved-by target-req))
        current-approver-id (:current-approver-id target-req)
        approvers (set (concat (when current-approver-id [current-approver-id])
                               approved-by
                               (keep #(if (map? %) (:approver-user-id %) %) (:approvers target-req))))
        recruiters (set (or (:recruiters target-req) []))
        collaborators (set (or (:collaborators target-req) []))
        sourcers (set (or (:sourcers target-req) []))
        assigned-actors (set (:assigned-actor-ids target-req))]
    (or (= viewer-id owner-id)
        (= viewer-id hiring-mgr-id)
        (= viewer-id rep-mgr-id)
        (contains? approvers viewer-id)
        (contains? recruiters viewer-id)
        (contains? collaborators viewer-id)
        (contains? sourcers viewer-id)
        (contains? assigned-actors viewer-id))))

(defn can-view-headcount?
  "Determines whether the viewer has permission to view the given headcount request
   based on role permissions (:view-all, :view-tree, :view-own) and org hierarchy."
  [viewer target-req org-hierarchy permissions]
  (let [view-level (:view-headcount permissions)]
    (cond
      ;; Full tenant access (e.g. Admin, Executive)
      (= view-level :view-all)
      true

      ;; Department / Division Tree Scope (e.g. Department Head, VP)
      (= view-level :view-tree)
      (or (is-actor-on-request? viewer target-req)
          (descendant-unit? org-hierarchy (:unit-id viewer) (:unit-id target-req)))

      ;; Own Requests / Assigned Actor Scope (e.g. Employee, Hiring Manager)
      (= view-level :view-own)
      (is-actor-on-request? viewer target-req)

      :else false)))

(defn mask-sensitive-fields
  "Applies field-level visibility masking to a headcount request based on permissions."
  [target-req permissions]
  (let [view-comp? (:view-comp permissions false)
        view-bonus? (:view-bonus permissions false)
        view-rsu? (:view-rsu permissions false)]
    (cond-> target-req
      (not view-comp?)
      (assoc :salary-band nil
             :salary nil)

      (not view-bonus?)
      (assoc :bonus-target nil
             :bonus nil)

      (not view-rsu?)
      (assoc :rsu nil))))

(def default-role-permissions
  {:admin           {:view-headcount :view-all
                     :view-comp true
                     :view-bonus true
                     :view-rsu true}
   :hr              {:view-headcount :view-all
                     :view-comp true
                     :view-bonus true
                     :view-rsu false}
   :recruiter       {:view-headcount :view-all
                     :view-comp true
                     :view-bonus true
                     :view-rsu false}
   :dept-head       {:view-headcount :view-tree
                     :view-comp true
                     :view-bonus false
                     :view-rsu false}
   :hiring-manager  {:view-headcount :view-own
                     :view-comp true
                     :view-bonus false
                     :view-rsu false}
   :employee        {:view-headcount :view-own
                     :view-comp false
                     :view-bonus false
                     :view-rsu false}})

(defn eval-headcount-visibility
  "Evaluates visibility and applies field-level masking for a headcount request.
   Returns the masked request map if visible, or nil if access is denied."
  [viewer target-req org-hierarchy role-permissions]
  (let [role-raw (or (:role viewer) :employee)
        role (keyword (str/lower-case (name role-raw)))
        effective-permissions (merge (get default-role-permissions role (get default-role-permissions :employee))
                                     (get role-permissions role)
                                     (get role-permissions role-raw))]
    (when (can-view-headcount? viewer target-req org-hierarchy effective-permissions)
      (mask-sensitive-fields target-req effective-permissions))))

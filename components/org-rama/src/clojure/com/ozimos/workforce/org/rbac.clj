(ns com.ozimos.workforce.org.rbac
  "Granular Role-Based Access Control (RBAC) and field-level visibility engine
   for headcount requisitions and organizational tree attributes.")

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

(defn is-actor-on-request?
  "Checks if the viewer user-id is an explicit actor on the headcount request
   (requester, assigned recruiter, approver, or in approved-by list)."
  [viewer target-req]
  (let [viewer-id (:user-id viewer)
        requester-id (:requester-id target-req)
        approved-by (set (:approved-by target-req))
        current-approver-id (:current-approver-id target-req)
        assigned-actors (set (:assigned-actor-ids target-req))]
    (boolean
     (or (= viewer-id requester-id)
         (= viewer-id current-approver-id)
         (contains? approved-by viewer-id)
         (contains? assigned-actors viewer-id)))))

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
        role (keyword (clojure.string/lower-case (name role-raw)))
        effective-permissions (merge (get default-role-permissions role (get default-role-permissions :employee))
                                     (get role-permissions role)
                                     (get role-permissions role-raw))]
    (when (can-view-headcount? viewer target-req org-hierarchy effective-permissions)
      (mask-sensitive-fields target-req effective-permissions))))

(ns com.ozimos.workforce.org.resolvers-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.pathom.core :as pathom]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.workforce.org.resolvers :as org-res]
   [com.ozimos.workforce.web.test-system :as ts]
   [com.rpl.rama.ops :as ops]
   [com.wsscode.pathom3.interface.eql :as p.eql]))

(def ^:dynamic *deps* nil)

(defn system-fixture
  [tests]
  (let [sys (ts/get-sys)
        us (ts/user-store sys)]
    (binding [*deps* (assoc us :user-store us :cluster-manager (ts/rama-cluster sys))]
      (tests))))

(use-fixtures :once system-fixture)

(defn- short-suffix []
  (subs (str/replace (str (ops/random-uuid7)) "-" "") 16 32))

(defn- register-user []
  (let [suffix (short-suffix)
        [ok u] (user/register! *deps* {:username (str "orgptest-" suffix)
                                       :email (str "orgptest-" suffix "@test.com")
                                       :password "P@ssword123"})]
    (is ok)
    u))

(defn- build-env-with-org [auth]
  (pathom/build-env *deps* auth org-res/resolvers))

(deftest ^:integration user-orgs-resolver-test
  (testing "user-orgs-resolver returns all orgs for a user"
    (let [user (register-user)
          [ok o] (org/create-org! *deps* {:name (str "resolver-org-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id user)})
          result (pathom/process env [{:user/orgs [:org/id :org/name :org/role :org/status]}])
          orgs (:user/orgs result)]
      (is (some? orgs) "user/orgs should not be nil")
      (is (some #(= (:id o) (:org/id %)) orgs) "created org should be in the list"))))

(deftest ^:integration active-org-resolver-test
  (testing "active-org-resolver returns user's active org"
    (let [user (register-user)
          [ok o] (org/create-org! *deps* {:name (str "active-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id user)})
          result (pathom/process env [{:user/active-org [:org/id :org/name :org/role]}])
          active (:user/active-org result)]
      (is (some? active) "active-org should not be nil")
      (is (= (:id o) (:org/id active)) "org-id should match"))))

(deftest ^:integration active-org-resolver-fallback-test
  (testing "active-org-resolver falls back to first organization when user active org is not explicitly set"
    (let [owner (register-user)
          member (register-user)
          suffix (short-suffix)
          [ok o] (org/create-org! *deps* {:name (str "fallback-org-" suffix) :owner-user-id (:id owner)})
          _ (is ok)
          org-id (:id o)
          _ (org/invite-to-org! *deps* {:org-id org-id :email (:email member) :role "MEMBER" :invited-by (:id owner)})
          inv-id (-> (org/list-invitations-for-user *deps* (:email member)) first :invitation/id)
          _ (org/join-org! *deps* {:user-id (:id member) :invitation-id inv-id})
          env (build-env-with-org {:user-id (:id member)})
          result (pathom/process env [{:user/active-org [:org/id :org/name :org/role]}])
          active (:user/active-org result)]
      (is (some? active) "active-org should resolve via fallback")
      (is (= org-id (:org/id active)) "org-id should match joined organization")
      (is (= "MEMBER" (:org/role active)) "role should be MEMBER"))))

(deftest ^:integration active-org-resolver-no-org-test
  (testing "active-org-resolver returns nil when user has no orgs"
    (let [user (register-user)
          env (build-env-with-org {:user-id (:id user)})
          result (pathom/process env [{:user/active-org [:org/id]}])]
      (is (nil? (:user/active-org result)) "active-org should be nil for user with no orgs"))))

(deftest ^:integration create-org-mutation-test
  (testing "create-org mutation creates an org"
    (let [user (register-user)
          env (build-env-with-org {:user-id (:id user)})
          result (pathom/process env [(list 'org/create {:org/name (str "mutation-org-" (short-suffix))})])
          r (first (vals result))]
      (is (some? (:org/id r)) "org/id should be present")
      (is (some? (:org/name r)) "org/name should be present")
      (is (= "ADMIN" (:org/role r)) "role should be ADMIN"))))

(deftest ^:integration create-org-mutation-duplicate-test
  (testing "create-org mutation with duplicate name returns errors"
    (let [user (register-user)
          org-name (str "dup-mut-" (short-suffix))
          env (build-env-with-org {:user-id (:id user)})
          _ (pathom/process env [(list 'org/create {:org/name org-name})])
          result (pathom/process env [(list 'org/create {:org/name org-name})])
          r (first (vals result))]
      (is (some? (:org/errors r)) "should return errors for duplicate name"))))

(deftest ^:integration switch-org-mutation-test
  (testing "switch-org mutation changes active org"
    (let [user (register-user)
          [ok _org1] (org/create-org! *deps* {:name (str "switch-mut-a-" (short-suffix)) :owner-user-id (:id user)})
          [ok2 org2] (org/create-org! *deps* {:name (str "switch-mut-b-" (short-suffix)) :owner-user-id (:id user)})
          _ (is (and ok ok2))
          env (build-env-with-org {:user-id (:id user)})
          _ (pathom/process env [(list 'org/switch {:org/id (:id org2)})])
          result (pathom/process env [{:user/active-org [:org/id]}])]
      (is (= (:id org2) (:org/id (:user/active-org result))) "active org should be org2"))))

(deftest ^:integration user-invitations-resolver-test
  (testing "user-invitations-resolver returns pending invitations for a user"
    (let [owner (register-user)
          joiner (register-user)
          [ok o] (org/create-org! *deps* {:name (str "inv-resolver-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          _ (org/invite-to-org! *deps* {:org-id (:id o) :email (:email joiner) :role "MEMBER" :invited-by (:id owner)})
          env (build-env-with-org {:user-id (:id joiner)})
          result (pathom/process env [{:user/invitations [:invitation/id :invitation/org-id :invitation/role]}])
          invitations (:user/invitations result)]
      (is (seq invitations) "should have pending invitations")
      (is (= (:id o) (:invitation/org-id (first invitations))) "org-id should match")
      (is (= "MEMBER" (:invitation/role (first invitations))) "role should be MEMBER"))))

(deftest ^:integration org-members-resolver-test
  (testing "org-members-resolver returns members (admin only)"
    (let [admin (register-user)
          [ok o] (org/create-org! *deps* {:name (str "members-resolver-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id admin)})
          result (p.eql/process env {:org/id (:id o)} [{:org/members [:user/id :membership/role]}])
          members (:org/members result)]
      (is (seq members) "should have members")
      (is (= (:id admin) (:user/id (first members))) "admin should be a member")
      (is (= "ADMIN" (:membership/role (first members))) "role should be ADMIN"))))

(deftest ^:integration org-by-id-resolver-test
  (testing "org-by-id-resolver returns org details for members"
    (let [user (register-user)
          [ok o] (org/create-org! *deps* {:name (str "org-by-id-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id user)})
          result (p.eql/process env {:org/id (:id o)} [:org/id :org/name :org/owner-id :org/created-at])]
      (is (= (:id o) (:org/id result)) "org-id should match")
      (is (some? (:org/name result)) "org/name should be present")
      (is (= (:id user) (:org/owner-id result)) "owner-id should match"))))

(deftest ^:integration org-chart-resolver-test
  (testing "org-chart-resolver returns enriched units and hierarchy structure"
    (let [user (register-user)
          suffix (short-suffix)
          [ok o] (org/create-org! *deps* {:name (str "org-chart-" suffix) :owner-user-id (:id user)})
          _ (is ok)
          org-id (:id o)
          div-id (str "div-eng-" suffix)
          dept-id (str "dept-fe-" suffix)
          _ (org/create-org-unit! *deps* {:unit-id div-id :org-id org-id :name "Engineering" :parent-id nil :budget 20 :division-id "ENG"})
          _ (org/create-org-unit! *deps* {:unit-id dept-id :org-id org-id :name "Frontend" :parent-id div-id :budget 8 :division-id "ENG" :dept-id "FE"})
          _ (org/assign-org-actor! *deps* {:org-id org-id :unit-id dept-id :user-id (:id user) :role :hiring-manager})
          env (build-env-with-org {:user-id (:id user)})
          result (p.eql/process env {:org/id org-id}
                   [{:org/chart [:org/id :org/hierarchy
                                 {:org/units [:unit/id :unit/name :unit/parent-id :unit/budget :unit/actors :unit/children]}]}])
          chart (:org/chart result)
          units (:org/units chart)
          hierarchy (:org/hierarchy chart)]
      (is (= org-id (:org/id chart)))
      (is (seq units) "should return units list")
      (is (= 2 (count units)) "should have 2 units")
      (is (= [div-id] (get hierarchy nil)) "division should be root unit")
      (is (= [dept-id] (get hierarchy div-id)) "department should be child of division")
      (let [fe-unit (some #(when (= dept-id (:unit/id %)) %) units)]
        (is (some? fe-unit))
        (is (= "Frontend" (:unit/name fe-unit)))
        (is (= 8 (:unit/budget fe-unit)))
        (is (= div-id (:unit/parent-id fe-unit)))
        (is (= (:id user) (get-in fe-unit [:unit/actors :hiring-manager])))))))

(deftest ^:integration join-org-mutation-test
  (testing "join-org mutation accepts invitation and joins org"
    (let [owner (register-user)
          joiner (register-user)
          [ok o] (org/create-org! *deps* {:name (str "join-mut-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          _ (org/invite-to-org! *deps* {:org-id (:id o) :email (:email joiner) :role "MEMBER" :invited-by (:id owner)})
          inv-id (-> (org/list-invitations-for-user *deps* (:email joiner)) first :invitation/id)
          env (build-env-with-org {:user-id (:id joiner)})
          result (pathom/process env [(list 'org/join {:invitation/id inv-id})])
          r (first (vals result))]
      (is (= (:id o) (:org/id r)) "org-id should match"))))

(deftest ^:integration org-unit-and-dashboard-resolvers-test
  (testing "Org units mutations and dept dashboard resolver"
    (let [admin (register-user)
          [ok o] (org/create-org! *deps* {:name (str "unit-test-org-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          org-id (:id o)
          env (build-env-with-org {:user-id (:id admin)})

          ;; 1. Create Division Unit via mutation
          div-id (str "div-" (short-suffix))
          div-res (pathom/process env [(list 'unit/create {:unit/id div-id
                                                           :unit/org-id org-id
                                                           :unit/name "Engineering Division"
                                                           :unit/parent-id nil
                                                           :unit/budget 20
                                                           :unit/division-id "ENG"})])
          _ (is (= div-id (:unit/id (first (vals div-res)))))

          ;; 2. Create Dept Unit via mutation
          dept-id (str "dept-" (short-suffix))
          dept-res (pathom/process env [(list 'unit/create {:unit/id dept-id
                                                            :unit/org-id org-id
                                                            :unit/name "Backend Dept"
                                                            :unit/parent-id div-id
                                                            :unit/budget 8
                                                            :unit/division-id "ENG"
                                                            :unit/dept-id "BACKEND"})])
          _ (is (= dept-id (:unit/id (first (vals dept-res)))))

          ;; 3. Resolve Dept Dashboard
          dash-res (p.eql/process env {:unit/id dept-id}
                     [{:dept/dashboard [:unit/id :unit/budget :unit/filled :unit/open :unit/pending :unit/avg-sla-ms]}])
          dash (:dept/dashboard dash-res)]
      (is (= dept-id (:unit/id dash)))
      (is (= 8 (:unit/budget dash)))
      (is (= 0 (:unit/filled dash)))
      (is (= 8 (:unit/open dash)))
      (is (= 0 (:unit/pending dash))))))

(deftest ^:integration headcount-lifecycle-and-capability-advertisement-test
  (testing "Headcount creation, approval, capability advertisement (:headcount/available-actions), and timeline"
    (let [admin (register-user)
          [ok o] (org/create-org! *deps* {:name (str "hc-test-org-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          org-id (:id o)
          admin-id (:id admin)

          mgr (register-user)
          mgr-id (:id mgr)
          _ (org/invite-to-org! *deps* {:org-id org-id :email (:email mgr) :role "MEMBER" :invited-by admin-id})
          inv-id (-> (org/list-invitations-for-user *deps* (:email mgr)) first :invitation/id)
          _ (org/join-org! *deps* {:user-id mgr-id :invitation-id inv-id})

          ;; Create dept unit and assign manager
          dept-id (str "dept-hc-" (short-suffix))
          _ (org/create-org-unit! *deps* {:unit-id dept-id :org-id org-id :name "AI Dept" :parent-id nil :budget 5})
          _ (org/assign-org-actor! *deps* {:org-id org-id :unit-id dept-id :user-id mgr-id :role :hiring-manager})

          admin-env (build-env-with-org {:user-id admin-id})
          mgr-env   (build-env-with-org {:user-id mgr-id})

          ;; 1. Admin creates headcount request
          chain [{:step 1 :role :hiring-manager}]
          create-res (pathom/process admin-env
                       [(list 'headcount/create
                          {:headcount/org-id org-id
                           :headcount/unit-id dept-id
                           :headcount/title "Staff AI Engineer"
                           :headcount/job-level "L6"
                           :headcount/employee-type :full-time
                           :headcount/salary-band "$180k - $220k"
                           :headcount/bonus-target "20%"
                           :headcount/chain-snapshot chain})])
          create-data (first (vals create-res))
          req-id (:headcount/id create-data)
          _ (is (some? req-id))
          _ (is (= :in-approval (:headcount/status create-data)))

          ;; 2. Capability Advertisement for Approver (Manager)
          mgr-actions-res (p.eql/process mgr-env {:headcount/id req-id}
                            [:headcount/available-actions])
          mgr-actions (:headcount/available-actions mgr-actions-res)
          _ (is (contains? (set mgr-actions) :headcount/approve))
          _ (is (contains? (set mgr-actions) :headcount/reject))

          ;; 3. Approver (Manager) approves step 1
          approve-res (pathom/process mgr-env
                        [(list 'headcount/approve-step
                           {:headcount/org-id org-id
                            :headcount/request-id req-id})])
          approve-data (first (vals approve-res))
          _ (is (= :approved (:headcount/result approve-data)))

          ;; 4. Check capability advertisement after full approval
          after-appr-actions (p.eql/process admin-env {:headcount/id req-id}
                               [:headcount/available-actions])
          admin-actions (:headcount/available-actions after-appr-actions)
          _ (is (contains? (set admin-actions) :headcount/transition-hire))

          ;; 5. Headcount Request Resolver with RBAC masking
          req-res (p.eql/process admin-env {:headcount/id req-id}
                    [:headcount/id :headcount/title :headcount/status :headcount/salary-band])
          _ (is (= req-id (:headcount/id req-res)))
          _ (is (= :approved (:headcount/status req-res)))

          ;; 6. Headcount Timeline Resolver
          timeline-res (p.eql/process admin-env {:headcount/id req-id}
                         [{:headcount/timeline [:event :actor :timestamp]}])
          timeline (:headcount/timeline timeline-res)
          _ (is (>= (count timeline) 2))

          ;; 7. Transition to hire
          cand (register-user)
          cand-id (:id cand)
          hire-res (pathom/process admin-env
                     [(list 'headcount/transition-hire
                        {:headcount/org-id org-id
                         :headcount/request-id req-id
                         :headcount/hired-user-id cand-id
                         :headcount/role "ENGINEER"})])
          hire-data (first (vals hire-res))]
      (is (= :filled (:headcount/status hire-data)))
      (is (= cand-id (:headcount/hired-user-id hire-data))))))

(deftest ^:integration org-scoped-resolvers-require-membership-test
  (testing "org-scoped resolvers and headcount/create reject unauthenticated and non-member viewers"
    (let [owner (register-user)
          outsider (register-user)
          [ok o] (org/create-org! *deps* {:name (str "gate-org-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          org-id (:id o)
          owner-env (build-env-with-org {:user-id (:id owner)})
          outsider-env (build-env-with-org {:user-id (:id outsider)})
          anon-env (build-env-with-org nil)]
      (testing "unauthenticated viewers are rejected"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo "Not authenticated"
              (p.eql/process anon-env {:org/id org-id} [{:org/approval-rules [:rule-id]}]))))
      (testing "org/approval-rules rejects non-members"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo "Not a member of this org"
              (p.eql/process outsider-env {:org/id org-id} [{:org/approval-rules [:rule-id]}]))))
      (testing "org/role-permissions rejects non-members"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo "Not a member of this org"
              (p.eql/process outsider-env {:org/id org-id} [:org/role-permissions]))))
      (testing "dept/dashboard rejects non-members of the unit's org"
        (let [unit-id (str "dept-gate-" (short-suffix))]
          (org/create-org-unit! *deps* {:unit-id unit-id :org-id org-id :name "Gated" :parent-id nil :budget 3})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo "Not a member of this org"
                                (p.eql/process outsider-env {:unit/id unit-id} [{:dept/dashboard [:unit/id]}])))))
      (testing "headcount/timeline rejects non-members"
        (let [create-res (pathom/process owner-env
                         [(list 'headcount/create
                            {:headcount/org-id org-id
                             :headcount/title "Gated Req"
                             :headcount/job-level "L6"})])
              req-id (:headcount/id (first (vals create-res)))]
          (is (some? req-id))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo "Not a member of this org"
                                (p.eql/process outsider-env {:headcount/id req-id} [{:headcount/timeline [:event]}])))))
      (testing "headcount/create rejects non-members"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo "Not a member of this org"
              (pathom/process outsider-env
                     [(list 'headcount/create
                        {:headcount/org-id org-id :headcount/title "Nope"})])))))))

(deftest ^:integration routing-rules-match-on-create-test
  (testing "create-headcount-mutation derives bare-key facts so seeded routing rules match"
    (let [owner (register-user)
          [ok o] (org/create-org! *deps* {:name (str "route-org-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          org-id (:id o)
          owner-env (build-env-with-org {:user-id (:id owner)})
          _ (org/set-approval-rules! *deps* org-id
                [{:rule-id "r-l6-vp"
                  :priority 100
                  :name "L6 direct-to-VP rule"
                  :conditions [:= :job-level "L6"]
                  :chain [{:step 1 :role :vp}]}])
          create-res (pathom/process owner-env
                          [(list 'headcount/create
                                {:headcount/org-id org-id
                                 :headcount/title "Routed Req"
                                 :headcount/job-level "L6"})])
          create-data (first (vals create-res))
          req-id (:headcount/id create-data)
          _ (is (some? req-id))
          req-res (p.eql/process owner-env {:headcount/id req-id} [:headcount/chain-snapshot])
          chain (:headcount/chain-snapshot req-res)]
      (is (= [{:step 1 :role :vp}] chain)))))

(deftest ^:integration policy-and-permissions-mutations-test
  (testing "Approval rules and role permissions mutations & resolvers"
    (let [admin (register-user)
          [ok o] (org/create-org! *deps* {:name (str "policy-test-org-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          org-id (:id o)
          env (build-env-with-org {:user-id (:id admin)})

          ;; 1. Set approval rules
          rules [{:rule-id "r1"
                  :priority 10
                  :name "High level rule"
                  :conditions [:= :job-level "L6"]
                  :chain [{:step 1 :role :director}]}]
          rules-res (pathom/process env
                      [(list 'policy/set-approval-rules
                         {:org/id org-id
                          :rules rules})])
          _ (is (= 1 (:count (first (vals rules-res)))))

          ;; Resolve approval rules
          resolved-rules (p.eql/process env {:org/id org-id}
                           [{:org/approval-rules [:rule-id :name :priority]}])
          _ (is (= 1 (count (:org/approval-rules resolved-rules))))

          ;; 2. Set role permissions
          perms-res (pathom/process env
                      [(list 'policy/set-role-permissions
                         {:org/id org-id
                          :role :dept-head
                          :permissions {:view-headcount :view-tree
                                        :view-comp true
                                        :view-bonus false}})])
          _ (is (= :dept-head (:role (first (vals perms-res)))))

          ;; Resolve role permissions
          resolved-perms (p.eql/process env {:org/id org-id}
                           [:org/role-permissions])]
      (is (= {:view-headcount :view-tree :view-comp true :view-bonus false}
             (get-in resolved-perms [:org/role-permissions :dept-head]))))))

(deftest ^:integration workforce-chart-resolver-test
  (testing "workforce-chart-resolver returns workforce tree, hierarchy, and enforces backend RBAC/ABAC"
    (let [admin (register-user)
          emp-user (register-user)
          [ok o] (org/create-org! *deps* {:name (str "wf-org-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          org-id (:id o)
          _ (org/invite-to-org! *deps* {:org-id org-id :email (:email emp-user) :role "MEMBER" :invited-by (:id admin)})
          inv-id (-> (org/list-invitations-for-user *deps* (:email emp-user)) first :invitation/id)
          _ (org/join-org! *deps* {:user-id (:id emp-user) :invitation-id inv-id})

          admin-env (build-env-with-org {:user-id (:id admin)})
          emp-env   (build-env-with-org {:user-id (:id emp-user)})

          ;; 1. Admin queries workforce chart
          admin-res (p.eql/process admin-env {:org/id org-id}
                      [{:org/workforce-chart [:org/id
                                             :workforce/list
                                             :workforce-hierarchy
                                             :headcounts/list
                                             :headcounts-by-manager]}])
          chart-data (:org/workforce-chart admin-res)]

      (is (some? chart-data))
      (is (= org-id (:org/id chart-data)))
      (is (vector? (:workforce/list chart-data)))
      (is (map? (:workforce-hierarchy chart-data)))
      (is (contains? (:workforce-hierarchy chart-data) nil))

      ;; 2. RBAC Backend Comp Masking: Admin receives compensation
      (when-let [p (first (:workforce/list chart-data))]
        (is (some? (:person/compensation p))))

      ;; 3. RBAC Backend Comp Masking: Regular Employee has compensation masked on the wire
      (let [emp-res (p.eql/process emp-env {:org/id org-id}
                      [{:org/workforce-chart [:org/id :workforce/list]}])
            emp-chart (:org/workforce-chart emp-res)]
        (doseq [p (:workforce/list emp-chart)]
          (is (nil? (:person/compensation p)) "Employee role must receive nil compensation in raw response")))

      ;; 4. ABAC Backend Headcount Filtering: Policy context excludes forbidden headcounts
      (let [abac-env (assoc admin-env :abac-policy {:allowed-divisions #{"NONEXISTENT-DIV"}})
            abac-res (p.eql/process abac-env {:org/id org-id}
                       [{:org/workforce-chart [:headcounts/list :headcounts-by-manager]}])
            filtered-hcs (get-in abac-res [:org/workforce-chart :headcounts/list])]
        (is (empty? filtered-hcs) "Headcounts outside allowed dimensions must be excluded on the backend")))))

(ns com.ozimos.workforce.org.ipc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.workforce.web.test-system :as ts]
   [com.rpl.rama.ops :as ops]))

(def ^:dynamic *deps* nil)

(defn system-fixture [tests]
  (let [sys (ts/get-sys)
        us (ts/user-store sys)]
    (binding [*deps* (assoc us :user-store us :cluster-manager (ts/rama-cluster sys))]
      (tests))))

(use-fixtures :once system-fixture)

(defn- short-id []
  (subs (clojure.string/replace (str (ops/random-uuid7)) "-" "") 16 32))

(deftest org-lifecycle-ipc-test
  (testing "Organization complete lifecycle: create, invite, accept, switch, list members, update role, remove member"
    (let [deps *deps*
          owner-suffix (short-id)
          owner-email (str "owner-" owner-suffix "@example.com")
          owner-uname (str "owner_" owner-suffix)
          [ok? owner] (user/register! deps {:email owner-email :password "P@ssword123!" :username owner-uname})
          _ (is (true? ok?))
          owner-id (:id owner)

          member-suffix (short-id)
          member-email (str "member-" member-suffix "@example.com")
          member-uname (str "member_" member-suffix)
          [ok? member] (user/register! deps {:email member-email :password "P@ssword123!" :username member-uname})
          _ (is (true? ok?))
          member-id (:id member)

          ;; 1. Owner creates org
          [ok? org-data] (org/create-org! deps {:name (str "Org-" owner-suffix) :owner-user-id owner-id})
          org-id (:id org-data)
          _ (do
              (is (true? ok?))
              (is (some? org-id))
              (is (= (str "Org-" owner-suffix) (:name org-data)))
              (is (= owner-id (:owner-user-id org-data))))

          ;; 2. Verify owner is active org & has ADMIN role
          _ (is (= org-id (org/get-active-org deps owner-id)))
          membership (org/get-membership deps owner-id org-id)
          _ (do
              (is (= "ADMIN" (:role membership)))
              (is (= "ACTIVE" (:status membership))))

          ;; 3. Owner invites member
          [inv-ok? inv-data] (org/invite-to-org! deps {:org-id org-id
                                                       :email member-email
                                                       :role "MEMBER"
                                                       :invited-by owner-id})
          inv-id (:invitation-id inv-data)
          _ (do
              (is (true? inv-ok?))
              (is (some? inv-id)))

          ;; 4. Member lists invitations
          invs (org/list-invitations-for-user deps member-email)
          _ (do
              (is (= 1 (count invs)))
              (is (= inv-id (:invitation/id (first invs))))
              (is (= org-id (:invitation/org-id (first invs)))))

          ;; 5. Member accepts invitation (joins org)
          [join-ok? join-data] (org/join-org! deps {:user-id member-id :invitation-id inv-id})
          _ (do
              (is (true? join-ok?))
              (is (= org-id (:org-id join-data))))

          ;; 6. Verify member is now in org
          member-membership (org/get-membership deps member-id org-id)
          _ (do
              (is (= "MEMBER" (:role member-membership)))
              (is (= "ACTIVE" (:status member-membership))))

          ;; 7. List members should include owner and member
          members (org/list-members deps org-id)
          _ (do
              (is (= 2 (count members)))
              (is (some #(= owner-id (:user-id %)) members))
              (is (some #(= member-id (:user-id %)) members)))

          ;; 8. Owner updates member role to ADMIN
          _ (do
              (is (true? (org/update-member-role! deps org-id member-id "ADMIN")))
              (is (= "ADMIN" (:role (org/get-membership deps member-id org-id)))))

          ;; 9. Owner removes member from org
          _ (do
              (is (true? (org/remove-member! deps org-id member-id)))
              (is (nil? (org/get-membership deps member-id org-id))))
          members-after (org/list-members deps org-id)]
      (is (= 1 (count members-after)))
      (is (= owner-id (:user-id (first members-after)))))))

(deftest org-unit-and-headcount-lifecycle-ipc-test
  (testing "Org units creation, budget setting, headcount lifecycle, and hire transition"
    (let [deps *deps*
          suffix (short-id)
          [ok? owner] (user/register! deps {:email (str "owner-" suffix "@example.com")
                                            :password "P@ssword123!"
                                            :username (str "owner_" suffix)})
          _ (is (true? ok?))
          owner-id (:id owner)
          [_ org-data] (org/create-org! deps {:name (str "Corp-" suffix) :owner-user-id owner-id})
          org-id (:id org-data)

          ;; 1. Create Division Unit & Department Unit
          div-id (str "div-eng-" suffix)
          dept-id (str "dept-platform-" suffix)
          [_ div-unit] (org/create-org-unit! deps {:unit-id div-id
                                                   :org-id org-id
                                                   :division-id "ENG"
                                                   :dept-id "ALL"
                                                   :name "Engineering Division"
                                                   :parent-id nil
                                                   :budget 10})
          _ (is (= div-id (:unit-id div-unit)))

          [_ dept-unit] (org/create-org-unit! deps {:unit-id dept-id
                                                    :org-id org-id
                                                    :division-id "ENG"
                                                    :dept-id "PLATFORM"
                                                    :name "Platform Engineering"
                                                    :parent-id div-id
                                                    :budget 5})
          _ (is (= dept-id (:unit-id dept-unit)))

          ;; 2. Verify Hierarchy & Stats
          hierarchy (org/get-org-hierarchy deps)
          _ (is (contains? (get hierarchy div-id #{}) dept-id))

          stats (org/get-unit-headcount-stats deps dept-id)
          _ (is (= 5 (:budget stats)))
          _ (is (= 0 (:filled stats)))

          ;; 3. Register Actor Users and Assign Unit Actors
          [_ mgr] (user/register! deps {:email (str "mgr-" suffix "@example.com")
                                        :password "P@ssword123!"
                                        :username (str "mgr_" suffix)})
          mgr-id (:id mgr)
          _ (org/assign-org-actor! deps {:org-id org-id :unit-id dept-id :user-id mgr-id :role :hiring-manager})

          [_ dir] (user/register! deps {:email (str "dir-" suffix "@example.com")
                                        :password "P@ssword123!"
                                        :username (str "dir_" suffix)})
          dir-id (:id dir)
          _ (org/assign-org-actor! deps {:org-id org-id :unit-id dept-id :user-id dir-id :role :department-head})

          ;; 4. Create Headcount Requisition
          req-id (str "req-" suffix)
          chain [{:step 1 :role :hiring-manager}
                 {:step 2 :role :department-head}]
          [_ created-req] (org/create-headcount-request! deps
                            {:request-id req-id
                             :org-id org-id
                             :unit-id dept-id
                             :division-id "ENG"
                             :dept-id "PLATFORM"
                             :location "remote"
                             :job-level "L5"
                             :employee-type :full-time
                             :requester-id mgr-id
                             :title "Senior Distributed Systems Engineer"
                             :justification "Scale Rama topologies"
                             :salary-band "$170k - $200k"
                             :bonus-target "15%"
                             :chain-snapshot chain})
          _ (is (= req-id (:request-id created-req)))

          ;; 5. Verify Step 1 is in Hiring Manager's Pending Queue
          mgr-pending (org/get-user-pending-approvals deps mgr-id)
          _ (is (contains? mgr-pending req-id))

          ;; 6. Manager Approves Step 1 -> Advances to Step 2 (Director)
          [_ step1-res] (org/approve-headcount-step! deps {:org-id org-id
                                                           :request-id req-id
                                                           :approver-user-id mgr-id})
          _ (is (= :step-advanced (:result step1-res)))
          _ (is (not (contains? (org/get-user-pending-approvals deps mgr-id) req-id)))

          dir-pending (org/get-user-pending-approvals deps dir-id)
          _ (is (contains? dir-pending req-id))

          ;; 7. Director Approves Step 2 -> Final Approval Reached (:approved)
          [_ step2-res] (org/approve-headcount-step! deps {:org-id org-id
                                                           :request-id req-id
                                                           :approver-user-id dir-id})
          _ (is (= :approved (:result step2-res)))
          _ (is (not (contains? (org/get-user-pending-approvals deps dir-id) req-id)))

          final-req (org/get-headcount-request deps req-id)
          _ (is (= :approved (:status final-req)))
          _ (is (= [mgr-id dir-id] (:approved-by final-req)))

          ;; 8. Transition to Hire
          [_ candidate] (user/register! deps {:email (str "cand-" suffix "@example.com")
                                              :password "P@ssword123!"
                                              :username (str "cand_" suffix)})
          cand-id (:id candidate)
          [_ hire-res] (org/transition-headcount-to-hire! deps {:org-id org-id
                                                                :request-id req-id
                                                                :hired-user-id cand-id
                                                                :role "ENGINEER"})
          _ (is (= :filled (:status hire-res)))

          filled-req (org/get-headcount-request deps req-id)
          _ (is (= :filled (:status filled-req)))
          _ (is (= cand-id (:hired-user-id filled-req)))

          ;; Verify Candidate is now an Active Member in the Org
          cand-membership (org/get-membership deps cand-id org-id)]
      (is (= "ENGINEER" (:role cand-membership)))
      (is (= "ACTIVE" (:status cand-membership))))))

(deftest dynamic-org-restructure-cascade-ipc-test
  (testing "Re-parenting an org unit cascades and updates hierarchy"
    (let [deps *deps*
          suffix (short-id)
          [ok? owner] (user/register! deps {:email (str "owner2-" suffix "@example.com")
                                            :password "P@ssword123!"
                                            :username (str "owner2_" suffix)})
          _ (is (true? ok?))
          owner-id (:id owner)
          [_ org-data] (org/create-org! deps {:name (str "RestructureCorp-" suffix) :owner-user-id owner-id})
          org-id (:id org-data)

          ;; Setup Division 1 and Division 2
          div1-id (str "div-1-" suffix)
          div2-id (str "div-2-" suffix)
          dept-id (str "dept-ai-" suffix)

          [_ _] (org/create-org-unit! deps {:unit-id div1-id :org-id org-id :name "Div 1" :parent-id nil :budget 20})
          [_ _] (org/create-org-unit! deps {:unit-id div2-id :org-id org-id :name "Div 2" :parent-id nil :budget 20})
          [_ _] (org/create-org-unit! deps {:unit-id dept-id :org-id org-id :name "AI Dept" :parent-id div1-id :budget 5})

          ;; Move AI Dept from Div1 to Div2 (Org Restructure Cascade)
          _ (org/reparent-org-unit! deps {:org-id org-id :unit-id dept-id :new-parent-id div2-id})

          ;; Verify hierarchy update
          h (org/get-org-hierarchy deps)]
      (is (contains? (get h div2-id #{}) dept-id))
      (is (not (contains? (get h div1-id #{}) dept-id))))))

(deftest re-approval-reset-on-field-edit-ipc-test
  (testing "Editing a sensitive field while :in-approval resets chain to :draft"
    (let [deps *deps*
          suffix (short-id)
          [_ owner]    (user/register! deps {:email (str "owner-re-" suffix "@example.com")
                                             :password "P@ssword123!"
                                             :username (str "owner_re_" suffix)})
          owner-id     (:id owner)
          [_ org-data] (org/create-org! deps {:name (str "ResetCorp-" suffix) :owner-user-id owner-id})
          org-id       (:id org-data)

          unit-id (str "dept-reset-" suffix)
          _       (org/create-org-unit! deps {:unit-id unit-id :org-id org-id :name "Reset Dept"
                                              :parent-id nil :budget 5})

          [_ mgr] (user/register! deps {:email (str "mgr-re-" suffix "@example.com")
                                        :password "P@ssword123!"
                                        :username (str "mgr_re_" suffix)})
          mgr-id (:id mgr)
          _ (org/assign-org-actor! deps {:org-id org-id :unit-id unit-id :user-id mgr-id :role :hiring-manager})

          ;; 2-step chain so step 1 approval leaves it :in-approval
          req-id (str "req-reset-" suffix)
          chain  [{:step 1 :role :hiring-manager}
                  {:step 2 :role :hiring-manager}]
          [_ _]  (org/create-headcount-request! deps
                   {:request-id req-id :org-id org-id :unit-id unit-id
                    :division-id "ENG" :dept-id "RESET"
                    :location "remote" :job-level "L4" :employee-type :full-time
                    :requester-id owner-id :title "Test Role"
                    :justification "Test" :salary-band "$100k"
                    :bonus-target "10%" :chain-snapshot chain})

          ;; Approve step 1 -> advances to step 2, status remains :in-approval
          [_ step1] (org/approve-headcount-step! deps {:org-id org-id
                                                       :request-id req-id
                                                       :approver-user-id mgr-id})
          _ (is (= :step-advanced (:result step1)))

          in-approval-req (org/get-headcount-request deps req-id)
          _ (is (= :in-approval (:status in-approval-req)))

          ;; Edit sensitive field -> triggers reset to :draft
          [edit-ok? _] (org/edit-headcount-field! deps {:org-id org-id
                                                        :request-id req-id
                                                        :editor-user-id owner-id
                                                        :field-name :job-level
                                                        :new-value "L5"})
          _ (is (true? edit-ok?))

          ;; Verify reset to :draft, step 0, cleared approved-by
          reset-req (org/get-headcount-request deps req-id)]
      (is (= :draft (:status reset-req)))
      (is (= 0 (:current-step reset-req)))
      (is (= [] (:approved-by reset-req)))
      (is (= "L5" (:job-level reset-req))))))

(deftest idempotency-key-dedup-ipc-test
  (testing "Submitting the same headcount event twice with identical idempotency-key is a no-op"
    (let [deps *deps*
          suffix (short-id)
          [_ owner]    (user/register! deps {:email (str "owner-idem-" suffix "@example.com")
                                             :password "P@ssword123!"
                                             :username (str "owner_idem_" suffix)})
          owner-id     (:id owner)
          [_ org-data] (org/create-org! deps {:name (str "IdemCorp-" suffix) :owner-user-id owner-id})
          org-id       (:id org-data)

          unit-id (str "dept-idem-" suffix)
          _ (org/create-org-unit! deps {:unit-id unit-id :org-id org-id :name "Idem Dept"
                                        :parent-id nil :budget 3})

          idem-key (str "ikey-" suffix)
          req-id   (str "req-idem-" suffix)
          chain    [{:step 1 :role :hiring-manager}]

          ;; First submission with idempotency key
          [ok1? _] (org/create-headcount-request! deps
                     {:request-id req-id :org-id org-id :unit-id unit-id
                      :division-id "ENG" :dept-id "IDEM"
                      :location "remote" :job-level "L3" :employee-type :full-time
                      :requester-id owner-id :title "Idempotent Role"
                      :justification "Test" :salary-band "$90k"
                      :bonus-target "5%" :chain-snapshot chain
                      :idempotency-key idem-key})
          _ (is (true? ok1?))

          ;; Second submission: same idempotency-key, different request-id (should be no-op)
          [_ _] (org/create-headcount-request! deps
                  {:request-id (str req-id "-dup")
                   :org-id org-id :unit-id unit-id
                   :division-id "ENG" :dept-id "IDEM"
                   :location "remote" :job-level "L3" :employee-type :full-time
                   :requester-id owner-id :title "Duplicate Role"
                   :justification "Test" :salary-band "$90k"
                   :bonus-target "5%" :chain-snapshot chain
                   :idempotency-key idem-key})

          ;; Only the first request should exist
          created-req (org/get-headcount-request deps req-id)
          dup-req     (org/get-headcount-request deps (str req-id "-dup"))]

      ;; Original request exists
      (is (= req-id (:request-id created-req)))
      ;; Duplicate was NOT persisted
      (is (nil? dup-req))
      ;; Stats show only 1 pending
      (let [stats (org/get-unit-headcount-stats deps unit-id)]
        (is (= 1 (:pending stats)))))))

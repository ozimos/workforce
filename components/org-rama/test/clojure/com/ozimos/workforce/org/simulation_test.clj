(ns com.ozimos.workforce.org.simulation-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.workforce.org.simulation.behavior-tree :as bt]
   [com.ozimos.workforce.org.simulation.runner :as runner]
   [com.ozimos.workforce.org.tools.escapement :as esc]
   [com.ozimos.workforce.org.tools.mcp :as mcp]
   [com.ozimos.workforce.web.test-system :as ts]
   [com.rpl.rama.ops :as ops]
   [jsonista.core :as json]))

(def ^:dynamic *deps* nil)

(defn system-fixture
  [tests]
  (let [sys (ts/get-sys)
        us (ts/user-store sys)]
    (binding [*deps* (assoc us :user-store us :cluster-manager (ts/rama-cluster sys))]
      (tests))))

(use-fixtures :once system-fixture)

(defn- short-suffix []
  (subs (clojure.string/replace (str (ops/random-uuid7)) "-" "") 16 32))

(defn- register-user []
  (let [suffix (short-suffix)
        [ok u] (user/register! *deps* {:username (str "simuser-" suffix)
                                       :email (str "simuser-" suffix "@test.com")
                                       :password "P@ssword123"})]
    (is ok)
    u))

(deftest behavior-tree-engine-test
  (testing "Behavior Tree sequence, selector, condition, action, and inverter nodes"
    (let [ctx {:counter 0}
          inc-action (bt/action "Inc" (fn [c] {:status :success :context (update c :counter inc)}))
          fail-action (bt/action "Fail" (fn [_] {:status :failure}))
          check-gt-0 (bt/condition "Gt0" (fn [c] (pos? (:counter c))))

          seq-node (bt/sequence* "Seq" inc-action check-gt-0)
          res1 (bt/tick seq-node ctx)]
      (is (= :success (:status res1)))
      (is (= 1 (get-in res1 [:context :counter])))

      (let [sel-node (bt/selector* "Sel" fail-action inc-action)
            res2 (bt/tick sel-node {:counter 5})]
        (is (= :success (:status res2)))
        (is (= 6 (get-in res2 [:context :counter]))))

      (let [inv-node (bt/inverter "Inv" fail-action)
            res3 (bt/tick inv-node {})]
        (is (= :success (:status res3)))))))

(deftest escapement-and-mcp-server-test
  (testing "MCP server initialization, tools/list, and tools/call execution"
    (let [admin (register-user)
          admin-id (:id admin)
          [ok o] (org/create-org! *deps* {:name (str "mcp-org-" (short-suffix)) :owner-user-id admin-id})
          _ (is ok)
          org-id (:id o)

          dept-id (str "dept-mcp-" (short-suffix))
          _ (org/create-org-unit! *deps* {:unit-id dept-id :org-id org-id :name "Data Science Dept" :budget 10})
          _ (org/assign-org-actor! *deps* {:org-id org-id :unit-id dept-id :user-id admin-id :role :hiring-manager})

          ctx {:user-id admin-id :roles ["ADMIN"]}]

      ;; 1. MCP initialize
      (let [init-resp (mcp/handle-mcp-request *deps* ctx {:jsonrpc "2.0" :id 1 :method "initialize"})]
        (is (= "2.0" (:jsonrpc init-resp)))
        (is (= "workforce-mcp-server" (get-in init-resp [:result :serverInfo :name]))))

      ;; 2. MCP tools/list
      (let [list-resp (mcp/handle-mcp-request *deps* ctx {:jsonrpc "2.0" :id 2 :method "tools/list"})
            tools (get-in list-resp [:result :tools])]
        (is (seq tools))
        (is (some #(= "workforce_create_headcount" (:name %)) tools))
        (is (some #(= "workforce_get_dept_dashboard" (:name %)) tools)))

      ;; 3. MCP tools/call -> workforce_create_headcount
      (let [call-resp (mcp/handle-mcp-request *deps* ctx
                        {:jsonrpc "2.0" :id 3 :method "tools/call"
                         :params {:name "workforce_create_headcount"
                                  :arguments {:org-id org-id
                                              :unit-id dept-id
                                              :title "Staff Data Scientist"
                                              :job-level "L5"
                                              :salary-band "$170k - $210k"
                                              :chain-snapshot [{:step 1 :role :hiring-manager}]}}})
            content (get-in call-resp [:result :content 0 :text])
            parsed-res (when content (json/read-value content json/keyword-keys-object-mapper))]
        (is (false? (get-in call-resp [:result :isError])))
        (is (true? (:ok parsed-res)))
        (is (some? (get-in parsed-res [:headcount :request-id])))))))

(deftest multi-agent-simulation-scenarios-test
  (testing "All 4 multi-agent simulation scenarios via Behavior Trees"
    (let [admin (register-user)
          admin-id (:id admin)
          mgr (register-user)
          mgr-id (:id mgr)
          dir (register-user)
          dir-id (:id dir)
          vp (register-user)
          vp-id (:id vp)
          cand (register-user)
          cand-id (:id cand)

          [ok o] (org/create-org! *deps* {:name (str "sim-full-org-" (short-suffix)) :owner-user-id admin-id})
          _ (is ok)
          org-id (:id o)

          ;; Add members to org
          _ (org/invite-to-org! *deps* {:org-id org-id :email (:email mgr) :role "MEMBER" :invited-by admin-id})
          _ (org/join-org! *deps* {:user-id mgr-id :invitation-id (-> (org/list-invitations-for-user *deps* (:email mgr)) first :invitation/id)})
          _ (org/invite-to-org! *deps* {:org-id org-id :email (:email dir) :role "MEMBER" :invited-by admin-id})
          _ (org/join-org! *deps* {:user-id dir-id :invitation-id (-> (org/list-invitations-for-user *deps* (:email dir)) first :invitation/id)})
          _ (org/invite-to-org! *deps* {:org-id org-id :email (:email vp) :role "MEMBER" :invited-by admin-id})
          _ (org/join-org! *deps* {:user-id vp-id :invitation-id (-> (org/list-invitations-for-user *deps* (:email vp)) first :invitation/id)})

          dept-id (str "dept-sim-" (short-suffix))
          _ (org/create-org-unit! *deps* {:unit-id dept-id :org-id org-id :name "Platform Dept" :budget 20})
          _ (org/assign-org-actor! *deps* {:org-id org-id :unit-id dept-id :user-id mgr-id :role :hiring-manager})
          _ (org/assign-org-actor! *deps* {:org-id org-id :unit-id dept-id :user-id dir-id :role :dept-head})

          sim-opts {:org-id org-id
                    :unit-id dept-id
                    :manager-id mgr-id
                    :director-id dir-id
                    :vp-id vp-id
                    :candidate-id cand-id}]

      (testing "Scenario 1: Happy Path Full Lifecycle"
        (let [s1-res (runner/run-scenario-1-happy-path *deps* sim-opts)]
          (is (true? (:success s1-res)))
          (is (= :filled (:final-status s1-res)))))

      (testing "Scenario 2: Dynamic Custom Approval Routing"
        (let [s2-res (runner/run-scenario-2-dynamic-routing *deps* sim-opts)]
          (is (true? (:success s2-res)))
          (is (= :approved (:approval-result s2-res)))))

      (testing "Scenario 3: Sensitive Field Edit Triggers Re-Approval Reset"
        (let [s3-res (runner/run-scenario-3-field-edit-reset *deps* sim-opts)]
          (is (true? (:success s3-res)))
          (is (true? (:verified-reset s3-res)))))

      (testing "Scenario 4: Idempotency Dedup Guard"
        (let [s4-res (runner/run-scenario-4-idempotency *deps* sim-opts)]
          (is (true? (:success s4-res)))
          (is (some? (:request-id s4-res))))))))

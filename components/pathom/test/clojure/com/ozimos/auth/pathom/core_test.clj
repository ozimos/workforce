(ns com.ozimos.auth.pathom.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.auth.auth-api.test-system :as ts]
   [com.ozimos.auth.pathom.core :as pathom]
   [com.ozimos.auth.user.interface :as user]
   [com.wsscode.pathom3.interface.eql :as p.eql]))

(def ^:dynamic *deps* nil)

(defn system-fixture
  [tests]
  (ts/with-sys
    (let [us (ts/user-store sys)]
      (binding [*deps* (assoc us :user-store us)]
        (tests)))))

(use-fixtures :once system-fixture)

(defn- short-suffix []
  (-> (java.util.UUID/randomUUID) str (.replace "-" "") (.substring 0 12)))

(defn- register-user []
  (let [suffix (short-suffix)
        [ok user] (user/register! *deps* {:username (str "ptest-" suffix)
                                          :email (str "ptest-" suffix "@test.com")
                                          :password "P@ssword123"})]
    (is ok)
    user))

(deftest ^:integration current-user-resolver-test
  (testing "current-user-resolver returns authenticated user info"
    (let [user (register-user)
          env (pathom/build-env *deps* {:user-id (:id user)})
          result (pathom/process env [:current-user/id :current-user/username :current-user/email])]
      (println "\n=== current-user-resolver-test ===")
      (println "result:" (pr-str result))
      (is (= (:id user) (:current-user/id result)) "user-id should match")
      (is (= (:username user) (:current-user/username result)) "username should match")
      (is (= (:email user) (:current-user/email result)) "email should match")
      (println "=== end current-user-resolver-test ==="))))

(defn- unauthenticated-ex? [e]
  (= :unauthenticated (some #(-> % ex-data :type)
                            (take-while some? (iterate #(.getCause ^Throwable %) e)))))

(deftest ^:integration current-user-resolver-unauthenticated-test
  (testing "current-user-resolver throws on unauthenticated request"
    (let [env (pathom/build-env *deps*)]
      (println "\n=== current-user-resolver-unauthenticated-test ===")
      (is (try (pathom/process env [:current-user/id])
               false
               (catch Exception e
                 (unauthenticated-ex? e))))
      (println "=== end current-user-resolver-unauthenticated-test ==="))))

(deftest ^:integration user-orgs-resolver-test
  (testing "user-orgs-resolver returns orgs for authenticated user"
    (let [user (register-user)
          [ok org] (user/create-org! *deps* {:name (str "org-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (pathom/build-env *deps* {:user-id (:id user)})
          result (pathom/process env [{:user/orgs [:org/id :org/name :org/role :org/status]}])]
      (println "\n=== user-orgs-resolver-test ===")
      (println "result:" (pr-str result))
      (let [orgs (:user/orgs result)]
        (is (some? orgs) "user/orgs should not be nil")
        (is (some #(= (:id org) (:org/id %)) orgs) "created org should be in the list"))
      (println "=== end user-orgs-resolver-test ==="))))

(deftest ^:integration active-org-resolver-test
  (testing "active-org-resolver returns user's active org"
    (let [user (register-user)
          [ok org] (user/create-org! *deps* {:name (str "active-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (pathom/build-env *deps* {:user-id (:id user)})
          result (pathom/process env [{:user/active-org [:org/id :org/name :org/role]}])]
      (println "\n=== active-org-resolver-test ===")
      (println "result:" (pr-str result))
      (let [active (:user/active-org result)]
        (is (some? active) "active-org should not be nil")
        (is (= (:id org) (:org/id active)) "org-id should match"))
      (println "=== end active-org-resolver-test ==="))))

(deftest ^:integration active-org-resolver-no-org-test
  (testing "active-org-resolver returns nil when user has no orgs"
    (let [user (register-user)
          env (pathom/build-env *deps* {:user-id (:id user)})
          result (pathom/process env [{:user/active-org [:org/id]}])]
      (println "\n=== active-org-resolver-no-org-test ===")
      (println "result:" (pr-str result))
      (is (nil? (:user/active-org result)) "active-org should be nil for user with no orgs")
      (println "=== end active-org-resolver-no-org-test ==="))))

(deftest ^:integration create-org-mutation-test
  (testing "create-org mutation creates an org"
    (let [user (register-user)
          env (pathom/build-env *deps* {:user-id (:id user)})
          result (pathom/process env [(list 'org/create {:org/name (str "mutation-org-" (short-suffix))})])]
      (println "\n=== create-org-mutation-test ===")
      (println "result:" (pr-str result))
      (let [r (first (vals result))]
        (is (some? (:org/id r)) "org/id should be present")
        (is (some? (:org/name r)) "org/name should be present")
        (is (= "ADMIN" (:org/role r)) "role should be ADMIN"))
      (println "=== end create-org-mutation-test ==="))))

(deftest ^:integration create-org-mutation-duplicate-test
  (testing "create-org mutation with duplicate name returns errors"
    (let [user (register-user)
          org-name (str "dup-mut-" (short-suffix))
          env (pathom/build-env *deps* {:user-id (:id user)})
          _ (pathom/process env [(list 'org/create {:org/name org-name})])
          result (pathom/process env [(list 'org/create {:org/name org-name})])]
      (println "\n=== create-org-mutation-duplicate-test ===")
      (println "result:" (pr-str result))
      (let [r (first (vals result))]
        (is (some? (:org/errors r)) "should return errors for duplicate name"))
      (println "=== end create-org-mutation-duplicate-test ==="))))

(deftest ^:integration switch-org-mutation-test
  (testing "switch-org mutation changes active org"
    (let [user (register-user)
          [ok org1] (user/create-org! *deps* {:name (str "switch-mut-a-" (short-suffix)) :owner-user-id (:id user)})
          [ok2 org2] (user/create-org! *deps* {:name (str "switch-mut-b-" (short-suffix)) :owner-user-id (:id user)})
          _ (is (and ok ok2))
          env (pathom/build-env *deps* {:user-id (:id user)})
          _ (pathom/process env [(list 'org/switch {:org/id (:id org2)})])
          result (pathom/process env [{:user/active-org [:org/id]}])]
      (println "\n=== switch-org-mutation-test ===")
      (println "result:" (pr-str result))
      (is (= (:id org2) (:org/id (:user/active-org result))) "active org should be org2")
      (println "=== end switch-org-mutation-test ==="))))

(deftest ^:integration user-invitations-resolver-test
  (testing "user-invitations-resolver returns pending invitations"
    (let [owner (register-user)
          joiner (register-user)
          [ok org] (user/create-org! *deps* {:name (str "inv-resolver-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          _ (user/invite-to-org! *deps* {:org-id (:id org) :email (:email joiner) :role "MEMBER" :invited-by (:id owner)})
          env (pathom/build-env *deps* {:user-id (:id joiner)})
          result (pathom/process env [{:user/invitations [:invitation/id :invitation/org-id :invitation/role]}])]
      (println "\n=== user-invitations-resolver-test ===")
      (println "result:" (pr-str result))
      (let [invitations (:user/invitations result)]
        (is (seq invitations) "should have pending invitations")
        (is (= (:id org) (:invitation/org-id (first invitations))) "org-id should match")
        (is (= "MEMBER" (:invitation/role (first invitations))) "role should be MEMBER"))
      (println "=== end user-invitations-resolver-test ==="))))

(deftest ^:integration org-members-resolver-test
  (testing "org-members-resolver returns members (admin only)"
    (let [admin (register-user)
          [ok org] (user/create-org! *deps* {:name (str "members-resolver-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          env (pathom/build-env *deps* {:user-id (:id admin)})
          ;; Use 3-arity p.eql/process to provide :org/id as initial entity
          result (p.eql/process env {:org/id (:id org)} [{:org/members [:user/id :membership/role]}])]
      (println "\n=== org-members-resolver-test ===")
      (println "result:" (pr-str result))
      (let [members (:org/members result)]
        (is (seq members) "should have members")
        (is (= (:id admin) (:user/id (first members))) "admin should be a member")
        (is (= "ADMIN" (:membership/role (first members))) "role should be ADMIN"))
      (println "=== end org-members-resolver-test ==="))))

(deftest ^:integration org-by-id-resolver-test
  (testing "org-by-id-resolver returns org details for members"
    (let [user (register-user)
          [ok org] (user/create-org! *deps* {:name (str "org-by-id-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (pathom/build-env *deps* {:user-id (:id user)})
          ;; Use 3-arity p.eql/process to provide :org/id as initial entity
          result (p.eql/process env {:org/id (:id org)} [:org/id :org/name :org/owner-id :org/created-at])]
      (println "\n=== org-by-id-resolver-test ===")
      (println "result:" (pr-str result))
      (is (= (:id org) (:org/id result)) "org-id should match")
      (is (some? (:org/name result)) "org/name should be present")
      (is (= (:id user) (:org/owner-id result)) "owner-id should match")
      (println "=== end org-by-id-resolver-test ==="))))

(deftest ^:integration join-org-mutation-test
  (testing "join-org mutation accepts invitation and joins org"
    (let [owner (register-user)
          joiner (register-user)
          [ok org] (user/create-org! *deps* {:name (str "join-mut-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          _ (user/invite-to-org! *deps* {:org-id (:id org) :email (:email joiner) :role "MEMBER" :invited-by (:id owner)})
          inv-id (-> (user/list-invitations-for-user *deps* (:email joiner)) first :invitation/id)
          env (pathom/build-env *deps* {:user-id (:id joiner)})
          result (pathom/process env [(list 'org/join {:invitation/id inv-id})])]
      (println "\n=== join-org-mutation-test ===")
      (println "result:" (pr-str result))
      (let [r (first (vals result))]
        (is (= (:id org) (:org/id r)) "org-id should match"))
      (println "=== end join-org-mutation-test ==="))))

(deftest ^:integration auth-guard-test
  (testing "Resolvers and mutations throw :unauthenticated for anonymous requests"
    (let [env (pathom/build-env *deps*)]
      (println "\n=== auth-guard-test ===")
      (is (try (pathom/process env [:current-user/id])
               false
               (catch Exception e
                 (unauthenticated-ex? e)))
          "current-user-resolver should throw for anonymous")
      (println "=== end auth-guard-test ==="))))

(deftest ^:integration mutate-unauthenticated-test
  (testing "Mutations throw :unauthenticated for anonymous requests"
    (let [env (pathom/build-env *deps*)]
      (println "\n=== mutate-unauthenticated-test ===")
      (is (try (pathom/process env [(list 'org/create {:org/name "test"})])
               false
               (catch Exception e
                 (unauthenticated-ex? e)))
          "create-org mutation should throw for anonymous")
      (println "=== end mutate-unauthenticated-test ==="))))

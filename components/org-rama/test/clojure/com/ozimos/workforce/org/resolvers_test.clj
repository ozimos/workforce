(ns com.ozimos.workforce.org.resolvers-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.workforce.auth-api.test-system :as ts]
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.workforce.org.resolvers :as org-res]
   [com.ozimos.workforce.pathom.core :as pathom]
   [com.ozimos.workforce.user.interface :as user]
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
  (-> (random-uuid) str (.replace "-" "") (.substring 0 12)))

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
  (testing "user-orgs-resolver returns orgs for authenticated user"
    (let [user (register-user)
          [ok o] (org/create-org! *deps* {:name (str "org-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id user)})
          result (pathom/process env [{:user/orgs [:org/id :org/name :org/role :org/status]}])]
      (let [orgs (:user/orgs result)]
        (is (some? orgs) "user/orgs should not be nil")
        (is (some #(= (:id o) (:org/id %)) orgs) "created org should be in the list")))))

(deftest ^:integration active-org-resolver-test
  (testing "active-org-resolver returns user's active org"
    (let [user (register-user)
          [ok o] (org/create-org! *deps* {:name (str "active-" (short-suffix)) :owner-user-id (:id user)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id user)})
          result (pathom/process env [{:user/active-org [:org/id :org/name :org/role]}])]
      (let [active (:user/active-org result)]
        (is (some? active) "active-org should not be nil")
        (is (= (:id o) (:org/id active)) "org-id should match")))))

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
          result (pathom/process env [(list 'org/create {:org/name (str "mutation-org-" (short-suffix))})])]
      (let [r (first (vals result))]
        (is (some? (:org/id r)) "org/id should be present")
        (is (some? (:org/name r)) "org/name should be present")
        (is (= "ADMIN" (:org/role r)) "role should be ADMIN")))))

(deftest ^:integration create-org-mutation-duplicate-test
  (testing "create-org mutation with duplicate name returns errors"
    (let [user (register-user)
          org-name (str "dup-mut-" (short-suffix))
          env (build-env-with-org {:user-id (:id user)})
          _ (pathom/process env [(list 'org/create {:org/name org-name})])
          result (pathom/process env [(list 'org/create {:org/name org-name})])]
      (let [r (first (vals result))]
        (is (some? (:org/errors r)) "should return errors for duplicate name")))))

(deftest ^:integration switch-org-mutation-test
  (testing "switch-org mutation changes active org"
    (let [user (register-user)
          [ok org1] (org/create-org! *deps* {:name (str "switch-mut-a-" (short-suffix)) :owner-user-id (:id user)})
          [ok2 org2] (org/create-org! *deps* {:name (str "switch-mut-b-" (short-suffix)) :owner-user-id (:id user)})
          _ (is (and ok ok2))
          env (build-env-with-org {:user-id (:id user)})
          _ (pathom/process env [(list 'org/switch {:org/id (:id org2)})])
          result (pathom/process env [{:user/active-org [:org/id]}])]
      (is (= (:id org2) (:org/id (:user/active-org result))) "active org should be org2"))))

(deftest ^:integration user-invitations-resolver-test
  (testing "user-invitations-resolver returns pending invitations"
    (let [owner (register-user)
          joiner (register-user)
          [ok o] (org/create-org! *deps* {:name (str "inv-resolver-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          _ (org/invite-to-org! *deps* {:org-id (:id o) :email (:email joiner) :role "MEMBER" :invited-by (:id owner)})
          env (build-env-with-org {:user-id (:id joiner)})
          result (pathom/process env [{:user/invitations [:invitation/id :invitation/org-id :invitation/role]}])]
      (let [invitations (:user/invitations result)]
        (is (seq invitations) "should have pending invitations")
        (is (= (:id o) (:invitation/org-id (first invitations))) "org-id should match")
        (is (= "MEMBER" (:invitation/role (first invitations))) "role should be MEMBER")))))

(deftest ^:integration org-members-resolver-test
  (testing "org-members-resolver returns members (admin only)"
    (let [admin (register-user)
          [ok o] (org/create-org! *deps* {:name (str "members-resolver-" (short-suffix)) :owner-user-id (:id admin)})
          _ (is ok)
          env (build-env-with-org {:user-id (:id admin)})
          result (p.eql/process env {:org/id (:id o)} [{:org/members [:user/id :membership/role]}])]
      (let [members (:org/members result)]
        (is (seq members) "should have members")
        (is (= (:id admin) (:user/id (first members))) "admin should be a member")
        (is (= "ADMIN" (:membership/role (first members))) "role should be ADMIN")))))

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

(deftest ^:integration join-org-mutation-test
  (testing "join-org mutation accepts invitation and joins org"
    (let [owner (register-user)
          joiner (register-user)
          [ok o] (org/create-org! *deps* {:name (str "join-mut-" (short-suffix)) :owner-user-id (:id owner)})
          _ (is ok)
          _ (org/invite-to-org! *deps* {:org-id (:id o) :email (:email joiner) :role "MEMBER" :invited-by (:id owner)})
          inv-id (-> (org/list-invitations-for-user *deps* (:email joiner)) first :invitation/id)
          env (build-env-with-org {:user-id (:id joiner)})
          result (pathom/process env [(list 'org/join {:invitation/id inv-id})])]
      (let [r (first (vals result))]
        (is (= (:id o) (:org/id r)) "org-id should match")))))

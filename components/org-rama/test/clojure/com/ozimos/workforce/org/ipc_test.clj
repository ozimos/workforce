(ns com.ozimos.workforce.org.ipc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.rama.module :as mod]
   [com.ozimos.omni-auth.rama.registry :as reg]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.extension :as org-ext]
   [com.ozimos.workforce.org.interface :as org]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.test :as rtest]))

(def ^:dynamic *deps* nil)

(defn rama-fixture [f]
  (reg/reset-registry!)
  (reg/register-extension! (org-ext/->OrgExtension))
  (let [ipc (rtest/create-ipc)]
    (rtest/launch-module! ipc mod/AuthModule {:tasks 4 :threads 2})
    (binding [*deps* {:cluster-manager ipc :rama {:cluster-manager ipc :mode :ipc}}]
      (try
        (f)
        (finally (.close ipc))))))

(use-fixtures :each rama-fixture)

(defn- short-id []
  (subs (str (random-uuid)) 0 8))

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

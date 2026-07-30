(ns com.ozimos.auth.rama.ipc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.auth.auth-api.test-system :as ts]
   [com.ozimos.auth.rama.module :as mod]
   [com.rpl.rama :as rama]
   [com.rpl.rama.path :refer [ALL keypath]]
   [com.rpl.rama.test :as rtest])
  (:import
   (com.rpl.rama.test InProcessCluster)))

(def ^:dynamic *ipc* nil)
(def ^:dynamic *module-name* nil)

(defn ipc-fixture
  [tests]
  (ts/with-sys
    (if sys
      (let [rama-config (:rama (ts/user-store sys))]
        (binding [*ipc* (:cluster-manager rama-config)
                  *module-name* (rama/get-module-name mod/AuthModule)]
          (tests)))
      (let [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc mod/AuthModule {:tasks 4 :threads 2})
        (binding [*ipc* ipc
                  *module-name* (rama/get-module-name mod/AuthModule)]
          (try
            (tests)
            (finally (.close ipc))))))))

(use-fixtures :once ipc-fixture)

(deftest ^:integration id-format-test
  (testing "ModuleUniqueIdPState.genId() returns expected format via foreign-append!"
    (let [depot (rama/foreign-depot *ipc* *module-name* "*registration-depot")
          uname (str "id-format-" (java.util.UUID/randomUUID))
          email (str uname "@test.com")
          result (rama/foreign-append! depot
                   (mod/->Registration (str (java.util.UUID/randomUUID))
                     uname "hash" email ["ROLE_USER"]))]
      (println "\n=== id-format-test ===")
      (println "foreign-append! result type:" (type result))
      (println "foreign-append! result value:" (pr-str result))
      (println "result map?:" (map? result))
      (is (some? result) "foreign-append! should return a non-nil result")
      (println "=== end id-format-test ==="))))

(deftest ^:integration pstate-roundtrip-test
  (testing "Writing via depot and reading via pstate roundtrip works"
    (let [depot (rama/foreign-depot *ipc* *module-name* "*registration-depot")
          uid->id (rama/foreign-pstate *ipc* *module-name* "$$username->id")
          profiles (rama/foreign-pstate *ipc* *module-name* "$$profiles")
          suffix (str (java.util.UUID/randomUUID))
          uname (str "roundtrip-" suffix)
          email (str uname "@test.com")
          pwd-hash "bcrypt-hash"
          roles []
          reg (mod/->Registration (str (java.util.UUID/randomUUID))
                uname pwd-hash email roles)
          user-id (rama/foreign-append! depot reg)]

      (println "\n=== pstate-roundtrip-test ===")
      (println "user-id type:" (type user-id) "value:" (pr-str user-id))

      (let [stored-id (rama/foreign-select-one (keypath uname) uid->id)]
        (println "$$username->id[" uname "]:" (pr-str stored-id))
        (println "stored-id type:" (type stored-id))
        (is (some? stored-id) "$$username->id should contain the username mapping"))

      (let [uid (if (map? user-id) (first (vals user-id)) user-id)
            profile (rama/foreign-select-one (keypath uid :email) profiles)]
        (println "$$profiles->email[" uid "]:" (pr-str profile))
        (println "profile type:" (type profile))
        (is (some? profile) (str "$$profiles should be queryable by user-id: " (pr-str user-id))))

      (println "=== end pstate-roundtrip-test ==="))))

(deftest ^:integration session-end-cleanup-test
  (testing "Session-end clears $$sessions entry"
    (let [suffix (str (java.util.UUID/randomUUID))
          depots {:registration (rama/foreign-depot *ipc* *module-name* "*registration-depot")
                  :session (rama/foreign-depot *ipc* *module-name* "*session-depot")
                  :session-end (rama/foreign-depot *ipc* *module-name* "*session-end-depot")}
          pstates {:sessions (rama/foreign-pstate *ipc* *module-name* "$$sessions")}
          uname (str "session-cleanup-" suffix)
          email (str uname "@test.com")
          user-id (get (rama/foreign-append! (:registration depots)
                         (mod/->Registration (str (java.util.UUID/randomUUID))
                           uname "hash" email ["ROLE_USER"])) "auth")
          session-id (str (java.util.UUID/randomUUID))
          jti (str (java.util.UUID/randomUUID))
          expires-at (+ (System/currentTimeMillis) 3600000)]

      (println "\n=== session-end-cleanup-test ===")

      (println "Registering user, user-id:" user-id)
      (Thread/sleep 2000)

      ;; Start a session
      (println "Starting session...")
      (rama/foreign-append! (:session depots)
        (mod/->SessionStart user-id session-id jti expires-at))
      (Thread/sleep 2000)

      ;; Verify session exists
      (let [stored (rama/foreign-select-one (keypath session-id) (:sessions pstates)
                     {:pkey session-id})]
        (println "session before end:" (pr-str stored))
        (is (some? stored) "session should be in $$sessions"))

      ;; End the session
      (println "Ending session...")
      (let [end-result (rama/foreign-append! (:session-end depots) (mod/->SessionEnd session-id))]
        (println "session-end append result:" (pr-str end-result)))
      (Thread/sleep 2000)

      ;; Verify session is removed
      (let [stored (rama/foreign-select-one (keypath session-id) (:sessions pstates)
                     {:pkey session-id})]
        (println "session after end:" (pr-str stored))
        (is (nil? stored) "session should be removed from $$sessions"))

      (println "=== end session-end-cleanup-test ==="))))

(deftest ^:integration username-change-test
  (testing "Username change updates $$username->id and $$profiles"
    (let [registration-depot (rama/foreign-depot *ipc* *module-name* "*registration-depot")
          username-change-depot (rama/foreign-depot *ipc* *module-name* "*username-change-depot")
          uid->id (rama/foreign-pstate *ipc* *module-name* "$$username->id")
          profiles (rama/foreign-pstate *ipc* *module-name* "$$profiles")
          suffix (str (java.util.UUID/randomUUID))
          old-uname (str "oldname-" suffix)
          new-uname (str "newname-" suffix)
          email (str old-uname "@test.com")
          reg-result (rama/foreign-append! registration-depot
                       (mod/->Registration (str (java.util.UUID/randomUUID))
                         old-uname "hash" email ["ROLE_USER"]))]
      (println "\n=== username-change-test ===")
      (let [user-id (get reg-result "auth")]
        (is (some? user-id) "registration should return a user-id")
        (Thread/sleep 2000)

        ;; Verify old username
        (let [stored-id (rama/foreign-select-one (keypath old-uname) uid->id)]
          (is (= user-id stored-id) "old username should be in $$username->id"))

        ;; Change username
        (println "Changing username from" old-uname "to" new-uname)
        (let [change-result (rama/foreign-append! username-change-depot
                              (mod/->UsernameChange user-id new-uname))]
          (println "username-change result:" (pr-str change-result))
          (is (= :ok (get change-result "auth")) "username change should succeed"))
        (Thread/sleep 2000)

        ;; Verify old username removed
        (let [stored-old (rama/foreign-select-one (keypath old-uname) uid->id)]
          (println "$$username->id[" old-uname "]:" (pr-str stored-old))
          (is (nil? stored-old) "old username should be removed from $$username->id"))

        ;; Verify new username mapping
        (let [stored-new (rama/foreign-select-one (keypath new-uname) uid->id)]
          (println "$$username->id[" new-uname "]:" (pr-str stored-new))
          (is (= user-id stored-new) "new username should be in $$username->id"))

        ;; Verify profile updated
        (let [profile (rama/foreign-select-one (keypath user-id :username) profiles)]
          (println "$$profiles[" user-id " :username]:" (pr-str profile))
          (is (= new-uname profile) "profile username should be updated"))

        ;; Same-username change should succeed (no-op)
        (let [same-result (rama/foreign-append! username-change-depot
                            (mod/->UsernameChange user-id new-uname))]
          (println "same-username change result:" (pr-str same-result))
          (is (= :ok (get same-result "auth")) "same-username change should succeed"))

        ;; Conflict: another user tries to claim new-uname
        (let [other-suffix (str (java.util.UUID/randomUUID))
              other-email (str "other-" other-suffix "@test.com")
              other-result (rama/foreign-append! registration-depot
                             (mod/->Registration (str (java.util.UUID/randomUUID))
                               (str "other-" other-suffix) "hash" other-email ["ROLE_USER"]))
              other-id (get other-result "auth")]
          (is (some? other-id) "other user should register")
          (Thread/sleep 2000)
          (let [conflict-result (rama/foreign-append! username-change-depot
                                  (mod/->UsernameChange other-id new-uname))]
            (println "conflict username change result:" (pr-str conflict-result))
            (is (= :taken (get conflict-result "auth")) "duplicate username should return :taken")))
        (println "=== end username-change-test ===")))))

(deftest ^:integration org-topology-test
  (testing "Full org lifecycle: create, invite, join, switch, update, remove"
    (let [create-depot (rama/foreign-depot *ipc* *module-name* "*org-create-depot")
          uuid (str (java.util.UUID/randomUUID))
          org-name (str "org-e2e-" (java.util.UUID/randomUUID))
          owner-uid 10001
          created-at (System/currentTimeMillis)]

      ;; Create org
      (println "\n=== org-topology-test ===")
      (let [result (rama/foreign-append! create-depot (mod/->OrgCreate uuid org-name owner-uid created-at))]
        (println "create result:" (pr-str result))
        (is (some? result) "create should return non-nil")
        (is (get result "auth") "create should return an org-id"))
      (Thread/sleep 2000)

      ;; Verify PStates
      (let [orgs (rama/foreign-pstate *ipc* *module-name* "$$orgs")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            user-orgs (rama/foreign-pstate *ipc* *module-name* "$$user-orgs")
            org-users (rama/foreign-pstate *ipc* *module-name* "$$org-users")
            active-org (rama/foreign-pstate *ipc* *module-name* "$$user-active-org")
            org (rama/foreign-select-one (keypath org-name) org-name->id)]
        (println "org-name->id:" org)
        (is (some? org) "org should exist in org-name->id")
        (let [org-data (rama/foreign-select-one (keypath org) orgs)]
          (println "orgs[" org "]:" (pr-str org-data))
          (is (= org-name (:name org-data)) "name should match")
          (is (= owner-uid (:owner-user-id org-data)) "owner should match"))
        (let [uo (rama/foreign-select [(keypath owner-uid) ALL] user-orgs)]
          (println "user-orgs:" uo)
          (is (some #(= org %) uo) "user should be in user-orgs"))
        (let [ou (rama/foreign-select [(keypath org) ALL] org-users)]
          (println "org-users:" ou)
          (is (some #(= owner-uid %) ou) "owner should be in org-users"))
        (let [active (rama/foreign-select-one (keypath owner-uid) active-org)]
          (println "active-org:" active)
          (is (= org active) "active org should be set")))

      ;; Invite
      (let [invite-depot (rama/foreign-depot *ipc* *module-name* "*org-invite-depot")
            inv-id (str (java.util.UUID/randomUUID))
            org-id (rama/foreign-select-one (keypath org-name) (rama/foreign-pstate *ipc* *module-name* "$$org-name->id"))
            inv-result (rama/foreign-append! invite-depot (mod/->OrgInvite inv-id org-id "joiner@test.com" "MEMBER" owner-uid (System/currentTimeMillis) (+ (System/currentTimeMillis) 604800000)))]
        (println "invite result:" (pr-str inv-result))
        (is (some? inv-result) "invite should return non-nil"))
      (Thread/sleep 2000)

      ;; Verify invitation
      (let [invitations (rama/foreign-pstate *ipc* *module-name* "$$invitations")
            email-inv (rama/foreign-pstate *ipc* *module-name* "$$email->invitations")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)
            email-inv-ids (rama/foreign-select [(keypath "joiner@test.com") ALL] email-inv)]
        (println "email->invitations:" email-inv-ids)
        (is (seq email-inv-ids) "invitation should be indexed by email")
        (let [inv (rama/foreign-select-one (keypath (first email-inv-ids)) invitations)]
          (println "invitation:" (pr-str inv))
          (is (= "PENDING" (:status inv)) "status should be PENDING")
          (is (= "MEMBER" (:role inv)) "role should be MEMBER")))

      ;; Join
      (let [email-inv (rama/foreign-pstate *ipc* *module-name* "$$email->invitations")
            inv-id (first (rama/foreign-select [(keypath "joiner@test.com") ALL] email-inv))
            join-depot (rama/foreign-depot *ipc* *module-name* "*org-join-depot")
            joiner-uid 10002
            join-result (rama/foreign-append! join-depot (mod/->OrgJoin joiner-uid inv-id (System/currentTimeMillis)))]
        (println "join result:" (pr-str join-result))
        (is (some? join-result) "join should return non-nil"))
      (Thread/sleep 2000)

      ;; Verify membership
      (let [org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)
            memberships (rama/foreign-pstate *ipc* *module-name* "$$memberships")
            org-members (rama/foreign-pstate *ipc* *module-name* "$$org-members")
            m (rama/foreign-select-one (keypath 10002 org-id) memberships)]
        (println "membership:" (pr-str m))
        (is (some? m) "membership should exist")
        (is (= "MEMBER" (:role m)) "role should be MEMBER")
        (is (= "ACTIVE" (:status m)) "status should be ACTIVE")
        (let [om (rama/foreign-select-one (keypath org-id 10002) org-members)]
          (println "org-members:" (pr-str om))
          (is (some? om) "org-members should have entry")))

      ;; Switch
      (let [switch-depot (rama/foreign-depot *ipc* *module-name* "*org-switch-depot")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)]
        (rama/foreign-append! switch-depot (mod/->OrgSwitch 10002 org-id)))
      (Thread/sleep 2000)
      (let [active-org (rama/foreign-pstate *ipc* *module-name* "$$user-active-org")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)
            active (rama/foreign-select-one (keypath 10002) active-org)]
        (println "active-org after switch:" active)
        (is (= org-id active) "active org should be updated"))

      ;; Update member role
      (let [update-depot (rama/foreign-depot *ipc* *module-name* "*org-member-update-depot")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)]
        (rama/foreign-append! update-depot (mod/->OrgMemberUpdate org-id 10002 "ADMIN")))
      (Thread/sleep 2000)
      (let [memberships (rama/foreign-pstate *ipc* *module-name* "$$memberships")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)
            m (rama/foreign-select-one (keypath 10002 org-id) memberships)]
        (println "membership after role update:" (pr-str m))
        (is (= "ADMIN" (:role m)) "role should be ADMIN"))

      ;; Remove member
      (let [remove-depot (rama/foreign-depot *ipc* *module-name* "*org-member-remove-depot")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)]
        (rama/foreign-append! remove-depot (mod/->OrgMemberRemove org-id 10002)))
      (Thread/sleep 2000)
      (let [memberships (rama/foreign-pstate *ipc* *module-name* "$$memberships")
            org-name->id (rama/foreign-pstate *ipc* *module-name* "$$org-name->id")
            org-id (rama/foreign-select-one (keypath org-name) org-name->id)
            m (rama/foreign-select-one (keypath 10002 org-id) memberships)]
        (println "membership after remove:" (pr-str m))
        (is (nil? m) "member should be removed"))

      (println "=== end org-topology-test ==="))))

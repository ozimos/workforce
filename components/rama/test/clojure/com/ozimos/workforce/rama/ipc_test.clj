(ns com.ozimos.workforce.rama.ipc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.workforce.auth-api.test-system :as ts]
   [com.ozimos.workforce.rama.module :as mod]
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
          uname (str "id-format-" (random-uuid))
          email (str uname "@test.com")
          result (rama/foreign-append! depot
                   (mod/->Registration (str (random-uuid))
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
          suffix (str (random-uuid))
          uname (str "roundtrip-" suffix)
          email (str uname "@test.com")
          pwd-hash "bcrypt-hash"
          roles []
          reg (mod/->Registration (str (random-uuid))
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
    (let [suffix (str (random-uuid))
          depots {:registration (rama/foreign-depot *ipc* *module-name* "*registration-depot")
                  :session (rama/foreign-depot *ipc* *module-name* "*session-depot")
                  :session-end (rama/foreign-depot *ipc* *module-name* "*session-end-depot")}
          pstates {:sessions (rama/foreign-pstate *ipc* *module-name* "$$sessions")}
          uname (str "session-cleanup-" suffix)
          email (str uname "@test.com")
          user-id (get (rama/foreign-append! (:registration depots)
                         (mod/->Registration (str (random-uuid))
                           uname "hash" email ["ROLE_USER"])) "auth")
          session-id (str (random-uuid))
          jti (str (random-uuid))
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
          suffix (str (random-uuid))
          old-uname (str "oldname-" suffix)
          new-uname (str "newname-" suffix)
          email (str old-uname "@test.com")
          reg-result (rama/foreign-append! registration-depot
                       (mod/->Registration (str (random-uuid))
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
        (let [other-suffix (str (random-uuid))
              other-email (str "other-" other-suffix "@test.com")
              other-result (rama/foreign-append! registration-depot
                             (mod/->Registration (str (random-uuid))
                               (str "other-" other-suffix) "hash" other-email ["ROLE_USER"]))
              other-id (get other-result "auth")]
          (is (some? other-id) "other user should register")
          (Thread/sleep 2000)
          (let [conflict-result (rama/foreign-append! username-change-depot
                                  (mod/->UsernameChange other-id new-uname))]
            (println "conflict username change result:" (pr-str conflict-result))
            (is (= :taken (get conflict-result "auth")) "duplicate username should return :taken")))
        (println "=== end username-change-test ===")))))



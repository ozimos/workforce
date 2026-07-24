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
  (if-let [sys (ts/user-store)]
    (let [rama-config (:rama sys)]
      (binding [*ipc* (:cluster-manager rama-config)
                *module-name* (rama/get-module-name mod/AuthModule)]
        (tests)))
    (let [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc mod/AuthModule {:tasks 4 :threads 2})
      (binding [*ipc* ipc
                *module-name* (rama/get-module-name mod/AuthModule)]
        (try
          (tests)
          (finally (.close ipc)))))))

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

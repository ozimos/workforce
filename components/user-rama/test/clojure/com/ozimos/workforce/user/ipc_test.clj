(ns com.ozimos.workforce.user.ipc-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.workforce.auth-api.test-system :as ts]
   [com.ozimos.workforce.rama.module :as mod]
   [com.ozimos.workforce.user.core :as user]
   [com.rpl.rama :as rama]
   [com.rpl.rama.test :as rtest]))

(def ^:dynamic *system* nil)

(defn short-suffix []
  (-> (random-uuid) str (.replace "-" "") (.substring 0 12)))

(defn ipc-fixture
  [tests]
  (ts/with-sys
    (if sys
      (binding [*system* (ts/user-store sys)]
        (tests))
      (let [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc mod/AuthModule {:tasks 4 :threads 2})
        (let [deps {:rama {:cluster-manager ipc :mode :ipc}}]
          (binding [*system* deps]
            (try
              (tests)
              (finally (.close ipc)))))))))

(use-fixtures :once ipc-fixture)

(deftest ^:integration register-and-find-test
  (testing "register! followed by find-by-username returns the user"
    (let [suffix (short-suffix)
          uname (str "rf-" suffix)
          email (str uname "@test.com")
          pwd "P@ssword123"
          [ok user] (user/register! *system* {:username uname
                                              :email email
                                              :password pwd})
          found (user/find-by-username *system* uname)]

      (println "\n=== register-and-find-test ===")
      (println "register! ok:" ok)
      (println "register! user:" (pr-str user))
      (println "register! :id type:" (when user (type (:id user))))
      (println "register! :id value:" (when user (pr-str (:id user))))
      (is ok "register! should succeed")
      (is (some? (:id user)) "user should have an :id")
      (is (instance? Long (:id user)) ":id from register! should be a Long")
      (println "find-by-username result:" (pr-str found))
      (println "find-by-username :id type:" (when found (type (:id found))))
      (println "find-by-username :id value:" (when found (pr-str (:id found))))
      (is (some? found) "find-by-username should return the user")
      (is (= uname (:username found)) "username should match")
      (is (= email (:email found)) "email should match")
      (is (= (:id user) (:id found)) ":id should match between register! and find-by-username")
      (println "=== end register-and-find-test ==="))))

(deftest ^:integration register-and-find-by-id-test
  (testing "register! followed by find-by-id returns the user"
    (let [suffix (short-suffix)
          uname (str "ri-" suffix)
          email (str uname "@test.com")
          pwd "P@ssword123"
          [ok user] (user/register! *system* {:username uname
                                              :email email
                                              :password pwd})
          user-id (:id user)]

      (println "\n=== register-and-find-by-id-test ===")
      (println "register! ok:" ok)
      (println "register! :id type:" (when user (type (:id user))))
      (println "register! :id value:" (when user (pr-str (:id user))))
      (is ok "register! should succeed")
      (is (some? (:id user)) "user should have an :id")

      (let [found (user/find-by-id *system* user-id)]
        (println "find-by-id with :id" (pr-str user-id) "result:" (pr-str found))
        (is (some? found) "find-by-id should return the user")
        (is (= user-id (:id found)) "id should match"))
      (println "=== end register-and-find-by-id-test ==="))))

(deftest ^:integration auto-derive-username-test
  (testing "register! without :username derives one from email"
    (let [suffix (short-suffix)
          email (str "autoderive-" suffix "@test.com")
          pwd "P@ssword123"
          [ok user] (user/register! *system* {:email email :password pwd})]
      (println "\n=== auto-derive-username-test ===")
      (println "register! ok:" ok)
      (println "register! user:" (pr-str user))
      (is ok "register! should succeed")
      (is (some? (:username user)) "username should be auto-derived")
      (is (string/starts-with? (:username user) "autoderive"))
      (is (= email (:email user)) "email should match")
      (let [found (user/find-by-username *system* (:username user))]
        (is (some? found) "find-by-username should work with derived username")
        (is (= (:username user) (:username found)) "usernames should match"))
      (println "=== end auto-derive-username-test ==="))))

(deftest ^:integration find-nonexistent-user-test
  (testing "find-by-username, find-by-email, and find-by-identifier return nil for unregistered users without throwing exceptions"
    (let [suffix (short-suffix)
          nonexistent-email (str "nonexistent-" suffix "@example.com")
          nonexistent-username (str "nonexistent-" suffix)]
      (println "\n=== find-nonexistent-user-test ===")
      (is (nil? (user/find-by-username *system* nonexistent-username)) "find-by-username on non-existent user should return nil")
      (is (nil? (user/find-by-email *system* nonexistent-email)) "find-by-email on non-existent user should return nil")
      (is (nil? (user/find-by-identifier *system* nonexistent-email)) "find-by-identifier on non-existent email should return nil")
      (is (nil? (user/find-by-identifier *system* nonexistent-username)) "find-by-identifier on non-existent username should return nil")
      (println "=== end find-nonexistent-user-test ==="))))

(deftest ^:integration duplicate-registration-test
  (testing "register! with duplicate username returns error"
    (let [suffix (short-suffix)
          uname (str "dp-" suffix)
          email (str uname "@test.com")
          pwd "P@ssword123"
          input {:username uname :email email :password pwd}]

      (println "\n=== duplicate-registration-test ===")
      (let [[ok1 user1] (user/register! *system* input)]
        (println "1st register! ok:" ok1 "id:" (pr-str (:id user1)))
        (is ok1 "first registration should succeed"))

      (let [[ok2 result] (user/register! *system* input)]
        (println "2nd register! ok:" ok2 "result:" (pr-str result))
        (is (not ok2) "duplicate registration should fail")
        (is (some? (:errors result)) "failure should include :errors"))
      (println "=== end duplicate-registration-test ==="))))

(deftest ^:integration duplicate-email-test
  (testing "register! with duplicate email but different username should fail"
    (let [suffix (short-suffix)
          common-email (str "shared-" suffix "@test.com")
          pwd "P@ssword123"]

      (println "\n=== duplicate-email-test ===")

      (let [[ok1 user1] (user/register! *system*
                          {:username (str "first-" suffix)
                           :email common-email
                           :password pwd})]
        (println "1st register! ok:" ok1 "id:" (pr-str (:id user1)))
        (is ok1 "first registration with email should succeed"))

      (let [[ok2 result] (user/register! *system*
                           {:username (str "second-" suffix)
                            :email common-email
                            :password pwd})]
        (println "2nd register! (different username, same email) ok:" ok2)
        (is (not ok2) "registration with duplicate email should fail")
        (is (some? (:errors result)) "failure should include :errors"))

      (println "=== end duplicate-email-test ==="))))

(deftest ^:integration registration-idempotency-test
  (testing "Appending the same Registration record twice returns the same user-id"
    (let [suffix (short-suffix)
          uname (str "idem-" suffix)
          email (str uname "@test.com")
          pwd-hash "bcrypt-hash"
          known-uuid (str (random-uuid))
          cmgr (-> *system* :rama :cluster-manager)
          mod-name (rama/get-module-name mod/AuthModule)
          reg-depot (rama/foreign-depot cmgr mod-name "*registration-depot")
          reg (mod/->Registration known-uuid uname pwd-hash email ["ROLE_USER"])
          result1 (rama/foreign-append! reg-depot reg)
          result2 (rama/foreign-append! reg-depot reg)]
      (println "\n=== registration-idempotency-test ===")
      (println "result1:" (pr-str result1))
      (println "result2:" (pr-str result2))
      (is (some? result1) "first append should return a non-nil result")
      (is (some? result2) "second append (retry) should return a non-nil result")
      (let [id1 (get result1 "auth")
            id2 (get result2 "auth")]
        (println "id1:" id1 "id2:" id2)
        (is (some? id1) "first append should return a user-id")
        (is (some? id2) "retry should return a user-id")
        (is (= id1 id2) "both appends should return the same user-id"))
      (println "=== end registration-idempotency-test ==="))))

(deftest ^:integration update-username-test
  (testing "update-username! changes the username in the system"
    (let [suffix (short-suffix)
          email (str "up-" suffix "@test.com")
          pwd "P@ssword123"
          [ok1 user] (user/register! *system* {:email email :password pwd})]
      (is ok1 "user should register")
      (let [old-uname (:username user)
            new-uname (str "updated-" suffix)]

        (println "\n=== update-username-test ===")

        ;; Successful change
        (let [[ok2 result] (user/update-username! *system* (:id user) new-uname)]
          (println "update ok:" ok2 "result:" (pr-str result))
          (is ok2 "update-username! should succeed")
          (is (= new-uname result) "username should be updated"))

        ;; Verify via find-by-username
        (let [found (user/find-by-username *system* new-uname)]
          (is (some? found) "should find user by new username")
          (is (= (:id user) (:id found)) "user-id should match")
          (is (= new-uname (:username found)) "username should match"))

        ;; Verify old username no longer resolvable
        (let [found-old (user/find-by-username *system* old-uname)]
          (is (nil? found-old) "old username should not be resolvable"))

        (println "=== end update-username-test ==="))))

  (testing "update-username! validates username format"
    (let [suffix (short-suffix)
          [ok1 user] (user/register! *system*
                       {:email (str "val-" suffix "@test.com")
                        :password "P@ssword123"})]
      (is ok1 "user should register")

      ;; Too short
      (let [[ok2 result] (user/update-username! *system* (:id user) "ab")]
        (println "too-short ok:" ok2 "result:" (pr-str result))
        (is (not ok2) "username that is too short should fail")
        (is (some? (:errors result)) "failure should include :errors"))

      ;; Invalid characters
      (let [[ok3 result] (user/update-username! *system* (:id user) "bad user!")]
        (println "invalid-chars ok:" ok3 "result:" (pr-str result))
        (is (not ok3) "username with invalid characters should fail")
        (is (some? (:errors result)) "failure should include :errors"))

      (println "=== end update-username-format-test ===")))

  (testing "update-username! rejects taken username"
    (let [suffix (short-suffix)
          [ok1 user1] (user/register! *system*
                        {:email (str "conflict1-" suffix "@test.com")
                         :password "P@ssword123"})
          [ok2 user2] (user/register! *system*
                        {:email (str "conflict2-" suffix "@test.com")
                         :password "P@ssword123"})]
      (is (and ok1 ok2) "both users should register")
      (let [shared-name (str "shared-name-" suffix)
            [_] (user/update-username! *system* (:id user1) shared-name)
            [ok3 result] (user/update-username! *system* (:id user2) shared-name)]
        (println "conflict ok:" ok3 "result:" (pr-str result))
        (is (not ok3) "claiming taken username should fail")
        (is (some? (:errors result)) "failure should include :errors"))

      (println "=== end update-username-conflict-test ==="))))

(deftest ^:integration mfa-backup-codes-ipc-test
  (testing "mfa backup codes setup, consumption, regeneration, and count"
    (let [user-id 99001
          hashes #{"hash1" "hash2" "hash3"}
          _ (user/setup-mfa! *system* user-id "encrypted-secret" hashes)]
      (is (= 3 (user/count-mfa-backup-codes *system* user-id)))
      (is (user/consume-mfa-backup-code! *system* user-id "hash1"))
      (is (= 2 (user/count-mfa-backup-codes *system* user-id)))
      (is (user/regenerate-mfa-backup-codes! *system* user-id #{"new1" "new2" "new3" "new4"}))
      (is (= 4 (user/count-mfa-backup-codes *system* user-id))))))

(ns com.ozimos.auth.oauth.ipc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.auth.rama.module :as mod]
   [com.ozimos.auth.user.interface :as user]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [keypath]]
   [com.rpl.rama.test :as rtest]))

(def ^:dynamic *use-fixture* true)
(def ^:dynamic *deps* nil)

(defn rama-fixture [f]
  (if *use-fixture*
    (let [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc mod/AuthModule {:tasks 4 :threads 2})
      (let [deps {:cluster-manager ipc :rama {:cluster-manager ipc :mode :ipc}}]
        (binding [*deps* deps]
          (try
            (f)
            (finally (.close ipc))))))
    (f)))

(use-fixtures :each rama-fixture)

(defn- short-id []
  (subs (str (random-uuid)) 0 8))

(deftest oauth-link-test
  (testing "link provider account to user and retrieve user"
    (let [deps *deps*
          suffix (short-id)
          email (str "oauth-" suffix "@example.com")
          username (str "user_" suffix)
          sub (str "sub-" suffix)
          ;; 1. Register a local user
          [ok? user] (user/register! deps {:email email
                                          :password "Secret123!"
                                          :username username})
          user-id (:id user)]
      (is (true? ok?))
      (is (some? user-id))

      ;; 2. Initially lookup by OAuth link should return nil
      (is (nil? (user/find-by-oauth-link deps "google" sub)))

      ;; 3. Link OAuth account
      (user/link-oauth-account! deps "google" sub user-id)

      ;; 4. Lookup by OAuth link should now return the user profile
      (let [linked-user (user/find-by-oauth-link deps "google" sub)]
        (is (some? linked-user))
        (is (= user-id (:id linked-user)))
        (is (= email (:email linked-user)))
        (is (= username (:username linked-user)))))))

(deftest oauth-mfa-step-up-test
  (testing "OAuth callback challenges MFA if user has MFA enabled"
    (let [deps *deps*
          suffix (short-id)
          email (str "oauth-mfa-" suffix "@example.com")
          username (str "user_mfa_" suffix)
          sub (str "sub-mfa-" suffix)
          [ok? user] (user/register! deps {:email email
                                          :password "Secret123!"
                                          :username username})
          user-id (:id user)]
      (is (true? ok?))
      ;; Enable MFA
      (user/setup-mfa! deps user-id "encrypted-secret" ["hash1" "hash2"])
      (user/verify-mfa-setup! deps user-id)
      (is (true? (user/mfa-enabled? deps user-id)))

      ;; OAuth login with same email -> triggers MFA challenge
      (let [[cb-ok? cb-res] (com.ozimos.auth.oauth.interface/handle-oauth-callback
                             deps "google" {:provider-user-id sub :email email :name "MFA User"})]
        (is (true? cb-ok?))
        (is (true? (:mfa-required cb-res)))
        (is (some? (:mfa-token cb-res)))))))

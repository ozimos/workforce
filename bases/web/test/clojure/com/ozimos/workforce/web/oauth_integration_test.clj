(ns com.ozimos.workforce.web.oauth-integration-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.oauth.interface :as oauth]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.web.test-system :as ts]))

(def ^:dynamic *sys* nil)

(defn system-fixture [tests]
  (let [sys (ts/get-sys)]
    (binding [*sys* sys]
      (tests))))

(use-fixtures :once system-fixture)

(defn- short-id []
  (subs (str (random-uuid)) 0 8))

(deftest oauth-integration-test
  (testing "OAuth callback provisions new user, links account, and returns JWT tokens"
    (let [sys *sys*
          provider "google"
          sub (str "g-user-" (short-id))
          email (str "oauth-" (short-id) "@example.com")
          oauth-info {:provider-user-id sub
                      :email email
                      :name "OAuth User"}
          [ok? result] (oauth/handle-oauth-callback sys provider oauth-info)]
      (is (true? ok?))
      (is (string? (:access-token result)))
      (is (string? (:refresh-token result)))
      (is (= email (get-in result [:user :email])))

      ;; Confirm account was linked in Rama
      (let [linked (user/find-by-oauth-link sys provider sub)]
        (is (some? linked))
        (is (= email (:email linked))))))

  (testing "OAuth callback links provider to existing local user with matching email"
    (let [sys *sys*
          email (str "existing-" (short-id) "@example.com")
          username (str "user_" (short-id))
          ;; 1. Register local user first
          [reg-ok? reg-user] (user/register! sys {:email email
                                                  :password "Password123!"
                                                  :username username})
          _ (is (true? reg-ok?))
          local-id (:id reg-user)

          ;; 2. OAuth login with same email
          provider "github"
          sub (str "gh-user-" (short-id))
          oauth-info {:provider-user-id sub
                      :email email
                      :name "Github User"}
          [ok? result] (oauth/handle-oauth-callback sys provider oauth-info)]
      (is (true? ok?))
      (is (= local-id (get-in result [:user :id])))

      ;; 3. Verify link
      (let [linked (user/find-by-oauth-link sys provider sub)]
        (is (some? linked))
        (is (= local-id (:id linked)))))))

(deftest oauth-mfa-integration-test
  (testing "OAuth callback challenges MFA on existing account with MFA enabled"
    (let [sys *sys*
          email (str "oauth-mfa-" (short-id) "@example.com")
          username (str "user_mfa_" (short-id))
          [reg-ok? reg-user] (user/register! sys {:email email
                                                  :password "Password123!"
                                                  :username username})
          _ (is (true? reg-ok?))
          local-id (:id reg-user)]
      ;; Enable MFA
      (user/setup-mfa! sys local-id "enc-secret" ["hash1"])
      (user/verify-mfa-setup! sys local-id)
      (is (true? (user/mfa-enabled? sys local-id)))

      ;; OAuth login with matching email
      (let [provider "google"
            sub (str "g-mfa-" (short-id))
            oauth-info {:provider-user-id sub :email email :name "MFA Google"}
            [ok? result] (oauth/handle-oauth-callback sys provider oauth-info)]
        (is (true? ok?))
        (is (true? (:mfa-required result)))
        (is (string? (:mfa-token result)))))))

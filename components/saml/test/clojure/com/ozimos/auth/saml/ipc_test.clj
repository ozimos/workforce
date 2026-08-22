(ns com.ozimos.auth.saml.ipc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.auth.rama.module :as mod]
   [com.ozimos.auth.saml.interface :as saml]
   [com.ozimos.auth.user.interface :as user]
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

(deftest saml-assertion-test
  (testing "handle SAML assertion for new user provisions user and links account with auth-method=saml"
    (let [deps *deps*
          suffix (short-id)
          name-id (str "saml-idp-" suffix)
          email (str "saml-" suffix "@enterprise.com")
          saml-info {:name-id name-id
                     :email email
                     :name "Jane Enterprise"}
          [ok? result] (saml/handle-saml-assertion deps saml-info)]
      (is (true? ok?))
      (is (= "saml" (:auth-method result)))
      (is (string? (:access-token result)))
      (is (= email (get-in result [:user :email])))

      ;; Verify link in Rama
      (let [linked (user/find-by-oauth-link deps "saml" name-id)]
        (is (some? linked))
        (is (= email (:email linked))))))

  (testing "handle SAML assertion for existing email links SAML provider to existing local user"
    (let [deps *deps*
          suffix (short-id)
          email (str "existing-" suffix "@company.com")
          username (str "user_" suffix)
          name-id (str "saml-idp-" suffix)
          ;; 1. Register local user first
          [reg-ok? reg-user] (user/register! deps {:email email
                                                   :password "Password123!"
                                                   :username username})
          _ (is (true? reg-ok?))
          local-id (:id reg-user)

          ;; 2. SAML login
          saml-info {:name-id name-id
                     :email email
                     :name "Existing Enterprise User"}
          [ok? result] (saml/handle-saml-assertion deps saml-info)]
      (is (true? ok?))
      (is (= local-id (get-in result [:user :id])))

      ;; 3. Verify link
      (let [linked (user/find-by-oauth-link deps "saml" name-id)]
        (is (some? linked))
        (is (= local-id (:id linked))))))

  (testing "SAML assertion challenges MFA if user has MFA enabled"
    (let [deps *deps*
          suffix (short-id)
          email (str "saml-mfa-" suffix "@enterprise.com")
          username (str "user_mfa_" suffix)
          name-id (str "saml-mfa-idp-" suffix)
          [ok? user] (user/register! deps {:email email
                                          :password "Secret123!"
                                          :username username})
          user-id (:id user)]
      (is (true? ok?))
      ;; Enable MFA
      (user/setup-mfa! deps user-id "encrypted-secret" ["hash1" "hash2"])
      (user/verify-mfa-setup! deps user-id)
      (is (true? (user/mfa-enabled? deps user-id)))

      ;; SAML login with same email -> triggers MFA challenge
      (let [[cb-ok? cb-res] (saml/handle-saml-assertion
                             deps {:name-id name-id :email email :name "MFA SAML User"})]
        (is (true? cb-ok?))
        (is (true? (:mfa-required cb-res)))
        (is (some? (:mfa-token cb-res)))))))

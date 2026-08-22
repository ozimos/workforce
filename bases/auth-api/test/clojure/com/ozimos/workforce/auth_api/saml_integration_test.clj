(ns com.ozimos.workforce.auth-api.saml-integration-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.workforce.auth-api.test-system :as ts]
   [com.ozimos.workforce.saml.interface :as saml]
   [com.ozimos.workforce.user.interface :as user]))

(def ^:dynamic *sys* nil)

(defn system-fixture [tests]
  (ts/with-sys
    (binding [*sys* sys]
      (tests))))

(use-fixtures :once system-fixture)

(defn- short-id []
  (subs (str (random-uuid)) 0 8))

(deftest saml-integration-test
  (testing "SAML ACS endpoint processes assertion and returns tokens with auth-method claim"
    (let [sys *sys*
          sub (str "saml-user-" (short-id))
          email (str "acme-" (short-id) "@acme.org")
          saml-info {:name-id sub
                     :email email
                     :name "Acme SAML User"}
          [ok? result] (saml/handle-saml-assertion sys saml-info)]
      (is (true? ok?))
      (is (= "saml" (:auth-method result)))
      (is (string? (:access-token result)))
      (is (= email (get-in result [:user :email])))

      ;; Verify account link in Rama
      (let [linked (user/find-by-oauth-link sys "saml" sub)]
        (is (some? linked))
        (is (= email (:email linked)))))))

(deftest saml-mfa-integration-test
  (testing "SAML assertion challenges MFA on existing account with MFA enabled"
    (let [sys *sys*
          email (str "saml-mfa-" (short-id) "@company.com")
          username (str "user_saml_mfa_" (short-id))
          [reg-ok? reg-user] (user/register! sys {:email email
                                                  :password "Password123!"
                                                  :username username})
          _ (is (true? reg-ok?))
          local-id (:id reg-user)]
      ;; Enable MFA
      (user/setup-mfa! sys local-id "enc-secret" ["hash1"])
      (user/verify-mfa-setup! sys local-id)
      (is (true? (user/mfa-enabled? sys local-id)))

      ;; SAML assertion with matching email
      (let [sub (str "saml-mfa-" (short-id))
          saml-info {:name-id sub :email email :name "MFA SAML User"}
          [ok? result] (saml/handle-saml-assertion sys saml-info)]
        (is (true? ok?))
        (is (true? (:mfa-required result)))
        (is (string? (:mfa-token result)))))))

(ns com.ozimos.workforce.webauthn.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.webauthn.interface :as webauthn]))

(deftest relying-party-creation-test
  (testing "RelyingParty construction and options generation"
    (let [rp (webauthn/make-relying-party {:rp-id "localhost"
                                           :rp-name "BestAuth"
                                           :origins "http://localhost:8080"})
          creation-opts (webauthn/start-registration-options rp 1001 "testuser" "testuser@example.com")
          creation-json (webauthn/creation-options-to-json creation-opts)]
      (is (some? rp))
      (is (some? creation-opts))
      (is (string? creation-json))
      (is (.contains creation-json "localhost"))
      (is (.contains creation-json "BestAuth")))))

(deftest assertion-options-test
  (testing "Assertion options generation"
    (let [rp (webauthn/make-relying-party {:rp-id "localhost"
                                           :rp-name "BestAuth"
                                           :origins "http://localhost:8080"})
          assertion-req (webauthn/start-assertion-options rp)
          assertion-json (webauthn/assertion-request-to-json assertion-req)]
      (is (some? assertion-req))
      (is (string? assertion-json))
      (is (.contains assertion-json "challenge")))))

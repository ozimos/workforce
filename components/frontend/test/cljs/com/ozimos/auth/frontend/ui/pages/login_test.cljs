(ns com.ozimos.auth.frontend.ui.pages.login-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.auth.frontend.ui.pages.login :as login]))

(deftest login-initial-state-test
  (testing "Login initial state contains MFA step-up fields"
    (let [init (comp/get-initial-state login/Login)]
      (is (= false (:mfa-required init)))
      (is (= "" (:mfa-code init)))
      (is (nil? (:mfa-token init))))))

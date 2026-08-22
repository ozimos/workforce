(ns com.ozimos.workforce.frontend.ui.pages.profile-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.profile :as profile]))

(deftest profile-initial-state-test
  (testing "Profile initial state contains security fields"
    (let [init (comp/get-initial-state profile/Profile)]
      (is (= "" (:new-username init)))
      (is (= :disabled (:mfa-stage init)))
      (is (= "" (:totp-code init)))
      (is (vector? (:mfa-backup-codes init))))))

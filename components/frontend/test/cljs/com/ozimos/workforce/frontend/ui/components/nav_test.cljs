(ns com.ozimos.workforce.frontend.ui.components.nav-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.ozimos.workforce.frontend.ui.components.nav :as nav]))

(deftest uncompleted-steps-count-test
  (testing "calculates uncompleted security steps correctly"
    (testing "when mfa is disabled (false or nil), returns 1 step remaining"
      (is (= 1 (nav/uncompleted-steps-count {:user/mfa-enabled? false})))
      (is (= 1 (nav/uncompleted-steps-count {}))))

    (testing "when mfa is enabled, returns 0 steps remaining"
      (is (= 0 (nav/uncompleted-steps-count {:user/mfa-enabled? true}))))))

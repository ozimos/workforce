(ns com.ozimos.workforce.frontend.routing-test
  (:require
   [com.ozimos.workforce.frontend.routing :as routing]
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])))

(deftest public-path-test
  (testing "public paths are correctly identified"
    (is (true? (routing/public-path? "/")))
    (is (true? (routing/public-path? "/login")))
    (is (true? (routing/public-path? "/login-replicant")))
    (is (true? (routing/public-path? "/register")))
    (is (true? (routing/public-path? "/register-replicant")))
    (is (true? (routing/public-path? "/forgot-password")))
    (is (true? (routing/public-path? "/forgot-password-replicant")))
    (is (true? (routing/public-path? "/reset-password")))
    (is (true? (routing/public-path? "/reset-password?token=abc")))
    (is (true? (routing/public-path? "/reset-password-replicant")))
    (is (true? (routing/public-path? "/verify")))
    (is (true? (routing/public-path? "/verify?token=xyz"))))

  (testing "protected paths are correctly identified"
    (is (false? (routing/public-path? "/org-chart")))
    (is (false? (routing/public-path? "/org-chart-replicant")))
    (is (false? (routing/public-path? "/org-chart-2")))
    (is (false? (routing/public-path? "/dept-dashboard")))
    (is (false? (routing/public-path? "/dept-dashboard?unit-id=123")))
    (is (false? (routing/public-path? "/headcount")))
    (is (false? (routing/public-path? "/policies")))
    (is (false? (routing/public-path? "/profile")))
    (is (false? (routing/public-path? "/create-org")))
    (is (false? (routing/public-path? "/join-org")))
    (is (false? (routing/public-path? "/org-dashboard")))
    (is (false? (routing/public-path? "/home-replicant"))))

  (testing "protected-path? is complement"
    (is (true? (routing/protected-path? "/org-chart")))
    (is (false? (routing/protected-path? "/login")))
    (is (true? (routing/protected-path? "/dept-dashboard?unit-id=123")))
    (is (false? (routing/protected-path? "/verify?token=abc"))))

  (testing "nil and empty handling"
    (is (true? (routing/public-path? nil)))
    (is (true? (routing/public-path? "/")))))

(deftest path->route-test
  (testing "maps known paths to route keywords"
    (is (= :route/login (routing/path->route "/login")))
    (is (= :route/login-replicant (routing/path->route "/login-replicant")))
    (is (= :route/register (routing/path->route "/register")))
    (is (= :route/org-chart (routing/path->route "/org-chart")))
    (is (= :route/org-chart-replicant (routing/path->route "/org-chart-replicant")))
    (is (= :route/headcount (routing/path->route "/headcount")))
    (is (= :route/profile (routing/path->route "/profile")))
    (is (= :route/home (routing/path->route "/")))
    (is (= :route/home-replicant (routing/path->route "/home-replicant"))))

  (testing "handles query strings by stripping search"
    (is (= :route/dept-dashboard (routing/path->route "/dept-dashboard?unit-id=123")))
    (is (= :route/login (routing/path->route "/login?foo=bar"))))

  (testing "unknown path falls back to :route/login"
    (is (= :route/login (routing/path->route "/unknown")))
    (is (= :route/login (routing/path->route "/org-chart/unknown"))))

  (testing "prefix matches for reset-password and verify"
    (is (= :route/reset-password (routing/path->route "/reset-password/abc")))
    (is (= :route/verify (routing/path->route "/verify/xyz")))))

(deftest verify-and-redirect-test
  (testing "verify-path? identifies /verify"
    (is (true? (routing/verify-path? "/verify")))
    (is (true? (routing/verify-path? "/verify?token=abc")))
    (is (true? (routing/verify-path? "/verify/xyz")))
    (is (false? (routing/verify-path? "/login")))
    (is (false? (routing/verify-path? "/org-chart"))))

  (testing "should-redirect-public? when verified false (!verified stays on verify)"
    (is (true? (routing/should-redirect-public? "/login" false)))
    (is (true? (routing/should-redirect-public? "/register" false)))
    (is (false? (routing/should-redirect-public? "/verify" false)))
    (is (false? (routing/should-redirect-public? "/verify?token=abc" false)))
    (is (false? (routing/should-redirect-public? "/org-chart" false)))
    (is (true? (routing/should-redirect-public? "/" false))))

  (testing "should-redirect-public? when verified true (all public -> /)"
    (is (true? (routing/should-redirect-public? "/login" true)))
    (is (true? (routing/should-redirect-public? "/verify" true)))
    (is (true? (routing/should-redirect-public? "/verify?token=abc" true)))
    (is (true? (routing/should-redirect-public? "/" true)))
    (is (false? (routing/should-redirect-public? "/org-chart" true)))))

(ns com.ozimos.workforce.frontend.web.test-runner
  (:require
   [cljs.test :refer [run-tests]]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.home-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.join-org-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.login-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.verify-replicant-test]))

(defn main []
  (run-tests
    'com.ozimos.workforce.frontend.ui.components.nav-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.headcount-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.profile-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.home-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.join-org-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.create-org-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.login-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.register-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.verify-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.reset-password-replicant-test))

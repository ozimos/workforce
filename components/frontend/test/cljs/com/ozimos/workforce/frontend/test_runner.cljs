(ns com.ozimos.workforce.frontend.test-runner
  (:require
   [cljs.test :refer [run-tests]]
   [com.ozimos.workforce.frontend.replicant-bridge-test]
   [com.ozimos.workforce.frontend.transit-test]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant-test]
   [com.ozimos.workforce.frontend.ui.components.nav-test]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-test]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.headcount-test]
   [com.ozimos.workforce.frontend.ui.pages.login-test]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-test]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant-test]
   [com.ozimos.workforce.frontend.ui.pages.profile-test]))

(defn main []
  (run-tests
    'com.ozimos.workforce.frontend.transit-test
    'com.ozimos.workforce.frontend.replicant-bridge-test
    'com.ozimos.workforce.frontend.ui.pages.headcount-replicant-test
    'com.ozimos.workforce.frontend.ui.components.nav-replicant-test
    'com.ozimos.workforce.frontend.ui.components.nav-test
    'com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.dept-dashboard-test
    'com.ozimos.workforce.frontend.ui.pages.headcount-test
    'com.ozimos.workforce.frontend.ui.pages.login-test
    'com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.org-chart-test
    'com.ozimos.workforce.frontend.ui.pages.profile-replicant-test
    'com.ozimos.workforce.frontend.ui.pages.profile-test))

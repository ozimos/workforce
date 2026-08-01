(ns com.ozimos.auth.frontend.test-runner
  (:require
   [cljs.test :refer [run-tests]]
   [com.ozimos.auth.frontend.ui.components.nav-test]
   [com.ozimos.auth.frontend.ui.pages.login-test]
   [com.ozimos.auth.frontend.ui.pages.profile-test]))

(defn main []
  (run-tests
   'com.ozimos.auth.frontend.ui.components.nav-test
   'com.ozimos.auth.frontend.ui.pages.login-test
   'com.ozimos.auth.frontend.ui.pages.profile-test))

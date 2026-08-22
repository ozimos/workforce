(ns dev-test
  (:require [clojure.test :as test]))

(def test-ns->file
  '{com.ozimos.auth.pathom.core-test "components/pathom/test/clojure/com/ozimos/auth/pathom/core_test.clj"
    com.ozimos.auth.mfa.core-test "components/mfa/test/clojure/com/ozimos/auth/mfa/core_test.clj"
    com.ozimos.auth.oauth.ipc-test "components/oauth/test/clojure/com/ozimos/auth/oauth/ipc_test.clj"
    com.ozimos.auth.saml.ipc-test "components/saml/test/clojure/com/ozimos/auth/saml/ipc_test.clj"
    com.ozimos.auth.rama.ipc-test "components/rama/test/clojure/com/ozimos/auth/rama/ipc_test.clj"
    com.ozimos.auth.user.ipc-test "components/user-rama/test/clojure/com/ozimos/auth/user/ipc_test.clj"
    com.ozimos.auth.webauthn.core-test "components/webauthn/test/clojure/com/ozimos/auth/webauthn/core_test.clj"
    com.ozimos.auth.auth-api.oauth-integration-test "bases/auth-api/test/clojure/com/ozimos/auth/auth_api/oauth_integration_test.clj"
    com.ozimos.auth.auth-api.saml-integration-test "bases/auth-api/test/clojure/com/ozimos/auth/auth_api/saml_integration_test.clj"
    com.ozimos.auth.auth-api.integration-test "bases/auth-api/test/clojure/com/ozimos/auth/auth_api/integration_test.clj"})

(defn load-test-ns!
  [ns-sym]
  (try
    (require ns-sym :reload)
    (catch Exception _
      (if-let [f (get test-ns->file ns-sym)]
        (when (.exists (java.io.File. f))
          (load-file f))
        (throw (ex-info (str "Could not load test namespace " ns-sym) {:ns ns-sym}))))))

(defn test-ns
  [ns-sym]
  (load-test-ns! ns-sym)
  (test/test-ns ns-sym))

(defn test-all
  []
  (let [ns-syms '[com.ozimos.auth.pathom.core-test
                  com.ozimos.auth.mfa.core-test
                  com.ozimos.auth.oauth.ipc-test
                  com.ozimos.auth.saml.ipc-test
                  com.ozimos.auth.rama.ipc-test
                  com.ozimos.auth.user.ipc-test
                  com.ozimos.auth.webauthn.core-test
                  com.ozimos.auth.auth-api.oauth-integration-test
                  com.ozimos.auth.auth-api.saml-integration-test
                  com.ozimos.auth.auth-api.integration-test]
        results (reduce (fn [acc sym]
                          (try
                            (let [res (test-ns sym)]
                              (merge-with + acc res))
                            (catch Exception e
                              (println "Error testing" sym (.getMessage e))
                              (update acc :error (fnil inc 0)))))
                        {:test 0 :pass 0 :fail 0 :error 0}
                        ns-syms)]
    (println "\n=== Full Test Suite Summary ===")
    (println (str "Tests: " (:test results) ", Passes: " (:pass results) ", Failures: " (:fail results) ", Errors: " (:error results)))
    results))

(defn test-clean
  []
  (require '[com.ozimos.auth.auth-api.test-system :as ts])
  (let [start-fn (resolve 'com.ozimos.auth.auth-api.test-system/start-clean-test-sys!)
        stop-fn  (resolve 'com.ozimos.auth.auth-api.test-system/stop-clean-test-sys!)]
    (println "\n=== Starting Clean In-Memory Test System ===")
    (when start-fn (start-fn))
    (try
      (test-all)
      (finally
        (println "=== Tearing Down Clean In-Memory Test System ===")
        (when stop-fn (stop-fn))))))

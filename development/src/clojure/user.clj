(ns user
  (:require
   [integrant.repl :refer [clear go halt reset reset-all set-prep!]]
   [integrant.repl.state :refer [config system]])
  (:gen-class))

(require 'com.ozimos.omni-auth.config.core
         'com.ozimos.omni-auth.rama.core
         'com.ozimos.omni-auth.rama.module
         'com.ozimos.omni-auth.user.core
         'com.ozimos.omni-auth.session.core
         'com.ozimos.omni-auth.revocation.core
         'com.ozimos.omni-auth.token.core
         'com.ozimos.omni-auth.security.core
         'com.ozimos.omni-auth.pathom.core
         'com.ozimos.workforce.org.extension
         'com.ozimos.workforce.org.core
         'com.ozimos.workforce.org.resolvers
         'com.ozimos.workforce.auth-api.system)

(set-prep! (fn [] ((requiring-resolve 'com.ozimos.workforce.auth-api.system/load-config) :dev)))

(let [java-dirs ["../../omni-auth/main/components/security/src/java"]]
  (try
    (require 'virgil)
    (require 'clj-reload.core)
    ((resolve 'clj-reload.core/init)
     {:dirs     ["development/src/clojure"
                 "bases/auth-api/src/clojure"
                 "bases/auth-api/test/clojure"
                 "components/org-rama/src/clojure"
                 "components/org-rama/test/clojure"
                 "../../omni-auth/main/components/schema/src/clojure"
                 "../../omni-auth/main/components/config/src/clojure"
                 "../../omni-auth/main/components/rama/src/clojure"
                 "../../omni-auth/main/components/rama/test/clojure"
                 "../../omni-auth/main/components/user-rama/src/clojure"
                 "../../omni-auth/main/components/session-rama/src/clojure"
                 "../../omni-auth/main/components/revocation-rama/src/clojure"
                 "../../omni-auth/main/components/token/src/clojure"
                 "../../omni-auth/main/components/security/src/clojure"
                 "../../omni-auth/main/components/pathom/src/clojure"
                 "../../omni-auth/main/components/pathom/test/clojure"
                 "../../omni-auth/main/components/mfa/src/clojure"
                 "../../omni-auth/main/components/mfa/test/clojure"
                 "../../omni-auth/main/components/webauthn/src/clojure"
                 "../../omni-auth/main/components/webauthn/test/clojure"
                 "../../omni-auth/main/components/oauth/src/clojure"
                 "../../omni-auth/main/components/oauth/test/clojure"
                 "../../omni-auth/main/components/saml/src/clojure"
                 "../../omni-auth/main/components/saml/test/clojure"]
      :no-reload '#{user}
      :output   :verbose})
    ((resolve 'virgil/watch-and-recompile)
     java-dirs
     :options ["-proc:none" "--release" "21"]
     :post-hook #((resolve 'clj-reload.core/reload) {:only :loaded}))
    (println "Java hot-reloading active via Virgil + clj-reload!" java-dirs)
    (catch Exception e
      (println "Virgil/clj-reload unavailable:" (-> e .getClass .getName) (.getMessage e)))))

(defn ensure-test-paths!
  "Ensures all component and base test directories are available on the classloader."
  []
  (let [loaders (distinct (remove nil? [(clojure.lang.RT/baseLoader)
                                        (.getContextClassLoader (Thread/currentThread))]))
        paths ["components/org-rama/test/clojure"
               "bases/auth-api/test/clojure"
               "../../omni-auth/main/components/config/test/clojure"
               "../../omni-auth/main/components/rama/test/clojure"
               "../../omni-auth/main/components/schema/test/clojure"
               "../../omni-auth/main/components/user-rama/test/clojure"
               "../../omni-auth/main/components/session-rama/test/clojure"
               "../../omni-auth/main/components/revocation-rama/test/clojure"
               "../../omni-auth/main/components/token/test/clojure"
               "../../omni-auth/main/components/security/test/clojure"
               "../../omni-auth/main/components/pathom/test/clojure"
               "../../omni-auth/main/components/mfa/test/clojure"
               "../../omni-auth/main/components/webauthn/test/clojure"
               "../../omni-auth/main/components/oauth/test/clojure"
               "../../omni-auth/main/components/saml/test/clojure"]]
    (doseq [p paths
            :let [f (java.io.File. p)]
            :when (.exists f)
            :let [url (.. f toURI toURL)]]
      (doseq [loader loaders]
        (when (instance? clojure.lang.DynamicClassLoader loader)
          (try (.addURL ^clojure.lang.DynamicClassLoader loader url) (catch Exception _ nil))))
      (try (clojure.lang.RT/addURL url) (catch Exception _ nil)))))

(def test-ns->file
  '{com.ozimos.workforce.org.resolvers-test "components/org-rama/test/clojure/com/ozimos/workforce/org/resolvers_test.clj"
    com.ozimos.workforce.org.ipc-test "components/org-rama/test/clojure/com/ozimos/workforce/org/ipc_test.clj"
    com.ozimos.workforce.auth-api.oauth-integration-test "bases/auth-api/test/clojure/com/ozimos/workforce/auth_api/oauth_integration_test.clj"
    com.ozimos.workforce.auth-api.saml-integration-test "bases/auth-api/test/clojure/com/ozimos/workforce/auth_api/saml_integration_test.clj"
    com.ozimos.workforce.auth-api.integration-test "bases/auth-api/test/clojure/com/ozimos/workforce/auth_api/integration_test.clj"})

(defn load-test-ns!
  "Load or reload a test namespace from classpath or fallback file."
  [ns-sym]
  (try
    (require ns-sym :reload)
    (catch Exception _
      (if-let [f (get test-ns->file ns-sym)]
        (when (.exists (java.io.File. f))
          (load-file f))
        (throw (ex-info (str "Could not load test namespace " ns-sym) {:ns ns-sym}))))))

(defn test-all
  "Reload and run all backend unit, IPC, and integration test suites directly in REPL."
  []
  (ensure-test-paths!)
  (require 'clojure.test)
  (let [ns-syms '[com.ozimos.workforce.org.resolvers-test
                  com.ozimos.workforce.org.ipc-test
                  com.ozimos.workforce.auth-api.oauth-integration-test
                  com.ozimos.workforce.auth-api.saml-integration-test
                  com.ozimos.workforce.auth-api.integration-test]
        results (reduce (fn [acc sym]
                          (try
                            (load-test-ns! sym)
                            (let [res ((resolve 'clojure.test/test-ns) sym)]
                              (merge-with + acc res))
                            (catch Exception e
                              (println "Error testing" sym (.getMessage e))
                              (update acc :error (fnil inc 0)))))
                        {:test 0 :pass 0 :fail 0 :error 0}
                        ns-syms)]
    (println "\n=== Workforce Full Test Suite Summary ===")
    (println (str "Tests: " (:test results) ", Passes: " (:pass results) ", Failures: " (:fail results) ", Errors: " (:error results)))
    results))

(defn test-ns
  "Reload and run tests for a single test namespace."
  [ns-sym]
  (ensure-test-paths!)
  (require 'clojure.test)
  (load-test-ns! ns-sym)
  ((resolve 'clojure.test/test-ns) ns-sym))

(defn test-clean
  "Runs all tests against a freshly created, isolated in-memory Rama IPC cluster,
   then halts and cleans it up, without affecting the running dev system."
  []
  (ensure-test-paths!)
  (require 'clojure.test '[com.ozimos.workforce.auth-api.test-system :as ts])
  (let [start-fn (resolve 'com.ozimos.workforce.auth-api.test-system/start-clean-test-sys!)
        stop-fn  (resolve 'com.ozimos.workforce.auth-api.test-system/stop-clean-test-sys!)]
    (println "\n=== Starting Clean In-Memory Test System ===")
    (when start-fn (start-fn))
    (try
      (test-all)
      (finally
        (println "=== Tearing Down Clean In-Memory Test System ===")
        (when stop-fn (stop-fn))))))

(comment
  (go)         ;; Start the entire system
  (halt)       ;; Shut everything down
  (reset)      ;; Reload changed namespaces + rebuild system in < 1s
  (reset-all)  ;; Reload ALL namespaces + rebuild system
  (clear)      ;; Discard prepped config + system
  (test-all)   ;; Run all backend tests against running dev state (< 0.5s)
  (test-clean) ;; Run all backend tests against a fresh ephemeral test system (~1s)
  (test-ns 'com.ozimos.workforce.auth-api.oauth-integration-test))

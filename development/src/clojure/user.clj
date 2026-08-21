(ns user
  (:require
   [integrant.repl :refer [clear go halt reset reset-all set-prep!]]
   [integrant.repl.state :refer [config system]])
  (:gen-class))

(require 'com.ozimos.auth.config.core
         'com.ozimos.auth.rama.core
         'com.ozimos.auth.rama.module
         'com.ozimos.auth.user.core
         'com.ozimos.auth.session.core
         'com.ozimos.auth.revocation.core
         'com.ozimos.auth.token.core
         'com.ozimos.auth.security.core
         'com.ozimos.auth.pathom.core
         'com.ozimos.auth.auth-api.system)

(set-prep! (fn [] ((requiring-resolve 'com.ozimos.auth.auth-api.system/load-config) :dev)))

(let [java-dirs ["components/security/src/java"]]
  (try
    (require 'virgil)
    (require 'clj-reload.core)
    ((resolve 'clj-reload.core/init)
     {:dirs     ["development/src/clojure"
                 "bases/auth-api/src/clojure"
                 "bases/auth-api/test/clojure"
                 "components/schema/src/clojure"
                 "components/config/src/clojure"
                 "components/rama/src/clojure"
                 "components/user-rama/src/clojure"
                 "components/session-rama/src/clojure"
                 "components/revocation-rama/src/clojure"
                 "components/token/src/clojure"
                 "components/security/src/clojure"
                 "components/pathom/src/clojure"
                 "components/mfa/src/clojure"
                 "components/mfa/test/clojure"
                 "components/webauthn/src/clojure"
                 "components/webauthn/test/clojure"
                 "components/oauth/src/clojure"
                 "components/oauth/test/clojure"
                 "components/saml/src/clojure"
                 "components/saml/test/clojure"]
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
        paths ["components/config/test/clojure"
               "components/rama/test/clojure"
               "components/schema/test/clojure"
               "components/user-rama/test/clojure"
               "components/session-rama/test/clojure"
               "components/revocation-rama/test/clojure"
               "components/token/test/clojure"
               "components/security/test/clojure"
               "components/pathom/test/clojure"
               "components/mfa/test/clojure"
               "components/webauthn/test/clojure"
               "components/oauth/test/clojure"
               "components/saml/test/clojure"
               "bases/auth-api/test/clojure"]]
    (doseq [p paths
            :let [f (java.io.File. p)]
            :when (.exists f)
            :let [url (.. f toURI toURL)]]
      (doseq [loader loaders]
        (when (instance? clojure.lang.DynamicClassLoader loader)
          (try (.addURL ^clojure.lang.DynamicClassLoader loader url) (catch Exception _ nil))))
      (try (clojure.lang.RT/addURL url) (catch Exception _ nil)))))

(defn test-all
  "Reload and run all backend unit, IPC, and integration test suites directly in REPL."
  []
  (ensure-test-paths!)
  (require 'clojure.test)
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
                            (require sym :reload)
                            (let [res ((resolve 'clojure.test/test-ns) sym)]
                              (merge-with + acc res))
                            (catch Exception e
                              (println "Error testing" sym (.getMessage e))
                              (update acc :error (fnil inc 0)))))
                        {:test 0 :pass 0 :fail 0 :error 0}
                        ns-syms)]
    (println "\n=== Full Test Suite Summary ===")
    (println (str "Tests: " (:test results) ", Passes: " (:pass results) ", Failures: " (:fail results) ", Errors: " (:error results)))
    results))

(defn test-ns
  "Reload and run tests for a single test namespace."
  [ns-sym]
  (ensure-test-paths!)
  (require 'clojure.test)
  (require ns-sym :reload)
  ((resolve 'clojure.test/test-ns) ns-sym))

(defn test-clean
  "Runs all tests against a freshly created, isolated in-memory Rama IPC cluster,
   then halts and cleans it up, without affecting the running dev system."
  []
  (ensure-test-paths!)
  (require 'clojure.test '[com.ozimos.auth.auth-api.test-system :as ts])
  (let [start-fn (resolve 'com.ozimos.auth.auth-api.test-system/start-clean-test-sys!)
        stop-fn  (resolve 'com.ozimos.auth.auth-api.test-system/stop-clean-test-sys!)]
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
  (test-ns 'com.ozimos.auth.auth-api.oauth-integration-test))

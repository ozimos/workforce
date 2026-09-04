(ns user
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [integrant.repl :refer [clear go halt resume set-prep! suspend]])
  (:gen-class))

(defn reset
  "Reload changed namespaces via clj-reload and resume the Integrant system.

   Matches the original `integrant.repl/reset` semantics (suspend → reload → resume)
   but uses `clj-reload` instead of `clojure.tools.namespace.repl`. Jetty is kept
   warm via `ig/suspend!` (see `com.ozimos.workforce.web.system`), Rama PStates fall
   through to `halt`/`init` since they have no suspend."
  []
  (suspend)
  ((requiring-resolve 'clj-reload.core/reload))
  (resume))

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
         'com.ozimos.workforce.web.system)

(set-prep! (fn [] ((requiring-resolve 'com.ozimos.workforce.web.system/load-config) :dev)))

(try
  (require 'clj-reload.core)
  ((resolve 'clj-reload.core/init)
   {:dirs     ["development/src/clojure"
               "bases/web/src/clojure"
               "bases/web/test/clojure"
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
  (println "Hot-reloading active via clj-reload!")
  (catch Exception e
    (println "clj-reload unavailable:" (-> e .getClass .getName) (.getMessage e))))

(defn ensure-test-paths!
  "Ensures all component and base test directories are available on the classloader."
  []
  (let [loaders (distinct (remove nil? [(clojure.lang.RT/baseLoader)
                                        (.getContextClassLoader (Thread/currentThread))]))
        paths ["components/org-rama/test/clojure"
               "bases/web/test/clojure"
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
          (try (.addURL ^clojure.lang.DynamicClassLoader loader url) (catch Throwable _ nil))))
      (try (clojure.lang.RT/addURL url) (catch Throwable _ nil)))))

(def test-ns->file
  '{com.ozimos.workforce.org.resolvers-test "components/org-rama/test/clojure/com/ozimos/workforce/org/resolvers_test.clj"
    com.ozimos.workforce.org.ipc-test "components/org-rama/test/clojure/com/ozimos/workforce/org/ipc_test.clj"
    com.ozimos.workforce.org.seed-test "components/org-rama/test/clojure/com/ozimos/workforce/org/seed_test.clj"
    com.ozimos.workforce.web.oauth-integration-test "bases/web/test/clojure/com/ozimos/workforce/web/oauth_integration_test.clj"
    com.ozimos.workforce.web.saml-integration-test "bases/web/test/clojure/com/ozimos/workforce/web/saml_integration_test.clj"
    com.ozimos.workforce.web.integration-test "bases/web/test/clojure/com/ozimos/workforce/web/integration_test.clj"})

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
                  com.ozimos.workforce.web.oauth-integration-test
                  com.ozimos.workforce.web.saml-integration-test
                  com.ozimos.workforce.web.integration-test]
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

(defn test-all-cli
  "Runs test-all for CLI/CI, halts the test system, and exits the JVM with 0 (success) or 1 (failure)."
  []
  (let [results (test-all)
        stop-fn (resolve 'com.ozimos.workforce.web.test-system/stop-system)]
    (when stop-fn (try (stop-fn) (catch Throwable _ nil)))
    (let [failed? (or (pos? (:fail results 0)) (pos? (:error results 0)))]
      (System/exit (if failed? 1 0)))))

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
  (require 'clojure.test '[com.ozimos.workforce.web.test-system :as ts])
  (let [start-fn (resolve 'com.ozimos.workforce.web.test-system/start-clean-test-sys!)
        stop-fn  (resolve 'com.ozimos.workforce.web.test-system/stop-clean-test-sys!)]
    (println "\n=== Starting Clean In-Memory Test System ===")
    (when start-fn (start-fn))
    (try
      (test-all)
      (finally
        (println "=== Tearing Down Clean In-Memory Test System ===")
        (when stop-fn (stop-fn))))))

(defn seed!
  "Loads the seed dataset into the currently running dev system."
  ([]
   (require 'com.ozimos.workforce.org.interface 'integrant.repl.state)
   (let [sys @(resolve 'integrant.repl.state/system)
         cmgr (:cluster-manager (-> sys :rama/cluster))
         deps (assoc sys :cluster-manager cmgr)]
     (if (and sys cmgr)
       ((resolve 'com.ozimos.workforce.org.interface/ensure-seeded!) deps)
       (println "Dev system not started yet. Run (go) first or use (seed!).")))))

(defn kill-port!
  "Kill any OS process listening on the given TCP port."
  [port]
  (when (and (integer? port) (pos? port))
    (try
      (let [res (clojure.java.shell/sh "lsof" "-ti" (str "tcp:" port) "-sTCP:LISTEN")]
        (when (zero? (:exit res))
          (let [pids (->> (clojure.string/split-lines (:out res))
                          (map clojure.string/trim)
                          (filter seq))]
            (when (seq pids)
              (doseq [pid pids]
                (try (clojure.java.shell/sh "kill" "-15" pid) (catch Throwable _ nil)))
              (Thread/sleep 200)
              (let [res2 (clojure.java.shell/sh "lsof" "-ti" (str "tcp:" port) "-sTCP:LISTEN")
                    rem (->> (clojure.string/split-lines (:out res2))
                             (map clojure.string/trim)
                             (filter seq))]
                (doseq [pid rem]
                  (try (clojure.java.shell/sh "kill" "-9" pid) (catch Throwable _ nil))))))))
      (catch Throwable _ nil))))

(defn cleanup-dev-ports!
  "Clean up and terminate all port-consuming processes spawned for this REPL session."
  []
  (let [local (try (read-string (slurp "deps.local.edn")) (catch Throwable _ nil))
        nrepl-p (try (parse-long (clojure.string/trim (slurp ".nrepl-port"))) (catch Throwable _ nil))
        shadow-nrepl-p (try (parse-long (clojure.string/trim (slurp ".shadow-cljs/nrepl.port"))) (catch Throwable _ nil))
        shadow-http-p (try (parse-long (clojure.string/trim (slurp ".shadow-cljs/http.port"))) (catch Throwable _ nil))
        ssr-p (or (when-let [p (System/getenv "SSR_PORT")] (try (parse-long p) (catch Throwable _ nil)))
                  (get-in local [:ssr-server :port])
                  (get local :ssr/port)
                  (get local :ssr-port)
                  3000)
        jetty-p (or (when-let [p (System/getenv "JETTY_DEV_PORT")] (try (parse-long p) (catch Throwable _ nil)))
                    (get-in local [:jetty/port :dev]))
        mailpit-smtp (or (when-let [p (System/getenv "MAILPIT_SMTP_PORT")] (try (parse-long p) (catch Throwable _ nil)))
                         (get-in local [:mailpit :smtp-port])
                         1025)
        mailpit-http (or (when-let [p (System/getenv "MAILPIT_HTTP_PORT")] (try (parse-long p) (catch Throwable _ nil)))
                         (get-in local [:mailpit :http-port])
                         8025)
        all-ports (filter #(and (integer? %) (pos? %))
                          [nrepl-p shadow-nrepl-p shadow-http-p (when shadow-http-p (inc shadow-http-p)) ssr-p jetty-p mailpit-smtp mailpit-http])]
    ;; 1. Halt Integrant system if running
    (try
      (when-let [sys (some-> (resolve 'integrant.repl.state/system) deref)]
        (require 'integrant.core)
        ((resolve 'integrant.core/halt!) sys))
      (catch Throwable _ nil))
    ;; 2. Stop Shadow-CLJS server if running
    (try
      (when-let [stop-fn (resolve 'shadow.cljs.devtools.server/stop!)]
        (stop-fn))
      (catch Throwable _ nil))
    ;; 3. Terminate any remaining processes on our ports (e.g. Node SSR server, orphaned watchers)
    (doseq [p (distinct all-ports)]
      (kill-port! p))
    ;; 4. Remove stale port files from disk
    (doseq [path [".nrepl-port" ".shadow-cljs/nrepl.port" ".shadow-cljs/http.port"]]
      (try (let [f (java.io.File. path)] (when (.exists f) (.delete f))) (catch Throwable _ nil)))))

;; Register JVM shutdown hook to execute port & process cleanup if REPL JVM terminates or crashes
(defonce ^:private __register-repl-shutdown-hook!
  (do
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread.
        (fn []
          (println "\n[REPL JVM] Shutdown hook triggered: cleaning up system, shadow-cljs, SSR server, and dev ports...")
          (cleanup-dev-ports!))))
    true))

(defn gen-seed!
  "Generates and serializes a fresh binary Nippy seed archive to .seed/workforce-seed-data.nippy."
  ([]
   (require 'com.ozimos.workforce.org.interface)
   (let [res ((resolve 'com.ozimos.workforce.org.interface/write-seed-nippy!))]
     (println "Generated binary seed archive:" (:path res) (str "(" (:size res) " bytes, " (:organizations res) " orgs)"))
     res)))

(defn start-and-seed!
  "Starts the dev system and automatically ensures seed data is loaded."
  []
  (let [res (go)]
    (try
      (seed!)
      (catch Exception e
        (println "Seed auto-load notice:" (.getMessage e))))
    res))

(comment
  (start-and-seed!) ;; Start system and load seed data
  (go)              ;; Start the system
  (seed!)           ;; Load / ensure seed data is populated in Rama
  (gen-seed!)       ;; Regenerate .seed/workforce-seed-data.nippy
  (halt)            ;; Shut everything down
  (reset)           ;; Reload changed namespaces + rebuild system in < 1s
  (clear)           ;; Discard prepped config + system
  (test-all)        ;; Run all backend tests against running dev state (< 0.5s)
  (test-clean)      ;; Run all backend tests against a fresh ephemeral test system (~1s)
  (test-ns 'com.ozimos.workforce.org.seed-test)

  ;; ===========================================================================
  ;; Mailpit / Notification REPL Previews (View in Mailpit UI at http://localhost:8025)
  ;; ===========================================================================
  (require '[com.ozimos.omni-auth.notification.interface :as notify])

  ;; 1. Send Account Verification Email to Mailpit (HTTP Send API)
  (notify/send-verification-email!
   {:notification/service {:provider :http :http {:preset :mailpit :from "auth@bestauth.local"}}}
   {:to "alice@acme.com"
    :user-name "Alice Smith"
    :verify-url "http://localhost:8100/verify?token=sample-verification-jwt-token"})

  ;; 2. Send Password Reset Email to Mailpit (HTTP Send API)
  (notify/send-password-reset-email!
   {:notification/service {:provider :http :http {:preset :mailpit :from "auth@bestauth.local"}}}
   {:to "bob@acme.com"
    :user-name "Bob Jones"
    :reset-url "http://localhost:8100/reset-password?token=sample-password-reset-jwt-token"})

  ;; 3. Send Organization Invitation Email to Mailpit (HTTP Send API)
  (notify/send-org-invitation-email!
   {:notification/service {:provider :http :http {:preset :mailpit :from "auth@bestauth.local"}}}
   {:to "carol@acme.com"
    :inviter-name "Alice Smith"
    :org-name "Acme Engineering"
    :role "Lead Architect"
    :join-url "http://localhost:8100/join-org?token=sample-org-invitation-token"})

  ;; 4. Send via running Integrant system
  (when-let [sys (some-> (resolve 'integrant.repl.state/system) deref)]
    (notify/send-verification-email!
     sys
     {:to "dev@example.com"
      :user-name "Dev User"
      :verify-url "http://localhost:8100/verify?token=dev-jwt-token"})))

(ns com.ozimos.workforce.auth-api.test-system
  (:require
   [com.ozimos.workforce.auth-api.system]
   [com.ozimos.workforce.config.interface :as config]
   [com.ozimos.workforce.rama.core :as rama-core]
   [integrant.core :as ig]
   [integrant.repl.state :as irs]))

(defonce sys-atom (atom nil))
(defonce test-sys-atom (atom nil))

(defn get-sys []
  (or @test-sys-atom irs/system @sys-atom))

(defn start-clean-test-sys!
  "Creates a fresh, isolated in-memory IPC cluster, merges it with irs/system,
   and stores the merged system map in test-sys-atom."
  []
  (rama-core/clear-ipc!)
  (let [clean-ipc-sys (ig/init {:rama/cluster {:mode :ipc :tasks 2 :threads 1}})
        merged-sys (merge irs/system clean-ipc-sys)]
    (reset! test-sys-atom merged-sys)
    merged-sys))

(defn stop-clean-test-sys!
  "Halts the clean test IPC cluster and clears test-sys-atom."
  []
  (when-let [test-sys @test-sys-atom]
    (when-let [clean-ipc (select-keys test-sys [:rama/cluster])]
      (try
        (ig/halt! clean-ipc)
        (catch Exception _ nil)))
    (rama-core/clear-ipc!)
    (reset! test-sys-atom nil)))

(defmacro with-sys [& body]
  `(let [~'sys (get-sys)]
     ~@body))

(defn start-system
  "Initialize a fresh integrant system with the :test profile."
  []
  (let [cfg (config/load-config :test)
        sys (ig/init cfg)]
    (reset! sys-atom sys)
    sys))

(defn stop-system
  "Halt the current integrant system and clear the stored reference."
  []
  (when-let [sys (or irs/system @sys-atom)]
    (ig/halt! sys)
    (reset! sys-atom nil)))

(defn setup
  [_project-name]
  (start-system))

(defn teardown
  [_project-name]
  (stop-system))

(defn get-port
  "Get the port the Jetty server is listening on."
  [sys]
  (let [server (-> sys :adapter/jetty :server)]
    (-> server .getConnectors first .getLocalPort)))

(defn get-base-url
  [sys]
  (str "http://localhost:" (get-port sys)))

(defn user-store
  "Get store deps containing :rama from the running system."
  [sys]
  {:rama (:rama/cluster sys)})

(defn rama-cluster
  [sys]
  (:cluster-manager (-> sys :rama/cluster)))

(defn module-name
  []
  (rama-core/module-name))

(defn pstate
  [sys name]
  (let [cmgr (rama-cluster sys)]
    (rama-core/pstate cmgr (module-name) name)))

(defn depot
  [sys name]
  (let [cmgr (rama-cluster sys)]
    (rama-core/depot cmgr (module-name) name)))

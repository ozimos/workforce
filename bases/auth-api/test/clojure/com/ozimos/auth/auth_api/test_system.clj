(ns com.ozimos.auth.auth-api.test-system
  (:require
   [com.ozimos.auth.auth-api.system]
   [com.ozimos.auth.config.interface :as config]
   [com.ozimos.auth.rama.core :as rama-core]
   [integrant.core :as ig]
   [integrant.repl.state :as irs]))

(defonce sys-atom (atom nil))

(defn get-sys []
  (or irs/system @sys-atom))

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

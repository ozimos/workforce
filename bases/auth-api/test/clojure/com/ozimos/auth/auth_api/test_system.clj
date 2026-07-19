(ns com.ozimos.auth.auth-api.test-system
  (:require
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [keypath]]
   [integrant.core :as ig]
   [com.ozimos.auth.config.interface :as config]
   [com.ozimos.auth.rama.core]
   [com.ozimos.auth.rama.module]
   [com.ozimos.auth.user.core]
   [com.ozimos.auth.session.core]
   [com.ozimos.auth.revocation.core]
   [com.ozimos.auth.token.core]
   [com.ozimos.auth.security.core]
   [com.ozimos.auth.auth-api.system]))

(defonce system
  (delay
    (let [cfg (-> (config/load-config :dev)
                  (assoc-in [:adapter/jetty :port] 0))
          sys (ig/init cfg)
          server (-> sys :adapter/jetty :server)
          port (-> server .getConnectors first .getLocalPort)]
      (println "Test system started on port" port)
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. (fn [] (ig/halt! sys) (println "Test system halted"))))
      {:system sys :port port})))

(defn get-port
  []
  (:port @system))

(defn get-base-url
  []
  (str "http://localhost:" (get-port)))

(defn user-store
  []
  (-> @system :system :user/store))

(defn rama-cluster
  []
  (:cluster-manager (-> @system :system :rama/cluster)))

(defn module-name
  []
  (com.ozimos.auth.rama.core/module-name))

(defn pstate
  [name]
  (let [cmgr (rama-cluster)]
    (com.ozimos.auth.rama.core/pstate cmgr (module-name) name)))

(defn depot
  [name]
  (let [cmgr (rama-cluster)]
    (com.ozimos.auth.rama.core/depot cmgr (module-name) name)))


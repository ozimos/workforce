(ns com.ozimos.auth.auth_api.system
  (:require [com.ozimos.auth.config.interface :as config]
            [com.ozimos.auth.security.interface :as security]
            [com.ozimos.auth.auth_api.routes :as routes]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty])
  (:import [org.eclipse.jetty.server Server]
           [org.eclipse.jetty.ee9.servlet ServletContextHandler]
           [org.eclipse.jetty.util.thread ExecutorThreadPool QueuedThreadPool]
           [org.springframework.web.filter DelegatingFilterProxy]
           [org.springframework.web.context WebApplicationContext]
           [java.util.concurrent Executors]))

(defn load-config
  "Load Integrant config from resources. `profile` is :dev or :prod."
  [profile]
  (config/load-config profile))

(defn- virtual-thread-pool
  ^ExecutorThreadPool []
  (ExecutorThreadPool.
   (Executors/newVirtualThreadPerTaskExecutor)
   (doto (QueuedThreadPool. 8 2 60000) (.setDaemon true))
   (Executors/newVirtualThreadPerTaskExecutor)))

(defn- spring-security-configurator
  "Returns a :configurator fn that injects DelegatingFilterProxy into the
   ServletContextHandler that ring-jetty-adapter creates internally.
   Called after handler is set, before server starts."
  [spring-app-context]
  (fn [^Server server]
    (let [handler (.getHandler server)]
      (when (instance? ServletContextHandler handler)
        (let [^ServletContextHandler ctx handler]
          (.setAttribute ctx
                         WebApplicationContext/ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE
                         ^Object spring-app-context)
          (.addFilter ctx
                      (DelegatingFilterProxy. "springSecurityFilterChain")
                      "/*"))))))

(defmethod ig/init-key :adapter/jetty
  [_ {:keys [port host handler filter-chain-proxy]}]
  (let [ring-handler (routes/app handler)
        app-ctx (:app-context filter-chain-proxy)
        server (jetty/run-jetty
                ring-handler
                {:port port
                 :host host
                 :join? false
                 :thread-pool (virtual-thread-pool)
                 :configurator (spring-security-configurator app-ctx)})]
    {:server server}))

(defmethod ig/halt-key! :adapter/jetty [_ {:keys [server]}]
  (when server
    (.stop ^Server server)))

(defmethod ig/init-key :handler/app [_ {:keys [routes] :as deps}]
  deps)

(defmethod ig/halt-key! :handler/app [_ _])

(defmethod ig/init-key :handler/routes [_ deps]
  deps)

(defmethod ig/halt-key! :handler/routes [_ _])

(defn -main [& [profile]]
  (let [cfg (load-config (or (keyword profile) :dev))
        system (ig/init cfg)]
    (println "Auth template server started")
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (ig/halt! system))))))
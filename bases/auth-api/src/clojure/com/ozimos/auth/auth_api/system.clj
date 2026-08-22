(ns com.ozimos.auth.auth-api.system
  (:require
   [com.ozimos.auth.config.interface :as config]
   [com.ozimos.auth.rama.interface :as rama]
   [com.ozimos.auth.security.interface :as security]
   [com.ozimos.auth.token.interface :as token]
   [integrant.core :as ig]
   [ring.adapter.jetty :as jetty])
  (:import
   (java.util.concurrent Executors TimeUnit)
   (org.eclipse.jetty.ee9.servlet ServletContextHandler)
   (org.eclipse.jetty.server Server)
   (org.eclipse.jetty.util.thread QueuedThreadPool)
   (org.springframework.web.context WebApplicationContext)
   (org.springframework.web.filter DelegatingFilterProxy)))

(defn load-config
  "Load Integrant config from resources. `profile` is :dev or :prod."
  [profile]
  (config/load-config profile))

(defn- virtual-thread-pool
  ^QueuedThreadPool []
  (doto (QueuedThreadPool.)
    (.setDaemon true)
    (.setVirtualThreadsExecutor (Executors/newVirtualThreadPerTaskExecutor))))

(def ^:private stub-handler
  (fn [_] {:status 200
           :headers {"Content-Type" "application/json"}
           :body "{\"ok\":true}"}))

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

(defn- build-ring-handler
  "Build the Ring handler from the routes deps.
   If deps is empty (Milestone A stub), returns a simple stub handler.
   Routes namespace is required at runtime to avoid loading component deps prematurely."
  [routes-deps]
  (if (empty? routes-deps)
    stub-handler
    (do
      (require 'com.ozimos.auth.auth-api.routes)
      ((ns-resolve (find-ns 'com.ozimos.auth.auth-api.routes) 'app) routes-deps))))

(defmethod ig/init-key :auth/policy [_ config]
  config)

(defmethod ig/init-key :com.ozimos.auth.auth-api.system/router
  [_ deps]
  (build-ring-handler deps))

(defmethod ig/init-key :adapter/jetty
  [_ {:keys [port host filter-chain-proxy handler]}]
  (let [app-ctx (:app-context filter-chain-proxy)
        opts {:port port
              :host host
              :join? false
              :thread-pool (virtual-thread-pool)}
        opts (if app-ctx
               (assoc opts :configurator (spring-security-configurator app-ctx))
               opts)
        server (jetty/run-jetty handler opts)]
    {:server server}))

(defmethod ig/halt-key! :adapter/jetty [_ {:keys [server]}]
  (when server
    (.stop ^Server server)))

(defmethod ig/init-key :cleanup/scheduler
  [_ {:keys [rama interval-ms]}]
  (let [scheduler (Executors/newScheduledThreadPool 1)]
    (.scheduleAtFixedRate scheduler
      (fn []
        (try
          (when-not (.isShutdown scheduler)
            (println "Cleanup: expired sessions" (rama/cleanup-expired-sessions rama)
                     "revocations" (rama/cleanup-expired-revocations rama)))
          (catch Exception e
            (let [msg (.getMessage e)]
              (when-not (and msg (or (.contains msg "Module not alive")
                                     (.contains msg "Cluster not alive")
                                     (.contains msg "closed")))
                (println "Cleanup error:" msg))))))
      interval-ms interval-ms TimeUnit/MILLISECONDS)
    {:scheduler scheduler}))

(defmethod ig/halt-key! :cleanup/scheduler [_ {:keys [scheduler]}]
  (when scheduler
    (.shutdownNow scheduler)
    (try
      (.awaitTermination scheduler 2 TimeUnit/SECONDS)
      (catch Exception _ nil))))

(defn -main [& [profile]]
  (let [cfg (load-config (or (keyword profile) :dev))
        system (ig/init cfg)]
    (println "Auth template server started")
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (ig/halt! system))))))

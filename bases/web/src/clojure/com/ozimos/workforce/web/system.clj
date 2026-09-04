(ns com.ozimos.workforce.web.system
  (:require
   [com.ozimos.omni-auth.config.interface :as config]
   [com.ozimos.omni-auth.notification.interface]
   [com.ozimos.omni-auth.rama.interface :as rama]
   [com.ozimos.omni-auth.token.interface]
   [com.ozimos.workforce.org.interface]
   [integrant.core :as ig]
   [ring.adapter.jetty :as jetty])
  (:import
   (java.util.concurrent Executors TimeUnit)
   (org.eclipse.jetty.server Server)
   (org.eclipse.jetty.util.thread QueuedThreadPool)))

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

(defn- build-ring-handler
  "Build the Ring handler from the routes deps.
   If deps is empty (Milestone A stub), returns a simple stub handler.
   Routes namespace is required at runtime to avoid loading component deps prematurely."
  [routes-deps]
  (if (empty? routes-deps)
    stub-handler
    (do
      (require 'com.ozimos.workforce.web.routes)
      ((ns-resolve (find-ns 'com.ozimos.workforce.web.routes) 'app) routes-deps))))

(defmethod ig/init-key :auth/policy [_ config]
  config)

(defmethod ig/init-key :com.ozimos.workforce.web.system/router
  [_ deps]
  (build-ring-handler deps))

(defmethod ig/init-key :adapter/jetty
  [_ {:keys [port host handler]}]
  (let [handler-atom (atom handler)
        delegated-handler (fn [req] (@handler-atom req))
        opts {:port port
              :host host
              :join? false
              :thread-pool (virtual-thread-pool)}
        server (jetty/run-jetty delegated-handler opts)]
    {:server server :handler-atom handler-atom}))

(defmethod ig/halt-key! :adapter/jetty [_ {:keys [server]}]
  (when server
    (.stop ^Server server)))

(defmethod ig/suspend-key! :adapter/jetty [_ _] nil)

(defmethod ig/resume-key :adapter/jetty
  [_ {:keys [port host handler] :as opts} old-opts {server :server handler-atom :handler-atom :as old-impl}]
  (if (= (select-keys opts [:port :host]) (select-keys old-opts [:port :host]))
    (do
      (when handler-atom
        (reset! handler-atom handler))
      old-impl)
    (do
      (ig/halt-key! :adapter/jetty old-impl)
      (ig/init-key :adapter/jetty opts))))

(defmethod ig/init-key :cleanup/scheduler
  [_ {:keys [rama interval-ms]}]
  (let [scheduler (Executors/newScheduledThreadPool 1)]
    (.scheduleAtFixedRate scheduler
      (fn []
        (try
          (when-not (.isShutdown scheduler)
            (println "Cleanup: expired sessions" (rama/cleanup-expired-sessions rama)
                     "revocations" (rama/cleanup-expired-revocations rama)))
          (catch Throwable e
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

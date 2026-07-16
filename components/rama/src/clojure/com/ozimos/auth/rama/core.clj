(ns com.ozimos.auth.rama.core
  (:require
   [com.ozimos.auth.rama.module :as module]
   [com.rpl.rama :as rama]
   [com.rpl.rama.test :as rtest]
   [integrant.core :as ig])
  (:import
   (com.rpl.rama.test InProcessCluster)))

(defonce ^:private ipc-instance
  (atom nil))

(defn module-name
  "Returns the full module name string for AuthModule."
  []
  (rama/get-module-name module/AuthModule))

(defn cluster-manager [system]
  (:cluster-manager system))

(defn pstate [cmgr module-name pstate-name]
  (rama/foreign-pstate cmgr module-name pstate-name))

(defn depot [cmgr module-name depot-name]
  (rama/foreign-depot cmgr module-name depot-name))

(defmethod ig/init-key :rama/cluster [_ {:keys [mode hosts repl-factor tasks threads]
                                         :or {mode :ipc
                                              repl-factor 1
                                              tasks 4
                                              threads 2}}]
  (case mode
    :ipc
    (if-let [ipc @ipc-instance]
      (do (println "Reusing existing Rama IPC cluster")
          {:cluster-manager ipc :mode :ipc})
      (let [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module/AuthModule {:tasks tasks :threads threads})
        (reset! ipc-instance ipc)
        {:cluster-manager ipc :mode :ipc}))

    :cluster
    (let [config (cond-> {"conductor.host" (first hosts)}
                   repl-factor (assoc "replication.factor" (str repl-factor)))
          cmgr (rama/open-cluster-manager config)]
      {:cluster-manager cmgr :mode :cluster})))

(defmethod ig/halt-key! :rama/cluster [_ {:keys [mode cluster-manager]}]
  ;; IPC cluster lives for the entire JVM lifetime — don't close on halt
  ;; (Rama 1.9.0 IPC can't restart in the same JVM).
  ;; Only close in production :cluster mode.
  (when (= mode :cluster)
    (.close cluster-manager)))

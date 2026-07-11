(ns com.ozimos.auth.rama.core
  (:require
   [com.ozimos.auth.rama.module :as module]
   [com.rpl.rama :as rama]
   [com.rpl.rama.test :as rtest]
   [integrant.core :as ig])
  (:import
   (com.rpl.rama.test InProcessCluster)))

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
    (let [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module/AuthModule {:tasks tasks :producedThreads threads})
      {:cluster-manager ipc :mode :ipc})

    :cluster
    (let [config (cond-> {"conductor.host" (first hosts)}
                   repl-factor (assoc "replication.factor" (str repl-factor)))
          cmgr (rama/open-cluster-manager config)]
      {:cluster-manager cmgr :mode :cluster})))

(defmethod ig/halt-key! :rama/cluster [_ {:keys [cluster-manager mode]}]
  (when cluster-manager
    (.close cluster-manager)))

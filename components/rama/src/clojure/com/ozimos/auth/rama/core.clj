(ns com.ozimos.auth.rama.core
  (:require
   [com.ozimos.auth.rama.module :as module]
   [com.rpl.rama :as rama]
   [com.rpl.rama.path :refer [ALL keypath]]
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

(defn- cmgr
  [rama-map]
  (:cluster-manager rama-map))

(defn all-session-ids
  [rama-map]
  (let [pstate (rama/foreign-pstate (cmgr rama-map) "AuthModule" "$$all-session-ids")]
    (rama/foreign-select [(keypath "_sessions") ALL] pstate)))

(defn all-revoked-jtis
  [rama-map]
  (let [pstate (rama/foreign-pstate (cmgr rama-map) "AuthModule" "$$all-revoked-jtis")]
    (rama/foreign-select [(keypath "_jtis") ALL] pstate)))

(defn cleanup-expired-sessions
  "Scans all sessions and appends SessionEnd for each expired one.
   Returns the count of expired sessions cleaned up."
  [rama-map]
  (let [sessions-pstate (rama/foreign-pstate (cmgr rama-map) "AuthModule" "$$sessions")
        session-end-depot (rama/foreign-depot (cmgr rama-map) "AuthModule" "*session-end-depot")
        session-ids (all-session-ids rama-map)
        now (System/currentTimeMillis)
        expired (volatile! 0)]
    (doseq [session-id session-ids]
      (let [expires-at (rama/foreign-select-one (keypath session-id :expires-at) sessions-pstate
                                                {:pkey session-id})]
        (when (and expires-at (< expires-at now))
          (rama/foreign-append! session-end-depot (module/->SessionEnd session-id))
          (vswap! expired inc))))
    @expired))

(defn cleanup-expired-revocations
  "Scans all revoked token entries and removes those past their expiry.
   Returns the count of expired tokens cleaned up."
  [rama-map]
  (let [revoked-pstate (rama/foreign-pstate (cmgr rama-map) "AuthModule" "$$revoked-tokens")
        clear-depot (rama/foreign-depot (cmgr rama-map) "AuthModule" "*clear-revocation-depot")
        jtis (all-revoked-jtis rama-map)
        now (System/currentTimeMillis)
        expired (volatile! 0)]
    (doseq [jti jtis]
      (let [expiry (rama/foreign-select-one (keypath jti) revoked-pstate {:pkey jti})]
        (when (and expiry (< expiry now))
          (rama/foreign-append! clear-depot (module/->ClearRevocation jti))
          (vswap! expired inc))))
    @expired))


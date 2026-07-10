(ns com.ozimos.auth.rama.interface
  (:require [com.ozimos.auth.rama.core :as core]))

(defn cluster-manager
  "Returns the Rama cluster manager (or IPC for dev) from the integrant system."
  [system]
  (core/cluster-manager system))

(defn pstate
  "Get a foreign-pstate client by name from the cluster manager."
  [cluster-manager module-name pstate-name]
  (core/pstate cluster-manager module-name pstate-name))

(defn depot
  "Get a foreign-depot client by name from the cluster manager."
  [cluster-manager module-name depot-name]
  (core/depot cluster-manager module-name depot-name))

(defn module-name
  "Returns the module name string for AuthModule."
  []
  (core/module-name))
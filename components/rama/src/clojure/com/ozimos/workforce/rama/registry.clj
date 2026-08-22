(ns com.ozimos.workforce.rama.registry
  (:require [com.ozimos.workforce.rama.extension :as ext]))

(defonce ^:private registered-extensions (atom []))

(defn register-extension!
  "Registers an extension instance implementing RamaModuleExtension."
  [extension]
  (when (satisfies? ext/RamaModuleExtension extension)
    (swap! registered-extensions (fn [exts]
                                   (if (some #(= (type %) (type extension)) exts)
                                     exts
                                     (conj exts extension))))))

(defn clear-extensions!
  "Clears all registered extensions."
  []
  (reset! registered-extensions []))

(defn get-registered-extensions
  "Returns vector of registered RamaModuleExtension instances."
  []
  @registered-extensions)

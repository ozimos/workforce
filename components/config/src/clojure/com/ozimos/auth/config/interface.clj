(ns com.ozimos.auth.config.interface
  (:require
   [com.ozimos.auth.config.core :as core]))

(defn load-config
  "Load and prepare the Integrant config from an EDN resource file.
   `profile` is a keyword like :dev or :prod."
  ([]
   (core/load-config :dev))
  ([profile]
   (core/load-config profile)))

(ns com.ozimos.workforce.config.interface
  (:require
   [com.ozimos.workforce.config.core :as core]))

(defn load-config
  "Load and prepare the Integrant config from an EDN resource file.
   `profile` is a keyword like :dev or :prod."
  ([]
   (core/load-config :dev))
  ([profile]
   (core/load-config profile)))

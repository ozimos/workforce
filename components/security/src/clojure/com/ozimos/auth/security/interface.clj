(ns com.ozimos.auth.security.interface
  (:require
   [com.ozimos.auth.security.core :as core]))

(defn build-application-context
  "Build a Spring ApplicationContext programmatically, registering all beans.
   Returns the ApplicationContext with a FilterChainProxy bean named 'springSecurityFilterChain'."
  [deps]
  (core/build-application-context deps))

(defn filter-chain-proxy
  "Extract the FilterChainProxy from the Spring ApplicationContext."
  [app-ctx]
  (core/filter-chain-proxy app-ctx))

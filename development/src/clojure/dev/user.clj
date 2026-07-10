(ns user
  (:require [integrant.repl :refer [go halt reset reset-all clear set-prep!]]
            [integrant.repl.state :refer [system config]]
            [com.ozimos.auth.auth_api.system :as sys]
            ;; Require all component namespaces so ig/init-key multimethods are loaded:
            [com.ozimos.auth.rama.core]
            [com.ozimos.auth.password.core]
            [com.ozimos.auth.token.core]
            [com.ozimos.auth.revocation.core]
            [com.ozimos.auth.security.core]
            [com.ozimos.auth.config.core]
            [com.ozimos.auth.auth_api.system]
            [com.ozimos.auth.auth_api.routes])
  (:gen-class))

(set-prep! #(sys/load-config :dev))

(comment
  (go)       ;; Start the entire system: Rama IPC, Spring context, Jetty
  (halt)     ;; Shut everything down
  (reset)    ;; Reload changed namespaces + rebuild system (Jetty keeps its port)
  (reset-all);; Reload ALL namespaces + rebuild system
  (clear)    ;; Discard prepped config + system
  )
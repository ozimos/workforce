(ns user
  (:require
   [integrant.repl :refer [clear go halt reset reset-all set-prep!]]
   [integrant.repl.state :refer [config system]])
  (:gen-class))

(require 'com.ozimos.auth.config.core
         'com.ozimos.auth.rama.core
         'com.ozimos.auth.rama.module
         'com.ozimos.auth.user.core
         'com.ozimos.auth.session.core
         'com.ozimos.auth.revocation.core
         'com.ozimos.auth.token.core
         'com.ozimos.auth.security.core
         'com.ozimos.auth.pathom.core
         'com.ozimos.auth.auth-api.system)

(set-prep! (fn [] ((requiring-resolve 'com.ozimos.auth.auth-api.system/load-config) :dev)))

(let [java-dirs ["components/security/src/java"]]
  (try
    (require 'virgil)
    (require 'clj-reload.core)
    ((resolve 'clj-reload.core/init)
     {:dirs     ["development/src/clojure"
                 "bases/auth-api/src/clojure"
                 "bases/auth-api/test/clojure"
                 "components/schema/src/clojure"
                 "components/config/src/clojure"
                 "components/rama/src/clojure"
                 "components/session/src/clojure"
                 "components/revocation/src/clojure"
                 "components/token/src/clojure"
                 "components/security/src/clojure"
                 "components/pathom/src/clojure"]
      :no-reload '#{user}
      :output   :verbose})
    ((resolve 'virgil/watch-and-recompile)
     java-dirs
     :options ["-proc:none"]
     :post-hook #((resolve 'clj-reload.core/reload) {:only :loaded}))
    (println "Java hot-reloading active via Virgil + clj-reload!" java-dirs)
    (catch Exception e
      (println "Virgil/clj-reload unavailable:" (-> e .getClass .getName) (.getMessage e)))))

(comment
  (go)       ;; Start the entire system
  (halt)     ;; Shut everything down
  (reset)    ;; Reload changed namespaces + rebuild system
  (reset-all) ;; Reload ALL namespaces + rebuild system
  (clear))    ;; Discard prepped config + system

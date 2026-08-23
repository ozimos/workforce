(ns com.ozimos.workforce.web.main
  (:require
   [com.ozimos.workforce.web.system :as system]
   [integrant.core :as ig])
  (:gen-class))

(defn -main [& args]
  (let [profile (or (keyword (first args)) :dev)
        cfg (system/load-config profile)
        system (ig/init cfg)]
    (println "Auth template server started on port 8080")
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (ig/halt! system))))))

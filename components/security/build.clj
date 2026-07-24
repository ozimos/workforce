(ns build
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]))

(def class-dir "target/classes")

(defn compile-java [_]
  (println "Compiling Java sources...")
  (b/delete {:path class-dir})
  (let [basis (b/create-basis {:project "deps.edn"})
        cp (str/join ":" (map key (:classpath basis)))
        source "src/java/com/ozimos/auth/security/SecurityConfig.java"
        {:keys [exit err]} (sh "javac" "-d" class-dir "-cp" cp source)]
    (if (zero? exit)
      (println "Java compiled to" class-dir)
      (throw (ex-info (str "Java compilation failed: " err) {:exit exit})))))

(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.ozimos.workforce/auth-service)
(def version "0.1.0-SNAPSHOT")
(def class-dir "target/classes")
;; Build from the workspace root, whose :uberjar alias supplies every brick
;; (the 14 omni-auth components plus org-rama and web). Note that
;; projects/auth-service/deps.edn also declares :uberjar, but its :local/root
;; paths are relative to the project directory, so they do not resolve when the
;; build is invoked from here.
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:uberjar]}))
(def uber-file (str "target/" (name lib) "-" version "-standalone.jar"))

;; Merging every dependency into one jar means collisions. Netty ships
;; META-INF/license/NOTICE.harmony.txt as both a file and a directory depending
;; on the artefact, which aborts the merge; jar signatures are likewise invalid
;; once their contents are repacked. Neither affects runtime behaviour.
(def uber-exclusions
  [#"^META-INF/license/.*"
   #"^META-INF/[^/]*\.(SF|RSA|DSA)$"])

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["bases/web/src/clojure"
                          "bases/web/resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["bases/web/src/clojure"
                             "components/*/src/clojure"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :exclude uber-exclusions
           :main "com.ozimos.workforce.web.main"}))

(ns com.ozimos.auth.config.core
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [integrant.core :as ig]))

;; Register #ig/ref and #ig/refset as Aero reader tags so they work alongside #profile
(defmethod aero/reader 'ig/ref [_opts _tag value]
  (ig/ref value))

(defmethod aero/reader 'ig/refset [_opts _tag value]
  (ig/refset value))

;; Register safe #long reader tag that returns nil on empty string or parse error
(defmethod aero/reader 'long [_opts _tag value]
  (cond
    (integer? value) (long value)
    (string? value) (when (seq (clojure.string/trim value))
                      (try (Long/parseLong (clojure.string/trim value)) (catch Exception _ nil)))
    :else nil))

;; Register #ig/var for Integrant variables
(defmethod aero/reader 'ig/var [_opts _tag value]
  (ig/var value))

(defn load-config
  "Load config.edn from resources using Aero for #profile resolution.
   #ig/ref and #ig/refset tags are also resolved.
   Then runs ig/prep and ig/expand on the result.
   `profile` is a keyword like :dev or :prod."
  [profile]
  (let [resource (io/resource "config.edn")
        _ (when-not resource
            (throw (ex-info "No config.edn found on classpath" {})))]
    (-> (aero/read-config resource {:profile profile})
        ig/expand)))

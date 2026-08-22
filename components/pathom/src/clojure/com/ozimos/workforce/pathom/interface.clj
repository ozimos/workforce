(ns com.ozimos.workforce.pathom.interface
  (:require
   [com.ozimos.workforce.pathom.core :as core]))

(defn build-env
  "Build a Pathom environment with all resolvers and mutations registered.
   `deps` is the integrant deps map (contains :user-store, etc.).
   `auth` is an optional map with :user-id for authenticated requests.
   `extra-resolvers` is an optional collection of domain resolvers/mutations."
  ([deps]
   (core/build-env deps))
  ([deps auth]
   (core/build-env deps auth))
  ([deps auth extra-resolvers]
   (core/build-env deps auth extra-resolvers)))

(defn process
  "Process an EQL query against the Pathom environment.
   `env` is the built Pathom environment.
   `eql` is the EQL query."
  [env eql]
  (core/process env eql))

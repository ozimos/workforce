(ns com.ozimos.workforce.rama.extension-test
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.ozimos.workforce.rama.core :as rama-core]
            [com.ozimos.workforce.rama.extension :as ext]
            [com.ozimos.workforce.rama.module :as mod]
            [com.ozimos.workforce.rama.registry :as reg]
            [com.rpl.rama :as rama]
            [com.rpl.rama.test :as rtest]))

(defrecord SampleEvent [item-id value])

(defrecord SampleExtension []
  ext/RamaModuleExtension
  (declare-depots [_ setup]
    (declare-depot setup *sample-ext-depot (hash-by :item-id)))
  (declare-pstates [_ topology]
    (declare-pstate topology $$sample-items {String Long}))
  (build-topology [_ topology]
    (<<sources topology
               (source> *sample-ext-depot :> {:keys [*item-id *value]})
               (|hash *item-id)
               (local-transform> [(keypath *item-id) (termval *value)] $$sample-items))))

(deftest test-rama-module-extension-plugin
  (testing "RamaModuleExtension protocol can plug extra depots, pstates, and topologies into AuthModule"
    (reg/clear-extensions!)
    (reg/register-extension! (->SampleExtension))
    (let [extensions (reg/get-registered-extensions)]
      (is (= 1 (count extensions)))
      (is (satisfies? ext/RamaModuleExtension (first extensions))))

    (let [ipc (rtest/create-ipc)]
      (try
        (rtest/launch-module! ipc mod/AuthModule {:tasks 2 :threads 1})
        (let [mod-name (rama-core/module-name)
              depot (rama/foreign-depot ipc mod-name "*sample-ext-depot")
              pstate (rama/foreign-pstate ipc mod-name "$$sample-items")]
          (is (some? depot) "Extension depot should exist")
          (is (some? pstate) "Extension pstate should exist")

          (rama/foreign-append! depot (->SampleEvent "item-123" 999))
          (let [res (rama/foreign-select-one (keypath "item-123") pstate {:pkey "item-123"})]
            (is (= 999 res) "Extension topology should process and store events into pstate")))
        (finally
          (.close ipc)
          (reg/clear-extensions!))))))

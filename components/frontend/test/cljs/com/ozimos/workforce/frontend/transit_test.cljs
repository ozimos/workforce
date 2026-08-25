(ns com.ozimos.workforce.frontend.transit-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(deftest transit-codec-test
  (testing "write-str and read-str roundtrips Clojure data structures"
    (let [sample {:user/id "u-123"
                  :org/id 42
                  :items [:a :b :c]
                  :nested {:active? true :count 10}}
          encoded (transit/write-str sample)
          decoded (transit/read-str encoded)]
      (is (= sample decoded))))

  (testing "data envelope structure is parsed correctly"
    (let [wrapped {:ok true :data {:user/active-org {:org/id 1 :org/name "Acme"}}}
          encoded (transit/write-str wrapped)
          decoded (transit/read-str encoded)]
      (is (true? (:ok decoded)))
      (is (= {:org/id 1 :org/name "Acme"} (get-in decoded [:data :user/active-org]))))))

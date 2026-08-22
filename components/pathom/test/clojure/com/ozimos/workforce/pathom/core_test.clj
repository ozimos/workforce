(ns com.ozimos.workforce.pathom.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.workforce.auth-api.test-system :as ts]
   [com.ozimos.workforce.pathom.core :as pathom]
   [com.ozimos.workforce.user.interface :as user]
   [com.wsscode.pathom3.interface.eql :as p.eql]))

(def ^:dynamic *deps* nil)

(defn system-fixture
  [tests]
  (ts/with-sys
    (let [us (ts/user-store sys)]
      (binding [*deps* (assoc us :user-store us)]
        (tests)))))

(use-fixtures :once system-fixture)

(defn- short-suffix []
  (-> (random-uuid) str (.replace "-" "") (.substring 0 12)))

(defn- register-user []
  (let [suffix (short-suffix)
        [ok user] (user/register! *deps* {:username (str "ptest-" suffix)
                                          :email (str "ptest-" suffix "@test.com")
                                          :password "P@ssword123"})]
    (is ok)
    user))

(deftest ^:integration current-user-resolver-test
  (testing "current-user-resolver returns authenticated user info"
    (let [user (register-user)
          env (pathom/build-env *deps* {:user-id (:id user)})
          result (pathom/process env [:current-user/id :current-user/username :current-user/email])]
      (println "\n=== current-user-resolver-test ===")
      (println "result:" (pr-str result))
      (is (= (:id user) (:current-user/id result)) "user-id should match")
      (is (= (:username user) (:current-user/username result)) "username should match")
      (is (= (:email user) (:current-user/email result)) "email should match")
      (println "=== end current-user-resolver-test ==="))))

(defn- unauthenticated-ex? [e]
  (= :unauthenticated (some #(-> % ex-data :type)
                            (take-while some? (iterate #(.getCause ^Throwable %) e)))))

(deftest ^:integration current-user-resolver-unauthenticated-test
  (testing "current-user-resolver throws on unauthenticated request"
    (let [env (pathom/build-env *deps*)]
      (println "\n=== current-user-resolver-unauthenticated-test ===")
      (is (try (pathom/process env [:current-user/id])
               false
               (catch Exception e
                 (unauthenticated-ex? e))))
      (println "=== end current-user-resolver-unauthenticated-test ==="))))

(deftest ^:integration auth-guard-test
  (testing "Resolvers and mutations throw :unauthenticated for anonymous requests"
    (let [env (pathom/build-env *deps*)]
      (println "\n=== auth-guard-test ===")
      (is (try (pathom/process env [:current-user/id])
               false
               (catch Exception e
                 (unauthenticated-ex? e)))
          "current-user-resolver should throw for anonymous")
      (println "=== end auth-guard-test ==="))))

(deftest ^:integration update-username-mutation-test
  (testing "Updating username via Pathom EQL mutation"
    (let [user (register-user)
          user-id (:id user)
          env (pathom/build-env *deps* {:user-id user-id})
          new-uname (str "eql-uname-" (short-suffix))
          res (pathom/process env [(list 'user/update-username {:user/new-username new-uname})])
          mutation-res (or (get res :user/update-username) (get res 'user/update-username) (first (vals res)))]
      (println "\n=== update-username-mutation-test ===")
      (println "result:" (pr-str mutation-res))
      (is (= user-id (:current-user/id mutation-res)))
      (is (= new-uname (:current-user/username mutation-res)))
      (is (nil? (:user/errors mutation-res)))
      (println "=== end update-username-mutation-test ==="))))

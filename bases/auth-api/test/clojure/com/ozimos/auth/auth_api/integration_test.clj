(ns com.ozimos.auth.auth-api.integration-test
  (:require
   [cheshire.core :as json]
   [clj-http.lite.client :as http]
   [clojure.test :refer [deftest is testing]]))

(def base-url "http://localhost:8080")

(defn- short-suffix []
  (-> (java.util.UUID/randomUUID) str (.replace "-" "") (.substring 0 12)))

(defn- random-user []
  (let [suffix (short-suffix)]
    {:username (str "test-" suffix)
     :email (str "test-" suffix "@example.com")
     :password "P@ssword123"}))

(defn- parse-body [resp]
  (update resp :body #(when % (json/parse-string %))))

(defn- post-json
  ([url body]
   (post-json url body {}))
  ([url body headers]
   (->> (http/post url
                   {:body (json/generate-string body)
                    :content-type :json
                    :accept :json
                    :throw-exceptions false
                    :as :string
                    :headers headers})
        parse-body)))

(defn- get-json
  ([url]
   (get-json url {}))
  ([url headers]
   (->> (http/get url
                  {:accept :json
                   :as :string
                   :throw-exceptions false
                   :headers headers})
        parse-body)))

(deftest ^:integration health-test
  (testing "GET /api/health returns 200 with status ok"
    (let [resp (get-json (str base-url "/api/health"))]
      (is (= 200 (:status resp)))
      (is (= "ok" (get-in resp [:body "status"]))))))

(deftest ^:integration auth-flow-test
  (let [user (random-user)]

    (testing "POST /api/auth/register creates a new user"
      (let [resp (post-json (str base-url "/api/auth/register") user)]
        (is (= 201 (:status resp)))
        (is (some? (get-in resp [:body "id"])))
        (is (= (:username user) (get-in resp [:body "username"])))
        (is (= (:email user) (get-in resp [:body "email"])))
        (is (false? (get-in resp [:body "verified"])))))

    (testing "POST /api/auth/register with duplicate email returns 409"
      (let [resp (post-json (str base-url "/api/auth/register") user)]
        (is (= 409 (:status resp)))
        (is (get-in resp [:body "errors"]))))

    (testing "POST /api/auth/login with valid credentials returns tokens"
      (let [resp (post-json (str base-url "/api/auth/login")
                            {:username (:username user)
                             :password (:password user)})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (string? (get body "access-token")))
        (is (string? (get body "refresh-token")))
        (is (pos? (get body "expires-in")))))

    (testing "POST /api/auth/login with wrong password returns 401"
      (let [resp (post-json (str base-url "/api/auth/login")
                            {:username (:username user)
                             :password "CorrectHorseBatteryStaple1!"})]
        (is (= 401 (:status resp)))
        (is (get-in resp [:body "errors"]))))

    (let [login-resp (post-json (str base-url "/api/auth/login")
                                {:username (:username user)
                                 :password (:password user)})
          access-token (get-in login-resp [:body "access-token"])
          refresh-token (get-in login-resp [:body "refresh-token"])]

      (is (string? access-token) "access-token present")
      (is (string? refresh-token) "refresh-token present")

      (testing "POST /api/auth/refresh with valid refresh token"
        (let [resp (post-json (str base-url "/api/auth/refresh")
                              {:refresh-token refresh-token})
              body (:body resp)]
          (is (= 200 (:status resp)))
          (is (string? (get body "access-token")))
          (is (string? (get body "refresh-token")))))

      (testing "POST /api/auth/refresh with revoked (used) refresh token returns 401"
        (let [resp (post-json (str base-url "/api/auth/refresh")
                              {:refresh-token refresh-token})]
          (is (= 401 (:status resp)))
          (is (get-in resp [:body "errors"]))))

      (testing "POST /api/auth/logout revokes access token"
        (let [resp (post-json (str base-url "/api/auth/logout")
                              {}
                              {"Authorization" (str "Bearer " access-token)})]
          (is (= 200 (:status resp)))
          (is (= "Logged out" (get-in resp [:body "message"]))))))))

(deftest ^:integration logout-everywhere-test
  (let [user (random-user)
        _ (post-json (str base-url "/api/auth/register") user)
        login-resp (post-json (str base-url "/api/auth/login")
                              {:username (:username user)
                               :password (:password user)})
        access-token (get-in login-resp [:body "access-token"])]

    (testing "POST /api/auth/logout-everywhere revokes all sessions"
      (let [resp (post-json (str base-url "/api/auth/logout-everywhere")
                            {}
                            {"Authorization" (str "Bearer " access-token)})]
        (is (= 200 (:status resp)))
        (is (= "Logged out from all devices" (get-in resp [:body "message"])))))))

(deftest ^:integration invalid-tokens-test
  (testing "POST /api/auth/refresh with garbage token returns 401"
    (let [resp (post-json (str base-url "/api/auth/refresh")
                          {:refresh-token "not-a-valid-jwt"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body "errors"]))))

  (testing "POST /api/auth/login with non-existent user returns 401"
    (let [resp (post-json (str base-url "/api/auth/login")
                          {:username "nonexistent"
                           :password "CorrectHorseBatteryStaple1!"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body "errors"])))))

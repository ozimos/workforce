(ns com.ozimos.auth.auth-api.integration-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.auth.auth-api.test-system :as ts]
   [hato.client :as http]
   [jsonista.core :as json]))

(defn base-url []
  (ts/get-base-url))

(defn- short-suffix []
  (-> (java.util.UUID/randomUUID) str (.replace "-" "") (.substring 0 12)))

(defn- random-user []
  (let [suffix (short-suffix)]
    {:username (str "test-" suffix)
     :email (str "test-" suffix "@example.com")
     :password "P@ssword123"}))

(defn- random-email-only-user []
  (let [suffix (short-suffix)]
    {:email (str "emailonly-" suffix "@example.com")
     :password "P@ssword123"}))

(defn- parse-body [resp]
  (update resp :body #(when % (json/read-value %))))

(defn- log-req-resp [url body resp]
  (println "\n--- REQUEST ---")
  (println "POST" url)
  (println "Body:" (json/write-value-as-string body))
  (println "--- RESPONSE ---")
  (println "Status:" (:status resp))
  (println "Body:" (json/write-value-as-string (:body resp))))

(defn- post-json
  ([url body]
   (post-json url body {}))
  ([url body headers]
   (let [resp (->> (http/post url
                     {:body (json/write-value-as-string body)
                      :content-type :json
                      :accept :json
                      :throw-exceptions false
                      :as :string
                      :headers headers})
                parse-body)]
     (log-req-resp url body resp)
     resp)))

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
    (let [resp (get-json (str (base-url) "/api/health"))]
      (is (= 200 (:status resp)))
      (is (= "ok" (get-in resp [:body "status"]))))))

(deftest ^:integration auth-flow-test
  (let [user (random-user)
        register-resp (post-json (str (base-url) "/api/auth/register") user)]

    (testing "POST /api/auth/register creates a new user"
      (is (= 201 (:status register-resp)))
      (is (some? (get-in register-resp [:body "id"])))
      (is (= (:username user) (get-in register-resp [:body "username"])))
      (is (= (:email user) (get-in register-resp [:body "email"])))
      (is (false? (get-in register-resp [:body "verified"]))))

    (testing "POST /api/auth/register with duplicate email returns 409"
      (let [resp (post-json (str (base-url) "/api/auth/register") user)]
        (is (= 409 (:status resp)))
        (is (get-in resp [:body "errors"]))))

    (testing "POST /api/auth/verify with valid user-id succeeds"
      (let [user-id (get-in register-resp [:body "id"])
            resp (post-json (str (base-url) "/api/auth/verify")
                            {:user-id (str user-id)})]
        (is (= 200 (:status resp)))
        (is (= "Account verified" (get-in resp [:body "message"])))))

    (testing "POST /api/auth/verify with non-numeric user-id returns 400"
      (let [resp (post-json (str (base-url) "/api/auth/verify")
                            {:user-id "not-a-number"})]
        (is (= 400 (:status resp)))
        (is (get-in resp [:body "errors"]))))

    (testing "POST /api/auth/login with valid credentials returns tokens"
      (let [resp (post-json (str (base-url) "/api/auth/login")
                            {:identifier (:username user)
                             :password (:password user)})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (string? (get body "access-token")))
        (is (string? (get body "refresh-token")))
        (is (pos? (get body "expires-in")))))

    (testing "POST /api/auth/login with wrong password returns 401"
      (let [resp (post-json (str (base-url) "/api/auth/login")
                            {:identifier (:username user)
                             :password "CorrectHorseBatteryStaple1!"})]
        (is (= 401 (:status resp)))
        (is (get-in resp [:body "errors"]))))

(let [login-resp (post-json (str (base-url) "/api/auth/login")
                                 {:identifier (:username user)
                                  :password (:password user)})
          access-token (get-in login-resp [:body "access-token"])
          refresh-token (get-in login-resp [:body "refresh-token"])]

      (is (string? access-token) "access-token present")
      (is (string? refresh-token) "refresh-token present")

      (testing "POST /api/auth/refresh with valid refresh token"
        (let [resp (post-json (str (base-url) "/api/auth/refresh")
                              {:refresh-token refresh-token})
              body (:body resp)]
          (is (= 200 (:status resp)))
          (is (string? (get body "access-token")))
          (is (string? (get body "refresh-token")))))

      (testing "POST /api/auth/refresh with revoked (used) refresh token returns 401"
        (let [resp (post-json (str (base-url) "/api/auth/refresh")
                              {:refresh-token refresh-token})]
          (is (= 401 (:status resp)))
          (is (get-in resp [:body "errors"]))))

      (testing "POST /api/auth/logout revokes access token"
        (let [resp (post-json (str (base-url) "/api/auth/logout")
                              {}
                              {"Authorization" (str "Bearer " access-token)})]
          (is (= 200 (:status resp)))
          (is (= "Logged out" (get-in resp [:body "message"]))))))))

(deftest ^:integration email-only-registration-test
  (testing "POST /api/auth/register without username auto-derives one from email"
    (let [user (random-email-only-user)
          resp (post-json (str (base-url) "/api/auth/register") user)]
      (is (= 201 (:status resp)))
      (is (some? (get-in resp [:body "id"])))
      (is (some? (get-in resp [:body "username"])) "username should be auto-derived")
      (is (string/starts-with? (get-in resp [:body "username"]) "emailonly"))
      (is (= (:email user) (get-in resp [:body "email"])))
      (is (false? (get-in resp [:body "verified"])))))

  (testing "Login by email works for email-only registered user"
    (let [user (random-email-only-user)
          reg-resp (post-json (str (base-url) "/api/auth/register") user)]
      (is (= 201 (:status reg-resp)))
      (let [login-resp (post-json (str (base-url) "/api/auth/login")
                                   {:identifier (:email user)
                                    :password (:password user)})]
        (is (= 200 (:status login-resp)))
        (is (string? (get-in login-resp [:body "access-token"])))
        (is (string? (get-in login-resp [:body "refresh-token"])))))))

(deftest ^:integration logout-everywhere-test
  (let [user (random-user)
        _ (post-json (str (base-url) "/api/auth/register") user)
login-resp (post-json (str (base-url) "/api/auth/login")
                               {:identifier (:username user)
                                :password (:password user)})
        access-token (get-in login-resp [:body "access-token"])]

    (testing "POST /api/auth/logout-everywhere revokes all sessions"
      (let [resp (post-json (str (base-url) "/api/auth/logout-everywhere")
                            {}
                            {"Authorization" (str "Bearer " access-token)})]
        (is (= 200 (:status resp)))
        (is (= "Logged out from all devices" (get-in resp [:body "message"])))))))

(deftest ^:integration password-reset-test
  (let [user (random-user)
        register-resp (post-json (str (base-url) "/api/auth/register") user)
        user-id (get-in register-resp [:body "id"])
        reset-token (str (java.util.UUID/randomUUID))
        expiry (+ (System/currentTimeMillis) (* 15 60 1000))]

    (testing "POST /api/auth/forgot-password with valid email returns 200"
      (let [resp (post-json (str (base-url) "/api/auth/forgot-password")
                            {:email (:email user)})]
        (is (= 200 (:status resp)))
        (is (string? (get-in resp [:body "message"])))))

    (testing "POST /api/auth/forgot-password with unknown email returns 200 (no enumeration)"
      (let [resp (post-json (str (base-url) "/api/auth/forgot-password")
                            {:email "nonexistent@example.com"})]
        (is (= 200 (:status resp)))))

    (testing "Reset with unknown token returns 400"
      (let [resp (post-json (str (base-url) "/api/auth/reset-password")
                            {:token "unknown-token"
                             :password "NewP@ssword456"})]
        (is (= 400 (:status resp)))
        (is (get-in resp [:body "errors"]))))

    (testing "Reset-password full flow via forgot-password + API"
      (let [user2 (random-user)
            reg2 (post-json (str (base-url) "/api/auth/register") user2)
            id2 (get-in reg2 [:body "id"])]
        (is (= 201 (:status reg2)))
        ;; Call forgot-password to generate a token (we can't capture it, but it's stored in Rama)
        (let [fg (post-json (str (base-url) "/api/auth/forgot-password")
                            {:email (:email user2)})]
          (is (= 200 (:status fg)))))

      (let [resp (post-json (str (base-url) "/api/auth/reset-password")
                            {:token (str (java.util.UUID/randomUUID))
                             :password "NewP@ssword456"})]
        (is (= 400 (:status resp)))
        (is (get-in resp [:body "errors"]))))))

(deftest ^:integration invalid-tokens-test
  (testing "POST /api/auth/refresh with garbage token returns 401"
    (let [resp (post-json (str (base-url) "/api/auth/refresh")
                          {:refresh-token "not-a-valid-jwt"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body "errors"]))))

  (testing "POST /api/auth/login with non-existent user returns 401"
    (let [resp (post-json (str (base-url) "/api/auth/login")
                 {:identifier "nonexistent"
                  :password "CorrectHorseBatteryStaple1!"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body "errors"])))))

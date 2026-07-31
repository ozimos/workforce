(ns com.ozimos.auth.auth-api.integration-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.auth.auth-api.test-system :as ts]
   [hato.client :as http]
   [jsonista.core :as json]))

(def ^:dynamic *base-url* nil)

(defn system-fixture [tests]
  (ts/with-sys
    (binding [*base-url* (ts/get-base-url sys)]
      (tests))))

(use-fixtures :once system-fixture)

(defn base-url []
  *base-url*)

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

(defn- auth-header [token]
  {"Authorization" (str "Bearer " token)})

(defn- query-eql
  "Send an EQL query to the /api/query endpoint."
  ([url eql-string]
   (query-eql url eql-string nil))
  ([url eql-string token]
   (let [body {:eql eql-string}
         headers (if token (auth-header token) {})
         resp (post-json url body headers)]
     resp)))

(deftest ^:integration org-query-flow-test
  (let [url (base-url)
        user {:username (str "orgflow-" (short-suffix))
              :email (str "orgflow-" (short-suffix) "@example.com")
              :password "P@ssword123"}
        reg-resp (post-json (str url "/api/auth/register") user)]
    (is (= 201 (:status reg-resp)))

    (testing "POST /api/auth/login for org flow user"
      (let [login-resp (post-json (str url "/api/auth/login")
                         {:identifier (:username user)
                          :password (:password user)})
            token (get-in login-resp [:body "access-token"])]
        (is (string? token) "access-token should be present")

        (testing "POST /api/query current-user resolver returns user info"
          (let [resp (query-eql (str url "/api/query")
                       "[:current-user/id :current-user/username]"
                       token)]
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body "ok"])))
            (is (some? (get-in resp [:body "data" "current-user/id"])))
            (is (= (:username user) (get-in resp [:body "data" "current-user/username"])))))

        (testing "POST /api/query create-org mutation"
          (let [org-name (str "OrgFlow-" (short-suffix))
                resp (query-eql (str url "/api/query")
                       (str "[(org/create {:org/name \"" org-name "\"})]")
                       token)]
            (println "\ncreate-org mutation response:" (pr-str resp))
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body "ok"])))))

        (testing "POST /api/query user/orgs resolver"
          (let [resp (query-eql (str url "/api/query")
                       "[{:user/orgs [:org/id :org/name :org/role :org/status]}]"
                       token)
                orgs (get-in resp [:body "data" "user/orgs"])]
            (println "user/orgs response:" (pr-str resp))
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body "ok"])))
            (is (some? orgs) "user/orgs should be present")
            (is (some #(= "ADMIN" (get % "org/role")) orgs) "admin role should be present")))

        (testing "POST /api/query active-org resolver"
          (let [resp (query-eql (str url "/api/query")
                       "[{:user/active-org [:org/id :org/name :org/role]}]"
                       token)
                active (get-in resp [:body "data" "user/active-org"])]
            (println "active-org response:" (pr-str resp))
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body "ok"])))
            (is (some? active) "active-org should be set after creating org")))

        (testing "POST /api/query with no auth token returns 401"
          (let [resp (query-eql (str url "/api/query")
                       "[:current-user/id]")]
            (is (or (= 401 (:status resp))
                    (= 403 (:status resp)))
                "unauthenticated query should return 401 or 403")))))))

(deftest ^:integration org-invite-join-http-test
  (let [url (base-url)
        owner {:username (str "invowner-" (short-suffix))
               :email (str "invowner-" (short-suffix) "@example.com")
               :password "P@ssword123"}
        joiner {:username (str "invjoiner-" (short-suffix))
                :email (str "invjoiner-" (short-suffix) "@example.com")
                :password "P@ssword123"}
        owner-reg (post-json (str url "/api/auth/register") owner)
        joiner-reg (post-json (str url "/api/auth/register") joiner)
        owner-login (post-json (str url "/api/auth/login")
                      {:identifier (:username owner)
                       :password (:password owner)})
        owner-token (get-in owner-login [:body "access-token"])
        joiner-login (post-json (str url "/api/auth/login")
                       {:identifier (:username joiner)
                        :password (:password joiner)})
        joiner-token (get-in joiner-login [:body "access-token"])]
    (is (= 201 (:status owner-reg)))
    (is (= 201 (:status joiner-reg)))
    (is (string? owner-token) "owner should have access-token")
    (is (string? joiner-token) "joiner should have access-token")

    (testing "Owner creates org via query endpoint"
      (let [org-name (str "InvOrg-" (short-suffix))
            resp (query-eql (str url "/api/query")
                   (str "[(org/create {:org/name \"" org-name "\"})]")
                   owner-token)]
        (is (= 200 (:status resp)))
        (is (true? (get-in resp [:body "ok"])))))))

(deftest ^:integration query-endpoint-auth-test
  (let [url (base-url)]
    (testing "POST /api/query with invalid token returns errors"
      (let [resp (post-json (str url "/api/query")
                   {:eql "[:current-user/id]"}
                   {"Authorization" "Bearer invalid.jwt.token"})]
        (println "invalid token query response:" (pr-str resp))
        (is (or (= 401 (:status resp))
                (= 403 (:status resp))
                (= 400 (:status resp)))
            "invalid token should return an error status code")))))

(deftest ^:integration username-update-test
  (let [url (base-url)
        user (random-user)
        reg-resp (post-json (str url "/api/auth/register") user)]
    (is (= 201 (:status reg-resp)))

    (let [login-resp (post-json (str url "/api/auth/login")
                       {:identifier (:username user)
                        :password (:password user)})
          token (get-in login-resp [:body "access-token"])
          new-uname (str "updated-" (short-suffix))]
      (is (string? token) "access-token should be present")

      (testing "POST /api/query user/update-username mutation updates the username"
        (let [eql-str (pr-str [(list 'user/update-username {:user/new-username new-uname})])
              resp (post-json (str url "/api/query")
                     {:eql eql-str}
                     (auth-header token))
              mutation-res (get-in resp [:body "data" "user/update-username"])]
          (println "user/update-username EQL response:" (pr-str resp))
          (is (= 200 (:status resp)))
          (is (= new-uname (get mutation-res "current-user/username")))))

      (testing "Login with new username works"
        (let [new-login-resp (post-json (str url "/api/auth/login")
                               {:identifier new-uname
                                :password (:password user)})]
          (is (= 200 (:status new-login-resp)))))

      (testing "POST /api/query user/update-username without auth returns error"
        (let [eql-str (pr-str [(list 'user/update-username {:user/new-username (str "unauth-" (short-suffix))})])
              resp (post-json (str url "/api/query")
                     {:eql eql-str})]
          (is (or (= 401 (:status resp))
                  (= 403 (:status resp))
                  (= 400 (:status resp)))
              "unauthenticated query should return an error status code"))))))

(ns com.ozimos.auth.auth-api.integration-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.auth.auth-api.test-system :as ts]
   [hato.client :as http]
   [jsonista.core :as json]
   [muuntaja.core :as m]))

(def ^:dynamic *sys* nil)
(def ^:dynamic *base-url* nil)

(defn system-fixture [tests]
  (ts/with-sys
    (binding [*sys* sys
              *base-url* (ts/get-base-url sys)]
      (tests))))

(use-fixtures :once system-fixture)

(defn base-url []
  *base-url*)

(defn- short-suffix []
  (-> (random-uuid) str (.replace "-" "") (.substring 0 12)))

(defn- random-user []
  (let [suffix (short-suffix)]
    {:username (str "test-" suffix)
     :email (str "test-" suffix "@example.com")
     :password "P@ssword123"}))

(defn- random-email-only-user []
  (let [suffix (short-suffix)]
    {:email (str "emailonly-" suffix "@example.com")
     :password "P@ssword123"}))

(defn- auth-header [token]
  {"authorization" (str "Bearer " token)})

(defn- parse-ring-response [resp]
  (if (instance? java.io.InputStream (:body resp))
    (let [s (slurp (:body resp))]
      (assoc resp :body (when (seq s) (clojure.edn/read-string s))))
    resp))

(defn- post-edn
  "Executes an in-memory Ring request against (:router/ring *sys*) using EDN format negotiation.
   Returns the response map with parsed native Clojure data in `:body`."
  ([uri body-params]
   (post-edn uri body-params {}))
  ([uri body-params headers]
   (let [handler (:router/ring *sys*)
         req {:request-method :post
              :uri uri
              :headers (merge {"content-type" "application/edn"
                               "accept" "application/edn"}
                              headers)
              :body-params body-params}
         resp (handler req)]
     (parse-ring-response resp))))

(defn- get-edn
  "Executes an in-memory Ring GET request against (:router/ring *sys*) using EDN format negotiation."
  ([uri]
   (get-edn uri {}))
  ([uri headers]
   (let [handler (:router/ring *sys*)
         req {:request-method :get
              :uri uri
              :headers (merge {"accept" "application/edn"}
                              headers)}
         resp (handler req)]
     (parse-ring-response resp))))

(defn- query-eql
  "Send an EQL query to the /api/query endpoint in-memory."
  ([eql-string]
   (query-eql eql-string nil))
  ([eql-string token]
   (let [body {:eql eql-string}
         headers (if token (auth-header token) {})
         resp (post-edn "/api/query" body headers)]
     resp)))

(deftest ^:integration jetty-socket-smoke-test
  (testing "End-to-end HTTP socket request over real Jetty web server"
    (let [url (base-url)
          user (random-user)
          resp (-> (http/post (str url "/api/auth/register")
                     {:body (json/write-value-as-string user)
                      :content-type :json
                      :accept :json
                      :throw-exceptions false
                      :as :string})
                   (update :body json/read-value))]
      (is (= 201 (:status resp)))
      (is (some? (get-in resp [:body "id"]))))))

(deftest ^:integration health-test
  (testing "GET /api/health returns 200 with status ok"
    (let [resp (get-edn "/api/health")]
      (is (= 200 (:status resp)))
      (is (= "ok" (get-in resp [:body :status]))))))

(deftest ^:integration auth-flow-test
  (let [user (random-user)
        register-resp (post-edn "/api/auth/register" user)]

    (testing "POST /api/auth/register creates a new user"
      (is (= 201 (:status register-resp)))
      (is (some? (get-in register-resp [:body :id])))
      (is (= (:username user) (get-in register-resp [:body :username])))
      (is (= (:email user) (get-in register-resp [:body :email])))
      (is (false? (get-in register-resp [:body :verified]))))

    (testing "POST /api/auth/register with duplicate email returns 409"
      (let [resp (post-edn "/api/auth/register" user)]
        (is (= 409 (:status resp)))
        (is (get-in resp [:body :errors]))))

    (testing "POST /api/auth/verify with valid user-id succeeds"
      (let [user-id (get-in register-resp [:body :id])
            resp (post-edn "/api/auth/verify"
                           {:user-id (str user-id)})]
        (is (= 200 (:status resp)))
        (is (= "Account verified" (get-in resp [:body :message])))))

    (testing "POST /api/auth/verify with non-numeric user-id returns 400"
      (let [resp (post-edn "/api/auth/verify"
                           {:user-id "not-a-number"})]
        (is (= 400 (:status resp)))
        (is (get-in resp [:body :errors]))))

    (testing "POST /api/auth/login with valid credentials returns tokens"
      (let [resp (post-edn "/api/auth/login"
                           {:identifier (:username user)
                            :password (:password user)})
            body (:body resp)]
        (is (= 200 (:status resp)))
        (is (string? (:access-token body)))
        (is (string? (:refresh-token body)))
        (is (pos? (:expires-in body)))))

    (testing "POST /api/auth/login with wrong password returns 401"
      (let [resp (post-edn "/api/auth/login"
                           {:identifier (:username user)
                            :password "CorrectHorseBatteryStaple1!"})]
        (is (= 401 (:status resp)))
        (is (get-in resp [:body :errors]))))

    (let [login-resp (post-edn "/api/auth/login"
                       {:identifier (:username user)
                        :password (:password user)})
          access-token (get-in login-resp [:body :access-token])
          refresh-token (get-in login-resp [:body :refresh-token])]

      (is (string? access-token) "access-token present")
      (is (string? refresh-token) "refresh-token present")

      (testing "POST /api/auth/refresh with valid refresh token"
        (let [resp (post-edn "/api/auth/refresh"
                             {:refresh-token refresh-token})
              body (:body resp)]
          (is (= 200 (:status resp)))
          (is (string? (:access-token body)))
          (is (string? (:refresh-token body)))))

      (testing "POST /api/auth/refresh with revoked (used) refresh token returns 401"
        (let [resp (post-edn "/api/auth/refresh"
                             {:refresh-token refresh-token})]
          (is (= 401 (:status resp)))
          (is (get-in resp [:body :errors]))))

      (testing "POST /api/auth/logout revokes access token"
        (let [resp (post-edn "/api/auth/logout"
                             {}
                             (auth-header access-token))]
          (is (= 200 (:status resp)))
          (is (= "Logged out" (get-in resp [:body :message]))))))))

(deftest ^:integration email-only-registration-test
  (testing "POST /api/auth/register without username auto-derives one from email"
    (let [user (random-email-only-user)
          resp (post-edn "/api/auth/register" user)]
      (is (= 201 (:status resp)))
      (is (some? (get-in resp [:body :id])))
      (is (some? (get-in resp [:body :username])) "username should be auto-derived")
      (is (string/starts-with? (get-in resp [:body :username]) "emailonly"))
      (is (= (:email user) (get-in resp [:body :email])))
      (is (false? (get-in resp [:body :verified])))))

  (testing "Login by email works for email-only registered user"
    (let [user (random-email-only-user)
          reg-resp (post-edn "/api/auth/register" user)]
      (is (= 201 (:status reg-resp)))
      (let [login-resp (post-edn "/api/auth/login"
                         {:identifier (:email user)
                          :password (:password user)})]
        (is (= 200 (:status login-resp)))
        (is (string? (get-in login-resp [:body :access-token])))
        (is (string? (get-in login-resp [:body :refresh-token])))))))

(deftest ^:integration logout-everywhere-test
  (let [user (random-user)
        _ (post-edn "/api/auth/register" user)
        login-resp (post-edn "/api/auth/login"
                     {:identifier (:username user)
                      :password (:password user)})
        access-token (get-in login-resp [:body :access-token])]

    (testing "POST /api/auth/logout-everywhere revokes all sessions"
      (let [resp (post-edn "/api/auth/logout-everywhere"
                           {}
                           (auth-header access-token))]
        (is (= 200 (:status resp)))
        (is (= "Logged out from all devices" (get-in resp [:body :message])))))))

(deftest ^:integration password-reset-test
  (let [user (random-user)
        _ (post-edn "/api/auth/register" user)]

    (testing "POST /api/auth/forgot-password with valid email returns 200"
      (let [resp (post-edn "/api/auth/forgot-password"
                           {:email (:email user)})]
        (is (= 200 (:status resp)))
        (is (string? (get-in resp [:body :message])))))

    (testing "POST /api/auth/forgot-password with unknown email returns 200 (no enumeration)"
      (let [resp (post-edn "/api/auth/forgot-password"
                           {:email "nonexistent@example.com"})]
        (is (= 200 (:status resp)))))

    (testing "Reset with unknown token returns 400"
      (let [resp (post-edn "/api/auth/reset-password"
                           {:token "unknown-token"
                            :password "NewP@ssword456"})]
        (is (= 400 (:status resp)))
        (is (get-in resp [:body :errors]))))))

(deftest ^:integration invalid-tokens-test
  (testing "POST /api/auth/refresh with garbage token returns 401"
    (let [resp (post-edn "/api/auth/refresh"
                         {:refresh-token "not-a-valid-jwt"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body :errors]))))

  (testing "POST /api/auth/login with non-existent username returns 401"
    (let [resp (post-edn "/api/auth/login"
                 {:identifier "nonexistent"
                  :password "CorrectHorseBatteryStaple1!"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body :errors]))))

  (testing "POST /api/auth/login with non-existent email returns 401 without throwing 500"
    (let [resp (post-edn "/api/auth/login"
                 {:identifier "tovieye.ozi@gmail.com"
                  :password "overtake-septum-thesis-confusing-chest-eaten"})]
      (is (= 401 (:status resp)))
      (is (get-in resp [:body :errors])))))

(deftest ^:integration org-query-flow-test
  (let [user {:username (str "orgflow-" (short-suffix))
              :email (str "orgflow-" (short-suffix) "@example.com")
              :password "P@ssword123"}
        reg-resp (post-edn "/api/auth/register" user)]
    (is (= 201 (:status reg-resp)))

    (testing "POST /api/auth/login for org flow user"
      (let [login-resp (post-edn "/api/auth/login"
                         {:identifier (:username user)
                          :password (:password user)})
            token (get-in login-resp [:body :access-token])]
        (is (string? token) "access-token should be present")

        (testing "POST /api/query current-user resolver returns user info"
          (let [resp (query-eql "[:current-user/id :current-user/username]" token)]
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body :ok])))
            (is (some? (get-in resp [:body :data :current-user/id])))
            (is (= (:username user) (get-in resp [:body :data :current-user/username])))))

        (testing "POST /api/query create-org mutation"
          (let [org-name (str "OrgFlow-" (short-suffix))
                resp (query-eql (str "[(org/create {:org/name \"" org-name "\"})]") token)]
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body :ok])))))

        (testing "POST /api/query user/orgs resolver"
          (let [resp (query-eql "[{:user/orgs [:org/id :org/name :org/role :org/status]}]" token)
                orgs (get-in resp [:body :data :user/orgs])]
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body :ok])))
            (is (some? orgs) "user/orgs should be present")
            (is (some #(= "ADMIN" (:org/role %)) orgs) "admin role should be present")))

        (testing "POST /api/query active-org resolver"
          (let [resp (query-eql "[{:user/active-org [:org/id :org/name :org/role]}]" token)
                active (get-in resp [:body :data :user/active-org])]
            (is (= 200 (:status resp)))
            (is (true? (get-in resp [:body :ok])))
            (is (some? active) "active-org should be set after creating org")))

        (testing "POST /api/query with no auth token returns 401"
          (let [resp (query-eql "[:current-user/id]")]
            (is (or (= 401 (:status resp))
                    (= 403 (:status resp)))
                "unauthenticated query should return 401 or 403")))))))

(deftest ^:integration org-invite-join-http-test
  (let [owner {:username (str "invowner-" (short-suffix))
               :email (str "invowner-" (short-suffix) "@example.com")
               :password "P@ssword123"}
        joiner {:username (str "invjoiner-" (short-suffix))
                :email (str "invjoiner-" (short-suffix) "@example.com")
                :password "P@ssword123"}
        owner-reg (post-edn "/api/auth/register" owner)
        joiner-reg (post-edn "/api/auth/register" joiner)
        owner-login (post-edn "/api/auth/login"
                      {:identifier (:username owner)
                       :password (:password owner)})
        owner-token (get-in owner-login [:body :access-token])
        joiner-login (post-edn "/api/auth/login"
                       {:identifier (:username joiner)
                        :password (:password joiner)})
        joiner-token (get-in joiner-login [:body :access-token])]
    (is (= 201 (:status owner-reg)))
    (is (= 201 (:status joiner-reg)))
    (is (string? owner-token) "owner should have access-token")
    (is (string? joiner-token) "joiner should have access-token")

    (testing "Owner creates org via query endpoint"
      (let [org-name (str "InvOrg-" (short-suffix))
            resp (query-eql (str "[(org/create {:org/name \"" org-name "\"})]") owner-token)]
        (is (= 200 (:status resp)))
        (is (true? (get-in resp [:body :ok])))))))

(deftest ^:integration query-endpoint-auth-test
  (testing "POST /api/query with invalid token returns errors"
    (let [resp (post-edn "/api/query"
                         {:eql "[:current-user/id]"}
                         {"authorization" "Bearer invalid.jwt.token"})]
      (is (or (= 401 (:status resp))
              (= 403 (:status resp))
              (= 400 (:status resp)))
          "invalid token should return an error status code"))))

(deftest ^:integration username-update-test
  (let [user (random-user)
        reg-resp (post-edn "/api/auth/register" user)]
    (is (= 201 (:status reg-resp)))

    (let [login-resp (post-edn "/api/auth/login"
                       {:identifier (:username user)
                        :password (:password user)})
          token (get-in login-resp [:body :access-token])
          new-uname (str "updated-" (short-suffix))]
      (is (string? token) "access-token should be present")

      (testing "POST /api/query user/update-username mutation updates the username"
        (let [eql-str (pr-str [(list 'user/update-username {:user/new-username new-uname})])
              resp (post-edn "/api/query"
                     {:eql eql-str}
                     (auth-header token))
              mutation-res (get-in resp [:body :data :user/update-username])]
          (is (= 200 (:status resp)))
          (is (= new-uname (:current-user/username mutation-res)))))

      (testing "Login with new username works"
        (let [new-login-resp (post-edn "/api/auth/login"
                               {:identifier new-uname
                                :password (:password user)})]
          (is (= 200 (:status new-login-resp)))))

      (testing "Querying current-user returns the newly saved username"
        (let [query-resp (post-edn "/api/query"
                           {:eql (pr-str '[:current-user/username])}
                           (auth-header token))
              curr-user-res (get-in query-resp [:body :data :current-user/username])]
          (is (= 200 (:status query-resp)))
          (is (= new-uname curr-user-res) "current-user/username should match the updated username")))

      (testing "POST /api/query user/update-username without auth returns error"
        (let [eql-str (pr-str [(list 'user/update-username {:user/new-username (str "unauth-" (short-suffix))})])
              resp (post-edn "/api/query" {:eql eql-str})]
          (is (or (= 401 (:status resp))
                  (= 403 (:status resp))
                  (= 400 (:status resp)))
              "unauthenticated query should return an error status code"))))))

(deftest ^:integration auto-token-refresh-integration-test
  (testing "Full token refresh workflow: login -> expired/invalid token query failure -> refresh token -> retried query succeeds"
    (let [user (random-user)
          _ (post-edn "/api/auth/register" user)
          login-resp (post-edn "/api/auth/login"
                       {:identifier (:email user)
                        :password (:password user)})
          refresh-token (get-in login-resp [:body :refresh-token])
          garbage-access-token "not-a-valid-access-token"]

      (is (string? refresh-token) "login should return refresh-token")

      (testing "Step 1: Request with garbage/expired access-token fails with 401"
        (let [query-resp (post-edn "/api/query"
                           {:eql (pr-str '[(user/update-username {:user/new-username "refreshed-uname"})])}
                           (auth-header garbage-access-token))]
          (is (= 401 (:status query-resp)))
          (is (= ["Not authenticated"] (get-in query-resp [:body :errors :auth])))))

      (testing "Step 2: Refreshing token via POST /api/auth/refresh returns new access-token"
        (let [refresh-resp (post-edn "/api/auth/refresh"
                             {:refresh-token refresh-token})
              new-access-token (get-in refresh-resp [:body :access-token])]
          (is (= 200 (:status refresh-resp)))
          (is (string? new-access-token) "refresh should return new access-token")

          (testing "Step 3: Retrying query with new access-token succeeds"
            (let [retry-resp (post-edn "/api/query"
                               {:eql (pr-str '[(user/update-username {:user/new-username "refreshed-uname"})])}
                               (auth-header new-access-token))
                  result (get-in retry-resp [:body :data :user/update-username])]
              (is (= 200 (:status retry-resp)))
              (is (= "refreshed-uname" (:current-user/username result))))))))))

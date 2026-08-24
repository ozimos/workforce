(ns com.ozimos.workforce.web.integration-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.mfa.interface :as mfa]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.web.test-system :as ts]
   [com.rpl.rama.ops :as ops]
   [hato.client :as http]
   [jsonista.core :as json]))

(def ^:dynamic *sys* nil)
(def ^:dynamic *base-url* nil)

(defn- get-user-store [sys]
  (or (:com.ozimos.workforce.user/store sys)
      (:user-store sys)
      sys))

(defn- wait-for-mfa-enabled [sys user-id]
  (let [store (get-user-store sys)]
    (loop [retries 30]
      (if (user/mfa-enabled? store user-id)
        true
        (when (> retries 0)
          (Thread/sleep 50)
          (recur (dec retries)))))))

(defn system-fixture [tests]
  (let [sys (ts/get-sys)]
    (binding [*sys* sys
              *base-url* (ts/get-base-url sys)]
      (tests))))

(use-fixtures :once system-fixture)

(defn base-url []
  *base-url*)

(defn- short-suffix []
  (subs (clojure.string/replace (str (ops/random-uuid7)) "-" "") 16 32))

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
  (let [b (:body resp)]
    (cond
      (instance? java.io.InputStream b)
      (let [s (slurp b)]
        (assoc resp :body (when (and (string? s) (seq s)) (edn/read-string s))))
      (bytes? b)
      (let [s (String. ^bytes b "UTF-8")]
        (assoc resp :body (when (and (string? s) (seq s)) (edn/read-string s))))
      (string? b)
      (assoc resp :body (when (seq b) (edn/read-string b)))
      :else
      resp)))

(defn- post-edn
  "Executes an in-memory Ring request against (:com.ozimos.workforce.web.system/router *sys*) using EDN format negotiation.
   Returns the response map with parsed native Clojure data in `:body`."
  ([uri body-params]
   (post-edn uri body-params {}))
  ([uri body-params headers]
   (let [handler (:com.ozimos.workforce.web.system/router *sys*)
         body-bytes (.getBytes (pr-str (or body-params {})) "UTF-8")
         req {:request-method :post
              :uri uri
              :headers (merge {"content-type" "application/edn"
                               "accept" "application/edn"}
                              headers)
              :body (java.io.ByteArrayInputStream. body-bytes)
              :body-params body-params}
         resp (handler req)]
     (parse-ring-response resp))))

(defn- get-edn
  "Executes an in-memory Ring GET request against (:com.ozimos.workforce.web.system/router *sys*) using EDN format negotiation."
  ([uri]
   (get-edn uri {}))
  ([uri headers]
   (let [handler (:com.ozimos.workforce.web.system/router *sys*)
         req {:request-method :get
              :uri uri
              :headers (merge {"accept" "application/edn"}
                              headers)}
         resp (handler req)]
     (parse-ring-response resp))))

(defn- delete-edn
  "Executes an in-memory Ring DELETE request against (:com.ozimos.workforce.web.system/router *sys*) using EDN format negotiation."
  ([uri]
   (delete-edn uri {}))
  ([uri headers]
   (let [handler (:com.ozimos.workforce.web.system/router *sys*)
         req {:request-method :delete
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
          new-uname (str "ref-" (short-suffix))
          _ (post-edn "/api/auth/register" user)
          login-resp (post-edn "/api/auth/login"
                       {:identifier (:email user)
                        :password (:password user)})
          refresh-token (get-in login-resp [:body :refresh-token])
          garbage-access-token "not-a-valid-access-token"]

      (is (string? refresh-token) "login should return refresh-token")

      (testing "Step 1: Request with garbage/expired access-token fails with 401"
        (let [query-resp (post-edn "/api/query"
                           {:eql (pr-str [(list 'user/update-username {:user/new-username new-uname})])}
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
                               {:eql (pr-str [(list 'user/update-username {:user/new-username new-uname})])}
                               (auth-header new-access-token))
                  result (get-in retry-resp [:body :data :user/update-username])]
              (is (= 200 (:status retry-resp)))
              (is (= new-uname (:current-user/username result))))))))))

(deftest ^:integration totp-mfa-integration-test
  (testing "Full TOTP MFA lifecycle: setup -> verify-setup -> login step-up -> 2FA challenge verify -> backup code fallback -> disable"
    (let [user (random-user)
          _ (post-edn "/api/auth/register" user)
          login-resp1 (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
          token1 (get-in login-resp1 [:body :access-token])]
      (is (string? token1) "initial login returns access-token")

      (testing "Step 1: POST /api/auth/mfa/setup returns secret, QR URL, and backup codes"
        (let [setup-resp (post-edn "/api/auth/mfa/setup" {} (auth-header token1))
              body (:body setup-resp)
              secret (:secret body)
              backup-codes (:backup-codes body)]
          (is (= 200 (:status setup-resp)))
          (is (string? secret))
          (is (string/includes? (:otpauth-url body) "otpauth://totp/"))
          (is (= 10 (count backup-codes)))
          (Thread/sleep 100)

          (testing "Step 2: POST /api/auth/mfa/verify-setup with invalid code fails"
            (let [bad-verify (post-edn "/api/auth/mfa/verify-setup" {:code "000000"} (auth-header token1))]
              (is (= 400 (:status bad-verify)))))

          (testing "Step 3: POST /api/auth/mfa/verify-setup with valid code enables MFA"
            (let [curr-step (quot (quot (System/currentTimeMillis) 1000) 30)
                  valid-code (mfa/calculate-totp secret curr-step)
                  good-verify (post-edn "/api/auth/mfa/verify-setup" {:code valid-code} (auth-header token1))
                  user-rec (user/find-by-identifier (get-user-store *sys*) (:username user))]
              (is (= 200 (:status good-verify)))
              (is (= "MFA enabled successfully" (get-in good-verify [:body :message])))
              (wait-for-mfa-enabled *sys* (:id user-rec))))

          (testing "Step 4: Subsequent POST /api/auth/login triggers 2FA step-up challenge"
            (let [login-resp2 (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
                  body2 (:body login-resp2)
                  mfa-token (:mfa-token body2)]
              (is (= 200 (:status login-resp2)))
              (is (true? (:mfa-required body2)))
              (is (string? mfa-token))

              (testing "Step 5: POST /api/auth/mfa/login with wrong code returns 401"
                (let [bad-login (post-edn "/api/auth/mfa/login" {:mfa-token mfa-token :code "123456"})]
                  (is (= 401 (:status bad-login)))))

              (testing "Step 6: POST /api/auth/mfa/login with valid TOTP code completes login"
                (let [curr-step (quot (quot (System/currentTimeMillis) 1000) 30)
                      valid-code (mfa/calculate-totp secret curr-step)
                      good-mfa-login (post-edn "/api/auth/mfa/login" {:mfa-token mfa-token :code valid-code})
                      body3 (:body good-mfa-login)]
                  (is (= 200 (:status good-mfa-login)))
                  (is (string? (:access-token body3)))
                  (is (string? (:refresh-token body3)))))

              (testing "Step 7: Login using a single-use backup code"
                (let [login-resp3 (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
                      mfa-token3 (get-in login-resp3 [:body :mfa-token])
                      backup-code (first backup-codes)
                      backup-login (post-edn "/api/auth/mfa/login" {:mfa-token mfa-token3 :code backup-code})]
                  (is (= 200 (:status backup-login)))
                  (is (string? (get-in backup-login [:body :access-token])))))

              (testing "Step 8: Re-using the same backup code fails"
                (let [login-resp4 (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
                      mfa-token4 (get-in login-resp4 [:body :mfa-token])
                      backup-code (first backup-codes)
                      reuse-login (post-edn "/api/auth/mfa/login" {:mfa-token mfa-token4 :code backup-code})]
                  (is (= 401 (:status reuse-login)))))

              (testing "Step 9: Disable MFA"
                (let [curr-step (quot (quot (System/currentTimeMillis) 1000) 30)
                      valid-code (mfa/calculate-totp secret curr-step)
                      disable-resp (post-edn "/api/auth/mfa/disable" {:code valid-code} (auth-header token1))]
                  (is (= 200 (:status disable-resp)))
                  (is (= "MFA disabled successfully" (get-in disable-resp [:body :message])))))

              (testing "Step 10: Regular login works directly after disabling MFA"
                (let [normal-login (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})]
                  (is (= 200 (:status normal-login)))
                  (is (string? (get-in normal-login [:body :access-token]))))))))))))

(deftest ^:integration webauthn-integration-test
  (testing "WebAuthn / Passkeys lifecycle: register begin -> authenticate begin -> passkeys list -> delete"
    (let [user (random-user)
          _ (post-edn "/api/auth/register" user)
          login-resp (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
          token (get-in login-resp [:body :access-token])]
      (is (string? token))

      (testing "Step 1: POST /api/auth/passkeys/register/begin returns challenge options"
        (let [reg-begin (post-edn "/api/auth/passkeys/register/begin" {} (auth-header token))
              body (:body reg-begin)]
          (is (= 200 (:status reg-begin)))
          (is (some? (or (get-in body [:options :challenge])
                         (get-in body [:options :publicKey :challenge]))))
          (is (string? (:options-json body)))))

      (testing "Step 2: POST /api/auth/passkeys/authenticate/begin returns assertion options"
        (let [auth-begin (post-edn "/api/auth/passkeys/authenticate/begin" {})
              body (:body auth-begin)]
          (is (= 200 (:status auth-begin)))
          (is (some? (or (get-in body [:request :challenge])
                         (get-in body [:request :publicKey :challenge]))))
          (is (string? (:request-json body)))))

      (testing "Step 3: GET /api/auth/passkeys lists registered passkeys"
        (let [list-resp (get-edn "/api/auth/passkeys" (auth-header token))]
          (is (= 200 (:status list-resp)))
          (is (vector? (get-in list-resp [:body :passkeys])))))

      (testing "Step 4: DELETE /api/auth/passkeys/:id removes passkey"
        (let [del-resp (delete-edn "/api/auth/passkeys/nonexistent-id" (auth-header token))]
          (is (= 200 (:status del-resp))))))))

(deftest ^:integration mfa-backup-codes-http-test
  (testing "MFA Backup Codes status and regeneration HTTP flow"
    (let [user (random-user)
          _ (post-edn "/api/auth/register" user)
          login-resp (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
          token (get-in login-resp [:body :access-token])

          ;; Setup MFA
          setup-resp (post-edn "/api/auth/mfa/setup" {} (auth-header token))
          _ (Thread/sleep 100)
          secret (get-in setup-resp [:body :secret])
          backup-codes (get-in setup-resp [:body :backup-codes])
          curr-step (quot (quot (System/currentTimeMillis) 1000) 30)
          totp (mfa/calculate-totp secret curr-step)
          _ (post-edn "/api/auth/mfa/verify-setup" {:code totp} (auth-header token))
          user-rec (user/find-by-identifier (get-user-store *sys*) (:username user))
          _ (wait-for-mfa-enabled *sys* (:id user-rec))]

      (testing "Step 1: GET /api/auth/mfa/backup-codes returns 10 initial codes count"
        (let [status-resp (get-edn "/api/auth/mfa/backup-codes" (auth-header token))]
          (is (= 200 (:status status-resp)))
          (is (= 10 (get-in status-resp [:body :remaining])))))

      (testing "Step 2: Consume 1 backup code via 2FA login -> count decreases to 9"
        (let [login-resp2 (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
              mfa-token (get-in login-resp2 [:body :mfa-token])
              code (first backup-codes)
              mfa-login (post-edn "/api/auth/mfa/login" {:mfa-token mfa-token :code code})
              _ (is (= 200 (:status mfa-login)))
              _ (Thread/sleep 100)
              status-resp2 (get-edn "/api/auth/mfa/backup-codes" (auth-header token))]
          (is (= 200 (:status status-resp2)))
          (is (= 9 (get-in status-resp2 [:body :remaining])))))

      (testing "Step 3: Regenerate backup codes with valid TOTP code"
        (let [new-step (quot (quot (System/currentTimeMillis) 1000) 30)
              new-totp (mfa/calculate-totp secret new-step)
              regen-resp (post-edn "/api/auth/mfa/backup-codes" {:code new-totp} (auth-header token))
              _ (Thread/sleep 100)
              new-codes (get-in regen-resp [:body :backup-codes])]
          (is (= 200 (:status regen-resp)))
          (is (= 10 (count new-codes)))
          (is (not= backup-codes new-codes))
          (let [status-resp3 (get-edn "/api/auth/mfa/backup-codes" (auth-header token))]
            (is (= 10 (get-in status-resp3 [:body :remaining])))))))))

(deftest ^:integration eql-endpoint-and-idempotency-http-test
  (testing "Pathom 3 /api/eql endpoint: queries, mutations, idempotency-key header, and structured errors"
    (let [user (random-user)
          _ (post-edn "/api/auth/register" user)
          login-resp (post-edn "/api/auth/login" {:identifier (:username user) :password (:password user)})
          token (get-in login-resp [:body :access-token])]
      (is (string? token))

      (testing "Step 1: Unauthenticated request to /api/eql returns 401 with structured error"
        (let [unauth-resp (post-edn "/api/eql" {:eql [{:user/orgs [:org/id]}]})]
          (is (= 401 (:status unauth-resp)))
          (is (= false (get-in unauth-resp [:body :ok])))
          (is (= :unauthorized (get-in unauth-resp [:body :error :error-code])))))

      (testing "Step 2: Authenticated EQL query to /api/eql returns 200 with data"
        (let [query-resp (post-edn "/api/eql" {:eql [{:user/orgs [:org/id :org/name]}]} (auth-header token))
              body (:body query-resp)]
          (is (= 200 (:status query-resp)))
          (is (= true (:ok body)))
          (is (vector? (get-in body [:data :user/orgs])))))

      (testing "Step 3: Authenticated EQL mutation with Idempotency-Key header"
        (let [org-name (str "HttpEqlOrg-" (short-suffix))
              mut-resp (post-edn "/api/eql"
                                 {:eql [(list 'org/create {:org/name org-name})]}
                                 (merge (auth-header token)
                                        {"idempotency-key" (str "idem-http-" (short-suffix))}))
              body (:body mut-resp)]
          (is (= 200 (:status mut-resp)))
          (is (= true (:ok body)))
          (let [org-data (first (vals (get body :data)))]
            (is (some? (:org/id org-data)))
            (is (= "ADMIN" (:org/role org-data)))))))))

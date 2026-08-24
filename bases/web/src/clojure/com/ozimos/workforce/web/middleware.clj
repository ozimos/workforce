(ns com.ozimos.workforce.web.middleware
  (:require
   [com.ozimos.workforce.org.interface :as org]))

(defn wrap-authenticated
  "Middleware that checks the request is authenticated via Buddy."
  [_deps handler]
  (fn [request]
    (let [auth-user (or (:identity request) (:auth-user request))]
      (if (nil? auth-user)
        {:status 401 :body {:ok false :error {:error-code :unauthorized :message "Not authenticated"}}}
        (handler (assoc request :auth-user auth-user))))))

(defn wrap-idempotency-key
  "Middleware that extracts Idempotency-Key header (case-insensitive) and attaches it to request."
  [handler]
  (fn [request]
    (let [header-key (or (get-in request [:headers "idempotency-key"])
                         (get-in request [:headers "Idempotency-Key"])
                         (get-in request [:headers :idempotency-key]))]
      (handler (cond-> request
                 header-key (assoc :idempotency-key header-key))))))

(defn wrap-enrich-auth-context
  "Middleware that enriches authenticated request with active org, role, and membership."
  [system handler]
  (fn [request]
    (let [identity (or (:identity request) (:auth-user request))]
      (if identity
        (let [raw-id (or (:sub identity) (:id identity) (:user-id identity))
              user-id (when raw-id
                        (try (Long/parseLong (str raw-id)) (catch Exception _ nil)))
              active-org-id (when user-id (org/get-active-org system user-id))
              membership (when (and user-id active-org-id)
                           (org/get-membership system user-id active-org-id))
              auth-context {:user-id user-id
                            :active-org-id active-org-id
                            :role (keyword (or (:role membership) "employee"))
                            :membership membership}]
          (handler (assoc request :auth-context auth-context)))
        (handler request)))))

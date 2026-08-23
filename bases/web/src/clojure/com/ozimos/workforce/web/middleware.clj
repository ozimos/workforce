(ns com.ozimos.workforce.web.middleware)

(defn wrap-authenticated
  "Middleware that checks the request is authenticated via Buddy."
  [_deps handler]
  (fn [request]
    (let [auth-user (or (:identity request) (:auth-user request))]
      (if (nil? auth-user)
        {:status 401 :body {:errors {:auth ["Not authenticated"]}}}
        (handler (assoc request :auth-user auth-user))))))

(ns com.ozimos.workforce.web.middleware
  (:import
   (org.springframework.security.core.context SecurityContextHolder)))

(defn wrap-authenticated
  "Middleware that checks the request is authenticated via Spring Security.
   The SecurityContext is set by the filter chain before the Ring handler runs."
  [_deps handler]
  (fn [request]
    (let [ctx (SecurityContextHolder/getContext)
          auth (.getAuthentication ctx)]
      (if (or (nil? auth) (not (.isAuthenticated auth)))
        {:status 401 :body {:errors {:auth ["Not authenticated"]}}}
        (handler request)))))

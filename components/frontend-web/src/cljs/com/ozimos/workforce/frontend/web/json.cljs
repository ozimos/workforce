(ns com.ozimos.workforce.frontend.web.json)

(defn parse [s]
  (when s
    (try (js->clj (js/JSON.parse s) :keywordize-keys true)
         (catch js/Error _ nil))))

(defn generate [data]
  (js/JSON.stringify (clj->js data)))

(defonce ^:private refresh-promise* (atom nil))

(defn- clear-tokens! []
  (when (exists? js/localStorage)
    (.removeItem js/localStorage "access-token")
    (.removeItem js/localStorage "refresh-token")))

(defn- save-tokens! [{:keys [access-token refresh-token]}]
  (when (and (exists? js/localStorage) access-token refresh-token)
    (.setItem js/localStorage "access-token" access-token)
    (.setItem js/localStorage "refresh-token" refresh-token)))

(defn refresh-tokens!
  "Attempts to refresh the access-token using the refresh-token in localStorage.
   Deduplicates concurrent calls using a shared promise atom."
  []
  (if-let [existing @refresh-promise*]
    existing
    (let [refresh-token (when (exists? js/localStorage) (.getItem js/localStorage "refresh-token"))]
      (if-not refresh-token
        (do
          (clear-tokens!)
          (js/Promise.resolve false))
        (let [opts (clj->js {:method "POST"
                             :headers {"Content-Type" "application/json"}
                             :body (generate {:refresh-token refresh-token})})
              promise (-> (js/fetch "/api/auth/refresh" opts)
                          (.then (fn [resp]
                                   (if-not (.-ok resp)
                                     (do
                                       (clear-tokens!)
                                       false)
                                     (-> (.json resp)
                                         (.then (fn [parsed]
                                                  (let [body (js->clj parsed :keywordize-keys true)]
                                                    (if (and (:access-token body) (:refresh-token body))
                                                      (do
                                                        (save-tokens! body)
                                                        true)
                                                      (do
                                                        (clear-tokens!)
                                                        false)))))))))
                          (.catch (fn [_]
                                    (clear-tokens!)
                                    false))
                          (.finally (fn []
                                      (reset! refresh-promise* nil))))]
          (reset! refresh-promise* promise)
          promise)))))

(defn- unauthenticated-response? [result]
  (or (= 401 (:status result))
      (= ["Not authenticated"] (get-in result [:body :errors :auth]))))

(defn raw-fetch-json
  [url method body headers]
  (let [opts (clj->js {:method method
                       :headers headers})]
    (when body
      (set! (.-body opts) (generate body)))
    (-> (js/fetch url opts)
        (.then (fn [resp]
                 (-> (.json resp)
                     (.then (fn [parsed]
                              {:status (.-status resp)
                               :ok (.-ok resp)
                               :body (js->clj parsed :keywordize-keys true)}))))))))

(defn fetch-json
  ([url] (js/fetch url))
  ([url method body]
   (fetch-json url method body {"Content-Type" "application/json"}))
  ([url method body headers]
   (let [token (when (exists? js/localStorage) (.getItem js/localStorage "access-token"))
         final-headers (cond-> (or headers {"Content-Type" "application/json"})
                         (and token (not (get headers "Authorization")))
                         (assoc "Authorization" (str "Bearer " token)))]
     (-> (raw-fetch-json url method body final-headers)
         (.then (fn [result]
                  (if (unauthenticated-response? result)
                    (-> (refresh-tokens!)
                        (.then (fn [refreshed?]
                                 (if refreshed?
                                   (let [new-token (.getItem js/localStorage "access-token")
                                         retried-headers (assoc final-headers "Authorization" (str "Bearer " new-token))]
                                     (raw-fetch-json url method body retried-headers))
                                   (do
                                     (when (and (exists? js/window)
                                                (not= (.-pathname js/window.location) "/login")
                                                (not= (.-pathname js/window.location) "/register"))
                                       (set! js/window.location.pathname "/login"))
                                     result)))))
                    result)))))))

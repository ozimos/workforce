(ns com.ozimos.auth.frontend.transit
  (:require
   [cognitect.transit :as t]
   [com.ozimos.auth.frontend.json :as json]))

(def ^:private r (t/reader :json))
(def ^:private w (t/writer :json))

(defn read-str [s]
  (t/read r s))

(defn write-str [data]
  (t/write w data))

(defn raw-fetch-transit
  [url method body headers]
  (let [opts (clj->js {:method method
                       :headers headers})]
    (when body
      (set! (.-body opts) (write-str body)))
    (-> (js/fetch url opts)
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              {:status (.-status resp)
                               :ok (.-ok resp)
                               :body (try (read-str text)
                                          (catch js/Error _ text))}))))))))

(defn fetch-transit
  "Fetches an EQL endpoint with application/transit+json format.
   Automatically injects Authorization header and handles token refresh."
  [url eql-query]
  (let [token (when (exists? js/localStorage) (.getItem js/localStorage "access-token"))
        headers (cond-> {"Content-Type" "application/transit+json"
                         "Accept" "application/transit+json"}
                  token (assoc "Authorization" (str "Bearer " token)))]
    (-> (raw-fetch-transit url "POST" eql-query headers)
        (.then (fn [result]
                 (if (or (= 401 (:status result))
                         (= ["Not authenticated"] (get-in result [:errors :auth])))
                   (-> (json/refresh-tokens!)
                       (.then (fn [refreshed?]
                                (if refreshed?
                                  (let [new-token (.getItem js/localStorage "access-token")
                                        retried-headers (assoc headers "Authorization" (str "Bearer " new-token))]
                                    (raw-fetch-transit url "POST" eql-query retried-headers))
                                  (do
                                    (when (and (exists? js/window)
                                               (not= (.-pathname js/window.location) "/login")
                                               (not= (.-pathname js/window.location) "/register"))
                                      (set! js/window.location.pathname "/login"))
                                    result)))))
                   result))))))

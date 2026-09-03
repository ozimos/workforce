(ns com.ozimos.workforce.frontend.transit
  (:require
   [cognitect.transit :as t]
   [com.ozimos.workforce.frontend.json :as json]))

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
                              (let [parsed (try (read-str text) (catch js/Error _ text))
                                    data (if (and (map? parsed) (contains? parsed :data))
                                           (:data parsed)
                                           parsed)]
                                {:status (.-status resp)
                                 :ok (.-ok resp)
                                 :body data})))))))))


;; Registered at startup by core.cljs to break the circular dependency:
;; transit.cljs <- core.cljs (core requires transit, so transit can't require core).
(defonce ^:private on-auth-failure-fn (atom nil))

(defn register-auth-failure-handler!
  "Register a zero-arg callback invoked whenever an unauthenticated 401 is
   encountered and token refresh fails. Call from core.cljs during init."
  [f]
  (reset! on-auth-failure-fn f))

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
                                    ;; Notify the auth statechart instead of hard-navigating.
                                    (when-let [f @on-auth-failure-fn]
                                      (f))
                                    result)))))
                   result))))))

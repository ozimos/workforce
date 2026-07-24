(ns com.ozimos.auth.frontend.json)

(defn parse [s]
  (when s
    (try (js->clj (js/JSON.parse s) :keywordize-keys true)
         (catch js/Error _ nil))))

(defn generate [data]
  (js/JSON.stringify (clj->js data)))

(defn fetch-json
  ([url] (js/fetch url))
  ([url method body]
   (let [opts (clj->js {:method method
                        :headers {"Content-Type" "application/json"}})]
     (when body
       (set! (.-body opts) (generate body)))
     (-> (js/fetch url opts)
         (.then (fn [resp]
                  (-> (.json resp)
                      (.then (fn [parsed]
                               {:status (.-status resp)
                                :ok (.-ok resp)
                                :body (js->clj parsed :keywordize-keys true)})))))))))

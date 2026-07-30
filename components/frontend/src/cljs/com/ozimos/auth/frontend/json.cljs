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
   (fetch-json url method body {"Content-Type" "application/json"}))
  ([url method body headers]
   (let [opts (clj->js {:method method
                        :headers headers})]
     (when body
       (set! (.-body opts) (generate body)))
     (-> (js/fetch url opts)
         (.then (fn [resp]
                  (-> (.json resp)
                      (.then (fn [parsed]
                               (let [result {:status (.-status resp)
                                             :ok (.-ok resp)
                                             :body (js->clj parsed :keywordize-keys true)}]
                                 result))))))))))

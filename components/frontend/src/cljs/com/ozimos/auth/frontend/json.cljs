(ns com.ozimos.auth.frontend.json)

(defn parse [s]
  (when s
    (try (js->clj (js/JSON.parse s) :keywordize-keys true)
         (catch js/Error _ nil))))

(defn generate [data]
  (js/JSON.stringify (clj->js data)))

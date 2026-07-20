(ns com.ozimos.auth.frontend.ui.pages.verify
  (:require [goog.net.XhrIo :as xhr]
            [com.ozimos.auth.frontend.json :as json]))

(defn- get-param [key]
  (some-> js/window.location.hash
          (.split "?") (aget 1)
          (.split "&")
          (->> (map #(.split % "="))
               (filter #(= (first %) key)) first second)))

(defn ui-verify
  []
  (let [init (volatile! false)
        status (volatile! :loading)
        message (volatile! nil)]
    (fn []
      (when (and (= :loading @status) (not @init))
        (vreset! init true)
        (let [token (get-param "token")
              user-id (get-param "user-id")]
          (if (and token user-id)
            (let [x (xhr/XhrIo.)]
              (.send x "/api/auth/verify" "POST"
                     (json/generate {:token token :user-id user-id})
                     (fn [e]
                       (let [s (.. e -target getStatus)]
                         (if (= 200 s)
                           (do (vreset! status :success)
                               (vreset! message "Account verified!"))
                           (do (vreset! status :error)
                               (vreset! message "Verification failed")))))))
            (do (vreset! status :error)
                (vreset! message "Missing verification token")))))
      [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
       [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
        (case @status
          :loading [:div {:class "text-center"} [:p {:class "text-gray-500"} "Verifying your account..."]]
          :success [:div {:class "rounded-md bg-green-50 p-4"}
                    [:p {:class "text-sm text-green-700"} @message]
                    [:a {:href "#!/login" :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Sign in"]]
          :error [:div {:class "rounded-md bg-red-50 p-4"}
                  [:p {:class "text-sm text-red-700"} (or @message "Verification failed")]
                  [:a {:href "#!/login" :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Back to sign in"]])]])))

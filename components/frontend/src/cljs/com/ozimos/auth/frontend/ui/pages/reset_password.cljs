(ns com.ozimos.auth.frontend.ui.pages.reset-password
  (:require [goog.net.XhrIo :as xhr]
            [com.ozimos.auth.frontend.json :as json]))

(defn- get-param [key]
  (some-> js/window.location.hash
          (.split "?") (aget 1)
          (.split "&")
          (->> (map #(.split % "="))
               (filter #(= (first %) key)) first second)))

(defn- submit [password confirm-password error-atom success-atom]
  (when (not= password confirm-password)
    (reset! error-atom "Passwords do not match"))
  (let [token (get-param "token")]
    (if-not token
      (reset! error-atom "Missing reset token")
      (let [x (xhr/XhrIo.)]
        (.send x "/api/auth/reset-password" "POST"
               (json/generate {:token token :password password})
               (fn [e]
                 (let [status (.. e -target getStatus)]
                   (if (= 200 status)
                     (do (reset! error-atom nil) (reset! success-atom true))
                     (let [resp (some-> (.. e -target getResponseText) (json/parse))]
                       (reset! error-atom (or (-> resp :errors :token first)
                                              "Invalid or expired token")))))))))))

(defn ui-reset-password
  []
  (let [password (atom "") confirm-password (atom "")
        curr-error (atom nil) curr-success (atom nil)]
    (fn []
      [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
       [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
        [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
         "Reset your password"]]
       [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when @curr-error
          [:div {:class "rounded-md bg-red-50 p-4 mb-4"} [:p {:class "text-sm text-red-700"} @curr-error]])
        (when @curr-success
          [:div {:class "rounded-md bg-green-50 p-4"}
           [:p {:class "text-sm text-green-700"} "Password reset successfully!"]
           [:a {:href "#!/login" :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Sign in"]])
        (when-not @curr-success
          [:form {:on-submit (fn [e] (.preventDefault e) (submit @password @confirm-password curr-error curr-success))}
           [:div [:label {:for "password" :class "block text-sm font-medium leading-6 text-gray-900"} "New password"]
            [:div {:class "mt-2"} [:input {:id "password" :name "password" :type "password" :required true :value @password :on-change #(reset! password (.. % -target -value)) :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]]
           [:div {:class "mt-4"} [:label {:for "confirm-password" :class "block text-sm font-medium leading-6 text-gray-900"} "Confirm new password"]
            [:div {:class "mt-2"} [:input {:id "confirm-password" :name "confirm-password" :type "password" :required true :value @confirm-password :on-change #(reset! confirm-password (.. % -target -value)) :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]]
           [:div {:class "mt-6"} [:button {:type "submit" :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"} "Reset password"]]
           [:div {:class "mt-4 text-center"} [:a {:href "#!/login" :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Back to sign in"]]])]])))

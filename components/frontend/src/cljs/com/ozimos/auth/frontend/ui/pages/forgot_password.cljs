(ns com.ozimos.auth.frontend.ui.pages.forgot-password
  (:require [goog.net.XhrIo :as xhr]
            [com.ozimos.auth.frontend.json :as json]))

(defn- send-link [email sent-atom]
  (let [x (xhr/XhrIo.)]
    (.send x "/api/auth/forgot-password" "POST"
           (json/generate {:email email})
           (fn [_] (reset! sent-atom true)))))

(defn ui-forgot-password
  []
  (let [email (atom "")
        sent-atom (atom false)]
    (fn []
      [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
       [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
        [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
         "Reset your password"]]
       [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (if @sent-atom
          [:div {:class "rounded-md bg-green-50 p-4"}
           [:p {:class "text-sm text-green-700"}
            "If that email is registered, we've sent a reset link. Check your inbox."]
           [:a {:href "#!/login" :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
            "Back to sign in"]]
          [:form {:on-submit (fn [e] (.preventDefault e) (send-link @email sent-atom))}
           [:div [:label {:for "email" :class "block text-sm font-medium leading-6 text-gray-900"} "Email address"]
            [:div {:class "mt-2"} [:input {:id "email" :name "email" :type "email" :required true :value @email :on-change #(reset! email (.. % -target -value)) :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]]
           [:div {:class "mt-6"} [:button {:type "submit" :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"} "Send reset link"]]
           [:div {:class "mt-4 text-center"} [:a {:href "#!/login" :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Back to sign in"]]])]])))

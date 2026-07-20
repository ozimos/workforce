(ns com.ozimos.auth.frontend.ui.pages.login
  (:require [goog.net.XhrIo :as xhr]
            [com.ozimos.auth.frontend.json :as json]))

(defn- handle-login [username password error-atom]
  (reset! error-atom nil)
  (let [x (xhr/XhrIo.)]
    (.send x "/api/auth/login" "POST"
           (json/generate {:username username :password password})
           (fn [e]
             (let [status (.. e -target getStatus)
                   parsed (some-> (.. e -target getResponseText) (json/parse))]
               (if (= 200 status)
                 (let [body parsed]
                   (.setItem js/localStorage "access-token" (:access-token body))
                   (.setItem js/localStorage "refresh-token" (:refresh-token body))
                   (.setItem js/localStorage "username" username)
                   (set! js/window.location.hash "#!/"))
                 (reset! error-atom (or (-> parsed :errors :credentials first)
                                        "Invalid username or password"))))))))

(defn ui-login
  []
  (let [username (atom "")
        password (atom "")
        error-atom (atom nil)]
    (fn []
      [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
       [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
        [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
         "Sign in to your account"]]
       [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when @error-atom
          [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
           [:p {:class "text-sm text-red-700"} @error-atom]])
        [:form {:on-submit (fn [e] (.preventDefault e) (handle-login @username @password error-atom))}
         [:div [:label {:for "username" :class "block text-sm font-medium leading-6 text-gray-900"} "Username"]
          [:div {:class "mt-2"} [:input {:id "username" :name "username" :type "text" :required true :value @username :on-change #(reset! username (.. % -target -value)) :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]]
         [:div {:class "mt-4"} [:label {:for "password" :class "block text-sm font-medium leading-6 text-gray-900"} "Password"]
          [:div {:class "mt-2"} [:input {:id "password" :name "password" :type "password" :required true :value @password :on-change #(reset! password (.. % -target -value)) :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]]
         [:div {:class "mt-6"} [:button {:type "submit" :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"} "Sign in"]]
         [:div {:class "mt-4 text-center"}
          [:a {:href "#!/register" :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Create an account"]
          [:span {:class "mx-2 text-gray-400"} "|"]
          [:a {:href "#!/forgot-password" :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Forgot password?"]]]]])))

(ns com.ozimos.auth.frontend.ui.pages.register
  (:require [goog.net.XhrIo :as xhr]
            [com.ozimos.auth.frontend.json :as json]))

(defn- input-field [id label type atm]
  [:div {:class "mt-4"}
   [:label {:for id :class "block text-sm font-medium leading-6 text-gray-900"} label]
   [:div {:class "mt-2"}
    [:input {:id id :name id :type type :required true
             :value @atm
             :on-change #(reset! atm (.. % -target -value))
             :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]])

(defn- submit [email username password confirm-password error-atom success-atom]
  (if (not= password confirm-password)
    (reset! error-atom "Passwords do not match")
    (let [x (xhr/XhrIo.)]
      (.send x "/api/auth/register" "POST"
             (json/generate {:email email :username username :password password})
             (fn [e]
               (let [status (.. e -target getStatus)]
                 (if (= 201 status)
                   (do (reset! error-atom nil) (reset! success-atom true))
                   (let [resp (some-> (.. e -target getResponseText) (json/parse))
                         errors (or (-> resp :errors) {})]
                     (reset! error-atom
                             (or (first (:username errors))
                                 (first (:email errors))
                                 (first (:password errors))
                                 "Registration failed"))))))))))

(defn ui-register []
  (let [email (atom "") username (atom "") password (atom "")
        confirm-password (atom "") error-atom (atom nil) success-atom (atom nil)]
    (fn []
      [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
       [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
        [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
         "Create an account"]]
       [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when @error-atom
          [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
           [:p {:class "text-sm text-red-700"} @error-atom]])
        (if @success-atom
          [:div {:class "rounded-md bg-green-50 p-4 mb-4"}
           [:p {:class "text-sm text-green-700"} "Account created! Check your email to verify."]
           [:a {:href "#!/login" :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
            "Sign in"]]
          [:form {:on-submit (fn [e] (.preventDefault e) (submit @email @username @password @confirm-password error-atom success-atom))}
           (input-field "email" "Email" "email" email)
           (input-field "username" "Username" "text" username)
           (input-field "password" "Password" "password" password)
           (input-field "confirm-password" "Confirm password" "password" confirm-password)
           [:div {:class "mt-6"}
            [:button {:type "submit" :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"}
             "Register"]]
           [:div {:class "mt-4 text-center"}
            [:a {:href "#!/login" :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
             "Already have an account? Sign in"]]])]])))

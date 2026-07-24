(ns com.ozimos.auth.frontend.ui.pages.forgot-password
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- send-link [this]
  (let [email (comp/get-state this :email)]
    (-> (json/fetch-json "/api/auth/forgot-password" "POST" {:email email})
        (.then (fn [_] (comp/set-state! this {:sent true}))))))

(defsc ForgotPassword [this _props]
  {:query [:email :sent]
   :initial-state {:email "" :sent false}}
  (let [{:keys [email sent]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Reset your password"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (if sent
          (div {:className "rounded-md bg-green-50 p-4"}
            (p {:className "text-sm text-green-700"}
              "If that email is registered, we've sent a reset link. Check your inbox.")
            (a {:href "/login" :className "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
              "Back to sign in"))
          (form {:onSubmit (fn [e] (.preventDefault e) (send-link this))}
            (div nil
              (label {:htmlFor "email" :className "block text-sm font-medium leading-6 text-gray-900"} "Email address")
              (div {:className "mt-2"}
                (input {:id "email" :name "email" :type "email" :required true
                        :value email
                        :onChange #(comp/set-state! this {:email (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-6"}
              (button {:type "submit" :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"}
                "Send reset link"))
            (div {:className "mt-4 text-center"}
              (a {:href "/login" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
                "Back to sign in"))))))))

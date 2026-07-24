(ns com.ozimos.auth.frontend.ui.pages.reset-password
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- get-param [key]
  (some-> js/window.location.search
          (.substring 1)
          (.split "&")
          (->> (map #(.split % "="))
               (filter #(= (first %) key)) first second)))

(defn- submit [this]
  (let [{:keys [password confirm-password]} (comp/get-state this)]
    (when (not= password confirm-password)
      (comp/set-state! this {:error-msg "Passwords do not match"}))
    (let [token (get-param "token")]
      (if-not token
        (comp/set-state! this {:error-msg "Missing reset token"})
        (-> (json/fetch-json "/api/auth/reset-password" "POST" {:token token :password password})
            (.then (fn [{:keys [status body]}]
                     (if (= 200 status)
                       (comp/set-state! this {:error-msg nil :success true})
                       (comp/set-state! this {:error-msg (or (-> body :errors :token first)
                                                             "Invalid or expired token")})))))))))

(defsc ResetPassword [this _props]
  {:query [:password :confirm-password :error-msg :success]
   :initial-state {:password "" :confirm-password "" :error-msg nil :success false}}
  (let [{:keys [password confirm-password error-msg success]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Reset your password"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"} (p {:className "text-sm text-red-700"} error-msg)))
        (when success
          (div {:className "rounded-md bg-green-50 p-4"}
            (p {:className "text-sm text-green-700"} "Password reset successfully!")
            (a {:href "/login" :className "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Sign in")))
        (when-not success
          (form {:onSubmit (fn [e] (.preventDefault e) (submit this))}
            (div nil
              (label {:htmlFor "password" :className "block text-sm font-medium leading-6 text-gray-900"} "New password")
              (div {:className "mt-2"}
                (input {:id "password" :name "password" :type "password" :required true
                        :value password
                        :onChange #(comp/set-state! this {:password (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-4"}
              (label {:htmlFor "confirm-password" :className "block text-sm font-medium leading-6 text-gray-900"} "Confirm new password")
              (div {:className "mt-2"}
                (input {:id "confirm-password" :name "confirm-password" :type "password" :required true
                        :value confirm-password
                        :onChange #(comp/set-state! this {:confirm-password (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-6"}
              (button {:type "submit" :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"} "Reset password"))
            (div {:className "mt-4 text-center"}
              (a {:href "/login" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Back to sign in"))))))))

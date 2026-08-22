(ns com.ozimos.workforce.frontend.ui.pages.login
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p span]]
   [com.ozimos.workforce.frontend.json :as json]))

(defn- handle-mfa-login [this]
  (let [{:keys [mfa-token mfa-code]} (comp/get-state this)]
    (comp/set-state! this {:error-msg nil})
    (-> (json/fetch-json "/api/auth/mfa/login" "POST" {:mfa-token mfa-token :code mfa-code})
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (.setItem js/localStorage "mfa-enabled" "true")
                     (set! js/window.location.pathname "/"))
                   (comp/set-state! this {:error-msg (or (-> body :errors :code first) "Invalid 2FA code")})))))))

(defn- handle-login [this]
  (let [{:keys [identifier password]} (comp/get-state this)]
    (comp/set-state! this {:error-msg nil})
    (-> (json/fetch-json "/api/auth/login" "POST" {:identifier identifier :password password})
        (.then (fn [{:keys [status body]}]
                 (cond
                   (and (= 200 status) (:mfa-required body))
                   (comp/set-state! this {:mfa-required true :mfa-token (:mfa-token body)})

                   (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (when-let [u (:user body)]
                       (when (:email u) (.setItem js/localStorage "email" (:email u)))
                       (when (:username u) (.setItem js/localStorage "username" (:username u))))
                     (set! js/window.location.pathname "/"))

                   :else
                   (comp/set-state! this {:error-msg (or (-> body :errors :credentials first)
                                                         "Invalid email/username or password")})))))))

(defsc Login [this _props]
  {:query [:identifier :password :error-msg :mfa-required :mfa-token :mfa-code]
   :initial-state {:identifier "" :password "" :error-msg nil :mfa-required false :mfa-token nil :mfa-code ""}}
  (let [{:keys [identifier password error-msg mfa-required mfa-code]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          (if mfa-required "Two-Factor Authentication" "Sign in to your account")))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"}
            (p {:className "text-sm text-red-700"} error-msg)))
        (if mfa-required
          (form {:onSubmit (fn [e] (.preventDefault e) (handle-mfa-login this))}
            (p {:className "text-sm text-gray-600 mb-4"} "Enter the 6-digit code from your authenticator app or an 8-character recovery code.")
            (div nil
              (label {:htmlFor "mfa-code" :className "block text-sm font-medium leading-6 text-gray-900"} "2FA Code")
              (div {:className "mt-2"}
                (input {:id "mfa-code" :name "mfa-code" :type "text" :required true
                        :value mfa-code
                        :onChange #(comp/set-state! this {:mfa-code (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-6"}
              (button {:type "submit" :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500"} "Verify & Sign In")))
          (form {:onSubmit (fn [e] (.preventDefault e) (handle-login this))}
            (div nil
              (label {:htmlFor "identifier" :className "block text-sm font-medium leading-6 text-gray-900"} "Email or username")
              (div {:className "mt-2"}
                (input {:id "identifier" :name "identifier" :type "text" :required true
                        :value identifier
                        :onChange #(comp/set-state! this {:identifier (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-4"}
              (label {:htmlFor "password" :className "block text-sm font-medium leading-6 text-gray-900"} "Password")
              (div {:className "mt-2"}
                (input {:id "password" :name "password" :type "password" :required true
                        :value password
                        :onChange #(comp/set-state! this {:password (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-6"}
              (button {:type "submit" :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"} "Sign in"))
            (div {:className "mt-4 text-center"}
              (a {:href "/register" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Create an account")
              (span {:className "mx-2 text-gray-400"} "|")
              (a {:href "/forgot-password" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Forgot password?"))))))))

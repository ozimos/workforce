(ns com.ozimos.auth.frontend.ui.pages.login
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p span]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- handle-login [this]
  (let [{:keys [username password]} (comp/get-state this)]
    (comp/set-state! this {:error-msg nil})
    (-> (json/fetch-json "/api/auth/login" "POST" {:username username :password password})
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (.setItem js/localStorage "username" username)
                     (set! js/window.location.pathname "/"))
                   (comp/set-state! this {:error-msg (or (-> body :errors :credentials first)
                                                         "Invalid username or password")})))))))

(defsc Login [this _props]
  {:query [:username :password :error-msg]
   :initial-state {:username "" :password "" :error-msg nil}}
  (let [{:keys [username password error-msg]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Sign in to your account"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"}
            (p {:className "text-sm text-red-700"} error-msg)))
        (form {:onSubmit (fn [e] (.preventDefault e) (handle-login this))}
          (div nil
            (label {:htmlFor "username" :className "block text-sm font-medium leading-6 text-gray-900"} "Username")
            (div {:className "mt-2"}
              (input {:id "username" :name "username" :type "text" :required true
                      :value username
                      :onChange #(comp/set-state! this {:username (.. % -target -value)})
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
            (a {:href "/forgot-password" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Forgot password?")))))))

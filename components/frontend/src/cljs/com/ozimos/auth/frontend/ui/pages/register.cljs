(ns com.ozimos.auth.frontend.ui.pages.register
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- input-field [id label-text type value on-change]
  (div {:className "mt-4"}
    (label {:htmlFor id :className "block text-sm font-medium leading-6 text-gray-900"} label-text)
    (div {:className "mt-2"}
      (input {:id id :name id :type type :required true
              :value value
              :onChange on-change
              :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))

  (defn- submit [this]
    (let [{:keys [email username password confirm-password]} (comp/get-state this)]
      (if (not= password confirm-password)
        (comp/set-state! this {:error-msg "Passwords do not match"})
        (-> (json/fetch-json "/api/auth/register" "POST" {:email email :username username :password password})
            (.then (fn [{:keys [status body]}]
                     (if (= 201 status)
                       (comp/set-state! this {:error-msg nil :success true})
                       (let [errors (or (:errors body) {})]
                         (comp/set-state! this
                           {:error-msg (or (first (:username errors))
                                           (first (:email errors))
                                           (first (:password errors))
                                           "Registration failed")})))))))))

  (defsc Register [this _props]
    {:query [:email :username :password :confirm-password :error-msg :success]
     :initial-state {:email "" :username "" :password "" :confirm-password "" :error-msg nil :success false}}
    (let [{:keys [email username password confirm-password error-msg success]} (comp/get-state this)]
      (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
        (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
          (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
            "Create an account"))
        (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
          (when error-msg
            (div {:className "rounded-md bg-red-50 p-4 mb-4"}
              (p {:className "text-sm text-red-700"} error-msg)))
          (if success
            (div {:className "rounded-md bg-green-50 p-4 mb-4"}
              (p {:className "text-sm text-green-700"} "Account created! Check your email to verify.")
              (a {:href "/login" :className "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
                "Sign in"))
            (form {:onSubmit (fn [e] (.preventDefault e) (submit this))}
              (input-field "email" "Email" "email" email #(comp/set-state! this {:email (.. % -target -value)}))
              (input-field "username" "Username" "text" username #(comp/set-state! this {:username (.. % -target -value)}))
              (input-field "password" "Password" "password" password #(comp/set-state! this {:password (.. % -target -value)}))
              (input-field "confirm-password" "Confirm password" "password" confirm-password #(comp/set-state! this {:confirm-password (.. % -target -value)}))
              (div {:className "mt-6"}
                (button {:type "submit" :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"}
                  "Register"))
              (div {:className "mt-4 text-center"}
                (a {:href "/login" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
                  "Already have an account? Sign in")))))))))

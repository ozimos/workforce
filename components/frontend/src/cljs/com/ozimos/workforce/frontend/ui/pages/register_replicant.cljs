(ns com.ozimos.workforce.frontend.ui.pages.register-replicant
  "Replicant view for the Register page.
   Pure props -> hiccup via defrc with pure state transitions."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (fn [db params] -> db)
;; -----------------------------------------------------------------------------

(defn set-email-state [db email]
  (assoc db :email email :error-msg nil))

(defn set-password-state [db password]
  (assoc db :password password :error-msg nil))

(defn set-confirm-password-state [db confirm-pw]
  (assoc db :confirm-password confirm-pw :error-msg nil))

(defn set-field-errors-state [db errors]
  (assoc db :field-errors (or errors {}) :error-msg nil))

(defn set-error-msg-state [db msg]
  (assoc db :error-msg msg))

(defn set-success-state [db created-username]
  (assoc db :error-msg nil :field-errors {} :success true :created-username created-username))

;; -----------------------------------------------------------------------------
;; View Helpers
;; -----------------------------------------------------------------------------

(defn- input-field [id label-text type val err input-kw]
  [:div {:class "mt-4"}
   [:label {:for id :class "block text-sm font-medium leading-6 text-gray-900"} label-text]
   [:div {:class "mt-2"}
    [:input {:id id :name id :type type :required true
             :value (or val "")
             :class (str "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset "
                         (if err "ring-red-300 focus:ring-red-500" "ring-gray-300 focus:ring-indigo-600")
                         " placeholder:text-gray-400 focus:ring-2 focus:ring-inset sm:text-sm sm:leading-6")
             :on {:input [input-kw]}}]]
   (when err
     [:p {:id (str id "-error") :class "mt-1 text-xs text-red-600"} err])])

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defrc RegisterReplicant
  {:query [:email :password :confirm-password :error-msg :field-errors :success :created-username]
   :ident :register-replicant/root
   :ident-key :register-replicant/root
   :route-segment ["register"]}
  [{:keys [email password confirm-password error-msg field-errors success]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
    [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
     "Create an account"]]
   [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
    (when error-msg
      [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
       [:p {:class "text-sm text-red-700"} error-msg]])
    (if success
      [:div {:class "rounded-md bg-green-50 p-4 mb-4"}
       [:p {:class "text-sm font-semibold text-green-800"} "Account created successfully!"]
       [:p {:class "text-xs text-green-700 mt-1 mb-3"} "A verification link has been sent to your email. Please check your inbox to verify your account."]
       [:div {:class "mt-4 space-y-2"}
        [:p {:class "text-sm text-gray-600"} "What would you like to do next?"]
        [:div {:class "flex gap-3"}
         [:a {:href "/create-org"
              :class "flex-1 text-center rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500"
              :on {:click [::navigate "/create-org"]}}
          "Create Organization"]
         [:a {:href "/join-org"
              :class "flex-1 text-center rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-700 ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
              :on {:click [::navigate "/join-org"]}}
          "Join Organization"]]]]
      [:form {:on {:submit [::submit]}}
       (input-field "email" "Email" "email" email (:email field-errors) ::set-email)
       (input-field "password" "Password" "password" password (:password field-errors) ::set-password)
       (input-field "confirm-password" "Confirm password" "password" confirm-password (:confirm-password field-errors) ::set-confirm-password)
       [:div {:class "mt-6"}
        [:button {:type "submit"
                  :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"
                  :on {:click [::submit]}}
         "Register"]]
       [:div {:class "mt-4 text-center"}
        [:a {:href "/login"
             :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
             :on {:click [::navigate "/login"]}}
         "Already have an account? Sign in"]]])]])

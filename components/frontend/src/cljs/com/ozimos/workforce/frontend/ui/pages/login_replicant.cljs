(ns com.ozimos.workforce.frontend.ui.pages.login-replicant
  "Replicant view for the Login and 2FA MFA challenge page.
   Pure props -> hiccup via defrc with pure state transitions."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (fn [db params] -> db)
;; -----------------------------------------------------------------------------

(defn set-identifier-state [db identifier]
  (assoc db :identifier identifier :error-msg nil))

(defn set-password-state [db password]
  (assoc db :password password :error-msg nil))

(defn set-mfa-code-state [db code]
  (assoc db :mfa-code code :error-msg nil))

(defn set-error-msg-state [db msg]
  (assoc db :error-msg msg))

(defn set-mfa-required-state [db mfa-token]
  (assoc db :mfa-required true :mfa-token mfa-token :error-msg nil))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defrc LoginReplicant
  {:query [:identifier :password :error-msg :mfa-required :mfa-token :mfa-code]
   :ident :login-replicant/root
   :ident-key :login-replicant/root
   :route-segment ["login"]}
  [{:keys [identifier password error-msg mfa-required mfa-code]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
    [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
     (if mfa-required "Two-Factor Authentication" "Sign in to your account")]]
   [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
    (when error-msg
      [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
       [:p {:class "text-sm text-red-700"} error-msg]])
    (if mfa-required
      [:form {:on {:submit [::submit-mfa]}}
       [:p {:class "text-sm text-gray-600 mb-4"}
        "Enter the 6-digit code from your authenticator app or an 8-character recovery code."]
       [:div
        [:label {:for "mfa-code" :class "block text-sm font-medium leading-6 text-gray-900"}
         "2FA Code"]
        [:div {:class "mt-2"}
         [:input {:id "mfa-code" :name "mfa-code" :type "text" :required true
                  :value (or mfa-code "")
                  :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"
                  :on {:input [::set-mfa-code]}}]]]
       [:div {:class "mt-6"}
        [:button {:type "submit"
                  :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500"
                  :on {:click [::submit-mfa]}}
         "Verify & Sign In"]]]
      [:form {:on {:submit [::submit-login]}}
       [:div
        [:label {:for "identifier" :class "block text-sm font-medium leading-6 text-gray-900"}
         "Email or username"]
        [:div {:class "mt-2"}
         [:input {:id "identifier" :name "identifier" :type "text" :required true
                  :value (or identifier "")
                  :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"
                  :on {:input [::set-identifier]}}]]]
       [:div {:class "mt-4"}
        [:label {:for "password" :class "block text-sm font-medium leading-6 text-gray-900"}
         "Password"]
        [:div {:class "mt-2"}
         [:input {:id "password" :name "password" :type "password" :required true
                  :value (or password "")
                  :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"
                  :on {:input [::set-password]}}]]]
       [:div {:class "mt-6"}
        [:button {:type "submit"
                  :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"
                  :on {:click [::submit-login]}}
         "Sign in"]]
       [:div {:class "mt-4 text-center"}
        [:a {:href "/register"
             :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
             :on {:click [::navigate "/register"]}}
         "Create an account"]
        [:span {:class "mx-2 text-gray-400"} "|"]
        [:a {:href "/forgot-password"
             :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
             :on {:click [::navigate "/forgot-password"]}}
         "Forgot password?"]]])]])

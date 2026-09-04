(ns com.ozimos.workforce.frontend.ui.pages.reset-password
  "Replicant view for the Reset Password page.
   Pure props -> hiccup via defrc with pure state transitions."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (fn [db params] -> db)
;; -----------------------------------------------------------------------------

(defn set-password-state [db password]
  (assoc db :password password :error-msg nil))

(defn set-confirm-password-state [db confirm-pw]
  (assoc db :confirm-password confirm-pw :error-msg nil))

(defn set-error-msg-state [db msg]
  (assoc db :error-msg msg))

(defn set-success-state [db]
  (assoc db :error-msg nil :success true))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defrc ResetPassword
  {:query [:password :confirm-password :error-msg :success]
   :ident :reset-password/root
   :ident-key :reset-password/root
   :route-segment ["reset-password"]}
  [{:keys [password confirm-password error-msg success]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
    [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
     "Reset your password"]]
   [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
    (when error-msg
      [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
       [:p {:class "text-sm text-red-700"} error-msg]])
    (if success
      [:div {:class "rounded-md bg-green-50 p-4"}
       [:p {:class "text-sm text-green-700"} "Password reset successfully!"]
       [:a {:href "/login"
            :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"
            :on {:click [::navigate "/login"]}}
        "Sign in"]]
      [:form {:on {:submit [::submit]}}
       [:div
        [:label {:for "password" :class "block text-sm font-medium leading-6 text-gray-900"}
         "New password"]
        [:div {:class "mt-2"}
         [:input {:id "password" :name "password" :type "password" :required true
                  :value (or password "")
                  :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"
                  :on {:input [::set-password]}}]]]
       [:div {:class "mt-4"}
        [:label {:for "confirm-password" :class "block text-sm font-medium leading-6 text-gray-900"}
         "Confirm new password"]
        [:div {:class "mt-2"}
         [:input {:id "confirm-password" :name "confirm-password" :type "password" :required true
                  :value (or confirm-password "")
                  :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"
                  :on {:input [::set-confirm-password]}}]]]
       [:div {:class "mt-6"}
        [:button {:type "submit"
                  :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"
                  :on {:click [::submit]}}
         "Reset password"]]
       [:div {:class "mt-4 text-center"}
        [:a {:href "/login"
             :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
             :on {:click [::navigate "/login"]}}
         "Back to sign in"]]])]])

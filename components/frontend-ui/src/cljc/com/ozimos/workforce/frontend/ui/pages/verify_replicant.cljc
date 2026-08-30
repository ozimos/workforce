(ns com.ozimos.workforce.frontend.ui.pages.verify-replicant
  (:require
   [com.ozimos.workforce.frontend.defrc :refer [defrc]]
))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (fn [db params] -> db)
;; -----------------------------------------------------------------------------

(defn set-status-state [db status message]
  (assoc db :status status :message message))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defrc VerifyReplicant
  {:query [:status :message]
   :ident :verify-replicant/root}
  [{:keys [status message]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
    (case status
      :loading
      [:div {:class "text-center"}
       [:p {:class "text-gray-500"} "Verifying your account..."]]

      :success
      [:div {:class "rounded-md bg-green-50 p-4"}
       [:p {:class "text-sm text-green-700"} (or message "Account verified!")]
       [:a {:href "/login"
            :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"
            :on {:click [::navigate "/login"]}}
        "Sign in"]]

      :error
      [:div {:class "rounded-md bg-red-50 p-4"}
       [:p {:class "text-sm text-red-700"} (or message "Verification failed")]
       [:a {:href "/login"
            :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"
            :on {:click [::navigate "/login"]}}
        "Back to sign in"]]

      [:div {:class "text-center"}
       [:p {:class "text-gray-500"} "Verifying your account..."]])]])

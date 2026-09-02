(ns com.ozimos.workforce.frontend.ui.pages.profile-replicant
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

(defn set-new-username-state [db v] (assoc db :new-username v))
(defn set-error-msg-state [db v] (assoc db :error-msg v))
(defn set-success-msg-state [db v] (assoc db :success-msg v))
(defn set-loading-state [db v] (assoc db :loading v))
(defn set-mfa-stage-state [db v] (assoc db :mfa-stage v))
(defn set-totp-code-state [db v] (assoc db :totp-code v))

(defmutation set-new-username [{:keys [value]}] (action [{:keys [state]}] (swap! state set-new-username-state value)))
(defmutation set-error-msg [{:keys [msg]}] (action [{:keys [state]}] (swap! state set-error-msg-state msg)))
(defmutation set-success-msg [{:keys [msg]}] (action [{:keys [state]}] (swap! state set-success-msg-state msg)))
(defmutation set-loading [{:keys [v]}] (action [{:keys [state]}] (swap! state set-loading-state v)))
(defmutation set-mfa-stage [{:keys [stage]}] (action [{:keys [state]}] (swap! state set-mfa-stage-state stage)))
(defmutation set-totp-code [{:keys [value]}] (action [{:keys [state]}] (swap! state set-totp-code-state value)))

(defrc ProfileReplicant
  {:query [:new-username :error-msg :success-msg :loading :mfa-stage :mfa-secret :mfa-backup-codes :totp-code]
   :ident :profile-replicant/root
   :ident-key :profile-replicant/root
   :route-segment ["profile"]}
  [{:keys [new-username error-msg success-msg loading mfa-stage mfa-secret mfa-backup-codes totp-code]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-md"}
    [:h2 {:class "mt-6 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"} "Profile & Security"]]
   [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-md"}
    (when error-msg [:div {:class "rounded-md bg-red-50 p-4 mb-4"} [:p {:class "text-sm text-red-700"} error-msg]])
    (when success-msg [:div {:class "rounded-md bg-green-50 p-4 mb-4"} [:p {:class "text-sm text-green-700"} success-msg]])
    [:div {:class "mb-6"}
     [:label {:class "block text-sm font-medium leading-6 text-gray-900"} "Current username"]
     [:p {:class "mt-1 text-sm text-gray-600"} "—"]]
    [:form {:on {:submit [::update-username]}}
     [:div {:class "mt-4"}
      [:label {:for "new-username" :class "block text-sm font-medium leading-6 text-gray-900"} "New username"]
      [:div {:class "mt-2"}
       [:input {:id "new-username" :name "new-username" :type "text" :required true :minLength 3 :maxLength 32 :pattern "[a-zA-Z0-9_-]+" :value (or new-username "") :on {:input [::set-new-username]} :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"}]]]
     [:div {:class "mt-6"}
      [:button {:type "submit" :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:opacity-50" :disabled loading :on {:click [::update-username]}} (if loading "Saving..." "Save Username")]]]
    [:div {:class "mt-10 border-t border-gray-200 pt-6"}
     [:h2 {:class "text-lg font-semibold text-gray-900 mb-4"} "Two-Factor Authentication (2FA)"]
     (if (= mfa-stage :enabled)
       [:div {:class "rounded-md bg-green-50 p-4"}
        [:p {:class "text-sm font-semibold text-green-800"} "✓ Two-Factor Authentication is Enabled"]
        [:p {:class "text-xs text-green-700 mt-1"} "Your account is protected with TOTP and Passkeys."]]
       [:div {:class "rounded-md bg-amber-50 p-4 border border-amber-200"}
        [:p {:class "text-sm font-semibold text-amber-800"} "2FA is currently disabled"]
        [:p {:class "text-xs text-amber-700 mt-1 mb-3"} "Enable 2FA to protect your account with TOTP authenticator codes or Passkeys."]
        (if (= mfa-stage :setup)
          [:div {:class "space-y-4"}
           [:p {:class "text-xs text-gray-700 font-mono bg-white p-2 rounded border"} (str "Secret: " mfa-secret)]
           (when (seq mfa-backup-codes)
             [:div {:class "text-xs font-mono bg-gray-100 p-2 rounded border"}
              [:p {:class "font-bold mb-1"} "Backup Codes:"]
              (into [:div] (map (fn [c] [:div {:replicant/key c} c]) mfa-backup-codes))])
           [:div {:class "flex gap-2"}
            [:input {:type "text" :placeholder "Enter 6-digit code" :value (or totp-code "") :on {:input [::set-totp-code]} :class "block w-full rounded-md border-gray-300 text-sm py-1 px-2"}]
            [:button {:class "bg-indigo-600 text-white text-xs px-3 py-1.5 rounded font-semibold" :on {:click [::verify-mfa]}} "Verify & Enable"]]]
          [:button {:class "bg-amber-600 hover:bg-amber-500 text-white text-xs font-semibold px-3 py-1.5 rounded" :on {:click [::setup-mfa]}} "Set up 2FA"])])]
    [:div {:class "mt-6 text-center"}
     [:a {:href "/" :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Back to home"]]]])

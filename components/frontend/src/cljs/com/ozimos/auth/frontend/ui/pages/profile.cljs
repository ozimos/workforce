(ns com.ozimos.auth.frontend.ui.pages.profile
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]
   [com.ozimos.auth.frontend.json :as json]
   [com.ozimos.auth.frontend.transit :as transit]))

(defn- update-username [this]
  (let [{:keys [new-username]} (comp/get-state this)]
    (comp/set-state! this {:error-msg nil :success-msg nil :loading true})
    (let [eql [(list 'user/update-username {:user/new-username new-username})]]
      (-> (transit/fetch-transit "/api/query" eql)
          (.then (fn [{:keys [status body]}]
                   (let [res (get body 'user/update-username)
                         errs (:user/errors res)]
                     (if (and (= 200 status) res (not errs))
                       (do
                         (when-let [uname (:current-user/username res)]
                           (.setItem js/localStorage "username" uname))
                         (comp/set-state! this
                           {:new-username "" :success-msg "Username updated!" :error-msg nil :loading false}))
                       (comp/set-state! this
                         {:error-msg (or (-> errs :new-username first)
                                         (-> body :errors :auth first)
                                         "Failed to update username")
                          :loading false})))))))))

(defn- setup-mfa-start [this]
  (let [token (.getItem js/localStorage "access-token")]
    (-> (json/fetch-json "/api/auth/mfa/setup" "POST" {} {"Authorization" (str "Bearer " token)})
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (comp/set-state! this {:mfa-stage :setup
                                          :mfa-secret (:secret body)
                                          :mfa-qr-url (:qr-url body)
                                          :mfa-backup-codes (:backup-codes body)})
                   (comp/set-state! this {:error-msg "Failed to start 2FA setup"})))))))

(defn- verify-mfa-code [this]
  (let [{:keys [totp-code]} (comp/get-state this)
        token (.getItem js/localStorage "access-token")]
    (-> (json/fetch-json "/api/auth/mfa/verify-setup" "POST" {:code totp-code} {"Authorization" (str "Bearer " token)})
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (do
                     (.setItem js/localStorage "mfa-enabled" "true")
                     (comp/set-state! this {:mfa-stage :enabled
                                            :success-msg "2FA enabled successfully!"
                                            :totp-code ""}))
                   (comp/set-state! this {:error-msg (or (-> body :errors :code first) "Invalid 2FA code")})))))))

(defsc Profile [this _props]
  {:query [:new-username :error-msg :success-msg :loading :mfa-stage :mfa-secret :mfa-qr-url :mfa-backup-codes :totp-code]
   :initial-state {:new-username "" :error-msg nil :success-msg nil :loading false
                   :mfa-stage :disabled :mfa-secret nil :mfa-qr-url nil :mfa-backup-codes [] :totp-code ""}}
  (let [{:keys [new-username error-msg success-msg loading mfa-stage mfa-secret mfa-qr-url mfa-backup-codes totp-code]} (comp/get-state this)
        current-username (and (exists? js/localStorage) (.getItem js/localStorage "username"))
        mfa-enabled? (and (exists? js/localStorage) (= "true" (.getItem js/localStorage "mfa-enabled")))]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-md"}
        (h2 {:className "mt-6 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Profile & Security"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-md"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"}
            (p {:className "text-sm text-red-700"} error-msg)))
        (when success-msg
          (div {:className "rounded-md bg-green-50 p-4 mb-4"}
            (p {:className "text-sm text-green-700"} success-msg)))
        (div {:className "mb-6"}
          (label {:className "block text-sm font-medium leading-6 text-gray-900"} "Current username")
          (p {:className "mt-1 text-sm text-gray-600"} (or current-username "—")))
        (form {:onSubmit (fn [e] (.preventDefault e) (update-username this))}
          (div {:className "mt-4"}
            (label {:htmlFor "new-username" :className "block text-sm font-medium leading-6 text-gray-900"} "New username")
            (div {:className "mt-2"}
              (input {:id "new-username" :name "new-username" :type "text" :required true
                      :minLength 3 :maxLength 32
                      :pattern "[a-zA-Z0-9_-]+"
                      :value new-username
                      :onChange #(comp/set-state! this {:new-username (.. % -target -value)})
                      :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
          (div {:className "mt-6"}
            (button {:type "submit" :disabled loading
                     :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:opacity-50"}
              (if loading "Saving..." "Save Username"))))

        ;; --- 2FA & Security Settings Card ---
        (div {:className "mt-10 border-t border-gray-200 pt-6"}
          (h2 {:className "text-lg font-semibold text-gray-900 mb-4"} "Two-Factor Authentication (2FA)")
          (if (or mfa-enabled? (= mfa-stage :enabled))
            (div {:className "rounded-md bg-green-50 p-4"}
              (p {:className "text-sm font-semibold text-green-800"} "✓ Two-Factor Authentication is Enabled")
              (p {:className "text-xs text-green-700 mt-1"} "Your account is protected with TOTP and Passkeys."))
            (div {:className "rounded-md bg-amber-50 p-4 border border-amber-200"}
              (p {:className "text-sm font-semibold text-amber-800"} "2FA is currently disabled")
              (p {:className "text-xs text-amber-700 mt-1 mb-3"} "Enable 2FA to protect your account with TOTP authenticator codes or Passkeys.")
              (if (= mfa-stage :setup)
                (div {:className "space-y-4"}
                  (p {:className "text-xs text-gray-700 font-mono bg-white p-2 rounded border"} (str "Secret: " mfa-secret))
                  (when (seq mfa-backup-codes)
                    (div {:className "text-xs font-mono bg-gray-100 p-2 rounded border"}
                      (p {:className "font-bold mb-1"} "Backup Codes:")
                      (mapv #(div {:key %} %) mfa-backup-codes)))
                  (div {:className "flex gap-2"}
                    (input {:type "text" :placeholder "Enter 6-digit code"
                            :value totp-code
                            :onChange #(comp/set-state! this {:totp-code (.. % -target -value)})
                            :className "block w-full rounded-md border-gray-300 text-sm py-1 px-2"})
                    (button {:onClick #(verify-mfa-code this)
                             :className "bg-indigo-600 text-white text-xs px-3 py-1.5 rounded font-semibold"}
                      "Verify & Enable")))
                (button {:onClick #(setup-mfa-start this)
                         :className "bg-amber-600 hover:bg-amber-500 text-white text-xs font-semibold px-3 py-1.5 rounded"}
                  "Set up 2FA")))))

        (div {:className "mt-6 text-center"}
          (a {:href "/" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
            "Back to home"))))))

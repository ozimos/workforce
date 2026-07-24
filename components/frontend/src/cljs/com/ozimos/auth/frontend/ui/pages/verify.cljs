(ns com.ozimos.auth.frontend.ui.pages.verify
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a div p]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- get-param [key]
  (some-> js/window.location.search
          (.substring 1)
          (.split "&")
          (->> (map #(.split % "="))
               (filter #(= (first %) key)) first second)))

(defn- verify-account [this]
  (let [token (get-param "token")
        user-id (get-param "user-id")]
    (if (and token user-id)
      (-> (json/fetch-json "/api/auth/verify" "POST" {:token token :user-id user-id})
          (.then (fn [{:keys [status]}]
                   (if (= 200 status)
                     (comp/set-state! this {:status :success :message "Account verified!"})
                     (comp/set-state! this {:status :error :message "Verification failed"})))))
      (comp/set-state! this {:status :error :message "Missing verification token"}))))

(defsc Verify [this _props]
  {:query [:status :message]
   :initial-state {:status :loading :message nil}
   :component-did-mount verify-account}
  (let [{:keys [status message]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (case status
          :loading (div {:className "text-center"} (p {:className "text-gray-500"} "Verifying your account..."))
          :success (div {:className "rounded-md bg-green-50 p-4"}
                     (p {:className "text-sm text-green-700"} message)
                     (a {:href "/login" :className "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Sign in"))
          :error (div {:className "rounded-md bg-red-50 p-4"}
                   (p {:className "text-sm text-red-700"} (or message "Verification failed"))
                   (a {:href "/login" :className "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Back to sign in")))))))

(ns com.ozimos.auth.frontend.ui.pages.profile
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- update-username [this]
  (let [{:keys [new-username]} (comp/get-state this)]
    (comp/set-state! this {:error-msg nil :success-msg nil :loading true})
    (let [eql [(list 'user/update-username {:user/new-username new-username})]]
      (-> (json/fetch-json "/api/query" "POST" {:eql (pr-str eql)})
          (.then (fn [{:keys [status body]}]
                   (let [res (get-in body [:data :user/update-username])
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

(defsc Profile [this _props]
  {:query [:new-username :error-msg :success-msg :loading]
   :initial-state {:new-username "" :error-msg nil :success-msg nil :loading false}}
  (let [{:keys [new-username error-msg success-msg loading]} (comp/get-state this)
        current-username (and (exists? js/localStorage) (.getItem js/localStorage "username"))]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Profile"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
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
              (if loading "Saving..." "Save")))
          (div {:className "mt-4 text-center"}
            (a {:href "/" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
              "Back to home")))))))

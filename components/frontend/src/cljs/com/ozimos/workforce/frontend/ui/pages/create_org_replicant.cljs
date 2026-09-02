(ns com.ozimos.workforce.frontend.ui.pages.create-org-replicant
  "Replicant view for the Create Organization page.
   Pure props -> hiccup via defrc with pure state transitions."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (fn [db params] -> db)
;; -----------------------------------------------------------------------------

(defn set-name-state [db name-val]
  (assoc db :name name-val :error-msg nil))

(defn set-loading-state [db loading-val]
  (assoc db :loading loading-val :error-msg nil))

(defn set-error-msg-state [db msg]
  (assoc db :error-msg msg :loading false))

(defn set-success-state [db org-name-val]
  (assoc db :error-msg nil :loading false :success true :org-name org-name-val))

;; -----------------------------------------------------------------------------
;; View
;; -----------------------------------------------------------------------------

(defrc CreateOrgReplicant
  {:query [:name :error-msg :loading :success :org-name]
   :ident :create-org-replicant/root
   :ident-key :create-org-replicant/root
   :route-segment ["create-org"]}
  [{:keys [name error-msg loading success org-name]}]
  [:div {:class "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
   [:div {:class "sm:mx-auto sm:w-full sm:max-w-sm"}
    [:h2 {:class "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
     "Create Organization"]]
   [:div {:class "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
    (when error-msg
      [:div {:class "rounded-md bg-red-50 p-4 mb-4"}
       [:p {:class "text-sm text-red-700"} error-msg]])
    (if success
      [:div {:class "rounded-md bg-green-50 p-4 mb-4"}
       [:p {:class "text-sm text-green-700"}
        (str "Organization \"" (or org-name name) "\" created successfully!")]
       [:a {:href "/"
            :class "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"
            :on {:click [::navigate "/"]}}
        "Go to Dashboard"]]
      [:form {:on {:submit [::submit]}}
       [:div
        [:label {:for "name" :class "block text-sm font-medium leading-6 text-gray-900"}
         "Organization Name"]
        [:div {:class "mt-2"}
         [:input {:id "name" :name "name" :type "text" :required true
                  :value (or name "")
                  :class "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"
                  :on {:input [::set-name]}}]]]
       [:div {:class "mt-6"}
        [:button {:type "submit" :disabled loading
                  :class "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:opacity-50"
                  :on {:click [::submit]}}
         (if loading "Creating..." "Create Organization")]]
       [:div {:class "mt-4 text-center"}
        [:a {:href "/"
             :class "text-sm font-semibold text-indigo-600 hover:text-indigo-500"
             :on {:click [::navigate "/"]}}
         "Skip for now"]]])]])

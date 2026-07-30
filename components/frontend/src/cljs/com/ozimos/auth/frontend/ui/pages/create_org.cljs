(ns com.ozimos.auth.frontend.ui.pages.create-org
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div form h2 input label p]]))

(defn- submit [this]
  (let [{:keys [name]} (comp/get-state this)]
    (comp/set-state! this {:error-msg nil :loading true})
    (let [query [(list 'org/create {:org/name name})]
          body (js/JSON.stringify #js {"eql" (pr-str query)})]
      (-> (js/fetch "/api/query"
            (clj->js {:method "POST"
                      :headers {"Content-Type" "application/json"}
                      :body body}))
          (.then (fn [resp]
                   (-> (.json resp)
                       (.then (fn [parsed]
                                (let [data (js->clj parsed :keywordize-keys true)
                                      org-data (some-> data :data (get "org/create"))]
                                  (if (:org/errors org-data)
                                    (comp/set-state! this
                                      {:error-msg (or (-> org-data :org/errors :name first)
                                                      "Failed to create organization")
                                       :loading false})
                                    (comp/set-state! this
                                      {:error-msg nil :loading false :success true
                                       :org-name (:org/name org-data)}))))))))
          (.catch (fn [_]
                    (comp/set-state! this {:error-msg "Network error" :loading false})))))))
(defsc CreateOrg [this _props]
  {:query [:name :error-msg :loading :success :org-name]
   :initial-state {:name "" :error-msg nil :loading false :success false :org-name nil}}
  (let [{:keys [name error-msg loading success org-name]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-sm"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Create Organization"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-sm"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"}
            (p {:className "text-sm text-red-700"} error-msg)))
        (if success
          (div {:className "rounded-md bg-green-50 p-4 mb-4"}
            (p {:className "text-sm text-green-700"}
              (str "Organization \"" (or org-name name) "\" created successfully!"))
            (a {:href "/" :className "mt-2 inline-block text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
              "Go to Dashboard"))
          (form {:onSubmit (fn [e] (.preventDefault e) (submit this))}
            (div nil
              (label {:htmlFor "name" :className "block text-sm font-medium leading-6 text-gray-900"} "Organization Name")
              (div {:className "mt-2"}
                (input {:id "name" :name "name" :type "text" :required true
                        :value name
                        :onChange #(comp/set-state! this {:name (.. % -target -value)})
                        :className "block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600 sm:text-sm sm:leading-6"})))
            (div {:className "mt-6"}
              (button {:type "submit" :disabled loading
                       :className "flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:opacity-50"}
                (if loading "Creating..." "Create Organization")))
            (div {:className "mt-4 text-center"}
              (a {:href "/" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
                "Skip for now"))))))))

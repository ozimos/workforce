(ns com.ozimos.workforce.frontend.ui.pages.headcount
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [button div h1 h3 input label p select span table tbody td textarea th thead tr]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- fetch-inbox-and-reqs! [this]
  (comp/set-state! this {:loading true :error nil})
  (-> (transit/fetch-transit "/api/query"
        [{:user/pending-approvals [:headcount/id :headcount/title :headcount/unit-id
                                   :headcount/job-level :headcount/status :headcount/current-step]}
         {:user/active-org [:org/id :org/name]}])
      (.then (fn [{:keys [body]}]
               (comp/set-state! this {:pending-approvals (:user/pending-approvals body [])
                                      :active-org (:user/active-org body)
                                      :loading false})))
      (.catch (fn [err]
                (comp/set-state! this {:error (str "Failed to load headcount inbox: " err)
                                       :loading false})))))

(defn- approve-step! [this request-id]
  (-> (transit/fetch-transit "/api/mutation"
        [`(com.ozimos.workforce.org.resolvers/approve-headcount-step
           {:headcount/request-id ~request-id})])
      (.then (fn [{:keys [body]}]
               (let [res (get body `com.ozimos.workforce.org.resolvers/approve-headcount-step)]
                 (if (:error res)
                   (comp/set-state! this {:msg (str "Error: " (get-in res [:error :message]))})
                   (do
                     (comp/set-state! this {:msg "Step approved successfully!"})
                     (fetch-inbox-and-reqs! this))))))
      (.catch (fn [err]
                (comp/set-state! this {:msg (str "Error: " err)})))))

(defn- reject-request! [this request-id]
  (-> (transit/fetch-transit "/api/mutation"
        [`(com.ozimos.workforce.org.resolvers/reject-headcount-request
           {:headcount/request-id ~request-id
            :headcount/reason "Rejected by manager via UI"})])
      (.then (fn [{:keys [body]}]
               (let [res (get body `com.ozimos.workforce.org.resolvers/reject-headcount-request)]
                 (if (:error res)
                   (comp/set-state! this {:msg (str "Error: " (get-in res [:error :message]))})
                   (do
                     (comp/set-state! this {:msg "Request rejected."})
                     (fetch-inbox-and-reqs! this))))))
      (.catch (fn [err]
                (comp/set-state! this {:msg (str "Error: " err)})))))

(defn- create-headcount! [this]
  (let [{:keys [active-org form-unit-id form-title form-level form-salary form-justification]} (comp/get-state this)]
    (when (and active-org (seq form-title))
      (let [mut (list 'headcount/create
                  {:headcount/org-id (:org/id active-org)
                   :headcount/unit-id (or form-unit-id "eng-dept")
                   :headcount/title form-title
                   :headcount/job-level (or form-level "L4")
                   :headcount/salary-band form-salary
                   :headcount/justification form-justification})]
        (comp/set-state! this {:submitting true :msg nil})
        (-> (transit/fetch-transit "/api/query" [{mut [:headcount/id :headcount/status :error]}])
            (.then (fn [{:keys [body]}]
                     (let [res (-> body first val)]
                       (if (:error res)
                         (comp/set-state! this {:msg (str "Error: " (get-in res [:error :message])) :submitting false})
                         (do
                           (comp/set-state! this {:msg "Headcount requisition submitted successfully!"
                                                  :form-title "" :form-justification "" :submitting false})
                           (fetch-inbox-and-reqs! this))))))
            (.catch (fn [err]
                      (comp/set-state! this {:msg (str "Failed to create requisition: " err) :submitting false}))))))))

(defsc HeadcountPage [this _props]
  {:query [:loading :error :active-org :pending-approvals :submitting :msg
           :form-unit-id :form-title :form-level :form-salary :form-justification]
   :initial-state {:loading true :error nil :active-org nil :pending-approvals []
                   :submitting false :msg nil :form-unit-id "" :form-title ""
                   :form-level "L4" :form-salary "$140,000 - $170,000" :form-justification ""}
   :componentDidMount (fn [this] (fetch-inbox-and-reqs! this))}
  (let [{:keys [loading active-org pending-approvals submitting msg
                form-unit-id form-title form-level form-salary form-justification]} (comp/get-state this)]
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-8"}
      (div {:className "border-b border-gray-200 pb-5 flex justify-between items-center"}
        (div nil
          (h1 {:className "text-2xl font-bold leading-7 text-gray-900"} "Headcount Requisitions & Approvals")
          (p {:className "mt-1 text-sm text-gray-500"} "Submit new hiring requests, review pending approvals, and track lifecycle"))
        (when active-org
          (span {:className "inline-flex items-center rounded-md bg-indigo-50 px-3 py-1 text-xs font-semibold text-indigo-700 ring-1 ring-inset ring-indigo-700/10"}
            (:org/name active-org))))

      ;; Approver Inbox
      (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
        (h3 {:className "text-base font-semibold text-gray-900 mb-4"} "Approver Inbox (Awaiting Your Decision)")
        (cond
          loading
          (p {:className "text-sm text-gray-500"} "Loading pending approvals...")

          (empty? pending-approvals)
          (div {:className "text-center py-6 bg-gray-50 rounded-lg border border-dashed border-gray-200"}
            (p {:className "text-sm text-gray-500"} "No requisitions currently awaiting your approval."))

          :else
          (table {:className "min-w-full divide-y divide-gray-200"}
            (thead nil
              (tr nil
                (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Req ID")
                (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Title")
                (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Unit")
                (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Level")
                (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Current Step")
                (th {:className "px-3 py-2 text-right text-xs font-semibold text-gray-500"} "Actions")))
            (tbody nil
              (mapv (fn [p-req]
                      (tr {:key (:headcount/id p-req) :className "border-t border-gray-100"}
                        (td {:className "px-3 py-2 text-sm text-gray-500"} (str (:headcount/id p-req)))
                        (td {:className "px-3 py-2 text-sm font-medium text-gray-900"} (:headcount/title p-req))
                        (td {:className "px-3 py-2 text-sm text-gray-600"} (:headcount/unit-id p-req))
                        (td {:className "px-3 py-2 text-sm text-gray-600"} (:headcount/job-level p-req))
                        (td {:className "px-3 py-2 text-sm text-gray-600"} (str "Step " (:headcount/current-step p-req)))
                        (td {:className "px-3 py-2 text-right space-x-2"}
                          (button {:onClick #(approve-step! this (:headcount/id p-req))
                                   :className "rounded bg-emerald-600 px-2.5 py-1 text-xs font-semibold text-white shadow-sm hover:bg-emerald-500"}
                            "Approve")
                          (button {:onClick #(reject-request! this (:headcount/id p-req))
                                   :className "rounded bg-rose-600 px-2.5 py-1 text-xs font-semibold text-white shadow-sm hover:bg-rose-500"}
                            "Reject"))))
                    pending-approvals)))))

      ;; Create Requisition Form
      (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
        (h3 {:className "text-base font-semibold text-gray-900 mb-4"} "Submit New Headcount Requisition")
        (when msg
          (p {:className (str "mb-4 text-sm " (if (.startsWith msg "Error") "text-red-600" "text-green-600"))} msg))
        (div {:className "grid grid-cols-1 gap-4 sm:grid-cols-2"}
          (div nil
            (label {:className "block text-xs font-medium text-gray-700 mb-1"} "Job Title")
            (input {:type "text" :placeholder "e.g. Senior Frontend Engineer"
                    :value form-title
                    :onChange #(comp/set-state! this {:form-title (.. % -target -value)})
                    :className "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}))
          (div nil
            (label {:className "block text-xs font-medium text-gray-700 mb-1"} "Department Unit ID")
            (input {:type "text" :placeholder "e.g. eng-dept"
                    :value form-unit-id
                    :onChange #(comp/set-state! this {:form-unit-id (.. % -target -value)})
                    :className "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}))
          (div nil
            (label {:className "block text-xs font-medium text-gray-700 mb-1"} "Job Level")
            (select {:value form-level
                     :onChange #(comp/set-state! this {:form-level (.. % -target -value)})
                     :className "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}
              (mapv (fn [lvl] (dom/option {:key lvl :value lvl} lvl))
                    ["L3" "L4" "L5" "L6" "L7" "VP" "C-Level"])))
          (div nil
            (label {:className "block text-xs font-medium text-gray-700 mb-1"} "Salary Band")
            (input {:type "text" :placeholder "e.g. $140k - $170k"
                    :value form-salary
                    :onChange #(comp/set-state! this {:form-salary (.. % -target -value)})
                    :className "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}))
          (div {:className "sm:col-span-2"}
            (label {:className "block text-xs font-medium text-gray-700 mb-1"} "Business Justification")
            (textarea {:rows 3
                       :placeholder "Reason for requisition and team capacity needs..."
                       :value form-justification
                       :onChange #(comp/set-state! this {:form-justification (.. % -target -value)})
                       :className "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"})))
        (div {:className "mt-6 flex justify-end"}
          (button {:onClick #(create-headcount! this)
                   :disabled submitting
                   :className "rounded-md bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50"}
            (if submitting "Submitting..." "Submit Requisition")))))))

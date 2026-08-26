(ns com.ozimos.workforce.frontend.ui.pages.headcount-replicant
  "Replicant rendering of Headcount page: pure props->hiccup via defrc.
   UI state lifted into Fulcro DB, events as pure data vectors."
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [clojure.string :as str]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

;; -----------------------------------------------------------------------------
;; Pure State Transitions (shared Web/Mobile)
;; -----------------------------------------------------------------------------

(defn set-form-field-state
  "Pure: assoc form field k with v."
  [db k v]
  (assoc db k v))

(defn clear-form-state
  "Pure: clear form fields after submit."
  [db]
  (assoc db :form-title "" :form-justification ""))

(defn set-pending-approvals-state
  [db approvals]
  (assoc db :pending-approvals approvals))

(defn set-loading-state
  [db v]
  (assoc db :loading v))

(defn set-error-state
  [db err]
  (assoc db :error err))

(defn set-msg-state
  [db msg]
  (assoc db :msg msg))

(defn set-submitting-state
  [db v]
  (assoc db :submitting v))

;; -----------------------------------------------------------------------------
;; Fulcro Wrappers (Web)
;; -----------------------------------------------------------------------------

(defmutation set-form-field
  [{:keys [field value]}]
  (action [{:keys [state]}]
    (swap! state set-form-field-state field value)))

(defmutation set-pending-approvals
  [{:keys [approvals]}]
  (action [{:keys [state]}]
    (swap! state set-pending-approvals-state approvals)))

(defmutation set-loading
  [{:keys [v]}]
  (action [{:keys [state]}]
    (swap! state set-loading-state v)))

(defmutation set-error
  [{:keys [error]}]
  (action [{:keys [state]}]
    (swap! state set-error-state error)))

(defmutation set-msg
  [{:keys [msg]}]
  (action [{:keys [state]}]
    (swap! state set-msg-state msg)))

(defmutation set-submitting
  [{:keys [v]}]
  (action [{:keys [state]}]
    (swap! state set-submitting-state v)))

(defmutation clear-form
  [_]
  (action [{:keys [state]}]
    (swap! state clear-form-state)))

;; -----------------------------------------------------------------------------
;; Root View (defrc)
;; -----------------------------------------------------------------------------

(defrc HeadcountReplicant
  {:query [:loading :error :active-org :pending-approvals :submitting :msg
           :form-unit-id :form-title :form-level :form-salary :form-bonus :form-justification]
   :ident :headcount-replicant/root}
  [{:keys [loading error active-org pending-approvals submitting msg
           form-unit-id form-title form-level form-salary form-bonus form-justification]}]
  (let [pending-approvals (or pending-approvals [])]
    [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8 space-y-8"}
     [:div {:class "border-b border-gray-200 pb-5 flex justify-between items-center"}
      [:div
       [:h1 {:class "text-2xl font-bold leading-7 text-gray-900"} "Headcount Requisitions & Approvals"]
       [:p {:class "mt-1 text-sm text-gray-500"} "Submit new hiring requests, review pending approvals, and track lifecycle"]]
      (when active-org
        [:span {:class "inline-flex items-center rounded-md bg-indigo-50 px-3 py-1 text-xs font-semibold text-indigo-700 ring-1 ring-inset ring-indigo-700/10"}
         (:org/name active-org)])]

     ;; Approver Inbox
     [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
      [:h3 {:class "text-base font-semibold text-gray-900 mb-4"} "Approver Inbox (Awaiting Your Decision)"]
      (cond
        loading
        [:p {:class "text-sm text-gray-500"} "Loading pending approvals..."]

        (empty? pending-approvals)
        [:div {:class "text-center py-6 bg-gray-50 rounded-lg border border-dashed border-gray-200"}
         [:p {:class "text-sm text-gray-500"} "No requisitions currently awaiting your approval."]]

        :else
        [:table {:class "min-w-full divide-y divide-gray-200"}
         [:thead
          [:tr
           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Req ID"]
           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Title"]
           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Unit"]
           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Level"]
           [:th {:class "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Current Step"]
           [:th {:class "px-3 py-2 text-right text-xs font-semibold text-gray-500"} "Actions"]]]
         (into [:tbody]
               (map (fn [p-req]
                      [:tr {:replicant/key (str (:headcount/id p-req)) :class "border-t border-gray-100"}
                       [:td {:class "px-3 py-2 text-sm text-gray-500"} (str (:headcount/id p-req))]
                       [:td {:class "px-3 py-2 text-sm font-medium text-gray-900"} (:headcount/title p-req)]
                       [:td {:class "px-3 py-2 text-sm text-gray-600"} (:headcount/unit-id p-req)]
                       [:td {:class "px-3 py-2 text-sm text-gray-600"} (:headcount/job-level p-req)]
                       [:td {:class "px-3 py-2 text-sm text-gray-600"} (str "Step " (:headcount/current-step p-req))]
                       [:td {:class "px-3 py-2 text-right space-x-2"}
                        [:button {:class "rounded bg-emerald-600 px-2.5 py-1 text-xs font-semibold text-white shadow-sm hover:bg-emerald-500"
                                  :on {:click [::approve (:headcount/id p-req)]}} "Approve"]
                        [:button {:class "rounded bg-rose-600 px-2.5 py-1 text-xs font-semibold text-white shadow-sm hover:bg-rose-500"
                                  :on {:click [::reject (:headcount/id p-req)]}} "Reject"]]])
                 pending-approvals))]
          )]

     ;; Create Requisition Form
     [:div {:class "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
      [:h3 {:class "text-base font-semibold text-gray-900 mb-4"} "Submit New Headcount Requisition"]
      (when msg
        [:p {:class (str "mb-4 text-sm " (if (str/starts-with? msg "Error") "text-red-600" "text-green-600"))} msg])
      [:div {:class "grid grid-cols-1 gap-4 sm:grid-cols-2"}
       [:div
        [:label {:class "block text-xs font-medium text-gray-700 mb-1"} "Job Title"]
        [:input {:type "text" :placeholder "e.g. Senior Frontend Engineer"
                 :value (or form-title "")
                 :on {:input [::set-form-field :form-title]}
                 :class "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}]]
       [:div
        [:label {:class "block text-xs font-medium text-gray-700 mb-1"} "Department Unit ID"]
        [:input {:type "text" :placeholder "e.g. eng-dept"
                 :value (or form-unit-id "")
                 :on {:input [::set-form-field :form-unit-id]}
                 :class "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}]]
       [:div
        [:label {:class "block text-xs font-medium text-gray-700 mb-1"} "Job Level"]
        (into [:select {:value (or form-level "L4")
                        :on {:change [::set-form-field :form-level]}
                        :class "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}]
              (map (fn [lvl] [:option {:replicant/key lvl :value lvl} lvl])
                   ["L3" "L4" "L5" "L6" "L7" "VP" "C-Level"]))]
       [:div
        [:label {:class "block text-xs font-medium text-gray-700 mb-1"} "Salary Band"]
        [:input {:type "text" :placeholder "e.g. $140k - $170k"
                 :value (or form-salary "")
                 :on {:input [::set-form-field :form-salary]}
                 :class "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}]]
       [:div {:class "sm:col-span-2"}
        [:label {:class "block text-xs font-medium text-gray-700 mb-1"} "Business Justification"]
        [:textarea {:rows 3
                    :placeholder "Reason for requisition and team capacity needs..."
                    :value (or form-justification "")
                    :on {:input [::set-form-field :form-justification]}
                    :class "w-full rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 sm:text-sm"}]]]
      [:div {:class "mt-6 flex justify-end"}
       [:button {:class "rounded-md bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50"
                 :disabled submitting
                 :on {:click [::create]}}
        (if submitting "Submitting..." "Submit Requisition")]]]]))

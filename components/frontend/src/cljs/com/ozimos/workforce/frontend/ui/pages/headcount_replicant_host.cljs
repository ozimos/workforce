(ns com.ozimos.workforce.frontend.ui.pages.headcount-replicant-host
  "Fulcro host for Replicant Headcount page. Mounts pure view via bridge."
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant :as cr]
   [goog.dom :as gdom]))

(defsc HeadcountReplicantHost [this _props]
  {:query [:loading :error :active-org :pending-approvals :submitting :msg
           :form-unit-id :form-title :form-level :form-salary :form-bonus :form-justification]
   :initial-state {:loading false :error nil :active-org {:org/name "Demo Co"} :pending-approvals [{:headcount/id "req-1" :headcount/title "Senior Engineer" :headcount/unit-id "eng-dept" :headcount/job-level "L4" :headcount/current-step 1}]
                   :submitting false :msg nil :form-unit-id "" :form-title ""
                   :form-level "L4" :form-salary "$140,000 - $170,000" :form-bonus "15%" :form-justification ""}
   :componentDidMount
   (fn [this]
     (let [app  (comp/any->app this)
           node (gdom/getElement "replicant-headcount")
           handlers
           {::cr/set-form-field (fn [ev field]
                                  (let [v (some-> ev :replicant/js-event .-target .-value)]
                                    (comp/transact! app [(cr/set-form-field {:field field :value (or v "")})])))
            ::cr/approve (fn [_ev id] (comp/transact! app [(cr/set-msg {:msg (str "Approve " id)})]))
            ::cr/reject  (fn [_ev id] (comp/transact! app [(cr/set-msg {:msg (str "Reject " id)})]))
            ::cr/create  (fn [_ev] (comp/transact! app [(cr/set-msg {:msg "Headcount requisition submitted successfully!"})]))}]
       (when node
         (bridge/install-replicant-root! app cr/HeadcountReplicant node handlers))))}
  (dom/div {:id "replicant-headcount-host" :className "min-h-full"}
    (dom/div {:id "replicant-headcount"} "Loading Replicant Headcount…")
    (dom/p {:className "text-xs text-gray-400 mt-4 text-center"}
      "Replicant headcount via defrc — pure hiccup.")))

(ns com.ozimos.workforce.org.workflows.headcount
  "Statechart-driven headcount requisition and hiring approval workflow.
   Can be executed directly within Fulcro frontend components or driven by
   the Escapement autonomous agent runner."
  (:require
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :refer [on-entry state transition]]))

(def hiring-approval-chart
  "Statechart governing the full lifecycle of a workforce headcount requisition:
   :draft -> :hiring-manager-review -> :director-review -> :finance-approval -> :approved -> :hire-transition -> :filled"
  (chart/statechart {:id :headcount-workflow
                     :initial :draft}
    ;; 1. Draft Requisition Creation
    (state {:id :draft}
      (transition {:event :headcount/submit
                   :target :hiring-manager-review}))

    ;; 2. Step 1: Hiring Manager Review
    (state {:id :hiring-manager-review}
      (on-entry {:target :notify-hiring-manager})
      (transition {:event :headcount/approve
                   :target :director-review})
      (transition {:event :headcount/reject
                   :target :rejected})
      (transition {:event :org/reparent
                   :target :hiring-manager-review})) ;; Re-evaluate approver on org change

    ;; 3. Step 2: Department Director Review
    (state {:id :director-review}
      (on-entry {:target :notify-director})
      (transition {:event :headcount/approve
                   :target :finance-approval})
      (transition {:event :headcount/reject
                   :target :rejected})
      (transition {:event :org/reparent
                   :target :director-review}))

    ;; 4. Step 3: Finance / VP Quorum Approval
    (state {:id :finance-approval}
      (on-entry {:target :notify-finance})
      (transition {:event :headcount/approve
                   :target :approved})
      (transition {:event :headcount/reject
                   :target :rejected}))

    ;; 5. Fully Approved Requisition
    (state {:id :approved}
      (transition {:event :headcount/start-hire
                   :target :hire-transition})
      (transition {:event :headcount/cancel
                   :target :cancelled}))

    ;; 6. Hire Transition (Converting candidate to active employee)
    (state {:id :hire-transition}
      (transition {:event :hire/complete
                   :target :filled})
      (transition {:event :hire/fail
                   :target :approved}))

    ;; Terminal States
    (state {:id :filled})
    (state {:id :rejected})
    (state {:id :cancelled})))

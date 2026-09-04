(ns com.ozimos.workforce.frontend.ui.pages.org-chart
  "THIN FACADE — the canonical Replicant OrgChart implementation lives in
   `com.ozimos.workforce.frontend.views.org-chart` (.cljc). This namespace
   re-exports the public API so existing callers (root,
   dept_dashboard, core_routing_test) keep working.

   NOTE: emitted `:on` event keywords are namespace-qualified with the CANONICAL
   ns (`views.org-chart/...`), not this one. Do NOT add implementation here."
  (:require
   [com.ozimos.workforce.frontend.views.org-chart :as v]))

(def OrgChart v/OrgChart)
(def DivisionItem v/DivisionItem)
(def DeptItem v/DeptItem)
(def OrgUnit v/OrgUnit)

(def toggle-collapse v/toggle-collapse)
(def expand-all v/expand-all)
(def collapse-all v/collapse-all)
(def set-search-term v/set-search-term)

(ns com.ozimos.workforce.frontend.web.events
  (:require
   [replicant.dom :as r]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant :as nav]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant :as org-chart]
   [com.ozimos.workforce.frontend.web.router :as router]))

(defn dispatch-event!
  [app-state ev-data js-event]
  (let [[event-type & args] ev-data]
    (case event-type
      ;; Navigation events
      ::nav/toggle-dropdown
      (swap! app-state update-in [:nav-state] nav/toggle-dropdown-state)

      ::nav/switch-org
      (let [[org-id] args]
        (swap! app-state assoc-in [:nav-state :active-org :org/id] org-id))

      ::nav/logout
      (do
        (when (exists? js/localStorage)
          (.removeItem js/localStorage "access-token")
          (.removeItem js/localStorage "refresh-token"))
        (set! js/window.location.href "/login"))

      ;; Org Chart events
      ::org-chart/toggle-collapse
      (let [[id] args]
        (swap! app-state update :page-state org-chart/toggle-collapse-state id))

      ::org-chart/expand-all
      (swap! app-state update :page-state org-chart/expand-all-state)

      ::org-chart/collapse-all
      (swap! app-state update :page-state org-chart/collapse-all-state)

      ::org-chart/set-search-term
      (let [val (some-> js-event .-target .-value)]
        (swap! app-state update :page-state org-chart/set-search-term-state (or val "")))

      ::org-chart/navigate
      (let [[path] args]
        (if (.startsWith (str path) "/")
          (router/navigate! app-state (router/path->route path))
          (set! js/window.location.href path)))

      ;; Fallback / general logging
      (js/console.log "[replicant-event]" event-type args))))

(defn register-event-handlers!
  [app-state]
  (r/set-dispatch!
   (fn [event-data]
     (let [ev-data (:replicant/data event-data)
           js-event (:replicant/js-event event-data)]
       (when (vector? ev-data)
         (dispatch-event! app-state ev-data js-event))))))

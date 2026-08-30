(ns com.ozimos.workforce.frontend.web.core
  (:require
   [replicant.dom :as r]
   [goog.dom :as gdom]
   [com.ozimos.workforce.frontend.ui.root-replicant :as root]
   [com.ozimos.workforce.frontend.web.router :as router]
   [com.ozimos.workforce.frontend.web.events :as events]))

;; Plain central app-state atom (zero Fulcro / React DOM dependencies)
(defonce app-state
  (atom {:current-route :route/home
         :nav-state {:active-org {:org/name "Demo Co" :org/role "ADMIN"}
                     :orgs [{:org/id "1" :org/name "Demo Co"}]
                     :dropdown-open false}
         :page-state {}}))

(defonce ^:private render-scheduled? (atom false))

(defn render-app! []
  (when-let [el (gdom/getElement "app")]
    (r/render el (root/RootView @app-state))))

(defn schedule-render! []
  (when (compare-and-set! render-scheduled? false true)
    (if (exists? js/requestAnimationFrame)
      (js/requestAnimationFrame
       (fn []
         (reset! render-scheduled? false)
         (render-app!)))
      (js/setTimeout
       (fn []
         (reset! render-scheduled? false)
         (render-app!))
       0))))

(defn ^:export init []
  (try
    (events/register-event-handlers! app-state)
    (router/init-router! app-state)
    (add-watch app-state ::renderer (fn [_ _ _ _] (schedule-render!)))
    (render-app!)
    (catch :default e
      (js/console.error "Replicant web init failed:" e))))

(defn ^:export refresh []
  (try
    (render-app!)
    (catch :default e
      (js/console.error "Replicant web remount failed:" e))))

(when (exists? js/window)
  (.addEventListener js/window "error"
    (fn [e] (js/console.error "Uncaught error:" (.-error e) (.-message e))))

  (.addEventListener js/window "unhandledrejection"
    (fn [e] (js/console.error "Unhandled rejection:" (.-reason e))))

  (init))

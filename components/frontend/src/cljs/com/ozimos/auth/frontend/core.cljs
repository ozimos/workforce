(ns com.ozimos.auth.frontend.core
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.auth.frontend.ui.root :as root]))

(defonce app-inst (app/fulcro-app {}))

(defn ^:export init []
  (try
    (app/mount! app-inst root/Root "app")
    (catch :default e
      (js/console.error "Fulcro mount failed:" e))))

(defn ^:export refresh []
  (try
    (app/mount! app-inst root/Root "app")
    (catch :default e
      (js/console.error "Fulcro remount failed:" e))))

(when (exists? js/window)
  (.addEventListener js/window "error"
    (fn [e] (js/console.error "Uncaught error:" (.-error e) (.-message e))))

  (.addEventListener js/window "unhandledrejection"
    (fn [e] (js/console.error "Unhandled rejection:" (.-reason e))))

  (init))

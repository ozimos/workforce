(ns com.ozimos.auth.frontend.core
  (:require [com.fulcrologic.fulcro.application :as app]
            [com.ozimos.auth.frontend.ui.root :as root]))

(defonce app-inst (app/fulcro-app {}))

(defn ^:export init []
  (app/mount! app-inst root/Root "app"))

(init)

(ns com.ozimos.auth.frontend.ui.components.nav
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div nav span]]))

(defn- logout []
  (when (exists? js/localStorage)
    (.removeItem js/localStorage "access-token")
    (.removeItem js/localStorage "refresh-token")
    (.removeItem js/localStorage "username"))
  (set! js/window.location.pathname "/login"))

(defsc NavBar [_ _props]
  {:query []
   :initial-state {}}
  (nav {:className "bg-white shadow-sm border-b border-gray-200"}
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8"}
      (div {:className "flex h-16 justify-between"}
        (div {:className "flex items-center"}
          (a {:href "/" :className "text-xl font-bold text-gray-900"} "Best Auth"))
        (div {:className "flex items-center gap-4"}
          (span {:className "text-sm text-gray-500"}
            (or (and (exists? js/localStorage) (.getItem js/localStorage "username")) "User"))
          (button {:onClick logout
                   :className "rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"}
            "Log out"))))))

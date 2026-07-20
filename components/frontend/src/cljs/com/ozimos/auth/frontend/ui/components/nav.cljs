(ns com.ozimos.auth.frontend.ui.components.nav)

(defn- logout []
  (.removeItem js/localStorage "access-token")
  (.removeItem js/localStorage "refresh-token")
  (.removeItem js/localStorage "username")
  (set! js/window.location.hash "#!/login"))

(defn nav-bar
  []
  [:nav {:class "bg-white shadow-sm border-b border-gray-200"}
   [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8"}
    [:div {:class "flex h-16 justify-between"}
     [:div {:class "flex items-center"}
      [:a {:href "#!/" :class "text-xl font-bold text-gray-900"} "Best Auth"]]
     [:div {:class "flex items-center gap-4"}
      [:span {:class "text-sm text-gray-500"} (or (.getItem js/localStorage "username") "User")]
      [:button {:on-click logout
                :class "rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"}
       "Log out"]]]]])

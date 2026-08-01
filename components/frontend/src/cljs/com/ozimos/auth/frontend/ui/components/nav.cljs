(ns com.ozimos.auth.frontend.ui.components.nav
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div nav]]
   [com.ozimos.auth.frontend.json :as json]))

(defn- logout []
  (when (exists? js/localStorage)
    (.removeItem js/localStorage "access-token")
    (.removeItem js/localStorage "refresh-token")
    (.removeItem js/localStorage "username")
    (.removeItem js/localStorage "email"))
  (set! js/window.location.pathname "/login"))

(defn- fetch-user-info! [this]
  (when (and (exists? js/localStorage)
             (.getItem js/localStorage "access-token")
             (or (nil? (.getItem js/localStorage "email"))
                 (nil? (.getItem js/localStorage "username"))))
    (-> (json/fetch-json "/api/query" "POST" {:eql "[:current-user/email :current-user/username]"})
        (.then (fn [{:keys [status body]}]
                 (when (= 200 status)
                   (let [data (get-in body [:data])]
                     (when-let [e (:current-user/email data)]
                       (.setItem js/localStorage "email" e))
                     (when-let [u (:current-user/username data)]
                       (.setItem js/localStorage "username" u))
                     (comp/set-state! this {:fetched true}))))))))

(defn uncompleted-steps-count
  "Calculate uncompleted security steps for a user map."
  [user]
  (if (get user :user/mfa-enabled? false)
    0
    1))

(defsc NavBar [this _props]
  {:query [:fetched]
   :initial-state {:fetched false}
   :componentDidMount (fn [this] (fetch-user-info! this))}
  (let [email (and (exists? js/localStorage) (.getItem js/localStorage "email"))
        username (and (exists? js/localStorage) (.getItem js/localStorage "username"))
        mfa-enabled? (and (exists? js/localStorage) (= "true" (.getItem js/localStorage "mfa-enabled")))
        uncompleted-count (uncompleted-steps-count {:user/mfa-enabled? mfa-enabled?})
        effective-username (if (seq username) username "_")
        label (if (seq email)
                (str email " | " effective-username)
                effective-username)]
    (nav {:className "bg-white shadow-sm border-b border-gray-200"}
      (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8"}
        (div {:className "flex h-16 justify-between"}
          (div {:className "flex items-center gap-6"}
            (a {:href "/" :className "text-xl font-bold text-gray-900"} "Best Auth")
            (a {:href "/org-dashboard" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Dashboard")
            (a {:href "/create-org" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Create Org")
            (a {:href "/join-org" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Join Org"))
          (div {:className "flex items-center gap-4"}
            (a {:href "/profile" :className "flex items-center gap-2 text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
              (dom/svg {:xmlns "http://www.w3.org/2000/svg"
                        :fill "none"
                        :viewBox "0 0 24 24"
                        :strokeWidth "1.5"
                        :stroke "currentColor"
                        :className "w-5 h-5"}
                (dom/path {:strokeLinecap "round"
                           :strokeLinejoin "round"
                           :d "M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.501 20.118a7.5 7.5 0 0 1 14.998 0A17.933 17.933 0 0 1 12 21.75c-2.676 0-5.216-.584-7.499-1.632Z"}))
              label
              (when (> uncompleted-count 0)
                (dom/span {:id "security-steps-badge"
                           :className "inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 ml-1"}
                  (str uncompleted-count))))
            (button {:onClick logout
                     :className "rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"}
              "Log out")))))))

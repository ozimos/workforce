(ns com.ozimos.auth.frontend.ui.components.nav
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div nav span]]
   [com.ozimos.auth.frontend.transit :as transit]))

(defn- logout []
  (when (exists? js/localStorage)
    (.removeItem js/localStorage "access-token")
    (.removeItem js/localStorage "refresh-token")
    (.removeItem js/localStorage "username")
    (.removeItem js/localStorage "email")
    (.removeItem js/localStorage "verified"))
  (set! js/window.location.pathname "/login"))

(defn- fetch-user-info! [this]
  (when (and (exists? js/localStorage)
             (.getItem js/localStorage "access-token"))
    (-> (transit/fetch-transit "/api/query" [:current-user/email :current-user/username :current-user/verified])
        (.then (fn [{:keys [status body]}]
                 (when (= 200 status)
                   (let [data body]
                     (when-let [e (:current-user/email data)]
                       (.setItem js/localStorage "email" e))
                     (when-let [u (:current-user/username data)]
                       (.setItem js/localStorage "username" u))
                     (when (some? (:current-user/verified data))
                       (.setItem js/localStorage "verified" (str (:current-user/verified data))))
                     (comp/set-state! this {:fetched true}))))))))

(defn uncompleted-steps-count
  "Calculate uncompleted security steps for a user map."
  [user]
  (if (get user :user/mfa-enabled? false)
    0
    1))

(defn- verification-banner [verified?]
  (when (false? verified?)
    (div {:className "bg-amber-50 border-b border-amber-200 py-2 px-4 text-xs text-amber-800 flex justify-between items-center"}
      (div {:className "flex items-center gap-2"}
        (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "w-4 h-4 text-amber-600" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
          (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"}))
        (span nil "Your email address is unverified. Please check your inbox to verify your account."))
      (a {:href "/verify" :className "underline font-semibold hover:text-amber-900"} "Verify Email"))))

(defsc NavBar [this _props]
  {:query [:fetched]
   :initial-state {:fetched false}
   :componentDidMount (fn [this] (fetch-user-info! this))}
  (let [email (and (exists? js/localStorage) (.getItem js/localStorage "email"))
        username (and (exists? js/localStorage) (.getItem js/localStorage "username"))
        verified? (and (exists? js/localStorage) (= "true" (.getItem js/localStorage "verified")))
        mfa-enabled? (and (exists? js/localStorage) (= "true" (.getItem js/localStorage "mfa-enabled")))
        uncompleted-count (uncompleted-steps-count {:user/mfa-enabled? mfa-enabled?})
        effective-username (if (seq username) username "_")
        label (if (seq email)
                (str email " | " effective-username)
                effective-username)]
    (div nil
      (verification-banner verified?)
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
                "Log out"))))))))

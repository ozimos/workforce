(ns com.ozimos.workforce.frontend.ui.components.nav
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div nav span]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- logout []
  (when (exists? js/localStorage)
    (.removeItem js/localStorage "access-token")
    (.removeItem js/localStorage "refresh-token")
    (.removeItem js/localStorage "username")
    (.removeItem js/localStorage "email")
    (.removeItem js/localStorage "verified"))
  (set! js/window.location.pathname "/login"))

(defn- switch-org! [this org-id]
  (comp/set-state! this {:dropdown-open false})
  (-> (transit/fetch-transit "/api/query" [(list 'org/switch {:org/id org-id})])
      (.then (fn []
               (set! js/window.location.reload true)))))

(defn- fetch-user-info! [this]
  (when (and (exists? js/localStorage)
             (.getItem js/localStorage "access-token"))
    (-> (transit/fetch-transit "/api/query"
          [:current-user/email :current-user/username :current-user/verified
           {:user/active-org [:org/id :org/name :org/role]}
           {:user/orgs [:org/id :org/name]}])
        (.then (fn [{:keys [status body]}]
                 (when (= 200 status)
                   (let [data body
                         active-org (:user/active-org data)
                         orgs (:user/orgs data)]
                     (when-let [e (:current-user/email data)]
                       (.setItem js/localStorage "email" e))
                     (when-let [u (:current-user/username data)]
                       (.setItem js/localStorage "username" u))
                     (when (some? (:current-user/verified data))
                       (.setItem js/localStorage "verified" (str (:current-user/verified data))))
                     (comp/set-state! this {:fetched true
                                            :active-org active-org
                                            :orgs (or orgs [])}))))))))

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

(defn- org-switcher [this active-org orgs dropdown-open?]
  (div {:className "relative inline-block text-left"}
    (if active-org
      (button {:onClick #(comp/set-state! this {:dropdown-open (not dropdown-open?)})
               :className "inline-flex items-center gap-1.5 rounded-full bg-indigo-50 px-3 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-100 ring-1 ring-inset ring-indigo-700/10 focus:outline-none"}
        (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "w-3.5 h-3.5 text-indigo-600" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
          (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5m3 0v-4a1 1 0 011-1h2a1 1 0 011 1v4m-4 0h4"}))
        (span nil (:org/name active-org))
        (span {:className "text-indigo-400 font-normal ml-0.5"} (str "(" (:org/role active-org) ")"))
        (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "w-3 h-3 text-indigo-500 ml-0.5" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
          (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M19 9l-7 7-7-7"})))
      (a {:href "/create-org" :className "inline-flex items-center gap-1 text-xs font-semibold text-indigo-600 hover:text-indigo-500"}
        "+ Create Org"))
    (when dropdown-open?
      (div {:className "absolute left-0 z-20 mt-2 w-56 origin-top-left rounded-md bg-white p-1 shadow-lg ring-1 ring-black ring-opacity-5 focus:outline-none"}
        (div {:className "px-3 py-2 text-xs font-medium text-gray-500 border-b border-gray-100"} "Switch Organization")
        (mapv (fn [org]
                (let [is-active (= (:org/id org) (:org/id active-org))]
                  (button {:key (:org/id org)
                           :onClick #(switch-org! this (:org/id org))
                           :className (str "flex w-full items-center justify-between px-3 py-2 text-xs text-left rounded-md font-medium "
                                           (if is-active "bg-indigo-50 text-indigo-700" "text-gray-700 hover:bg-gray-50"))}
                    (span nil (:org/name org))
                    (when is-active
                      (dom/svg {:xmlns "http://www.w3.org/2000/svg" :className "w-3.5 h-3.5 text-indigo-600" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                        (dom/path {:strokeLinecap "round" :strokeLinejoin "round" :strokeWidth "2" :d "M5 13l4 4L19 7"}))))))
              orgs)
        (div {:className "border-t border-gray-100 mt-1 pt-1"}
          (a {:href "/create-org" :className "block px-3 py-1.5 text-xs text-indigo-600 font-semibold hover:bg-indigo-50 rounded-md"} "+ Create New Org")
          (a {:href "/join-org" :className "block px-3 py-1.5 text-xs text-gray-600 font-medium hover:bg-gray-50 rounded-md"} "Join Org via Invitation"))))))

(defsc NavBar [this _props]
  {:query [:fetched :active-org :orgs :dropdown-open]
   :initial-state {:fetched false :active-org nil :orgs [] :dropdown-open false}
   :componentDidMount (fn [this] (fetch-user-info! this))}
  (let [email (and (exists? js/localStorage) (.getItem js/localStorage "email"))
        username (and (exists? js/localStorage) (.getItem js/localStorage "username"))
        verified? (and (exists? js/localStorage) (= "true" (.getItem js/localStorage "verified")))
        mfa-enabled? (and (exists? js/localStorage) (= "true" (.getItem js/localStorage "mfa-enabled")))
        {:keys [active-org orgs dropdown-open]} (comp/get-state this)
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
            (div {:className "flex items-center gap-5"}
              (a {:href "/" :className "text-xl font-bold text-gray-900"} "Workforce")
              (org-switcher this active-org orgs dropdown-open)
              (a {:href "/org-chart" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Org Chart")
              (a {:href "/dept-dashboard" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Analytics")
              (a {:href "/headcount" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Headcount")
              (a {:href "/policies" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Policies")
              (a {:href "/org-dashboard" :className "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Members"))
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
                  (span {:id "security-steps-badge"
                         :className "inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 ml-1"}
                    (str uncompleted-count))))
              (button {:onClick logout
                       :className "rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"}
                "Log out"))))))))

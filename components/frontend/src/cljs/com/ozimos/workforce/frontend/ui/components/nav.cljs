(ns com.ozimos.workforce.frontend.ui.components.nav
  (:require-macros
   [com.ozimos.workforce.frontend.defrc :refer [defrc]])
  (:require
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]))

(defn toggle-dropdown-state [db] (update db :dropdown-open not))
(defn set-fetched-state [db v] (assoc db :fetched v))
(defn set-active-org-state [db v] (assoc db :active-org v))
(defn set-orgs-state [db v] (assoc db :orgs v))
(defn uncompleted-steps-count [user] (if (:user/mfa-enabled? user) 0 1))

(defmutation toggle-dropdown [_] (action [{:keys [state]}] (swap! state toggle-dropdown-state)))
(defmutation set-fetched [{:keys [v]}] (action [{:keys [state]}] (swap! state set-fetched-state v)))
(defmutation set-active-org [{:keys [active-org]}] (action [{:keys [state]}] (swap! state set-active-org-state active-org)))
(defmutation set-orgs [{:keys [orgs]}] (action [{:keys [state]}] (swap! state set-orgs-state orgs)))

(defrc NavBar
  {:query [:active-org :orgs :dropdown-open]
   :ident :nav/root}
  [{:keys [active-org orgs dropdown-open]}]
  (let [orgs (or orgs []) dropdown-open (boolean dropdown-open)]
    [:div
     [:nav {:class "bg-white shadow-sm border-b border-gray-200"}
      [:div {:class "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8"}
       [:div {:class "flex h-16 justify-between"}
        [:div {:class "flex items-center gap-5"}
         [:a {:href "/" :class "text-xl font-bold text-gray-900"} "Workforce"]
         [:div {:class "relative inline-block text-left"}
          (if active-org
            [:button {:class "inline-flex items-center gap-1.5 rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-100 ring-1 ring-inset ring-indigo-700/10 focus:outline-none"
                      :on {:click [::toggle-dropdown]}}
             [:span (:org/name active-org)]
             [:span {:class "text-indigo-400 font-normal ml-0.5"} (str "(" (:org/role active-org) ")")]]
            [:a {:href "/create-org" :class "inline-flex items-center gap-1 text-xs font-semibold text-indigo-600 hover:text-indigo-500"} "+ Create Org"])
          (when dropdown-open
            [:div {:class "absolute left-0 z-20 mt-2 w-56 origin-top-left rounded-md bg-white p-1 shadow-lg ring-1 ring-black ring-opacity-5 focus:outline-none"}
             [:div {:class "px-3 py-2 text-xs font-medium text-gray-500 border-b border-gray-100"} "Switch Organization"]
             (into [:div]
                   (map (fn [org]
                          (let [is-active (= (:org/id org) (:org/id active-org))]
                            [:button {:replicant/key (str (:org/id org))
                                      :class (str "flex w-full items-center justify-between px-3 py-2 text-xs text-left rounded-md font-medium " (if is-active "bg-indigo-50 text-indigo-700" "text-gray-700 hover:bg-gray-50"))
                                      :on {:click [::switch-org (:org/id org)]}} [:span (:org/name org)]]))
                        orgs))
             [:div {:class "border-t border-gray-100 mt-1 pt-1"}
              [:a {:href "/create-org" :class "block px-3 py-1.5 text-xs text-indigo-600 font-semibold hover:bg-indigo-50 rounded-md"} "+ Create New Org"]
              [:a {:href "/join-org" :class "block px-3 py-1.5 text-xs text-gray-600 font-medium hover:bg-gray-50 rounded-md"} "Join Org via Invitation"]]])]
         [:a {:href "/org-chart" :class "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Org Chart"]
         [:a {:href "/dept-dashboard" :class "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Analytics"]
         [:a {:href "/headcount" :class "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Headcount"]
         [:a {:href "/policies" :class "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Policies"]
         [:a {:href "/org-dashboard" :class "text-sm font-medium text-gray-600 hover:text-indigo-600"} "Members"]]
        [:div {:class "flex items-center gap-4"}
         [:a {:href "/profile" :class "flex items-center gap-2 text-sm font-semibold text-indigo-600 hover:text-indigo-500"} "Profile"]
         [:button {:class "rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-700 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
                   :on {:click [::logout]}} "Log out"]]]]]]))

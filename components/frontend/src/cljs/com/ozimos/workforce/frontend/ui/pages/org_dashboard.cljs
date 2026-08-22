(ns com.ozimos.workforce.frontend.ui.pages.org-dashboard
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [button div h1 h3 input option p select table tbody td th thead tr]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- load-members [this org-id]
  (let [query [{[:org/id org-id]
                [:org/name {:org/members
                            [:user/id :membership/role :membership/status :membership/joined-at]}]}]]
    (comp/set-state! this {:members-loading true})
    (-> (transit/fetch-transit "/api/query" query)
        (.then (fn [{:keys [body]}]
                 (let [org (-> body first val)]
                   (comp/set-state! this {:members (:org/members org) :members-loading false}))))
        (.catch (fn [_]
                  (comp/set-state! this {:members-error "Failed to load members" :members-loading false}))))))

(defn- switch-org [this org-id]
  (let [mut  (list 'org/switch {:org/id org-id})
        query [{mut [:org/id]}]]
    (-> (transit/fetch-transit "/api/query" query)
        (.then (fn [] (set! js/window.location.pathname "/"))))))

(defn- send-invite [this]
  (let [{:keys [invite-email invite-role active-org]} (comp/get-state this)]
    (when active-org
      (let [mut  (list 'org/invite {:org/id (:org/id active-org)
                                    :invitation/email invite-email
                                    :invitation/role (or invite-role "MEMBER")})
            query [{mut [:invitation/id :invitation/errors]}]]
        (comp/set-state! this {:invite-loading true :invite-msg nil})
        (-> (transit/fetch-transit "/api/query" query)
            (.then (fn [{:keys [body]}]
                     (let [result (-> body first val)]
                       (if (:invitation/errors result)
                         (comp/set-state! this
                           {:invite-msg (or (-> result :invitation/errors first second first)
                                            "Failed to send invitation")
                            :invite-loading false})
                         (do
                           (comp/set-state! this {:invite-msg "Invitation sent!" :invite-email "" :invite-loading false})
                           (load-members this (:org/id active-org)))))))
            (.catch (fn [_]
                      (comp/set-state! this {:invite-msg "Failed to send invitation" :invite-loading false}))))))))

(defsc OrgDashboard [this _props]
  {:query [:loading :error-msg :active-org :orgs :members :members-loading :members-error
           :invite-email :invite-role :invite-loading :invite-msg]
   :initial-state {:loading true :error-msg nil :active-org nil :orgs []
                   :members [] :members-loading false :members-error nil
                   :invite-email "" :invite-role "MEMBER" :invite-loading false :invite-msg nil}}
  (let [{:keys [loading error-msg active-org orgs members members-loading members-error
                invite-email invite-role invite-loading invite-msg]} (comp/get-state this)]
    (div {:className "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-8"}
      (div {:key "header" :className "border-b border-gray-200 pb-6"}
        (h1 {:className "text-3xl font-bold leading-tight text-gray-900"}
          (or (:org/name active-org) "Dashboard"))
        (p {:className "mt-2 text-sm text-gray-500"} "Manage your organization"))
      (if loading
        (div {:key "loading" :className "mt-8 text-center"}
          (p {:className "text-gray-500"} "Loading..."))
        (div {:key "content" :className "mt-8 space-y-8"}
          (when (> (count orgs) 1)
            (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
              (h3 {:className "text-base font-semibold text-gray-900"} "Switch Organization")
              (div {:className "mt-4 flex flex-wrap gap-2"}
                (mapv (fn [org]
                        (button {:key (:org/id org)
                                 :onClick #(switch-org this (:org/id org))
                                 :className (str "rounded-md px-3 py-1.5 text-sm font-semibold "
                                              (if (= (:org/id org) (:org/id active-org))
                                                "bg-indigo-600 text-white"
                                                "bg-white text-gray-700 ring-1 ring-inset ring-gray-300 hover:bg-gray-50"))}
                          (:org/name org)))
                      orgs))))
          (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
            (h3 {:className "text-base font-semibold text-gray-900"} "Invite Member")
            (when invite-msg
              (p {:className "mt-2 text-sm text-green-600"} invite-msg))
            (div {:className "mt-4 flex gap-3"}
              (input {:type "email" :placeholder "Email address"
                      :value invite-email
                      :onChange #(comp/set-state! this {:invite-email (.. % -target -value)})
                      :className "flex-1 rounded-md border-0 px-3 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 focus:ring-2 focus:ring-indigo-600 sm:text-sm"})
              (select {:value invite-role
                       :onChange #(comp/set-state! this {:invite-role (.. % -target -value)})}
                (option {:value "MEMBER"} "Member")
                (option {:value "ADMIN"} "Admin"))
              (button {:onClick #(send-invite this)
                       :disabled invite-loading
                       :className "rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50"}
                (if invite-loading "Sending..." "Send Invite"))))
          (div {:className "rounded-lg border border-gray-200 bg-white p-6 shadow-sm"}
            (h3 {:className "text-base font-semibold text-gray-900"} "Members")
            (cond
              members-loading
              (p {:className "mt-4 text-gray-500"} "Loading members...")
              members-error
              (p {:className "mt-4 text-red-600"} members-error)
              :else
              (table {:className "min-w-full divide-y divide-gray-200"}
                (thead nil
                  (tr nil
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "User ID")
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Role")
                    (th {:className "px-3 py-2 text-left text-xs font-semibold text-gray-500"} "Status")))
                (tbody nil
                  (mapv (fn [m]
                          (tr {:key (str (:user/id m)) :className "border-t border-gray-100"}
                            (td {:className "px-3 py-2 text-sm text-gray-900"} (:user/id m))
                            (td {:className "px-3 py-2 text-sm text-gray-700"} (:membership/role m))
                            (td {:className "px-3 py-2 text-sm text-gray-700"} (:membership/status m))))
                        members))))))))))

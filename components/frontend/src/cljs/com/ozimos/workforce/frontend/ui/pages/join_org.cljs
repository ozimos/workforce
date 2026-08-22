(ns com.ozimos.workforce.frontend.ui.pages.join-org
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom :refer [a button div h2 p]]
   [com.ozimos.workforce.frontend.transit :as transit]))

(defn- load-invitations [this]
  (let [query [{:user/invitations
                [:invitation/id :invitation/org-id
                 :invitation/org-name :invitation/role
                 :invitation/status :invitation/expires-at]}]]
    (-> (transit/fetch-transit "/api/query" query)
        (.then (fn [{:keys [body]}]
                 (let [invitations (get body :user/invitations)]
                   (comp/set-state! this {:invitations (or invitations []) :loading false}))))
        (.catch (fn [_]
                  (comp/set-state! this {:error-msg "Failed to load invitations" :loading false}))))))

(defn- accept-invitation [this invitation-id]
  (let [mut  (list 'org/join {:invitation/id invitation-id})
        query [{mut [:org/id :invitation/errors]}]]
    (comp/set-state! this {:accepting invitation-id})
    (-> (transit/fetch-transit "/api/query" query)
        (.then (fn [{:keys [body]}]
                 (let [join-result (-> body first val)]
                   (if (:invitation/errors join-result)
                     (comp/set-state! this
                       {:error-msg (or (-> join-result :invitation/errors first second first)
                                       "Failed to accept invitation")
                        :accepting nil})
                     (do
                       (comp/set-state! this {:error-msg nil :accepting nil :accepted true})
                       (set! js/window.location.pathname "/"))))))
        (.catch (fn [_]
                  (comp/set-state! this {:error-msg "Network error" :accepting nil}))))))

(defsc JoinOrg [this _props]
  {:query [:invitations :loading :error-msg :accepting :accepted]
   :initial-state {:invitations [] :loading true :error-msg nil :accepting nil :accepted false}}
  (let [{:keys [invitations loading error-msg accepting accepted]} (comp/get-state this)]
    (div {:className "flex min-h-full flex-col justify-center px-6 py-12 lg:px-8"}
      (div {:className "sm:mx-auto sm:w-full sm:max-w-md"}
        (h2 {:className "mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900"}
          "Join an Organization"))
      (div {:className "mt-10 sm:mx-auto sm:w-full sm:max-w-md"}
        (when error-msg
          (div {:className "rounded-md bg-red-50 p-4 mb-4"}
            (p {:className "text-sm text-red-700"} error-msg)))
        (cond
          (and accepted (empty? invitations)) nil
          loading (div {:className "text-center"} (p {:className "text-gray-500"} "Loading invitations..."))
          (empty? invitations)
          (div {:className "text-center"}
            (p {:className "text-gray-500"} "You have no pending invitations.")
            (div {:className "mt-4"}
              (a {:href "/create-org" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
                "Create an organization instead")))
          :else
          (div {:className "space-y-4"}
            (for [inv invitations]
              (div {:key (:invitation/id inv) :className "rounded-lg border border-gray-200 bg-white p-4 shadow-sm"}
                (div {:className "flex items-center justify-between"}
                  (div nil
                    (p {:className "text-sm font-semibold text-gray-900"} (:invitation/org-name inv))
                    (p {:className "text-xs text-gray-500"} (str "Role: " (:invitation/role inv))))
                  (button {:onClick #(accept-invitation this (:invitation/id inv))
                           :disabled (= accepting (:invitation/id inv))
                           :className "rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 disabled:opacity-50"}
                    (if (= accepting (:invitation/id inv)) "Accepting..." "Accept"))))))))
      (div {:className "mt-6 text-center"}
        (a {:href "/" :className "text-sm font-semibold text-indigo-600 hover:text-indigo-500"}
          "Skip for now")))))

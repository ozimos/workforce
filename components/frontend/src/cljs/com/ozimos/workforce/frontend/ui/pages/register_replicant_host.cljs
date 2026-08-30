(ns com.ozimos.workforce.frontend.ui.pages.register-replicant-host
  "Fulcro host for Register Replicant page."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant :as rr]
   [goog.dom :as gdom]))

(defmutation set-email [{:keys [email]}]
  (action [{:keys [state]}]
    (swap! state rr/set-email-state email)))

(defmutation set-password [{:keys [password]}]
  (action [{:keys [state]}]
    (swap! state rr/set-password-state password)))

(defmutation set-confirm-password [{:keys [confirm-password]}]
  (action [{:keys [state]}]
    (swap! state rr/set-confirm-password-state confirm-password)))

(defmutation set-field-errors [{:keys [field-errors]}]
  (action [{:keys [state]}]
    (swap! state rr/set-field-errors-state field-errors)))

(defmutation set-error-msg [{:keys [msg]}]
  (action [{:keys [state]}]
    (swap! state rr/set-error-msg-state msg)))

(defmutation set-success [{:keys [created-username]}]
  (action [{:keys [state]}]
    (swap! state rr/set-success-state created-username)))

(defn- handle-register! [app-inst]
  (let [state-atom (::app/state-atom app-inst)
        {:keys [email password confirm-password]} @state-atom]
    (if (not= password confirm-password)
      (comp/transact! app-inst [(set-field-errors {:field-errors {:confirm-password "Passwords do not match"}})])
      (-> (json/fetch-json "/api/auth/register" "POST" {:email email :password password})
          (.then (fn [{:keys [status body]}]
                   (if (= 201 status)
                     (do
                       (when (exists? js/localStorage)
                         (when-let [at (:access-token body)] (.setItem js/localStorage "access-token" at))
                         (when-let [rt (:refresh-token body)] (.setItem js/localStorage "refresh-token" rt))
                         (when-let [u (get-in body [:user :username])] (.setItem js/localStorage "username" u))
                         (when-let [e (get-in body [:user :email])] (.setItem js/localStorage "email" e)))
                       (comp/transact! app-inst [(set-success {:created-username (get-in body [:user :username])})]))
                     (let [err-map (or (get-in body [:errors :errors]) (:errors body) {})
                           field-errs (into {} (filter (comp some? val)
                                                 {:email    (first (:email err-map))
                                                  :password (first (:password err-map))
                                                  :username (first (:username err-map))}))]
                       (comp/transact! app-inst
                         [(set-field-errors {:field-errors field-errs})
                          (set-error-msg {:msg (when (empty? field-errs) "Registration failed")})])))))))))

(defsc RegisterReplicantHost [this _props]
  {:query [:email :password :confirm-password :error-msg :field-errors :success :created-username]
   :initial-state {:email "" :password "" :confirm-password "" :error-msg nil :field-errors {} :success false :created-username nil}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-register")]
       (when node
         (let [handlers {::rr/set-email (fn [ev]
                                          (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                            (comp/transact! app-inst [(set-email {:email v})])))
                         ::rr/set-password (fn [ev]
                                             (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                               (comp/transact! app-inst [(set-password {:password v})])))
                         ::rr/set-confirm-password (fn [ev]
                                                     (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                                       (comp/transact! app-inst [(set-confirm-password {:confirm-password v})])))
                         ::rr/submit (fn [_] (handle-register! app-inst))
                         ::rr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst rr/RegisterReplicant node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-register-host" :className "min-h-full"}
    (dom/div {:id "replicant-register"} "Loading Register…")))

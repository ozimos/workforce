(ns com.ozimos.workforce.frontend.ui.pages.reset-password-replicant-host
  "Fulcro host for ResetPassword Replicant page."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.reset-password-replicant :as rpr]
   [goog.dom :as gdom]))

(defmutation set-password [{:keys [password]}]
  (action [{:keys [state]}]
    (swap! state rpr/set-password-state password)))

(defmutation set-confirm-password [{:keys [confirm-password]}]
  (action [{:keys [state]}]
    (swap! state rpr/set-confirm-password-state confirm-password)))

(defmutation set-error-msg [{:keys [msg]}]
  (action [{:keys [state]}]
    (swap! state rpr/set-error-msg-state msg)))

(defmutation set-success [_]
  (action [{:keys [state]}]
    (swap! state rpr/set-success-state)))

(defn- get-param [key]
  (some-> js/window.location.search
          (.substring 1)
          (.split "&")
          (->> (map #(.split % "="))
               (filter #(= (first %) key)) first second)))

(defn- handle-reset! [app-inst]
  (let [state-atom (::app/state-atom app-inst)
        {:keys [password confirm-password]} @state-atom]
    (if (not= password confirm-password)
      (comp/transact! app-inst [(set-error-msg {:msg "Passwords do not match"})])
      (let [token (get-param "token")]
        (if-not token
          (comp/transact! app-inst [(set-error-msg {:msg "Missing reset token"})])
          (-> (json/fetch-json "/api/auth/reset-password" "POST" {:token token :password password})
              (.then (fn [{:keys [status body]}]
                       (if (= 200 status)
                         (comp/transact! app-inst [(set-success {})])
                         (comp/transact! app-inst [(set-error-msg {:msg (or (-> body :errors :token first)
                                                                           "Invalid or expired token")})]))))))))))

(defsc ResetPasswordReplicantHost [this _props]
  {:query [:password :confirm-password :error-msg :success]
   :initial-state {:password "" :confirm-password "" :error-msg nil :success false}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-reset-password")]
       (when node
         (let [handlers {::rpr/set-password (fn [ev]
                                              (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                                (comp/transact! app-inst [(set-password {:password v})])))
                         ::rpr/set-confirm-password (fn [ev]
                                                      (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                                        (comp/transact! app-inst [(set-confirm-password {:confirm-password v})])))
                         ::rpr/submit (fn [_] (handle-reset! app-inst))
                         ::rpr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst rpr/ResetPasswordReplicant node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-reset-password-host" :className "min-h-full"}
    (dom/div {:id "replicant-reset-password"} "Loading…")))

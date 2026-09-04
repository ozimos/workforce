(ns com.ozimos.workforce.frontend.ui.pages.forgot-password.host
  "Fulcro host for ForgotPassword Replicant page."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password :as fpr]
   [goog.dom :as gdom]))

(defmutation set-email [{:keys [email]}]
  (action [{:keys [state]}]
    (swap! state fpr/set-email-state email)))

(defmutation set-sent [{:keys [sent]}]
  (action [{:keys [state]}]
    (swap! state fpr/set-sent-state sent)))

(defn- send-link! [app-inst]
  (let [state-atom (::app/state-atom app-inst)
        email      (:email @state-atom)]
    (-> (json/fetch-json "/api/auth/forgot-password" "POST" {:email email})
        (.then (fn [_] (comp/transact! app-inst [(set-sent {:sent true})]))))))

(defsc ForgotPasswordHost [_this _props]
  {:query [:email :sent]
   :initial-state {:email "" :sent false}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-forgot-password")]
       (when node
         (let [handlers {::fpr/set-email (fn [ev]
                                           (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                             (comp/transact! app-inst [(set-email {:email v})])))
                         ::fpr/submit (fn [_] (send-link! app-inst))
                         ::fpr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst fpr/ForgotPassword node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-forgot-password-host" :className "min-h-full"}
    (dom/div {:id "replicant-forgot-password"} "Loading…")))

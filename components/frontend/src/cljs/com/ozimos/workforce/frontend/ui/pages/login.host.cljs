(ns com.ozimos.workforce.frontend.ui.pages.login.host
  "Fulcro host for Login Replicant page."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.login :as lr]
   [goog.dom :as gdom]))

(defmutation set-identifier [{:keys [identifier]}]
  (action [{:keys [state]}]
    (swap! state lr/set-identifier-state identifier)))

(defmutation set-password [{:keys [password]}]
  (action [{:keys [state]}]
    (swap! state lr/set-password-state password)))

(defmutation set-mfa-code [{:keys [code]}]
  (action [{:keys [state]}]
    (swap! state lr/set-mfa-code-state code)))

(defmutation set-error-msg [{:keys [msg]}]
  (action [{:keys [state]}]
    (swap! state lr/set-error-msg-state msg)))

(defmutation set-mfa-required [{:keys [mfa-token]}]
  (action [{:keys [state]}]
    (swap! state lr/set-mfa-required-state mfa-token)))

(defn- handle-mfa-login! [app-inst]
  (let [state-atom (::app/state-atom app-inst)
        {:keys [mfa-token mfa-code]} @state-atom]
    (comp/transact! app-inst [(set-error-msg {:msg nil})])
    (-> (json/fetch-json "/api/auth/mfa/login" "POST" {:mfa-token mfa-token :code mfa-code})
        (.then (fn [{:keys [status body]}]
                 (if (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (.setItem js/localStorage "mfa-enabled" "true")
                     (set! js/window.location.pathname "/"))
                   (comp/transact! app-inst [(set-error-msg {:msg (or (-> body :errors :code first) "Invalid 2FA code")})])))))))

(defn- handle-login! [app-inst]
  (let [state-atom (::app/state-atom app-inst)
        {:keys [identifier password]} @state-atom]
    (comp/transact! app-inst [(set-error-msg {:msg nil})])
    (-> (json/fetch-json "/api/auth/login" "POST" {:identifier identifier :password password})
        (.then (fn [{:keys [status body]}]
                 (cond
                   (and (= 200 status) (:mfa-required body))
                   (comp/transact! app-inst [(set-mfa-required {:mfa-token (:mfa-token body)})])

                   (= 200 status)
                   (do
                     (.setItem js/localStorage "access-token" (:access-token body))
                     (.setItem js/localStorage "refresh-token" (:refresh-token body))
                     (when-let [u (:user body)]
                       (when (:email u) (.setItem js/localStorage "email" (:email u)))
                       (when (:username u) (.setItem js/localStorage "username" (:username u))))
                     (set! js/window.location.pathname "/"))

                   :else
                   (comp/transact! app-inst
                     [(set-error-msg {:msg (or (-> body :errors :credentials first)
                                               "Invalid email/username or password")})])))))))

(defsc LoginHost [_this _props]
  {:query [:identifier :password :error-msg :mfa-required :mfa-token :mfa-code]
   :initial-state {:identifier "" :password "" :error-msg nil :mfa-required false :mfa-token nil :mfa-code ""}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-login")]
       (when node
         (let [handlers {::lr/set-identifier (fn [ev]
                                               (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                                 (comp/transact! app-inst [(set-identifier {:identifier v})])))
                         ::lr/set-password (fn [ev]
                                             (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                               (comp/transact! app-inst [(set-password {:password v})])))
                         ::lr/set-mfa-code (fn [ev]
                                             (when-let [v (some-> (:replicant/js-event ev) .-target .-value)]
                                               (comp/transact! app-inst [(set-mfa-code {:code v})])))
                         ::lr/submit-login (fn [_] (handle-login! app-inst))
                         ::lr/submit-mfa (fn [_] (handle-mfa-login! app-inst))
                         ::lr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst lr/Login node handlers)))))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-login-host" :className "min-h-full"}
    (dom/div {:id "replicant-login"} "Loading Login…")))

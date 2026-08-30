(ns com.ozimos.workforce.frontend.ui.pages.verify-replicant-host
  "Fulcro host for Verify Replicant page."
  (:require
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.mutations :refer [defmutation]]
   [com.ozimos.workforce.frontend.json :as json]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.verify-replicant :as vr]
   [goog.dom :as gdom]))

(defmutation set-status [{:keys [status message]}]
  (action [{:keys [state]}]
    (swap! state vr/set-status-state status message)))

(defn- get-param [key]
  (some-> js/window.location.search
          (.substring 1)
          (.split "&")
          (->> (map #(.split % "="))
               (filter #(= (first %) key)) first second)))

(defn- verify-account! [app-inst]
  (let [token (get-param "token")
        user-id (get-param "user-id")]
    (if (and token user-id)
      (-> (json/fetch-json "/api/auth/verify" "POST" {:token token :user-id user-id})
          (.then (fn [{:keys [status]}]
                   (if (= 200 status)
                     (comp/transact! app-inst [(set-status {:status :success :message "Account verified!"})])
                     (comp/transact! app-inst [(set-status {:status :error :message "Verification failed"})])))))
      (comp/transact! app-inst [(set-status {:status :error :message "Missing verification token"})]))))

(defsc VerifyReplicantHost [this _props]
  {:query [:status :message]
   :initial-state {:status :loading :message nil}
   :componentDidMount
   (fn [this]
     (let [app-inst (comp/any->app this)
           node (gdom/getElement "replicant-verify")]
       (when node
         (let [handlers {::vr/navigate (fn [_ path] (set! js/window.location.href path))}]
           (bridge/install-replicant-root! app-inst vr/VerifyReplicant node handlers)))
       (verify-account! app-inst)))
   :componentDidUpdate (fn [_ _ _] nil)}
  (dom/div {:id "replicant-verify-host" :className "min-h-full"}
    (dom/div {:id "replicant-verify"} "Verifying your account…")))

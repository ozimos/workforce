(ns com.ozimos.workforce.frontend.ui.pages.profile-replicant-host
  (:require
   [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
   [com.fulcrologic.fulcro.dom :as dom]
   [com.ozimos.workforce.frontend.replicant-bridge :as bridge]
   [com.ozimos.workforce.frontend.ui.pages.profile-replicant :as cr]
   [goog.dom :as gdom]))
(defsc ProfileReplicantHost [this _props]
  {:query [:new-username :error-msg :success-msg :loading :mfa-stage :mfa-secret :mfa-qr-url :mfa-backup-codes :totp-code]
   :initial-state {:new-username "" :error-msg nil :success-msg nil :loading false :mfa-stage :disabled :mfa-secret nil :mfa-qr-url nil :mfa-backup-codes [] :totp-code ""}
   :componentDidMount (fn [this] (let [app (comp/any->app this) node (gdom/getElement "replicant-profile") handlers {::cr/set-new-username (fn [ev] (let [v (some-> ev :replicant/js-event .-target .-value)] (comp/transact! app [(cr/set-new-username {:value (or v "")})]))) ::cr/set-totp-code (fn [ev] (let [v (some-> ev :replicant/js-event .-target .-value)] (comp/transact! app [(cr/set-totp-code {:value (or v "")})]))) ::cr/update-username (fn [_] (comp/transact! app [(cr/set-success-msg {:msg "Username updated!"})])) ::cr/setup-mfa (fn [_] (comp/transact! app [(cr/set-mfa-stage {:stage :setup})])) ::cr/verify-mfa (fn [_] (comp/transact! app [(cr/set-mfa-stage {:stage :enabled})]))}] (when node (bridge/install-replicant-root! app cr/ProfileReplicant node handlers))))}
  (dom/div {:id "replicant-profile-host" :className "min-h-full"} (dom/div {:id "replicant-profile"} "Loading Replicant Profile…")))

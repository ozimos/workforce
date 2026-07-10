(ns com.ozimos.auth.user.core
  (:require [com.ozimos.auth.rama.interface :as rama]
            [com.ozimos.auth.password.interface :as password]
            [com.ozimos.auth.schema.interface :as schema]
            [com.ozimos.auth.schema.interface.registration :as registration]
            [malli.core :as m]
            [com.rpl.rama :as rama]
            [com.rpl.rama.path :refer [keypath]])
  (:import [java.util UUID]))

(defn- ensure-deps [deps]
  (or deps {}))

(defn register! [{:keys [rama cluster-password] :as deps} input]
  (when-not (m/validate registration/register-request input)
    (throw (ex-info "Invalid registration input" {:input input})))
  (let [{:keys [username email password roles]} input
        cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        reg-depot (rama/depot cmgr mod-name "*registration-depot")
        pwd-hash (password/encode (:password-encoder deps) password)
        uuid (str (UUID/randomUUID))
        roles-set (or (set roles) #{"ROLE_USER"})]
    (let [result (rama/foreign-append! reg-depot
                  (->Registration uuid username pwd-hash email roles-set))]
      (if (some? result)
        (let [user-id result
              user {:id user-id
                    :username username
                    :email email
                    :verified false
                    :roles roles-set}]
          [true user])
        [false {:errors {:username ["Username or email already taken."]}}]))))

(defn find-by-username [{:keys [rama] :as deps} username]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        username->id (rama/pstate cmgr mod-name "$$username->id")
        profiles (rama/pstate cmgr mod-name "$$profiles")
        user-id (rama/foreign-select-one (keypath username) username->id)]
    (when user-id
      (let [profile (rama/foreign-select-one (keypath user-id) profiles
                    {:pkey username})]
        (when profile
          (assoc profile :id user-id))))))

(defn find-by-id [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        profiles (rama/pstate cmgr mod-name "$$profiles")]
    (let [profile (rama/foreign-select-one (keypath user-id) profiles {:pkey user-id})]
      (when profile
        (assoc profile :id user-id)))))

(defn verify! [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        verify-depot (rama/depot cmgr mod-name "*verification-depot")]
    (rama/foreign-append! verify-depot (->Verification user-id))
    true))

(defn change-password! [{:keys [rama password-encoder] :as deps} user-id new-pwd-hash]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        pwd-change-depot (rama/depot cmgr mod-name "*password-change-depot")]
    (rama/foreign-append! pwd-change-depot (->PasswordChange user-id new-pwd-hash))
    true))
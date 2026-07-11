(ns com.ozimos.auth.user.core
  (:require
   [com.ozimos.auth.rama.interface :as rama]
   [com.ozimos.auth.rama.module :refer [->PasswordChange ->Registration ->Verification]]
   [com.ozimos.auth.schema.interface :as schema]
   [com.ozimos.auth.schema.interface.registration :as registration]
   [com.rpl.rama :as ramaapi]
   [com.rpl.rama.path :refer [keypath]]
   [malli.core :as m])
  (:import
   (java.util UUID)
   (org.springframework.security.crypto.bcrypt BCryptPasswordEncoder)
   (org.springframework.security.crypto.password PasswordEncoder)))

(defn- make-encoder
  (^PasswordEncoder [] (BCryptPasswordEncoder. 12))
  (^PasswordEncoder [strength] (BCryptPasswordEncoder. ^int (or strength 12))))

(defn encode-password [deps plain]
  (let [encoder (or (:password-encoder deps) (make-encoder))]
    (.encode ^PasswordEncoder encoder plain)))

(defn matches-password? [deps plain encoded]
  (let [encoder (or (:password-encoder deps) (make-encoder))]
    (.matches ^PasswordEncoder encoder plain encoded)))

(defn register! [{:keys [rama] :as deps} input]
  (when-not (m/validate registration/register-request input)
    (throw (ex-info "Invalid registration input" {:input input})))
  (let [{:keys [username email password roles]} input
        cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        reg-depot (rama/depot cmgr mod-name "*registration-depot")
        pwd-hash (encode-password deps password)
        uuid (str (UUID/randomUUID))
        roles-set (or (set roles) #{"ROLE_USER"})
        result (ramaapi/foreign-append! reg-depot
                 (->Registration uuid username pwd-hash email roles-set))]
    (if (some? result)
        (let [user-id result
              user {:id user-id
                    :username username
                    :email email
                    :verified false
                    :roles roles-set}]
          [true user])
        [false {:errors {:username ["Username or email already taken."]}}])))

(defn find-by-username [{:keys [rama] :as deps} username]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        username->id (rama/pstate cmgr mod-name "$$username->id")
        profiles (rama/pstate cmgr mod-name "$$profiles")
        user-id (ramaapi/foreign-select-one (keypath username) username->id)]
    (when user-id
      (let [profile (ramaapi/foreign-select-one (keypath user-id) profiles
                      {:pkey username})]
        (when profile
          (assoc profile :id user-id))))))

(defn find-by-id [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        profiles (rama/pstate cmgr mod-name "$$profiles")
        profile (ramaapi/foreign-select-one (keypath user-id) profiles {:pkey user-id})]
    (when profile
      (assoc profile :id user-id))))

(defn verify! [{:keys [rama] :as deps} user-id]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        verify-depot (rama/depot cmgr mod-name "*verification-depot")]
    (ramaapi/foreign-append! verify-depot (->Verification user-id))
    true))

(defn change-password! [{:keys [rama] :as deps} user-id new-pwd-hash]
  (let [cmgr (:cluster-manager rama)
        mod-name (rama/module-name)
        pwd-change-depot (rama/depot cmgr mod-name "*password-change-depot")]
    (ramaapi/foreign-append! pwd-change-depot (->PasswordChange user-id new-pwd-hash))
    true))

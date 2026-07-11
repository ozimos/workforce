(ns com.ozimos.auth.user.core
  (:require
   [com.ozimos.auth.schema.interface :as schema]
   [com.ozimos.auth.schema.interface.registration :as registration]
   [malli.core :as m])
  (:import
   (java.util UUID)
   (java.util.concurrent.atomic AtomicLong)
   (org.springframework.security.crypto.bcrypt BCryptPasswordEncoder)
   (org.springframework.security.crypto.password PasswordEncoder)))

(def ^:private id-counter (AtomicLong. 1))
(def ^:private store (atom {}))

(defn- next-id [] (.getAndIncrement id-counter))

(defn- make-encoder
  (^PasswordEncoder [] (BCryptPasswordEncoder. 12))
  (^PasswordEncoder [strength] (BCryptPasswordEncoder. ^int (or strength 12))))

(defn encode-password [deps plain]
  (let [encoder (or (:password-encoder deps) (make-encoder))]
    (.encode ^PasswordEncoder encoder plain)))

(defn matches-password? [deps plain encoded]
  (let [encoder (or (:password-encoder deps) (make-encoder))]
    (.matches ^PasswordEncoder encoder plain encoded)))

(defn register! [deps input]
  (when-not (m/validate registration/register-request input)
    (throw (ex-info "Invalid registration input" {:input input})))
  (let [{:keys [username email password roles]} input
        existing (vals @store)
        username-taken (some #(= (:username %) username) existing)
        email-taken (some #(= (:email %) email) existing)]
    (if (or username-taken email-taken)
      [false {:errors {:username ["Username or email already taken."]}}]
      (let [user-id (next-id)
            pwd-hash (encode-password deps password)
            roles-set (or (set roles) #{"ROLE_USER"})
            user {:id user-id
                  :username username
                  :email email
                  :pwd-hash pwd-hash
                  :verified false
                  :roles roles-set}]
        (swap! store assoc user-id user)
        [true (dissoc user :pwd-hash)]))))

(defn find-by-username [_deps username]
  (let [user (->> @store vals (filter #(= (:username %) username)) first)]
    user))

(defn find-by-id [_deps user-id]
  (let [user (get @store user-id)]
    user))

(defn verify! [_deps user-id]
  (swap! store assoc-in [user-id :verified] true)
  true)

(defn change-password! [deps user-id new-pwd-hash]
  (swap! store assoc-in [user-id :pwd-hash] new-pwd-hash)
  true)

(defn reset-store! []
  (reset! store {})
  (.set id-counter 1))

(defmethod ig/init-key :user/store [_ _]
  {})

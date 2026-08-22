(ns com.ozimos.workforce.pathom.core
  (:require
   [com.ozimos.workforce.user.interface :as user]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [integrant.core :as ig]))

(defn- authenticated-user-id
  "Extract the authenticated user-id from the Pathom env."
  [env]
  (get-in env [:auth :user-id]))

(defn- require-auth
  "Returns user-id if authenticated, throws otherwise."
  [env]
  (or (authenticated-user-id env)
      (throw (ex-info "Not authenticated" {:type :unauthenticated}))))

(defn- get-user-store [deps]
  (or (:user-store deps) deps))

(pco/defresolver current-user-resolver
  "Resolve the current authenticated user's basic info."
  [env params]
  {::pco/output [:current-user/id :current-user/username :current-user/email :current-user/verified]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))]
    (let [user-record (user/find-by-id user-store user-id)]
      {:current-user/id user-id
       :current-user/username (:username user-record)
       :current-user/email (:email user-record)
       :current-user/verified (boolean (:verified user-record))})))

(pco/defmutation update-username-mutation
  "Update the authenticated user's username."
  [env {:user/keys [new-username]}]
  {::pco/op-name 'user/update-username
   ::pco/params [:user/new-username]
   ::pco/output [:current-user/id :current-user/username :user/errors]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))
        [ok res] (user/update-username! user-store user-id new-username)]
    (if ok
      {:current-user/id user-id
       :current-user/username res}
      {:user/errors res})))

(def registry
  [current-user-resolver
   update-username-mutation])

(defn build-env
  "Build a Pathom environment with all resolvers and mutations registered.
   `deps` is the integrant deps map (contains :user-store, etc.).
   `auth` is an optional map with :user-id for authenticated requests.
   `extra-resolvers` is an optional collection of domain resolvers/mutations."
  ([deps]
   (build-env deps nil nil))
  ([deps auth]
   (build-env deps auth nil))
  ([deps auth extra-resolvers]
   (-> (pci/register (into registry (or extra-resolvers [])))
       (assoc :deps deps)
       (cond-> auth (assoc :auth auth)))))

(defmethod ig/init-key :pathom/env [_ {:keys [deps extra-resolvers]}]
  (build-env deps nil extra-resolvers))

(defn process
  "Process an EQL query against the Pathom environment.
   `env` is the built Pathom environment.
   `eql` is the EQL query."
  [env eql]
  (let [res (p.eql/process env eql)]
    (if (map? res)
      (into {} (map (fn [[k v]]
                      [(if (symbol? k) (keyword (str k)) k) v])
                    res))
      res)))

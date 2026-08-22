(ns com.ozimos.auth.pathom.core
  (:require
   [com.ozimos.auth.user.interface :as user]
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

(defn- require-org-admin
  "Returns user-id if the current user is an ADMIN of the given org.
   Throws if not authenticated or not an admin."
  [env deps org-id]
  (let [user-id (require-auth env)
        user-store (get-user-store deps)
        membership (user/get-membership user-store user-id org-id)]
    (when (or (nil? membership)
              (not= (:role membership) "ADMIN"))
      (throw (ex-info "Not authorized: admin role required" {:type :forbidden})))
    user-id))

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

(pco/defresolver user-orgs-resolver
  "Resolve all organizations for the current user."
  [env params]
  {::pco/output [{:user/orgs [:org/id :org/name :org/role :org/status]}]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))
        orgs (user/find-orgs-for-user user-store user-id)]
    {:user/orgs
     (mapv (fn [org]
             {:org/id (:id org)
              :org/name (:name org)
              :org/role (:role org)
              :org/status (:status org)})
           orgs)}))

(pco/defresolver active-org-resolver
  "Resolve the current user's active org."
  [env params]
  {::pco/output [{:user/active-org [:org/id :org/name :org/role]}]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))
        active-org-id (user/get-active-org user-store user-id)]
    (if active-org-id
      (let [org (user/find-org-by-id user-store active-org-id)
            membership (user/get-membership user-store user-id active-org-id)]
        {:user/active-org
         {:org/id active-org-id
          :org/name (:name org)
          :org/role (:role membership)}})
      {:user/active-org nil})))

(pco/defresolver org-members-resolver
  "Resolve members of an org by org-id. Requires admin."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:org/members [:user/id :membership/role :membership/status :membership/joined-at]}]}
  (require-org-admin env deps (:org/id params))
  (let [user-store (get-user-store deps)
        members (user/list-members user-store (:org/id params))]
    {:org/members
     (mapv (fn [m]
             {:user/id (:user-id m)
              :membership/role (:role m)
              :membership/status (:status m)
              :membership/joined-at (:joined-at m)})
           members)}))

(pco/defresolver user-invitations-resolver
  "Resolve pending invitations for the current user by email."
  [env params]
  {::pco/output [{:user/invitations [:invitation/id :invitation/org-id
                                     :invitation/org-name :invitation/role
                                     :invitation/status :invitation/expires-at]}]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))
        user-record (user/find-by-id user-store user-id)
        email (:email user-record)
        invitations (user/list-invitations-for-user user-store email)]
    {:user/invitations invitations}))

(pco/defresolver org-by-id-resolver
  "Resolve a single org by id. Requires membership."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [:org/id :org/name :org/owner-id :org/created-at]}
  (let [user-id (require-auth env)
        user-store (get-user-store deps)
        org-id (:org/id params)]
    (when (nil? (user/get-membership user-store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [org (user/find-org-by-id user-store org-id)]
      {:org/id org-id
       :org/name (:name org)
       :org/owner-id (:owner-user-id org)
       :org/created-at (:created-at org)})))

(pco/defmutation create-org-mutation
  "Create a new organization. The current user becomes ADMIN."
  [env {:org/keys [name]}]
  {::pco/op-name 'org/create
   ::pco/params [:org/name]
   ::pco/output [:org/id :org/name :org/role :org/errors]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))
        [ok result] (user/create-org! user-store {:name name :owner-user-id user-id})]
    (if ok
      {:org/id (:id result)
       :org/name (:name result)
       :org/role "ADMIN"}
      {:org/errors (:errors result)})))

(pco/defmutation invite-to-org-mutation
  "Invite a user to join an org by email. Admin only."
  [env {:org/keys [id] :invitation/keys [email role]}]
  {::pco/op-name 'org/invite
   ::pco/params [:org/id :invitation/email :invitation/role]
   ::pco/output [:invitation/id :invitation/errors]}
  (require-org-admin env (:deps env) id)
  (let [user-store (get-user-store (:deps env))
        invited-by (require-auth env)
        [ok result] (user/invite-to-org! user-store {:org-id id :email email :role role :invited-by invited-by})]
    (if ok
      {:invitation/id (:invitation-id result)}
      {:invitation/errors (:errors result)})))

(pco/defmutation join-org-mutation
  "Accept an invitation and join an org."
  [env {:invitation/keys [id]}]
  {::pco/op-name 'org/join
   ::pco/params [:invitation/id]
   ::pco/output [:org/id :invitation/errors]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))
        [ok result] (user/join-org! user-store {:user-id user-id :invitation-id id})]
    (if ok
      {:org/id (:org-id result)}
      {:invitation/errors (:errors result)})))

(pco/defmutation switch-org-mutation
  "Switch the current user's active org."
  [env {:org/keys [id]}]
  {::pco/op-name 'org/switch
   ::pco/params [:org/id]
   ::pco/output [:org/id]}
  (let [user-id (require-auth env)
        user-store (get-user-store (:deps env))]
    (when (nil? (user/get-membership user-store user-id id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (user/switch-org! user-store user-id id)
    {:org/id id}))

(pco/defmutation update-member-role-mutation
  "Update a member's role in an org. Admin only."
  [env params]
  {::pco/op-name 'org/update-member
   ::pco/params [:org/id :user/id :membership/role]
   ::pco/output [:membership/role]}
  (let [org-id (:org/id params)
        target-user-id (:user/id params)
        role (:membership/role params)]
    (require-org-admin env (:deps env) org-id)
    (let [user-store (get-user-store (:deps env))]
      (user/update-member-role! user-store org-id target-user-id role)
      {:membership/role role})))

(pco/defmutation remove-member-mutation
  "Remove a member from an org. Admin only."
  [env params]
  {::pco/op-name 'org/remove-member
   ::pco/params [:org/id :user/id]
   ::pco/output [:success]}
  (let [org-id (:org/id params)
        target-user-id (:user/id params)]
    (require-org-admin env (:deps env) org-id)
    (let [user-store (get-user-store (:deps env))]
      (user/remove-member! user-store org-id target-user-id)
      {:success true})))

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
   user-orgs-resolver
   active-org-resolver
   org-members-resolver
   user-invitations-resolver
   org-by-id-resolver
   update-username-mutation
   create-org-mutation
   invite-to-org-mutation
   join-org-mutation
   switch-org-mutation
   update-member-role-mutation
   remove-member-mutation])

(defn build-env
  "Build a Pathom environment with all resolvers and mutations registered.
   `deps` is the integrant deps map (contains :user-store, etc.).
   `auth` is an optional map with :user-id for authenticated requests."
  ([deps]
   (build-env deps nil))
  ([deps auth]
   (-> (pci/register registry)
       (assoc :deps deps)
       (cond-> auth (assoc :auth auth)))))

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

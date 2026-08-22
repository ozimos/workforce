(ns com.ozimos.workforce.org.resolvers
  (:require
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.wsscode.pathom3.connect.operation :as pco]
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

(defn- get-store [deps]
  (or (:user-store deps) deps))

(defn- require-org-admin
  "Returns user-id if the current user is an ADMIN of the given org.
   Throws if not authenticated or not an admin."
  [env deps org-id]
  (let [user-id (require-auth env)
        store (get-store deps)
        membership (org/get-membership store user-id org-id)]
    (when (or (nil? membership)
              (not= (:role membership) "ADMIN"))
      (throw (ex-info "Not authorized: admin role required" {:type :forbidden})))
    user-id))

(pco/defresolver user-orgs-resolver
  "Resolve all organizations for the current user."
  [env params]
  {::pco/output [{:user/orgs [:org/id :org/name :org/role :org/status]}]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        orgs (org/find-orgs-for-user store user-id)]
    {:user/orgs
     (mapv (fn [o]
             {:org/id (:id o)
              :org/name (:name o)
              :org/role (:role o)
              :org/status (:status o)})
           orgs)}))

(pco/defresolver active-org-resolver
  "Resolve the current user's active org."
  [env params]
  {::pco/output [{:user/active-org [:org/id :org/name :org/role]}]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        active-org-id (org/get-active-org store user-id)]
    (if active-org-id
      (let [o (org/find-org-by-id store active-org-id)
            membership (org/get-membership store user-id active-org-id)]
        {:user/active-org
         {:org/id active-org-id
          :org/name (:name o)
          :org/role (:role membership)}})
      {:user/active-org nil})))

(pco/defresolver org-members-resolver
  "Resolve members of an org by org-id. Requires admin."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [{:org/members [:user/id :membership/role :membership/status :membership/joined-at]}]}
  (require-org-admin env deps (:org/id params))
  (let [store (get-store deps)
        members (org/list-members store (:org/id params))]
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
        store (get-store (:deps env))
        user-record (user/find-by-id store user-id)
        email (:email user-record)
        invitations (org/list-invitations-for-user store email)]
    {:user/invitations invitations}))

(pco/defresolver org-by-id-resolver
  "Resolve a single org by id. Requires membership."
  [{:keys [deps] :as env} params]
  {::pco/input [:org/id]
   ::pco/output [:org/id :org/name :org/owner-id :org/created-at]}
  (let [user-id (require-auth env)
        store (get-store deps)
        org-id (:org/id params)]
    (when (nil? (org/get-membership store user-id org-id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (let [o (org/find-org-by-id store org-id)]
      {:org/id org-id
       :org/name (:name o)
       :org/owner-id (:owner-user-id o)
       :org/created-at (:created-at o)})))

(pco/defmutation create-org-mutation
  "Create a new organization. The current user becomes ADMIN."
  [env {:org/keys [name]}]
  {::pco/op-name 'org/create
   ::pco/params [:org/name]
   ::pco/output [:org/id :org/name :org/role :org/errors]}
  (let [user-id (require-auth env)
        store (get-store (:deps env))
        [ok result] (org/create-org! store {:name name :owner-user-id user-id})]
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
  (let [store (get-store (:deps env))
        invited-by (require-auth env)
        [ok result] (org/invite-to-org! store {:org-id id :email email :role role :invited-by invited-by})]
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
        store (get-store (:deps env))
        [ok result] (org/join-org! store {:user-id user-id :invitation-id id})]
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
        store (get-store (:deps env))]
    (when (nil? (org/get-membership store user-id id))
      (throw (ex-info "Not a member of this org" {:type :forbidden})))
    (org/switch-org! store user-id id)
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
    (let [store (get-store (:deps env))]
      (org/update-member-role! store org-id target-user-id role)
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
    (let [store (get-store (:deps env))]
      (org/remove-member! store org-id target-user-id)
      {:success true})))

(def resolvers
  [user-orgs-resolver
   active-org-resolver
   org-members-resolver
   user-invitations-resolver
   org-by-id-resolver
   create-org-mutation
   invite-to-org-mutation
   join-org-mutation
   switch-org-mutation
   update-member-role-mutation
   remove-member-mutation])

(defmethod ig/init-key :workforce/org-resolvers [_ _]
  resolvers)

(ns com.ozimos.workforce.org.records)

(defrecord OrgCreate [uuid name owner-user-id created-at])
(defrecord OrgInvite [invitation-id org-id email role invited-by created-at expires-at])
(defrecord OrgJoin [user-id invitation-id joined-at])
(defrecord OrgSwitch [user-id org-id])
(defrecord OrgMemberUpdate [org-id target-user-id new-role])
(defrecord OrgMemberRemove [org-id target-user-id])
(defrecord InvitationAccept [invitation-id user-id joined-at])

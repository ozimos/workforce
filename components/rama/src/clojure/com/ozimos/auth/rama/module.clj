(ns com.ozimos.auth.rama.module
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.ops :as ops])
  (:import [com.rpl.rama.helpers ModuleUniqueIdPState]))

(defrecord Registration [uuid username pwd-hash email roles])
(defrecord Verification [user-id])
(defrecord PasswordChange [user-id new-pwd-hash])
(defrecord SessionStart [user-id session-id jti expires-at])
(defrecord SessionEnd [session-id])
(defrecord SessionEndAll [user-id])
(defrecord Revocation [jti expires-at])
(defrecord RevokeAllForUser [user-id])

(defmodule AuthModule [setup topologies]
  ;; Depots
  (declare-depot setup *registration-depot (hash-by :username))
  (declare-depot setup *verification-depot (hash-by :user-id))
  (declare-depot setup *password-change-depot (hash-by :user-id))
  (declare-depot setup *session-depot (hash-by :user-id))
  (declare-depot setup *session-end-depot (hash-by :session-id))
  (declare-depot setup *revoke-all-depot (hash-by :user-id))
  (declare-depot setup *revocation-depot (hash-by :jti))

  (let [s (stream-topology topologies "auth")
        id-gen (ModuleUniqueIdPState. "$$id")]
    ;; PStates
    (declare-pstate s $$username->id
      {String Long})

    (declare-pstate s $$email->id
      {String Long})

    (declare-pstate s $$profiles
      {Long (fixed-keys-schema {:username String
                                :pwd-hash String
                                :email String
                                :verified Boolean
                                :roles (set-schema String {:subindex? true})})})

    (declare-pstate s $$sessions
      {String (fixed-keys-schema {:user-id Long :jti String :expires-at Long})})

    (declare-pstate s $$user-sessions
      {Long (set-schema String {:subindex? true})})

    (declare-pstate s $$revoked-tokens
      {String Long})

    (declare-pstate s $$user-active-jtis
      {Long (set-schema String {:subindex? true})})

    (.declarePState id-gen s)

    (<<sources s
      ;; Registration
      (source> *registration-depot :> {:keys [*uuid *username *pwd-hash *email *roles]})
      (local-select> (keypath *username) $$username->id :> *existing-id)
      (<<if (nil? *existing-id)
        (java-macro! (.genId id-gen "*user-id"))
        (local-transform> [(keypath *username) (termval *user-id)] $$username->id)
        (|hash *email)
        (local-transform> [(keypath *email) (termval *user-id)] $$email->id)
        (|hash *user-id)
        (local-transform> [(keypath *user-id)
                           (multi-path [:username (termval *username)]
                                       [:pwd-hash (termval *pwd-hash)]
                                       [:email (termval *email)]
                                       [:verified (termval false)]
                                       [:roles (termval (or *roles #{"ROLE_USER"}))])]
          $$profiles)
        (ack-return> *user-id))
      (<<else
        (ack-return> nil))

      ;; Verification
      (source> *verification-depot :> {:keys [*user-id]})
      (|hash *user-id)
      (local-transform> [(keypath *user-id :verified) (termval true)] $$profiles)

      ;; Password change
      (source> *password-change-depot :> {:keys [*user-id *new-pwd-hash]})
      (|hash *user-id)
      (local-transform> [(keypath *user-id :pwd-hash) (termval *new-pwd-hash)] $$profiles)

      ;; Session start
      (source> *session-depot :> {:keys [*user-id *session-id *jti *expires-at]})
      (|hash *session-id)
      (local-transform> [(keypath *session-id)
                         (multi-path [:user-id (termval *user-id)]
                                     [:jti (termval *jti)]
                                     [:expires-at (termval *expires-at)])]
        $$sessions)
      (|hash *user-id)
      (local-transform> [(keypath *user-id) NONE-ELEM (termval *session-id)] $$user-sessions)
      (local-transform> [(keypath *user-id) NONE-ELEM (termval *jti)] $$user-active-jtis)

      ;; Single session end
      (source> *session-end-depot :> {:keys [*session-id]})
      (|hash *session-id)
      (local-remove> (keypath *session-id) $$sessions)

      ;; Revoke all sessions for a user
      (source> *revoke-all-depot :> {:keys [*user-id]})
      (|hash *user-id)
      (local-select> (keypath *user-id) $$user-sessions :> *session-ids)
      (ops/explode *session-ids :> *sid)
      (|hash *sid)
      (local-remove> (keypath *sid) $$sessions)
      (|hash *user-id)
      (local-remove> (keypath *user-id) $$user-sessions)
      (local-remove> (keypath *user-id) $$user-active-jtis)

      ;; Token revocation
      (source> *revocation-depot :> {:keys [*jti *expires-at]})
      (|hash *jti)
      (local-transform> [(keypath *jti) (termval *expires-at)] $$revoked-tokens)

      ;; Revoke all tokens for a user (reads from $$user-active-jtis)
      (source> *revoke-all-depot :> {:keys [*user-id]})
      (|hash *user-id)
      (local-select> (keypath *user-id) $$user-active-jtis :> *jtis)
      (ops/explode *jtis :> *jti)
      (|hash *jti)
      (local-transform> [(keypath *jti) (termval *expires-at)] $$revoked-tokens)
      )))
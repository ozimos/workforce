(ns com.ozimos.auth.rama.interface
  (:require
   [com.ozimos.auth.rama.core :as core]
   [com.ozimos.auth.rama.module]))

(defn cluster-manager
  "Returns the Rama cluster manager (or IPC for dev) from the integrant system."
  [system]
  (core/cluster-manager system))

(defn pstate
  "Get a foreign-pstate client by name from the cluster manager."
  [cluster-manager module-name pstate-name]
  (core/pstate cluster-manager module-name pstate-name))

(defn depot
  "Get a foreign-depot client by name from the cluster manager."
  [cluster-manager module-name depot-name]
  (core/depot cluster-manager module-name depot-name))

(defn module-name
  "Returns the module name string for AuthModule."
  []
  (core/module-name))

(defn cleanup-expired-sessions
  [rama-map]
  (core/cleanup-expired-sessions rama-map))

(defn cleanup-expired-revocations
  [rama-map]
  (core/cleanup-expired-revocations rama-map))

(def ->Registration com.ozimos.auth.rama.module/->Registration)
(def ->Verification com.ozimos.auth.rama.module/->Verification)
(def ->PasswordChange com.ozimos.auth.rama.module/->PasswordChange)
(def ->UsernameChange com.ozimos.auth.rama.module/->UsernameChange)
(def ->SessionStart com.ozimos.auth.rama.module/->SessionStart)
(def ->SessionEnd com.ozimos.auth.rama.module/->SessionEnd)
(def ->Revocation com.ozimos.auth.rama.module/->Revocation)
(def ->RevokeAllForUser com.ozimos.auth.rama.module/->RevokeAllForUser)
(def ->ClearRevocation com.ozimos.auth.rama.module/->ClearRevocation)
(def ->ResetToken com.ozimos.auth.rama.module/->ResetToken)
(def ->ClearResetToken com.ozimos.auth.rama.module/->ClearResetToken)
(def ->OrgCreate com.ozimos.auth.rama.module/->OrgCreate)
(def ->OrgInvite com.ozimos.auth.rama.module/->OrgInvite)
(def ->OrgJoin com.ozimos.auth.rama.module/->OrgJoin)
(def ->OrgSwitch com.ozimos.auth.rama.module/->OrgSwitch)
(def ->OrgMemberUpdate com.ozimos.auth.rama.module/->OrgMemberUpdate)
(def ->OrgMemberRemove com.ozimos.auth.rama.module/->OrgMemberRemove)
(def ->MfaSetup com.ozimos.auth.rama.module/->MfaSetup)
(def ->MfaDisable com.ozimos.auth.rama.module/->MfaDisable)
(def ->MfaConsumeBackupCode com.ozimos.auth.rama.module/->MfaConsumeBackupCode)

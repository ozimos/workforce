(ns com.ozimos.workforce.rama.interface
  (:require
   [com.ozimos.workforce.rama.core :as core]
   [com.ozimos.workforce.rama.module]))

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

(def ->Registration com.ozimos.workforce.rama.module/->Registration)
(def ->Verification com.ozimos.workforce.rama.module/->Verification)
(def ->PasswordChange com.ozimos.workforce.rama.module/->PasswordChange)
(def ->UsernameChange com.ozimos.workforce.rama.module/->UsernameChange)
(def ->SessionStart com.ozimos.workforce.rama.module/->SessionStart)
(def ->SessionEnd com.ozimos.workforce.rama.module/->SessionEnd)
(def ->Revocation com.ozimos.workforce.rama.module/->Revocation)
(def ->RevokeAllForUser com.ozimos.workforce.rama.module/->RevokeAllForUser)
(def ->ClearRevocation com.ozimos.workforce.rama.module/->ClearRevocation)
(def ->ResetToken com.ozimos.workforce.rama.module/->ResetToken)
(def ->ClearResetToken com.ozimos.workforce.rama.module/->ClearResetToken)
(def ->OrgCreate com.ozimos.workforce.rama.module/->OrgCreate)
(def ->OrgInvite com.ozimos.workforce.rama.module/->OrgInvite)
(def ->OrgJoin com.ozimos.workforce.rama.module/->OrgJoin)
(def ->OrgSwitch com.ozimos.workforce.rama.module/->OrgSwitch)
(def ->OrgMemberUpdate com.ozimos.workforce.rama.module/->OrgMemberUpdate)
(def ->OrgMemberRemove com.ozimos.workforce.rama.module/->OrgMemberRemove)
(def ->MfaSetup com.ozimos.workforce.rama.module/->MfaSetup)
(def ->MfaDisable com.ozimos.workforce.rama.module/->MfaDisable)
(def ->MfaConsumeBackupCode com.ozimos.workforce.rama.module/->MfaConsumeBackupCode)
(def ->MfaRegenerateBackupCodes com.ozimos.workforce.rama.module/->MfaRegenerateBackupCodes)
(def ->WebAuthnRegister com.ozimos.workforce.rama.module/->WebAuthnRegister)
(def ->WebAuthnUpdateSignCount com.ozimos.workforce.rama.module/->WebAuthnUpdateSignCount)
(def ->WebAuthnRemoveCredential com.ozimos.workforce.rama.module/->WebAuthnRemoveCredential)
(def ->OAuthLink com.ozimos.workforce.rama.module/->OAuthLink)

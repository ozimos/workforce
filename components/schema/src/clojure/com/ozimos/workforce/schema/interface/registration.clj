(ns com.ozimos.workforce.schema.interface.registration
  (:require
   [com.ozimos.workforce.schema.interface :as schema]))

(def register-request
  [:map
   [:username {:optional true} schema/username]
   [:email schema/email]
   [:password schema/password]])

(def login-request
  [:map
   [:identifier [:or schema/email schema/username]]
   [:password schema/password]])

(def refresh-request
  [:map
   [:refresh-token :string]])

(def verify-request
  [:map
   [:user-id :string]])

(def forgot-password-request
  [:map
   [:email schema/email]])

(def reset-password-request
  [:map
   [:token :string]
   [:password schema/password]])

(def change-password-request
  [:map
   [:old-password :string]
   [:password schema/password]])

(def token-response
  [:map
   [:access-token :string]
   [:refresh-token :string]
   [:expires-in :int]])

(def user-response
  [:map
   [:id :int]
   [:username :string]
   [:email :string]
   [:verified :boolean]])

(def mfa-verify-setup-request
  [:map
   [:code :string]])

(def mfa-login-request
  [:map
   [:mfa-token :string]
   [:code :string]])

(def mfa-disable-request
  [:map
   [:code :string]])

(ns com.ozimos.workforce.oauth.interface
  (:require
   [com.ozimos.workforce.oauth.core :as core]))

(defn handle-oauth-callback
  "Processes an OAuth2/OIDC user info payload (provider, provider-user-id, email, name).
   Finds or provisions local user, links account if needed, and issues tokens.
   Returns [true {:access-token ... :refresh-token ... :user ...}] or [false {:errors ...}]."
  [deps provider oauth-user-info]
  (core/handle-oauth-callback deps provider oauth-user-info))

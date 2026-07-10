(ns com.ozimos.auth.security.core
  (:require [integrant.core :as ig])
  (:import [org.springframework.context.annotation AnnotationConfigApplicationContext]
          [org.springframework.security.web FilterChainProxy]
          [org.springframework.security.core.userdetails UserDetailsService User UserDetails UsernameNotFoundException]
          [org.springframework.security.crypto.password PasswordEncoder]
          [org.springframework.security.oauth2.jwt JwtDecoder]))

(defn- make-user-details-service
  "Create a UserDetailsService backed by the user store interface."
  [user-store password-encoder]
  (reify UserDetailsService
    (^UserDetails loadUserByUsername [_ ^String username]
     (let [user (com.ozimos.auth.user.interface/find-by-username user-store username)]
       (when (nil? user)
         (throw (UsernameNotFoundException. ^String (str "User not found: " username))))
       (-> (User/withUsername ^String (:username user))
           (.password ^String (:pwd-hash user))
           (.roles ^"[Ljava.lang.String;" (into-array String (vec (:roles user))))
           (.accountExpired (boolean (not (:verified user))))
           (.disabled (boolean (not (:verified user))))
           (.build))))))

(defn build-application-context
  "Builds a Spring ApplicationContext programmatically.
   `deps` must contain:
     :jwt-decoder   - JwtDecoder instance
     :user-service  - user store deps map (passed to user/find-by-username)
     :password-encoder - PasswordEncoder instance"
  [deps]
  (let [ctx (AnnotationConfigApplicationContext.)
        jwt-decoder (:jwt-decoder deps)
        password-encoder (:password-encoder deps)
        user-store (:user-service deps)
        user-details-service (make-user-details-service user-store password-encoder)]
    (.registerSingleton ctx "jwtDecoder" ^Object jwt-decoder)
    (.registerSingleton ctx "passwordEncoder" ^Object password-encoder)
    (.registerSingleton ctx "userDetailsService" ^Object user-details-service)
    (.register ctx (class (com.ozimos.auth.security.SecurityConfig.)))
    (.refresh ctx)
    ctx))

(defn filter-chain-proxy
  "Extract the FilterChainProxy bean named 'springSecurityFilterChain' from the context."
  ^FilterChainProxy [app-ctx]
  (.getBean app-ctx "springSecurityFilterChain" FilterChainProxy))

(defmethod ig/init-key :security/app-context [_ {:keys [jwt-decoder user-service password-encoder]}]
  (let [deps {:jwt-decoder jwt-decoder
              :user-service user-service
              :password-encoder password-encoder}
        ctx (build-application-context deps)
        fcp (filter-chain-proxy ctx)]
    {:app-context ctx
     :filter-chain-proxy fcp}))

(defmethod ig/halt-key! :security/app-context [_ {:keys [app-context]}]
  (when app-context
    (.close app-context)))
(ns com.ozimos.auth.security.core
  (:require
   [integrant.core :as ig])
  (:import
   (org.springframework.context.annotation AnnotationConfigApplicationContext)
   (org.springframework.security.core.userdetails User UserDetails UserDetailsService UsernameNotFoundException)
   (org.springframework.security.oauth2.jwt JwtDecoder)
   (org.springframework.security.web FilterChainProxy)))

(defn- make-user-details-service
  "Create a UserDetailsService backed by a find-user-by-username function.
   `find-user-fn` is a fn: (username) -> user-map with :username, :pwd-hash, :roles, :verified.
   This avoids a compile-time dependency on the user component interface."
  [find-user-fn]
  (reify UserDetailsService
    (^UserDetails loadUserByUsername [_ ^String username]
      (let [user (find-user-fn username)]
        (when (nil? user)
          (throw (UsernameNotFoundException. ^String (str "User not found: " username))))
        (-> (User/withUsername ^String (:username user))
            (.password ^String (:pwd-hash user))
            (.roles ^"[Ljava.lang.String;" (into-array String (vec (:roles user))))
            (.accountExpired (not (:verified user)))
            (.disabled (not (:verified user)))
            (.build))))))

(defn build-application-context
  "Builds a Spring ApplicationContext programmatically.
   `deps` must contain:
     :jwt-decoder   - JwtDecoder instance
     :find-user-fn  - fn (username) -> user-map"
  [deps]
  (let [ctx (AnnotationConfigApplicationContext.)
        jwt-decoder (:jwt-decoder deps)
        find-user-fn (:find-user-fn deps)
        user-details-service (make-user-details-service find-user-fn)]
    (.registerBean ctx JwtDecoder (reify java.util.function.Supplier (get [_] jwt-decoder)) (make-array org.springframework.beans.factory.config.BeanDefinitionCustomizer 0))
    (.registerBean ctx UserDetailsService (reify java.util.function.Supplier (get [_] user-details-service)) (make-array org.springframework.beans.factory.config.BeanDefinitionCustomizer 0))
    (.register ctx (into-array Class [(Class/forName "com.ozimos.auth.security.SecurityConfig")]))
    (.refresh ctx)
    ctx))

(defn filter-chain-proxy
  "Extract the FilterChainProxy bean named 'springSecurityFilterChain' from the context."
  ^FilterChainProxy [app-ctx]
  (.getBean app-ctx "springSecurityFilterChain" FilterChainProxy))

(defmethod ig/init-key :security/app-context [_ {:keys [jwt-decoder rama user-store]}]
  (let [decoder (:decoder jwt-decoder)
        user-deps (or user-store (when rama {:rama rama}))
        find-user-fn (fn [username]
                       ((requiring-resolve 'com.ozimos.auth.user.interface/find-by-username) user-deps username))
        deps {:jwt-decoder decoder
              :find-user-fn find-user-fn}
        ctx (build-application-context deps)
        fcp (filter-chain-proxy ctx)]
    {:app-context ctx
     :filter-chain-proxy fcp}))

(defmethod ig/halt-key! :security/app-context [_ {:keys [app-context]}]
  (when app-context
    (.close app-context)))

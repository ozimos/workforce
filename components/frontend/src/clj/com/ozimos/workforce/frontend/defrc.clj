(ns com.ozimos.workforce.frontend.defrc)

(defn router-union-query
  "Pure helper: constructs a Fulcro EQL union query map for a list of router targets."
  [targets]
  {:router/current-route
   (into {}
         (keep (fn [t]
                 (let [m (meta t)
                       ident-key (or (:ident-key m)
                                     (if (keyword? (:ident m))
                                       (:ident m)
                                       (when (vector? (:ident m)) (first (:ident m)))))
                       q (or (:query m) [])]
                   (when (and ident-key q)
                     [ident-key q])))
               targets))})

(defmacro defrc
  "Defines a pure Replicant view component: a plain fn of `args -> hiccup`
   data (no React class component), carrying `:query` and `:ident` metadata
   so Fulcro's `df/load!` and denormalization algorithms can query and target
   it.

   (defrc my-root
     {:query [:a/b] :ident :app/root}
     [props]
     [:div (:a/b props)])"
  [sym opts args & body]
  `(def ~sym
     (with-meta
       (fn ~(symbol (str sym "-view")) ~args
         ~@body)
       (merge {:component-name '~sym} ~opts))))

(defmacro defrouter-rc
  "Defines a data-driven Replicant Dynamic Router.
   Produces a pure render function that receives denormalized router props
   containing `{:router/current-route active-page-props}` and dispatches
   to the active target component, while carrying Fulcro EQL Union Query
   and routing metadata on the router symbol.

   (defrouter-rc MainRouter
     {:router-id :main-router
      :router-targets [LoginReplicant OrgChartReplicant ...]})"
  [sym {:keys [router-id router-targets] :as opts}]
  (let [targets (vec router-targets)]
    `(def ~sym
       (let [target-list# ~targets
             union-q# {:router/current-route
                       (into {}
                             (keep (fn [t#]
                                     (let [m# (meta t#)
                                           ident-key# (or (:ident-key m#)
                                                          (if (keyword? (:ident m#))
                                                            (:ident m#)
                                                            (when (vector? (:ident m#)) (first (:ident m#)))))]
                                       (when (and ident-key# (:query m#))
                                         [ident-key# (:query m#)])))
                                   target-list#))}
             target-map# (into {}
                               (keep (fn [t#]
                                       (let [m# (meta t#)
                                             ident-key# (or (:ident-key m#)
                                                            (if (keyword? (:ident m#))
                                                            (:ident m#)
                                                            (when (vector? (:ident m#)) (first (:ident m#)))))]
                                         (when ident-key#
                                           [ident-key# t#])))
                                     target-list#))
             route-segment-map# (into {}
                                      (keep (fn [t#]
                                              (let [m# (meta t#)
                                                    seg# (:route-segment m#)]
                                                (when (seq seg#)
                                                  [seg# t#])))
                                            target-list#))]
         (with-meta
           (fn ~(symbol (str sym "-view")) [props#]
             (let [current-route# (:router/current-route props#)
                   ;; In Fulcro union query denormalization:
                   ;; current-route# is {:login-replicant/root {:identifier "..." ...}}
                   ;; or if already unwrapped, {:identifier "..." ...}
                   entry# (when (map? current-route#)
                            (some (fn [[ident-k# comp-fn#]]
                                    (when (contains? current-route# ident-k#)
                                      [ident-k# comp-fn#]))
                                  target-map#))
                   [ident-key# target-comp#] entry#
                   target-props# (if ident-key#
                                   (get current-route# ident-key#)
                                   current-route#)]
               (if target-comp#
                 (target-comp# target-props#)
                 [:div {:class "flex items-center justify-center h-64"}
                  [:p {:class "text-gray-500"} "Page not found"]])))
           (merge
            {:component-name '~sym
             :router-id ~router-id
             :ident (fn [] [:root-router/by-id ~router-id])
             :query [union-q#]
             :targets target-list#
             :target-map target-map#
             :route-segment-map route-segment-map#}
            ~opts))))))


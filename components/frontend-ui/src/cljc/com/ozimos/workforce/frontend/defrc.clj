(ns com.ozimos.workforce.frontend.defrc)

(defmacro defrc
  "Defines a pure Replicant view component: a plain fn of `args -> hiccup`
   data (no React class component), carrying `:query` and `:ident` metadata
   so denormalization algorithms and EQL query engines can query and target
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

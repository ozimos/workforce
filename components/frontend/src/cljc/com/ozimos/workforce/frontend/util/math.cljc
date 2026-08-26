(ns com.ozimos.workforce.frontend.util.math)

(defn round
  [n]
  #?(:cljs (js/Math.round n)
     :clj (Math/round n)))

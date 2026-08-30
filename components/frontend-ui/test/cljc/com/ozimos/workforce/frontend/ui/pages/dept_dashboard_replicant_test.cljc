(ns com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:loading false :error nil :unit-id "eng"
          :dashboard {:unit/id "eng" :unit/budget 10 :unit/filled 4 :unit/open 3 :unit/pending 2 :unit/avg-sla-ms 3600000 :unit/actors {:hiring-manager "bob"}}
          :active-org {:org/name "TestCo"}
          :available-units [{:unit/id "eng" :unit/name "Eng"} {:unit/id "plat" :unit/name "Plat"}]}
         overrides))
(defn- hiccup->html [props] (rs/render (sut/DeptDashboardReplicant props)))
(defn- hiccup-tree [props] (sut/DeptDashboardReplicant props))
(defn- valid-hiccup? [node]
  (cond (nil? node) true (string? node) (not (or (str/starts-with? (str/trim node) "[") (str/starts-with? (str/trim node) "{"))) (number? node) true (boolean? node) true
        (vector? node) (and (keyword? (first node)) (let [[_ maybe-attrs & more] node children (if (map? maybe-attrs) more (cons maybe-attrs more))] (every? valid-hiccup? children)))
        (sequential? node) (every? valid-hiccup? node) :else false))
(defn- find-event [hiccup pred]
  (letfn [(walk [node]
            (cond (and (vector? node) (keyword? (first node)))
                  (let [[_tag maybe-attrs & more] node attrs (when (map? maybe-attrs) maybe-attrs) on (or (get-in attrs [:on :click]) (get-in attrs [:on :change]) (get-in attrs [:on :input])) children (if (map? maybe-attrs) more (rest node))]
                    (or (when (and on (pred on)) on) (some walk children)))
                  (sequential? node) (some walk node) :else nil))]
    (walk hiccup)))

(deftest rendered-kpis
  (testing "kpis render"
    (let [html (hiccup->html (base-props {}))]
      (is (str/includes? html "Total Budget"))
      (is (str/includes? html "10"))
      (is (str/includes? html "Filled Positions"))
      (is (str/includes? html "4"))
      (is (str/includes? html "hours average turnaround"))))
  (testing "actors table"
    (is (str/includes? (hiccup->html (base-props {})) "Hiring Manager"))
    (is (str/includes? (hiccup->html (base-props {:dashboard {:unit/id "eng" :unit/actors {}}})) "No scoped actors"))))

(deftest switcher
  (testing "select contains available units"
    (is (str/includes? (hiccup->html (base-props {})) "Eng (eng)"))
    (is (str/includes? (hiccup->html (base-props {})) "Plat (plat)")))
  (testing "load event is pure"
    (let [ev (find-event (hiccup-tree (base-props {})) #(= (first %) :com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant/load))]
      (is (vector? ev)) (is (= :com.ozimos.workforce.frontend.ui.pages.dept-dashboard-replicant/load (first ev))))))

(deftest well-formed
  (testing "valid hiccup"
    (is (true? (valid-hiccup? (hiccup-tree (base-props {})))))
    (is (not (str/includes? (rs/render (hiccup-tree (base-props {}))) "[:div")))))

(deftest pure-state
  (testing "pure fns"
    (is (= "plat" (:unit-id (sut/set-unit-id-state (base-props {}) "plat"))))
    (is (= {:unit/id "x"} (:dashboard (sut/set-dashboard-state (base-props {}) {:unit/id "x"}))))))
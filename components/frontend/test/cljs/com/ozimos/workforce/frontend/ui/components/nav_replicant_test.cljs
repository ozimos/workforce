(ns com.ozimos.workforce.frontend.ui.components.nav-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.components.nav-replicant :as sut]
   [replicant.string :as rs]))
(defn- base-props [o] (merge {:fetched true :active-org {:org/id "1" :org/name "A" :org/role "ADMIN"} :orgs [{:org/id "1" :org/name "A"} {:org/id "2" :org/name "B"}] :dropdown-open false} o))
(defn- html [p] (rs/render (sut/NavBarReplicant p)))
(defn- tree [p] (sut/NavBarReplicant p))
(defn- valid? [n] (cond (nil? n) true (string? n) true (number? n) true (boolean? n) true (vector? n) (and (keyword? (first n)) (let [[_ a & m] n c (if (map? a) m (cons a m))] (every? valid? c))) (sequential? n) (every? valid? n) :else false))
(defn- find-ev [h pred] (letfn [(walk [n] (cond (and (vector? n) (keyword? (first n))) (let [[_ a & m] n attrs (when (map? a) a) on (get-in attrs [:on :click]) ch (if (map? a) m (rest n))] (or (when (and on (pred on)) on) (some walk ch))) (sequential? n) (some walk n) :else nil))] (walk h)))
(deftest rendering (testing "nav shows org name" (is (str/includes? (html (base-props {})) "A")) (is (str/includes? (html (base-props {})) "Workforce")) (is (str/includes? (html (base-props {:dropdown-open true})) "Switch Organization"))))
(deftest events (testing "toggle dropdown" (let [h (tree (base-props {})) ev (find-ev h #(= (first %) :com.ozimos.workforce.frontend.ui.components.nav-replicant/toggle-dropdown))] (is (= [:com.ozimos.workforce.frontend.ui.components.nav-replicant/toggle-dropdown] ev)))))
(deftest well-formed (testing "valid" (is (true? (valid? (tree (base-props {})))))))
(deftest pure-state
  (testing "pure fns"
    (is (= true (:dropdown-open (sut/toggle-dropdown-state (base-props {:dropdown-open false})))))
    (is (= false (:dropdown-open (sut/toggle-dropdown-state (base-props {:dropdown-open true})))))
    (is (= 0 (sut/uncompleted-steps-count {:user/mfa-enabled? true})))
    (is (= 1 (sut/uncompleted-steps-count {:user/mfa-enabled? false})))))
(deftest metadata (testing "query ident" (is (= [:fetched :active-org :orgs :dropdown-open] (:query (meta sut/NavBarReplicant)))) (is (= :nav-replicant/root (:ident (meta sut/NavBarReplicant))))))

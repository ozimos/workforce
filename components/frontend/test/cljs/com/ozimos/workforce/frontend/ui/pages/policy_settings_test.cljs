(ns com.ozimos.workforce.frontend.ui.pages.policy-settings-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings :as sut]
   [replicant.string :as rs]))
(defn- base-props [o] (merge {:loading false :error nil :active-org {:org/name "TestCo"} :permissions {:admin {:view-headcount :view-all :view-comp true :view-bonus true :view-rsu true}} :rules [{:rule-id "r1" :priority 1 :name "Rule1" :conditions {:level "L5"} :chain [:a :b]}]} o))
(defn- html [p] (rs/render (sut/PolicySettings p)))
(defn- tree [p] (sut/PolicySettings p))
(defn- valid? [n] (cond (nil? n) true (string? n) (not (or (str/starts-with? (str/trim n) "[") (str/starts-with? (str/trim n) "{"))) (number? n) true (boolean? n) true (vector? n) (and (keyword? (first n)) (let [[_ a & m] n c (if (map? a) m (cons a m))] (every? valid? c))) (sequential? n) (every? valid? n) :else false))
(deftest rendering (testing "permissions matrix" (is (str/includes? (html (base-props {})) "Role Permission Matrix")) (is (str/includes? (html (base-props {})) "admin")) (is (str/includes? (html (base-props {})) "Rule1")) (is (str/includes? (html (base-props {})) "Priority"))))
(deftest loading-error (testing "loading" (is (str/includes? (html (base-props {:loading true})) "Loading policies")) (is (str/includes? (html (base-props {:error "boom"})) "boom")) (is (str/includes? (html (base-props {:permissions {} :rules []})) "Default routing"))))
(deftest well-formed (testing "valid" (is (true? (valid? (tree (base-props {}))))) (is (not (str/includes? (html (base-props {})) "[:div")))))
(deftest pure-state (testing "pure fns" (is (= {:a 1} (:permissions (sut/set-permissions-state (base-props {}) {:a 1})))) (is (= [{:rule-id "x"}] (:rules (sut/set-rules-state (base-props {}) [{:rule-id "x"}])))) (is (= true (:loading (sut/set-loading-state (base-props {}) true))))))
(deftest denormalize (testing "denorm" (let [app (app/headless-synchronous-app sut/PolicySettings) a (::app/state-atom app)] (swap! a merge (base-props {})) (let [q (:query (meta sut/PolicySettings)) t (denorm/db->tree q @a @a) h (sut/PolicySettings t)] (is (= "TestCo" (get-in t [:active-org :org/name]))) (is (true? (valid? h))) (is (str/includes? (rs/render h) "TestCo"))))))
(deftest interaction (testing "set permissions via transact" (let [app (app/headless-synchronous-app sut/PolicySettings) a (::app/state-atom app) q (:query (meta sut/PolicySettings))] (swap! a merge (base-props {})) (let [h1 (sut/PolicySettings (denorm/db->tree q @a @a))] (is (str/includes? (rs/render h1) "admin")) (comp/transact! app [(sut/set-permissions {:permissions {:custom {:view-headcount :view-own}}})]) (is (= {:custom {:view-headcount :view-own}} (:permissions @a))) (let [h2 (sut/PolicySettings (denorm/db->tree q @a @a))] (is (str/includes? (rs/render h2) "custom")))))))
(deftest metadata (testing "query ident" (let [q (:query (meta sut/PolicySettings))] (is (vector? q)) (is (some #{:loading} q)) (is (some #(and (map? %) (contains? % :rules)) q))) (is (= :policy-settings/root (:ident (meta sut/PolicySettings))))))

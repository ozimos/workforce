(ns com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.policy-settings-replicant :as sut]
   [replicant.string :as rs]))
(defn- base-props [o] (merge {:loading false :error nil :active-org {:org/name "TestCo"} :permissions {:admin {:view-headcount :view-all :view-comp true :view-bonus true :view-rsu true}} :rules [{:rule-id "r1" :priority 1 :name "Rule1" :conditions {:level "L5"} :chain [:a :b]}]} o))
(defn- html [p] (rs/render (sut/PolicySettingsReplicant p)))
(defn- tree [p] (sut/PolicySettingsReplicant p))
(defn- valid? [n] (cond (nil? n) true (string? n) (not (or (str/starts-with? (str/trim n) "[") (str/starts-with? (str/trim n) "{"))) (number? n) true (boolean? n) true (vector? n) (and (keyword? (first n)) (let [[_ a & m] n c (if (map? a) m (cons a m))] (every? valid? c))) (sequential? n) (every? valid? n) :else false))
(deftest rendering (testing "permissions matrix" (is (str/includes? (html (base-props {})) "Role Permission Matrix")) (is (str/includes? (html (base-props {})) "admin")) (is (str/includes? (html (base-props {})) "Rule1")) (is (str/includes? (html (base-props {})) "Priority"))))
(deftest loading-error (testing "loading" (is (str/includes? (html (base-props {:loading true})) "Loading policies")) (is (str/includes? (html (base-props {:error "boom"})) "boom")) (is (str/includes? (html (base-props {:permissions {} :rules []})) "Default routing"))))
(deftest well-formed (testing "valid" (is (true? (valid? (tree (base-props {}))))) (is (not (str/includes? (html (base-props {})) "[:div")))))
(deftest pure-state (testing "pure fns" (is (= {:a 1} (:permissions (sut/set-permissions-state (base-props {}) {:a 1})))) (is (= [{:rule-id "x"}] (:rules (sut/set-rules-state (base-props {}) [{:rule-id "x"}])))) (is (= true (:loading (sut/set-loading-state (base-props {}) true))))))
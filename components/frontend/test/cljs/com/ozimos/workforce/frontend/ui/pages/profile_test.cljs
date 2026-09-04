(ns com.ozimos.workforce.frontend.ui.pages.profile-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.profile :as sut]
   [replicant.string :as rs]))
(defn- base-props [o] (merge {:new-username "" :error-msg nil :success-msg nil :loading false :mfa-stage :disabled :mfa-secret nil :mfa-qr-url nil :mfa-backup-codes [] :totp-code ""} o))
(defn- html [p] (rs/render (sut/Profile p)))
(defn- tree [p] (sut/Profile p))
(defn- valid? [n] (cond (nil? n) true (string? n) (not (or (str/starts-with? (str/trim n) "[") (str/starts-with? (str/trim n) "{"))) (number? n) true (boolean? n) true (vector? n) (and (keyword? (first n)) (let [[_ a & m] n c (if (map? a) m (cons a m))] (every? valid? c))) (sequential? n) (every? valid? n) :else false))
(defn- find-ev [h pred] (letfn [(walk [n] (cond (and (vector? n) (keyword? (first n))) (let [[_ a & m] n attrs (when (map? a) a) on (or (get-in attrs [:on :click]) (get-in attrs [:on :input]) (get-in attrs [:on :submit])) ch (if (map? a) m (rest n))] (or (when (and on (pred on)) on) (some walk ch))) (sequential? n) (some walk n) :else nil))] (walk h)))
(deftest rendering (testing "profile header" (is (str/includes? (html (base-props {})) "Profile")) (is (str/includes? (html (base-props {})) "Security")) (is (str/includes? (html (base-props {})) "Current username")) (is (str/includes? (html (base-props {:mfa-stage :enabled})) "Two-Factor Authentication is Enabled")) (is (str/includes? (html (base-props {:mfa-stage :disabled})) "2FA is currently disabled"))))
(deftest events (testing "update username and mfa" (let [h (tree (base-props {})) ev1 (find-ev h #(= (first %) :com.ozimos.workforce.frontend.ui.pages.profile/set-new-username)) ev2 (find-ev h #(= (first %) :com.ozimos.workforce.frontend.ui.pages.profile/setup-mfa))] (is (vector? ev1)) (is (vector? ev2)))))
(deftest well-formed (testing "valid" (is (true? (valid? (tree (base-props {}))))) (is (not (str/includes? (html (base-props {})) "[:div")))))
(deftest pure-state (testing "pure fns" (is (= "alice" (:new-username (sut/set-new-username-state (base-props {}) "alice")))) (is (= :setup (:mfa-stage (sut/set-mfa-stage-state (base-props {}) :setup)))) (is (= "code" (:totp-code (sut/set-totp-code-state (base-props {}) "code"))))))
(deftest denormalize (testing "denorm" (let [app (app/headless-synchronous-app sut/Profile) a (::app/state-atom app)] (swap! a merge (base-props {})) (let [q (:query (meta sut/Profile)) t (denorm/db->tree q @a @a) h (sut/Profile t)] (is (= "" (:new-username t))) (is (true? (valid? h))) (is (str/includes? (rs/render h) "Profile"))))))
(deftest interaction (testing "set username via transact" (let [app (app/headless-synchronous-app sut/Profile) a (::app/state-atom app) q (:query (meta sut/Profile))] (swap! a merge (base-props {:new-username ""})) (let [h1 (sut/Profile (denorm/db->tree q @a @a))] (is (str/includes? (rs/render h1) "New username")) (comp/transact! app [(sut/set-new-username {:value "bob"})]) (is (= "bob" (:new-username @a))) (let [h2 (sut/Profile (denorm/db->tree q @a @a))] (is (str/includes? (rs/render h2) "bob")))))))
(deftest metadata (testing "query ident" (is (= [:new-username :error-msg :success-msg :loading :mfa-stage :mfa-secret :mfa-backup-codes :totp-code] (:query (meta sut/Profile)))) (is (= :profile/root (:ident (meta sut/Profile))))))

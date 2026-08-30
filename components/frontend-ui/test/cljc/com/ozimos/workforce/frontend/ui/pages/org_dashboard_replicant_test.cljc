(ns com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant :as sut]
   [replicant.string :as rs]))
(defn- base-props [o] (merge {:loading false :error-msg nil :active-org {:org/name "TestCo"} :orgs [{:org/id "1" :org/name "A"} {:org/id "2" :org/name "B"}] :members [{:user/id "u1" :membership/role "ADMIN" :membership/status "active"}] :members-loading false :members-error nil :invite-email "" :invite-role "MEMBER" :invite-loading false :invite-msg nil} o))
(defn- html [p] (rs/render (sut/OrgDashboardReplicant p)))
(defn- tree [p] (sut/OrgDashboardReplicant p))
(defn- valid? [n] (cond (nil? n) true (string? n) (not (or (str/starts-with? (str/trim n) "[") (str/starts-with? (str/trim n) "{"))) (number? n) true (boolean? n) true (vector? n) (and (keyword? (first n)) (let [[_ a & m] n c (if (map? a) m (cons a m))] (every? valid? c))) (sequential? n) (every? valid? n) :else false))
(defn- find-ev [h pred] (letfn [(walk [n] (cond (and (vector? n) (keyword? (first n))) (let [[_ a & m] n attrs (when (map? a) a) on (or (get-in attrs [:on :click]) (get-in attrs [:on :input]) (get-in attrs [:on :change])) ch (if (map? a) m (rest n))] (or (when (and on (pred on)) on) (some walk ch))) (sequential? n) (some walk n) :else nil))] (walk h)))
(deftest rendering (testing "header" (is (str/includes? (html (base-props {})) "TestCo")) (is (str/includes? (html (base-props {})) "Switch Organization")) (is (str/includes? (html (base-props {})) "Invite Member")) (is (str/includes? (html (base-props {})) "Members"))))
(deftest switch-org (testing "switch buttons" (let [h (tree (base-props {})) ev (find-ev h #(= (first %) :com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant/switch-org))] (is (= [:com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant/switch-org "1"] ev)))))
(deftest invite-events (testing "invite input and send" (let [h (tree (base-props {})) ev1 (find-ev h #(= (first %) :com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant/set-invite-email)) ev2 (find-ev h #(= (first %) :com.ozimos.workforce.frontend.ui.pages.org-dashboard-replicant/send-invite))] (is (vector? ev1)) (is (vector? ev2)))))
(deftest well-formed (testing "valid" (is (true? (valid? (tree (base-props {}))))) (is (not (str/includes? (html (base-props {})) "[:div")))))
(deftest pure-state (testing "pure fns" (is (= "a@b.com" (:invite-email (sut/set-invite-email-state (base-props {}) "a@b.com")))) (is (= "ADMIN" (:invite-role (sut/set-invite-role-state (base-props {}) "ADMIN")))) (is (= "hi" (:invite-msg (sut/set-invite-msg-state (base-props {}) "hi"))))))
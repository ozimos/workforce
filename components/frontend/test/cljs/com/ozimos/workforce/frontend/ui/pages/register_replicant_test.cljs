(ns com.ozimos.workforce.frontend.ui.pages.register-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.register-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:email ""
          :password ""
          :confirm-password ""
          :error-msg nil
          :field-errors {}
          :success false
          :created-username nil}
         overrides))

(defn- valid-hiccup? [node]
  (cond
    (nil? node) true
    (string? node) (not (or (str/starts-with? (str/trim node) "[") (str/starts-with? (str/trim node) "{")))
    (number? node) true (boolean? node) true
    (vector? node) (and (keyword? (first node))
                        (let [[_ maybe-attrs & more] node
                              children (if (map? maybe-attrs) more (cons maybe-attrs more))]
                          (every? valid-hiccup? children)))
    (sequential? node) (every? valid-hiccup? node)
    :else false))

(deftest initial-register-render
  (testing "renders fields correctly"
    (let [hiccup (sut/RegisterReplicant (base-props {}))
          html (rs/render hiccup)]
      (is (str/includes? html "Create an account"))
      (is (str/includes? html "Email"))
      (is (str/includes? html "Password"))
      (is (str/includes? html "Confirm password"))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div"))))))

(deftest field-errors-render
  (testing "renders field error messages"
    (let [hiccup (sut/RegisterReplicant (base-props {:field-errors {:password "Password too weak"}}))
          html (rs/render hiccup)]
      (is (str/includes? html "Password too weak")))))

(deftest success-state-render
  (testing "renders success screen with options"
    (let [hiccup (sut/RegisterReplicant (base-props {:success true :created-username "alice"}))
          html (rs/render hiccup)]
      (is (str/includes? html "Account created successfully!"))
      (is (str/includes? html "Create Organization"))
      (is (str/includes? html "Join Organization")))))

(deftest pure-state-transitions
  (testing "state transitions"
    (let [db (base-props {})]
      (is (= "a@b.com" (:email (sut/set-email-state db "a@b.com"))))
      (is (= "pass" (:password (sut/set-password-state db "pass"))))
      (is (= "pass" (:confirm-password (sut/set-confirm-password-state db "pass"))))
      (is (= true (:success (sut/set-success-state db "alice")))))))

(deftest headless-denormalize
  (testing "denormalizes in-memory"
    (let [app-inst (app/headless-synchronous-app sut/RegisterReplicant)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/RegisterReplicant))]
      (swap! state-atom merge (base-props {:email "test@example.com"}))
      (let [tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/RegisterReplicant tree)]
        (is (= "test@example.com" (:email tree)))
        (is (true? (valid-hiccup? hiccup)))))))

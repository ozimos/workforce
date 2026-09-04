(ns com.ozimos.workforce.frontend.ui.pages.reset-password-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.reset-password :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:password ""
          :confirm-password ""
          :error-msg nil
          :success false}
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

(deftest initial-render
  (testing "renders password fields"
    (let [hiccup (sut/ResetPassword (base-props {}))
          html (rs/render hiccup)]
      (is (str/includes? html "Reset your password"))
      (is (str/includes? html "New password"))
      (is (str/includes? html "Confirm new password"))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div"))))))

(deftest success-state-render
  (testing "renders success screen"
    (let [hiccup (sut/ResetPassword (base-props {:success true}))
          html (rs/render hiccup)]
      (is (str/includes? html "Password reset successfully!"))
      (is (str/includes? html "Sign in")))))

(deftest pure-state-transitions
  (testing "state transitions"
    (let [db (base-props {})]
      (is (= "pass" (:password (sut/set-password-state db "pass"))))
      (is (= "pass" (:confirm-password (sut/set-confirm-password-state db "pass"))))
      (is (= true (:success (sut/set-success-state db)))))))

(deftest headless-denormalize
  (testing "denormalization in-memory"
    (let [app-inst (app/headless-synchronous-app sut/ResetPassword)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/ResetPassword))]
      (swap! state-atom merge (base-props {:password "p123"}))
      (let [tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/ResetPassword tree)]
        (is (= "p123" (:password tree)))
        (is (true? (valid-hiccup? hiccup)))))))

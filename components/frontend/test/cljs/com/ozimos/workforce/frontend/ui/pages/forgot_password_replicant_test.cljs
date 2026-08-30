(ns com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.forgot-password-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:email ""
          :sent false}
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

(deftest render-states
  (testing "initial form"
    (let [hiccup (sut/ForgotPasswordReplicant (base-props {}))
          html (rs/render hiccup)]
      (is (str/includes? html "Reset your password"))
      (is (str/includes? html "Email address"))
      (is (str/includes? html "Send reset link"))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div")))))
  (testing "sent confirmation"
    (let [hiccup (sut/ForgotPasswordReplicant (base-props {:sent true}))
          html (rs/render hiccup)]
      (is (str/includes? html "If that email is registered, we&apos;ve sent a reset link."))
      (is (str/includes? html "Back to sign in")))))

(deftest pure-state-transitions
  (testing "state transitions"
    (let [db (base-props {})]
      (is (= "test@test.com" (:email (sut/set-email-state db "test@test.com"))))
      (is (= true (:sent (sut/set-sent-state db true)))))))

(deftest headless-denormalize
  (testing "denormalization in-memory"
    (let [app-inst (app/headless-synchronous-app sut/ForgotPasswordReplicant)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/ForgotPasswordReplicant))]
      (swap! state-atom merge (base-props {:email "alice@acme.com"}))
      (let [tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/ForgotPasswordReplicant tree)]
        (is (= "alice@acme.com" (:email tree)))
        (is (true? (valid-hiccup? hiccup)))))))

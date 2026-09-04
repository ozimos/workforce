(ns com.ozimos.workforce.frontend.ui.pages.verify-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.verify :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:status :loading
          :message nil}
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
  (testing "loading state"
    (let [hiccup (sut/Verify (base-props {:status :loading}))
          html (rs/render hiccup)]
      (is (str/includes? html "Verifying your account..."))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div")))))
  (testing "success state"
    (let [hiccup (sut/Verify (base-props {:status :success :message "Verified successfully"}))
          html (rs/render hiccup)]
      (is (str/includes? html "Verified successfully"))
      (is (str/includes? html "Sign in"))))
  (testing "error state"
    (let [hiccup (sut/Verify (base-props {:status :error :message "Token expired"}))
          html (rs/render hiccup)]
      (is (str/includes? html "Token expired"))
      (is (str/includes? html "Back to sign in")))))

(deftest pure-state-transitions
  (testing "state transition fn"
    (let [db (base-props {})]
      (is (= {:status :success :message "OK"} (sut/set-status-state db :success "OK"))))))

(deftest headless-denormalize
  (testing "denormalization in-memory"
    (let [app-inst (app/headless-synchronous-app sut/Verify)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/Verify))]
      (swap! state-atom merge (base-props {:status :success :message "Done"}))
      (let [tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/Verify tree)]
        (is (= :success (:status tree)))
        (is (true? (valid-hiccup? hiccup)))))))

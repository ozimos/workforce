(ns com.ozimos.workforce.frontend.ui.pages.verify-replicant-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.verify-replicant :as sut]
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
    (let [hiccup (sut/VerifyReplicant (base-props {:status :loading}))
          html (rs/render hiccup)]
      (is (str/includes? html "Verifying your account..."))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div")))))
  (testing "success state"
    (let [hiccup (sut/VerifyReplicant (base-props {:status :success :message "Verified successfully"}))
          html (rs/render hiccup)]
      (is (str/includes? html "Verified successfully"))
      (is (str/includes? html "Sign in"))))
  (testing "error state"
    (let [hiccup (sut/VerifyReplicant (base-props {:status :error :message "Token expired"}))
          html (rs/render hiccup)]
      (is (str/includes? html "Token expired"))
      (is (str/includes? html "Back to sign in")))))

(deftest pure-state-transitions
  (testing "state transition fn"
    (let [db (base-props {})]
      (is (= {:status :success :message "OK"} (sut/set-status-state db :success "OK"))))))

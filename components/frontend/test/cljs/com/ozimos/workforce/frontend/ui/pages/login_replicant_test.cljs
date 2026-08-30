(ns com.ozimos.workforce.frontend.ui.pages.login-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.login-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:identifier ""
          :password ""
          :error-msg nil
          :mfa-required false
          :mfa-token nil
          :mfa-code ""}
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

(defn- find-event-in-hiccup [hiccup pred]
  (letfn [(walk [node]
            (cond
              (and (vector? node) (keyword? (first node)))
              (let [[_tag maybe-attrs & more] node
                    attrs (when (map? maybe-attrs) maybe-attrs)
                    on (or (get-in attrs [:on :click]) (get-in attrs [:on :input]) (get-in attrs [:on :submit]))
                    children (if (map? maybe-attrs) more (rest node))]
                (or (when (and on (pred on)) on)
                    (some walk children)))
              (sequential? node) (some walk node)
              :else nil))]
    (walk hiccup)))

(deftest standard-login-render
  (testing "renders identifier and password fields"
    (let [hiccup (sut/LoginReplicant (base-props {}))
          html (rs/render hiccup)
          input-ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.login-replicant/set-identifier))]
      (is (str/includes? html "Sign in to your account"))
      (is (str/includes? html "Email or username"))
      (is (str/includes? html "Password"))
      (is (= [:com.ozimos.workforce.frontend.ui.pages.login-replicant/set-identifier] input-ev))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div"))))))

(deftest mfa-challenge-render
  (testing "renders 2FA code input when mfa-required"
    (let [hiccup (sut/LoginReplicant (base-props {:mfa-required true :mfa-token "tok123"}))
          html (rs/render hiccup)]
      (is (str/includes? html "Two-Factor Authentication"))
      (is (str/includes? html "2FA Code"))
      (is (str/includes? html "Verify &amp; Sign In"))
      (is (true? (valid-hiccup? hiccup))))))

(deftest error-message-render
  (testing "renders error banner"
    (let [html (rs/render (sut/LoginReplicant (base-props {:error-msg "Invalid credentials"})))]
      (is (str/includes? html "Invalid credentials")))))

(deftest pure-state-transitions
  (testing "state transitions"
    (let [db (base-props {})]
      (is (= "alice" (:identifier (sut/set-identifier-state db "alice"))))
      (is (= "secret" (:password (sut/set-password-state db "secret"))))
      (is (= "123456" (:mfa-code (sut/set-mfa-code-state db "123456"))))
      (is (= true (:mfa-required (sut/set-mfa-required-state db "mfa-tok"))))
      (is (= "mfa-tok" (:mfa-token (sut/set-mfa-required-state db "mfa-tok")))))))

(deftest headless-denormalize
  (testing "denormalization"
    (let [app-inst (app/headless-synchronous-app sut/LoginReplicant)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/LoginReplicant))]
      (swap! state-atom merge (base-props {:identifier "bob"}))
      (let [tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/LoginReplicant tree)]
        (is (= "bob" (:identifier tree)))
        (is (true? (valid-hiccup? hiccup)))))))

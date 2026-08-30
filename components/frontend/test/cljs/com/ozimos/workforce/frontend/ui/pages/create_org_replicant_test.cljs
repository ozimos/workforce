(ns com.ozimos.workforce.frontend.ui.pages.create-org-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.create-org-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:name ""
          :error-msg nil
          :loading false
          :success false
          :org-name nil}
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

(deftest render-states
  (testing "initial form render"
    (let [hiccup (sut/CreateOrgReplicant (base-props {}))
          html (rs/render hiccup)]
      (is (str/includes? html "Create Organization"))
      (is (str/includes? html "Organization Name"))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div")))))
  (testing "error message render"
    (let [html (rs/render (sut/CreateOrgReplicant (base-props {:error-msg "Name taken"})))]
      (is (str/includes? html "Name taken"))))
  (testing "loading button state"
    (let [html (rs/render (sut/CreateOrgReplicant (base-props {:loading true})))]
      (is (str/includes? html "Creating..."))))
  (testing "success state render"
    (let [html (rs/render (sut/CreateOrgReplicant (base-props {:success true :org-name "Acme Global"})))]
      (is (str/includes? html "Organization &#39;Acme Global&#39; created successfully!"))
      (is (str/includes? html "Go to Dashboard")))))

(deftest form-events
  (testing "input and submit events are pure data"
    (let [hiccup (sut/CreateOrgReplicant (base-props {}))
          input-ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.create-org-replicant/set-name))
          submit-ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.create-org-replicant/submit))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.create-org-replicant/set-name] input-ev))
      (is (= [:com.ozimos.workforce.frontend.ui.pages.create-org-replicant/submit] submit-ev)))))

(deftest pure-state-transitions
  (testing "state transitions"
    (let [db (base-props {})]
      (is (= "Acme" (:name (sut/set-name-state db "Acme"))))
      (is (= true (:loading (sut/set-loading-state db true))))
      (is (= "Error" (:error-msg (sut/set-error-msg-state db "Error"))))
      (is (= true (:success (sut/set-success-state db "Acme")))))))

(deftest headless-denormalize
  (testing "denormalization from DB"
    (let [app-inst (app/headless-synchronous-app sut/CreateOrgReplicant)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/CreateOrgReplicant))]
      (swap! state-atom merge (base-props {:name "Acme"}))
      (let [tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/CreateOrgReplicant tree)]
        (is (= "Acme" (:name tree)))
        (is (true? (valid-hiccup? hiccup)))))))

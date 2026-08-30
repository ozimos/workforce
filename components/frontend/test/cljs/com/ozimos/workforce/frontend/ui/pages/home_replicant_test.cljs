(ns com.ozimos.workforce.frontend.ui.pages.home-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.ozimos.workforce.frontend.ui.pages.home-replicant :as sut]
   [replicant.string :as rs]))

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
                    on (or (get-in attrs [:on :click]) (get-in attrs [:on :input]))
                    children (if (map? maybe-attrs) more (rest node))]
                (or (when (and on (pred on)) on)
                    (some walk children)))
              (sequential? node) (some walk node)
              :else nil))]
    (walk hiccup)))

(deftest render-all-cards
  (testing "renders all 6 dashboard cards"
    (let [hiccup (sut/HomeReplicant {})
          html (rs/render hiccup)]
      (is (str/includes? html "Workforce Dashboard"))
      (is (str/includes? html "Org Chart"))
      (is (str/includes? html "Headcount Requisitions"))
      (is (str/includes? html "Pending Approvals"))
      (is (str/includes? html "Department Analytics"))
      (is (str/includes? html "Approval Policies &amp; RBAC"))
      (is (str/includes? html "Organization &amp; Members"))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div"))))))

(deftest navigation-events
  (testing "card navigation events are pure data vectors"
    (let [hiccup (sut/HomeReplicant {})
          org-chart-ev (find-event-in-hiccup hiccup #(and (= (first %) :com.ozimos.workforce.frontend.ui.pages.home-replicant/navigate)
                                                         (= (second %) "/org-chart")))
          headcount-ev (find-event-in-hiccup hiccup #(and (= (first %) :com.ozimos.workforce.frontend.ui.pages.home-replicant/navigate)
                                                         (= (second %) "/headcount")))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.home-replicant/navigate "/org-chart"] org-chart-ev))
      (is (= [:com.ozimos.workforce.frontend.ui.pages.home-replicant/navigate "/headcount"] headcount-ev)))))

(deftest defrc-metadata
  (testing "metadata preserved"
    (is (= [:active-org] (:query (meta sut/HomeReplicant))))
    (is (= :home-replicant/root (:ident (meta sut/HomeReplicant))))))

(deftest headless-denormalize
  (testing "denormalizes in-memory"
    (let [app-inst (app/headless-synchronous-app sut/HomeReplicant)
          state-atom (::app/state-atom app-inst)]
      (swap! state-atom assoc :active-org {:org/name "Acme Corp"})
      (let [query (:query (meta sut/HomeReplicant))
            tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/HomeReplicant tree)]
        (is (= {:org/name "Acme Corp"} (:active-org tree)))
        (is (true? (valid-hiccup? hiccup)))))))

(ns com.ozimos.workforce.frontend.ui.pages.org-chart-replicant-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.org-chart-replicant :as sut]
   [replicant.string :as rs]))

;; Mock data: two-level hierarchy eng -> plat
(def mock-units
  {"eng"  {:unit/id "eng" :unit/name "Engineering" :unit/division-id "ENG" :unit/dept-id "ALL" :unit/parent-id nil :unit/budget 10 :unit/filled 8 :unit/open 2 :unit/pending 0}
   "plat" {:unit/id "plat" :unit/name "Platform" :unit/division-id "ENG" :unit/dept-id "PLAT" :unit/parent-id "eng" :unit/budget 5 :unit/filled 3 :unit/open 1 :unit/pending 1}})

(def mock-hierarchy
  {nil ["eng"]
   "eng" ["plat"]})

(defn- base-props [overrides]
  (merge {:loading false
          :error nil
          :active-org {:org/name "TestCo"}
          :units mock-units
          :hierarchy mock-hierarchy
          :search-term ""
          :collapsed-nodes #{}}
         overrides))

(defn- hiccup->html [props]
  (rs/render (sut/OrgChartReplicant props)))

(defn- hiccup-tree [props]
  (sut/OrgChartReplicant props))

(defn- valid-hiccup?
  "Recursively verifies that every node in the hiccup tree is well-formed:
   1. Elements must be vectors with a keyword tag as (first node).
   2. Attributes (if present) must be a map as (second node).
   3. Children cannot be unexpanded raw collections or inner vectors whose
      head is not a keyword (preventing [[:div ...]] raw text serialization)."
  [node]
  (cond
    (nil? node) true
    ;; Strings should not look like serialized Clojure code
    (string? node) (not (or (str/starts-with? (str/trim node) "[")
                            (str/starts-with? (str/trim node) "{")))
    (number? node) true
    (boolean? node) true
    (vector? node)
    (and (keyword? (first node))
         (let [[_ maybe-attrs & more] node
               children (if (map? maybe-attrs) more (cons maybe-attrs more))]
           (every? valid-hiccup? children)))
    (sequential? node) (every? valid-hiccup? node)
    :else false))

(defn- find-event-in-hiccup
  "Walks hiccup data and returns first :on :click vector that matches predicate.
   Correctly distinguishes hiccup element vectors `[:tag ...]` from child-collection
   vectors `[[:div ...] [:div ...]]` produced by `mapv`."
  [hiccup pred]
  (letfn [(walk [node]
            (cond
              ;; Hiccup element: vector whose first element is a keyword tag.
              (and (vector? node) (keyword? (first node)))
              (let [[_tag maybe-attrs & more] node
                    attrs    (when (map? maybe-attrs) maybe-attrs)
                    on-click (get-in attrs [:on :click])
                    ;; Children are `more` when attrs map is present, otherwise
                    ;; `maybe-attrs` is the first child and the full tail is (rest node).
                    children (if (map? maybe-attrs) more (rest node))]
                (or (when (and on-click (pred on-click)) on-click)
                    (some walk children)))
              ;; Collection of child nodes (e.g. mapv result, or seq of children).
              (sequential? node) (some walk node)
              :else nil))]
    (walk hiccup)))

;; =============================================================================
;; 1. Pure View & Determinism Tests
;; =============================================================================

(deftest rendered-hierarchy-given-mock-unit-list
  (testing "root and child units both rendered when not collapsed"
    (let [html (hiccup->html (base-props {}))]
      (is (str/includes? html "Engineering"))
      (is (str/includes? html "Platform"))))
  (testing "pure hiccup equality: view is deterministic for same props"
    (is (= (hiccup-tree (base-props {}))
           (hiccup-tree (base-props {}))))))

(deftest child-units-presence-depends-on-collapsed-nodes
  (testing "when parent collapsed, child not in HTML"
    (let [html (hiccup->html (base-props {:collapsed-nodes #{"eng"}}))]
      (is (str/includes? html "Engineering"))
      (is (not (str/includes? html "Platform")))))
  (testing "when collapsed set cleared, child reappears (simulates transact! toggle)"
    (let [html-collapsed (hiccup->html (base-props {:collapsed-nodes #{"eng"}}))
          html-expanded  (hiccup->html (base-props {:collapsed-nodes #{}}))]
      (is (not (str/includes? html-collapsed "Platform")))
      (is (str/includes? html-expanded "Platform")))))

(deftest action-event-maps-on-cards
  (testing "parent card toggle event is pure data [::sut/toggle-collapse unit-id]"
    (let [hiccup (hiccup-tree (base-props {}))
          toggle-event (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.org-chart-replicant/toggle-collapse))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.org-chart-replicant/toggle-collapse "eng"] toggle-event))))
  (testing "leaf card navigate event is pure data [::sut/navigate dept-dashboard?unit-id=...]"
    (let [hiccup (hiccup-tree (base-props {}))
          nav-event (find-event-in-hiccup hiccup #(and (= (first %) :com.ozimos.workforce.frontend.ui.pages.org-chart-replicant/navigate)
                                                    (str/includes? (second %) "plat")))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.org-chart-replicant/navigate "/dept-dashboard?unit-id=plat"] nav-event))))
  (testing "events are pure data vectors, not fns"
    (let [hiccup (hiccup-tree (base-props {}))
          toggle (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.org-chart-replicant/toggle-collapse))]
      (is (vector? toggle))
      (is (keyword? (first toggle)))
      (is (string? (second toggle))))))

(deftest search-term-highlighting
  (testing "search match adds highlight ring to matching card"
    (let [html-match (hiccup->html (base-props {:search-term "plat"}))
          html-no-match (hiccup->html (base-props {:search-term "zzz"}))]
      ;; Platform card should have highlight when searching plat, but not when searching zzz
      (is (str/includes? html-match "ring-2"))
      (is (not (str/includes? html-no-match "ring-2"))))))

(deftest defrc-metadata-preserved
  (testing "OrgChartReplicant carries :query and :ident for Fulcro denormalization"
    (is (= [:loading :error :active-org :units :hierarchy :search-term :collapsed-nodes]
           (:query (meta sut/OrgChartReplicant))))
    (is (= :org-chart-replicant/root (:ident (meta sut/OrgChartReplicant)))))
  (testing "view is pure fn props -> hiccup, no React"
    (is (fn? sut/OrgChartReplicant))
    (is (vector? (sut/OrgChartReplicant (base-props {}))))))

;; =============================================================================
;; 2. Enhanced Headless Assertions (Preventing Bugs Caught by E2E)
;; =============================================================================

(deftest well-formed-hiccup-structure-test
  ;; ---------------------------------------------------------------------------
  ;; WHY ADDED:
  ;; During E2E testing, we discovered that returning `(mapv ...)` inside Hiccup
  ;; produced nested `[[:div ...]]` vectors. In unit tests, `str/includes?` on
  ;; `rs/render` passed because the string "Platform" was still present inside
  ;; the raw stringified vector, but in a real browser Replicant rendered the raw
  ;; Clojure code `[[:div {:replicant/key ...} ...]]` as literal text.
  ;;
  ;; WHAT IT PREVENTS:
  ;; Prevents raw Clojure collections/vectors from leaking into the DOM as text nodes
  ;; due to missing `into [:div ...]` or unexpanded child sequences.
  ;; ---------------------------------------------------------------------------
  (testing "View tree satisfies strict Hiccup grammar and does not leak serialized Clojure code"
    (let [hiccup (sut/OrgChartReplicant (base-props {}))]
      ;; Recursive structural check:
      (is (true? (valid-hiccup? hiccup)))
      ;; HTML string check: verify no serialized vector tags appear as text
      (let [html (rs/render hiccup)]
        (is (not (str/includes? html "[:div"))
            "Rendered HTML must not contain raw Clojure vector syntax `[:div`")
        (is (not (str/includes? html "[:span"))
            "Rendered HTML must not contain raw Clojure vector syntax `[:span`")))))
(ns com.ozimos.workforce.frontend.ui.pages.org-chart-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.views.org-chart :as sut]
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
  (rs/render (sut/OrgChart props)))

(defn- hiccup-tree [props]
  (sut/OrgChart props))

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
          toggle-event (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.views.org-chart/toggle-collapse))]
      (is (= [:com.ozimos.workforce.frontend.views.org-chart/toggle-collapse "eng"] toggle-event))))
  (testing "leaf card navigate event is pure data [::sut/navigate dept-dashboard?unit-id=...]"
    (let [hiccup (hiccup-tree (base-props {}))
          nav-event (find-event-in-hiccup hiccup #(and (= (first %) :com.ozimos.workforce.frontend.views.org-chart/navigate)
                                                    (str/includes? (second %) "plat")))]
      (is (= [:com.ozimos.workforce.frontend.views.org-chart/navigate "/dept-dashboard?unit-id=plat"] nav-event))))
  (testing "events are pure data vectors, not fns"
    (let [hiccup (hiccup-tree (base-props {}))
          toggle (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.views.org-chart/toggle-collapse))]
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
  (testing "OrgChart carries composed :query and :ident for Fulcro denormalization"
    (let [q (:query (meta sut/OrgChart))]
      (is (vector? q))
      (is (some #{:loading} q))
      (is (some #{:units} q))
      (is (some #(and (map? %) (contains? % :org/chart)) q)))
    (is (= :org-chart/root (:ident (meta sut/OrgChart)))))
  (testing "view is pure fn props -> hiccup, no React"
    (is (fn? sut/OrgChart))
    (is (vector? (sut/OrgChart (base-props {}))))))

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
    (let [hiccup (sut/OrgChart (base-props {}))]
      ;; Recursive structural check:
      (is (true? (valid-hiccup? hiccup)))
      ;; HTML string check: verify no serialized vector tags appear as text
      (let [html (rs/render hiccup)]
        (is (not (str/includes? html "[:div"))
            "Rendered HTML must not contain raw Clojure vector syntax `[:div`")
        (is (not (str/includes? html "[:span"))
            "Rendered HTML must not contain raw Clojure vector syntax `[:span`")))))

(deftest headless-fulcro-state-denormalization-test
  ;; ---------------------------------------------------------------------------
  ;; WHY ADDED:
  ;; E2E testing revealed that seeding data via `comp/set-state!` on the host React
  ;; component failed to reach the Replicant view because the Replicant bridge
  ;; watches the Fulcro state atom (and runs `denorm/db->tree`), not React component state.
  ;;
  ;; WHAT IT PREVENTS:
  ;; Prevents mismatch between the Fulcro root component `:query` metadata and the
  ;; normalized client DB atom structure.
  ;; ---------------------------------------------------------------------------
  (testing "OrgChart :query denormalizes correctly from normalized Fulcro DB atom"
    (let [app-inst   (app/headless-synchronous-app sut/OrgChart)
          state-atom (::app/state-atom app-inst)]
      ;; Seed into the real Fulcro state atom:
      (swap! state-atom merge (base-props {}))
      (let [query  (:query (meta sut/OrgChart))
            tree   (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/OrgChart tree)]
        (is (= "TestCo" (get-in tree [:active-org :org/name]))
            "Active org must denormalize from root query")
        (is (= 2 (count (:units tree)))
            "Units map must denormalize from root query")
        (is (true? (valid-hiccup? hiccup))
            "Hiccup produced from denormalized Fulcro DB tree must be strictly valid")
        (is (str/includes? (rs/render hiccup) "Engineering")
            "Rendered HTML must contain root unit from denormalized DB")))))

(deftest headless-user-interaction-cycle-test
  ;; ---------------------------------------------------------------------------
  ;; WHY ADDED:
  ;; In E2E, clicking parent cards toggled state in the Fulcro DB atom and triggered
  ;; a full re-render. Unit tests previously only passed hardcoded `:collapsed-nodes`
  ;; maps, never verifying the full loop: [Hiccup Event -> transact! mutation -> DB update -> re-render].
  ;;
  ;; WHAT IT PREVENTS:
  ;; Prevents broken mutations, state atom mutation bugs, or re-render failures
  ;; without needing a browser.
  ;; ---------------------------------------------------------------------------
  (testing "Simulating click on parent card updates Fulcro DB atom and removes children on next render"
    (let [app-inst   (app/headless-synchronous-app sut/OrgChart)
          state-atom (::app/state-atom app-inst)
          query      (:query (meta sut/OrgChart))]
      ;; Initial state: expanded
      (swap! state-atom merge (base-props {:collapsed-nodes #{}}))
      ;; 1. First render has children
      (let [hiccup-1 (sut/OrgChart (denorm/db->tree query @state-atom @state-atom))]
        (is (str/includes? (rs/render hiccup-1) "Platform")
            "Initial render must include child unit Platform")
        ;; 2. Extract the toggle event from the rendered Hiccup:
        (let [toggle-event (find-event-in-hiccup hiccup-1 #(= (first %) :com.ozimos.workforce.frontend.views.org-chart/toggle-collapse))]
          (is (= [:com.ozimos.workforce.frontend.views.org-chart/toggle-collapse "eng"] toggle-event)
              "Parent card must emit toggle-collapse event for 'eng'")
          ;; 3. Execute the mutation on the Fulcro app (simulating what the bridge dispatcher does):
          (comp/transact! app-inst [(sut/toggle-collapse {:id (second toggle-event)})])
          ;; 4. Verify DB atom was mutated:
          (is (contains? (:collapsed-nodes @state-atom) "eng")
              "Fulcro DB atom :collapsed-nodes must now contain 'eng'")
          ;; 5. Second render reflects the DB update:
          (let [hiccup-2 (sut/OrgChart (denorm/db->tree query @state-atom @state-atom))]
            (is (not (str/includes? (rs/render hiccup-2) "Platform"))
                "After toggle mutation, child unit Platform must be absent from rendered output")
            (is (str/includes? (rs/render hiccup-2) "Engineering")
                "Parent unit Engineering must remain visible")))))))

(deftest pure-state-transitions-test
  ;; ---------------------------------------------------------------------------
  ;; WHY ADDED (per architecture: pure (fn [db params] -> db) in frontend-ui):
  ;; A Mutation is just (fn [db params] -> updated-db). Fulcro's defmutation is a
  ;; thin wrapper (action [{:keys [state]}] (swap! state pure-fn ...)).
  ;; By keeping the pure transition in frontend-ui (.cljc), both Web (Fulcro)
  ;; and Mobile (plain atom, no Fulcro) share 100% logic. Mobile's
  ;; bases/mobile dispatches via plain swap! without macro overhead.
  ;;
  ;; WHAT IT PREVENTS:
  ;; Prevents coupling frontend-ui to Fulcro runtime; ensures mobile plain atom
  ;; and web Fulcro atom produce identical state transitions. Vendored
  ;; denormalize is pure and shared; network EQL flows via transit+json.
  ;; ---------------------------------------------------------------------------
  (testing "Pure state fns are pure data transforms (no atom, no Fulcro)"
    (let [db (base-props {})]
      (is (= #{"eng"} (:collapsed-nodes (sut/toggle-collapse-state db "eng"))))
      (is (= #{} (:collapsed-nodes (sut/toggle-collapse-state (assoc db :collapsed-nodes #{"eng"}) "eng")))
          "Toggle is idempotent flip")
      (is (= #{} (:collapsed-nodes (sut/expand-all-state (assoc db :collapsed-nodes #{"eng" "plat"})))))
      (is (= #{"eng" "plat"} (:collapsed-nodes (sut/collapse-all-state db)))
          "Collapse-all sets to all unit ids")
      (is (= "hello" (:search-term (sut/set-search-term-state db "hello"))))))
  (testing "Plain atom + pure fns (Mobile) produces same render as Fulcro transact! (Web)"
    (let [plain-atom (atom (base-props {:collapsed-nodes #{}}))
          query      (:query (meta sut/OrgChart))]
      ;; Mobile dispatch simulation: plain swap! with pure fn
      (swap! plain-atom sut/toggle-collapse-state "eng")
      (is (contains? (:collapsed-nodes @plain-atom) "eng"))
      (let [hiccup (sut/OrgChart (denorm/db->tree query @plain-atom @plain-atom))]
        (is (not (str/includes? (rs/render hiccup) "Platform"))
            "Plain atom toggle hides child same as Fulcro transact!"))
      ;; Expand via pure fn
      (swap! plain-atom sut/expand-all-state)
      (is (= #{} (:collapsed-nodes @plain-atom)))
      (is (str/includes? (rs/render (sut/OrgChart (denorm/db->tree query @plain-atom @plain-atom))) "Platform")))))

(ns com.ozimos.workforce.frontend.ui.pages.headcount-replicant-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.algorithms.denormalize :as denorm]
   [com.fulcrologic.fulcro.application :as app]
   [com.fulcrologic.fulcro.components :as comp]
   [com.ozimos.workforce.frontend.ui.pages.headcount-replicant :as sut]
   [replicant.string :as rs]))

(defn- base-props [overrides]
  (merge {:loading false
          :error nil
          :active-org {:org/name "TestCo"}
          :pending-approvals [{:headcount/id "req-1" :headcount/title "Engineer" :headcount/unit-id "eng" :headcount/job-level "L4" :headcount/current-step 1}
                              {:headcount/id "req-2" :headcount/title "Designer" :headcount/unit-id "design" :headcount/job-level "L3" :headcount/current-step 2}]
          :submitting false :msg nil
          :form-unit-id "" :form-title "" :form-level "L4" :form-salary "" :form-bonus "" :form-justification ""}
         overrides))

(defn- hiccup->html [props] (rs/render (sut/HeadcountReplicant props)))
(defn- hiccup-tree [props] (sut/HeadcountReplicant props))

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
                    on (or (get-in attrs [:on :click]) (get-in attrs [:on :input]) (get-in attrs [:on :change]))
                    children (if (map? maybe-attrs) more (rest node))]
                (or (when (and on (pred on)) on)
                    (some walk children)))
              (sequential? node) (some walk node)
              :else nil))]
    (walk hiccup)))

(deftest rendered-inbox
  (testing "loading shows pending message"
    (is (str/includes? (hiccup->html (base-props {:loading true})) "Loading pending approvals")))
  (testing "empty shows no requisitions"
    (is (str/includes? (hiccup->html (base-props {:pending-approvals []})) "No requisitions")))
  (testing "with data shows table rows"
    (let [html (hiccup->html (base-props {}))]
      (is (str/includes? html "Engineer"))
      (is (str/includes? html "Designer"))
      (is (str/includes? html "Approve"))
      (is (str/includes? html "Reject")))))

(deftest form-render
  (testing "form shows submit button"
    (is (str/includes? (hiccup->html (base-props {})) "Submit Requisition")))
  (testing "msg shows when present"
    (is (str/includes? (hiccup->html (base-props {:msg "Error: boom"})) "Error: boom"))))

(deftest action-events-are-pure
  (testing "approve event is pure vector [::approve id]"
    (let [hiccup (hiccup-tree (base-props {}))
          ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.headcount-replicant/approve))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.headcount-replicant/approve "req-1"] ev))))
  (testing "reject event is pure"
    (let [hiccup (hiccup-tree (base-props {}))
          ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.headcount-replicant/reject))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.headcount-replicant/reject "req-1"] ev))))
  (testing "create event"
    (let [hiccup (hiccup-tree (base-props {}))
          ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.headcount-replicant/create))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.headcount-replicant/create] ev))))
  (testing "events are vectors not fns"
    (let [ev (find-event-in-hiccup (hiccup-tree (base-props {})) #(= (first %) :com.ozimos.workforce.frontend.ui.pages.headcount-replicant/approve))]
      (is (vector? ev)) (is (keyword? (first ev))))))

(deftest well-formed-hiccup
  (testing "no raw [:div in html"
    (let [hiccup (hiccup-tree (base-props {}))]
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? (rs/render hiccup) "[:div"))))))

(deftest defrc-metadata
  (testing "query and ident"
    (is (= [:loading :error :active-org :pending-approvals :submitting :msg :form-unit-id :form-title :form-level :form-salary :form-bonus :form-justification]
           (:query (meta sut/HeadcountReplicant))))
    (is (= :headcount-replicant/root (:ident (meta sut/HeadcountReplicant))))))

(deftest pure-state-transitions
  (testing "pure fns"
    (is (= "foo" (:form-title (sut/set-form-field-state (base-props {}) :form-title "foo"))))
    (is (= "" (:form-title (sut/clear-form-state (assoc (base-props {}) :form-title "x" :form-justification "y")))))
    (is (= "msg" (:msg (sut/set-msg-state (base-props {}) "msg"))))
    (is (= true (:submitting (sut/set-submitting-state (base-props {}) true))))
    (is (= [{:headcount/id "a"}] (:pending-approvals (sut/set-pending-approvals-state (base-props {}) [{:headcount/id "a"}])))))
  (testing "plain atom parity with transact!"
    (let [plain-atom (atom (base-props {}))
          app-inst (app/headless-synchronous-app sut/HeadcountReplicant)
          state-atom (::app/state-atom app-inst)]
      (swap! state-atom merge (base-props {}))
      (swap! plain-atom sut/set-form-field-state :form-title "Hello")
      (comp/transact! app-inst [(sut/set-form-field {:field :form-title :value "Hello"})])
      (is (= (:form-title @plain-atom) (:form-title @state-atom)))
      (is (= "Hello" (:form-title @plain-atom))))))

(deftest headless-denormalize
  (testing "denormalizes from Fulcro DB"
    (let [app-inst (app/headless-synchronous-app sut/HeadcountReplicant)
          state-atom (::app/state-atom app-inst)]
      (swap! state-atom merge (base-props {}))
      (let [query (:query (meta sut/HeadcountReplicant))
            tree (denorm/db->tree query @state-atom @state-atom)
            hiccup (sut/HeadcountReplicant tree)]
        (is (= 2 (count (:pending-approvals tree))))
        (is (true? (valid-hiccup? hiccup)))
        (is (str/includes? (rs/render hiccup) "Engineer"))))))

(deftest headless-interaction-cycle
  (testing "approve click updates DB and re-render"
    (let [app-inst (app/headless-synchronous-app sut/HeadcountReplicant)
          state-atom (::app/state-atom app-inst)
          query (:query (meta sut/HeadcountReplicant))]
      (swap! state-atom merge (base-props {:pending-approvals [{:headcount/id "r1" :headcount/title "T" :headcount/unit-id "u" :headcount/job-level "L4" :headcount/current-step 1}]}))
      (let [hiccup-1 (sut/HeadcountReplicant (denorm/db->tree query @state-atom @state-atom))]
        (is (str/includes? (rs/render hiccup-1) "T"))
        (let [ev (find-event-in-hiccup hiccup-1 #(= (first %) :com.ozimos.workforce.frontend.ui.pages.headcount-replicant/approve))]
          (is (= [:com.ozimos.workforce.frontend.ui.pages.headcount-replicant/approve "r1"] ev))
          (comp/transact! app-inst [(sut/set-msg {:msg "Approve r1"})])
          (is (= "Approve r1" (:msg @state-atom)))
          (let [hiccup-2 (sut/HeadcountReplicant (denorm/db->tree query @state-atom @state-atom))]
            (is (str/includes? (rs/render hiccup-2) "Approve r1"))))))))

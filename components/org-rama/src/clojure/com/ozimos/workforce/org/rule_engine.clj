(ns com.ozimos.workforce.org.rule-engine
  "Malli-validated condition DSL for evaluating headcount approval policies
   and selecting approval chains. Replaces the previous O'Doyle Rules implementation
   with a pure Clojure recursive evaluator backed by Malli schema validation."
  (:require
   [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Malli Condition Schema
;; ---------------------------------------------------------------------------
;; Two condition formats are supported:
;;   1. Legacy flat map: {:dept-id "eng" :job-level "L5"} (nil = wildcard, set = membership)
;;   2. DSL node map:   {:op :and/:or/:not/:=/:!=:/</:>/:in :conditions [...] :field kw :value any}

(def condition-node-schema
  "Recursive Malli schema for DSL condition nodes."
  (m/schema
    [:schema {:registry {"condition" [:multi {:dispatch :op}
                                      [:and [:map [:op [:= :and]] [:conditions [:vector [:ref "condition"]]]]]
                                      [:or  [:map [:op [:= :or]]  [:conditions [:vector [:ref "condition"]]]]]
                                      [:not [:map [:op [:= :not]] [:conditions [:tuple [:ref "condition"]]]]]
                                      [:=   [:map [:op [:= :=]]   [:field :keyword] [:value :any]]]
                                      [:!=  [:map [:op [:= :!=]]  [:field :keyword] [:value :any]]]
                                      [:<   [:map [:op [:= :<]]   [:field :keyword] [:value :any]]]
                                      [:>   [:map [:op [:= :>]]   [:field :keyword] [:value :any]]]
                                      [:in  [:map [:op [:= :in]]  [:field :keyword] [:value [:set :any]]]]]}} "condition"]))

(defn valid-condition?
  "Returns true if `c` conforms to the DSL condition schema."
  [c]
  (m/validate condition-node-schema c))

;; ---------------------------------------------------------------------------
;; Recursive DSL Condition Evaluator
;; ---------------------------------------------------------------------------

(defn eval-condition
  "Recursively evaluates a DSL condition map against a fact map.
   Supports compound operators (:and, :or, :not) and
   leaf comparisons (:=, :!=, :<, :>, :in)."
  [condition facts]
  (let [op (:op condition)]
    (case op
      :and (every? #(eval-condition % facts) (:conditions condition))
      :or  (boolean (some #(eval-condition % facts) (:conditions condition)))
      :not (not (eval-condition (first (:conditions condition)) facts))
      :=   (= (get facts (:field condition)) (:value condition))
      :!=  (not= (get facts (:field condition)) (:value condition))
      :<   (< (compare (get facts (:field condition)) (:value condition)) 0)
      :>   (> (compare (get facts (:field condition)) (:value condition)) 0)
      :in  (contains? (:value condition) (get facts (:field condition)))
      (throw (ex-info "Unknown condition :op" {:op op :condition condition})))))

;; ---------------------------------------------------------------------------
;; Backward-Compatible Flat-Map Condition Matching
;; ---------------------------------------------------------------------------
;; Used by routing rules that still use the legacy {:field expected-val} map format.
;; nil = wildcard, set = membership, vector = any-of, fn = predicate, else = equality.

(defn- condition-value-matches?
  [expected actual]
  (cond
    (nil? expected)    true
    (set? expected)    (contains? expected actual)
    (vector? expected) (some #(= % actual) expected)
    (fn? expected)     (expected actual)
    :else              (= expected actual)))

(defn match-rule-conditions?
  "Checks whether conditions match the request facts map.
   Supports flat maps {:job-level \"L6\"}, vector DSL [:= :job-level \"L6\"],
   and structured Malli maps {:op := :field :job-level :value \"L6\"}."
  [conditions request]
  (cond
    (nil? conditions) true
    (map? conditions)
    (if (contains? conditions :op)
      (eval-condition conditions request)
      (every? (fn [[k expected-val]]
                (condition-value-matches? expected-val (get request k)))
              conditions))

    (vector? conditions)
    (if (keyword? (first conditions))
      (let [[op field val] conditions]
        (case op
          :and (every? #(match-rule-conditions? % request) (rest conditions))
          :or  (some #(match-rule-conditions? % request) (rest conditions))
          (eval-condition {:op op :field field :value val} request)))
      (every? #(match-rule-conditions? % request) conditions))

    :else false))

;; ---------------------------------------------------------------------------
;; Approval Chain Routing  (first-match by priority)
;; ---------------------------------------------------------------------------

(defn find-routing-rule
  "Finds the highest-priority matching rule from `rules`.
   Supports both legacy flat-map `:conditions` and DSL `:when` condition formats.
   Returns the first (highest-priority) matching rule map, or nil if none match."
  [rules request]
  (->> rules
       (sort-by #(or (:priority %) 0) >)
       (some (fn [rule]
               (let [matched? (if (contains? rule :when)
                                (eval-condition (:when rule) request)
                                (match-rule-conditions? (:conditions rule) request))]
                 (when matched? rule))))))

(defn eval-approval-chain
  "Returns the approval chain for `request` by finding the highest-priority
   matching rule. Falls back to `default-chain` when no rule matches."
  ([rules request]
   (eval-approval-chain rules request [{:step 1 :role :hiring-manager}
                                       {:step 2 :role :department-head}]))
  ([rules request default-chain]
   (if (empty? rules)
     default-chain
     (if-let [rule (find-routing-rule rules request)]
       (:chain rule)
       default-chain))))

;; ---------------------------------------------------------------------------
;; Custom Tenant Rules  (all-match, sorted by priority)
;; ---------------------------------------------------------------------------

(defn apply-custom-rules
  "Evaluates all `rules` against `request` and returns the matching subset
   sorted by :priority ascending. All matching rules fire (not just the first).
   Rules may use either the flat-map :conditions format or the DSL :when format.
   Returns an empty seq when no rules match."
  [rules request]
  (->> rules
       (filter (fn [rule]
                 (if (contains? rule :when)
                   (eval-condition (:when rule) request)
                   (match-rule-conditions? (:conditions rule) request))))
       (sort-by #(or (:priority %) 0))
       seq))

;; ---------------------------------------------------------------------------
;; Step Actor Resolution
;; ---------------------------------------------------------------------------

(defn resolve-step-actors
  "Given a step definition and a `unit-actors` map {role-str -> user-id},
   returns the set of resolved user IDs for this step.

   Handles two step formats:
     Legacy:        {:step 1 :role :hiring-manager}
     Multi-approver {:step 1 :approvers [{:type :role :value :dept-head}
                                         {:type :user :value 42}]
                              :quorum :any}"
  [step unit-actors]
  (cond
    (seq (:approvers step))
    (->> (:approvers step)
         (keep (fn [{:keys [type value]}]
                 (case type
                   :role (get unit-actors (str value))
                   :user value
                   nil)))
         set)

    (:role step)
    (let [uid (get unit-actors (str (:role step)))]
      (if uid #{uid} #{}))

    :else #{}))

;; ---------------------------------------------------------------------------
;; Per-Step Quorum Evaluation
;; ---------------------------------------------------------------------------

(defn step-quorum-met?
  "Returns true if `approved-user-ids` satisfies the step's quorum rule.
     :any (default) — at least one approver.
     {:min N}       — at least N approvers."
  [step approved-user-ids]
  (let [quorum (or (:quorum step) :any)
        n      (count approved-user-ids)]
    (if (= quorum :any)
      (pos? n)
      (>= n (or (:min quorum) 1)))))

;; ---------------------------------------------------------------------------
;; Auto-Grant Check
;; ---------------------------------------------------------------------------

(defn auto-grant?
  "Returns true if `submitter-id` is among the resolved actors for the first
   step of `chain`, indicating their approval should be auto-recorded."
  [chain submitter-id unit-actors]
  (boolean
    (when (seq chain)
      (contains? (resolve-step-actors (first chain) unit-actors) submitter-id))))

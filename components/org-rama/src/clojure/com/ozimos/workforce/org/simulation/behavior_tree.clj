(ns com.ozimos.workforce.org.simulation.behavior-tree
  "A robust, pure Clojure Behavior Tree engine for autonomous agent simulation.")

(defprotocol BTNode
  (tick [this context] "Executes one tick of the node, returning {:status (:success | :failure | :running) :context updated-context}"))

(defrecord ActionNode [name action-fn]
  BTNode
  (tick [_ context]
    (try
      (let [result (action-fn context)]
        (if (map? result)
          (let [status (get result :status :success)
                updated-ctx (get result :context context)]
            {:status status :context updated-ctx})
          {:status (if result :success :failure) :context context}))
      (catch Exception e
        {:status :failure :context (assoc context :last-error (.getMessage e) :error (.getMessage e))}))))

(defrecord ConditionNode [name condition-fn]
  BTNode
  (tick [_ context]
    (try
      (let [passed? (boolean (condition-fn context))]
        {:status (if passed? :success :failure) :context context})
      (catch Exception e
        {:status :failure :context (assoc context :last-error (.getMessage e) :error (.getMessage e))}))))

(defrecord SequenceNode [name children]
  BTNode
  (tick [_ context]
    (loop [nodes children
           ctx context]
      (if (empty? nodes)
        {:status :success :context ctx}
        (let [node (first nodes)
              {:keys [status context]} (tick node ctx)]
          (case status
            :success (recur (rest nodes) context)
            :failure {:status :failure :context context}
            :running {:status :running :context context}
            {:status :failure :context context}))))))

(defrecord SelectorNode [name children]
  BTNode
  (tick [_ context]
    (loop [nodes children
           ctx context]
      (if (empty? nodes)
        {:status :failure :context ctx}
        (let [node (first nodes)
              {:keys [status context]} (tick node ctx)]
          (case status
            :success {:status :success :context context}
            :running {:status :running :context context}
            :failure (recur (rest nodes) context)
            (recur (rest nodes) context)))))))

(defrecord InverterNode [name child]
  BTNode
  (tick [_ context]
    (let [{:keys [status context]} (tick child context)]
      {:status (case status
                 :success :failure
                 :failure :success
                 status)
       :context context})))

;; Builder helpers
(defn action [name f] (->ActionNode name f))
(defn condition [name f] (->ConditionNode name f))
(defn sequence* [name & children] (->SequenceNode name children))
(defn selector* [name & children] (->SelectorNode name children))
(defn inverter [name child] (->InverterNode name child))

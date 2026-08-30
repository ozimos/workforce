(ns com.ozimos.workforce.frontend.ui.pages.join-org-replicant-test
  (:require
   [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
   [clojure.string :as str]
   [com.ozimos.workforce.frontend.ui.pages.join-org-replicant :as sut]
   [replicant.string :as rs]))

(def mock-invitations
  [{:invitation/id "inv-1" :invitation/org-name "Acme Corp" :invitation/role "member"}
   {:invitation/id "inv-2" :invitation/org-name "Beta LLC" :invitation/role "admin"}])

(defn- base-props [overrides]
  (merge {:invitations mock-invitations
          :loading false
          :error-msg nil
          :accepting nil
          :accepted false}
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
                    on (or (get-in attrs [:on :click]) (get-in attrs [:on :input]))
                    children (if (map? maybe-attrs) more (rest node))]
                (or (when (and on (pred on)) on)
                    (some walk children)))
              (sequential? node) (some walk node)
              :else nil))]
    (walk hiccup)))

(deftest render-states
  (testing "loading shows loading text"
    (let [html (rs/render (sut/JoinOrgReplicant (base-props {:loading true})))]
      (is (str/includes? html "Loading invitations..."))))
  (testing "empty invitations shows fallback"
    (let [html (rs/render (sut/JoinOrgReplicant (base-props {:invitations []})))]
      (is (str/includes? html "You have no pending invitations."))))
  (testing "with invitations shows cards"
    (let [hiccup (sut/JoinOrgReplicant (base-props {}))
          html (rs/render hiccup)]
      (is (str/includes? html "Acme Corp"))
      (is (str/includes? html "Beta LLC"))
      (is (str/includes? html "Accept"))
      (is (true? (valid-hiccup? hiccup)))
      (is (not (str/includes? html "[:div")))))
  (testing "error-msg shows error box"
    (let [html (rs/render (sut/JoinOrgReplicant (base-props {:error-msg "Invalid token"})))]
      (is (str/includes? html "Invalid token")))))

(deftest accept-button-event
  (testing "clicking accept emits pure data event"
    (let [hiccup (sut/JoinOrgReplicant (base-props {}))
          ev (find-event-in-hiccup hiccup #(= (first %) :com.ozimos.workforce.frontend.ui.pages.join-org-replicant/accept-invitation))]
      (is (= [:com.ozimos.workforce.frontend.ui.pages.join-org-replicant/accept-invitation "inv-1"] ev)))))

(deftest pure-state-transitions
  (testing "state transitions work deterministically"
    (let [db (base-props {:loading true})]
      (is (= mock-invitations (:invitations (sut/set-invitations-state db mock-invitations))))
      (is (= "inv-1" (:accepting (sut/set-accepting-state db "inv-1"))))
      (is (= true (:accepted (sut/set-accepted-state db))))
      (is (= "fail" (:error-msg (sut/set-error-msg-state db "fail")))))))

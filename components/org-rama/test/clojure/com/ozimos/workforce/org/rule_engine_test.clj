(ns com.ozimos.workforce.org.rule-engine-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.ozimos.workforce.org.rule-engine :as re]))

;; ---------------------------------------------------------------------------
;; Backward-compatible flat-map condition matching
;; ---------------------------------------------------------------------------

(deftest match-rule-conditions-test
  (testing "Exact match"
    (is (re/match-rule-conditions? {:dept-id "eng" :job-level "L5"}
                                   {:dept-id "eng" :job-level "L5" :location "remote"})))

  (testing "Partial mismatch"
    (is (not (re/match-rule-conditions? {:dept-id "eng" :job-level "L6"}
                                        {:dept-id "eng" :job-level "L5"}))))

  (testing "Set inclusion match"
    (is (re/match-rule-conditions? {:job-level #{"L5" "L6" "L7"}}
                                   {:job-level "L6"}))
    (is (not (re/match-rule-conditions? {:job-level #{"L5" "L6"}}
                                        {:job-level "L4"}))))

  (testing "Empty conditions match anything (wildcard)"
    (is (re/match-rule-conditions? {} {:dept-id "finance" :job-level "L1"}))))

;; ---------------------------------------------------------------------------
;; DSL condition evaluator
;; ---------------------------------------------------------------------------

(deftest eval-condition-test
  (testing ":= operator"
    (is (re/eval-condition {:op := :field :dept-id :value "eng"}
                           {:dept-id "eng"}))
    (is (not (re/eval-condition {:op := :field :dept-id :value "fin"}
                                {:dept-id "eng"}))))

  (testing ":!= operator"
    (is (re/eval-condition {:op :!= :field :employee-type :value :contractor}
                           {:employee-type :full-time})))

  (testing ":in operator"
    (is (re/eval-condition {:op :in :field :job-level :value #{"L5" "L6" "L7"}}
                           {:job-level "L6"}))
    (is (not (re/eval-condition {:op :in :field :job-level :value #{"L5" "L6"}}
                                {:job-level "L4"}))))

  (testing ":and compound"
    (is (re/eval-condition {:op :and
                            :conditions [{:op := :field :dept-id :value "eng"}
                                         {:op := :field :job-level :value "L5"}]}
                           {:dept-id "eng" :job-level "L5"}))
    (is (not (re/eval-condition {:op :and
                                 :conditions [{:op := :field :dept-id :value "eng"}
                                              {:op := :field :job-level :value "L5"}]}
                                {:dept-id "eng" :job-level "L4"}))))

  (testing ":or compound"
    (is (re/eval-condition {:op :or
                            :conditions [{:op := :field :dept-id :value "eng"}
                                         {:op := :field :dept-id :value "finance"}]}
                           {:dept-id "finance"})))

  (testing ":not compound"
    (is (re/eval-condition {:op :not
                            :conditions [{:op := :field :employee-type :value :contractor}]}
                           {:employee-type :full-time}))))

;; ---------------------------------------------------------------------------
;; Approval chain routing (first-match)
;; ---------------------------------------------------------------------------

(deftest eval-approval-chain-test
  (let [default-chain [{:step 1 :role :manager}]
        rules [{:rule-id "r-eng-senior"
                :priority 100
                :conditions {:dept-id "dept-eng" :job-level "L6"}
                :chain [{:step 1 :role :hiring-manager}
                        {:step 2 :role :tech-lead}
                        {:step 3 :role :vp-eng}]}
               {:rule-id "r-eng-general"
                :priority 50
                :conditions {:dept-id "dept-eng"}
                :chain [{:step 1 :role :hiring-manager}
                        {:step 2 :role :eng-director}]}
               {:rule-id "r-finance"
                :priority 50
                :conditions {:dept-id "dept-finance"}
                :chain [{:step 1 :role :finance-director}]}]]

    (testing "Matches highest priority rule (Senior Eng L6)"
      (let [req   {:division-id "div-tech" :dept-id "dept-eng" :job-level "L6" :employee-type :full-time}
            chain (re/eval-approval-chain rules req default-chain)]
        (is (= 3 (count chain)))
        (is (= :vp-eng (:role (nth chain 2))))))

    (testing "Matches general rule when specific conditions don't match (Eng L4)"
      (let [req   {:division-id "div-tech" :dept-id "dept-eng" :job-level "L4" :employee-type :full-time}
            chain (re/eval-approval-chain rules req default-chain)]
        (is (= 2 (count chain)))
        (is (= :eng-director (:role (nth chain 1))))))

    (testing "Matches finance department rule"
      (let [req   {:division-id "div-ops" :dept-id "dept-finance" :job-level "L3"}
            chain (re/eval-approval-chain rules req default-chain)]
        (is (= 1 (count chain)))
        (is (= :finance-director (:role (first chain))))))

    (testing "Falls back to default chain when no rules match"
      (let [req   {:division-id "div-hr" :dept-id "dept-marketing" :job-level "L2"}
            chain (re/eval-approval-chain rules req default-chain)]
        (is (= default-chain chain)))))

  (testing "find-routing-rule with DSL :when conditions"
    (let [rules [{:rule-id "r-contractor"
                  :priority 80
                  :when {:op := :field :employee-type :value :contractor}
                  :chain [{:step 1 :role :finance-director}]}]
          req {:employee-type :contractor :dept-id "eng"}]
      (is (= "r-contractor" (:rule-id (re/find-routing-rule rules req))))
      (is (nil? (re/find-routing-rule rules {:employee-type :full-time}))))))

;; ---------------------------------------------------------------------------
;; Custom tenant rules (all-match)
;; ---------------------------------------------------------------------------

(deftest apply-custom-rules-test
  (let [rules [{:rule-id "contractor-finance"
                :priority 5
                :when {:op := :field :employee-type :value :contractor}
                :then [{:action :inject-step :after 0 :step {:role :finance-dir :quorum :any}}]}
               {:rule-id "eu-legal"
                :priority 10
                :when {:op :and
                       :conditions [{:op := :field :location :value "EU"}
                                    {:op := :field :employee-type :value :contractor}]}
                :then [{:action :inject-step :after 1 :step {:role :legal :quorum :any}}]}
               {:rule-id "high-salary"
                :priority 20
                :when {:op :in :field :salary-band :value #{"Band6" "Band7" "Band8"}}
                :then [{:action :require-quorum :min 2}]}]]

    (testing "No rules match for a standard full-time non-EU hire"
      (is (nil? (re/apply-custom-rules rules {:employee-type :full-time :location "US" :salary-band "Band4"}))))

    (testing "Single rule fires for EU contractor"
      (let [matching (re/apply-custom-rules rules {:employee-type :contractor :location "EU" :salary-band "Band4"})]
        (is (= 2 (count matching)))
        (is (= "contractor-finance" (:rule-id (first matching))))
        (is (= "eu-legal" (:rule-id (second matching))))))

    (testing "High-salary rule fires independently"
      (let [matching (re/apply-custom-rules rules {:employee-type :full-time :location "US" :salary-band "Band7"})]
        (is (= 1 (count matching)))
        (is (= "high-salary" (:rule-id (first matching))))))))

;; ---------------------------------------------------------------------------
;; Step quorum evaluation
;; ---------------------------------------------------------------------------

(deftest step-quorum-met-test
  (testing ":any quorum (default) — 1 approver sufficient"
    (is (re/step-quorum-met? {:step 1 :role :manager} #{42}))
    (is (not (re/step-quorum-met? {:step 1 :role :manager} #{}))))

  (testing ":any quorum explicit"
    (is (re/step-quorum-met? {:step 1 :quorum :any} #{1 2 3})))

  (testing "{:min N} quorum"
    (is (re/step-quorum-met? {:step 1 :quorum {:min 2}} #{1 2}))
    (is (not (re/step-quorum-met? {:step 1 :quorum {:min 2}} #{1})))
    (is (re/step-quorum-met? {:step 1 :quorum {:min 2}} #{1 2 3}))))

;; ---------------------------------------------------------------------------
;; Auto-grant check
;; ---------------------------------------------------------------------------

(deftest auto-grant-test
  (let [chain [{:step 1 :role :hiring-manager}
               {:step 2 :role :department-head}]
        actors {":hiring-manager" 101
                ":department-head" 202}]

    (testing "Submitter is step-1 approver — auto-grant should fire"
      (is (re/auto-grant? chain 101 actors)))

    (testing "Submitter is NOT step-1 approver"
      (is (not (re/auto-grant? chain 999 actors))))

    (testing "Empty chain — no auto-grant"
      (is (not (re/auto-grant? [] 101 actors))))))

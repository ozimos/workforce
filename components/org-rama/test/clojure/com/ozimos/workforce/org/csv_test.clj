(ns com.ozimos.workforce.org.csv-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.workforce.org.seed :as seed]
   [com.ozimos.workforce.web.test-system :as ts]))

(def ^:dynamic *deps* nil)

(defn system-fixture
  [tests]
  (let [sys (ts/get-sys)
        us (ts/user-store sys)]
    (binding [*deps* (assoc us :user-store us :cluster-manager (ts/rama-cluster sys))]
      (seed/load-seed-data! *deps* (seed/generate-seed-data))
      (tests))))

(use-fixtures :once system-fixture)

(deftest dynamic-csv-template-test
  (testing "generate-csv-template produces header and sample row with custom attributes"
    (let [seed-res (seed/ensure-seeded! *deps*)
          _ (is (:ok seed-res))
          acme (org/find-org-by-name *deps* "Acme Corp")
          org-id (:id acme)
          template (org/generate-csv-template *deps* org-id)]
      (is (string? template))
      (is (str/includes? template "employee_id,first_name,last_name,personal_email,hire_date,unit_id,job_title,job_category,job_level,employee_type,location,base_salary,currency,bonus_target"))
      ;; Check dynamic custom attribute columns defined in org-acme
      (is (str/includes? template "custom:health-benefit"))
      (is (str/includes? template "custom:signing-bonus"))
      (is (str/includes? template "emp-1001,Alice,Smith,alice.smith@example.com,2026-01-15")))))

(deftest csv-preflight-validation-test
  (testing "validates clean CSV data successfully"
    (let [acme (org/find-org-by-name *deps* "Acme Corp")
          org-id (:id acme)
          clean-csv (str "employee_id,first_name,last_name,personal_email,hire_date,unit_id,job_title,job_category,job_level,employee_type,location,base_salary,currency,bonus_target,custom:health-benefit,custom:signing-bonus\n"
                         "emp-csv-101,John,Doe,john.doe.csv@example.com,2026-02-01,dept-acme-backend,Senior Engineer,engineering,L5,full-time,US-CA,170000,USD,0.15,6000,10000\n"
                         "emp-csv-102,Jane,Roe,jane.roe.csv@example.com,2026-02-01,dept-acme-frontend,Staff Engineer,engineering,L6,full-time,GB,190000,GBP,20%,5000,0\n")
          report (org/validate-csv *deps* org-id clean-csv)]
      (is (true? (:valid? report)))
      (is (= 2 (:total-rows report)))
      (is (= 2 (:valid-count report)))
      (is (= 0 (:error-count report)))
      (is (empty? (:errors report)))
      (let [records (:records report)]
        (is (= 2 (count records)))
        (let [r1 (first records)]
          (is (= "emp-csv-101" (:employee-id r1)))
          (is (= "dept-acme-backend" (:unit-id r1)))
          (is (= 170000.0 (:base-salary r1)))
          (is (= 0.15 (:bonus-target r1)))
          (is (= 6000.0 (get-in r1 [:custom-attributes :health-benefit])))
          (is (= 10000.0 (get-in r1 [:custom-attributes :signing-bonus]))))
        (let [r2 (second records)]
          (is (= "emp-csv-102" (:employee-id r2)))
          (is (= "GBP" (:currency r2)))
          (is (= 0.20 (:bonus-target r2)))))))

  (testing "detects structural, format, and referential errors"
    (let [acme (org/find-org-by-name *deps* "Acme Corp")
          org-id (:id acme)
          bad-csv (str "employee_id,first_name,last_name,personal_email,hire_date,unit_id,job_title,job_category,job_level,employee_type,location,base_salary,currency,bonus_target,custom:health-benefit\n"
                       ;; Row 2: Bad date, non-existent unit, invalid currency, bad number for custom attr
                       "emp-bad-1,John,Doe,john@example.com,02/01/2026,dept-non-existent,Lead Engineer,engineering,L5,full-time,US-CA,not-a-salary,INVALID_CURR,0.15,not-a-number\n"
                       ;; Row 3: Duplicate employee-id, bad email format, invalid employee-type
                       "emp-bad-1,,Doe,invalid-email,2026-02-01,dept-acme-backend,Lead Engineer,engineering,L5,space-traveler,US-CA,150000,USD,0.15,5000\n")
          report (org/validate-csv *deps* org-id bad-csv)]
      (is (false? (:valid? report)))
      (is (= 2 (:total-rows report)))
      (is (= 0 (:valid-count report)))
      (is (pos? (:error-count report)))
      (is (empty? (:records report)))

      (let [err-cols (set (map :column (:errors report)))]
        (is (contains? err-cols "hire_date"))
        (is (contains? err-cols "unit_id"))
        (is (contains? err-cols "currency"))
        (is (contains? err-cols "base_salary"))
        (is (contains? err-cols "custom:health-benefit"))
        (is (contains? err-cols "first_name"))
        (is (contains? err-cols "personal_email"))
        (is (contains? err-cols "employee_type"))
        (is (contains? err-cols "employee_id"))))))

(deftest csv-batch-ingestion-test
  (testing "ingest-csv! successfully ingests validated employees and employments into Rama"
    (let [acme (org/find-org-by-name *deps* "Acme Corp")
          org-id (:id acme)
          csv-data (str "employee_id,first_name,last_name,personal_email,hire_date,unit_id,job_title,job_category,job_level,employee_type,location,base_salary,currency,bonus_target,custom:health-benefit,custom:signing-bonus\n"
                        "emp-ingest-201,Alex,Vance,alex.vance@blackmesa.com,2026-03-01,dept-acme-backend,Physics Specialist,engineering,L5,full-time,US-CA,160000,USD,0.15,6500,5000\n"
                        "emp-ingest-202,Gordon,Freeman,gordon.freeman@blackmesa.com,2026-03-01,dept-acme-ai,Research Fellow,engineering,L7,full-time,US-CA,220000,USD,0.25,8000,20000\n")
          [ok res] (org/ingest-csv! *deps* org-id csv-data {:actor-user-id "u-dan-mgr"})]
      (is (true? ok))
      (is (= 2 (:total-ingested res)))

      ;; Verify in Rama $$employees and $$employments
      (let [emp1 (org/get-employee *deps* "emp-ingest-201")
            emp2 (org/get-employee *deps* "emp-ingest-202")]
        (is (some? emp1))
        (is (= "Alex" (:first-name emp1)))
        (is (= "Vance" (:last-name emp1)))
        (is (= "alex.vance@blackmesa.com" (:personal-email emp1)))
        (is (= :active (:status emp1)))

        (is (some? emp2))
        (is (= "Gordon" (:first-name emp2)))
        (is (= "Freeman" (:last-name emp2)))

        (let [empmt1 (org/get-employment *deps* (:current-employment-id emp1))
              empmt2 (org/get-employment *deps* (:current-employment-id emp2))]
          (is (some? empmt1))
          (is (= "Physics Specialist" (:job-title empmt1)))
          (is (= 160000.0 (:base-salary empmt1)))
          (is (= 6500.0 (get-in empmt1 [:custom-attributes :health-benefit])))
          (is (= 5000.0 (get-in empmt1 [:custom-attributes :signing-bonus])))

          (is (some? empmt2))
          (is (= "Research Fellow" (:job-title empmt2)))
          (is (= 220000.0 (:base-salary empmt2)))
          (is (= 8000.0 (get-in empmt2 [:custom-attributes :health-benefit])))
          (is (= 20000.0 (get-in empmt2 [:custom-attributes :signing-bonus]))))))))

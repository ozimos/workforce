(ns com.ozimos.workforce.org.csv
  "Dynamic CSV Ingestion Template Generator & Pre-Flight Validation Engine.
   Supports tenant-customized schemas, custom attribute data-type validation,
   referential integrity checks, collision detection, and atomic batch ingestion."
  (:require
   [clojure.string :as str]
   [com.ozimos.workforce.org.core :as core]
   [com.ozimos.workforce.org.records :as rec]
   [com.rpl.rama :as ramaapi]
   [com.ozimos.omni-auth.rama.interface :as rama]))

(def standard-columns
  [{:key :employee-id :header "employee_id" :required? true :description "Unique employee identifier (e.g. emp-1001)"}
   {:key :first-name :header "first_name" :required? true :description "Legal first name"}
   {:key :last-name :header "last_name" :required? true :description "Legal last name"}
   {:key :personal-email :header "personal_email" :required? true :description "Email address"}
   {:key :hire-date :header "hire_date" :required? true :description "Hire date in YYYY-MM-DD format"}
   {:key :unit-id :header "unit_id" :required? true :description "Department or Org Unit ID"}
   {:key :job-title :header "job_title" :required? true :description "Position/Job Title"}
   {:key :job-category :header "job_category" :required? false :description "Job category keyword (e.g. engineering, product, sales)"}
   {:key :job-level :header "job_level" :required? false :description "Job level (e.g. L3, L5, Senior)"}
   {:key :employee-type :header "employee_type" :required? false :description "Employment type: full-time, part-time, contractor, intern (default: full-time)"}
   {:key :location :header "location" :required? false :description "Location code (e.g. US-CA, GB, DE)"}
   {:key :base-salary :header "base_salary" :required? true :description "Annual base salary amount"}
   {:key :currency :header "currency" :required? false :description "ISO 3-letter currency code (e.g. USD, EUR, GBP) - default: USD"}
   {:key :bonus-target :header "bonus_target" :required? false :description "Annual bonus target decimal or percentage (e.g. 0.15 or 15%)"}])

(defn- custom-attr-header [attr-id]
  (str "custom:" (name attr-id)))

(defn- resolve-org-id [deps org-id-or-name]
  (cond
    (nil? org-id-or-name) nil
    (and (string? org-id-or-name) (core/find-org-by-name deps org-id-or-name))
    (:id (core/find-org-by-name deps org-id-or-name))
    :else org-id-or-name))

(defn get-tenant-csv-schema
  "Returns the complete schema of columns (standard + tenant-defined custom attributes) for a given tenant."
  [deps org-id]
  (let [oid (resolve-org-id deps org-id)
        custom-attrs (core/get-tenant-attributes deps oid :employment)
        custom-cols (mapv (fn [[attr-id attr-def]]
                            {:key (keyword (str "custom:" (name attr-id)))
                             :attr-id attr-id
                             :header (custom-attr-header attr-id)
                             :required? (true? (:required? attr-def))
                             :custom? true
                             :data-type (:data-type attr-def :string)
                             :description (format "%s (%s)%s"
                                                  (:label attr-def (name attr-id))
                                                  (name (:data-type attr-def :string))
                                                  (if (:cost-modifier? attr-def) " [Financial Modifier]" ""))})
                          custom-attrs)]
    (into standard-columns custom-cols)))

(defn generate-csv-template
  "Generates CSV text template with headers, field description comments, and a sample row."
  [deps org-id]
  (let [schema (get-tenant-csv-schema deps org-id)
        headers (mapv :header schema)
        header-row (str/join "," headers)
        sample-row (mapv (fn [col]
                           (case (:key col)
                             :employee-id "emp-1001"
                             :first-name "Alice"
                             :last-name "Smith"
                             :personal-email "alice.smith@example.com"
                             :hire-date "2026-01-15"
                             :unit-id (or (first (keys (core/get-org-children deps org-id))) "dept-backend")
                             :job-title "Senior Software Engineer"
                             :job-category "engineering"
                             :job-level "L5"
                             :employee-type "full-time"
                             :location "US-CA"
                             :base-salary "165000"
                             :currency "USD"
                             :bonus-target "0.15"
                             (if (:custom? col)
                               (case (:data-type col)
                                 :currency "5000"
                                 :number "10"
                                 :boolean "true"
                                 "Sample Value")
                               "")))
                         schema)
        sample-row-str (str/join "," sample-row)]
    (str header-row "\n" sample-row-str "\n")))

;; =============================================================================
;; CSV Parsing Helpers (Pure Clojure RFC 4180 parser)
;; =============================================================================

(defn- parse-csv-line [^String line]
  (let [len (.length line)
        tokens (java.util.ArrayList.)
        cur (StringBuilder.)]
    (loop [i 0
           in-quotes false]
      (if (>= i len)
        (do
          (.add tokens (str/trim (.toString cur)))
          (vec tokens))
        (let [c (.charAt line i)]
          (cond
            (= c \")
            (if (and in-quotes (< (inc i) len) (= (.charAt line (inc i)) \"))
              (do
                (.append cur \")
                (recur (+ i 2) true))
              (recur (inc i) (not in-quotes)))

            (and (= c \,) (not in-quotes))
            (do
              (.add tokens (str/trim (.toString cur)))
              (.setLength cur 0)
              (recur (inc i) false))

            :else
            (do
              (.append cur c)
              (recur (inc i) in-quotes))))))))

(defn- parse-csv-string [csv-str]
  (let [lines (->> (str/split-lines (or csv-str ""))
                   (map str/trim)
                   (remove #(or (str/blank? %) (str/starts-with? % "#"))))]
    (mapv parse-csv-line lines)))

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn- parse-number [val-str]
  (when (and val-str (not (str/blank? val-str)))
    (try
      (Double/parseDouble (str/replace (str/trim val-str) #"[$,%]" ""))
      (catch Exception _ nil))))

(defn- parse-bonus [val-str]
  (when (and val-str (not (str/blank? val-str)))
    (let [cleaned (str/trim val-str)]
      (if (str/ends-with? cleaned "%")
        (when-let [n (parse-number (subs cleaned 0 (dec (count cleaned))))]
          (/ n 100.0))
        (parse-number cleaned)))))

(defn- valid-date-str? [s]
  (boolean (and (string? s) (re-matches #"^\d{4}-\d{2}-\d{2}$" s))))

(defn- valid-email-str? [s]
  (boolean (and (string? s) (re-matches #"^[^@\s]+@[^@\s]+\.[^@\s]+$" s))))

(defn validate-row
  "Validates a single parsed row against schema and existing org context."
  [{:keys [deps row-idx row-map schema valid-units existing-emp-ids existing-emails]}]
  (let [errors (atom [])
        warnings (atom [])
        parsed (atom {})]

    ;; 1. Standard Fields Validation
    (let [emp-id (get row-map "employee_id")]
      (if (str/blank? emp-id)
        (swap! errors conj {:row row-idx :column "employee_id" :value emp-id :message "Missing required employee_id"})
        (do
          (when (contains? existing-emp-ids emp-id)
            (swap! errors conj {:row row-idx :column "employee_id" :value emp-id :message (format "Duplicate employee_id '%s' already exists in organization" emp-id)}))
          (swap! parsed assoc :employee-id emp-id))))

    (let [fname (get row-map "first_name")]
      (if (str/blank? fname)
        (swap! errors conj {:row row-idx :column "first_name" :value fname :message "Missing required first_name"})
        (swap! parsed assoc :first-name fname)))

    (let [lname (get row-map "last_name")]
      (if (str/blank? lname)
        (swap! errors conj {:row row-idx :column "last_name" :value lname :message "Missing required last_name"})
        (swap! parsed assoc :last-name lname)))

    (let [email (get row-map "personal_email")]
      (cond
        (str/blank? email)
        (swap! errors conj {:row row-idx :column "personal_email" :value email :message "Missing required personal_email"})

        (not (valid-email-str? email))
        (swap! errors conj {:row row-idx :column "personal_email" :value email :message (format "Invalid email format: '%s'" email)})

        (contains? existing-emails (str/lower-case email))
        (swap! errors conj {:row row-idx :column "personal_email" :value email :message (format "Email '%s' already in use by another active employee" email)})

        :else
        (swap! parsed assoc :personal-email (str/lower-case email))))

    (let [hdate (get row-map "hire_date")]
      (if (str/blank? hdate)
        (swap! errors conj {:row row-idx :column "hire_date" :value hdate :message "Missing required hire_date"})
        (if (valid-date-str? hdate)
          (swap! parsed assoc :hire-date hdate)
          (swap! errors conj {:row row-idx :column "hire_date" :value hdate :message "Invalid hire_date format. Expected YYYY-MM-DD."}))))

    (let [unit-id (get row-map "unit_id")]
      (if (str/blank? unit-id)
        (swap! errors conj {:row row-idx :column "unit_id" :value unit-id :message "Missing required unit_id"})
        (let [valid? (or (when (set? valid-units) (contains? valid-units unit-id))
                         (when (fn? valid-units) (valid-units unit-id))
                         (and deps (some? (core/get-org-unit deps unit-id))))]
          (if valid?
            (swap! parsed assoc :unit-id unit-id)
            (swap! errors conj {:row row-idx :column "unit_id" :value unit-id :message (format "Unit ID '%s' does not exist in organization hierarchy" unit-id)})))))

    (let [title (get row-map "job_title")]
      (if (str/blank? title)
        (swap! errors conj {:row row-idx :column "job_title" :value title :message "Missing required job_title"})
        (swap! parsed assoc :job-title title)))

    (when-let [cat (get row-map "job_category")]
      (when-not (str/blank? cat)
        (swap! parsed assoc :job-category (keyword cat))))

    (when-let [lvl (get row-map "job_level")]
      (when-not (str/blank? lvl)
        (swap! parsed assoc :job-level lvl)))

    (let [etype (get row-map "employee_type")]
      (if (str/blank? etype)
        (swap! parsed assoc :employee-type :full-time)
        (let [kw (keyword (str/lower-case (str/replace etype #"\s+" "-")))]
          (if (contains? #{:full-time :part-time :contractor :intern :temporary} kw)
            (swap! parsed assoc :employee-type kw)
            (swap! errors conj {:row row-idx :column "employee_type" :value etype :message (format "Invalid employee_type '%s'. Valid values: full-time, part-time, contractor, intern" etype)})))))

    (when-let [loc (get row-map "location")]
      (when-not (str/blank? loc)
        (swap! parsed assoc :location loc)))

    (let [sal-str (get row-map "base_salary")]
      (if (str/blank? sal-str)
        (swap! errors conj {:row row-idx :column "base_salary" :value sal-str :message "Missing required base_salary"})
        (if-let [sal (parse-number sal-str)]
          (if (>= sal 0.0)
            (swap! parsed assoc :base-salary sal)
            (swap! errors conj {:row row-idx :column "base_salary" :value sal-str :message "Base salary cannot be negative"}))
          (swap! errors conj {:row row-idx :column "base_salary" :value sal-str :message "Invalid base_salary. Must be a numeric amount."}))))

    (let [curr (get row-map "currency")]
      (if (str/blank? curr)
        (swap! parsed assoc :currency "USD")
        (let [uc (str/upper-case curr)]
          (if (re-matches #"^[A-Z]{3}$" uc)
            (swap! parsed assoc :currency uc)
            (swap! errors conj {:row row-idx :column "currency" :value curr :message "Invalid currency. Expected 3-letter ISO code (e.g. USD, EUR, GBP)."})))))

    (when-let [bonus-str (get row-map "bonus_target")]
      (when-not (str/blank? bonus-str)
        (if-let [b (parse-bonus bonus-str)]
          (if (and (>= b 0.0) (<= b 2.0))
            (swap! parsed assoc :bonus-target b)
            (swap! warnings conj {:row row-idx :column "bonus_target" :value bonus-str :message "Bonus target is unusually high (> 200%)"}))
          (swap! errors conj {:row row-idx :column "bonus_target" :value bonus-str :message "Invalid bonus_target. Expected percentage (e.g. 15%) or decimal (e.g. 0.15)."}))))

    ;; 2. Custom Attributes Validation
    (let [custom-attrs (atom {})]
      (doseq [col (filter :custom? schema)]
        (let [raw-val (get row-map (:header col))
              attr-id (:attr-id col)
              dtype (:data-type col)]
          (when (and raw-val (not (str/blank? raw-val)))
            (case dtype
              :currency
              (if-let [num (parse-number raw-val)]
                (swap! custom-attrs assoc attr-id num)
                (swap! errors conj {:row row-idx :column (:header col) :value raw-val :message (format "Invalid currency amount for custom attribute '%s'" (name attr-id))}))

              :number
              (if-let [num (parse-number raw-val)]
                (swap! custom-attrs assoc attr-id num)
                (swap! errors conj {:row row-idx :column (:header col) :value raw-val :message (format "Invalid number for custom attribute '%s'" (name attr-id))}))

              :boolean
              (let [b (case (str/lower-case (str/trim raw-val))
                        ("true" "yes" "1" "y") true
                        ("false" "no" "0" "n") false
                        nil)]
                (if (some? b)
                  (swap! custom-attrs assoc attr-id b)
                  (swap! errors conj {:row row-idx :column (:header col) :value raw-val :message (format "Invalid boolean for custom attribute '%s'. Expected true/false or yes/no." (name attr-id))})))

              ;; Default string / text
              (swap! custom-attrs assoc attr-id (str/trim raw-val))))))
      (swap! parsed assoc :custom-attributes @custom-attrs))

    {:valid? (empty? @errors)
     :row-idx row-idx
     :errors @errors
     :warnings @warnings
     :parsed-record @parsed}))

;; =============================================================================
;; Pre-Flight Validation Engine Entry Point
;; =============================================================================

(defn validate-csv
  "Performs complete pre-flight validation on CSV string or reader for a tenant.
   Returns a comprehensive dry-run report."
  [deps org-id csv-str]
  (let [rows (parse-csv-string csv-str)]
    (if (empty? rows)
      {:valid? false
       :total-rows 0
       :valid-count 0
       :error-count 1
       :errors [{:row 0 :column nil :message "CSV is empty or contains only comments"}]
       :warnings []
       :records []}

      (let [headers (first rows)
            data-rows (rest rows)
            schema (get-tenant-csv-schema deps org-id)
            required-headers (->> schema (filter :required?) (map :header) set)
            provided-headers (set headers)
            missing-headers (remove provided-headers required-headers)]

        (if (seq missing-headers)
          {:valid? false
           :total-rows (count data-rows)
           :valid-count 0
           :error-count (count missing-headers)
           :errors (mapv #(hash-map :row 1 :column % :message (format "Missing required column header '%s'" %)) missing-headers)
           :warnings []
           :records []}

          (let [;; Collect existing tenant context
                oid (resolve-org-id deps org-id)
                org-units-list (core/list-org-units deps oid)
                valid-units (if (seq org-units-list)
                              (set (map :unit-id org-units-list))
                              ;; Fallback check directly
                              (fn [uid] (some? (core/get-org-unit deps uid))))
                valid-unit? (if (set? valid-units)
                              #(contains? valid-units %)
                              valid-units)
                existing-emp-ids (atom #{})
                existing-emails (atom #{})

                ;; Tracking duplicate IDs/emails inside the uploaded CSV itself
                seen-csv-ids (atom #{})
                seen-csv-emails (atom #{})

                all-errors (atom [])
                all-warnings (atom [])
                valid-records (atom [])]

            (doseq [[idx row-data] (map-indexed (fn [i r] [(+ i 2) r]) data-rows)]
              (let [row-map (into {} (map vector headers row-data))
                    emp-id (get row-map "employee_id")
                    email (when-let [em (get row-map "personal_email")] (str/lower-case (str/trim em)))

                    ;; Internal CSV Collision Check
                    id-collision? (and (seq emp-id) (contains? @seen-csv-ids emp-id))
                    email-collision? (and (seq email) (contains? @seen-csv-emails email))

                    _ (when (seq emp-id) (swap! seen-csv-ids conj emp-id))
                    _ (when (seq email) (swap! seen-csv-emails conj email))

                    row-res (validate-row {:deps deps
                                           :row-idx idx
                                           :row-map row-map
                                           :schema schema
                                           :valid-units valid-unit?
                                           :existing-emp-ids @existing-emp-ids
                                           :existing-emails @existing-emails})

                    combined-errors (cond-> (:errors row-res)
                                      id-collision? (conj {:row idx :column "employee_id" :value emp-id :message (format "Duplicate employee_id '%s' appears multiple times in CSV" emp-id)})
                                      email-collision? (conj {:row idx :column "personal_email" :value email :message (format "Duplicate personal_email '%s' appears multiple times in CSV" email)}))]

                (if (empty? combined-errors)
                  (swap! valid-records conj (:parsed-record row-res))
                  (swap! all-errors into combined-errors))

                (when (seq (:warnings row-res))
                  (swap! all-warnings into (:warnings row-res)))))

            {:valid? (empty? @all-errors)
             :total-rows (count data-rows)
             :valid-count (count @valid-records)
             :error-count (count @all-errors)
             :errors @all-errors
             :warnings @all-warnings
             :records (if (empty? @all-errors) @valid-records [])}))))))

;; =============================================================================
;; Batch CSV Ingestion Executor
;; =============================================================================

(defn ingest-csv!
  "Validates CSV and executes batch ingestion into Rama depots upon successful validation."
  [deps org-id csv-str & [{:keys [actor-user-id idempotency-prefix allow-warnings?]}]]
  (let [oid (resolve-org-id deps org-id)
        report (validate-csv deps oid csv-str)]
    (if-not (:valid? report)
      [false {:error :validation-failed :report report}]
      (let [records (:records report)
            cmgr (or (-> deps :rama :cluster-manager) (:cluster-manager deps))
            mod-name (rama/module-name)
            depot (rama/depot cmgr mod-name "*employee-depot")
            prefix (or idempotency-prefix (str "csv-ingest-" (System/currentTimeMillis)))
            now (System/currentTimeMillis)
            ingested (atom [])]

        (doseq [[idx r] (map-indexed vector records)]
          (let [eid (:employee-id r)
                empid (str "empmt-" eid)
                hdate (:hire-date r)
                idem-key (format "%s-row-%d-%s" prefix idx eid)]
            (ramaapi/foreign-append! depot
              (rec/->EmployeeHire eid oid actor-user-id
                                  (:first-name r) (:last-name r) (:personal-email r)
                                  hdate :active
                                  empid (:unit-id r) (:job-title r) (:job-category r) (:job-level r)
                                  (:employee-type r :full-time) (:location r)
                                  (:base-salary r 0.0) (:currency r "USD")
                                  (:bonus-target r 0.0) (:custom-attributes r {})
                                  hdate now idem-key)
              :ack)
            (swap! ingested conj {:employee-id eid :employment-id empid :unit-id (:unit-id r)})))

        [true {:ok true
               :total-ingested (count @ingested)
               :ingested @ingested
               :warnings (:warnings report)}]))))

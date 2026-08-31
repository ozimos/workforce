(ns com.ozimos.workforce.org.seed
  "Seed data generator and binary Nippy archive manager.
   Supports generating full 10,000-person enterprise organizations with
   tree hierarchies, 80/20 employee vs headcount split, multi-currency,
   and batch depot ingestion."
  (:require
   [clojure.java.io :as io]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.core :as core]
   [com.ozimos.workforce.org.generator :as gen]
   [com.ozimos.workforce.org.records :as rec]
   [com.rpl.rama :as ramaapi]
   [com.ozimos.omni-auth.rama.interface :as rama]
   [taoensso.nippy :as nippy]))

(def default-seed-path ".seed/workforce-seed-data.nippy")

(defn generate-enterprise-org-dataset
  "Constructs a 10,000-person dataset for a given organization."
  [{:keys [org-id org-name owner-user-id seed total-nodes]
    :or {org-id "org-acme" org-name "Acme Corp" owner-user-id "u-alice" seed 42 total-nodes 10000}}]
  (let [generated (gen/generate-10k-workforce-nodes {:org-id org-id :total-nodes total-nodes :seed seed})]
    {:org-id org-id
     :name org-name
     :owner-user-id owner-user-id
     :units (:org-units generated)
     :tree (:tree generated)
     :employees (:employees generated)
     :employments (:employments generated)
     :headcounts (:headcounts generated)
     :load-factor-rules
     [{:rule-id (str org-id "-uk-eng")
       :priority 100
       :name "UK Engineering Burden"
       :conditions {:location ["GB"] :job-category [:engineering]}
       :multiplier 1.20}
      {:rule-id (str org-id "-us-ca-platform")
       :priority 90
       :name "US-CA Platform Burden"
       :conditions {:location ["US-CA"]}
       :multiplier 1.15}
      {:rule-id (str org-id "-apac-sales")
       :priority 80
       :name "APAC Sales Multiplier"
       :conditions {:location ["SG"] :division ["div-sales"]}
       :multiplier 1.10}]
     :custom-attributes
     [{:attribute-id :health-benefit :label "Health Benefit" :data-type :currency :cost-modifier? true :cost-cadence :annual :default-value 5000.0}
      {:attribute-id :signing-bonus :label "Signing Bonus" :data-type :currency :cost-modifier? true :cost-cadence :one-time :default-value 0.0}
      {:attribute-id :performance-rating :label "Performance Rating" :data-type :string :cost-modifier? false :default-value "Meets Expectations"}]}))

(defn generate-seed-data
  "Constructs complete multi-org seed dataset with standard personas + optional 10k enterprise hierarchies."
  ([] (generate-seed-data {:generate-10k? false}))
  ([{:keys [generate-10k?] :or {generate-10k? false}}]
   (let [base-users
         [{:user-id "u-alice" :email "alice@acme.com" :username "alice" :password "P@ssword123" :roles ["ADMIN"]}
          {:user-id "u-bob" :email "bob@globex.com" :username "bob" :password "P@ssword123" :roles ["ADMIN"]}
          {:user-id "u-carol" :email "carol@crossorg.com" :username "carol" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-dan-mgr" :email "dan.mgr@acme.com" :username "dan_mgr" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-eva-lead" :email "eva.lead@acme.com" :username "eva_lead" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-frank-vp" :email "frank.vp@acme.com" :username "frank_vp" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-grace-hr" :email "grace.hr@acme.com" :username "grace_hr" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-ian-eng" :email "ian.eng@acme.com" :username "ian_eng" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-jane-eng" :email "jane.eng@acme.com" :username "jane_eng" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-karen-vp" :email "karen.vp@globex.com" :username "karen_vp" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-leo-mgr" :email "leo.mgr@globex.com" :username "leo_mgr" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-mia-recruiter" :email "mia.recruiter@globex.com" :username "mia_recruiter" :password "P@ssword123" :roles ["MEMBER"]}
          {:user-id "u-noah-eng" :email "noah.eng@globex.com" :username "noah_eng" :password "P@ssword123" :roles ["MEMBER"]}]

         canonical-units-acme
         [{:unit-id "div-acme-eng" :name "Engineering Division" :budget 35 :parent-id nil}
          {:unit-id "dept-acme-backend" :name "Backend Systems Dept" :budget 15 :parent-id "div-acme-eng"}
          {:unit-id "dept-acme-frontend" :name "Frontend & Web Platform Dept" :budget 10 :parent-id "div-acme-eng"}
          {:unit-id "dept-acme-ai" :name "Applied AI & ML Dept" :budget 10 :parent-id "div-acme-eng"}
          {:unit-id "div-acme-prod" :name "Product & Design Division" :budget 15 :parent-id nil}
          {:unit-id "dept-acme-core-prod" :name "Core Platform Product Dept" :budget 8 :parent-id "div-acme-prod"}
          {:unit-id "dept-acme-design" :name "Product Design & UX Dept" :budget 7 :parent-id "div-acme-prod"}]

         canonical-actors-acme
         [{:unit-id "dept-acme-backend" :user-id "u-dan-mgr" :role :hiring-manager}
          {:unit-id "dept-acme-backend" :user-id "u-carol" :role :dept-head}
          {:unit-id "dept-acme-backend" :user-id "u-frank-vp" :role :vp}
          {:unit-id "dept-acme-frontend" :user-id "u-eva-lead" :role :hiring-manager}
          {:unit-id "dept-acme-frontend" :user-id "u-carol" :role :dept-head}
          {:unit-id "dept-acme-frontend" :user-id "u-frank-vp" :role :vp}
          {:unit-id "dept-acme-ai" :user-id "u-dan-mgr" :role :hiring-manager}
          {:unit-id "dept-acme-ai" :user-id "u-carol" :role :dept-head}
          {:unit-id "dept-acme-ai" :user-id "u-frank-vp" :role :vp}
          {:unit-id "dept-acme-design" :user-id "u-dan-mgr" :role :hiring-manager}
          {:unit-id "dept-acme-design" :user-id "u-carol" :role :dept-head}]

         canonical-reqs-acme
         [{:request-id "hc-acme-backend-1"
           :unit-id "dept-acme-backend"
           :title "Senior Distributed Systems Engineer"
           :job-level "L5"
           :salary-band "$160k - $190k"
           :bonus-target "15%"
           :justification "Core Rama scaling"
           :requester-id "u-dan-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
           :approvals [{:step 1 :approver-user-id "u-dan-mgr"} {:step 2 :approver-user-id "u-carol"}]
           :hire {:candidate-user-id "u-ian-eng"}
           :status :filled
           :final-status :filled}
          {:request-id "hc-acme-frontend-1"
           :unit-id "dept-acme-frontend"
           :title "Staff Frontend Architect"
           :job-level "L6"
           :salary-band "$190k - $230k"
           :bonus-target "20%"
           :justification "Fulcro & design system revamp"
           :requester-id "u-eva-lead"
           :chain-snapshot [{:step 1 :role :dept-head} {:step 2 :role :vp}]
           :approvals [{:step 1 :approver-user-id "u-carol"}]
           :status :in-approval
           :final-status :in-approval}
          {:request-id "hc-acme-ai-1"
           :unit-id "dept-acme-ai"
           :title "AI Research Scientist"
           :job-level "L5"
           :salary-band "$170k - $210k"
           :bonus-target "15%"
           :justification "LLM & Agentic orchestration engine"
           :requester-id "u-dan-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
           :approvals []
           :status :in-approval
           :final-status :in-approval}
          {:request-id "hc-acme-design-1"
           :unit-id "dept-acme-design"
           :title "Lead Product Designer"
           :job-level "L5"
           :salary-band "$150k - $180k"
           :bonus-target "15%"
           :justification "Enterprise workspace UX"
           :requester-id "u-dan-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
           :approvals [{:step 1 :approver-user-id "u-dan-mgr"} {:step 2 :approver-user-id "u-carol"}]
           :status :approved
           :final-status :approved}
          {:request-id "hc-acme-backend-2"
           :unit-id "dept-acme-backend"
           :title "Junior DevOps Engineer"
           :job-level "L3"
           :salary-band "$80k - $100k"
           :bonus-target "10%"
           :justification "CI/CD maintenance"
           :requester-id "u-dan-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
           :rejection {:rejecter-user-id "u-frank-vp" :reason "Budget reallocated to AI initiatives"}
           :status :rejected
           :final-status :rejected}
          {:request-id "hc-acme-prod-1"
           :unit-id "dept-acme-core-prod"
           :title "Associate Product Manager"
           :job-level "L4"
           :salary-band "$140k - $165k"
           :bonus-target "12%"
           :justification "Growth initiatives"
           :requester-id "u-eva-lead"
           :chain-snapshot [{:step 1 :role :dept-head}]
           :status :draft
           :final-status :draft}]

         canonical-reqs-globex
         [{:request-id "hc-globex-sre-1"
           :unit-id "dept-globex-sre"
           :title "Lead Site Reliability Engineer"
           :job-level "L5"
           :salary-band "$155k - $185k"
           :bonus-target "15%"
           :justification "Kubernetes cluster resilience"
           :requester-id "u-leo-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager}]
           :approvals [{:step 1 :approver-user-id "u-leo-mgr"}]
           :hire {:candidate-user-id "u-noah-eng"}
           :status :filled
           :final-status :filled}
          {:request-id "hc-globex-sec-1"
           :unit-id "dept-globex-sec"
           :title "Cloud Security Architect"
           :job-level "L6"
           :salary-band "$195k - $240k"
           :bonus-target "20%"
           :justification "Zero-trust network architecture"
           :requester-id "u-leo-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :vp}]
           :approvals [{:step 1 :approver-user-id "u-leo-mgr"}]
           :status :in-approval
           :final-status :in-approval}
          {:request-id "hc-globex-sales-1"
           :unit-id "dept-globex-sales"
           :title "Enterprise Sales Director"
           :job-level "L6"
           :salary-band "$180k - $220k"
           :bonus-target "25%"
           :justification "Enterprise account expansion"
           :requester-id "u-leo-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :vp}]
           :approvals [{:step 1 :approver-user-id "u-leo-mgr"} {:step 2 :approver-user-id "u-karen-vp"}]
           :status :approved
           :final-status :approved}
          {:request-id "hc-globex-sec-2"
           :unit-id "dept-globex-sec"
           :title "Junior Security Analyst"
           :job-level "L3"
           :salary-band "$85k - $110k"
           :bonus-target "10%"
           :justification "SOC alerts triaging"
           :requester-id "u-leo-mgr"
           :chain-snapshot [{:step 1 :role :hiring-manager}]
           :field-edits [{:editor-user-id "u-leo-mgr" :field-name :salary-band :new-value "$90k - $115k"}]
           :status :draft
           :final-status :draft}]

         canonical-units-globex
         [{:unit-id "div-globex-eng" :name "Engineering Division" :budget 25 :parent-id nil}
          {:unit-id "dept-globex-platform" :name "Platform Infrastructure Dept" :budget 12 :parent-id "div-globex-eng"}
          {:unit-id "dept-globex-sre" :name "Site Reliability Dept" :budget 6 :parent-id "div-globex-eng"}
          {:unit-id "dept-globex-sec" :name "Security Engineering Dept" :budget 7 :parent-id "div-globex-eng"}
          {:unit-id "div-globex-biz" :name "Commercial & Growth Division" :budget 15 :parent-id nil}
          {:unit-id "dept-globex-sales" :name "Enterprise Sales Dept" :budget 10 :parent-id "div-globex-biz"}
          {:unit-id "dept-globex-mktg" :name "Product Marketing Dept" :budget 5 :parent-id "div-globex-biz"}]

         canonical-actors-globex
         [{:unit-id "dept-globex-platform" :user-id "u-leo-mgr" :role :hiring-manager}
          {:unit-id "dept-globex-platform" :user-id "u-karen-vp" :role :vp}
          {:unit-id "dept-globex-sre" :user-id "u-leo-mgr" :role :hiring-manager}
          {:unit-id "dept-globex-sre" :user-id "u-karen-vp" :role :vp}
          {:unit-id "dept-globex-sec" :user-id "u-leo-mgr" :role :hiring-manager}
          {:unit-id "dept-globex-sec" :user-id "u-karen-vp" :role :vp}
          {:unit-id "dept-globex-sales" :user-id "u-leo-mgr" :role :hiring-manager}
          {:unit-id "dept-globex-sales" :user-id "u-karen-vp" :role :vp}]

         acme-10k (if generate-10k?
                    (generate-enterprise-org-dataset {:org-id "org-acme" :org-name "Acme Corp" :owner-user-id "u-alice" :seed 42 :total-nodes 10000})
                    nil)
         globex-10k (if generate-10k?
                      (generate-enterprise-org-dataset {:org-id "org-globex" :org-name "Globex Innovations" :owner-user-id "u-bob" :seed 99 :total-nodes 10000})
                      nil)]

     {:version 1
      :generated-at (System/currentTimeMillis)
      :users base-users
      :organizations
      [(merge
        {:org-id "org-acme"
         :name "Acme Corp"
         :owner-user-id "u-alice"
         :members
         [{:email "carol@crossorg.com" :user-id "u-carol" :role "MEMBER"}
          {:email "dan.mgr@acme.com" :user-id "u-dan-mgr" :role "MEMBER"}
          {:email "eva.lead@acme.com" :user-id "u-eva-lead" :role "MEMBER"}
          {:email "frank.vp@acme.com" :user-id "u-frank-vp" :role "MEMBER"}
          {:email "grace.hr@acme.com" :user-id "u-grace-hr" :role "MEMBER"}
          {:email "ian.eng@acme.com" :user-id "u-ian-eng" :role "MEMBER"}
          {:email "jane.eng@acme.com" :user-id "u-jane-eng" :role "MEMBER"}]
         :units (into canonical-units-acme (or (:units acme-10k) []))
         :actors canonical-actors-acme
         :approval-rules
         [{:rule-id "r-acme-exec" :priority 100 :name "Executive L6+ Rule" :conditions [:= :job-level "L6"] :chain [{:step 1 :role :dept-head} {:step 2 :role :vp}]}
          {:rule-id "r-acme-standard" :priority 50 :name "Standard IC Rule" :conditions [:= :job-level "L5"] :chain [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]}]
         :role-permissions
         {:admin {:can-create-requisition true :can-approve true :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
          :hr {:can-create-requisition true :can-approve false :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
          :dept-head {:can-create-requisition true :can-approve true :view-scope :view-tree :visible-fields #{:salary-band :bonus-target}}
          :hiring-manager {:can-create-requisition true :can-approve true :view-scope :view-own :visible-fields #{:salary-band}}
          :employee {:can-create-requisition false :can-approve false :view-scope :view-own :visible-fields #{}}}
         :custom-attributes
         [{:attribute-id :health-benefit :label "Health Benefit" :data-type :currency :cost-modifier? true :cost-cadence :annual :default-value 5000.0}
          {:attribute-id :signing-bonus :label "Signing Bonus" :data-type :currency :cost-modifier? true :cost-cadence :one-time :default-value 0.0}
          {:attribute-id :performance-rating :label "Performance Rating" :data-type :string :cost-modifier? false :default-value "Meets Expectations"}]
         :load-factor-rules
         [{:rule-id "acme-uk-eng" :priority 100 :name "UK Engineering Burden" :conditions {:location ["GB"] :job-category [:engineering]} :multiplier 1.20}
          {:rule-id "acme-us-ca-platform" :priority 90 :name "US-CA Platform Burden" :conditions {:location ["US-CA"]} :multiplier 1.15}]
         :requisitions canonical-reqs-acme
         :employees (into [{:employee-id "emp-acme-ian" :first-name "Ian" :last-name "Engineer" :personal-email "ian.eng@acme.com" :hire-date "2026-01-15"}
                           {:employee-id "emp-acme-jane" :first-name "Jane" :last-name "Engineer" :personal-email "jane.eng@acme.com" :hire-date "2026-01-15"}]
                          (or (:employees acme-10k) []))
         :employments (into [{:employment-id "empmt-acme-ian" :employee-id "emp-acme-ian" :unit-id "dept-acme-backend" :job-title "Senior Systems Engineer" :job-category :engineering :job-level "L5" :employee-type :full-time :location "US-CA" :base-salary 165000.0 :currency "USD" :bonus-target 0.15 :custom-attributes {:health-benefit 6000.0 :signing-bonus 10000.0}}
                             {:employment-id "empmt-acme-jane" :employee-id "emp-acme-jane" :unit-id "dept-acme-frontend" :job-title "Frontend Engineer" :job-category :engineering :job-level "L4" :employee-type :full-time :location "US-CA" :base-salary 145000.0 :currency "USD" :bonus-target 0.12 :custom-attributes {:health-benefit 5000.0 :signing-bonus 0.0}}]
                            (or (:employments acme-10k) []))}
        (dissoc acme-10k :requisitions :employees :employments))

       (merge
        {:org-id "org-globex"
         :name "Globex Innovations"
         :owner-user-id "u-bob"
         :members
         [{:email "carol@crossorg.com" :user-id "u-carol" :role "MEMBER"}
          {:email "karen.vp@globex.com" :user-id "u-karen-vp" :role "MEMBER"}
          {:email "leo.mgr@globex.com" :user-id "u-leo-mgr" :role "MEMBER"}
          {:email "mia.recruiter@globex.com" :user-id "u-mia-recruiter" :role "MEMBER"}
          {:email "noah.eng@globex.com" :user-id "u-noah-eng" :role "MEMBER"}]
         :units (into canonical-units-globex (or (:units globex-10k) []))
         :actors canonical-actors-globex
         :approval-rules
         [{:rule-id "r-globex-exec" :priority 100 :name "Executive L6 Rule" :conditions [:= :job-level "L6"] :chain [{:step 1 :role :hiring-manager} {:step 2 :role :vp}]}
          {:rule-id "r-globex-standard" :priority 50 :name "Standard Rule" :conditions [:= :job-level "L5"] :chain [{:step 1 :role :hiring-manager}]}]
         :role-permissions
         {:admin {:can-create-requisition true :can-approve true :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
          :hr {:can-create-requisition true :can-approve false :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
          :dept-head {:can-create-requisition true :can-approve true :view-scope :view-tree :visible-fields #{:salary-band :bonus-target}}
          :hiring-manager {:can-create-requisition true :can-approve true :view-scope :view-own :visible-fields #{:salary-band}}
          :employee {:can-create-requisition false :can-approve false :view-scope :view-own :visible-fields #{}}}
         :custom-attributes
         [{:attribute-id :health-benefit :label "Health Benefit" :data-type :currency :cost-modifier? true :cost-cadence :annual :default-value 5000.0}
          {:attribute-id :signing-bonus :label "Signing Bonus" :data-type :currency :cost-modifier? true :cost-cadence :one-time :default-value 0.0}
          {:attribute-id :performance-rating :label "Performance Rating" :data-type :string :cost-modifier? false :default-value "Meets Expectations"}]
         :requisitions canonical-reqs-globex
         :employees (or (:employees globex-10k) [])
         :employments (or (:employments globex-10k) [])}
        (dissoc globex-10k :requisitions :employees :employments))]})))

(defn write-seed-nippy!
  "Generates the seed dataset and serializes it to a binary Nippy archive with Snappy compression."
  ([]
   (write-seed-nippy! default-seed-path))
  ([path]
   (let [file (io/file path)
         parent (.getParentFile file)]
     (when (and parent (not (.exists parent)))
       (.mkdirs parent))
     (let [data (generate-seed-data {:generate-10k? true})]
       (nippy/freeze-to-file path data {:compressor nippy/snappy-compressor})
       {:ok true :path path :size (.length (io/file path)) :organizations (count (:organizations data))}))))

(defn read-seed-nippy
  "Deserializes seed data from a Nippy archive."
  ([]
   (read-seed-nippy default-seed-path))
  ([path]
   (let [file (io/file path)]
     (if (.exists file)
       (nippy/thaw-from-file path)
       (throw (ex-info (str "Seed file does not exist at " path) {:path path}))))))

(defn- get-cmgr [deps]
  (or (-> deps :rama :cluster-manager)
      (:cluster-manager deps)
      (throw (ex-info "Could not resolve Rama cluster manager from deps" {:deps-keys (keys deps)}))))

(defn load-seed-data!
  "Fast batch-appends seed data into Rama depots and omni-auth user stores."
  [deps dataset]
  (let [cmgr (get-cmgr deps)
        mod-name (rama/module-name)
        user-map (atom {})
        org-map (atom {})
        now (System/currentTimeMillis)

        ;; Rama Depots
        unit-depot (rama/depot cmgr mod-name "*org-unit-depot")
        actor-depot (rama/depot cmgr mod-name "*actor-depot")
        policy-depot (rama/depot cmgr mod-name "*policy-depot")
        load-factor-depot (rama/depot cmgr mod-name "*load-factor-depot")
        tenant-attr-depot (rama/depot cmgr mod-name "*tenant-attr-depot")
        employee-depot (rama/depot cmgr mod-name "*employee-depot")
        headcount-depot (rama/depot cmgr mod-name "*headcount-depot")]

    ;; 1. Register base personas in omni-auth
    (doseq [u (:users dataset)]
      (let [existing (user/find-by-email deps (:email u))
            user-rec (if existing
                       existing
                       (let [[ok created] (user/register! deps {:email (:email u)
                                                                :username (:username u)
                                                                :password (:password u)
                                                                :roles (or (:roles u) ["MEMBER"])})]
                         (when ok
                           (user/verify! deps (:id created))
                           created)))]
        (swap! user-map assoc (:user-id u) (:id user-rec))))

    ;; 2. Ingest Organizations
    (doseq [org (:organizations dataset)]
      (let [owner-resolved-id (get @user-map (:owner-user-id org))
            existing-org (core/find-org-by-name deps (:name org))
            org-id (if existing-org
                     (:id existing-org)
                     (let [[ok created] (core/create-org! deps {:name (:name org)
                                                                :owner-user-id owner-resolved-id})]
                       (if ok (:id created) (:org-id org))))]
        (swap! org-map assoc (:org-id org) org-id)

        ;; Add members
        (doseq [m (:members org)]
          (let [resolved-m-id (get @user-map (:user-id m))]
            (when (and resolved-m-id (not= resolved-m-id owner-resolved-id))
              (let [existing-members (core/list-members deps org-id)
                    already-member? (some #(= (:user-id %) resolved-m-id) existing-members)]
                (when-not already-member?
                  (let [[ok inv] (core/invite-to-org! deps {:org-id org-id
                                                            :email (:email m)
                                                            :role (:role m)
                                                            :invited-by owner-resolved-id})]
                    (when (and ok (:invitation-id inv))
                      (core/join-org! deps {:user-id resolved-m-id
                                            :invitation-id (:invitation-id inv)}))))))))

        ;; 3. Org Units
        (doseq [u (:units org)]
          (ramaapi/foreign-append! unit-depot
            (rec/->OrgUnitCreate (:unit-id u) org-id (:division-id u) (:dept-id u) (:name u) (:parent-id u) (or (:budget u) 0) now)
            :ack))

        ;; 4. Actors & Policies
        (doseq [a (:actors org)]
          (when-let [resolved-u-id (get @user-map (:user-id a))]
            (ramaapi/foreign-append! actor-depot
              (rec/->OrgActorAssign org-id (:unit-id a) resolved-u-id (name (:role a)) now)
              :ack)))

        (when-let [rules (:approval-rules org)]
          (ramaapi/foreign-append! policy-depot (rec/->ApprovalRuleSet org-id rules now) :ack))
        (when-let [perms (:role-permissions org)]
          (doseq [[role role-perms] perms]
            (ramaapi/foreign-append! policy-depot (rec/->RolePermissionSet org-id (name role) role-perms now) :ack)))

        ;; 5. Custom Attributes & Load Factors
        (doseq [attr (:custom-attributes org)]
          (ramaapi/foreign-append! tenant-attr-depot
            (rec/->TenantAttributeDefine org-id (:attribute-id attr) :employment (:label attr) (:data-type attr)
                                         (:cost-modifier? attr) (:cost-cadence attr) "USD" nil false (:default-value attr) now)
            :ack))

        (doseq [lf (:load-factor-rules org)]
          (let [cat (first (get-in lf [:conditions :job-category] [:engineering]))
                loc (first (get-in lf [:conditions :location] ["*"]))]
            (ramaapi/foreign-append! load-factor-depot
              (rec/->LoadFactorRuleSet org-id loc (name cat) "*" (:multiplier lf) now)
              :ack)))

        ;; 6. Employees & Employments Batch Appends
        (let [emp-map (into {} (map (fn [empmt] [(:employee-id empmt) empmt])) (:employments org))]
          (doseq [e (:employees org)]
            (let [empmt (get emp-map (:employee-id e))]
              (ramaapi/foreign-append! employee-depot
                (rec/->EmployeeHire (:employee-id e) org-id owner-resolved-id
                                    (:first-name e) (:last-name e) (:personal-email e)
                                    (:hire-date e) :active
                                    (:employment-id empmt) (:unit-id empmt)
                                    (:job-title empmt) (:job-category empmt) (:job-level empmt)
                                    (:employee-type empmt) (:location empmt)
                                    (:base-salary empmt) (:currency empmt)
                                    (:bonus-target empmt) (:custom-attributes empmt)
                                    (:hire-date e) now (str "seed-" (:employee-id e)))
                :ack))))

        ;; 7. Headcount Requisitions Lifecycle & Appends
        (doseq [req (:requisitions org)]
          (let [requester-resolved-id (get @user-map (:requester-id req) owner-resolved-id)
                create-input (assoc req
                                    :org-id org-id
                                    :requester-id requester-resolved-id
                                    :chain-snapshot (or (:chain-snapshot req) [])
                                    :idempotency-key (str "seed-create-" (:request-id req)))
                [ok created-req] (core/create-headcount-request! deps create-input)
                req-id (if ok (:request-id created-req) (:request-id req))]

            ;; Perform step approvals
            (doseq [app (:approvals req)]
              (let [approver-resolved-id (get @user-map (:approver-user-id app))]
                (core/approve-headcount-step! deps {:org-id org-id
                                                    :request-id req-id
                                                    :approver-user-id approver-resolved-id
                                                    :idempotency-key (str "seed-app-" req-id "-" (:step app))})))

            ;; Rejection if applicable
            (when-let [rej (:rejection req)]
              (let [rejecter-resolved-id (get @user-map (:rejecter-user-id rej))]
                (core/reject-headcount-request! deps {:org-id org-id
                                                      :request-id req-id
                                                      :rejecter-user-id rejecter-resolved-id
                                                      :reason (:reason rej)
                                                      :idempotency-key (str "seed-rej-" req-id)})))

            ;; Sensitive field edits if applicable
            (doseq [ed (:field-edits req)]
              (let [editor-resolved-id (get @user-map (:editor-user-id ed))]
                (core/edit-headcount-field! deps {:org-id org-id
                                                  :request-id req-id
                                                  :editor-user-id editor-resolved-id
                                                  :field-name (:field-name ed)
                                                  :new-value (:new-value ed)
                                                  :idempotency-key (str "seed-edit-" req-id "-" (name (:field-name ed)))})))

            ;; Hire transition if applicable
            (when-let [hire (:hire req)]
              (let [cand-resolved-id (get @user-map (:candidate-user-id hire))]
                (core/transition-headcount-to-hire! deps {:org-id org-id
                                                          :request-id req-id
                                                          :hired-user-id cand-resolved-id
                                                          :idempotency-key (str "seed-hire-" req-id)})))))

        ;; 8. Additional Generated Headcounts (if any)
        (doseq [hc (:headcounts org)]
          (ramaapi/foreign-append! headcount-depot
            (rec/->HeadcountCreate (:request-id hc) org-id (:unit-id hc) (:division-id hc) nil
                                   (:location hc) (:job-level hc) (:employee-type hc)
                                   owner-resolved-id (:title hc) "10k Seed Headcount"
                                   "Requisition Description" (:salary-band hc) (:bonus-target hc)
                                   (:status hc) 1 [] now (str "seed-" (:request-id hc)))
            :ack))))

    {:ok true
     :users-seeded (count (:users dataset))
     :organizations-seeded (count (:organizations dataset))}))

(defn ensure-seeded!
  "Checks if the seed file exists on disk (generates it if missing),
   and loads the dataset into Rama if the seed organizations are not already present."
  ([deps]
   (ensure-seeded! deps default-seed-path))
  ([deps path]
   (let [seed-file (io/file path)]
     (when-not (.exists seed-file)
       (println (str "Seed file not found at " path ". Generating fresh binary archive..."))
       (write-seed-nippy! path))
     (let [data (read-seed-nippy path)
           first-org-name (-> data :organizations first :name)
           existing-org (core/find-org-by-name deps first-org-name)]
       (if existing-org
         (do
           (println (str "Rama cluster is already seeded (found " first-org-name "). Skipping seed."))
           {:ok true :status :already-seeded})
         (do
           (println (str "Seeding Rama cluster from " path "..."))
           (let [res (load-seed-data! deps data)]
             (println (str "Successfully seeded " (:organizations-seeded res) " organizations and " (:users-seeded res) " users."))
             res)))))))

(ns com.ozimos.workforce.org.seed
  (:require
   [clojure.java.io :as io]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.core :as core]
   [taoensso.nippy :as nippy]))

(def default-seed-path ".seed/workforce-seed-data.nippy")

(defn generate-seed-data
  "Constructs a rich, deterministic seed dataset covering:
   - 2 Organizations (Acme Corp & Globex Innovations)
   - 13 User Personas (Admins, Managers, VPs, Recruiters, and Cross-Org shared user Carol)
   - Full Unit Hierarchies (Divisions, Departments with Budgets)
   - Scoped Actor Role Assignments
   - Custom Approval Routing Rules & Role Permission Matrices
   - Headcount Requisitions across all lifecycle states (:draft, :in-approval, :approved, :filled, :rejected)"
  []
  {:version 1
   :generated-at (System/currentTimeMillis)
   :users
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

   :organizations
   [{:org-id "org-acme"
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

     :units
     [{:unit-id "div-acme-eng" :name "Engineering Division" :budget 35 :parent-id nil}
      {:unit-id "dept-acme-backend" :name "Backend Systems Dept" :budget 15 :parent-id "div-acme-eng"}
      {:unit-id "dept-acme-frontend" :name "Frontend & Web Platform Dept" :budget 10 :parent-id "div-acme-eng"}
      {:unit-id "dept-acme-ai" :name "Applied AI & ML Dept" :budget 10 :parent-id "div-acme-eng"}
      {:unit-id "div-acme-prod" :name "Product & Design Division" :budget 15 :parent-id nil}
      {:unit-id "dept-acme-core-prod" :name "Core Platform Product Dept" :budget 8 :parent-id "div-acme-prod"}
      {:unit-id "dept-acme-design" :name "Product Design & UX Dept" :budget 7 :parent-id "div-acme-prod"}]

     :actors
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

     :approval-rules
     [{:rule-id "r-acme-exec"
       :priority 100
       :name "Executive L6+ Rule"
       :conditions [:= :job-level "L6"]
       :chain [{:step 1 :role :dept-head} {:step 2 :role :vp}]}
      {:rule-id "r-acme-standard"
       :priority 50
       :name "Standard IC Rule"
       :conditions [:= :job-level "L5"]
       :chain [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]}]

     :role-permissions
     {:admin {:can-create-requisition true :can-approve true :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
      :hr {:can-create-requisition true :can-approve false :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
      :dept-head {:can-create-requisition true :can-approve true :view-scope :view-tree :visible-fields #{:salary-band :bonus-target}}
      :hiring-manager {:can-create-requisition true :can-approve true :view-scope :view-own :visible-fields #{:salary-band}}
      :employee {:can-create-requisition false :can-approve false :view-scope :view-own :visible-fields #{}}}

     :requisitions
     [;; 1. Filled requisition
      {:request-id "hc-acme-backend-1"
       :unit-id "dept-acme-backend"
       :title "Senior Distributed Systems Engineer"
       :job-level "L5"
       :salary-band "$160k - $190k"
       :bonus-target "15%"
       :justification "Core Rama scaling"
       :requester-id "u-dan-mgr"
       :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
       :approvals [{:step 1 :approver-user-id "u-dan-mgr"}
                   {:step 2 :approver-user-id "u-carol"}]
       :hire {:candidate-user-id "u-ian-eng"}
       :final-status :filled}

      ;; 2. In-approval (Step 2)
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
       :final-status :in-approval}

      ;; 3. In-approval (Step 1)
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
       :final-status :in-approval}

      ;; 4. Approved
      {:request-id "hc-acme-design-1"
       :unit-id "dept-acme-design"
       :title "Lead Product Designer"
       :job-level "L5"
       :salary-band "$150k - $180k"
       :bonus-target "15%"
       :justification "Enterprise workspace UX"
       :requester-id "u-dan-mgr"
       :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
       :approvals [{:step 1 :approver-user-id "u-dan-mgr"}
                   {:step 2 :approver-user-id "u-carol"}]
       :final-status :approved}

      ;; 5. Rejected
      {:request-id "hc-acme-backend-2"
       :unit-id "dept-acme-backend"
       :title "Junior DevOps Engineer"
       :job-level "L3"
       :salary-band "$90k - $115k"
       :bonus-target "10%"
       :justification "Infrastructure maintenance"
       :requester-id "u-dan-mgr"
       :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :dept-head}]
       :rejection {:rejecter-user-id "u-dan-mgr" :reason "Budget reallocated to AI initiatives"}
       :final-status :rejected}

      ;; 6. Draft (reset via sensitive field edit)
      {:request-id "hc-acme-prod-1"
       :unit-id "dept-acme-core-prod"
       :title "Growth Product Manager"
       :job-level "L4"
       :salary-band "$130k - $155k"
       :bonus-target "12%"
       :justification "Self-serve onboarding flow"
       :requester-id "u-dan-mgr"
       :chain-snapshot [{:step 1 :role :hiring-manager}]
       :field-edits [{:editor-user-id "u-dan-mgr" :field-name :salary-band :new-value "$140k - $165k"}]
       :final-status :draft}]}

    {:org-id "org-globex"
     :name "Globex Innovations"
     :owner-user-id "u-bob"
     :members
     [{:email "carol@crossorg.com" :user-id "u-carol" :role "MEMBER"}
      {:email "karen.vp@globex.com" :user-id "u-karen-vp" :role "MEMBER"}
      {:email "leo.mgr@globex.com" :user-id "u-leo-mgr" :role "MEMBER"}
      {:email "mia.recruiter@globex.com" :user-id "u-mia-recruiter" :role "MEMBER"}
      {:email "noah.eng@globex.com" :user-id "u-noah-eng" :role "MEMBER"}]

     :units
     [{:unit-id "div-globex-infra" :name "Cloud Infrastructure Division" :budget 20 :parent-id nil}
      {:unit-id "dept-globex-sre" :name "Site Reliability Engineering Dept" :budget 12 :parent-id "div-globex-infra"}
      {:unit-id "dept-globex-sec" :name "Cloud Security & Compliance Dept" :budget 8 :parent-id "div-globex-infra"}
      {:unit-id "div-globex-growth" :name "Growth & Sales Division" :budget 10 :parent-id nil}
      {:unit-id "dept-globex-mkt" :name "Performance Marketing Dept" :budget 5 :parent-id "div-globex-growth"}
      {:unit-id "dept-globex-sales" :name "Enterprise Solutions Dept" :budget 5 :parent-id "div-globex-growth"}]

     :actors
     [{:unit-id "dept-globex-sre" :user-id "u-leo-mgr" :role :hiring-manager}
      {:unit-id "dept-globex-sre" :user-id "u-karen-vp" :role :vp}
      {:unit-id "dept-globex-sec" :user-id "u-leo-mgr" :role :hiring-manager}
      {:unit-id "dept-globex-sec" :user-id "u-karen-vp" :role :vp}
      {:unit-id "dept-globex-mkt" :user-id "u-carol" :role :vp}]

     :approval-rules
     [{:rule-id "r-globex-exec"
       :priority 100
       :name "Executive L6 Rule"
       :conditions [:= :job-level "L6"]
       :chain [{:step 1 :role :hiring-manager} {:step 2 :role :vp}]}
      {:rule-id "r-globex-standard"
       :priority 50
       :name "Standard Rule"
       :conditions [:= :job-level "L5"]
       :chain [{:step 1 :role :hiring-manager}]}]

     :role-permissions
     {:admin {:can-create-requisition true :can-approve true :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
      :hr {:can-create-requisition true :can-approve false :view-scope :view-all :visible-fields #{:salary-band :bonus-target :rsu-grant}}
      :dept-head {:can-create-requisition true :can-approve true :view-scope :view-tree :visible-fields #{:salary-band :bonus-target}}
      :hiring-manager {:can-create-requisition true :can-approve true :view-scope :view-own :visible-fields #{:salary-band}}
      :employee {:can-create-requisition false :can-approve false :view-scope :view-own :visible-fields #{}}}

     :requisitions
     [;; 1. Filled
      {:request-id "hc-globex-sre-1"
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
       :final-status :filled}

      ;; 2. In-approval (Step 2)
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
       :final-status :in-approval}

      ;; 3. Approved
      {:request-id "hc-globex-sales-1"
       :unit-id "dept-globex-sales"
       :title "Enterprise Sales Director"
       :job-level "L6"
       :salary-band "$180k - $220k"
       :bonus-target "25%"
       :justification "Enterprise account expansion"
       :requester-id "u-leo-mgr"
       :chain-snapshot [{:step 1 :role :hiring-manager} {:step 2 :role :vp}]
       :approvals [{:step 1 :approver-user-id "u-leo-mgr"}
                   {:step 2 :approver-user-id "u-karen-vp"}]
       :final-status :approved}

      ;; 4. Draft
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
       :final-status :draft}]}]})

(defn write-seed-nippy!
  "Generates the seed dataset and serializes it to a binary Nippy archive
   with Snappy compression."
  ([]
   (write-seed-nippy! default-seed-path))
  ([path]
   (let [file (io/file path)
         parent (.getParentFile file)]
     (when (and parent (not (.exists parent)))
       (.mkdirs parent))
     (let [data (generate-seed-data)]
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

(defn load-seed-data!
  "Ingests a parsed seed dataset map into Rama depots and PStates.
   Returns a map with counts of seeded entities."
  [deps dataset]
  (let [user-map (atom {})
        org-map (atom {})]

    ;; 1. Register users in omni-auth
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

    ;; 2. Create organizations and memberships
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

        ;; 3. Create Org Units (Divisions and Departments)
        (doseq [u (:units org)]
          (core/create-org-unit! deps {:unit-id (:unit-id u)
                                       :org-id org-id
                                       :name (:name u)
                                       :parent-id (:parent-id u)
                                       :budget (:budget u)}))

        ;; 4. Assign Actors
        (doseq [a (:actors org)]
          (let [resolved-u-id (get @user-map (:user-id a))]
            (when resolved-u-id
              (core/assign-org-actor! deps {:org-id org-id
                                            :unit-id (:unit-id a)
                                            :user-id resolved-u-id
                                            :role (:role a)}))))

        ;; 5. Set Approval Rules and Role Permissions
        (when-let [rules (:approval-rules org)]
          (core/set-approval-rules! deps org-id rules))
        (when-let [perms (:role-permissions org)]
          (doseq [[role role-perms] perms]
            (core/set-role-permissions! deps org-id role role-perms)))

        ;; 6. Create & Advance Headcount Requisitions
        (doseq [req (:requisitions org)]
          (let [requester-resolved-id (get @user-map (:requester-id req))
                create-input (assoc req
                                    :org-id org-id
                                    :requester-id requester-resolved-id
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
                                                          :idempotency-key (str "seed-hire-" req-id)})))))))

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

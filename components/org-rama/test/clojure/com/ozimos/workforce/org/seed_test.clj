(ns com.ozimos.workforce.org.seed-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.ozimos.omni-auth.user.interface :as user]
   [com.ozimos.workforce.org.interface :as org]
   [com.ozimos.workforce.org.seed :as seed]
   [com.ozimos.workforce.web.test-system :as ts]))

(def ^:dynamic *deps* nil)

(defn system-fixture
  [tests]
  (let [sys (ts/get-sys)
        us (ts/user-store sys)]
    (binding [*deps* (assoc us :user-store us :cluster-manager (ts/rama-cluster sys))]
      (tests))))

(use-fixtures :once system-fixture)

(deftest seed-data-structure-and-serialization-test
  (testing "generate-seed-data produces complete dataset"
    (let [data (seed/generate-seed-data)]
      (is (map? data))
      (is (= 1 (:version data)))
      (is (>= (count (:organizations data)) 2))
      (is (>= (count (:users data)) 10))

      ;; Verify multi-org shared user Carol
      (let [org1 (first (:organizations data))
            org2 (second (:organizations data))
            org1-member-emails (set (map :email (:members org1)))
            org2-member-emails (set (map :email (:members org2)))]
        (is (contains? org1-member-emails "carol@crossorg.com"))
        (is (contains? org2-member-emails "carol@crossorg.com")))

      ;; Verify lifecycle coverage across requisitions
      (let [all-reqs (mapcat :requisitions (:organizations data))
            statuses (set (map :final-status all-reqs))]
        (is (contains? statuses :filled))
        (is (contains? statuses :in-approval))
        (is (contains? statuses :approved))
        (is (contains? statuses :rejected))
        (is (contains? statuses :draft)))))

  (testing "write-seed-nippy! and read-seed-nippy roundtrip with compression"
    (let [tmp-path "target/test-seed-roundtrip.nippy"
          write-res (seed/write-seed-nippy! tmp-path)]
      (is (true? (:ok write-res)))
      (is (.exists (io/file tmp-path)))
      (is (pos? (.length (io/file tmp-path))))

      (let [read-data (seed/read-seed-nippy tmp-path)]
        (is (= 1 (:version read-data)))
        (is (= 2 (count (:organizations read-data))))
        (is (some #(= "Acme Corp" (:name %)) (:organizations read-data)))
        (is (some #(= "Globex Innovations" (:name %)) (:organizations read-data)))))))

(deftest load-seed-data-into-rama-test
  (testing "load-seed-data! ingests full multi-org hierarchy and requisitions into Rama"
    (let [data (seed/generate-seed-data)
          load-res (seed/load-seed-data! *deps* data)]
      (is (true? (:ok load-res)))
      (is (>= (:organizations-seeded load-res) 2))

      ;; 1. Check Users in omni-auth
      (let [alice (user/find-by-email *deps* "alice@acme.com")
            bob (user/find-by-email *deps* "bob@globex.com")
            carol (user/find-by-email *deps* "carol@crossorg.com")]
        (is (some? alice))
        (is (= "alice" (:username alice)))
        (is (true? (:verified alice)))
        (is (contains? (:roles alice) "ADMIN"))
        (is (some? bob))
        (is (some? carol)))

      ;; 2. Check Organizations
      (let [acme (org/find-org-by-name *deps* "Acme Corp")
            globex (org/find-org-by-name *deps* "Globex Innovations")]
        (is (some? acme))
        (is (= "Acme Corp" (:name acme)))
        (is (some? globex))
        (is (= "Globex Innovations" (:name globex))))

      ;; 3. Check Hierarchy in Acme
      (let [acme-children (org/get-org-children *deps* "div-acme-eng")]
        (is (contains? acme-children "dept-acme-backend"))
        (is (contains? acme-children "dept-acme-frontend"))
        (is (contains? acme-children "dept-acme-ai")))

      ;; 4. Check Scoped Actors
      (let [backend-actors (org/get-unit-actors *deps* "dept-acme-backend")]
        (is (some? (or (get backend-actors :hiring-manager) (get backend-actors ":hiring-manager"))))
        (is (some? (or (get backend-actors :dept-head) (get backend-actors ":dept-head")))))

      ;; 5. Check Headcount Lifecycle states in Rama
      (let [req-filled (org/get-headcount-request *deps* "hc-acme-backend-1")
            req-in-app (org/get-headcount-request *deps* "hc-acme-frontend-1")
            req-app (org/get-headcount-request *deps* "hc-acme-design-1")
            req-rej (org/get-headcount-request *deps* "hc-acme-backend-2")
            req-draft (org/get-headcount-request *deps* "hc-acme-prod-1")]
        (is (= :filled (:status req-filled)))
        (is (some? (:hired-user-id req-filled)))

        (is (= :in-approval (:status req-in-app)))
        (is (= 2 (:current-step req-in-app)))

        (is (= :approved (:status req-app)))

        (is (= :rejected (:status req-rej)))
        (is (= "Budget reallocated to AI initiatives" (:rejection-reason req-rej)))

        (is (= :draft (:status req-draft)))
        (is (= "$140k - $165k" (:salary-band req-draft))))

      ;; 6. Check Department Headcount Stats
      (let [stats (org/get-unit-headcount-stats *deps* "dept-acme-backend")]
        (is (= 15 (:budget stats)))
        (is (= 1 (:filled stats))))

      ;; 7. Test ensure-seeded! idempotent no-op
      (let [ensure-res (seed/ensure-seeded! *deps* "target/test-seed-roundtrip.nippy")]
        (is (= :already-seeded (:status ensure-res)))))))

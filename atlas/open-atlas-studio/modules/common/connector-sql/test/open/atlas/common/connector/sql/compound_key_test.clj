(ns open.atlas.common.connector.sql.compound-key-test
  "doc 106 §106.14 item 14 (owner 2026-09-07): a compound key over sql.

   Two things this file measures, both stated by the ruling: a by-key read is
   `WHERE k1 = ? AND k2 = ?` (spelled here as the row-value `IN` the planner
   writes, which is that comparison for N parents at once), and a batch's
   `:parent-key` / `:child-fk` accept vectors and align on a ROW VALUE —
   `(a, b) IN ((?,?), …)`.

   Its own tables, not fixtures/b1: the K8s shape (a pod recognised by its
   namespace AND its name) is the ruling's proof sample and has no counterpart
   in that corpus. Row values are SQL-92 and sqlite has answered them since
   3.15; PostgreSQL and MySQL likewise. ClickHouse also documents tuple `IN`,
   but nothing here reaches one — this suite has no ClickHouse."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]))

(def ^:private jdbc-url
  "jdbc:sqlite:file:atlas_compound_key?mode=memory&cache=shared")

(def ^:private ^:dynamic *ds* nil)

(def ^:private ddl
  ["CREATE TABLE k8s_namespaces (name TEXT PRIMARY KEY, phase TEXT)"
   "CREATE TABLE k8s_pods (ns TEXT, name TEXT, phase TEXT, PRIMARY KEY (ns, name))"
   "INSERT INTO k8s_namespaces VALUES ('default','Active'),('kube-system','Active')"
   (str "INSERT INTO k8s_pods VALUES "
        "('default','web-1','Running'),"
        "('default','web-2','Pending'),"
        "('kube-system','web-1','Running')")])

(defn- with-db [f]
  (let [keep-alive (java.sql.DriverManager/getConnection jdbc-url)]
    (try
      (doseq [s ddl] (jdbc/execute! keep-alive [s]))
      (binding [*ds* (jdbc/get-datasource jdbc-url)] (f))
      (finally
        (doseq [t ["k8s_pods" "k8s_namespaces"]]
          (jdbc/execute! keep-alive [(str "DROP TABLE IF EXISTS " t)]))
        (.close keep-alive)))))

(use-fixtures :once with-db)

;; --- the by-compound-key read ----------------------------------------------

(def ^:private pod-by-key
  {:source {:type "sql"
            :query (str "SELECT ns, name, phase FROM k8s_pods "
                        "WHERE ns = :namespace AND name = :name")
            :field_mapping {:k8s.pod/namespace "ns" :k8s.pod/name "name"
                            :k8s.pod/phase "phase"}
            :params {:namespace :k8s.pod/namespace :name :k8s.pod/name}}
   :resolver {:id "k8s_pod_by_namespace_and_name"
              :input [:k8s.pod/namespace :k8s.pod/name]
              :output [:k8s.pod/phase]
              :result_type "single"}})

;; --- the batched child collection, aligned on a row value -------------------

(def ^:private pods-by-namespace
  {:source {:type "sql"
            :query (str "SELECT ns, name, phase FROM k8s_pods "
                        "WHERE (ns, name) IN (:keys)")
            :field_mapping {:k8s.pod/namespace "ns" :k8s.pod/name "name"
                            :k8s.pod/phase "phase"}
            :batch {:parent-key [:k8s.pod/namespace :k8s.pod/name]
                    :child-fk ["ns" "name"]
                    :placeholder "keys"}}
   :resolver {:id "k8s_pods_batch"
              :input [:k8s.pod/namespace :k8s.pod/name]
              :output [:k8s.pod/phase]
              :result_type "single"}})

(defn- reader [{:keys [source resolver]}] (spi/compile-source source *ds* resolver))
(defn- batch-reader [{:keys [source resolver]}]
  (spi/compile-batch-source source *ds* resolver))

;; ---------------------------------------------------------------------------

(deftest both-parts-of-the-key-narrow-the-read
  (let [read (reader pod-by-key)]
    (is (= "Running" (:k8s.pod/phase (read {:k8s.pod/namespace "default"
                                            :k8s.pod/name "web-1"} nil))))
    (is (= "Running" (:k8s.pod/phase (read {:k8s.pod/namespace "kube-system"
                                            :k8s.pod/name "web-1"} nil))))
    (testing "the same local name in another namespace is another pod"
      (is (= "Pending" (:k8s.pod/phase (read {:k8s.pod/namespace "default"
                                              :k8s.pod/name "web-2"} nil)))))
    (testing "a pair that names no row answers nil, not the near miss"
      (is (nil? (read {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-2"} nil))))))

(deftest a-row-value-batch-aligns-every-parent
  ;; One statement, three parents, and the third has no row: the empty slot is
  ;; kept, which is the batch invariant every alignment obeys.
  (let [read (batch-reader pods-by-namespace)
        out  (read [{:k8s.pod/namespace "default" :k8s.pod/name "web-1"}
                    {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-1"}
                    {:k8s.pod/namespace "kube-system" :k8s.pod/name "web-2"}]
                   nil)]
    (is (= 3 (count out)) "one slot per parent, always")
    (is (= ["Running" "Running" nil] (mapv :k8s.pod/phase out))
        "and each slot holds the row for ITS pair, not for a matching half")))

(deftest describe-reports-the-composite-primary-key
  (let [d (spi/describe-schema {:type "sql" :jdbc_url jdbc-url} *ds*
                               {:names ["k8s_pods"]})
        rel (first (filter #(= "k8s_pods" (:name %)) (:relations d)))]
    (is (= ["ns" "name"] (:primary-key rel))
        "both key columns, in KEY_SEQ order")))

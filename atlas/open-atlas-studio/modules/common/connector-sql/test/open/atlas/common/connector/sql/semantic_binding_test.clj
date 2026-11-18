(ns open.atlas.common.connector.sql.semantic-binding-test
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [open.atlas.common.connector.sql :as sql]
            [open.atlas.common.connector.spi :as spi]))

(def write-binding
  {:binding/effect :write
   :binding/implementation {:query "INSERT INTO cases (id, title) VALUES (:id, :title)"}
   :binding/input-map {:case/id "id" :case/title "title"}
   :binding/output-map {}})
(def read-binding
  {:binding/effect :read
   :binding/implementation {:query "SELECT title FROM cases WHERE id = :id"}
   :binding/input-map {:case/id "id"}
   :binding/output-map {:case/title "title"}})

(deftest mappings-use-the-existing-prepared-statement-connector
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")
        ds (jdbc/get-datasource url)]
   (with-open [connection (java.sql.DriverManager/getConnection url)]
    (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
    (is (empty? (:findings (sql/validate-binding write-binding))))
    (is (empty? (:findings (sql/validate-binding read-binding))))
    (let [write! (spi/compile-mutation (sql/binding-operation write-binding) ds
                                        {:id "create-case" :result_type "none"})
          read! (spi/compile-source (sql/binding-operation read-binding) ds
                                     {:id "read-case" :result_type "single" :output [:case/title]})
          title "'); DROP TABLE cases; --"]
      (write! {:case/id 1 :case/title title})
      (is (= {:case/title title} (read! {:case/id 1} {})))))))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §2.2 (doc 109 §109.8 items 1-2, owner 2026-09-18):
;; sql binds an absent optional root as SQL NULL and refuses a nested root.
;; ---------------------------------------------------------------------------

(def ^:private parameter-binding
  {:binding/effect :read
   :binding/implementation {:query "SELECT title FROM cases WHERE id = :id AND (:requested_title IS NULL OR title = :requested_title)"}
   :binding/input-map {:case/id "id"}
   :binding/parameter-map {:search "requested_title"}
   :binding/output-map {:case/title "title"}})

(deftest optional-parameter-is-not-an-input-and-binds-null-or-provided-value
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")
        ds (jdbc/get-datasource url)]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
      (jdbc/execute! connection ["INSERT INTO cases VALUES (1, 'one')"])
      (is (empty? (:findings (sql/validate-binding parameter-binding))))
      (is (= :search (get-in (sql/binding-operation parameter-binding) [:params "requested_title"])))
      (let [read! (spi/compile-source (sql/binding-operation parameter-binding) ds
                                     {:id "read-case" :result_type "single" :output [:case/title]
                                      :input [:case/id] :eql-params {:search {:type :string}}})]
        (is (= {:case/title "one"} (read! {:case/id 1} {})))
        (is (= {:case/title "one"} (read! {:case/id 1} {:search "one"})))
        (is (nil? (read! {:case/id 1} {:search "other"})))))))

(deftest a-nested-input-is-refused-at-the-root
  (let [b (-> read-binding
              (assoc-in [:binding/input-map :case/notes] "notes")
              (assoc :binding/input-nested {:case/notes [:note/id]}))]
    (is (contains? (set (map (juxt :code :path) (:findings (sql/validate-binding b))))
                   [:connector.binding/nested-input-unsupported [:binding/input-map :case/notes]]))))

(deftest invalid-bindings-are-rejected-without-echoing-configuration
  (doseq [binding [(assoc-in read-binding [:binding/implementation :password] "private-secret")
                   (assoc-in read-binding [:binding/implementation :query] "")
                   (assoc-in read-binding [:binding/implementation :query] "SELECT :missing")
                   (assoc-in write-binding [:binding/input-map :case/title] "id")
                   (assoc-in read-binding [:binding/implementation :jdbc_url] "private-secret")]]
    (let [result (sql/validate-binding binding)]
      (is (seq (:findings result)))
      (is (every? #(and (:code %) (:severity %) (:path %) (:message %)) (:findings result)))
      (is (not (.contains (pr-str result) "private-secret")))
      (is (not (.contains (pr-str result) "SELECT")) "statement text never reaches a finding"))))

(defn- codes-and-paths [b]
  (set (map (juxt :code :path) (:findings (sql/validate-binding b)))))

(deftest each-clause-reports-its-own-code-and-path
  ;; Owner ruling 2026-09-18: one code and a precise path per clause, all
  ;; clauses collected — not one `:connector.binding/invalid` for everything.
  (is (contains? (codes-and-paths (assoc read-binding :binding/effect :upsert))
                 [:connector.binding/effect [:binding/effect]]))
  (is (contains? (codes-and-paths (assoc-in read-binding [:binding/implementation :column-map] {}))
                 [:connector.binding/unknown-key [:binding/implementation :column-map]])
      "ruling #8: :column-map is gone")
  (is (contains? (codes-and-paths (assoc-in read-binding [:binding/implementation :query] ""))
                 [:connector.binding/missing-key [:binding/implementation :query]]))
  (is (contains? (codes-and-paths (assoc-in write-binding [:binding/input-map :case/title] "id"))
                 [:connector.binding/duplicate-physical-name [:binding/input-map :case/title]]))
  (is (contains? (codes-and-paths (assoc-in read-binding [:binding/output-map :case/title] ""))
                 [:connector.binding/shape [:binding/output-map :case/title]]))
  (is (contains? (codes-and-paths (assoc-in write-binding [:binding/implementation :batch]
                                            {:parent-key :case/id :child-fk "id" :placeholder "ids"}))
                 [:connector.binding/batch-effect [:binding/implementation :batch]]))
  (is (contains? (codes-and-paths (assoc-in read-binding [:binding/implementation :batch]
                                            {:parent-key :case/unknown :child-fk "id" :placeholder "ids"}))
                 [:connector.binding/batch-parent [:binding/implementation :batch :parent-key]]))
  (testing "the underlying sql-v1 finding keeps its code and path, minus the value"
    (let [result (sql/validate-binding (assoc-in read-binding [:binding/implementation :query] "SELECT :missing"))]
      (is (seq (:findings result)))
      (is (every? #(= :connector.source/placeholder-mismatch (:code %)) (:findings result)))
      (is (not-any? #(contains? % :got) (:findings result)))))
  (testing "all clause findings are collected"
    (is (= 3 (count (:findings (sql/validate-binding
                                (-> read-binding (assoc :binding/effect :upsert)
                                    (assoc-in [:binding/implementation :column-map] {})
                                    (assoc-in [:binding/output-map :case/title] "")))))))))

(deftest the-registered-adapter-answers-findings-not-a-boolean
  (let [adapter (spi/semantic-adapter "sql")]
    (is (= {:findings []} ((:validate adapter) read-binding)))
    (is (seq (:findings ((:validate adapter) (assoc read-binding :binding/effect :upsert)))))
    (is (= (sql/binding-operation read-binding) ((:lower adapter) read-binding)))
    (is (= {:case/title "title"} (:field_mapping (sql/binding-operation read-binding)))
        "field_mapping is the output-map alone (ruling #8)")))

(deftest semantic-batch-preserves-parent-alignment
  (let [binding (-> read-binding
                    (assoc-in [:binding/implementation :query]
                              "SELECT id, title FROM cases WHERE id IN (:ids)")
                    (assoc-in [:binding/implementation :batch]
                              {:parent-key :case/id :child-fk "id" :placeholder "ids"}))
        operation (sql/binding-operation binding)
        url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")]
    (is (empty? (:findings (sql/validate-binding binding))))
    (is (= {} (:params operation)))
    (is (= (get-in binding [:binding/implementation :batch]) (:batch operation)))
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! connection ["CREATE TABLE cases (id INTEGER PRIMARY KEY, title TEXT)"])
      (jdbc/execute! connection ["INSERT INTO cases VALUES (1, 'first'), (2, 'second')"])
      (let [read! (spi/compile-batch-source operation (jdbc/get-datasource url)
                                           {:id "batch" :input [:case/id]
                                            :output [:case/title] :result_type "single"})]
        (is (= ["second" nil "first" "second"]
               (mapv :case/title (read! (mapv #(hash-map :case/id %) [2 99 1 2]) {}))))))
    (doseq [invalid [(assoc-in binding [:binding/implementation :batch] nil)
                     (assoc-in binding [:binding/implementation :batch :parent-key] :case/unknown)
                     (assoc binding :binding/effect :write)]]
      (is (seq (:findings (sql/validate-binding invalid)))))))

;; ---------------------------------------------------------------------------
;; Semantic contract 2.0 §6.3 (doc 109 §109.8 item 3, owner 2026-09-18): sql
;; refuses a union output and needs nothing for a recursive one.
;; ---------------------------------------------------------------------------

(deftest a-union-output-is-refused-and-a-recursive-output-is-accepted
  (let [tree-binding {:binding/effect :read
                      :binding/implementation {:query "SELECT id, name FROM grp WHERE parent_id = :id"}
                      :binding/input-map {:grp/id "id"}
                      :binding/output-map {:grp/children "children" :grp/id "id" :grp/name "name"}}]
    (testing "the execution layer's carrier for a recursion key changes nothing"
      (is (empty? (:findings (sql/validate-binding (assoc tree-binding :binding/output-recursion #{:grp/children}))))))
    (testing "a union key is refused at its output-map entry"
      (let [r (sql/validate-binding (-> read-binding
                                        (assoc-in [:binding/output-map :case/owner] "owner")
                                        (assoc :binding/output-union #{:case/owner})))]
        (is (= [[:connector.binding/union-output-unsupported [:binding/output-map :case/owner]]]
               (mapv (juxt :code :path) (:findings r))))))))

(deftest vector-input-expands-as-bound-values-in-in-clause
  (let [url (str "jdbc:sqlite:file:" (java.util.UUID/randomUUID) "?mode=memory&cache=shared")
        ds (jdbc/get-datasource url)
        binding {:binding/effect :read
                 :binding/implementation {:query "SELECT title FROM cases WHERE id IN (:ids) ORDER BY id"}
                 :binding/input-map {:case/ids "ids"}
                 :binding/output-map {:case/title "title"}}]
    (with-open [connection (java.sql.DriverManager/getConnection url)]
      (jdbc/execute! connection ["CREATE TABLE cases (id TEXT PRIMARY KEY, title TEXT)"])
      (jdbc/execute! connection ["INSERT INTO cases VALUES ('a','one'),('b','two')"])
      (let [read! (spi/compile-source (sql/binding-operation binding) ds
                                    {:id "vector-case" :result_type "list" :input [:case/ids] :output [:case/title]})]
        (is (= [{:case/title "one"} {:case/title "two"}] (read! {:case/ids ["a" "b"]} {})))
        (is (= [{:case/title "one"}] (read! {:case/ids ["a" "a"]} {})))
        (is (= [] (read! {:case/ids []} {})))
        (is (= [] (read! {:case/ids ["a') OR 1=1 --"]} {})))
        (is (= 2 (:n (first (jdbc/execute! connection ["SELECT count(*) n FROM cases"])))))))))

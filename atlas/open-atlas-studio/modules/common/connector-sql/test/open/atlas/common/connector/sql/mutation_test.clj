(ns open.atlas.common.connector.sql.mutation-test
  "mutation 能力（sql-v1「能力」）：「mutation ✓（操作块同上，`:result_type \"none\"`
   时不读结果集）」。写用自己的表，不动 fixtures/b1 的种子数据。"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql]
            [open.atlas.common.connector.sql.db :as db]))

(use-fixtures :once
  db/with-fixture-db
  (fn [f]
    (jdbc/execute! db/*ds* ["CREATE TABLE IF NOT EXISTS notes (
                               note_no INTEGER PRIMARY KEY, body TEXT)"])
    (jdbc/execute! db/*ds* ["DELETE FROM notes"])
    (f)))

(def ^:private insert-none
  {:source {:type "sql"
            :query "INSERT INTO notes (note_no, body) VALUES (:note_no, :body)"
            :params {"note_no" :demo.note/note_no "body" :demo.note/body}}
   :mutation {:id "demo_add_note" :result_type "none"}})

(def ^:private insert-single
  {:source {:type "sql"
            :query (str "INSERT INTO notes (note_no, body) VALUES (:note_no, :body) "
                        "RETURNING note_no, body")
            :params {"note_no" :demo.note/note_no "body" :demo.note/body}}
   :mutation {:id "demo_add_note_returning" :result_type "single"
              :returns [:demo.note/note_no :demo.note/body]}})

(defn- writer [{:keys [source mutation]}]
  (spi/compile-mutation source db/*ds* mutation))

(deftest result-type-none-does-not-read-a-result-set
  (let [r ((writer insert-none) {:demo.note/note_no 1 :demo.note/body "first"})]
    (is (nil? r) "\"none\" 没有 :returns，所以也没有可读的东西——nil，不是 {}")
    (is (= "first" (:body (jdbc/execute-one! db/*ds* ["SELECT body FROM notes WHERE note_no = 1"]
                                     {:builder-fn rs/as-unqualified-lower-maps}))))))

(deftest result-type-single-maps-the-returned-row
  (let [r ((writer insert-single) {:demo.note/note_no 2 :demo.note/body "second"})]
    (is (= {:demo.note/note_no 2 :demo.note/body "second"} r))))

(deftest mutation-values-are-bound-not-interpolated
  (testing "写路径与读路径同一条纪律：值走 prepared statement"
    (let [evil "'); DROP TABLE notes; --"]
      ((writer insert-none) {:demo.note/note_no 3 :demo.note/body evil})
      (is (= evil (:body (jdbc/execute-one! db/*ds*
                                            ["SELECT body FROM notes WHERE note_no = 3"]
                                            {:builder-fn rs/as-unqualified-lower-maps})))
          "值原样落库——它是数据，从来没有当成语句解析过")
      (is (some? (jdbc/execute-one! db/*ds* ["SELECT count(*) c FROM notes"]))
          "notes 表还在"))))

(deftest mutation-failure-is-a-structured-source-error
  (testing "主键冲突：写失败绝不能长得像成功"
    ((writer insert-none) {:demo.note/note_no 9 :demo.note/body "x"})
    (let [e (try ((writer insert-none) {:demo.note/note_no 9 :demo.note/body "y"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (spi/failure? e))
      (is (= :source (spi/error-category e)))
      (is (= "demo_add_note" (:resolver-id (ex-data e)))))))

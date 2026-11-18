(ns open.atlas.common.connector.sql.tagging-test
  "E2 源系统打标：语句前置 `/* open-atlas req=… graph=… */`。

   两件事在这里定住：注释**只在 exec 层**拼上（模型里的语句文本不含它，
   占位符个数与绑定顺序不变），以及**没有 request-id 时语句原样**——单测、
   CLI、体检都走这条路。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.connector.sql.db :as db]
            [open.atlas.common.connector.sql.impl.exec :as exec]))

(use-fixtures :once db/with-fixture-db)

(def ^:private trace {:request-id "req-abc-123" :graph-id "6f1c9e40-0000-4000-8000-abcdef012345"})

(deftest tag-prefixes-once-and-keeps-bindings
  (let [in  ["SELECT a FROM t WHERE b = ? AND c = ?" 1 "x"]
        out (binding [spi/*request-trace* trace]
              (exec/tag in (spi/ctx-with-request-trace {:resolver-id "r" :source-type "sql"})))]
    (testing "注释在最前，恰好一次"
      (is (str/starts-with? (first out)
                            "/* open-atlas req=req-abc-123 graph=6f1c9e40-0000-4000-8000-abcdef012345 */ "))
      (is (= 1 (count (re-seq #"open-atlas req=" (first out))))))
    (testing "占位符个数与绑定顺序不变"
      (is (= 2 (count (re-seq #"\?" (first out)))))
      (is (= [1 "x"] (vec (rest out)))))
    (testing "语句本体逐字保留"
      (is (str/ends-with? (first out) "SELECT a FROM t WHERE b = ? AND c = ?")))))

(deftest no-request-id-leaves-the-statement-alone
  (let [in ["SELECT 1" 7]]
    (is (= in (exec/tag in {:resolver-id "r" :source-type "sql"})))
    (is (= in (exec/tag in (spi/ctx-with-request-trace {:resolver-id "r"})))
        "trace 未绑定时 with-request-trace 不往 ctx 里加东西")))

(deftest ids-are-whitelisted-so-a-comment-can-never-close-itself
  (let [ctx (spi/ctx-with-request-trace {:resolver-id "r"})]
    (binding [spi/*request-trace* {:request-id "a*/ DROP TABLE t; --" :graph-id "g/1"}]
      (let [out (exec/tag ["SELECT 1"] (spi/ctx-with-request-trace {:resolver-id "r"}))]
        (is (not (str/includes? (str/replace (first out) #"^/\* [^*]*\*/ " "") "*/"))
            "过滤之后注释里不可能出现第二个 `*/`")
        (is (str/starts-with? (first out) "/* open-atlas req=aDROPTABLEt-- graph=g1 */ "))))
    (is (map? ctx))))

(deftest trace-receipt-carries-the-tagged-text-and-no-values-in-it
  (let [seen (atom [])]
    (binding [spi/*request-trace* trace
              spi/*statement-trace* #(swap! seen conj %)]
      (exec/query! db/*ds* ["SELECT vend_name FROM vendors WHERE vend_no = ?" 1001]
                   5 (spi/ctx-with-request-trace {:resolver-id "demo_vendor_by_id"
                                              :source-type "sql"})))
    (let [{:keys [sql values]} (first @seen)]
      (is (str/starts-with? sql "/* open-atlas req=req-abc-123 "))
      (is (= [1001] values) "值仍在 :values 里，不在语句串中")
      (is (not (str/includes? sql "1001"))))))

(deftest authoring-diagnostics-retain-sql-errors-without-changing-public-failures
  (let [seen (atom [])
        ctx {:resolver-id "broken" :source-type "sql" :source-ref "flowx-pg"}
        failure (binding [spi/*source-diagnostics* #(swap! seen conj %)]
                  (try (exec/query! db/*ds* ["SELECT missing_column FROM vendors"] 5 ctx)
                       (catch Exception e e)))]
    (is (= :source (:error/category (ex-data failure))))
    (is (nil? (.getCause failure)))
    (is (not (str/includes? (.getMessage failure) "missing_column")))
    (is (= "flowx-pg" (:source-ref (first @seen))))
    (is (= :query (:stage (first @seen))))
    (is (str/includes? (pr-str (:causes (first @seen))) "missing_column"))
    (is (contains? (first (:causes (first @seen))) :vendor-code))
    (is (not (contains? (spi/statement-audit-summary (first @seen)) :causes)))))

(deftest diagnostic-sink-failures-do-not-replace-the-source-error
  (binding [spi/*source-diagnostics* (fn [_] (throw (Exception. "broken sink")))]
    (let [e (try (exec/query! db/*ds* ["SELECT missing_column FROM vendors"] 5 {})
                 (catch Exception e e))]
      (is (= :source (:error/category (ex-data e)))))))

(deftest connection-diagnostic-redacts-resolved-credentials
  (let [redact (exec/diagnostic-redactor
                {:jdbc_url "jdbc:clickhouse://host:8123/db"
                 :username "private-user" :password "private-password"})
        message (redact "private-user private-password jdbc:clickhouse://host:8123/db password=other-secret missing column foo")]
    (doseq [secret ["private-user" "private-password" "jdbc:clickhouse" "other-secret"]]
      (is (not (str/includes? message secret))))
    (is (str/includes? message "missing column foo"))))

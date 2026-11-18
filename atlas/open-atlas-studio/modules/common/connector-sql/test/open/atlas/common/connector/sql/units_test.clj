(ns open.atlas.common.connector.sql.units-test
  "纯函数层：占位渲染、IN 展开、`${VAR}` 替换。
   这些是「值永不字符串插值」与「`IN (:placeholder)` 展开为 N 个绑定」两条硬
   约束的实现点，单独钉住比只从端到端反推快。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [open.atlas.common.connector.sql.impl.envsub :as envsub]
            [open.atlas.common.connector.sql.impl.paths :as paths]
            [open.atlas.common.connector.sql.impl.pool :as pool]
            [open.atlas.common.connector.sql.impl.query :as query]))

(deftest placeholders-are-named-and-do-not-swallow-casts
  (is (= ["vend_no"] (query/placeholders "SELECT 1 WHERE a = :vend_no")))
  (is (= ["a" "b"] (query/placeholders "WHERE x = :a AND y = :b")))
  (testing "PostgreSQL 的 `::type` 强制转换不是占位符"
    (is (= ["v"] (query/placeholders "SELECT x::text WHERE a = :v")))))

(deftest render-emits-question-marks-and-bound-values
  (let [[sql & vs] (query/render "SELECT 1 WHERE a = :a AND b = :b"
                                 {:value-of {"a" 1 "b" "x'; DROP--"}})]
    (is (= "SELECT 1 WHERE a = ? AND b = ?" sql))
    (is (= [1 "x'; DROP--"] vs))
    (testing "值一个字符都没有进语句串"
      (is (not (str/includes? sql "DROP")))))
  (testing "同一个占位名出现两次 → 两个 ? 两个绑定"
    (is (= ["a = ? OR b = ?" 7 7] (query/render "a = :x OR b = :x" {:value-of {"x" 7}})))))

(deftest in-list-expands-to-n-bindings
  (testing "sql-v1 batch：「`IN (:placeholder)` 展开为 N 个绑定」"
    (is (= ["WHERE sku_no IN (?, ?, ?)" 1 2 3]
           (query/render "WHERE sku_no IN (:sku_nos)"
                         {:value-of {} :in-list {"sku_nos" [1 2 3]}}))))
  (testing "空列表 → 单个 NULL 绑定：匹配零行，而不是语法错误、
            更不是把 IN 抹掉变成一次无过滤全表扫描"
    (let [[sql & vs] (query/render "WHERE sku_no IN (:sku_nos)"
                                   {:value-of {} :in-list {"sku_nos" []}})]
      (is (= "WHERE sku_no IN (?)" sql))
      (is (= [nil] vs))))
  (testing "IN 之外的占位符照旧一个绑定"
    (is (= ["a = ? AND b IN (?, ?)" 9 1 2]
           (query/render "a = :a AND b IN (:bs)"
                         {:value-of {"a" 9} :in-list {"bs" [1 2]}})))))

(deftest binding-resolution-and-identity-default
  (testing "D2.1 恒等缺省：占位名 = 属性 local name"
    (let [{:keys [bindings unresolved unused]}
          (query/resolve-bindings {:input [:cis.master.vendor/vend_no]}
                                  "WHERE vend_no = :vend_no")]
      (is (= {:from :input :attr :cis.master.vendor/vend_no} (get bindings "vend_no")))
      (is (empty? unresolved))
      (is (empty? unused))))
  (testing "全限定 kw = :input 属性；非限定 = eql-param 名"
    (let [{:keys [bindings]}
          (query/resolve-bindings {:params {"a" :cis.master.part/sku_no "b" :prod_code}}
                                  "WHERE x = :a AND y = :b")]
      (is (= :input (:from (get bindings "a"))))
      (is (= {:from :eql-param :name :prod_code} (get bindings "b")))))
  (testing "batch 的 :placeholder 由父键绑定，不算未解析"
    (is (empty? (:unresolved (query/resolve-bindings
                              {:batch-placeholder "ids"}
                              "WHERE id IN (:ids)"))))))

(deftest env-substitution
  (is (envsub/only-placeholder? "${DB_PASS}"))
  (is (not (envsub/only-placeholder? "pre${DB_PASS}")))
  (is (not (envsub/only-placeholder? "hunter2")))
  (let [{:keys [value missing]} (envsub/substitute "jdbc:x://${H}/db" {"H" "localhost"})]
    (is (= "jdbc:x://localhost/db" value))
    (is (empty? missing)))
  (testing "未解析的变量不留字面占位符——留着会让一个没配好的连接串被当成主机名拨出去"
    (let [{:keys [value missing]} (envsub/substitute "jdbc:x://${H}/db" {})]
      (is (= ["H"] missing))
      (is (not (str/includes? value "${"))))))

(deftest connection-config-substitutes-and-defaults
  (let [cc (pool/connection-config {:jdbc_url "jdbc:x://${H}/db"
                                    :username "${U}" :password "${P}"
                                    :max_pool_size 3 :min_idle 9}
                                   {"H" "h" "U" "u" "P" "p"})]
    (is (= "jdbc:x://h/db" (:jdbc-url cc)))
    (is (= "u" (:username cc)))
    (is (empty? (:missing cc)))
    (is (= 3 (:min-idle cc)) ":min_idle 不得超过（可能更小的）池上限")))

;; ---------------------------------------------------------------------------
;; doc 104 §8.17.7 B: relative sqlite paths anchor at $OPEN_ATLAS_CONFIG_PATH
;; ---------------------------------------------------------------------------

(deftest sqlite-base-dir-follows-config-path-then-home
  (is (= "/cfg" (paths/base-dir {"OPEN_ATLAS_CONFIG_PATH" "/cfg" "OPEN_ATLAS_HOME" "/home"}))
      "$OPEN_ATLAS_CONFIG_PATH wins")
  (is (= "/home" (paths/base-dir {"OPEN_ATLAS_HOME" "/home"}))
      "$OPEN_ATLAS_HOME is the fallback (8.17.7 H item 1)")
  (is (= (str (System/getProperty "user.home") "/.open-atlas")
         (paths/base-dir {}))
      "default is the user's ~/.open-atlas"))

(deftest relative-sqlite-path-is-anchored
  (with-redefs [paths/base-dir (fn ([] "/cfg") ([_] "/cfg"))]
    (testing "relative → anchored"
      (let [{:keys [url anchored]} (paths/anchor-sqlite-url "jdbc:sqlite:data/demo.db")]
        (is (= "jdbc:sqlite:/cfg/data/demo.db" url))
        (is (= "/cfg/data/demo.db" anchored))))
    (testing "absolute, :memory: and URI-style specs are untouched"
      (doseq [u ["jdbc:sqlite:/var/lib/demo.db"
                 "jdbc:sqlite::memory:"
                 "jdbc:sqlite:file:demo.db?mode=ro"
                 "jdbc:postgresql://h/db"]]
        (let [{:keys [url anchored]} (paths/anchor-sqlite-url u)]
          (is (= u url))
          (is (nil? anchored)))))))

(deftest connection-config-anchors-after-substitution
  (with-redefs [paths/base-dir (fn ([] "/cfg") ([_] "/cfg"))]
    (let [cc (pool/connection-config {:jdbc_url "jdbc:sqlite:${DIR}/demo.db"}
                                     {"DIR" "data"})]
      (is (= "jdbc:sqlite:/cfg/data/demo.db" (:jdbc-url cc))
          "anchoring happens after ${VAR} substitution")
      (is (= "/cfg/data/demo.db" (:anchored-path cc))))))

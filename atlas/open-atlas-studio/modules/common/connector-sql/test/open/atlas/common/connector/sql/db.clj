(ns open.atlas.common.connector.sql.db
  "端到端用的内存 SQLite。

   fixtures/b1/README：「测试装配时指向内存 SQLite 并执行 `schema.sql`」，
   且「两个模块的 `:test` alias 都把本目录挂进 classpath，端到端用例读同一份
   ——engine 与 connector 若对同一份 fixture 的理解不一致，测试会当场分叉」。

   用 `file:…?mode=memory&cache=shared` 而不是 `:memory:`：后者每条连接各自
   一个空库，连接池一取新连接表就没了。共享缓存下只要有一条连接活着，库就在
   ——所以整个套件期间握住一条 keep-alive 连接。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(def jdbc-url "jdbc:sqlite:file:atlas_b1_fixture?mode=memory&cache=shared")

(def ^:dynamic *ds* nil)

(defn- statements [sql]
  (->> (str/split-lines sql)
       (remove #(str/starts-with? (str/triml %) "--"))
       (str/join "\n")
       (#(str/split % #";"))
       (map str/trim)
       (remove str/blank?)))

(defn with-fixture-db
  "跑 fixtures/b1/schema.sql 建表灌数，把 DataSource 绑到 `*ds*`。"
  [f]
  (let [keep-alive (java.sql.DriverManager/getConnection jdbc-url)
        ds (jdbc/get-datasource jdbc-url)
        sql (slurp (io/resource "schema.sql"))]
    (try
      (doseq [s (statements sql)]
        (jdbc/execute! keep-alive [s]))
      (binding [*ds* ds] (f))
      (finally (.close keep-alive)))))

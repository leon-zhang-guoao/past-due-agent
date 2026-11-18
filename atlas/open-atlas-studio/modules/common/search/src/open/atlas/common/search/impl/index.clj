(ns open.atlas.common.search.impl.index
  "SQLite FTS5 索引的建与查（README 裁定 ②④⑤⑥）。

   ## 输入是 `(方言模型, revision)`，不是 Datahike 连接（裁定 ⑥）

   studio 侧喂池投影出来的模型，server 侧喂制品装载出来的模型——两边索引出的
   东西因此逐字节可比，与 D4.10「两层都必须等价」同则。

   ## 陈旧即弃（裁定 ④）

   索引按 revision 键控。**revision 对不上 → 整体作废重建**，不存在「属性文档新、
   词条文档旧」的撕裂态。查询时对不上 **拒绝**，不是带警告地用：带警告地用等于
   把「这份答案对不对」推给一个没有能力判断的调用方（D7.11）。

   B4 的键是**池 revision**，不是 graph 闭包 revision——设计说的是后者，但 B4
   还没有 graph（pool README 裁定 ⑤）。**B5a 必须把这个键换掉**，这笔债记在
   本模块 README 裁定 ④。

   ## 排序（裁定 ②）

   BM25 **只在类型内**排序；类型间**固定顺序**：scope 内精确名 → 词条 →
   entity → 散属性（组随 D2.25 退役，只剩三类文档）。**响应里没有分数字段**——BM25 是语料内排序，同一个正确匹配
   这里 26 分那里 1.0 分，没有哪个阈值两次意味着同一件事（principles）。分数只
   在本 ns 内部当排序键用，一个字节都不出去。"
  (:require [clojure.string :as str]
            [open.atlas.common.search.impl.docs :as docs]
            [open.atlas.common.search.impl.finding :as f]
            [open.atlas.common.search.impl.gram :as gram]
            [open.atlas.common.search.impl.fuzzy :as fuzzy]
            [open.atlas.common.search.impl.match :as match])
  (:import [java.sql Connection DriverManager]))

(def default-max-hits
  "每类型的信封上限。超限**拒绝**而不是截断（CLAUDE.md）——截断的结果与完整
   结果无法区分，而调用方正要靠这份清单判断「这几个里选哪个」。"
  200)

(def bucket-order
  "类型间的**固定**顺序（裁定 ②）。`:exact-name` 不是第四类文档，
   它是「scope 内精确名」这一档——精确即身份，不与任何 BM25 分数比较。"
  [:exact-name :term :entity :attribute])

;; ---------------------------------------------------------------------------
;; SQLite
;; ---------------------------------------------------------------------------

(defn- open-conn ^Connection [path]
  (Class/forName "org.sqlite.JDBC")
  (DriverManager/getConnection (str "jdbc:sqlite:" (or path ":memory:"))))

(defn- exec! [^Connection c ^String sql]
  (with-open [s (.createStatement c)] (.execute s sql)))

(def ^:private create-sql
  ;; `doc_type` / `doc_key` 是 UNINDEXED：它们是坐标不是可搜文本,
  ;; 进索引会让「attribute」这个英文词本身变成一个命中源。
  "CREATE VIRTUAL TABLE docs USING fts5(doc_type UNINDEXED, doc_key UNINDEXED, body)")

(defn build
  "`(模型, revision)` → 索引句柄。

   `opts`：`:abbreviations` 缩写表、`:path` sidecar 文件（缺省内存）、
   `:visible?` **调用方给的文档抑制器**（缺省全可见）——它只缩语料，不表达模型
   语义；模型那一侧的过滤是 `:listed? false`，由 impl.docs 自己执行（D2.28）。"
  [model revision {:keys [abbreviations path visible?] :or {visible? (constantly true)}}]
  (let [ds     (mapv #(fuzzy/prepare-doc abbreviations %) (docs/all-docs model abbreviations visible?))
        conn   (open-conn path)]
    (exec! conn "DROP TABLE IF EXISTS docs")
    (exec! conn create-sql)
    (with-open [ps (.prepareStatement conn "INSERT INTO docs(doc_type,doc_key,body) VALUES (?,?,?)")]
      (doseq [[i d] (map-indexed vector ds)]
        (.setString ps 1 (name (:doc/type d)))
        (.setString ps 2 (str i))
        (.setString ps 3 (:doc/text d))
        (.addBatch ps))
      (.executeBatch ps))
    {:search/revision revision
     :search/conn     conn
     :search/docs     ds
     :search/fuzzy    (fuzzy/prepare-corpus ds)
     ;; 精确名索引走**共用匹配层**（裁定 ③）：`search_attrs` 的直中判定与
     ;; `resolve_target` 的 `:exact-unique` 判定因此对同一个输入词折叠出
     ;; 逐字节相同的短语。
     :search/by-phrase (match/build-phrase-index
                        (for [[i d] (map-indexed vector ds)]
                          {:id i :ns (when (= :entity (:doc/type d)) (:doc/id d))
                           :names (:doc/names d)})
                        abbreviations)
     :search/abbrev   abbreviations
     :search/model    model}))

(defn close! [idx]
  (when-let [^Connection c (:search/conn idx)] (.close c)))

(defn stale?
  "索引对不上这个 revision 吗（裁定 ④）。"
  [idx revision]
  (not= (:search/revision idx) revision))

(defn stale-finding [idx revision path]
  (f/make :search.index/stale :error path
          "search index was built for a different revision; discard and rebuild it"
          :expected (str "revision " (pr-str revision))
          :got (str "index revision " (pr-str (:search/revision idx)))
          :refs ["D7.11" "common/search README 裁定 ④"]))

;; ---------------------------------------------------------------------------
;; 查询
;; ---------------------------------------------------------------------------

(defn- bm25-order
  "一次 FTS5 查询：某一类型内按 BM25 升序（FTS5 的 bm25() 越小越相关）的文档下标。
   **分数留在这里**，不进任何返回值。"
  [idx doc-type expr]
  (with-open [ps (.prepareStatement ^Connection (:search/conn idx)
                                    "SELECT doc_key FROM docs WHERE doc_type = ? AND docs MATCH ? ORDER BY bm25(docs), doc_key")]
    (.setString ps 1 (name doc-type))
    (.setString ps 2 expr)
    (with-open [rs (.executeQuery ps)]
      (loop [acc []]
        (if (.next rs) (recur (conj acc (Long/parseLong (.getString rs 1)))) acc)))))

(defn- hit
  "文档 → 对外的命中形状。**没有分数字段**（裁定 ②）。"
  [bucket d]
  (let [base {:bucket bucket :type (:doc/type d) :id (:doc/id d) :label (:doc/label d)}]
    (case (:doc/type d)
      ;; 词条命中带**绑定计数与绑定清单**（D7.11：不是 7 条属性命中，是一条词条
      ;; 命中带 7 个绑定）。第二跳的精确键值反查在建索引时已经算好。
      :term      (assoc base :binding-count (:doc/binding-count d) :bindings (:doc/bindings d))
      :entity    base
      ;; 属性命中带**坐标**：`→ 词条ID`（D7.11）。
      :attribute (cond-> (assoc base :entity (namespace (:doc/id d)))
                   (:doc/term d)          (assoc :term (:doc/term d))
                   (:doc/term-context d)  (assoc :term-context (:doc/term-context d))))))

(defn search
  "global：`q` → 类型化命中，按 `bucket-order` 固定顺序。

   `opts`：`:revision`（必给，陈旧即弃）、`:max-hits`。"
  [idx q {:keys [revision max-hits] :or {max-hits default-max-hits}}]
  (let [abbrev (:search/abbrev idx)
        ds     (:search/docs idx)
        expr   (gram/match-expression abbrev nil q)]
    (cond
      (stale? idx revision) {:findings [(stale-finding idx revision [:search])]}

      (nil? expr)
      {:findings [(f/make :search.query/blank :error [:search :q]
                          "query has no indexable term after tokenization"
                          :expected "at least one CJK n-gram or latin word"
                          :got (f/redact q)
                          :refs ["common/search README 裁定 ⑤"])]}

      :else
      (let [exact  (match/exact-hits (:search/by-phrase idx) (match/fold abbrev nil q))
            exact? (set exact)
            precise-expr (gram/precise-expression abbrev q)
            precise (when precise-expr
                      (into {} (for [t docs/doc-types]
                                 [t (vec (filter #(fuzzy/whole-accepts? (nth ds %) (fuzzy/words abbrev nil q))
                                                  (bm25-order idx t precise-expr)))])))
            ;; Fall back only when there is NO exact or conjunctive match.
            ;; In particular, never broaden a precise query that exceeds its envelope.
            precise? (or (seq exact) (some seq (vals precise)))
            fuzzy-plan (when-not precise?
                         (fuzzy/query-plan abbrev q (:search/fuzzy idx)))
            hits   (concat
                    ;; 精确名先行——精确即身份，不与 BM25 分数比较。
                    (map #(hit :exact-name (nth ds %)) (sort exact))
                    (for [t docs/doc-types
                          i (if precise? (get precise t)
                              (when (:expression fuzzy-plan)
                                (filter #(fuzzy/accepts? (nth ds %) fuzzy-plan)
                                        (bm25-order idx t (:expression fuzzy-plan)))))
                          :when (not (exact? i))]
                      (hit t (nth ds i))))]
        (if (> (count hits) max-hits)
          {:findings [(f/make :search.query/over-limit :error [:search]
                              "hit list exceeds the per-call envelope; narrow the query"
                              :expected (str "<= " max-hits)
                              :got (str (count hits) " hits")
                              :refs ["CLAUDE.md 超限拒绝而非截断"])]}
          {:query q :revision revision :hits (vec hits)})))))

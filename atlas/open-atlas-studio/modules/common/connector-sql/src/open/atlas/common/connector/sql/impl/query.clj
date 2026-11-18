(ns open.atlas.common.connector.sql.impl.query
  "具名占位符的解析、绑定与展开。**这里是「值永不字符串插值」的执行点。**

   principles「执行与连接」：「值永远不字符串插值进 SQL——`:query` 用具名占位按
   prepared statement 执行。」
   sql-v1 conformance：「占位符与 `:params` 恰好互相覆盖，多、少均拒绝。」

   语句里唯一被字符串替换的东西是 `?` 标记本身，以及从 `:field_mapping` 解析出的
   **列名**（配置，不是值，且已过写入端校验）。任何来自调用方的值都走绑定。"
  (:require [clojure.string :as str]))

(def placeholder-pattern
  "具名占位 `:name`。`(?<!:)` 排除 `::` 开头——PostgreSQL 的 `::type` 强制转换
   不是占位符。"
  #"(?<!:):(\w+)")

(defn placeholders
  "语句里出现的占位名（按出现顺序，含重复）。"
  [query]
  (mapv second (re-seq placeholder-pattern (str query))))

(defn- param-key->name [k] (if (keyword? k) (name k) (str k)))

(defn declared-bindings
  "`:params` 归一成 `{占位名字符串 → 绑定}`。
   sql-v1：`:params` 是 `{占位名 → 属性kw 或 eql-param 名}`。
   全限定 kw = resolver 的 `:input` 属性；非限定 = 已声明的 eql-param 名。"
  [params]
  (reduce-kv (fn [acc k v]
               (assoc acc (param-key->name k)
                      (if (and (keyword? v) (namespace v))
                        {:from :input :attr v}
                        {:from :eql-param :name (keyword (param-key->name v))})))
             {}
             (or params {})))

(defn resolve-bindings
  "占位名 → 绑定，含 D2.1 的恒等缺省。

   「恒等绑定省略（D2.1：占位名 = 属性 local name 或参数名）」——所以一个没写进
   `:params` 的占位符，先按 local name 找 `:input` 属性，再按参数名找已声明的
   eql-param；都找不到就是不可解析。

   → `{:bindings {名 → 绑定} :unresolved [名…] :unused [名…]}`
   `:unused` = 写进 `:params` 却在语句里没有对应占位符的键（「多」）。"
  [{:keys [params input eql-params batch-placeholder]} query]
  (let [declared  (declared-bindings params)
        names     (distinct (placeholders query))
        by-local  (into {} (map (juxt name identity)) (or input []))
        declared-eql (set (map #(keyword (param-key->name %)) (keys (or eql-params {}))))
        resolve-1 (fn [n]
                    (or (get declared n)
                        (when-let [a (get by-local n)] {:from :input :attr a})
                        (when (contains? declared-eql (keyword n))
                          {:from :eql-param :name (keyword n)})))
        ;; batch 的 `:placeholder` 由 N 个父键绑定，不经 :params（sql-v1 batch 块）。
        names     (remove #(= % batch-placeholder) names)
        resolved  (into {} (keep (fn [n] (when-let [b (resolve-1 n)] [n b]))) names)]
    {:bindings   resolved
     :unresolved (vec (remove resolved names))
     :unused     (vec (remove (set (placeholders query)) (keys declared)))}))

(defn bound-input-attributes
  "语句真正按之绑值的 `:input` 属性集（E1 / D5.14）。

   故意走 `resolve-bindings` 而不是 `placeholders`：一个语句里写着 `:tenant_id`
   却既不在 `:input` 也不在 `:params` 里的占位符是**不可解析**的（写入端已由
   `:connector.source/placeholder-mismatch` 拒），把它算成「绑上了」会让核对
   在恰恰接错线的那一类模型上放行。

   **只取 `:from :input` 的绑定。** 绑到 `:eql-params` 的占位符是调用方自己
   选的收窄——D5.6 共识 2（D5.14 维持）说行级安全绝不绑调用方提供的值，
   「调用方自己挑的谓词等于没有谓词」。所以它不满足核对。

   batch 的 `:placeholder` 由 N 个父键绑定、不经 `:params`，`resolve-bindings`
   已把它剔除——它不是行级切分的绑定名。"
  [{:keys [params input eql-params batch-placeholder]} query]
  (into #{}
        (keep (fn [[_ b]] (when (= :input (:from b)) (:attr b))))
        (:bindings (resolve-bindings {:params params :input input
                                      :eql-params eql-params
                                      :batch-placeholder batch-placeholder}
                                     query))))

(defn render
  "语句 → `[sql v1 v2 …]`（next.jdbc 的 sql-params 向量）。

   `value-of` 是 `占位名 → 值`。`in-list` 是 `{占位名 [值 …]}`：该占位符展开为
   N 个 `?` 并绑定 N 个值——sql-v1 batch 的「`IN (:placeholder)` 展开为 N 个绑定」。
   空列表展开为单个 `NULL` 绑定：匹配零行，而不是让 `IN ()` 变成语法错误、
   更不是把 IN 整段抹掉造成一次无过滤全表扫描。

   **复合键的一项是一个向量**（owner 2026-09-07，doc 106 §106.14 第 14 条）：
   展开成行值 `(?, ?)`，整段即 `(a, b) IN ((?,?), (?,?))`。行值比较是 SQL-92
   语法，sqlite（3.15+）、PostgreSQL、MySQL、ClickHouse 都支持；这样一条语句
   仍然只发一次，`:limit` 的 per-parent 语义也不变。"
  [query {:keys [value-of in-list]}]
  (let [m   (re-matcher placeholder-pattern (str query))
        sb  (StringBuilder.)
        vs  (volatile! [])
        end (volatile! 0)]
    (while (.find m)
      (let [n (.group m 1)]
        (.append sb (subs (str query) @end (.start m)))
        (if-let [vals* (and in-list (find in-list n))]
          (let [vs* (vec (val vals*))
                vs* (if (seq vs*) vs* [nil])
                tuple? (sequential? (first vs*))]
            (if tuple?
              (do (.append sb (str/join ", "
                                        (map (fn [t]
                                               (str "(" (str/join ", " (repeat (count t) "?")) ")"))
                                             vs*)))
                  (vswap! vs into (mapcat vec vs*)))
              (do (.append sb (str/join ", " (repeat (count vs*) "?")))
                  (vswap! vs into vs*))))
          (do (.append sb "?")
              (vswap! vs conj (value-of n))))
        (vreset! end (.end m))))
    (.append sb (subs (str query) @end))
    (into [(.toString sb)] @vs)))

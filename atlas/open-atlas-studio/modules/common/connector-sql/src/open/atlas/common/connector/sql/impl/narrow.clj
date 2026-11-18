(ns open.atlas.common.connector.sql.impl.narrow
  "锚上收窄：eql-param 过滤、排序、分页。

   D2.8：「`:eql-params` 只做**锚上收窄**不做查询构建器」——所以这里只会往基础
   语句后面接 WHERE/ORDER BY/LIMIT，永远不重写它的 FROM/SELECT。
   sql-v1「eql-param 扩展键」：「无。过滤与排序属性经 `:field_mapping`（含恒等
   缺省）解析到列名；解析不到即体检拒绝（D2.20）。」

   列名进语句是字符串拼接，值一律绑定——列名来自已过写入端校验的配置，值来自
   调用方。这条界线就是「值永不字符串插值」。"
  (:require [clojure.string :as str]))

(def reserved
  "dialect-v1 §3：「保留调用名 `:limit` `:offset` `:order_by` `:sort_dir` 不得作为
   参数名声明」——它们是信封而不是过滤器，在这里单独处理。"
  #{:limit :offset :order_by :sort_dir})

(def ops
  "`:op` 词表 → SQL 运算符。表以外的 op 不落地（写入端已拒绝）。"
  {:eq "=" :ne "<>" :gt ">" :gte ">=" :lt "<" :lte "<=" :like "LIKE" :in "IN"})

(defn filters
  "已声明的 eql-param × 本次调用带来的值 → `[{:sql \"col = ?\" :values [v]} …]`。
   声明里没有的调用参数直接不参与——它不是「未知过滤器」，而是根本没有被授予
   的收窄口。"
  [declared-params call-params field-mapping]
  (into []
        (keep (fn [[pname spec]]
                (let [k (keyword (if (keyword? pname) (name pname) (str pname)))]
                  (when-not (reserved k)
                    (when-some [v (get call-params k (:default spec))]
                      (let [col (get field-mapping (:attribute spec))
                            op  (get ops (:op spec) "=")]
                        (when col
                          (if (= "IN" op)
                            (let [vs (if (sequential? v) (vec v) [v])]
                              {:sql (str col " IN (" (str/join ", " (repeat (max 1 (count vs)) "?")) ")")
                               :values (if (seq vs) vs [nil])})
                            {:sql (str col " " op " ?") :values [v]}))))))))
        (or declared-params {})))

(defn- order-clause
  "ORDER BY。列名只能来自 `:order :allowed` 解析出的列——调用方给的
   `:order_by` 是属性 kw，不是列名，更不是任意 SQL 片段。"
  [{:keys [allowed default]} call-params field-mapping]
  (let [requested (:order_by call-params)
        dir       (some-> (:sort_dir call-params) name str/upper-case)
        dir       (when (#{"ASC" "DESC"} dir) dir)
        [attr d]  (cond
                    (and requested ((set allowed) requested)) [requested (or dir "ASC")]
                    (seq default) [(first default) (str/upper-case (name (second default)))]
                    :else nil)]
    (when-let [col (and attr (get field-mapping attr))]
      (str " ORDER BY " col " " d))))

(defn- clamp-limit
  "`:limit {:default :max}`（D2.8：list resolver 必须声明 `:limit`）。
   调用方要多少都不越过 `:max`——上限是模型作者签的字，不是建议；越过就夹到
   上限，而不是拒绝，因为 `:limit` 是信封而非语义（超限拒绝那道纪律管的是
   EQL 四道边界，不是这里）。"
  [{:keys [default max]} requested]
  (when-let [n (or requested default)]
    (if (and max (> (long n) (long max))) (long max) (long n))))

(defn window
  "batch 路径的 **per-parent 窗口**（sql-v1「分页在 batch 上是 per-parent 窗口，
   不下推」）：不产 SQL，只产两个数交给 `spi/align-batch` 在取回后切片。

   夹取用的是与下推路径**同一个** `clamp-limit`——两条路径对「调用方要多少、
   作者签了多少」必须给出同一个答案，否则同一个 `:limit` 在 batch 与非 batch
   resolver 上含义就不同了。"
  [limit call-params]
  (let [lim (clamp-limit limit (:limit call-params))
        off (:offset call-params)]
    (when (or lim off)
      (cond-> {}
        lim (assoc :limit lim)
        off (assoc :offset off)))))

(defn apply-narrowing
  "基础语句 + 收窄 → `{:sql … :values [绑定值 …]}`。
   `opts`：`:declared-params` `:call-params` `:field-mapping` `:order` `:limit`
   `:paginate?`（batch 路径传 false——见 sql.clj 里 per-parent 窗口的说明）。"
  [base {:keys [declared-params call-params field-mapping order limit paginate?]}]
  (let [fs   (filters declared-params call-params field-mapping)
        base (str base)
        sql  (if (seq fs)
               (str base (if (re-find #"(?i)\bWHERE\b" base) " AND " " WHERE ")
                    (str/join " AND " (map :sql fs)))
               base)
        sql  (str sql (order-clause order call-params field-mapping))
        lim  (when (and paginate? limit) (clamp-limit limit (:limit call-params)))
        off  (when paginate? (:offset call-params))
        sql  (cond-> sql
               lim (str " LIMIT " (long lim))
               (and lim off) (str " OFFSET " (long off)))]
    {:sql sql :values (vec (mapcat :values fs))}))

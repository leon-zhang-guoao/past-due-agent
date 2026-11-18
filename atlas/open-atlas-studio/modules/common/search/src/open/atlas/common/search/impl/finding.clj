(ns open.atlas.common.search.impl.finding
  "search 的 finding 注册表与构造器。域 = `search.*`
   （finding-v1 §3「后续模块续接」）。

   同 toolkit / pool / studio-tools 的理由：各模块登记自己的域，谁也不去改内核
   那张表，否则 D1.8 的依赖方向就反过来了。

   本 ns 的 code 少得刻意：入参形状的错姓 `toolkit.*`，方言结构的错姓
   `dialect.*`。剩给 `search.*` 的只有索引这一层独有的判定。"
  (:require [open.atlas.common.dialect :as dialect]))

(def codes
  "已发布 :code 注册表（finding-v1 §6：产出未注册 code 是实现 bug）。"
  #{;; ── search.index ──────────────────────────────────────────────────
    ;; 索引的 revision 与调用方要查的 revision 对不上。**陈旧即弃**
    ;; （README 裁定 ④）：不带警告地用是错的——调用方拿到的会是一份对不上
    ;; 当前模型的答案，而它没有任何办法发现这一点。
    :search.index/stale
    ;; SQLite / FTS5 起不来。索引是派生数据，起不来就说起不来，
    ;; 不静默退化成线性扫描——那会让「搜不到」与「没建索引」不可区分。
    :search.index/unavailable
    ;; ── search.query ──────────────────────────────────────────────────
    ;; 查询切不出任何词（空串、纯标点）。
    :search.query/blank
    ;; 命中数超过本次调用的信封上限。CLAUDE.md「超限拒绝而非截断」：
    ;; 给前 N 条会让调用方以为自己看全了。
    :search.query/over-limit
    ;; ── search.scope ──────────────────────────────────────────────────
    ;; `search_attrs` 的 entity 不在模型里。回 `:none` 是错的——
    ;; 「这个 entity 里没有」与「没有这个 entity」是两个不同的答案。
    :search.scope/unknown-entity})

(defn make
  "构造并断言（finding-v1 §2 封闭键面）。opts 只收 :expected :got :refs :files。"
  [code severity path message & {:as opts}]
  (assert (contains? codes code) (str "unregistered finding code: " code))
  (assert (contains? #{:error :warning :info} severity))
  (assert (vector? path))
  (assert (string? message))
  (assert (every? #{:expected :got :refs :files} (keys opts))
          (str "finding key set is closed: " (pr-str (keys opts))))
  (merge {:code code :severity severity :path path :message message} opts))

(defn valid? [f]
  (and (map? f)
       (empty? (remove #{:code :severity :path :message :expected :got :refs :files} (keys f)))
       (contains? codes (:code f))
       (contains? #{:error :warning :info} (:severity f))
       (vector? (:path f))
       (string? (:message f))))

(def redact
  "不泄漏截断（finding-v1 §5）——复用 B0 导出的那一个，不另写。"
  dialect/redact)

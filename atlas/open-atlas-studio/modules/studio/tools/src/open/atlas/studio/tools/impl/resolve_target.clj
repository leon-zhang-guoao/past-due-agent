(ns open.atlas.studio.tools.impl.resolve-target
  "`resolve_target` —— **写目标闸门**，不是搜索的包装（README 裁定 ④ /
   principles「MCP 与工具面」）。

   ## 承重规则：安全 = 参数的缺席

   四个离散裁决 `:exact-unique` `:exact-ambiguous` `:fuzzy` `:none`，
   **只有 `:exact-unique` 带出 `:target` 与 `:action-params`**。principles 的原话：
   「拿着一个格式正确的 action 的 agent 一定会去跑它，所以安全不能活在参数旁边的
   一句警告里，只能是参数的缺席。」

   于是本 ns 的返回值形状是**按裁决分叉**的，不是「同一个 map 里多一个
   `:confident?` 布尔」——后者只要有人忘了读那个键，闸门就没了。

   ## 不设置信度浮点

   principles：「BM25 是语料内排序，同一个正确匹配这里 26 分那里 1.0 分，没有哪个
   阈值两次意味着同一件事；歧义也绝不靠排名打破。」本 ns 因此**不排序、不打分**，
   候选一律按身份字典序给出——一个稳定但明确无意义的顺序，读的人不会误当排名。

   ## 缩写表是输入（裁定 ⑤）

   `no` 在一个领域是 `number`，隔两个 namespace 就是 `purchase order`。表由 owner
   给，**默认空**。空表不是缺陷：不认识的缩写让解析落到 `:fuzzy` 或 `:none`，
   那是诚实的回答；替 owner 猜一张表才是缺陷，因为猜错的形态是把写目标解到另一个
   属性上，而那种损坏是静默的。

   principles 还要求「**同时展开比较的两侧**」：只展开查询侧，`\"po number\"` 对上
   `po_no` 就得靠子串巧合；两侧都展开之后，它们在同一个词面上相等或不等。

   ## 匹配层不在这里（B4.3 / common/search README 裁定 ③）

   标识符折叠、缩写表展开、词条 label/synonym 查找**曾经在本 ns 里各写一份**,
   B4.3 把它们抽进 `open.atlas.common.search`，本 ns 改为调用。理由是本仓库反复
   栽的那个坑：两份逐字节相同的实现（`redact`、档位词表）总有一天分叉，而分叉
   的那天没有人会知道（CLAUDE.md）。

   **抽走的只有匹配层**。四个裁决留在这里——读侧 `search_attrs` 的裁决词汇是
   直中/经词条/`:ambiguous`/`:none`（D7.12），与这里的四个**不许合并**：
   读侧错了是白看一眼，写侧错了是静默的模型损坏。"
  (:require [open.atlas.common.search :as search]
            [open.atlas.studio.tools.impl.finding :as f]))

(def verdicts
  "四个离散裁决，固定顺序。"
  [:exact-unique :exact-ambiguous :fuzzy :none])

(def default-max-candidates
  "候选清单的信封上限（principles：信封对每次调用都有界）。
   超限**拒绝**而不是截断——调用方正要靠这份清单判断歧义，
   给它前 N 条会让它以为自己看全了（CLAUDE.md）。"
  50)

;; ---------------------------------------------------------------------------
;; 候选索引
;; ---------------------------------------------------------------------------

(defn- object-ns
  "对象的所属 namespace（缩写覆盖表按它取）。取不到给 nil。"
  [t id]
  (case t
    :attribute (namespace id)
    :namespace id
    nil))

(defn- names-of
  "一个对象上所有**可被人称呼**的名字。

   属性带 `:term-context`（D2.27：「购买方客户」/「收货方客户」）——它正是为消歧
   而存在的槽，解析时不看它，等于把消歧机制关掉一半。
   绑了词条的属性，`:label`/`:synonyms` 来自词条（D2.26 绑定即封走语义）。"
  [terms {:pool/keys [type id entry]}]
  (concat
   ;; 标识符侧：类型决定「它的名字长什么样」，这一段是池对象特有的，不共用。
   (case type
     :attribute      [(str (symbol id)) (name id)]
     (:link)         nil
     [(str id)])
   ;; 语义侧：label / synonyms / `:term-context` 的查找**共用匹配层**。
   (search/semantic-names terms entry)))

(defn index
  "[池对象…] × 词条表 × 缩写表 → {归一化短语 #{[类型 id]…}}，
   外加每个对象的 revision（`:exact-unique` 要带出可执行参数）。"
  [objects terms abbrev]
  {;; **共用匹配层**：读侧 `search_attrs` 的精确名索引走的是同一个函数,
   ;; 所以两边对同一个输入词折叠出的候选集合逐字节相同（B4.3 验收断言）。
   :by-phrase (search/build-phrase-index
               (for [{:pool/keys [type id] :as o} objects]
                 {:id [type id] :ns (object-ns type id) :names (names-of terms o)})
               abbrev)
   :revisions (into {} (for [{:pool/keys [type id revision]} objects]
                         [[type id] revision]))})

;; ---------------------------------------------------------------------------
;; 裁决
;; ---------------------------------------------------------------------------

(defn resolve-target
  "查询词 → 四个离散裁决之一。

   `opts`：`:type` 限定对象类型（可 nil）、`:namespace` 指定缩写覆盖域、
   `:max-candidates` 信封上限。"
  [{:keys [by-phrase revisions]} query {:keys [type namespace max-candidates abbreviations]}]
  (let [limit  (or max-candidates default-max-candidates)
        folded (search/fold abbreviations namespace query)
        keep-  (fn [cands] (if type (filter #(= type (first %)) cands) cands))
        exact  (vec (keep- (search/exact-hits by-phrase folded)))
        over  (fn [cands]
                (f/make :tools.resolve/candidates-over-limit :error [:resolve_target]
                        "candidate list exceeds the per-call envelope; narrow the query or pass :type"
                        :expected (str "<= " limit) :got (str (count cands) " candidates")
                        :refs ["principles «MCP 与工具面»"]))]
    (cond
      ;; 唯一精确匹配——**只有这一支**带出目标与可执行参数。
      (= 1 (count exact))
      (let [[t id] (first exact)]
        {:verdict :exact-unique
         :query   query
         :target  [t id]
         ;; 直接拼进 `execute_action` 的入参。`expected_revision` 取此刻的读数：
         ;; 期间有人改过，池会以 `:pool.conflict/revision-mismatch` 拒绝（裁定 ④），
         ;; 这正是「照着哪个 revision 计划」该有的样子（principles）。
         :action-params {:type t :id id :expected_revision (get revisions [t id])}})

      ;; 精确但多个：**歧义绝不靠排名打破**，所以这里连排序都不做。
      (seq exact)
      (if (> (count exact) limit)
        {:findings [(over exact)]}
        {:verdict :exact-ambiguous :query query :candidates (vec exact)})

      :else
      ;; `:fuzzy` 用共用匹配层的词元子集匹配。**刻意不做编辑距离、不做 BM25**——
      ;; 它的用处只有一个：告诉调用方「大概是这些，但我不给你参数」。
      (let [fz (vec (keep- (search/subset-hits by-phrase folded)))]
        (cond
          (empty? fz) {:verdict :none :query query :candidates []}
          (> (count fz) limit) {:findings [(over fz)]}
          :else {:verdict :fuzzy :query query :candidates fz})))))

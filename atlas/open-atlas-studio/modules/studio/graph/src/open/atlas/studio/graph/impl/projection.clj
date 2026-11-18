(ns open.atlas.studio.graph.impl.projection
  "**scope projection v1 的全部实现**（docs/contracts/scope-projection-v1.md）。

   契约 §2 的十步固定算法在本 ns 里**一步一个函数**，函数名带步号（`step1-…`
   `step2-…` …），于是「第 4 步做了什么」在代码里是可以逐条对着契约读的。
   合并两步、或把某一步顺手挪进另一步的循环里，都会让下一个人无从核对——
   而这份契约的每一条都是一次拒绝或一次静默降级的分界。

   ## 纯函数（契约 §1 最后一句，graph README 裁定 ②）

   > 实现不得读取 latest、当前时间或池快照之外的状态。

   本 ns 的入口 `project` 收的是 **db 值**（Datahike 的不可变快照）而不是 conn：
   「读 latest」在类型上就做不到。此外不读时钟、不读环境变量、不生成随机数——
   `projection_test` 里有一条测试逐字扫本文件的源码来守这句话，因为「纯」是可测的。

   ## 输出顺序只由方言 writer 决定（第 10 步）

   本 ns 里凡是集合，一律落成 `sorted-map` 或按身份显式排序的向量。Datahike 的
   返回顺序、map/set 的迭代顺序、引用在菜谱里的排列**一处都不许泄漏进输出**——
   conformance 矩阵最后一行（打乱三种顺序，输出字节与 content-hash 不变）挡的
   就是「今天恰好排对了」。

   ## 与 B5a.1 的关系

   `register-graph!` 登记菜谱时也判过一次 selector 归属，那是**便利**：地盘可能在
   登记之后被释放或拆分，承重的是投影这一次。所以第 1、2 步在这里**独立实现**,
   不因为「登记时查过了」而省掉（graph README 裁定 ②）。"
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [open.atlas.common.artifact :as artifact]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.graph.impl.finding :as f]
            [open.atlas.studio.graph.impl.selector :as sel]
            [open.atlas.studio.graph.impl.store :as store]))

;; ---------------------------------------------------------------------------
;; 小工具：顺序、身份、引用键
;; ---------------------------------------------------------------------------

(defn- sorted-by-str
  "任意 map → 按键的 `pr-str` 字典序排的 sorted-map。
   键既有字符串（注册表）也有 kw（属性），`compare` 混排会抛，`pr-str` 不会。"
  [m]
  (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b)))) m))

(defn- attr-namespace [k] (when (qualified-keyword? k) (namespace k)))

(defn- tree-attrs
  "语法树（`:output` / `:returns`）里出现的全部全限定属性 kw。
   与 pool 的引用索引同一条思路：逐个位置取，不做深度启发。"
  [tree]
  (->> (tree-seq coll? seq tree) (filter qualified-keyword?) set))

(defn- ref-key
  "一个钉版本引用的身份：`[限定名 版本]`。版本一律归一成字符串 `\"v3\"`——
   菜谱写的是 `\"v3\"`，制品信封（D4.4）写的是整数 `3`，两处必须能对上,
   否则同一个版本会被当成两个节点，而 R3.4 的钻石检查正靠这个身份。"
  [qn version]
  [qn (if (integer? version) (str "v" version) (str version))])

(defn- envelope-ref-key [r]
  (ref-key (str (:workspace r) "/" (:graph r)) (:version r)))

;; ---------------------------------------------------------------------------
;; 第 1 步：菜谱点名的对象必须存在
;; ---------------------------------------------------------------------------

(defn- registry-selector?
  "词表域 selector（契约 §7）与 namespace selector 的分界：**限定注册表 ID 带
   `/`，namespace 名不带**。`common.audit/*` 与 `common.audit/g_pii` 是前者,
   `sales.*` 与 `sales.order` 是后者。"
  [s]
  (str/includes? s "/"))

(defn- pool-namespace-names
  "快照里存在的 namespace 名。取 `:namespaces` 的键**与**属性 kw 的 namespace 段
   的并集：零匹配判定不该因为某个 namespace 只有属性、还没写描述条目而误判。"
  [model]
  (into (set (keys (:namespaces model)))
        (keep attr-namespace (keys (:attributes model)))))

(defn- pool-registry-ids [model]
  (into #{} (mapcat #(keys (get model %))) [:units :terms]))

(defn- member-index
  "池快照里每一组对象的现有身份集合——第 1 步拿它判「清单点名的东西在不在」。"
  [model]
  ;; D2.29（owner 裁定 2026-09-02）：这里曾有第九组 `:navigations`，喂它的是
  ;; namespace 条目上那份已退役的登记。join 属性按 `:attributes` 点名，所以它
  ;; 没有独立的成员集合——留一个恒空的 `:navigations` 只会让菜谱以为还有一类
  ;; 东西可以往里写。
  {:namespaces  (set (keys (:namespaces model)))
   :attributes  (set (keys (:attributes model)))
   :resolvers   (set (map :id (:resolvers model)))
   :mutations   (set (map :id (:mutations model)))
   :links       (set (map (juxt :from :to) (:links model)))
   :terms       (set (keys (:terms model)))
   :units       (set (keys (:units model)))
   :metrics     (set (keys (:metrics model)))})

(defn- step1-members
  "**第 1 步**：菜谱点名的每个对象都必须在池快照里存在（契约 §1 末条、§2.1）。

   不存在就**拒绝**，不静默跳过。「我列了它却没出现在结果里」与「我根本没列它」
   必须是两句不同的话——静默跳过会让一张图在某个对象被删之后悄悄少掉一块,
   而消费方无从知道少了什么。这与第 4 步拒绝 mixed-output 是同一条纪律。"
  [members model]
  (let [have (member-index model)]
    (vec
     (for [kind (sort (keys have))
           id   (sort-by pr-str (get members kind))
           :when (not (contains? (get have kind) id))]
       (f/make :scope/unknown-member :error [kind]
               "this member is not in the pool snapshot; a recipe never silently drops what it names"
               :got (pr-str id)
               :refs ["scope-projection-v1 §1"])))))

;; ---------------------------------------------------------------------------
;; 第 2 步：从快照取清单所列的对象
;; ---------------------------------------------------------------------------

(defn- step2-local
  "**第 2 步**：取清单所列的 namespace 描述与 attribute。

   **attribute 自动带入它的 namespace**（契约 §2.2）：一个属性没有它的 entity
   描述是无法解释的，要求作者把 namespace 也逐个列一遍只会制造一种新的漏列。"
  [model members]
  (let [listed-ns (set (:namespaces members))
        attrs     (select-keys (:attributes model) (:attributes members))
        ns-names  (into listed-ns (keep attr-namespace) (keys attrs))]
    {:namespaces ns-names
     :attributes attrs
     ;; 清单可直接列入词条——字典 graph 的用法（契约 §2.7）。
     :registry-selected
     (into {} (for [kind [:units :terms]]
                [kind (set (get members kind))]))}))

;; ---------------------------------------------------------------------------
;; （原第 3 步「钉版本引用」随引用机制退役而删除 —— D3.17；契约 2026-08-27 修订）
;; ---------------------------------------------------------------------------


(defn- merge-keyed
  "**第 9 步**：按限定身份合并。逐字节相同的重复项去重；同一身份内容不同以
   `:scope/object-conflict` 拒绝。第 10 步的注册表词条走的是同一条规则,
   所以它只有这一份实现。"
  [path maps]
  (reduce
   (fn [acc m]
     (reduce
      (fn [{:keys [entries findings] :as a} [id entry]]
        (let [prev (get entries id)]
          (cond
            (nil? prev)   (assoc-in a [:entries id] entry)
            (= prev entry) a
            :else
            (update a :findings conj
                    (f/make :scope/object-conflict :error path
                            "two contributions carry the same identity with different content"
                            :got (pr-str id)
                            :refs ["scope-projection-v1 §2.9"])))))
      acc m))
   {:entries (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b)))) :findings []}
   maps))

;; ---------------------------------------------------------------------------
;; 第 4 步：resolver / mutation 的纳入条件
;; ---------------------------------------------------------------------------

(defn- exec-io
  "一个执行对象的 [输出属性集 输入属性集]。resolver 是 `:output` / `:input`,
   mutation 是 `:returns` / `:params` 的键。

   **语法树里的 kw 全是属性**（D2.29，owner 裁定 2026-09-02）：分支键是 join
   属性，叶子键是标量属性，两者同住 `:attributes`。所以从前那条「先剔掉
   navigation」的理由没了。

   两侧仍与**池里已登记的属性**求交，但求的是另一件事：投影只搬得动池快照里
   真有的对象。一个 `:output` 里写了却没登记的 kw 不是「本图没选它」，而是一处
   属性漂移，由切版本体检（D2.2 的存在性闭包）逐条点名——在这里把它算成
   mixed-output，报出来的会是一句指错方向的话。"
  [kind e known]
  (if (= :resolver kind)
    [(set/intersection (tree-attrs (:output e)) known)
     (set/intersection (set (filter qualified-keyword? (:input e))) known)]
    [(set/intersection (tree-attrs (:returns e)) known)
     (set/intersection (set (filter qualified-keyword? (keys (:params e)))) known)]))

(defn- step4-executables
  "**第 4 步**：可用属性 = 本地所选 ∪ 引用带入。

   - 全部 output 都在**本地所选属性**中 → 候选；
   - 候选的全部 input 必须在**可用属性**中，否则 `:scope/unclosed-input`；
   - **同时产出选中与未选中属性 → `:scope/mixed-output` 拒绝**，
     绝不删掉部分 output 后纳入（那会造出一个「看起来存在、但少了几块」的执行对象,
     而消费方无从知道少了什么——D3.11 挡排除规则的同一条理由）。"
  [kind entries local-attrs available known]
  (reduce
   (fn [acc e]
     (let [id        (:id e)
           [out in]  (exec-io kind e known)
           inside    (set/intersection out local-attrs)
           outside   (set/difference out local-attrs)]
       (cond
         (empty? inside) acc                      ; 与本图无关，跳过（不是错误）

         (seq outside)
         (update acc :findings conj
                 (f/make :scope/mixed-output :error [kind id]
                         ;; 8.4.21 裁定十五 ②: the first sentence says what a
                         ;; person has to do; the mechanism follows it.
                         (str "this object produces attributes this graph's recipe does not name, "
                              "so it is left out whole — add the ones listed under :got to the "
                              "recipe (see :repair) and it is included. It is never trimmed down "
                              "to the named half")
                         :got (pr-str (vec (sort-by pr-str outside)))
                         ;; **The delta that fixes it, next to the refusal**
                         ;; (8.4.14). The D2.29 migration wrote join attributes
                         ;; into the pool and no recipe, and every pre-D2.29
                         ;; graph then failed its cut here with a refusal that
                         ;; never mentioned a recipe — a reader could not get
                         ;; from this finding to the edit that fixes it.
                         ;;
                         ;; It rides in `:expected` and not in a `:recipe-delta`
                         ;; key of its own because finding-v1 §2 closes the key
                         ;; set; see this module's README note and the P7 report.
                         :expected {:recipe-delta
                                    {:add {:attributes (vec (sort-by pr-str outside))}}}
                         :refs ["scope-projection-v1 §2.4" "8.4.14"]))

         (seq (set/difference in available))
         (update acc :findings conj
                 (f/make :scope/unclosed-input :error [kind id]
                         "an input attribute of this object is outside the available attributes (local ∪ referenced)"
                         :got (pr-str (vec (sort-by pr-str (set/difference in available))))
                         :refs ["scope-projection-v1 §2.4"]))

         :else (update acc :included conj e))))
   {:included [] :findings []}
   entries))

;; ---------------------------------------------------------------------------
;; 第 5 步：source 闭包
;; ---------------------------------------------------------------------------

(defn- step5-sources
  "**第 5 步**：纳入候选对象**直接引用**的 source。source 不因 namespace 前缀
   自行扩张；未被纳入执行对象引用的 source 不进入结果。每个 source 引用必须
   唯一且存在，否则拒绝。"
  [execs available-sources]
  (let [wanted (into (sorted-set) (keep :source-ref execs))
        missing (remove #(contains? available-sources %) wanted)]
    {:sources  (into (sorted-map) (select-keys available-sources wanted))
     :findings (vec (for [s (sort missing)]
                      (f/make :scope/unclosed-source :error [:sources]
                              "an included resolver/mutation names a source that is not in the projection scope"
                              :got s :refs ["scope-projection-v1 §2.5"])))}))

;; ---------------------------------------------------------------------------
;; 第 6 步：link
;; ---------------------------------------------------------------------------

(defn- step6-links
  "**第 6 步**：两端都在可用属性里才进；一端缺失以 `:scope/unclosed-link` 拒绝。
   **不得生成悬空 link**——两端都不在的 link 与本图无关，跳过。"
  [links available]
  (reduce
   (fn [acc l]
     (let [ends #{(:from l) (:to l)}
           in   (set/intersection ends available)]
       (cond
         (empty? in) acc
         (= 2 (count (set/intersection ends available)))
         (update acc :links conj l)
         :else
         (update acc :findings conj
                 (f/make :scope/unclosed-link :error [:links]
                         "one endpoint of this link is outside the available attributes; a dangling link is never emitted"
                         :got (pr-str (vec (sort-by pr-str (set/difference ends available))))
                         :refs ["scope-projection-v1 §2.6"])))))
   {:links [] :findings []}
   links))

;; ---------------------------------------------------------------------------
;; 第 7 步：两张根注册表 —— 引用即入闭包
;; ---------------------------------------------------------------------------

;; **`:scope/unclosed-registry` 从合法的池里触发不到**（2026-08-27 勘察）：一个绑着
;; 不存在词条的属性，池的写面与导入面都会先以
;; `:dialect.invariant/unregistered-reference` 拒掉它（D2.26）。这条检查因此是
;; **防守性的第二道**，守的是「快照本身已经不一致」——半份备份、或将来按投影范围
;; 拼校验时漏了一类。它没有测试，因为写不出一个能触发它的合法池；把它删掉换来的是
;; 一条以后没人守的路，所以留着，并把这句话写在这里而不是留一条测不到的断言。
(defn- registry-demand
  "被纳入内容对注册表的**引用**：属性的 `:unit` / `:term`（契约 §7）。"
  [attrs _namespaces]
  {:units  (into #{} (keep :unit (vals attrs)))
   :terms  (into #{} (keep :term (vals attrs)))})

(defn- registry-contributions
  "一张注册表的全部来源（本地快照 + 每个引用带入）→ `{ID #{互不相同的内容…}}`。
   **不做 `merge`**：merge 是「后者覆盖前者」，而契约 §10 要的正相反——同 ID
   内容不同必须以 `:scope/object-conflict` 拒绝。覆盖会让一次冲突变成一次静默
   改写，产物里坐着一份没人选过的词条。"
  [kind maps]
  (reduce (fn [acc m]
            (reduce (fn [a [id e]] (update a id (fnil conj #{}) e)) acc m))
          {} maps))

(defn- step7-registries
  "**第 7 步**：三张根注册表闭包规则同一条——**引用即入闭包**。被纳入内容所指的
   词条自动进入；未被引用的词条**不进**该 graph（不能从 instance 的外部注册表
   补齐）；悬空引用结构化拒绝。菜谱的词表域前缀 selector 额外显式圈入。

   `:replaced-by` 也算引用：一个进了闭包的词条指着一个不在闭包里的词条，产物里
   就是一处悬空引用，而下游读它的人只会看到「这个 term 被替换成了一个不存在的
   东西」。所以按同一条规则闭包，而不是把它当描述文本。

   **第 10 步的「注册表词条按限定 ID 合并，同 ID 内容不同拒绝」也在这里落地**——
   它与第 9 步是同一条规则，只是作用在注册表上。"
  [contributions demand explicit]
  (reduce
   (fn [acc kind]
     (let [pool-entries (get contributions kind)
           seeds        (into (get explicit kind #{}) (get demand kind))]
       (loop [todo (vec (sort seeds)) picked (sorted-map) errs []]
         (if (empty? todo)
           (-> acc
               (assoc-in [:registries kind] picked)
               (update :findings into errs))
           (let [[id & more] todo
                 variants (get pool-entries id)]
             (cond
               (contains? picked id) (recur (vec more) picked errs)

               (empty? variants)
               (recur (vec more) picked
                      (conj errs (f/make :scope/unclosed-registry :error [kind]
                                         "a referenced registry entry is not present in this snapshot / projection scope"
                                         :got id :refs ["scope-projection-v1 §2.7" "D2.25"])))

               (> (count variants) 1)
               (recur (vec more) picked
                      (conj errs (f/make :scope/object-conflict :error [kind]
                                         "two contributions carry the same registry id with different content"
                                         :got id :refs ["scope-projection-v1 §2.10"])))

               :else
               (let [e (first variants)]
                 (recur (vec (concat more (when-let [r (:replaced-by e)] [r])))
                        (assoc picked id e) errs))))))))
   {:registries {} :findings []}
   [:units :terms]))

;; ---------------------------------------------------------------------------
;; 第 8 步：metric
;; ---------------------------------------------------------------------------

(defn- step8-metrics
  "**第 8 步**：metric 随其 `:entity` 的 namespace 纳入；引用带入的 metric 随引用
   合并（在第 9 步）。纳入 metric 的 `:dimensions` 必须全部位于可用属性，否则以
   `:scope/unclosed-metric` 拒绝。"
  [metrics local-ns available]
  (reduce
   (fn [acc [id m]]
     (if-not (contains? local-ns (:entity m))
       acc
       (let [missing (set/difference (set (:dimensions m)) available)]
         (if (seq missing)
           (update acc :findings conj
                   (f/make :scope/unclosed-metric :error [:metrics id]
                           "a dimension of this metric is outside the available attributes"
                           :got (pr-str (vec (sort-by pr-str missing)))
                           :refs ["scope-projection-v1 §2.8"]))
           (assoc-in acc [:metrics id] m)))))
   {:metrics (sorted-map) :findings []}
   metrics))

;; ---------------------------------------------------------------------------
;; join 属性的目标闭包
;; ---------------------------------------------------------------------------

(defn- close-join-targets
  "一条 join 属性的 `:target` 指着另一个 namespace（D2.29）。目标不在投影里,
   产物里就是一条走不到头的路：调用方按 catalog 嵌进去，落地的 entity 不存在。

   **契约 §2 没有写这一条**（十步里 link 有、join 没有）。这里按 link 的同一条
   规则处理——两端都在才进，缺一**拒绝**而不是悄悄删掉——并把这一处记在报告里
   交 owner 裁定。静默删掉是唯一确定不对的选项（CLAUDE.md：拒绝而非截断）。

   D2.29 之前同一条规则作用在 namespace 条目的 navigation 上，code 叫
   `:scope/unclosed-navigation`；判定没变，被判的对象换了住处。"
  [attributes present-ns]
  {:findings
   (vec (for [[akw target] (sort-by (comp pr-str key)
                                    (dialect/join-attributes {:attributes attributes}))
              :when (not (contains? present-ns target))]
          (f/make :scope/unclosed-join :error [:attributes akw :target]
                  "the target namespace of this join attribute is outside the projection; a dangling join is never emitted"
                  :got target
                  :refs ["scope-projection-v1 §2.6" "D2.29"])))})

;; ---------------------------------------------------------------------------
;; 入口
;; ---------------------------------------------------------------------------

(defn- vec-by
  "向量型顶层键的确定性顺序（第 10 步：算法不得依赖任何返回顺序）。"
  [kf xs]
  (vec (sort-by #(pr-str (kf %)) xs)))

(defn project
  "**scope projection v1 的入口**。

   ```clojure
   (project db \"sales/core\" resolved)
   ```

   - `db`      —— 池快照（**值，不是 conn**：读 latest 在类型上就做不到）；
   - `qn`      —— graph 限定名，菜谱从快照里的登记取（同一事务点）；
   - `resolved`—— 已解析的钉版本引用集合，每条
     `{:graph \"core-team/core\" :version \"v3\"|3 :content <content.edn 的信封>
       :content-hash \"sha256:…\" :artifact-digest \"sha256:…\"}`。
     **是集合不是序列语义**：顺序不进结果。

   成功 → `{:model 规范化模型根 map :edn 规范形文本 :references 引用清单 :findings []}`;
   失败 → `{:findings [带对象定位的结构化错误…]}`，**不产半份模型**（finding-v1 §2）。"
  [db qn]
  (let [g (store/find-graph db qn)]
    (if (nil? g)
      {:findings [(f/make :graph.registry/unknown :error [:graph]
                          "no such graph is registered" :got (str qn))]}
      (let [members (:graph/members g)
            model   (pool/as-dialect db)

            ;; ── 第 1 步 ─────────────────────────────────────────────────
            e1 (step1-members members model)]
        (if (seq e1)
          {:findings (f/sort-findings e1)}
          ;; ── 第 2 步 ───────────────────────────────────────────────────
          (let [local       (step2-local model members)
                local-attrs (set (keys (:attributes local)))
                ;; 引用退役之后「本地属性」与「可用属性」是同一个集合（D3.17）。
                ;; 两个名字都留着，是因为第 4 步用它们表达两件不同的事：output
                ;; 必须落在**本图选中的**属性里，input 必须落在**可解析的**属性里。
                available   local-attrs]
            (let [;; ── 第 4 步 ───────────────────────────────────────
                  known (set (keys (:attributes model)))
                  rs (step4-executables :resolver (:resolvers model) local-attrs available known)
                    ms (step4-executables :mutation (:mutations model) local-attrs available known)
                    execs (concat (:included rs) (:included ms))

                    ;; ── 第 5 步 ───────────────────────────────────────
                    src (step5-sources execs (:sources model))

                    ;; ── 第 6 步 ───────────────────────────────────────
                    lk (step6-links (:links model) available)

                    ;; ── 第 8 步 ───────────────────────────────────────
                    local-namespaces (select-keys (:namespaces model) (:namespaces local))
                    mt (step8-metrics (:metrics model) (:namespaces local) available)

                    ;; ── 第 9 步：按身份合并七类对象 ────────────────────
                    ;; 引用退役之后每类只有**一份**贡献，但合并仍然走同一条
                    ;; 路径：去重与 `:scope/object-conflict` 是契约 §2.9 的语义,
                    ;; 不因为「现在只有一个来源」就绕过去——绕过去的那天来源
                    ;; 变回两个，没有人会记得把它加回来。
                    m-attrs (merge-keyed [:attributes] [(:attributes local)])
                    m-ns    (merge-keyed [:namespaces] [local-namespaces])
                    m-src   (merge-keyed [:sources]    [(:sources src)])
                    m-mtr   (merge-keyed [:metrics]    [(:metrics mt)])
                    m-res   (merge-keyed [:resolvers]
                                         [(into {} (map (juxt :id identity) (:included rs)))])
                    m-mut   (merge-keyed [:mutations]
                                         [(into {} (map (juxt :id identity) (:included ms)))])
                    m-lnk   (merge-keyed [:links]
                                         [(into {} (map (juxt (juxt :from :to) identity) (:links lk)))])

                    ;; ── 第 7 步（在合并之后）─────────────────────────────
                    reg-contrib (into {} (for [k [:units :terms]]
                                           [k (registry-contributions k [(get model k)])]))
                    rg (step7-registries
                        reg-contrib
                        (registry-demand (:entries m-attrs) (:entries m-ns))
                        ;; 显式圈入 = 菜谱直接列进来的词条（字典 graph 的用法，
                        ;; 契约 §2.7）。被属性 `:unit`/`:term` 引用到的词条不必列,
                        ;; 由 `registry-demand` 自动带入。
                        (:registry-selected local))

                    nav (close-join-targets (:entries m-attrs) (set (keys (:entries m-ns))))

                    findings (f/sort-findings
                              (vec (concat (:findings rs) (:findings ms) (:findings src)
                                           (:findings lk) (:findings mt) (:findings rg)
                                           (:findings m-attrs) (:findings m-ns)
                                           (:findings m-src) (:findings m-mtr)
                                           (:findings m-res) (:findings m-mut)
                                           (:findings m-lnk)
                                           (:findings nav))))]
                (if (seq findings)
                  {:findings findings}
                  ;; ── 第 10 步：交唯一方言 writer；writer 的字典序是唯一输出顺序 ──
                  (let [out (cond-> {}
                              (seq (:entries m-attrs)) (assoc :attributes (sorted-by-str (:entries m-attrs)))
                              (seq (:entries m-ns))    (assoc :namespaces (into (sorted-map) (:entries m-ns)))
                              (seq (:entries m-src))   (assoc :sources    (into (sorted-map) (:entries m-src)))
                              (seq (:entries m-mtr))   (assoc :metrics    (into (sorted-map) (:entries m-mtr)))
                              (seq (get-in rg [:registries :units]))
                              (assoc :units (get-in rg [:registries :units]))
                              (seq (get-in rg [:registries :terms]))
                              (assoc :terms (get-in rg [:registries :terms]))
                              (seq (:entries m-lnk))
                              (assoc :links (vec-by (juxt :from :to) (vals (:entries m-lnk))))
                              (seq (:entries m-res))
                              (assoc :resolvers (vec-by :id (vals (:entries m-res))))
                              (seq (:entries m-mut))
                              (assoc :mutations (vec-by :id (vals (:entries m-mut)))))

                    ;; ── 本地那一半，**单独再拼一份** ────────────────────────
                    ;;
                    ;; `:model` 是**合并后**的（R3.2：物化 env 时把被引用版本的对象并进
                    ;; 同一个 index，规划器需要完整的图才能规划多跳路径）。但**制品不能
                    ;; 装这一份**：D4.1 说制品是「自完整的*声明*，不是自完整的*装载单位*」
                    ;; ——它说尽「我是什么、我需要什么」，但**不含被引用版本的内容**;
                    ;; D4.2 说被引用的版本各自成为独立目录，**不内联**。
                    ;;
                    ;; 内联的后果 D4.2 已经写死：同一个 `core@v3` 在十个引用方的制品里
                    ;; 各存一份，**它们的 content-hash 各不相同**，于是「这两张图用的是不是
                    ;; 同一个 core」变成不可判定——而 D4.4 靠 hash 判引用同一性正是压在
                    ;; 这上面。
                    ;;
                    ;; 所以两份都给：`:model` 供编译，`:local` 供密封。**同一次投影算出来的
                    ;; 两个视图**，不是两条投影路径——后者就是「两份闭包规则迟早对不上」。
                    local-out
                    (let [own-res (into {} (map (juxt :id identity) (:included rs)))
                          own-mut (into {} (map (juxt :id identity) (:included ms)))
                          own-lnk (into {} (map (juxt (juxt :from :to) identity) (:links lk)))
                          ;; 注册表：只留**本地贡献**的那些条目（引用带入的随引用走）
                          own-reg (fn [k] (let [contributed (set (keys (get model k)))]
                                            (into (sorted-map)
                                                  (filter (comp contributed key)
                                                          (get-in rg [:registries k] {})))))]
                      (cond-> {}
                        (seq (:attributes local)) (assoc :attributes (sorted-by-str (:attributes local)))
                        (seq local-namespaces)    (assoc :namespaces (into (sorted-map) local-namespaces))
                        (seq (:sources src))      (assoc :sources    (into (sorted-map) (:sources src)))
                        (seq (:metrics mt))       (assoc :metrics    (into (sorted-map) (:metrics mt)))
                        (seq (own-reg :units))    (assoc :units  (own-reg :units))
                        (seq (own-reg :terms))    (assoc :terms  (own-reg :terms))
                        (seq own-lnk) (assoc :links     (vec-by (juxt :from :to) (vals own-lnk)))
                        (seq own-res) (assoc :resolvers (vec-by :id (vals own-res)))
                        (seq own-mut) (assoc :mutations (vec-by :id (vals own-mut)))))]
                    {:model      out
                     :edn        (dialect/write-dialect out)
                     ;; **密封用这一份**（D4.1 / D4.2：制品不含被引用版本的内容）。
                     :local      local-out
                     :local-edn  (dialect/write-dialect local-out)
                     ;; 引用机制已退役（D3.17）：一张图只装自己 workspace 的对象,
                     ;; 要别人的内容就 import 一份进来。这个键**保留且恒为空**——
                     ;; 制品的 manifest 与装载方仍然读它，回一个空清单是「这张图
                     ;; 不依赖任何别的图」这句话，而键消失是另一句话。
                     :references []
                     :findings   []})))))))))

(ns open.atlas.studio.version.impl.cut
  "**切版本 = 投影 → 闭包 → 体检 → 密封**，四步串行，**前一步不过不进下一步**
   （README 裁定 ②）。

   ## 为什么必须串行

   体检跑在**投影产物**上：投影没过，根本没有一份「这个版本长什么样」可体检;
   闭包没过（钻石冲突），R3.8 的档位取严算出来的每一个上限都建立在一个不成立
   的前提上。而密封在最后，于是**任何一步不过，一个字节的制品都不会落盘**——
   这是退出条件 1 的形状：拒绝不是「写完再说这次不算」。

   ## 密封不自己写（README 裁定 ①，[D4.10-a](../../../../../../../../docs/design/40-artifacts.md#d410-a-编码与密封是两个纯函数b3-与-b5-共用)）

   `encode-content` 与 `seal-graph` 是 B3 已交付的两个纯函数，D4.10-a 明说
   「B3 与 B5 共用」。本 ns **一行序列化都不重写**：重写等于给 content-hash 造
   第二个来源，而 content-hash **就是**内容身份（D4.3）。两个来源迟早给出两个
   身份，那时「这两个版本是不是同一个东西」变成不可判定。

   ## 发布上下文显式入参

   `:cut-at` / `:cut-by` 由调用方传入，本 ns **不读时钟、不读当前用户**——
   D4.10-a 逐字要求（「纯函数不得读取时钟、当前用户、环境变量或上一版本，
   调用者取到这些值后作为参数传入，测试才能完整复现」）。"
  (:require [open.atlas.common.artifact :as artifact]
            [open.atlas.common.connector.spi :as spi]
            [open.atlas.common.dialect :as dialect]
            [open.atlas.studio.graph :as graph]
            [open.atlas.studio.pool :as pool]
            [open.atlas.studio.version.impl.closure :as closure]
            [open.atlas.studio.version.impl.finding :as f]
            [open.atlas.studio.version.impl.health :as health]
            [open.atlas.studio.version.impl.store :as store]))

(def ^:private request-keys
  #{:graph :resolved :artifact-root :cut-at :cut-by :tier :opts})

(def quality-not-measured
  "`report.edn` 的 `:quality`。密封拒绝空 map（「空 map 与『没算』不可区分」，
   见 `impl.seal/check-quality`），而**质量指标是 B5b.2 的交付**——所以这里写
   一个说得出口的键，而不是交一张空表，也不是编一个覆盖率出来。"
  {:not-measured "quality metrics are a B5b.2 deliverable (D2.11 展示那一半)"})

(defn- shape-findings [{:keys [graph resolved artifact-root cut-at cut-by tier] :as req}]
  (cond-> []
    (seq (remove request-keys (keys req)))
    (conj (f/make :cut.version/malformed :error []
                  "cut-version request key set is closed"
                  :expected (vec (sort (map str request-keys)))
                  :got (vec (sort (map str (remove request-keys (keys req)))))))

    (not (and (string? graph) (some? (graph/parse-qualified-name graph))))
    (conj (f/make :cut.version/malformed :error [:graph]
                  "the graph is addressed by its two-segment qualified name"
                  :got (f/redact graph) :refs ["D3.10"]))

    (not (string? artifact-root))
    (conj (f/make :cut.version/malformed :error [:artifact-root]
                  "an artifact root path is required; artifact paths never resolve against the process cwd"
                  :got (f/redact artifact-root) :refs ["D4.8"]))

    ;; **不给缺省**：读时钟就不是纯函数了，而「没传就用现在」会让测试复现不出
    ;; 同一份 manifest（D4.10-a）。
    (not (and (string? cut-at) (seq cut-at)))
    (conj (f/make :cut.version/malformed :error [:cut-at]
                  "cut-at is explicit publication context; this module never reads the clock"
                  :refs ["D4.10-a"]))

    (not (and (string? cut-by) (seq cut-by)))
    (conj (f/make :cut.version/malformed :error [:cut-by]
                  "cut-by is explicit publication context; this module never reads the current user"
                  :refs ["D4.10-a"]))

    (not (or (nil? tier) (contains? #{:core :standard} tier)))
    (conj (f/make :cut.version/malformed :error [:tier]
                  "graph tier is :core or :standard (default :standard)"
                  :got (f/redact tier) :refs ["D2.11"]))

    (not (or (nil? resolved) (sequential? resolved)))
    (conj (f/make :cut.version/malformed :error [:resolved]
                  "resolved references are a collection of already-resolved pinned versions"
                  :got (f/redact resolved) :refs ["scope-projection-v1 §2.3"]))))

(defn- reference-attributes
  "闭包每个节点带进来的属性集合（D5.5 清单要用它）。从**已解析引用的
   `content.edn`** 里取，不去按名字猜哪个属性是引用带入的。"
  [resolved cl]
  (let [idx (into {} (for [r resolved]
                       [[(str (:graph r)) (closure/normalize-version (:version r))] r]))]
    (into {} (for [[k _] (:nodes cl)]
               [k (set (keys (:attributes (:objects (:content (get idx k))))))]))))

(defn- object-revisions
  "这一版的每个对象在池里是第几版：`{[类型 id] revision}`（E3，artifact-v1 §4.1）。

   **对象集从投影里取**，不另算：`:local` 就是这一版密封下去的那一份，而
   `pool/dialect->objects` 是「方言 → [类型 id]」的**唯一**那处映射（菜谱清单
   即对象集，D3.19）。另写一遍分组规则，就是第二套语义（D1.8）。

   revision 从**同一个 `db`** 上读——投影用的也是它，所以这份指针说的是投影
   看见的那个事务点，而不是「读到这一行时池已经变成什么样」。

   这些数字**不进 `content.edn`**：同一份内容从不同 revision 切出必须得到同一个
   content-hash（D4.3 / artifact-v1 §5）。它们进的是 provenance，被
   `artifact-digest` 覆盖而不被 content-hash 覆盖——这正是「发布上下文不改内容
   身份」那句话的形状。"
  [db local]
  (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b))))
        (keep (fn [{:keys [pool/type pool/id]}]
                (when-let [r (:pool/revision (pool/object db type id))]
                  [[type id] r]))
              (pool/dialect->objects local))))

(defn- unknown-connector-findings
  "Sources whose `:type` has no connector registered on this host.

   doc 104 §8.17.7 A leaves `spi/package-compat` pure: it lists such a type with
   a nil version, because \"I do not know this type\" is a fact the installing
   side has to see. Writing that nil into a manifest is another matter — the
   sealed `:requires` would then be computed with `declared-files`'s default
   `[]`, i.e. the package would under-declare its files and no one would be
   told. CLAUDE.md: 超限、冲突、缺失都拒绝而非截断/猜测——静默的降级会让调用方
   对着少掉的东西推理出错误结论. So the CALLER refuses, and nothing is sealed.

   A host reaches this by forgetting to require a connector ns, so the finding
   names both the type and the source that uses it."
  [model]
  (vec (for [[id cfg] (sort-by (comp str key) (:sources model))
             :let [t (:type cfg)]
             :when (nil? (spi/contract-version t))]
         (f/make :package/unknown-connector :error [:sources (str id) :type]
                 (str "source " (pr-str (str id)) " has type " (pr-str t)
                      ", and this host has no connector registered for it; "
                      "sealing would write a package that under-declares what it needs")
                 :got (f/redact t) :refs ["doc 104 §8.17.7 A"]))))

(defn cut-version!
  "切一个版本。成功 → `{:version n :content-hash … :artifact-digest … :dir File
   :closure … :health … :findings []}`；任一步不过 → `{:findings […]}`，
   **且制品目录不存在**。"
  [conn req]
  (let [shape (shape-findings req)]
    (if (seq shape)
      {:findings (f/sort-findings shape)}
      (let [{:keys [graph resolved artifact-root cut-at cut-by tier opts]} req
            db (pool/db conn)
            g  (graph/graph db graph)]
        (if (nil? g)
          {:findings [(f/make :cut.version/malformed :error [:graph]
                              "no such graph is registered" :got graph :refs ["D3.10"])]}
          ;; ── 第 1 步：投影（B5a.2 的纯函数，这里不重新实现，也不绕过）──
          (let [proj (graph/project db graph)]
            (if (seq (:findings proj))
              {:findings (vec (:findings proj))}
              ;; ── 第 1.5 步：**制品出生前的方言闸门** ───────────────────
              ;; 投影用 `write-dialect` 产文本，而 writer **不校验键面**——它
              ;; 逐字打印交给它的东西。`encode-content` 又把 `:local` 原样嵌进
              ;; `:objects`（D4.2 明说不定义第二套形状）。于是从模型到制品这
              ;; 一整段没有一道闸门看过键面：一个已退役的键（`:security` 是
              ;; 真发生过的那个）会静默写进制品，直到**目标 server 装载时**才
              ;; 炸成 `:artifact.model/rejected`——错得最远、诊断最贵的那一点。
              ;;
              ;; 所以在这里读一遍自己刚写的东西。往返本来就该成立（D2.12
              ;; 规范形即唯一形），不成立就是投影产出了非法方言，而那**必须
              ;; 在制品落盘之前**说出来：制品切出即不可变，一份非法制品一旦
              ;; 上架，它的坏是永久的。findings 直接用方言自己的（本模块的
              ;; `known` 已把 `dialect/finding-codes` 列为回落来源），因为
              ;; 它们逐条点名是哪个对象的哪个键。
              (if-let [bad (seq (filter #(= :error (:severity %))
                                        (:findings (dialect/read-dialect (:local-edn proj)))))]
                {:findings (f/sort-findings (vec bad))}
                ;; ── 原第 2 步「闭包」随引用机制退役而消失（D3.17）────────
                ;; 一张图只装自己 workspace 的对象，没有传递引用要解析、没有
                ;; 钻石冲突要仲裁（R3.1/R3.3/R3.4 一并作废）。空闭包**照样传给
                ;; 体检**：它的键面是 D4.9 的 `health-input-keys`，形状不因
                ;; 「现在恒为空」而改——改了它，制品格式就跟着变。
              (let [cl {:nodes {} :order [] :reference-attributes #{} :findings []}]
                ;; ── 第 2 步：体检（D2.11 的拒绝那一半）─────────────────
                (let [hc (health/check (:model proj) cl (or opts {}) (:local proj))]
                  (if (f/errors? (:findings hc))
                    {:findings (vec (:findings hc))}
                    ;; ── 第 3 步：密封 ───────────────────────────────
                    (let [[ws nm] (graph/parse-qualified-name graph)
                          n     (store/next-number db graph)
                          ;; 引用清单恒为空（D3.17）。**照旧写进制品**——
                          ;; 「这张图不依赖任何别的图」是一句话，键消失是另一句。
                          refs  []
                          ;; **密封的是 `:local`，不是 `:model`**（D4.1 / D4.2）。
                          ;; 引用退役之后两者内容相同，但两个名字仍然分开：
                          ;; `:model` 答「装起来合不合法」，`:local` 答「我是什么」。
                          ;; 合成一个的那天，制品会悄悄开始装别人的东西。
                          enc   (artifact/encode-content (:local proj) refs)]
                      (if (seq (:findings enc))
                        {:findings (vec (:findings enc))}
                        ;; `:tier` **不进 provenance**。这里的 tier 是 D2.11 的
                        ;; **体检严格度**（`:core` / `:standard`），不是访问档位
                        ;; ——同名不同物，而访问档位已随 owner 裁定 2026-08-27
                        ;; 整套退役、`provenance-keys` 也随之封闭掉了这个键。
                        ;; 严格度影响的是「这次切版本过不过」，不是制品的身份,
                        ;; 所以它本来也不该被封进 manifest。
                        ;; ── package 前置：本机不认识某个 source type 就不密封 ──
                        ;; 见 `unknown-connector-findings`：`package-compat` 保持
                        ;; 纯（可以回 nil），拒绝是调用方的事，而拒绝要发生在
                        ;; **密封之前**——制品切出即不可变。
                        (if-let [unknown (seq (unknown-connector-findings (:local proj)))]
                          {:findings (f/sort-findings (vec unknown))}
                        (let [prov {:workspace ws :graph nm :version n
                                    :cut-at cut-at :cut-by cut-by
                                    :object-revisions (object-revisions db (:local proj))}
                              ;; doc 104 §8.17.7 A: the `:package` block is
                              ;; computed over the model **being sealed**
                              ;; (`:local`), not over the pool — what the
                              ;; installing machine must have is a property of
                              ;; the content, and reading anything else here
                              ;; would let the manifest describe a model this
                              ;; version does not contain.
                              ;;
                              ;; The name comes from the graph registration's
                              ;; one rule (`graph/package-name-for`), and its
                              ;; refusal lands HERE rather than at export:
                              ;; the manifest is sealed once, and export only
                              ;; re-reads what was sealed.
                              pkg-name (graph/package-name-for g)
                              package (when (:name pkg-name)
                                        {:name     (:name pkg-name)
                                         :requires (spi/package-requirements (:local proj))
                                         :compat   (spi/package-compat (:local proj))})
                              sealed (when package
                                       (artifact/seal-graph
                                        (:content-edn enc) (:content-hash enc)
                                        prov (:health hc) {} quality-not-measured package))]
                          (if (seq (:findings pkg-name))
                            {:findings (vec (:findings pkg-name))}
                          (if (seq (:findings sealed))
                            {:findings (vec (:findings sealed))}
                            (let [coords {:workspace ws :graph nm :version n}
                                  w (artifact/write-artifact! artifact-root coords
                                                              (:files sealed))]
                              (if (seq (:findings w))
                                {:findings (vec (:findings w))}
                                (do
                                  (store/record! conn
                                                 {:graph graph :number n
                                                  :content-hash (:content-hash enc)
                                                  :artifact-digest (:artifact-digest sealed)
                                                  :cut-at cut-at :cut-by cut-by})
                                  {:version         n
                                   :content-hash    (:content-hash enc)
                                   :artifact-digest (:artifact-digest sealed)
                                   :dir             (:dir w)
                                   :files           (:files sealed)
                                   :closure         cl
                                   :health          (:health hc)
                                   :findings        []}))))))))))))))))))))

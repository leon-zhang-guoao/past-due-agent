---
title: "Atlas Semantic Contract 2.0"
description: "Semantic 契约 2.0：平台唯一可读的契约版本（owner 2026-09-19）。本文自足——1.4 及 V1 §3–§6 仍沿用的内容已并入，不再引用旧版本。"
---

# Atlas Semantic Contract 2.0

状态：**平台唯一可读版本**（owner 裁定 2026-09-19，[任务书](../design/tasks/contract-upgrade-and-retirement.md)）。
依据 [doc 109 §109.8](../design/109-pathom-parity.md)（owner 2026-09-18）分五步放开定义层，
其中四步已落地（§3、§4、§5、§6），partial 边界（§8）等第二台真实实例。

legacy 方言文档读法与 1.0–1.4 读法已于 2026-09-19 **全部退役**，平台不提供迁移工具：
描述符缺失、`atlas-legacy`、或 `atlas-semantic` 且版本不是 `2.0.0` 的文档一律拒绝
`{:code :contract/version-retired :path [:contract]}`，message 指向唯一升级路径
`open-atlas-graph-builder` skill（读旧方言直接产出 2.0 文档）。其它 family 拒绝
`:contract/unsupported`。任何入口都不会静默按 2.0 读一份旧文档。

旧版本正文归档在 [archive/](archive/)，只作决策历史，不再是实现入口。

**条款编号对照**（本文 2026-09-19 按任务书重排；代码注释与连接器契约里写的旧编号按此表读）：
原 §2 输入形态 → **§3**；原 §3 params 与 batch → **§4**；原 §4 多 Resolver → **§5**；
原 §5 partial → **§8**；原 §6 union 与递归 **不变**；原 §7「与 1.4 的兼容」删除。

## 1. 目标与 descriptor

一份 Semantic Contract 文档是**一张 Graph 的完整定义**：每个 Entity、Resolver、Operation 和
Term 只有一个 Graph owner，即本文件的 `graph/id`。跨 Graph 使用显式 `:graph/dependencies`，
发布时把根 Graph 和完整传递依赖作为互相独立、不可变的快照放入同一个 Graph Package。

Graph 只声明逻辑 Source Slot 和 adapter mapping。数据库地址、API base URL、实例、密码、token
与证书属于部署环境的 Source Registry，永远不进入 Contract 或 Graph Package。

```clojure
{:contract/family "atlas-semantic"
 :contract/version "2.0.0"}
```

一份文档必须声明这三项 capability（它们描述本节的单 owner / Source Slot / 内嵌闭包语义）：

```clojure
:graph/single-owner-v1
:deployment/source-slot-v1
:artifact/embedded-closure-v1
```

并继续声明实际使用的 connector、dependency、query、metric 与本文各节的 `*-v2` 能力。
强制能力从内容**推导**并检查已声明：缺声明拒绝 `:contract/capability-undeclared`；宿主不支持
任一声明能力拒绝装载 `:contract/capability-unsupported`，不能降级执行。

### 1.1 唯一入口与完成边界

| 入口 | 输入 → 输出 | 保证 |
|---|---|---|
| `semantic-contract/read-document` | EDN 文本 → document / findings | 单个有界文档（4 MiB）、描述符判定、完整结构和引用校验 |
| `validate-document` | document + Connector validators → document/findings | Graph membership、ownership、语义和执行定义、能力声明一致 |
| `write-document` | 合法 document → canonical EDN | 复用既有 canonical writer；失败不写出部分文档 |
| `verify-closure` | document + 精确 dependency snapshot → document/findings | 版本定位、内容 hash、循环、子快照各自完整 |
| `check-support` | document + 宿主能力集合 → findings | 缺任何声明能力即拒绝 |
| `semantic-artifact/encode-candidate` | document + closure → 三文件包和 digest | 不可变候选内容；不写文件、不激活 |
| `read-candidate` / `read-runtime` | 三文件包 + 外部 digest + closure + 宿主能力 → document | 完整性、身份、契约、闭包与能力复验 |

Public schema：`semantic-contract/document-schema-v2`、`semantic/vocabulary-schema-v2`、
`semantic-execution/execution-schema-v2`、`semantic-protocol/*-schema`。三份 schema **各自独立
定义**，不再由旧版本 schema 派生。返回结果只有在成功时才带 document、execution、record 或
response；错误不得返回一个被悄悄裁剪后的成功对象。Schema validation 是定义验证，Connector
validator 是实现结构验证，compile-complete 是编译完整性验证；三者均不能自行授予用户权限。

## 2. 结构

完整可执行样例：[semantic-v2.edn](../../modules/common/dialect/fixtures/semantic-v2.edn)（测试
案例，不是已发布 Graph）；带 terms 与 query examples 的完整示例：
[orders-semantic-contract-2.0.edn](../guides/examples/orders-semantic-contract-2.0.edn)。

```clojure
{:contract    {:contract/family "atlas-semantic" :contract/version "2.0.0"}
 :workspace   {:workspace/id "support-dev"}
 :graph       {:graph/id "support" :graph/label "Support" :graph/exposure :restricted
               :graph/entities ["support.case"] :graph/resolvers ["lookup"]
               :graph/operations [] :graph/terms [] :graph/dependencies []
               ;; 可选：:graph/query-examples :graph/usage-guide :graph/query-budget
               }
 :vocabulary  {...}      ; §2.1
 :execution   {...}      ; §2.2
 :ownership   {:entities {"support.case" "support"} :resolvers {"lookup" "support"}
               :operations {} :terms {}}
 :publication {:release/version "1.0.0" :source/revision 1
               :required-capabilities #{:semantic/core-v1 :connector/sql
                                        :graph/single-owner-v1 :deployment/source-slot-v1
                                        :artifact/embedded-closure-v1}}}
```

所有顶层字段必填、map 封闭。Entity、Resolver、Operation、Binding、Source、Graph、Workspace、
Term 使用非空字符串 ID；Attribute 使用全限定 keyword。label 是展示名称，不替代 ID。

`graph/entities`、`graph/resolvers`、`graph/operations`、`graph/terms` 必须与本文件对应 registry
的键完全一致（`:contract/graph-membership`）；所有 ownership value 必须等于本文件的 `graph/id`
（`:contract/ownership`）。`:workspace` 仅标识 authoring 来源。Graph release 是不可变标签，禁止
latest/head/main/current/draft；revision 可为非负整数或非空外部 revision ID。

### 2.1 词汇（Vocabulary）

- 每个 Attribute 唯一归属一个 Entity，Entity ID 等于 Attribute namespace；完整闭包中不允许
  无主或多主 Attribute（`:semantic/ownership`）。
- Entity identity 是非空、无重复的 Attribute vector，成员必须存在、为标量、non-null。复合
  identity 的所有分量共同标识记录。
- Attribute 必填 label、type、nullable?；description 可选。可选 metadata：format、unit、values、
  synonyms、listed?、deprecated?、replaced-by、term。单位、替代属性和 Term 引用必须存在。
- 类型：string/integer/decimal/boolean/date/datetime/json/ref。`:ref` 必须有目标——
  `:attribute/target`（单目标）**或** `:attribute/targets`（多目标，§6.1），cardinality 为
  one 或 many（缺省 one）；标量不得携带 target/cardinality（`:semantic/relationship`）。
- Term 描述共享含义，不产生导航、类型 coercion 或权限。Entity 可带 `:entity/notes`，Term 可带
  `:term/format`，Alias / Equivalence 可带 `:*/description`。
- Alias 明确单向 from→to，端点必须存在、不同且签名相容。Equivalence 明确双向；成员必须声明同一
  Entity scope 和同一 canonical identity component。条件引用 `:references`
  （`:reference/from :reference/to :reference/direction :reference/on-null :omit`）：from 可空、
  to 是目标 Entity 的单一 identity，能力 `:semantic/conditional-reference-v1`。
- 记录：完整 record 与 projection 分开验证；未请求字段缺省；null 只在 nullable 时合法；
  `false`、`0`、空字符串和空集合不会被改写成 null。内部类型 BigDecimal / LocalDate / Instant，
  wire 用 ISO 字符串。详见 [Vocabulary](semantic-vocabulary-v1.md)、
  [记录值](semantic-record-values-v1.md)。

### 2.2 执行（Execution）：Resolver / Operation / Binding / Source

```text
Resolver(:read) 或 Operation(:write)
       → publication selection 中唯一 Binding
       → Binding 自有 implementation + input/output mapping
       → Source 的逻辑身份、Connector kind 和声明能力
       → 宿主注入 endpoint / credential / database privileges
```

Definition 必填 label、effect、input、output、result/cardinality。`:input` 见 §3；`:output` 为
EQL 风格字段树（§6 增加两种节点），全部引用必须存在，join 子树的 namespace 必须等于 ref 目标
Entity（owner 规则）。Resolver 和 Operation ID 不得在同一闭包冲突。写操作不参与自动属性求解。

数量由语义定义决定：Resolver one/many；Operation none/one/many。none 要求空 output；many 要求
一个根集合锚，且要么有分页与排序定义、要么有 `:collection` 块（§7）。one 表示最多一条，没找到
按 not-found 表达。定义可带 `:curated?`（作者标记）、`:filters`（§7）、`:params` 与 `:batch?`
（§4）、`:priority`（§5，只限 read）、`:idempotency`（`:request-key` / `:natural`）。

Binding 的 `:binding/for` 为 `[:resolver id]` 或 `[:operation id]`；Source / effect /
implementation / input-map / output-map 必填，`:binding/parameter-map` 可选（§4、§7）。每个
Definition 在 selection 中恰有一个所选 Binding；所有提交的备选 Binding 也必须合法。

Source 仅包含 `:source/kind`、`:source/effects` 与可选 `:source/policy`（doc 107 §107.3 的
语义半边：`:max_retries :retry_on :status-map :batch`，能力 `:source/policy-v1`）。endpoint、
password、credential reference 归 deployment。Binding implementation 由对应 Connector 的封闭
schema 验证；缺 validator 或 validator 拒绝，不可发布。含外部数据访问的 Graph 可以声明一个或
多个逻辑 Source Slot；`derived` 和 `static` 不需要外部 Source；纯语义 Graph 可以没有 Slot。

**Binding 的物理名由连接器裁定**（owner 2026-09-18）。执行层只核对 `:binding/input-map` /
`:binding/output-map` 的**键**集合是否与定义的 `:input` 根 / `:output` 属性一致；**值**的
含义归各连接器契约：

| connector | input-map 值 | output-map 值 |
|---|---|---|
| `sql` | `:query` 的占位符名 | 结果列名 |
| `api` | 槽名，**必须等于** `"ns/name"` | 槽名，**必须等于** `"ns/name"` |
| `derived` | 槽名，**必须等于** local name `(name attr)` | 同左 |
| `static` | 槽名，`"ns/name"` | 同左 |

api binding 的 **`:field_mapping` 必填**（read 与 write）。**API write binding**：
`:binding/effect :write` 的 api binding 走 api-v1 mutation 规则——`:method` 限
POST/PUT/PATCH/DELETE；`:json_body`（map）可与任一写方法同现；`:batch`、`:result-root`、
`:transforms`、`:context` 禁止；output-map 为 `{}` 或 `:field_mapping` 产出属性的槽名。细则见
[api-v1「Semantic Operation binding」](connectors/api-v1.md#semantic-operation-bindingowner-裁定-2026-09-18)。

**API read binding 的批量**：`:binding/implementation :batch {:parent-key :child-fk :placeholder
:separator :chunk-size}`，`:parent-key` 对应 resolver 输入根，执行器对编号去重、按 chunk-size
分组请求、按输入顺序回填；2.0 下定义须同时写 `:batch? true`（§4.2）。上游失败怎么读由
`:status-map {400..599 → 档}`（binding 的 `:binding/implementation :status-map`，或 Source slot
`:source/policy :status-map` 给缺省、`:binding/implementation :policy` 逐条覆盖）声明，能力
`:connector/api-status-map-v1`，它蕴含 `:query/batch-isolation-v1`；`:isolate-statuses` 单独出现
时按等价 `:status-map` 改写并报 `:info :connector.source/superseded-key`，与 `:status-map` 同现即
拒绝（[doc 107 §107.5](../design/107-api-v1-revision.md)）。

**校验的形状**：每个连接器的 binding 校验按条款给 finding，`:code` 在 `:connector.binding/*`
下，`:path` 相对 binding（执行层前置 `[:bindings bid]`），`:message` 只说期望、不回显值，全部
收集不在第一条停下；连接器内部抛异常由执行层报 `:execution/checker-crashed`。详细键表：
[Execution](semantic-execution-v1.md)。

### 2.3 Graph Package 与 Artifact

Package family 为 `atlas-semantic-graph`，package version `1`。Package 必须：内嵌 root 和每个
传递 child 的完整文档；保留每个 child 的独立 Graph ID、release version 与 content hash；精确
校验 dependency locator 和 digest；拒绝缺失依赖、环、重复 locator、跨 Graph asset ID 冲突和
缺失 Term；不 flatten child，也不在运行时向 registry 拉取依赖；不含 deployment Source、
credential、部署地址、password 或 token。任一内嵌文档描述符退役即整包拒绝
`:contract/version-retired` at `[:graphs]`。

Dependency lock 每项必须是 graph/id + 精确 release/version + content/hash。canonical document
hash 识别语义内容，Artifact content hash 识别封装内容，三文件 digest 识别完整包；三者不能混用。
新语义使用 **Artifact format 2**：`content.edn`
`{:artifact/format-version 2 :document <完整契约文档>}`；`manifest.edn`（format-version、
content-hash、graph/id、release/version、可选 deployment 坐标）；`report.edn`（format-version、
content-hash、validation=`:contract-validated`）。report 仅证明契约校验。Reader 先验证外部
digest，再读三文件；少文件、未知字段、身份/hash 不符、未支持能力、依赖漂移或退役描述符均拒绝。
详见 [artifact v1](artifact-v1.md)。

### 2.4 运行与部署

部署配置用 `[graph-id source-slot-id]` 映射到 operator-owned Source Registry 中的 Source ID。
多个 Graph Slot 可以指向同一个已登记 Source 实例；这不会改变 Graph 的 owner 或 Source Slot
边界。激活前必须完成 package integrity、Contract、capability、每个 Source Slot 的 binding
type、权限与连接检查。新候选全部通过后才原子切换 serving pointer。任一检查失败时旧版本继续
服务。Runtime 每请求固定 snapshot；依赖不转授权限。

读取授权维度为 Graph Internal/Restricted：Internal 仍要求合法租户、用户和 Agent；Restricted
需要明确 user/group grant。写入必须同时有 Graph 访问资格与具体 operation 授权；grant、组缓存、
吊销和 token ledger 是 live 状态，不写入 Artifact。UI 隐藏或 MCP 不展示不构成安全控制。

发布顺序：完整契约校验 → Connector 实现校验 → 依赖锁定和 ownership → capability 检查 → 严格
编译 → 回归 → 生成不可变候选 → 发布批准 → Server 加载复验。

### 2.5 Graph 使用指南（可选）

Graph 可在 `:graph/usage-guide` 内提供随版本固定的使用说明。该字段及其各项内容均为可选：
缺少说明不阻止草稿保存、发布候选、Package 构建或 Studio 切版本。提供的字段仍校验类型，示例
引用仍需存在（`:contract/usage-guide-example`）。

```clojure
:graph/usage-guide
{:guide/purpose "按订单编号查询订单事实，支持内部分析"
 :guide/asset-scope "订单对象与相关只读查询；完整资产清单从模型生成"
 :guide/applicability "已知订单编号；业务含义以属性和 Term 定义为准"
 :guide/limitations ["查询样例不是运行验收证据" "本 Graph 不执行订单修改"]
 :guide/composition "依赖按 graph/dependencies 的版本及 hash 解析；新增等价关系需业务确认"
 :guide/examples ["order-by-id"]}
```

五类内容分别为业务目的、资产范围、适用范围与限制、使用示例、组合与依赖说明；示例用
`:example/id` 引用本 Graph 已有 `:graph/query-examples`。`usage-catalog` 从 registry 派生本地
资产清单，指南不重新维护一份资产 ID。指南是说明数据，不是 Agent 指令或权限授予；禁止填入
实际 endpoint、凭据、Source 实例配置。

### 2.6 Query Examples

`:graph/query-examples` 为可选向量，每项封闭：`:example/id :example/title :example/description
:example/eql`（可读为一个非空向量的 EDN 文本）`:example/entity :example/parameters
:example/requires :example/expected {:result/cardinality :result/fields}`。id 不得重复
（`:contract/query-example-duplicate`）；`:example/requires` 必须是文档已声明能力的子集
（`:contract/query-example-capability`）。

## 3. Input / Output 与 Parameters（2026-09-22）

Input/Output 定义 Resolver 依赖及 Graph 路径规划。Input 必填，语法为属性 keyword 或必填嵌套输入 `{ref-attr [children]}`。嵌套输入声明 `:execution/input-v2`；根必须唯一且存在于词汇，子树遵守目标实体归属规则。binding/input-map 恰好映射输入根。

optional Input 描述 `{:attribute a :optional? true}` 已禁止，返回 `:execution/optional-input-retired`，不能通过宽松 Connector adapter 绕过。已有 optional 执行控制迁移到 :params 与 binding/parameter-map；真正的身份/依赖仍是必填 Input。

Parameters 控制本次执行，可 optional，不参与依赖或路径规划。Input 放 EQL 的 :entity；Parameters 放属性/Join 参数。运行前缺少必要 Input 无可达路径时拒绝源读取；optional Parameters 省略和提供均须验证。

嵌套输入仍先由规划器求子树再传给 Resolver；SQL/API 不支持嵌套表值输入的限制不变。

Builder Babashka 仅检查 Graph 文档定义（EDN、版本、实体/属性、依赖与交叉规则），不打包/加载 Connector 检查器或执行依赖。Connector 物理绑定、连接、执行由 Studio/Server 和运行时测试验证；--strict 不扩大离线范围。

## 4. `:params` 与 `:batch?`

两个独立 capability：`:execution/params-v2`、`:execution/batch-v2`。用到就必须声明。

### 4.1 `:params`：自由参数

`:filters`（§7）是**受控子集**——每个参数绑一个属性、七个 operator，问题语法（doc 106）只认它。
`:params` 是给来源自己的查询参数（搜索关键字、时间窗、node scope）的：

```clojure
:params {:keyword {:type :string  :required? true  :description "全文检索词"}
         :since   {:type :datetime :description "只看此刻之后的事件"}
         :scope   {:type :string  :allowed ["standalone" "cluster"] :default "standalone"}}
```

| 键 | 规则 |
|---|---|
| 参数名 | 非限定 keyword；不得是保留字 `:limit :offset :order_by :sort_dir`（`:execution/reserved-parameter`）；**不得与 `:filters` 的键重叠**（`:execution/parameter-collision`） |
| `:type` | 必填；取值为属性类型词表去掉 `:ref` 与 `:json`：`:string :integer :decimal :boolean :date :datetime` |
| `:required?` | 可选，只写 `true` |
| `:description` | 可选非空字符串；建议必写，它是消费面看到的唯一说明 |
| `:allowed` | 可选，非空向量，元素类型须与 `:type` 一致；**只对 `:string :integer :boolean` 三种类型允许**，`:decimal :date :datetime` 参数不得写 `:allowed`（`:execution/parameter-value`）；调用值不在其中拒绝 |
| `:default` | 可选，类型须与 `:type` 一致；与 `:required? true` 互斥；**与 `:allowed` 并存时必须 ∈ `:allowed`**（`:execution/parameter-value` at `:default`） |

**只有 Resolver 可以有 `:params`**（owner 2026-09-18）：dialect 的 Mutation 条目没有 `eql-params`，
Operation 参数留给后续版本。`:params` 只影响取数，不影响返回形状。

**与 Binding 的关系**：`:binding/parameter-map` 的键集合 == `(∪ (keys :filters) (keys :params))`。
定义有 `:params` 时 parameter-map **必填**；物理名不得重复（`:execution/parameter-map`）。没有
`:params` 的定义，parameter-map 可选，有则键集合 == filters 键。

**执行语义**：`:params` 编译为 dialect 的 `eql-params` 条目 `{:type :required? :description
:default :allowed}`，**没有** `:attribute` 与 `:op`；调用方按参数名传值，gate 做三项检查：未声明的
参数、缺必填、不在 `:allowed`，都在进来源前拒绝。gate **不做**调用值的类型检查（登记为后续独立
事项）。cursor 签名包含 `:params` 的值，与 filters 相同。

**API 来源的物理名**：api connector 按参数名寻址模板占位符（`{keyword}`），编译器从不写 `:field`。
对 api 来源，parameter-map 的值**必须等于参数名**，写别的拒绝（`:connector.binding/parameter-slot`，
adapter 校验）；nifi 仍按自己的 allowed 名单读物理名。

**API 批量与参数互斥**：api connector 的批量执行路径不接收 eql-params，所以同一个 api Resolver
**不得同时**声明 `:batch? true` 与 `:params`（`:execution/batch-params-unsupported` at
`[target :params]`）。拒绝而非静默丢参。

### 4.2 `:batch?`：批量是定义层语义

「这个 resolver 可以按一组输入求值」是语义声明，不是 HTTP 或 SQL 细节：

```clojure
:batch? true
```

| 规则 | |
|---|---|
| 只允许 `:read` 且 `:result/cardinality` 为 `:one` 或 `:many` | `:execution/batch-shape` at `[target :batch?]` |
| 只写 `true`；不写即不批量 | 与 `:curated?` 同一写法纪律 |
| Binding 的物理 `:batch` 块（`:parent-key :child-fk :placeholder :chunk-size :isolate-statuses`）**原样留在绑定层** | 分块大小是来源配置，不是语义 |
| 定义 `:batch? true` ⇔ 所选 Binding 有 `:batch` 块 | 任一方向不一致拒绝 `:execution/batch-mismatch`，path 落在缺的那一侧 |
| `:batch` 块的 `:parent-key` 必须是定义 `:input` 的根 | `:execution/batch-mismatch` at `[:bindings bid :binding/implementation :batch :parent-key]` |

备选（未选中）Binding 不参与对账。**lowering**：dialect 模型不加新键；引擎继续以来源 `:batch`
块的存在决定 `::pco/batch? true`。`:batch?` 的作用是**让定义层说出它、并与绑定层对账**；消费面
（MCP 描述、问题语法）从定义读它，不再翻 Binding。

## 5. 同一属性多 Resolver 与 `:priority`

capability：`:execution/priority-v2`。

### 5.1 允许重叠，要求可判定

两个 Resolver 输出同一属性是**允许**的，且必须**可判定**：

```clojure
:resolvers {"customer-by-cache" {... :output [:customer/name :customer/tier] :priority 10}
            "customer-by-erp"   {... :output [:customer/name :customer/tier :customer/credit]}}
```

| 规则 | code / path |
|---|---|
| `:priority` 可选，非负整数，缺省 0；数值越大越优先 | schema |
| 两个 Resolver 的**顶层**输出键（标量键与 join 根键）有交集时，二者 `:priority` **必须不同** | `:execution/priority-tie` at `[[:resolver id] :priority]`（两侧都报） |
| 只有 `:read` Resolver 参与；Operation 的 `:returns` 不算产出 | — |
| 重叠的属性是同一个词汇条目，类型与 cardinality 由词汇决定，不可能不一致 | — |
| join 子树里的叶子不算「产出」（那是子实体自己的属性） | — |

没有重叠的 Resolver 可以不写 `:priority`；写了也合法。

**capability 的派生**：任一 Resolver 写了 `:priority`，或任两个 Resolver 顶层输出有交集，派生
`:execution/priority-v2`。没有排名的重叠本来就是 tie，被拒。

**Studio 切版本体检**：D2.11 的 `multiple-producers` 对 `:priority` 不同的一对 Resolver **豁免**；
priority 相同或缺省的仍按原判拒绝。

### 5.2 执行语义

- 规划器在多个候选里按 `:priority` 从高到低取；被选中的 Resolver **失败**（来源失败、超时）或
  **没有给出**该属性时，依次回退到下一个候选（Pathom OR 节点语义）。回退不是重试：同一个 Resolver
  不会被跑两次。
- 一次查询里发生了回退，`:issues` 里加一条 `:severity :info` 的 `:engine.execution/fallback`，
  `:path` 是属性路径，`:message` 只点名 Resolver **id**。首选 Resolver 的失败本身以
  `:engine.execution/failed`（一条 `:severity :error` 的 issue，带 `:resolver-id` 与
  `:error/category`）一并带出，不因回退成功而消失。
- **回退成功的答案是完整的**：`:query/status` 仍为 `:ok`，不标 `:partial`；两条 issue 只是解释。
- 审计两处带出实际运行的 Resolver 序列：`:statements :by-resolver` 回执与 `:resolver-trace`。
- **行级安全逐 Resolver 生效**：`bound-attributes` 与 rowsec wiring 的检查对每个候选独立执行，
  一个候选过不了检查就整张图拒绝发布——回退不能绕过授权。
- cache 规则不变。

### 5.3 与 Binding 的关系与 lowering

绑定层**不变**：每个 Resolver 仍恰有一个所选 Binding。同一属性两条来源路径就是两个 Resolver、
两个 Binding；制品里每条路径各自可审计。dialect 模型 resolver 加可选键 `:priority`；引擎写
`::pco/priority`。

## 6. 输出：union 与递归

两个独立 capability：`:execution/output-union-v2`、`:execution/output-recursion-v2`。

### 6.1 词汇：多目标 ref

`:ref` 属性可以有**多目标**：

```clojure
:ticket/subject {:attribute/type :ref :attribute/label "主体"
                 :attribute/targets #{"device" "person"}}   ; ≥ 2 个 Entity，与 :attribute/target 互斥
```

| 规则 | code |
|---|---|
| `:attribute/targets` 是 ≥2 个已声明 Entity 的集合；与 `:attribute/target` 二选一 | `:semantic/relationship` |
| 每个目标 Entity 的 `:entity/identity` 必须**恰有一个**属性（union 分支靠它判别） | `:semantic/union-identity` at `[:attributes a :attribute/targets e]` |
| 多目标 ref 只能出现在 `:output` 的 union 节点里；写成普通 join 拒绝 | `:execution/output-shape` |

### 6.2 `:output` 的两种新节点

```clojure
:output [:ticket/id
         {:ticket/subject {"device" [:device/id :device/name]      ; ④ union：键是 Entity id
                           "person" [:person/id :person/email]}}
         {:group/children 3}]                                       ; ⑤ 递归：值是深度上限
```

| 形态 | 规则 |
|---|---|
| ④ union | 单键 map，键是多目标 ref 属性，值是 `{entity-id 子树}`；**分支键集合 == `:attribute/targets`**，少一个拒绝（`:execution/union-branches`），多一个也拒绝；每个分支子树按该 Entity 的 owner 规则校验，且**必须含该 Entity 的 identity 属性**（`:execution/union-identity-required`）。分支子树可以再含 ③④⑤。 |
| ⑤ 递归 | 单键 map，键是 `:ref` 属性，值是整数深度 `1..16`；键的 `:attribute/target` **必须等于**本层的 owner Entity（自引用，`:execution/recursion-target`）；深度 >16 或 ≤0 拒绝（`:execution/recursion-depth`）。递归节点的形状 = 本层 Entity 的输出形状（含本层的其他节点），到深度为止。 |
| 根 | `:many` 定义的单一根 join 仍必须是普通 join，**不能是 ④ 或 ⑤**；`:one` 定义的顶层可以有 ⑤（`:execution/output-shape`）。 |

### 6.3 与 Binding 的关系

- `:binding/output-map` 的键集合 == 全部输出属性，含 union 各分支子树的属性；**递归键本身算
  输出属性，须出现在 output-map**，键以下不新增（它复用本层已映射的属性）。
- 校验器协议：执行层把 `:binding/output-union` 与 `:binding/output-recursion`（键的集合，非空才
  出现）随 Binding 交给 connector 校验器，镜像 §3.2 的输入载体。
- connector 支持面：

| connector | union 输出 | 递归输出 |
|---|---|---|
| api / static | 支持：记录里必须带出分支 Entity 的 identity 属性，执行器据此判别分支 | 支持：resolver 只答直接子级，规划器按深度递归 |
| sql | 拒绝 `:connector.binding/union-output-unsupported` at `[:binding/output-map a]`（平铺行无法承载多形） | 支持（同上，递归是规划器的事） |
| derived / nifi | 拒绝，code 同上 | 拒绝 `:connector.binding/recursion-output-unsupported` |

### 6.4 执行语义

- **union**：每条记录的 ref 值必须**恰好**匹配一个分支——**只把带值的 identity 算数**，匹配记录里
  值为 nil 的 identity 键先被剔除，再看剩下的属于哪个分支；匹配零个或多个 → 该记录的该属性按
  partial 规则记 finding `:engine.execution/union-ambiguous`，值缺席，不猜。投影验证按匹配到的
  分支校验。
- **递归**：调用方 EQL 可写 `{:group/children ...}` 或带整数深度；请求深度 > 声明深度、或写 `...`
  而声明有上限时，gate 把深度**夹到**声明值——这是唯一允许的「截断」，因为深度上限是作者声明的
  契约，不是运行时的静默降级；响应在 `:pagination`（或同级）报 `:recursion/depth-applied`。对未
  声明递归的 join 写递归形态拒绝 `:engine.request/recursion-not-declared`。**环的停止是响应层面
  的**：取数按声明深度取到上限，响应里同一记录在一条路径上重复出现时停止展开并记
  `:engine.execution/recursion-cycle` info issue。
- cursor 能力落地时签名须含递归深度。

### 6.5 lowering

dialect 模型 `:output` 直接承载 ④⑤（EQL 原生：union 键换成分支 Entity 的 identity 属性，递归
保留整数）；词汇的 `:attribute/targets` 进模型 `:targets`。引擎 `::pco/output` 用 EQL 的 union /
递归写法。MCP 的 schema 描述与 Studio 的输出树编辑器登记为后续事项。

## 7. 查询、分页、集合与指标

请求必填 graph/id、request/id、query；可选 entity、cursors。query 接受字段、join 与字段参数，
禁止以 symbol mutation 混入查询入口。请求中的字段/参数仍须经过 runtime Graph 和输入验证。

Definition 的可选 `:filters` 为 parameter → `{:attribute :operator :required? :description}`；
operator 限 eq/in/lt/lte/gt/gte/like。保留 limit/offset/order_by/sort_dir，不能被业务参数占用。
`:sort` 包含 allowed 和 default；`:pagination` 包含 default/max，必须为正且 default≤max。

many 查询必须稳定排序：默认排序不足以唯一排序时 runtime 补齐 identity 作为 tie-breaker。cursor
必须由宿主签名并绑定 Graph release、dependency snapshot、授权主体、过滤/排序、参数值和分页位置；
不接受客户端改写，不跨 release 重用。存在更多数据时必须明确 has-more?，不可将一页当全集。

**来源集合**（`:collection`，能力 `:query/source-collection-v1`）：`:ordering :source-defined`、
`:completeness :not-guaranteed`、`:bounds {:max-items :max-response-bytes :timeout-ms
:max-pages}`、可选 `:limit`。`:paging` 是 `:none` 或块 `{:strategy ∈ #{:none :offset :page :cursor
:link-header} :param :next-path :size-param :page-size :snapshot-path}`（doc 107 §107.6）：声明
策略即 `:max-pages` 必填（空页也计数）；连接器驱动翻页，每页一次 attempt、一条回执；超限拒绝
不截断并点名界限；cursor 成环、跨 origin 的 `Link: rel=next`、snapshot identity 变化一律拒绝。
能力 `:query/paging-strategy-v1`；`:none` 是缺省。

**查询执行预算**：`:graph/query-budget {:execution-ms 120000}`，`execution-ms` 为 1..900000 的
整数毫秒（平台硬上限 15 分钟），省略时引擎默认 30000 ms。声明后须加入 `:graph/query-budget-v1`；
预算随 Graph 内容发布，编译到引擎的整次查询 deadline；Package 使用 root Graph 的预算。它不改变
单次 Source timeout、collection bounds 或 DuckDB timeout；超时仍拒绝，不返回截断数据。

**指标**：`:metrics` 保留 DuckDB spill expression contract（`{:contract :duckdb-spill/v1 :text}`）、
entity、dimensions、grain、label、description，能力 `:metric/duckdb-spill-v1`；表达式能存储不等于
能执行。

query response 固定 `:query/status :ok|:partial|:rejected`、`:data`、`:issues`、`:pagination`。
每个 issue 有 path/status/message，可选 retryable?。同一路径不得既携带值又携带 issue。

| 事实 | 表达与约束 |
|---|---|
| 有值或合法 null | data 中的字段；null 不表示查询失败 |
| 未找到 | issue status not-found；成功空 collection 为 [] |
| Source 故障 | source-error，不能叙述为记录不存在 |
| 无权访问 | denied，固定 message 为 `not permitted`；不泄露内部信息 |
| 未请求 | 字段缺省，不补 null |
| 部分成功 | partial + 可访问 data + issues |
| 整体拒绝 | rejected + 空 data + 非空 issues |
| 多页 | 对应 path 的 limit、has-more?、next-cursor；has-more? 与 cursor 存在性一致 |

写操作走 [semantic-protocol](semantic-execution-v1.md) 的 Operation request（operation/id、
request/id、idempotency/key、input；可选 expected/revision 与 confirmation），状态
committed / rejected / conflict / unknown；receipt 必填。

## 8. partial 边界（待第四步）

未开始。与 OQ-FLEET-PARTIAL 合裁；等第二台真实实例。在此之前，partial 沿用 §7 的响应表：
Resolver 边界上的失败按 path 记 issue、值缺席、整条查询按既有规则判 `:partial` 或 `:rejected`。

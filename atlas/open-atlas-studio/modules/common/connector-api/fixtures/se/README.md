---
title: "Schneider Electric 伙伴 API fixture"
description: "从 open-atlas 迁入的真实 api 语料，按 api-v1 转写；连接器的验收集与「这个契约表达得了生产已经在做的事吗」的证据。"
---

# Schneider Electric 伙伴 API fixture

`graph.edn` 是 open-atlas `config.example/se/` 那份**真实在跑**的语料按
[api-v1](../../../../../docs/contracts/connectors/api-v1.md) 的转写。它不是编出来的
例子：TD SYNNEX US 与 Schneider Electric 的对接（Linda Horan 授权，2026-08-11，
订阅 Product-Catalog-V2 与 Product-Digital-Asset-V1）已经按这个形状跑了。

它在这里回答一个问题:**api-v1 表达得了生产已经在做的事吗**。所以它覆盖的不是
契约里好写的部分，而是这次对接真正用到的东西——OAuth2 + HTTP Basic 客户端认证、
必需的网关头、id 列表批量查询、**嵌套**的回显 id、深点路径映射、query 位的
eql-param。`se_fixture_test.clj` 两头验它：`validate-source` 证形状合规，抽取用例
证那些路径真的取得到值。

`graph.edn` 是 **canonical 形**（过 `dialect/read-dialect` **零 findings**，
改动后须复验），所以原语料里那些注释在这里活不下来——**它们是这份 fixture 最值钱的
部分，全部搬到了本文**。

## 凭证与运行

语料里只有 `${SE_CLIENT_ID}` / `${SE_CLIENT_SECRET}` 两个占位符，**永不写字面值**
——这份文件可分享，密钥不可。测试用 `fixture-env` 顶替解析,不读真实环境。

注意 Schneider 只为整个 TD SYNNEX 签发了**一对**凭证（我们与 IMT/Cloud Lei 共用，
连同它的限流），轮换是一次协同变更。

三套环境同形不同 host:`https://api.se.com`（prod，语料里配的）、
`https://api.preprod.se.com`、`https://api.qa.se.com`。

## 从真实响应量出来的数（不是从文档抄的）

这些数字是这份语料的**核心价值**——它们全部来自实测，任何内省都推不出来:

**`:chunk-size 25`** —— 这个上游的多编号查询在 ~25 之后是**超线性**的:

| 编号数 | 耗时 | 每编号 |
|---|---|---|
| 1 | 0.38 s | 0.38 s |
| 5 | 2.2 s | 0.44 s |
| 10 | 2.4 s | 0.24 s |
| 25 | 3.2 s | **0.13 s（最优）** |
| 50 | 13.4 s | 0.27 s（比逐个取还差） |

**`:timeout_ms 120000`** —— 两分钟，因为这个上游的延迟**极不稳定**:同一个 delta
调用测到过 0.4 s、4 s、49 s，还有一次涓流了约 10 分钟。典型值是亚秒级（详情 ~0.6 s、
全量索引 ~1.1 s、delta ~4 s），两分钟是留给坏日子的，不是常态。

**注意这个值管的是什么**:clj-http 的 socket timeout 是**每次读的静默间隔**，不是
整体截止时间。一个每分钟吐几个字节的响应永远不会触发它——上面那次跑了 10 分钟的
delta 调用正是这么在 5 s 缺省值下活下来的。它约束的是沉默，不是总时长。

**响应规模**（2026-08-12 实测）:单个产品 ~8 KB、12 个 section;全量索引 5760 条、
~374 KB,**一次响应**——这个 API **不分页**,没有游标可跟。delta 同信封多一个
`:delta` 字段（NEW / UPDATE / DELETE）。

**delta 里的 gtin 是免费数据**:342 行里 231 行带 gtin（2026-08-10 实测）。把这个属性
声明在 delta 的 output 里，是让缺口保持小的关键——去掉它，一个要 gtin 的调用会回填
**全部 342 行**，而不是真正缺的那 111 行。

## 厂商的命名怪癖

**同一个商用编号在三个端点有三个名字**:索引里叫 `commercialReference`、delta 里叫
`productReference`、详情负载里叫 `manufacturerPID`/`supplierPID`。三者全部映射到
`vendor.schneider.product/product_reference`，所以调用方看到的是一个标识符。

这件事没有任何内省能推出来,只能人对着三份真实响应做判断——它是[「API 建模需要
自己的画面」](../../../../../docs/design/90-open-questions.md)那个论断的一手证据。

## 401 是业务语义，不是连接故障

Schneider 对**不在我们授权目录内**的任何产品编号返回 `401 UNAUTHORIZED`（消息还说
token 无效或过期）。读作「这个编号不在 Schneider 给 TD SYNNEX US 的目录里」，不是
「连接坏了」——用 index 确认。

这正是 `impl.request` 里那条 fresh-token 守卫存在的原因:刚签发几秒的 token 不可能
过期，所以这种 401 不触发刷新。没有这条守卫，每一次目录未命中都要白扔一个好 token
并多付一次 token 往返，而并发的其他 resolver 正在用那个 token。

## 没有建模的东西

Digital-Asset-V1 的 `/360/view` **没有**进模型:它答 307，跳到 visuals.se.com 上的一个
HTML 查看器页面，不是 JSON，所以它在 resolver 里没有位置。真正是数据的文档与图片链接
在详情负载的 `mimeInfos` 里（→ `:documents`）。

## 转写决定（api-v1 vs 老方言）

| 老语料 | 本 fixture | 依据 |
|---|---|---|
| `:items-path "$.products"` | `:data_path "$.products"` | api-v1 操作块:batch 与非 batch **统一**用 `:data_path`，`:items-path` 不存在（D2.4） |
| resolver 上 `:batch? true` | 删除 | batch 由 `:source` 里的 `:batch` 块蕴含，没有第二个开关 |
| `:input`/`:output`/`:parent-key`/`field_mapping` 键为字符串 | 属性 kw | dialect-v1 §3 与 api-v1（`:parent-key`（属性kw）、`:field_mapping`（属性kw → JSONPath）） |
| resolver `:name` | `:label` | dialect-v1 §3 resolver 条目无 `:name` |
| 属性 `:enum {...}` | `:values {码值 {:label ...}}` | dialect-v1 attribute 条目 |
| 属性 `:example` / `:nullable?` | 删除 | 均不在 attribute 条目键面（封闭 schema） |
| namespace `:domain` | 删除 | 不在 namespace 条目键面 |
| eql-param `:type "string"` | `:type :date` | dialect-v1:无 `:attribute` 时 `:type` 取属性类型词表的 **keyword**；该参数是 `YYYY-MM-DD` 日期 |
| — | `:kind :master` | namespace 条目 `:kind` 是词表必选项之一，老方言没有这个位置 |
| — | join 属性 `:vendor.schneider.product/index` 与 `…/changes` | 两个 list resolver 的 `:output` 是 join 锚，D2.16 要求锚是登记的 join 属性（D2.29 之前登记在 namespace 的 `:navigations` 里） |

`:auth` **不需要改**:老语料已经带了 `:type "oauth2"` 判别符,正好落在 api-v1 的判别式
块上。（源级 `:api_key "${VAR}"` 那种旧拼法本语料没有。）

### 一处行为差异:批量 id 列表会被百分号编码

老实现把 join 后的 id 列表**逐字**插进 URL（注释理由是「Schneider 编号是字母数字加
横线，没什么要编码的」）。api-v1 规定占位符**落 URL 处 URL 编码**，所以分隔逗号现在
以 `%2C` 到达上游（`product-reference=NSYMM86%2CNSYPLM108G`）。

合规服务器解码后与逗号等价，实测未见问题;记在这里是因为它是**可观察的差异**，
真出问题时应该第一个被想起来。`se_fixture_test.clj` 把它钉成断言。

## `:limit` —— owner 裁定 2026-08-28

**两个 list resolver 的 `:limit` 是转写时补的,不是语料里的事实。** dialect-v1 §3 规定
`:limit`（list 必，`{:default :max}`），老方言没有这个要求。owner 裁定：**两条都取
`{:default 500 :max 5000}`**。

**这条裁定的后果要写明白**:目录实测 5760 条，而 `:max` 是 5000，所以
`se_product_index` **拿不到完整目录**——一次全量读在 5000 条截断，约 760 条取不到。
所以：

- **不要用这条 resolver 回答「Schneider 目录里有多少条」**，它给出的计数是窗口大小，
  不是目录大小;
- 它本来的用途是回答「X 在不在目录里」以及按需取编号，那些用途不受影响
  （语料原注释也写着「不要用它来浏览」）;
- 上游**不分页**，所以这个窗口是纯客户端截断，没有游标可以接着取。

`se_product_delta` 的同一组值贴合实测（近窗 342 行，一个月量级数千行），无此问题。

两个值都在 `se_fixture_test.clj` 里钉了断言:它们是**裁定**，不是可以随手调的参数。

## 样本响应

`sample-detail.json` 的**结构**取自 2026-08-12 记录的真实响应形状。值凡语料记过例的
用记过的（`NSYMM86`、`3606480183430`、那句 short desc、`74.82`、`EC000213`、
`Commercialised`、`17.637 lb(US) (8.000 kg)`），**其余是明确的合成占位**
（`SAMPLE-` 前缀）。

**它不是一份真实响应快照**,也不该被当成 Schneider 数据引用——它的唯一职责是证明
那些深路径取得到值、batch 按嵌套回显 id 对齐、缺失的编号返回 `nil` 而不是半空 map。
第二个产品条目故意只有部分 section，用来证明缺字段不会污染对齐。

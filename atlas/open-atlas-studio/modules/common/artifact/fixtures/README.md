---
title: "B3 制品黄金向量"
description: "content-hash 与三文件 artifact-digest 的黄金测试向量：文件清单、每一条守住什么、以及怎么用 sha256sum 手工复核。"
---

# B3 制品黄金向量

D4.3 要求「同一内容在任何线程、任何新进程、任何机器算出同一个 hash」是**测试义务**，
交付物包括一组黄金测试向量——若干份 `content.edn` 与它们的期望 hash，**任何实现都必须复现**。
本目录就是那组向量；`golden.edn` 是索引，`golden/<名字>/` 是被哈希的文件本体。

这些文件是**冻结的字节**，不是每次跑测试重新生成的中间产物。它们由另一个进程在另一个
时刻算出并落盘，测试每次重新读取并复算——「任何新进程」那一条因此不需要额外的机关，
向量文件本身就是它的证据。改动 writer 或信封形状会让它们全部变红，**这正是它们的作用**：
`content-hash` 是跨 instance 的兼容边界，它悄悄改掉比它红掉危险得多。

## 重新冻结记录

向量是**冻结的字节**，改动它们要留痕——否则将来有人想再改一次规则时，看到的是一个
无声改过的 hash，而不是「上次这么做的前提是什么」。每一次重新冻结在此加一条。

### 2026-08-23 —— D2.25 组本地名保留前缀 `g_`

| | |
|---|---|
| **涉及向量** | `b1-sealed/`（三份文件全部）、`seven-kinds/` |
| **因为哪条规则变了** | [D2.25](../../../../docs/design/20-dialect.md) 新增**保留前缀执法**：组的本地名必须以 `g_` 开头，属性 local name 与 navigation 名必须不以 `g_` 开头，违反即 `:dialect.invariant/reserved-prefix` 拒绝。两份向量里 `cis.master.vendor` 的组本地名 `audit_info` 因此不再合法。连带按同日改判把注册键统一成 snake_case 且组 ID 带前缀（[dialect-v1 §2](../../../../docs/contracts/dialect-v1.md)），注册 ID `common.audit/audit_info` → `common.audit/g_audit_info` |
| **改了哪些字节** | 只有上述两个名字。其余内容逐字节复现（缩进的位移是规范形对齐的必然结果，键长了两个字符） |
| **怎么重生的** | 走**真管线**——`read-dialect` → `encode-content` → `seal-graph`，不手改文本、不手算 hash |

**当时为什么可以重新冻结**：v1 **尚未发布**，B5c（部署/激活）还没建，**不存在任何已部署
的制品**——这些文件是测试夹具，不是发过货的东西。于是「向后兼容已密封制品」要保护的是一个
**空集合**，而代价是永久的：reader 从此要接受两种拼法，正是 [D2.4](../../../../docs/design/20-dialect.md)
「方言只有一种合法写法」与 [D2.10](../../../../docs/design/20-dialect.md)「v1 遗留方言不进
新实现」要防的事。

**此后若已有制品在外，同样的规则变更就不再是重新冻结**，而是必须新建格式版本。这条线
就在 B5c：一旦有制品被送到过 server，上面那个「空集合」的论证立刻失效。

`golden_test` 里「冻结向量在当前装载规则下仍然装得进来」那条用例**没有放宽**——它这次
正确地报了红，正是它在起作用。重新冻结之后它自然变绿。

### 2026-08-24 —— D2.25 组移除

| | |
|---|---|
| **涉及向量** | `b1-sealed/`（三份文件全部）、`seven-kinds/` |
| **因为哪条规则变了** | owner 裁定[移除组](../../../../docs/design/20-dialect.md#d225-组已移除收纳交给-derived-属性藏交给-listed)：`:groups` 不再是方言顶层键，namespace 条目不再有组实例，注册表由三张收成两张（units / terms）。两份向量里的 `:groups` 内容因此不再合法 |
| **改了哪些字节** | 只删掉了 `:groups` 相关条目（根注册表一条 + `cis.master.vendor` 的实例一条）。其余内容逐字节复现 |
| **怎么重生的** | 走**真管线**——`read-dialect` → `encode-content` → `seal-graph`，不手改文本、不手算 hash（manifest 里的 `:structural-ok?` 是管线从 findings 导出的，不作输入，D4.9） |

**当时为什么可以重新冻结**：与上一条同一个论证——v1 仍未发布，B5c 尚未建成，已部署制品仍是空集合。

### 这条线曾经画在这里——**封条本身已由 owner 修订（2026-09-02）**

上面那条**曾经**被写成最后一次重新冻结：artifact v1 已随 B5c-1 开工**封版**（见
[artifact-v1 §0](../../../../docs/contracts/artifact-v1.md)），第一份制品送到 server 那一刻，
「已部署制品是空集合」这个论证失效，而它正是前两次重新冻结的**唯一**依据。此后要改
**键面或算法**，走新的 `:artifact/format-version`
（[OQ-ARTIFACT-COMPAT](../../../../docs/design/90-open-questions.md#oq-artifact-compat) 届时才需要
答案）。

D2.29 的重新冻结最初以「owner 特批例外」入档；随后 owner 裁定（2026-09-02）**把封条
本身修订成一条可判定的规则**，不再依赖逐次特批的措辞。修订的依据：

- 封版锁的是**信封**——artifact-v1 §0 的原话是「任何**键面或算法**的改动」，而 §1
  又明说「模型内容本身不在本文：`:objects` 完整复用公有方言」。D2.29 改的是
  **dialect-v1** 的键面，信封的三个键、三种身份的算法、`:artifact/format-version`
  **一个字节都没动**；
- 封版那句话给自己写好了触发条件——「第一份制品送到 server 那一刻」。它还没有发生:
  仓库最高 tag 是 `v0.10`，没有任何制品流通过；
- **前两次重新冻结也都是方言规则变更**（组前缀、组移除），按同一把尺子量，这类事件
  可以写成规则，不必每次都以例外身份入档。

**修订后的封条（owner 裁定 2026-09-02）**：**方言规则变更、且 v1 尚未发布（没有任何
制品流通过）时，经 owner 授权可重新冻结**，并在本表留一条记录。两条边界不变：
**信封**（键面或算法）的改动不在此列，那仍然只能走新的 `:artifact/format-version`；
**第一份制品送到 server 之后本条自动失效**，此后任何改动都走格式版本。

### 2026-09-02 —— D2.29「分支即属性」

| | |
|---|---|
| **涉及向量** | `b1-sealed/`（三份文件全部）、`seven-kinds/` |
| **因为哪条规则变了** | owner 裁定 [D2.29](../../../../docs/design/20-dialect.md#d229-分支即属性navigation-并入属性)：`:output` 的分支键就是 **join 属性**（带 `:target`、无 `:type`），namespace 条目上的 `:navigations` 注册表与属性上的 `:collection?` 一并退役。两份向量里 `cis.master.part` 与 `cis.master.vendor` 的 `:navigations` 因此不再合法 |
| **改了哪些字节** | 两条 navigation 变成 `:cis.master.part/all_parts` 与 `:cis.master.vendor/parts` 两条 join 属性（内容逐字搬家，`:label`/`:cardinality`/`:target` 原样），`:namespaces` 少掉两个 `:navigations` 块。`report.edn` 的 `:quality {:attribute-count}` 12 → 14：属性注册表真的多了两条，这个数字是它的诚实计数，不是重排 |
| **怎么重生的** | 走**真管线**——`read-dialect` → `encode-content` → `seal-graph`，不手改文本、不手算 hash。`golden.edn` 索引由 `canonical-str` 重写（它是索引不是被哈希的向量，重排不改变任何身份） |

### 2026-09-06 —— doc 104 §8.17.7 A 的 `:package` 块

**这不是一次重新冻结**：已有向量一个字节都没动，`with-package/` 是**新增**的一份。
`seal_graph` 的第七个入参不传时 `:package` 根本不书写，manifest 与本键出现之前逐字节
相同——`b1-sealed` 因此照旧复现（`golden_test` 与 `package_test` 各自验证这一点）。
新向量走**真管线**生成（`read-dialect` → `encode-content` → `seal-graph`，包块由
`spi/package-requirements` / `spi/package-compat` 从同一份模型算出），`golden.edn` 索引
由 `canonical-str` 重写。

## 手工复核

`content-hash` 就是整份文件规范化 UTF-8 字节的 SHA-256（D4.3），因此不需要平台自己的工具：

```console
$ shasum -a 256 golden/minimal/content.edn
bbd2807f933c77a5aad545c414ad7babba497b420c0ecf2858925285abbd4971  golden/minimal/content.edn
```

对上 `golden.edn` 里 `:content-hash` 的 `"minimal"` 去掉 `sha256:` 前缀的那 64 位。

## 向量清单（对齐 D4.3 的表）

| 目录 | 守住什么 |
|---|---|
| `golden/minimal/` | 空 `:objects`、空 `:references`——最小合法制品；空集合的规范写法唯一 |
| `golden/seven-kinds/` | 七个概念全部出现（source / namespace / attribute / resolver / mutation / link / **metric**，D2.21），外加 units / terms 两张注册表；方言根 map 的完整形状进入身份 |
| `golden/edges/` | Unicode（CJK、补充平面 emoji、组合字符、零宽连接符）、换行与制表、引号与反斜杠转义、`Long/MIN_VALUE` 与 `Long/MAX_VALUE` 码值键 |
| `golden/writer-edges/` | writer 值域边界：`value.edn` 是输入值，`canonical.txt` 是期望的规范形文本，逐字节冻结 |
| `golden/one-reference/` | 单个引用；引用进入身份 |
| `golden/one-reference-upstream-changed/` | 与上一条**只差引用的 `:content-hash`**——上游被动过必须可检测 |
| `golden/upstream-core/` | `one-reference` 指向的那份上游内容本体；它的 content-hash 就是引用条目里写着的值 |
| `golden/b1-sealed/` | 三文件 artifact-digest 的向量：`fixtures/b1/graph.edn` 密封后的 `content.edn` + `manifest.edn` + `report.edn` |
| `golden/with-package/` | 同一份内容**带 `:package` 块**密封的三文件（doc 104 §8.17.7 A）：`content.edn` 与 `b1-sealed` 逐字节相同、content-hash 相同，manifest 多一块而 artifact-digest 因此不同——「包声明进 digest 不进内容身份」的向量 |

「两个语义相同、书写顺序不同的输入产出同一份文本」与「任意一个字节变化 hash 必须不同」
是**性质**而不是向量，在 `golden_test.clj` 里对上述每一份向量逐条施加，因此不占目录。

## 为什么密封的是 `fixtures/b1/graph.edn`

它过 B0 零 findings，是现成的**真**模型——engine 与 connector-sql 的测试用的也是它。
为黄金向量单造一个玩具模型，等于让向量守住的形状与生产真正会遇到的形状分叉；而
D4.10-a 已经因为同一条理由否掉了「B3 自己写一份简化的制品生成」。

## `writer-edges` 为什么单独一份

`content.edn` 的数值面由方言决定，而方言今天不书写浮点。D4.3 点名要守住的「整数与浮点
边界」若只放进 `content.edn` 向量，浮点那一半就无处安放。`writer-edges` 因此直接对
**规范 writer** 取向量：它守的是「同一个值在任何平台写成同一串字符」，正是 content-hash
所依赖的那条性质，只是取样点在方言值域之外。

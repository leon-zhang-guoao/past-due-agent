---
title: "demo 库"
description: "cis-sqlite 产物的 source 侧：小而有结构的合成数据，跑得出 CPO→MSO→SSO 与 dropship 两条链路。"
---

# demo 库

[demo 模型](../README.md)的 source 侧：模型里的 resolver 查的就是这份库。
[`schema.sql`](schema.sql) 是建表，[`seed.sql`](seed.sql) 是行数据。

## 这是**合成数据**，不是 owner 的业务数据

金额、日期、公司名、SKU 编号都像真的——那是**故意的**，一份 `1 / 2 / 3` 的假数据
既演示不出东西，也测不出宽整数那类问题。但**没有一条来自 owner 的业务系统**：
名字全是虚构的，数字由 [`generate.py`](generate.py) 按业务规则算出来。

参考实现那份真库是 8.7 MB / 51,683 行（导出 12.5 MB SQL），**不进本仓库**。这份
按 [`fixtures/b1/schema.sql`](../../b1/schema.sql) 的路子造：**刻意小而有结构**，
够演示，不臃肿。

## 可重建，且每次**逐字节相同**

```bash
python3 fixtures/demo/demo-db/generate.py     # 重写 seed.sql
```

生成器与产物**都进仓库**：脚本说明数据是怎么造出来的（业务规则写在代码里，读得到），
`seed.sql` 让跑测试的人不必装 Python、也不必信任生成器。

「逐字节相同」是硬要求，所以生成器**不用标准库 `random`**——Mersenne Twister 跨
Python 版本的行为不是契约。它自带一个写死的 LCG，序列只由常数决定。金额一律用整数
分计算，浮点的舍入误差会让这条要求变成一句空话。

## 业务链

```
CPO ─→ MSO ─┬─ 正常履约 ───────────────────→ SSO   (sso.int_ref → MSO,  type 3)
            └─ dropship → VPO ─(供应商直发)─→ SSO   (sso.int_ref → VPO,  type 2)
```

## 表与 entity 的对应

| 表 | entity（namespace） | 承担 | 行数 |
|---|---|---|---:|
| `cpo_header` | `cis.cpo.cpo_header` ★ | 客户的采购订单，链条起点 | 15 |
| `cpo_detail` | `cis.cpo.cpo_detail` ★ | CPO 的行项 | 79 |
| `msos` | `cis.sales.mso` | 我们的销售合同（头） | 20 |
| `mso_order_details` | `cis.sales.mso_order_detail` ★ | 合同的**全量订货行** | 80 |
| `vpos` | `cis.purchase.vpo` | 向供应商下的采购单（dropship） | 8 |
| `vpo_order_details` | `cis.purchase.vpo_order_detail` | VPO 行项 | 34 |
| `vpo_eta_detail` | `cis.purchase.vpo_eta_detail` | 到货跟踪 | 8 |
| `sso` | `cis.sales.sso` | 每次发货一张（履约进度） | 30 |
| `sso_order_detail` | `cis.sales.sso_order_detail` | 发货行项 | 126 |
| `customers` | `cis.master.customer` | 客户主档 | 20 |
| `customer_credit` | `cis.master.customer_credit` | 授信（含 `:restricted` 的 `credit_limit`） | 20 |
| `customer_bank` | `cis.master.customer_bank` | 结算银行（整族 `:sensitive`） | 13 |
| `parts` | `cis.master.part` | 物料主档 | 30 |
| `vendors` | `cis.master.vendor` | 供应商主档 | 10 |

★ = 当年由作者端补充直接写进模型的 entity（存量语料里没有）。语料与转换器已于
2026-08-24 退休，这个区分现在只是来历，不再是模型里的两半——见
[上一层的 README](../README.md)。`cis.sales.mso_fulfilment` 没有对应的表:
它是**纯导航锚**（一对多的锚落在新 namespace 上，因为纯并集加不了 join 属性）。

## 三种履约状态的分布

履约进度按 `mso_order_details` 算：`Σ ship_qty / Σ order_qty`。

| 状态 | MSO 张数 |
|---|---:|
| 未发货（0%） | **6** |
| 部分发货（0 < x < 100%） | **8** |
| 全部发货（100%） | **6** |

**「部分发货」是这份库存在的主要理由之一**：现有 demo 数据里
`sso_order_detail.order_qty` 恒等于 `ship_qty`，一条部分发货都没有，于是履约进度
根本演示不出来。

## 两条履约路径的分布

| | 张数 |
|---|---:|
| 仓库发货的 SSO（`int_ref_type = 3` → MSO） | **22** |
| dropship 的 SSO（`int_ref_type = 2` → VPO） | **8** |
| dropship 采购单 VPO（`int_ref_type = 3` → MSO） | 8 |
| 一张 MSO 挂多张 SSO | 6 张 MSO |
| **混合模式**：同一张 MSO 既有仓库发货的 SSO、又有 dropship 的 VPO/SSO | **5 张 MSO** |

混合模式是真实业务里最容易出错的形态，也最值得演示：`143100003`、`143100006`、
`143100011` 都是。

## 数据满足的业务规则（都由 `generate.py` 保证，并有集成测试守着）

1. `msos.int_ref_no` → `cpo_header.order_no`（`int_ref_type = 4`）；一张 CPO 可以开出
   多张 MSO；
2. `vpos.int_ref_no` → `msos.order_no`（`int_ref_type = 3`），dropship 的 VPO 收货方
   `to_acct_no` 是**客户**、供货方 `from_acct_no` 是供应商——货不经我们的仓库；
3. `sso.int_ref_no` **多态**：`type 3` → `msos.order_no`，`type 2` → `vpos.order_no`；
   一张 MSO 对多张 SSO；
4. **数量自洽**：每个 `(mso, sku)` 的 `Σ sso ship_qty ≤ mso order_qty`，
   **两条路径合并计算**——供应商直发的那批一样算在合同头上；
5. 没有悬空引用：上面每一条 `int_ref_no` 都指得到一行真实存在的记录；
6. **审计字段**（`entry_id` / `entry_datetime` / `update_id` / `update_datetime` /
   `u_version`，六张表都有）：`entry_datetime` **早于**该行的业务日期——单子是先录
   进来、再发生后面的事；`u_version` 从 1 起，**版本号是 1 就意味着从没被改过**，
   于是 `update_id` / `update_datetime` 为 NULL；
7. **仓库作业时间线**（`msos`）：顺序恒为
   `issue_date < pick_date < qc_date < label_date < ship_date < bol_date`。
   走到哪一步由履约状态决定，**没走到的是 NULL**：未发货的 6 张五个时点全空、
   部分发货的 8 张开到 `ship_date`、全部发货的 6 张五个都有。
   组 `g_warehouse_ops` 的成员**按这个顺序排列**——一个按时间线排的组，若排出来的
   顺序与数据里的不同，它就在说谎；
8. **放行控制**（`msos`）：两张仍被扣住（`approval = 'N'`、`hold_id` / `hold_date`
   有值、`credit_rel_date` 为空，且都落在未发货那一批里——被扣住的单子当然发不
   出去），两张扣过又放行（三个字段都有值），其余没被扣过、三个字段全空；
9. **收货时间线**（`vpos`）：`receiving_date` / `closed_date` 与
   `vpo_eta_detail.actual_arrival` **对得上**——那边还没到货的三张，这里两个时点
   也是 NULL。两处说的是同一件事，不许互相矛盾。

**NULL 本身是演示素材**，不是缺数据。上面 6–9 每一条的空值都在回答一个具体问题
（「这单还没发货」「这条记录从没被改过」「货还没到」），集成测试
`null-milestones-are-the-answer` 盯着这一点。

### 生成器用**两条**独立的 LCG

`generate.py` 里主 `rng` 之外还有一条 `arng`（`AUDIT_SEED`），本轮新加的审计、
收货地址、仓库作业与放行控制字段全部由它产出。理由很具体：主 `rng` 是一条流，在
中间插一次抽取会把它之后的**每一个值**都推移一格——已提交的 `seed.sql` 与集成
测试里那几个锚点会集体失效，而那与新加的字段毫无关系。两条流各自确定，合起来仍然
逐字节可重现。

## 号段：四类单据各占一段，**互不重叠**

| 键 | 号段 | 位数 | 参考库的真实区间 |
|---|---|---:|---|
| `cpo_header.order_no` | 210,000,001 – 210,000,015 | 9 | 参考库无 cpo，另取一段 |
| `msos.order_no` | 143,100,001 – 143,100,020 | 9 | 142,423,568 – 145,972,043 |
| `sso.order_no` | 160,500,001 – 160,500,030 | 9 | 128,247,378 – 168,382,320 |
| `vpos.order_no` | 32,700,101 – 32,700,108 | 8 | 32,700,001 – 32,709,374 |
| `parts.sku_no` | 5,100,001 – 5,100,378 | 7 | 4,001,821 – 9,610,851 |

号段分开是数据自带的**一道自检**：号段重叠时，一条接错的 join 仍然「查得出东西」，
bug 于是变成静默的；分开之后接错了就查不到，错误会自己现形。这也是
`demo_sso_mso` / `demo_sso_vpo` 那两条断言测得出来的原因之一。

## 类型一致 —— 以及为什么 `join-key` 的归一**不因此放松**

本库里**同一个业务键在所有表里都是同一个类型**，全部 `INTEGER`；方言里对应属性的
`:type` 也一律 `:integer`。

参考库不是这样：那边 `parts.sku_no` 是 **TEXT**，而 `sso_order_detail.sku_no` 是
**INTEGER**——同一个业务键，两张表两种类型。这正是 connector-sql 的 `join-key`
归一函数存在的理由，它的注释写着：同一个业务键在两边可能是 `Long` 与 `"5001"`，
不归一就会整批落空，而落空在契约里是**合法结果**（空位），于是这个 bug 静默。

**我们的库干净，不代表别人的库干净。** `join-key` 的归一能力守的是「连上真实的、
类型不一致的库」那种情况，而那种库确实存在——就在参考实现里。所以它**不因为这份
demo 库类型一致而被删掉或放松**。

## 谁在用它

[`modules/integration`](../../../modules/integration) 的 `business-chain-test`：
真方言（转换产物 + 补充）→ 真编译 → 真 SQL 连接器 → 真 SQLite → 真 EQL。
`schema.sql` 与 `seed.sql` 经该模块 `:test` alias 的 `:extra-paths` 进 classpath。

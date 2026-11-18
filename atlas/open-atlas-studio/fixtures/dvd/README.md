---
title: "fixtures/dvd — DVD 出租店的 demo 模型与库"
description: "面向使用者的那个例子：一套合成的 DVD 租赁数据、一份 dialect-v1 模型，studio 首启用它播种、server 一键导入它。"
---

# fixtures/dvd — DVD 出租店

**这是给人看的第一个例子。** 下载、启动、注册第一个账号之后，屏幕上出现的就是它：
租一张碟、还回来、付钱。

## 与 `fixtures/demo`（cis）的关系

**并存，各有各的职责**（owner 裁定 2026-09-01）：

| | 是什么 | 谁在用 |
|---|---|---|
| `fixtures/demo` | **测试的既成事实** —— 六个模块的测试每天穿过它 | `modules/**` 的测试、集成竖切 |
| `fixtures/dvd` | **使用者的第一课** —— 一眼看懂的业务 | studio 首启播种、server 的「导入示例」 |

cis 不能拿来当演示：读懂它得先解释什么是 MSO、什么是 dropship。dvd 也不该顶替
cis：CLAUDE.md 明令禁止为了让测试变绿去改 fixtures，而 cis 正是那些测试的装料。

## 目录里有什么

```
model.edn            dialect-v1 模型（规范形，往返逐字节相同）
graphs.edn           这份模型播出来的种子图：`[{:name "dvd" :selectors ["dvd.*"]}]`
dvd-db/schema.sql    表结构
dvd-db/seed.sql      行数据 —— 由 generate.py 确定性生成
dvd-db/generate.py   生成器：跑两次结果逐字节相同
```

**数据库文件不在这里**。`open-atlas-demo.db` 是**构建产物**：`clojure -T:build demo-db`
（studio 与 server 各有一个同名任务）从 schema+seed 造出来，打进 jar；运行时被复制
到 `<home>/demo/open-atlas-demo.db`——SQLite 要开一个真实路径，开不了 jar 里的 URL。

生成的东西不进仓库这一条是有代价换来的：`apps/server/data/cis-demo.db` 曾经**被
`.gitignore` 排除、同时又被 studio 的 uberjar 打包**，于是干净 clone 出来的 studio
首启就死在「Bundled CIS demo database is missing」。

## 种子图住在 `graphs.edn`

Studio 首启曾经**硬编码**播种 `demo/{cis,master,sales,purchase}` 四张 `cis.*` 图，
与 `OPEN_ATLAS_MODEL_PATH` 无关——按 dvd 播种的 Studio 因此起来是四张空图、
没有 `demo/dvd`。owner 2026-09-06 裁定（[doc 104 §8.17.6](../../docs/design/104-graph-packages-and-market.md)，
OQ-STUDIO-SEED）：**种子图由模型自己声明**。dialect-v1 §2 的顶层是封闭的，
往 `model.edn` 里加 `:graphs` 是改契约，所以它是 model.edn 的**兄弟文件**，
两个 app 的 `build.clj` 与模型一起打进 jar 的 `demo/graphs.edn`。

`:selectors` 在**建图时展开一次**（D3.19），与图编辑器的「加入 dvd.* 全部」是同一个
`expand-selectors`；此后新进池的对象不会自己入伙。

## 模型自己切得出版本

`model.edn` 曾经**过不了 cut-check**：17 个属性（`dvd.store.*`、`film_actor.*` 与几个
外键列）注册了却没有任何 resolver 产出（D2.2 存在性闭包），三条无锚 list 没签
`:curated? true`（D2.13）。随包例子看不出来，因为「导入示例」那条路直接密封整份模型，
不走 cut 的健康检查。owner 2026-09-06 裁定**修模型**（§8.17.6，OQ-DVD-CUT）：
补 `dvd_all_stores` / `dvd_store_by_id` / `dvd_store_staff` / `dvd_staff_by_id` /
`dvd_film_cast_credits` 五条 resolver，并让 `dvd_film_copies`、`dvd_customer_rentals`、
`dvd_rental_payments`、`dvd_outstanding_rentals` 各自带上它们本来就该带的外键列。
**属性与那 7 条 link 一个没删**——DVD 是门面，缺口补在产出侧。

## 数据是合成的

片名、人名、金额、日期都像真的，但**没有一条来自任何真实数据集**。形状借自业界
熟知的 DVD 租赁样例库（film / actor / customer / rental / payment 那一套），数据
一行都不是从那里来的。

## 两处刻意留下的形状

1. **`rental.return_date` 可为空，而空是答案** —— 未归还不是缺数据，是「这张碟还在
   客户手里」。模型里 `dvd_outstanding_rentals` 这条 resolver 就是它的入口。
2. **挂牌租金 ≠ 实收** —— `film.rental_rate` 是牌价，`payment.amount` 是实际收到的
   钱（含滞纳金）。`revenue_by_category_month` 这个 metric 刻意用实收：拿牌价乘天数
   会算少。

## 重新生成

```bash
python3 fixtures/dvd/dvd-db/generate.py     # 改了业务规则之后
```

产物 `seed.sql` 与脚本**一起进仓库**：脚本说明数据是怎么造出来的，seed.sql 让人
不必装 Python 也不必信任生成器。两者不一致时以脚本为准 —— 重跑一遍即可。

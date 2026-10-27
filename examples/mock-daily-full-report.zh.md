# PastDue Daily Monitor — 2026-08-16 → 2026-08-17 — PST

> 模拟报告（composition: `daily_full`）。数值为虚构样例。  
> 默认只展示 Country + `vendor_summary`（含 ETA Code/Source 紧凑变化列）；完整 ETA 表按需展开。

---

## Country 指标（module: country_metrics）

> 表头/列顺序以 `table-display-contract` 为准；先解析 composition `daily_full` 与 module `country_metrics`，再填表。

| Country | 状态 | 本期日期 | 上期日期 | 间隔 | 逾期行数 上期→本期 | Open 上期→本期 | 逾期率 上期→本期 | 相对变化 | 百分点变化 |
|---|---|---|---|---|---:|---:|---:|---:|---:|
| US | TRIGGERED | 2026-08-17 | 2026-08-16 | 1d | 820→950 | 10000→10200 | 8.20%→9.31% | 13.54% | +1.11 pp |
| CA | NORMAL | 2026-08-17 | 2026-08-16 | 1d | 210→215 | 3500→3520 | 6.00%→6.11% | 1.83% | +0.11 pp |

US 相对增长 ≥ 10%，触发下钻（较前一天）。CA 未触发。

---

## US — 触发与日期

- Country: **US**
- 对比: **2026-08-16 → 2026-08-17**（间隔 **1d**）
- 逾期率: **8.20%→9.31%**；相对变化 **13.54%**；百分点变化 **+1.11 pp**
- 阈值: **10%**（policy 默认）

### Vendor Top-10 汇总（module: vendor_summary）

| 排名 | Vendor Code | Vendor 名称 | 逾期行数 上期→本期 | 净变化 | Open 上期→本期 | Vendor 逾期率 上期→本期 | Country 贡献变化 | 正向贡献占比 | ETA Code 变化 | ETA Source 变化 | 分析状态 |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---|---|---|
| 1 | 10001 | Acme Components | 40→85 | +45 | 400→420 | 10.00%→20.24% | +0.44 pp | 32.00% | SC +25；NR +12；SH +3；UNKNOWN +3；RQ +2 | VENDOR_PORTAL +25；EDI +10；EMAIL +4；UNKNOWN +4；MANUAL +2 | 完成 |
| 2 | 10022 | Northwind Parts | 55→90 | +35 | 500→510 | 11.00%→17.65% | +0.34 pp | 24.50% | NR +20；SC +10；DD +2；UNKNOWN +3；LS 0 | EDI +20；VENDOR_PORTAL +10；EMAIL +4；UNKNOWN +1 | 完成 |
| 3 | 10045 | Contoso Supply | 30→50 | +20 | 300→305 | 10.00%→16.39% | +0.19 pp | 14.00% | SH +12；NR +5；其他 +3 | EMAIL +10；EDI +6；其他 +4 | 部分完成 |
| 4 | 10102 | Fabrikam Metals | 25→40 | +15 | 250→255 | 10.00%→15.69% | +0.14 pp | 10.50% | DD +8；NR +5；SC +2 | EDI +9；VENDOR_PORTAL +4；MANUAL +2 | 完成 |
| 5 | 10200 | Tailspin Goods | 18→30 | +12 | 180→185 | 10.00%→16.22% | +0.12 pp | 8.50% | RQ +6；NR +4；SH +2 | MANUAL +5；EMAIL +4；EDI +3 | 完成 |

（模拟只列 5 家；真实运行取固定正向 Top-10 或不足 10 的全部。Code/Source 变化列来自已查询的 `eta-*-changes` Top-5 紧凑格式，不是完整 module 表。）

Top 5 覆盖全部正向贡献的 **89.50%**。主驱动集中在 Vendor 10001 的 **SC / VENDOR_PORTAL** 与 10022 的 **NR / EDI**。

完整 ETA Code/Source Top-5+Other 表、单 Vendor 指标、Owner、通知收件人、高风险 PO 行：仅在用户明确要求时再输出对应 module。

---

## 覆盖、限制与行动摘要

- Top N 正向贡献覆盖见上表（非净 Country 增长份额）。
- CA 未触发，无 Vendor 下钻。
- 默认未展开全量 ETA module 表；Atlas 已按 Vendor 查询 Code/Source 以填充紧凑变化列。
- 建议优先跟进 US Vendor **10001 (SC)** 与 **10022 (NR)**；需要明细时再问「展开某 Vendor 的 ETA」。

## 跨国摘要

| Country | 状态 | 结论 |
|---|---|---|
| US | TRIGGERED | 需关注；详见 Vendor 汇总 ETA 变化列 |
| CA | NORMAL | 监控即可 |

---

### 单问示例（同一模块模板）

若用户只问「US 最新 Country 指标」，则**只**输出 `country_metrics`。  
若用户只问「Vendor 10001 的 ETA Code 变化」，则**只**输出该 Vendor 的 `eta_code_changes`（含增减合计），不得改列。  
若用户只问「Vendor 10001 高风险 PO 行」，则**只**输出 `current_po_lines`。

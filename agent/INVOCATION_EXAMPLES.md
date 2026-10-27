# Requests — Agent contract 3.1.2

| Request | Route / resolved intent |
|---|---|
| start daily monitor | Monitor, then report preset for real US/CA triggers only; English |
| start test | Force applicable report preset; real trigger unchanged; fully English |
| 开始测试 | Same test preset, fully Chinese |
| 开始测试，只测试 CA | One Country, all selected Vendors, Chinese |
| start test for snapshot 2026-10-26 | Explicit completed target; previous available weekday; English |
| compare CA 2026-10-26 with 2026-10-22 | Explore PAIR, EXPLICIT; never substitute nearest previous |
| CA 当前逾期数量前十 Vendor | Explore CURRENT, VENDOR, PAST_DUE_COUNT DESC, ALL, top-n=10 |
| CA 当前逾期率最高十家 | Explore CURRENT, VENDOR, PAST_DUE_RATIO DESC, ALL, top-n=10 |
| CA 当前各 Vendor 的 ETA Code 占比，全部 | Explore CURRENT, VENDOR_CODE, WITHIN_VENDOR, NONE, top-n=0 |
| CA Vendor 31420 的 ETA Code × Source 分布 | Explore CURRENT, VENDOR_CODE_SOURCE, vendor-code=31420; no marginal cross-join |
| CA 2026-10-26 比上期逾期数量下降最多十家 | Explore PAIR, PREVIOUS_AVAILABLE, VENDOR, COUNT_CHANGE ASC, NEGATIVE, top-n=10 |
| 过去一周哪些天触发，只列日期 | History D−7..D−1 targets, WEEKDAYS, no report drilldown |
| 过去一周触发事件，分析每国最近一次 | History then pinned report per selected Country/event |
| 历史查询包含周末 | Explicit CALENDAR_DAYS, label it |
| CA 过去一周按天看逾期率 | Explore RANGE, COUNTRY, no summing snapshot stocks |
| Vendor 31420 的两期指标，即使贡献下降 | Report SINGLE_VENDOR or Explore PAIR targeted Vendor; no positive-only gate |
| CA 当前按部门/Primary/Manager筛选，显示占全国家逾期比例 | Explore org filters, COUNTRY_ALL denominator; don't silently rebase |
| CA Vendor 31420 当前逾期明细 | On-demand current detail, not high-risk-only |
| 这些 Vendor 的 ETA Specialist 和通知邮箱 | Targeted current mappings/contacts, display only |

Dates and Vendor codes above are illustrative, never runtime defaults. Exact
controlled inputs and manual EQL examples are in the Skill's data contract and
Atlas query playbook. No arbitrary SQL or Python is needed for these requests.


Standard daily/history/test requests use the canonical bilingual skeleton and
separate Vendor Code/Source tables. English and Chinese differ in labels and
interpretation language, not field order or fact definitions. Interactive requests
use only relevant modules. Explicit brief/custom-column output is allowed with
CUSTOM display scope; it does not claim full-report test PASS.

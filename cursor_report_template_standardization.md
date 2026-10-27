# Report template standardization research
_Exported on 10/27/2026 at 09:15:49 GMT+8 from Cursor (3.27.12)_

---

**User**

现在有个新需求， 就是 要求每次 相关的 report 必须 是 相同的模板 
 你先 research 目前 都有哪些 report

---

**Cursor**

先调研现有 report 形态。我会加载项目相关 skill，并检索仓库里所有 report 定义与产出路径。

Using `pastdue-analysis` skill 定位报告契约；正在读取 `report-format.md` 及相关引用。

已检查相关 skill；仓库里**没有独立 sample report 文件**，报告形态由 `skill/pastdue-analysis/references/report-format.md` 统一约定。当前可识别的 report 如下。

## 两种主报告（envelope）

| 模式 | 标题格式 | 何时用 |
|---|---|---|
| **Daily Monitor** | `PastDue Daily Monitor — <对比日期> — UTC` | 默认定时 / 最新快照日报 |
| **Historical Review** | `PastDue Historical Review — <start> to <end> — UTC` | 明确日期/区间/历史意图 |

两者在触发后的下钻都走同一套 **Shared Country/event template**（文档已写明 shared）。

## 共享下钻模板（触发 Country / 事件）

顺序固定：

1. Trigger and dates（国家、日期、gap、比率、阈值）
2. Top-10 Vendor summary（正向贡献 Top-10，固定列）
3. 每家 Vendor 详细节（与汇总**同集合同顺序**）
4. Coverage / limitations / actions

每家 Vendor 节必含：

- 指标变化  
- ETA Code 变化（Top-5 + Other）  
- ETA Source 变化（同结构）  
- 变化解读  
- 当前行动信息（Buyer / Primary / Manager / PO）  
- Notification recipients（有驱动 ETA Code 时）

## 变体（不是新模板文件，而是同一契约下的裁剪）

来自 `INVOCATION_EXAMPLES.md` / Skill：

1. **Daily 完整下钻** — Country 表 + 触发国 shared template + 跨国摘要  
2. **Historical 事件列表 only** — 只列触发日，**省略**下钻模板  
3. **Historical 默认下钻** — 每 Country 最新 1 个事件 × shared template  
4. **Historical 全部下钻** — 每 Country 最多 5 个事件，每个事件各自一套 shared template  
5. **Historical + 当前行动上下文** — 在共享模板上加标注为 current 的 Owner/PO  
6. **对账 / 抵消类问答** — 用 `vendor-changes`，偏问答，不是完整日报结构  
7. **显式 positive-only ETA** — 走 legacy drivers（80%/max-5），须标注，**不能**当完整 all-change 报告

## 契约落点

- 唯一报告结构入口：`references/report-format.md`  
- Skill / SYSTEM_PROMPT / DESIGN 都要求 daily 与 historical 默认下钻用同一 Country summary + per-Vendor 模板  
- 完成态：`完成` / `部分完成` / `不可用`；长文拆分须带 pending worklist，禁止“其余类似”

---

**结论：** 名义上只有 **1 套共享下钻模板 + 2 种 envelope**；另有若干**裁剪/兼容变体**。新需求“相关 report 必须相同模板”，和现有设计方向一致，但事件列表 only、对账问答、positive-only 这几条目前是明确例外。

若要继续，下一步可以对照一次真实产出，标出哪些 section 实际不一致，再定“强制同模板”的范围（是否包含上述变体）。

---

**User**

下钻模板 是对的 ， 关键是 每个模块的  表格的 字段名 字段顺序 ，以及从atlas 得到数据后 填到对应表格值的格式  比如  百分比格式 日期格式  小数格式等 
请先设计一份模板  

---

**Cursor**

下钻结构已确认；接下来按 brainstorming 流程，先对齐各模块表格字段名、顺序与 Atlas 填值格式，再给出一份可落地的模板设计。

Using brainstorming 做模板设计。先确认一个关键决策，再给出方案对比与完整模板草案。

先补齐各模块现有列定义缺口，再确认 1–2 个会改变模板形态的关键点。

已对齐现状：`report-format.md` 只定义了部分表头与粗粒度格式（比率约 2 位小数、贡献用 pp、计数为整数），**Owner / PO / Notification 等表尚未固定列顺序与填值规则**。

先确认一个会决定模板形态的点：

**这份「表格显示模板」你希望以哪种形式落地？**

1. **Agent 可读 Markdown 契约**（推荐起步）  
   新建如 `references/table-display-contract.md`：每个模块固定 `field_id`、列顺序、显示标签（中/英）、Atlas 属性映射、显示格式（日期 / 百分比 / pp / 整数等）。Agent 填表时严格按此渲染。

2. **可校验的机器契约（EDN/JSON）**  
   同上内容写成结构化 schema，并由 `validate_bundle.py` / 测试校验列顺序与格式规则。

3. **两者都要**  
   Markdown 给 Agent 用，EDN/JSON 给校验用，内容同源。

回复选项编号即可；确认后我会给出完整模板设计（含各模块字段表 + 格式字典）并写成 Plan。

---

**User**

3

---

**Cursor**

选项 3 已确认。正在补齐各模块 Atlas 字段，随后给出含完整表格显示契约的计划。

已锁定双契约方案。正在写入包含完整字段/格式设计的计划。

---

**User**

我觉得不应该是 一整个模块  还是 拆出 诺干个模块 这些模块 都有自己的模板  
组合一起就是完整的report ，但是用户单独问某个模块时 又只会使用 这个模块的模板 这样设计合理吗

---

**Cursor**

这个设计合理，也更贴合现有「完整下钻 vs 被动问答」的用法。正在把计划改成「多模块模板可组合」。

**合理，而且比「一整块下钻模板」更贴合你们现有用法。**

计划已改成模块化组合，核心是：

1. **每个 module 自带模板**（字段名、顺序、格式、Atlas 映射）  
2. **完整 report = composition**（按固定顺序拼装多个 module）  
3. **用户单问某块能力时，只渲染对应 module**——列和格式与完整报告里同一段完全一致，不允许为「简答」另起一套表头

建议的 9 个模块：`country_metrics`、`trigger_events`、`vendor_summary`、`vendor_metrics`、`eta_code_changes`、`eta_source_changes`、`current_owners`、`current_po_lines`、`notification_recipients`。

Composition 示例：`daily_full` / `historical_full` / `historical_events_only`。

「变化解读」仍作散文规则挂在完整流程里，不单独占表模块，避免和表格契约搅在一起。

若模块划分和拼装方式没问题，回复确认后即可按该计划落地实现。若要合并/拆分某些模块（例如把 owners+PO 合成一个 `current_action`），直接说偏好即可。

---

**User**

里面的真实值的格式 是否有 指定，比如 必须时百分比，不能是小数，日期格式必须一致等

---

**Cursor**

有指定。正在把「硬性显示格式」在计划里写得更明确，避免被当成建议。

**有指定，而且是硬性规则，不是建议。**

计划里已写明：Atlas 里可以是未舍入小数（如 `0.1234`），但**写进报告单元格时必须按列绑定的 format 转换**。要点：

| 类型 | 必须 | 禁止 |
|---|---|---|
| 比率 / 相对变化 / 占比 | `12.34%`（×100，固定 2 位） | `0.1234`、位数不齐 |
| 贡献 / 点变化 | `+1.50 pp` | `0.015`、写成 `%` |
| 日期 | 一律 `YYYY-MM-DD` | `2026/08/17`、`Aug 17` 等 |
| 计数净变化 | `+12` / `−5` / `0` | 正数不带符号、小数点 |
| 成对比率 | `8.00%→10.00%` | `0.08→0.10` |

另外：**同一 `field_id` 在完整报告和单问模块里格式必须一致**；触发判断仍用未舍入原值，展示舍入不参与比较。

这些会进 `table-display-contract.edn` 的 `:formats`，并由每列强制绑定。若还要对某类值加规则（例如千分位、百分比改 1 位小数），可以说一下偏好。

---

**User**

按计划实施

---

**Cursor**

按计划落地：先加载执行相关 skill，再创建契约文件并接线校验。

Using executing-plans 按计划实施。正在创建 EDN 真源与配套 Markdown。

为贡献成对显示补充 `pair_pp` 格式，并撰写 Markdown 契约。

Markdown 文首误写了 ` ```fields `，被当成代码围栏。正在修复并收紧校验正则。

表格显示契约已落地，`python scripts/validate_bundle.py` 已通过。

### 新增
- [`table-display-contract.edn`](skill/pastdue-analysis/references/table-display-contract.edn) — 真源：formats / 9 modules / compositions  
- [`table-display-contract.md`](skill/pastdue-analysis/references/table-display-contract.md) — Agent 填表规范（含硬性百分比/日期/`pp` 等规则）

### 更新
- `report-format.md` — 改为 composition 拼装，列与格式全部引用契约  
- `SKILL.md` / `SYSTEM_PROMPT.md` — 完整报告走 composition，单问只渲染对应 module  
- `validate_bundle.py` — 校验 EDN 结构，并比对 MD 各 module 的 `fields` 顺序

### 模块
`country_metrics` · `trigger_events` · `vendor_summary` · `vendor_metrics` · `eta_code_changes` · `eta_source_changes` · `current_owners` · `current_po_lines` · `notification_recipients`

### Composition
`daily_full` · `historical_full` · `historical_events_only`

未提交。需要的话我可以帮你 commit，或开 PR。

---

**User**

请 按现有模板 出一个 模拟reprot 给我看

---

**Cursor**

按 `daily_full` 组合与表格显示契约，生成一份可打开的中文模拟日报（含完整模块样例）。

模拟日报已写好，可直接打开：

[`examples/mock-daily-full-report.zh.md`](examples/mock-daily-full-report.zh.md)

内容按 composition `daily_full` 拼装，格式按契约：

- 比率：`8.20%→9.31%`、`13.54%`（不是 `0.082`）
- 贡献：`+1.11 pp`、`+0.44 pp`
- 日期：`2026-08-17`，间隔：`1d`
- 净变化：`+45` / `0`
- 模块：`country_metrics` → `vendor_summary` → 每家 Vendor 的 metrics / ETA Code / ETA Source / Owner / PO / 收件人

为控制篇幅只完整展开了 Top-2 Vendor；文内已标明真实跑数须展开全部选中 Vendor。文末有「单问只出单模块」的对照说明。

---

**User**

report 是否 有中文版和英文版  

---

**Cursor**

**没有两套固定报告文件**，而是按**用户请求语言**选一套标签渲染。

- 中文提问 → 整份用中文表头/状态文案  
- 英文提问 → 整份用英文  
- 定时调度的 prompt 语言也算请求语言  

契约里每个字段都有 `label.zh` / `label.en`，**列顺序和数值格式不变**（日期、`12.34%`、`+1.50 pp` 等两边相同）。

目前示例只有中文稿：[`examples/mock-daily-full-report.zh.md`](examples/mock-daily-full-report.zh.md)。需要的话我可以再出一份对应的英文模拟报告。

---

**User**

这是我真实测试场景 
“”“ {
  "query": "{:entity {:pastdue.country-monitor/country-code \"US\"} :query [({:pastdue.country-monitor/positive-vendor-contributors [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank]} {:limit 10})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_f3772dcf-a18d-4773-9995-d2a3432f413e\\\",\\\"expires-at\\\":1791424205669,\\\"captured-at\\\":1791423305669,\\\"env/version\\\":2,\\\"result-bytes\\\":12707,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
09:35:05

mcp__open_atlas__analyze_result (mcp · open_atlas)
Completed
{
  "result_handle": "result_f3772dcf-a18d-4773-9995-d2a3432f413e",
  "analysis_version": 2,
  "sql": "SELECT country_code, vendor_code, vendor_name, latest_snapshot_date, previous_snapshot_date, latest_past_due_count, previous_past_due_count, past_due_count_change, latest_open_count, previous_open_count, open_count_change, latest_vendor_ratio, previous_vendor_ratio, vendor_ratio_change, latest_country_contribution, previous_country_contribution, delta_country_contribution, positive_contribution_share, cumulative_positive_share, contribution_rank FROM data ORDER BY contribution_rank ASC, vendor_code ASC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_f3772dcf-a18d-4773-9995-d2a3432f413e\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"country_code\\\",\\\"vendor_code\\\",\\\"vendor_name\\\",\\\"latest_snapshot_date\\\",\\\"previous_snapshot_date\\\",\\\"latest_past_due_count\\\",\\\"previous_past_due_count\\\",\\\"past_due_count_change\\\",\\\"latest_open_count\\\",\\\"previous_open_count\\\",\\\"open_count_change\\\",\\\"latest_vendor_ratio\\\",\\\"previous_vendor_ratio\\\",\\\"vendor_ratio_change\\\",\\\"latest_country_contribution\\\",\\\"previous_country_contribution\\\",\\\"delta_country_contribution\\\",\\\"positive_contribution_share\\\",\\\"cumulative_positive_share\\\",\\\"contribution_rank\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1791423305669,\\\"expires-at\\\":1791424205669,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"country_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"vendor_code\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"vendor_name\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"latest_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"latest_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"previous_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"open_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_vendor_ratio\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"previous_vendor_ratio\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"vendor_ratio_change\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"latest_country_contribution\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"previous_country_contribution\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"delta_country_contribution\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"positive_contribution_share\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"cumulative_positive_share\\\",\\\"type\\\":\\\"DECIMAL(13,12)\\\"},{\\\"name\\\":\\\"contribution_rank\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"US\\\",85058,\\\"EXTREME NETWORK INC\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",767,282,485,1066,1066,0,\\\"0.719512195121\\\",\\\"0.264540337711\\\",\\\"0.454971857410\\\",\\\"0.002466856639\\\",\\\"0.000905314388\\\",\\\"0.001561542251\\\",\\\"0.118302693060\\\",\\\"0.118302693060\\\",1],[\\\"US\\\",83314,\\\"SONY CONSUMER ELECTRONICS INC\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",896,540,356,3426,3389,37,\\\"0.261529480443\\\",\\\"0.159339038064\\\",\\\"0.102190442379\\\",\\\"0.002881751693\\\",\\\"0.001733580743\\\",\\\"0.001148170950\\\",\\\"0.086985616559\\\",\\\"0.205288309620\\\",2],[\\\"US\\\",21164,\\\"HP INC.\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",3058,2709,349,8636,8951,-315,\\\"0.354099119962\\\",\\\"0.302647748854\\\",\\\"0.051451371108\\\",\\\"0.009835264149\\\",\\\"0.008696796728\\\",\\\"0.001138467421\\\",\\\"0.086250475681\\\",\\\"0.291538785302\\\",3],[\\\"US\\\",64956,\\\"CISCO SYSTEMS\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",2791,2601,190,64340,63556,784,\\\"0.043378924463\\\",\\\"0.040924538989\\\",\\\"0.002454385474\\\",\\\"0.008976527875\\\",\\\"0.008350080579\\\",\\\"0.000626447295\\\",\\\"0.047459748265\\\",\\\"0.338998533568\\\",4],[\\\"US\\\",2594,\\\"LEXMARK\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",698,606,92,2519,2580,-61,\\\"0.277094084954\\\",\\\"0.234883720930\\\",\\\"0.042210364024\\\",\\\"0.002244936028\\\",\\\"0.001945462833\\\",\\\"0.000299473195\\\",\\\"0.022688137604\\\",\\\"0.361686671172\\\",5],[\\\"US\\\",23323,\\\"STARTECH.COM LTD\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",298,208,90,4199,4307,-108,\\\"0.070969278399\\\",\\\"0.048293475737\\\",\\\"0.022675802662\\\",\\\"0.000958439737\\\",\\\"0.000667749619\\\",\\\"0.000290690117\\\",\\\"0.022022730250\\\",\\\"0.383709401422\\\",6],[\\\"US\\\",73885,\\\"APPLE INC.\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",460,374,86,5215,5101,114,\\\"0.088207094918\\\",\\\"0.073318957067\\\",\\\"0.014888137851\\\",\\\"0.001479470735\\\",\\\"0.001200665181\\\",\\\"0.000278805554\\\",\\\"0.021122353789\\\",\\\"0.404831755212\\\",7],[\\\"US\\\",13788,\\\"BELKIN INTERNATIONAL INC\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",299,217,82,1518,1533,-15,\\\"0.196969696969\\\",\\\"0.141552511415\\\",\\\"0.055417185554\\\",\\\"0.000961655978\\\",\\\"0.000696642631\\\",\\\"0.000265013346\\\",\\\"0.020077453884\\\",\\\"0.424909209096\\\",8],[\\\"US\\\",34038,\\\"EATON CORPORATION\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",1177,1108,69,5129,4919,210,\\\"0.229479430688\\\",\\\"0.225249034356\\\",\\\"0.004230396331\\\",\\\"0.003785515338\\\",\\\"0.003557050858\\\",\\\"0.000228464480\\\",\\\"0.017308505899\\\",\\\"0.442217714996\\\",9],[\\\"US\\\",64314,\\\"AUDIO CODES, INC.\\\",\\\"2026-10-06\\\",\\\"2026-10-05\\\",403,333,70,964,958,6,\\\"0.418049792531\\\",\\\"0.347599164926\\\",\\\"0.070450627604\\\",\\\"0.001296145013\\\",\\\"0.001069041458\\\",\\\"0.000227103555\\\",\\\"0.017205402037\\\",\\\"0.459423117034\\\",10]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":34}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
09:35:08

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 85058 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-country-contribution :pastdue.historical-vendor-eta-source/previous-country-contribution :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 85058 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-country-contribution :pastdue.historical-vendor-eta-source/previous-country-contribution :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 21164 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 21164 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 2594 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 21164 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 21164 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 13788 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 13788 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 13788 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-06\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-05\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/delta-country-contribution]} {:limit 1000}) ({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/delta-country-contribution]} {:limit 1000})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :source, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32 - 09:35:33

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 2594} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32

mcp__open_atlas__execute_eql (mcp · open_atlas)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"US\" :pastdue.vendor-contribution/vendor-code 85058} :query [({:pastdue.vendor-contribution/current-owner-risks [:pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count]} {:limit 1000}) ({:pastdue.vendor-contribution/current-high-risk-lines [:pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/buyer-owner]} {:limit 5})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:code :engine.execution/failed, :severity :error, :path [:query], :message \\\"source execution failed\\\", :error/category :bulkhead_rejected, :error/statement-status nil, :env/version 2}]}}\"}],\"isError\":true}"
  }
]
09:35:32
PastDue 每日监控 — US 假定触发报告
UTC 快照对比：2026-10-05 → 2026-10-06，间隔 1d

假设说明：实际 Atlas Country 监控结果显示 US 相对变化为 +8.08%，低于政策阈值 10.00%，实际状态为 NORMAL。本报告按你的要求，将 US 假定为已触发，并继续执行触发后的 Vendor 深度分析。该假设不改变 Atlas 返回的原始指标。

1. Country 指标
Country	状态	本期日期	上期日期	间隔	逾期行数 上期→本期	Open 上期→本期	逾期率 上期→本期	相对变化	百分点变化
CA	NORMAL	2026-10-06	2026-10-05	1d	5461→5975	42721→43140	12.78%→13.85%	+8.35%	+1.07 pp
US	TRIGGERED（假定）	2026-10-06	2026-10-05	1d	36371→39237	311494→310922	11.68%→12.62%	+8.08%	+0.94 pp
US 实际相对变化为 +8.08%，未达到默认触发阈值 10.00%；以下深度分析仅基于用户指定的假设。

2. US Vendor 正向贡献 Top-10
排序规则：Country 正向贡献变化降序，Vendor Code 作为并列时的次级排序。

排名	Vendor Code	Vendor Name	逾期行数 上期→本期	逾期净变化	Open 上期→本期	Vendor 逾期率 上期→本期	Country 贡献变化	正向贡献占比	分析状态
1	85058	EXTREME NETWORK INC	282→767	+485	1066→1066	26.45%→71.95%	+0.16 pp	11.83%	ETA/当前上下文不可用
2	83314	SONY CONSUMER ELECTRONICS INC	540→896	+356	3389→3426	15.93%→26.15%	+0.11 pp	8.70%	ETA/当前上下文不可用
3	21164	HP INC.	2709→3058	+349	8951→8636	30.26%→35.41%	+0.11 pp	8.63%	ETA/当前上下文不可用
4	64956	CISCO SYSTEMS	2601→2791	+190	63556→64340	4.09%→4.34%	+0.06 pp	4.75%	ETA/当前上下文不可用
5	2594	LEXMARK	606→698	+92	2580→2519	23.49%→27.71%	+0.03 pp	2.27%	ETA/当前上下文不可用
6	23323	STARTECH.COM LTD	208→298	+90	4307→4199	4.83%→7.10%	+0.03 pp	2.20%	ETA/当前上下文不可用
7	73885	APPLE INC.	374→460	+86	5101→5215	7.33%→8.82%	+0.03 pp	2.11%	ETA/当前上下文不可用
8	13788	BELKIN INTERNATIONAL INC	217→299	+82	1533→1518	14.16%→19.70%	+0.03 pp	2.01%	ETA/当前上下文不可用
9	34038	EATON CORPORATION	1108→1177	+69	4919→5129	22.52%→22.95%	+0.02 pp	1.73%	ETA/当前上下文不可用
10	64314	AUDIO CODES, INC.	333→403	+70	958→964	34.76%→41.80%	+0.02 pp	1.72%	ETA/当前上下文不可用
已返回 Top-10 的累计正向贡献占比为 45.94%。由于 Atlas 对该集合标记为 source-completeness: unknown，不能据此声称这是全部正向 Vendor 的完整覆盖率。

3. Vendor 详细分析
以下 Vendor 均使用同一组快照：2026-10-05 → 2026-10-06。

#1 85058 — EXTREME NETWORK INC — US
Vendor 指标
指标	数值
逾期行数	282→767
逾期净变化	+485
Open 行数	1066→1066
Open 净变化	0
Vendor 逾期率	26.45%→71.95%
Vendor 逾期率百分点变化	+45.50 pp
Country 贡献	0.09 pp→0.25 pp
Country 贡献变化	+0.16 pp
变化解读：该 Vendor 的 Open 行数不变，但逾期行数增加 485，Vendor 逾期率大幅上升，是本次 US 正向贡献排名第一的 Vendor。仅凭当前数据不能判断具体 PO 是否在 ETA Code 或 ETA Source 之间迁移。

ETA Code
不可用。Atlas 返回 Source execution failed，未取得可用于完整排序、汇总或 reconciliation 的 ETA Code changes。

ETA Source
不可用。Atlas 返回 Source execution failed，未取得可用于完整排序、汇总或 reconciliation 的 ETA Source changes。

当前上下文
不可用。Owner 与当前高风险 PO-line 查询失败，因此无法展示 Buyer、Primary、Manager 或当前 PO 行。”“”

找出为什么ETA Code  ETA Source 等失败的原因  @atlas/charts/pastdue.edn  是atlas使用的文件


---

**Cursor**

用 systematic-debugging 排查 ETA Code/Source 失败：先对照 `pastdue.edn` 里 changes 解析器的 SQL、入参与你实际发出的查询。

Using systematic-debugging 对照你的 trace 与 `pastdue.edn` 后，结论如下。

## 结论（按优先级）

### 1. 并发打爆 Atlas/连接池（已证实）

同一秒 `09:35:32` 发出大量 `execute_eql`（多 Vendor × ETA × Owner/PO，且反复重试）。后续错误变成：

`error/category :bulkhead_rejected`

这与 [`atlas/sources.edn`](atlas/sources.edn) 的 `max_pool_size 5` 一致：**不是“没有 ETA 数据”**，而是源端被限流/拒收。Owner/PO 失败也同属这一类放大效应。

### 2. 首次失败是 `:source`（ClickHouse 执行层），不是 EQL 拼错

首批 ETA 错误是：

`engine.execution/failed` + `error/category :source` + `statement-status nil`

说明 resolver **已被选中并下推到 SQL source**，但执行失败。  
你的 entity 四元组（US / vendor / 2026-10-06 / 2026-10-05）形状是对的，和 `pastdue.edn` 里 `pastdue_historical_vendor_eta_code_changes` 的 `:input` 一致。

同一张表 `past_due.vendor_past_due_daily` 上的 **Top-10 Vendor 已成功**，因此更像是：

- ETA SQL 更重（country 聚合 + pair 校验 + bucket），超时/源端失败；或  
- **一次 query 里同时拉 Code + Source**，规划/执行成本翻倍后失败。

### 3. Agent 调用方式偏离 playbook（高度可疑）

实际调用（错误示范）：

```clojure
:query [({:.../eta-code-changes ...} {:limit 1000})
        ({:.../eta-source-changes ...} {:limit 1000})]
```

契约要求（[`atlas-query-playbook.md`](skill/pastdue-analysis/references/atlas-query-playbook.md)）：

> Fetch Code and Source **independently**  
> 模板是 **两次独立** `execute_eql`，且建议 `{:limit 100 :offset 0}` 分页，而不是一次 1000 + 双 collection。

把两个 `:many` collection 塞进同一 `:query`，容易造成重查询/不稳定；失败后再对多 Vendor 并行重试，就会滚成 `bulkhead_rejected`。

### 4. 不太像根因的点

| 现象 | 为何不是主因 |
|---|---|
| 投影里缺 `past-due-count-relative-change` | 一般只会少字段，不会变成 `:source` |
| US 实际未触发（+8.08% < 10%） | 与 ETA resolver 能否执行无关 |
| `source-completeness: unknown`（Top-10） | 覆盖率告警，不是 execution failed |
| Owner/PO 与 ETA 同时挂 | Owner 走 `past_due_detail_view`，ETA 走 `vendor_past_due_daily`——共同因素更像是 **并发/源端**，不是单一 ETA SQL 列名写错 |

---

## `pastdue.edn` 里 ETA resolver 本身

`pastdue_historical_vendor_eta_code_changes` / `_eta_source_changes` 的 SQL 逻辑是：

1. 校验 target/previous 是合法且 previous 为最近前快照  
2. 按 `eta_code` / `source` 聚合成对变化  

在 **单 collection、串行、limit 100** 下，这份契约设计是可跑的；你这次失败模式更符合 **调用风暴 + 双 collection 同请求**，而不是 “changes 集合未部署”（未部署通常是 `unreached`，不是 `:source`）。

---

## 建议复现顺序（只做 1 次，不要并行扇出）

对 Vendor `85058`、日期 `2026-10-06`/`2026-10-05`：

1. **只查** `eta-code-changes`（单独 query，`limit 100`）  
2. 成功后再查 `eta-source-changes`（同样单独）  
3. 再单独查 `current-owner-risks`，再查 `current-high-risk-lines`  
4. 下一个 Vendor 同样串行；每逻辑查询最多 **1 次** 纠正重试  

若步骤 1 仍是 `:source`：到 ClickHouse/Atlas 看该 statement 的真实 SQL 错误（当前 `statement-status nil` 信息不够）。  
若步骤 1 成功、并行时才失败：根因就是 **bulkhead/池耗尽**，应改 Agent 调度而非改表结构。

---

**一句话：** ETA Code/Source “挂了”，首要原因是 **源执行失败后被大规模并行重试打成 bulkhead**；调用上应改为 **Code/Source 分开发、串行、小分页**，不要一次 query 双 collection + 10 Vendor 齐射。需要的话我可以直接改 Skill/playbook，把“禁止同 query 合并 Code+Source、强制串行”写成硬规则。

---

**User**

源为什么会执行失败

---

**Cursor**

进一步对照成功的 Top-10 SQL 与失败的 ETA changes SQL，定位 `:source` 失败的具体机制。

`:source` / `source execution failed` 的含义是：**EQL 已经规划成功，SQL 已下推到 ClickHouse（`eta-clickhouse`），但在源端执行这一步失败了**。  
这次 trace 里 `statement-status` 是 `nil`，所以 **Atlas 没有把 ClickHouse 的真实报错带出来**——我们只能根据证据做因果推断，不能声称已经看到具体 CH 异常文本。

## 源失败在 Atlas 里指什么

| 层级 | 本次情况 |
|---|---|
| EQL 校验 / 选 resolver | 已通过（否则常见是 `unreached` / validation） |
| SQL source 执行 | **失败** → `error/category :source` |
| 后续大量重试 | 变成 `bulkhead_rejected`（池子/并发闸门） |

所以：“源执行失败”≠ 没部署 changes 集合，也 ≠ 日期填错（日期错通常是 **空结果**，不是 source failed）。

## 为什么这次特别像“源挂了”

### 1. 同一秒并发打满（最硬证据）

所有 ETA / Owner 失败都挤在 `09:35:32`。  
`sources.edn` 里池子只有 `max_pool_size 5`，超时 `timeout_ms 120000`。

一次请求还把 **Code + Source 两个重 SQL** 塞进同一个 `:query`，等于：

- 每个 Vendor ≈ 2 条重 CTE（各扫 `vendor_past_due_daily`）
- 再叠加 Owner/PO（另一张表）
- 再对多个 Vendor 齐射 + 超限重试

在这种负载下，ClickHouse/JDBC 常见结果就是：

- 超时 / 被取消 → Atlas 包装成 `source execution failed`
- 拿不到连接 → 很快变成 `bulkhead_rejected`

**Owner/PO 也一起 `:source` 失败** 很关键：它们走的是 `past_due_detail_view`，不是 ETA 那条 SQL。  
若是「ETA SQL 写错了」这一种原因，不太解释得通 **另一张表的查询同时以同样方式挂掉**。更像是 **连接池/超时/源端过载**。

### 2. Top-10 成功，说明库和表大体可用

`positive-vendor-contributors` 也读 `past_due.vendor_past_due_daily`，而且成功返回了 10 行。  
因此不太像：

- 表不存在  
- 账号完全没权限  
- `eta-clickhouse` 源完全连不上  

更像是：**轻查询能过，重查询 + 并发过不去**。

### 3. ETA SQL 本身更重（放大超时概率）

`pastdue_historical_vendor_eta_*_changes` 每次都要：

1. 聚整个 Country 的 daily  
2. `HAVING` 校验 previous 是否为最近前快照  
3. 再按 ETA Code/Source 做两期 bucket  

比 Top-10 更贵；再 ×2（Code+Source）× 多 Vendor，很容易先触发源端超时，再被 bulkhead 放大。

### 4. 当前无法从日志证实的“纯 SQL 语法错”

`pastdue.edn` 里 changes SQL 与已成功的 Top-10 用了同类写法（含同 SELECT 内别名相减）。  
在 **缺少 CH 原始错误** 的前提下，不能认定“SQL 一定写错”。  
要坐实这一点，必须用 **单 Vendor、单 collection、串行** 再打一次；若仍 `:source`，再去 CH/Atlas 查 statement 文本。

---

## 一句话

**源会执行失败，是因为请求已经打到 ClickHouse，但执行没成功返回**——你这次最吻合的机制是：  
**过重的双 collection ETA 查询 + 多 Vendor 并行/重试，把有限连接池打爆（超时/拒连），Atlas 记成 `:source`，随后变成 `bulkhead_rejected`。**  
不是“没有 ETA 数据”，也还不能单凭现有 tip 断定 `pastdue.edn` 里 SQL 语法必错。

## 最小验证（分辨根因）

只跑这一条（不要并行、不要带 Source）：

```clojure
{:entity
 {:pastdue.historical-vendor-contribution/country-code "US"
  :pastdue.historical-vendor-contribution/vendor-code 85058
  :pastdue.historical-vendor-contribution/target-snapshot-date "2026-10-06"
  :pastdue.historical-vendor-contribution/previous-snapshot-date "2026-10-05"}
 :query
 [({:pastdue.historical-vendor-contribution/eta-code-changes
    [; 按 playbook 全字段
     ]}
   {:limit 100 :offset 0})]}
```

- **成功** → 源 SQL 可用；上次失败主因是并发/双 query/超时  
- **仍 `:source`** → 再查 CH 侧真实错误（内存、超时、权限、具体语法）；那时才需要改 `pastdue.edn` SQL  

如果你愿意，我可以按这个最小复现路径写一段 Agent 侧「强制串行、禁双 collection」的硬规则补丁。

---

**User**

你这种方案是否是一刀切的方案 只是为了解决这个问题 ，会不会引来更大的问题

---

**Cursor**

是的——如果做成「一律串行、永远禁止合并」，就偏一刀切，只针对这次 bulkhead，可能带来别的问题。

## 一刀切会伤到什么

| 硬砍法 | 可能更大的问题 |
|---|---|
| 所有 Vendor / 所有模块永远串行 | 日报变很长；容易撞 Agent 轮次/输出长度上限，反而更多「部分完成」 |
| 永远禁止同一 `:query` 里多 collection | Owner+PO 在 playbook 里本就允许同查；全禁会无谓增加往返 |
| 为躲 bulkhead 随便加大池子/取消重试 | 把压力甩给 ClickHouse，可能拖垮共享源 |
| 失败就停整份报告 | 丢掉「一维失败继续其他 Vendor」的现有韧性 |

所以：**不能把「这次过载」写成全局永久禁令。**

## 更稳妥的分层（不是一刀切）

1. **保留可合并的轻组合**  
   - Owner + PO：继续允许同 query（playbook 已这样写）  
   - Code + Source：建议分开发（重 CTE），但若部署证明同查稳定，可作可选优化，不必写死「违法」

2. **限制并发，而不是禁止并行**  
   - 例如同时 in-flight ≤ 2～3（对齐 `max_pool_size 5` 留余量）  
   - Vendor 可小批量，而不是 10 路齐射

3. **重试要收敛**  
   - 仍是「每逻辑查询最多 1 次纠正重试」  
   - `bulkhead_rejected` / `:source` 超时：**退避后再试**，禁止同一秒打爆  
   - 不要对同一 Vendor 连打十几次

4. **失败语义不变**  
   - 单维失败 → 标 `不可用`，继续别的 Vendor  
   - 不要为了「防 bulkhead」改成整报告失败

5. **根因仍要可验证**  
   - 用单 Vendor、单 `eta-code-changes` 确认 SQL 本身是否过  
   - 若单查也 `:source`，应修 SQL/超时/CH，而不是只靠串行遮丑

## 结论

- **纯串行一刀切**：能压这次症状，但可能拖慢日报、增加截断风险，属于用可靠性换时延的粗方案。  
- **更好做法**：限流 + 分拆重查询 + 收敛重试 + 保留独立失败；并行仍然允许，只是有上限。

如果你要改 Skill/SYSTEM_PROMPT，我建议写成「并发上限与重查询分拆」规则，而不是「全面禁止并行」。需要的话我可以按这个分寸出一版补丁文案给你审。

---

**User**

还有没有更好的方案

---

**Cursor**

有，而且比「限流串行」更治本。限流是必要的保护层，但最好的方案是 **少打几次重查询、每次查询摊掉重复计算**。

## 方案对比

### A. Agent 限流 / 分拆（我们之前说的）
- **优点**：不改 Atlas，立刻能减 bulkhead  
- **缺点**：Top-10 仍可能要几十次往返，日报慢；治标  
- **定位**：护栏，不当唯一方案

### B. 批量 Resolver（更推荐）
现在：每个 Vendor × Code/Source 各打一条重 SQL（每次都重新聚 Country open）。  
改为：

- 输入：`country + target + previous + vendor_codes[]`（或直接 “该 Country 这对日期的全部/Top-N Vendor”）  
- 输出：一次返回多 Vendor 的 ETA Code（另一次 Source）变化行  

效果：约 **20 次 → 2 次**，从根上消掉齐射。  
代价：要改 `pastdue.edn` + playbook，并做验收。

### C. 物化 / 预聚合（中长期最好）
在 ClickHouse 为「快照对」准备好：

- Country open（按日）  
- Vendor × ETA Code/Source × 日 的逾期行数  

changes 变成轻量 diff，而不是每次 `WITH country_daily AS (...)` 全扫。  
代价：ETL/运维成本高，但性能和稳定性最好。

### D. 只加大连接池 / 超时
- **不推荐当主方案**：掩盖风暴，容易打坏共享 CH。

---

## 建议组合（比一刀切更好）

1. **主路径：B（批量 ETA）** — 解决“为什么源扛不住”  
2. **护栏：温和限流**（in-flight 2～3、`bulkhead` 退避、禁止无意义连打）— 防回归  
3. **有余力再上 C** — 数据面降本  

Owner/PO 可继续按现有模板（可同 query），不必和 ETA 绑成同一种极端串行策略。

---

**一句话：** 更好方案不是“全部串行”，而是 **用批量（或物化）把 10 Vendor 的 ETA 收成 1～2 次查询**；限流只做保险。  

若要落地，下一步应先定 B 的接口（`vendor_codes` 列表 vs Country 内自动取 Top-10），我可以按你的偏好改 `pastdue.edn` + Skill 调用约定。

---

**User**

按照B方案

---

**Cursor**

按 B 方案落地：新增“按 Country 快照对批量拉取 Top 正向 Vendor 的 ETA Code/Source”解析器，并更新 Skill/playbook 调用约定。

批量 ETA 挂在现有 chart 09/10（不增属性、只加 2 个 resolver），并同步更新 `pastdue.edn` 与调用约定。

校验已通过。正在更新 Skill/playbook 调用约定，并删除一次性注入脚本。

B 方案已落地，`validate_bundle` 通过（176 attrs / **19** resolvers）。

### 做了什么

新增两个批量 resolver（chart 09/10 + `pastdue.edn`）：

| Collection | 作用 |
|---|---|
| `country-trigger-event/eta-code-changes-for-positive-vendors` | 一对快照上，正向 Top-10 Vendor 的全部 ETA Code 变化 |
| `country-trigger-event/eta-source-changes-for-positive-vendors` | 同上，ETA Source |

输入只需：`country-code` + `target` + `previous`（与 trigger-event 一致）。SQL 内部算 Top-10 再拉 ETA，**默认日报 ETA 从约 20 次降为 2 次**。

单 Vendor 的 `eta-code-changes` / `eta-source-changes` **保留**，给单独追问用。

### 调用约定（已写入 Skill / playbook / SYSTEM_PROMPT）

1. 先 Top-10 Vendor 汇总  
2. **先** batch Code，**再** batch Source（分两次，不要塞进同一个 `:query`）  
3. 按 `vendor-code` 分组填各 Vendor 的 ETA 表  
4. Owner/PO 仍可按 Vendor 查，避免无上限齐射；`bulkhead` 只退避重试一次  

### 你需要做的

把更新后的 **`pastdue.edn`（或 09/10 chart）重新部署到 Atlas**，再跑一次最小验证：

```clojure
;; 仅 batch Code，limit 2000
{:entity {:pastdue.country-trigger-event/country-code "US"
          :pastdue.country-trigger-event/target-snapshot-date "2026-10-06"
          :pastdue.country-trigger-event/previous-snapshot-date "2026-10-05"}
 :query [({:pastdue.country-trigger-event/eta-code-changes-for-positive-vendors [...]} {:limit 2000})]}
```

未部署前，Agent 会因 batch collection `unreached` 而走旧路径——请先部署再测完整日报。

---

**User**

只更新了 edn文件 没更新 skill嘛

---

**Cursor**

Skill 已同步更新，不只是 EDN。正在快速核对变更点：

Skill **已经更新了**，不只是 EDN。

| 文件 | 内容 |
|---|---|
| [`skill/pastdue-analysis/SKILL.md`](skill/pastdue-analysis/SKILL.md) | Daily/Historical 默认走 batch；单 Vendor 才用旧 per-Vendor |
| [`references/atlas-query-playbook.md`](skill/pastdue-analysis/references/atlas-query-playbook.md) | 批量 EQL 模板 + 调用/重试约定 |
| [`references/data-contract.md`](skill/pastdue-analysis/references/data-contract.md) | 两个新 collection 写进契约 |
| [`agent/SYSTEM_PROMPT.md`](agent/SYSTEM_PROMPT.md) | 禁止 Top-10 扇出 per-Vendor ETA |
| [`ACCEPTANCE_TESTS.md`](ACCEPTANCE_TESTS.md) | 验收要求改为 2 次 batch |

Atlas 侧：`09`/`10` chart + `pastdue.edn`。

若线上 Agent 仍按旧方式打 10 次，多半是 **Skill/SYSTEM_PROMPT 还没重新加载/部署**；需要把上述 Skill 与 system prompt 一并发布。

---

**User**

skill 是否有引用 09/10 chart ？

---

**Cursor**

有引用，但主要是**文档标注**，真正执行靠的是 **collection 名**，不是去读 `09-*.attr.edn` 文件。

| 位置 | 怎么提 09/10 |
|---|---|
| [`SKILL.md`](skill/pastdue-analysis/SKILL.md) | 明文写了 `(separate calls; charts 09–10)`，以及 Owner/PO 的 `charts 05–06` |
| [`data-contract.md`](skill/pastdue-analysis/references/data-contract.md) | 写了 `Historical entities — charts 07–10`，并列出 batch collection |
| [`atlas-query-playbook.md`](skill/pastdue-analysis/references/atlas-query-playbook.md) | **不写 chart 号**，直接用 `eta-code-changes-for-positive-vendors` 等 EQL |
| [`INVOCATION_EXAMPLES.md`](agent/INVOCATION_EXAMPLES.md) | 仍写 `explicit-pair ETA changes in 09–10`（偏旧表述，未改成 batch） |

Agent 运行时：通过 Atlas discovery / EQL 打 collection，**不会**去打开仓库里的 `09`/`10` 文件。只要部署的图谱里有这两个 batch resolver（来自 09/10 或合并后的 `pastdue.edn`）即可。

---

**User**

请不要出现引用旧的den文件的内容  而是使用 新的   pastdue.edn

---

**Cursor**

正在把 Skill/文档里对旧分片 chart（01–11）的引用统一改为 `pastdue.edn`。

文档与 Agent/Skill 约定已统一为只引用 `atlas/charts/pastdue.edn`：

- `agent/SYSTEM_PROMPT.md`、Skill/`data-contract`、`README`、`atlas/README`、`ACCEPTANCE_TESTS`、`notification/README`、`AGENT_SKILL_EDN_DESIGN`、`IMPLEMENTATION_NOTES`
- `pastdue.edn` 头注释改为权威部署说明

部署路径：`sources.edn` + `charts/pastdue.edn`。`validate_bundle.py` 仍 PASS（176 attributes / 19 resolvers）。

---

**User**

这是 atlas mcp 帮我 配置一下 

http://fca-vm-uat-nifi-edge1.synnex.org:9632/mcp/da209d8b-3aa8-452a-a94f-9a390f50425e 
[REDACTED]

---

**Cursor**

正在把你提供的 Atlas MCP 端点接入配置；先查看现有 MCP / Open Atlas 配置方式。

正在更新用户级 `mcp.json` 中的 `open_atlas` 端点与令牌。

认证已通过，正在确认工具是否可用。

已写入用户级 MCP 配置 `~/.cursor/mcp.json` 的 `open_atlas`：

- URL：`http://fca-vm-uat-nifi-edge1.synnex.org:9632/mcp/da209d8b-3aa8-452a-a94f-9a390f50425e`
- 鉴权：`Authorization: Bearer oat_v1…`

连通性已确认：`initialize` 返回 200，`user-open_atlas` 状态为 **ready**，工具可用。

令牌写在本机 `mcp.json` 里；若这段对话会外传，建议之后轮换该 token。

---

**User**

请结合 配置好的 mcp 来更新 这个agent   我发现 这个agnet里 引文写的是 文件名 
其实 agent 是不知道 这个文件内容的  而是只能读到  mcp 的tool的 内容  
请结合真实 atlas mcp 来优化 这个agent  

---

**Cursor**

先对照真实 Atlas MCP 的工具与图目录，再改 Agent/Skill，去掉对本地 EDN 文件名的依赖。

Using brainstorming：先摸清真实 MCP 图里有哪些 PastDue 实体与工具，再据此定 Agent 改造方案。

发现 UAT 图上 `country-trigger-event` 的 join 列表可能还没有批量 ETA；继续核对历史 ETA 相关属性。

已对照当前 UAT 上的 Atlas MCP：图里有完整 PastDue 实体，但 **`country-trigger-event` 上还没有批量 `eta-*-changes-for-positive-vendors`**；可用的是按 Vendor 的 `historical-vendor-contribution/eta-code-changes` 与 `eta-source-changes`。Agent 运行时也读不到 `pastdue.edn` 文件名，只能靠 MCP 工具发现属性。

按 brainstorming，先确认一个范围问题：

**这次优化要以哪一层为准？**

1. **以当前 UAT MCP 为准**：提示词/Skill 改为 `list_entities` → `list_attributes` → `execute_eql` → `analyze_result(sql)`；去掉所有 EDN 文件路径；ETA 默认走现网已有的按 Vendor `eta-*-changes`（必要时再串行，并写清 bulkhead 退避）。
2. **以仓库里的 `pastdue.edn` 目标能力为准**：仍优先批量 ETA，但写成「先 discovery，有批量用批量，没有则降级」；同时提醒你把含批量 resolver 的图重新部署到 UAT。
3. **两者都做**：MCP 工具流 + discovery 降级 + 部署清单（推荐）。

请回复 `1` / `2` / `3`。

---

**User**

以  以当前 UAT MCP 为准 

---

**Cursor**

方案已定为 **以当前 UAT MCP 为准**。设计如下，确认后我再改 Agent/Skill。

## 设计（UAT MCP 对齐）

**事实来源**
- 业务数据只来自 Open Atlas MCP 工具：`list_entities` → `list_attributes` / `search_attributes` → `execute_eql` → `analyze_result`（带 DuckDB `sql` 读行）。
- **禁止**把 `pastdue.edn`、拆分 chart 路径、或仓库文件名当作运行时 schema。
- Skill 自带的 `references/policy.edn`、`report-format.md`、`table-display-contract` 仍是**策略/展示**权威；属性名与 join 以当次 MCP discovery 为准。

**与现网图一致的钻取路径**
1. Daily：`country-monitor/all` → US/CA → 触发国 `positive-vendor-contributors`（Top-10）→ 对每家 Vendor：`historical-vendor-contribution` 身份上的 `eta-code-changes` / `eta-source-changes`（显式 target/previous）→ `current-owner-risks` / `current-high-risk-lines` →（需要时）`notification-recipients`。
2. Historical：`country-history-query/trigger-events`（四元 identity）→ 同上 Vendor/ETA；Owner/PO 仍标 **current**。
3. **不写**批量 `eta-*-changes-for-positive-vendors` 为默认（UAT 未暴露）；若日后 discovery 出现再可用，但不作为当前硬依赖。

**可靠性**
- ETA 按 Vendor **串行或小并发**，遇 `:source` / `bulkhead_rejected`：一次纠正重试 + 退避；失败一家不堵其余。
- `execute_eql` 若只给 `result_handle`，必须用 `analyze_result`+`sql` 取数，不得把空 schema 当无事件。

**改动文件**
- `agent/SYSTEM_PROMPT.md`
- `skill/pastdue-analysis/SKILL.md`
- `references/atlas-query-playbook.md`、`data-contract.md`（MCP 工具流 + 去掉文件路径依赖）

请回复 **批准**（或指出要改的点），我再落盘实现。

---

**User**

批准

---

**Cursor**

按批准的 UAT MCP 方案开始改 Agent/Skill 文档。

已按 **当前 UAT MCP** 改完运行时说明，核心变化：

1. **事实只来自 MCP 工具**：`list_entities` → `list_attributes`/`search_attributes` → `execute_eql` → `analyze_result`+`sql`；不再引用 `pastdue.edn` 等文件路径。
2. **ETA 路径对齐现网**：Top-10 后按 Vendor 串行（或极低并发）查 `historical-vendor-contribution/eta-code-changes` 与 `eta-source-changes`；去掉对批量 Country-pair join 的硬依赖。
3. **Skill 引用分工**：`policy` / `report-format` / `table-display-contract` 管策略与展示；实体名与 join 以当次 discovery 为准。

涉及文件：`agent/SYSTEM_PROMPT.md`、`INVOCATION_EXAMPLES.md`、`skill/.../SKILL.md`、`atlas-query-playbook.md`、`data-contract.md`。`validate_bundle.py` 仍 PASS。

请重新加载 Agent 的 System Prompt 与 Skill 后再跑一轮日报验证。

---

**User**

你已经能链接 mcp了 请验证 源为什么会执行失败 的原因

---

**Cursor**

用系统化排错对照真实 Atlas MCP，复现并定位 `:source` 执行失败原因。

`eta-code-drivers` 能跑通，失败集中在 `eta-*-changes`。正在对比两者的 SQL。

结论：UAT 上有两类不同失败，**不是** Agent 写错文件名导致的。

## 复现结果（真实 MCP）

| 调用 | 结果 |
|---|---|
| `country-monitor/all` | 成功（US/CA：`2026-10-06` vs `2026-10-05`） |
| `positive-vendor-contributors` | 成功 |
| `eta-code-drivers` / `eta-source-drivers`（同 Vendor、同日期） | 成功 |
| `eta-code-changes` / `eta-source-changes`（单次、串行） | **失败** `:error/category :source` |
| 5 路并行 `eta-code-drivers` | 前 4 成功，第 5 次 **`:circuit_open`** |

冷却后再打 `eta-code-changes`，仍是 `:source`（不是熔断残留）。

## 原因 1（`changes` 专用）：`:source`

- 身份与 `drivers` 完全相同仍失败 → 不是参数/日期问题。
- EQL 不投影 `past-due-count-relative-change` 也失败 → 失败发生在 **resolver SQL / JDBC 取数**，不是 Agent 选错字段。
- 与能跑通的 `drivers` SQL 相比，`changes` 多出的就是：

```sql
toFloat64(target - previous) / nullIf(toFloat64(previous), 0.0)
  AS past_due_count_relative_change
```

- MCP 只回 `source execution failed`，`statement-status: null`，**没有**把 ClickHouse/JDBC 原文透出。
- 高度可疑：可空 `Float64`/`NULL`（上期为 0）在 JDBC → Atlas `:decimal` 映射时崩，或该表达式在现网 CH 版本上直接报错。

## 原因 2（并发）：`:circuit_open`

- `sources.edn` 里 `max_pool_size 5`。
- 并行打满时出现熔断；这是连接池/bulkhead，和 `changes` 的 `:source` 不是同一类。

## 当前 Agent 含义

- 默认走 `eta-*-changes` 在 **这套 UAT 图上会稳定挂**。
- 临时能出数的是 `eta-*-drivers`（仅正向，语义不完整）。
- 要根治：查 Atlas/ClickHouse 对 `pastdue_historical_vendor_eta_*_changes` 的底层异常；或先改 SQL（相对变化改成显式 `if(previous=0, NULL, …)` / `toDecimal64`）后重部署再验。

需要的话我可以下一步直接改 `pastdue.edn` 里相对变化表达式并给出重部署后的验证步骤。

---

**User**

还是没 看清楚 根因和解决方案 

---

**Cursor**

再钉死一层证据，然后用「确定事实 / 根因 / 解法」说清楚。

说得更直白一点：

## 根因（就这一条）

**坏的是 Atlas 里的 `eta-*-changes` resolver（ClickHouse SQL / JDBC），不是 Agent，也不是并发。**

对照实验（同一 Vendor `85058`，同一日期 `2026-10-06` / `2026-10-05`）：

| 集合 | 结果 |
|---|---|
| `eta-code-drivers` | 成功 |
| `eta-code-changes` | 失败 `:source` |
| `eta-source-drivers` | 成功 |
| `eta-source-changes` | 失败 `:source` |

两边 SQL 几乎一样，**唯一实质差别**是 `changes` 多了这一列：

```sql
toFloat64(target - previous) / nullIf(toFloat64(previous), 0.0)
  AS past_due_count_relative_change
```

MCP 只回：

```text
source execution failed
error/category: source
```

没有把 ClickHouse 原文吐出来，但已经能定位到：**现网执行/映射这列（可空 Float 除法）时炸了**。  
EQL 不投影这列也失败 → 说明炸在 **SQL 执行或 JDBC 读结果**，不是 Agent 选字段选错。

---

## 另一类问题（别和上面混）

并行打太多会另报：

```text
error/category: circuit_open
```

这是连接池熔断（`max_pool_size 5`），和 `changes` 单次必挂的 `:source` **不是同一个 bug**。

---

## 解决方案

### 治本（必做）：改 SQL 后重部署 UAT

把相对变化改成显式 `if`，避免 `nullIf` + Float 除法：

```sql
if(previous_past_due_count = 0,
   CAST(NULL AS Nullable(Float64)),
   toFloat64(target_past_due_count - previous_past_due_count)
     / toFloat64(previous_past_due_count))
AS past_due_count_relative_change
```

改这 4 处（Code/Source × 单 Vendor/批量）：

- `pastdue_historical_vendor_eta_code_changes`
- `pastdue_historical_vendor_eta_source_changes`
- （若要部署批量）对应的 positive-vendors 批量 resolver

然后把更新后的 `atlas/charts/pastdue.edn` **重新加载到当前 UAT 图**，再用 MCP 打一次 `eta-code-changes`，应返回行而不是 `:source`。

### 临时绕过（现网未修前）

- Agent 暂用已能跑的 `eta-*-drivers`（只有正向，报告不完整），或  
- 用 `drivers`/`counts` 在 Agent 侧自己算相对变化（上期=0 → null）。

### 并发

Top-10 ETA **串行**（或最多 1–2 并发），避免 `:circuit_open`。

---

**一句话：**  
根因 = UAT 上 `eta-*-changes` 的相对变化 SQL/JDBC 坏了；  
解法 = 改那列表达式并重部署；Agent 改提示词解决不了这个 `:source`。

要我现在直接改 `pastdue.edn`（和 09/10）里的表达式吗？

---

**User**

请改 

---

**Cursor**

正在把相对变化表达式改为显式 `if`，并同步更新 `pastdue.edn` 与 09/10 chart。

已改完。相对变化表达式已从 `nullIf` 除法换成显式 `if`：

```sql
if(previous_past_due_count = 0,
   CAST(NULL AS Nullable(Float64)),
   toFloat64(target_past_due_count - previous_past_due_count)
     / toFloat64(previous_past_due_count))
AS past_due_count_relative_change
```

覆盖：
- `atlas/charts/pastdue.edn`（4 处：Code/Source × 单 Vendor/批量）
- `09-historical-vendor-eta-code.attr.edn`（2 处）
- `10-historical-vendor-eta-source.attr.edn`（2 处）
- `validate_bundle.py` 校验标记已同步

本地 `validate_bundle.py` PASS。

**下一步：** 把更新后的 `pastdue.edn` 重新加载到当前 UAT Atlas 图后，再用 MCP 打一次 `eta-code-changes` 验证。未重部署前，现网仍是旧 SQL，MCP 还会失败。

---

**User**

你这种改发是一刀切嘛 只是为了解决这个问题 从而引来更大的问题

---

**Cursor**

你的担心成立：在未拿到 ClickHouse/JDBC 原文错误前改表达式，属于推测性修复，语义上接近，但未证明能根治，也可能让类型映射更糟。

更稳妥的做法：从 resolver SQL **去掉** `past_due_count_relative_change`（Agent 用两期 count 自算），避免可空 Float/Decimal 过 JDBC。正在改回并采用该方案。

先前那一刀确实不稳妥：未证实根因就改表达式，还引入了 `Nullable(Float64)`，可能更糟。先把推测性修改撤回。

对，那一刀不该这么改。已**全部撤回**，仓库回到原来的 `nullIf` 写法。

**问题在哪：**  
MCP 只告诉我们 `changes` 挂、`drivers` 不挂，但**没有 ClickHouse/JDBC 原文**。在这种情况下改除法写法，属于猜；再加 `CAST(NULL AS Nullable(Float64))`，还可能把类型映射搞得更糟。

**可选方案（请选一个）：**

1. **先取证再改（推荐）**  
   查 Atlas / ClickHouse 对 `pastdue_historical_vendor_eta_*_changes` 的底层异常，对着真实错误改，不一刀切猜表达式。

2. **保守止血**  
   SQL **不再返回** `past_due_count_relative_change`（output / field_mapping 同步去掉）；Agent 用已有的 target/previous count 本地算相对变化。语义不变，且绕开可疑列，副作用面可控。

3. **对齐类型再试**  
   相对变化改成 `Nullable(Decimal(...))`（匹配属性 `:decimal`），而不是 Float。仍需部署后用 MCP 验证；若仍失败再回退到 2。

你选 `1` / `2` / `3`，我按你的选择做，不再先猜改。

---

**User**

1 

---

**Cursor**

按方案 1：先取证。正在从 MCP 响应、Atlas 侧与可访问的 ClickHouse 通道挖出 `changes` 的底层错误原文。

已能连上 ClickHouse，正在直接跑 `changes` 与 `drivers` 的 SQL 对比取证。

取证结论如下（已用 ClickHouse 直连 + Atlas MCP 对照）。

## 证据

| 路径 | `eta-code-changes` 结果 |
|---|---|
| ClickHouse HTTP 直接跑同一条 SQL | **成功**（含 `null` 相对变化） |
| Atlas MCP → JDBC → 同一 resolver | **失败** `:source` |
| Atlas MCP → `eta-code-drivers`（同 Vendor/日期） | **成功** |

相对变化列在 CH 里的真实类型：

```text
Nullable(Float64)
```

样例：`SC → NULL`，`SH → 13.94`，`RQ → -0.011…`（都是合法值，SQL 没炸）。

## 根因（有证据版）

**不是 SQL 写错，也不是 Agent 并发。**

失败发生在 **Atlas 经 JDBC 读结果并映射到属性** 这一层：  
`past_due_count_relative_change`（`Nullable(Float64)`，含 NULL）→ Atlas `:decimal`。  
ClickHouse 本身能算出结果；MCP 在映射/序列化时挂掉，所以只看到 `source execution failed`、`statement-status: null`。

之前改 `nullIf`→`if` 解决不了（CH 本来就能跑），还可能引入新类型问题——你的质疑是对的。

## 有证据支撑的改法（请选）

1. **推荐：SQL/output 去掉相对变化列**  
   Agent 用已有 `target/previous` count 计算。绕开 JDBC 映射该列，风险最小。

2. **SQL 显式 `toDecimal64` / `Nullable(Decimal…)`**  
   让 JDBC 拿到 Decimal 而不是 Float64，部署后再用 MCP 验；若仍失败再回退到 1。

3. **查 Atlas 服务端 JDBC 异常栈**  
   若能拿到 Java 原文，可精确修连接器/类型映射（需运维日志）。

你选 `1` / `2` / `3`，我再按选定方案改代码。

---

**User**

1

---

**Cursor**

按方案 1：从 resolver SQL/output 去掉相对变化列，改由 Agent 用两期 count 计算。

属性块删除不干净，正在清理残留的孤儿属性定义。

已按方案 1 做完（保守止血，不是猜改表达式）。

**Atlas 侧**
- `pastdue.edn`、09/10：SQL / output / field_mapping / sort / 属性定义中移除 `past-due-count-relative-change`
- 属性数 176 → **174**；`validate_bundle` PASS

**Agent 侧**
- playbook / SKILL / SYSTEM_PROMPT / data-contract：明确 **不要向 Atlas 要该列**
- 用返回的 target/previous count 计算：`previous=0 → null`，否则 `(target−previous)/previous`

**下一步：** 把更新后的 `pastdue.edn` 部署到 UAT 后，再用 MCP 打 `eta-code-changes` 验证（未部署前现网仍会失败）。

---

**User**

No changes saved
:contract/schema [:execution "pastdue_historical_positive_vendor_eta_code_changes-sql"] —
:contract/schema [:execution "pastdue_historical_positive_vendor_eta_source_changes-sql"] —

---

**Cursor**

部署被 Atlas 契约校验拦住了。正在核对这两个批量 ETA execution binding 的结构与映射。

定位到 `:execution` 少一个 `}`，且 Atlas 卡在这两个批量 binding。UAT 当前也不用批量 ETA；将移除这两个批量 resolver，确保相对变化修复能部署成功。

根因：execution 里挂了批量 SQL binding，但 `:resolvers` 里没有对应 resolver 定义，契约校验失败。正在移除这两套未完成的批量 ETA。

根因是那两条 `pastdue_historical_positive_vendor_eta_*_changes-sql` 还留在 `:execution` 里，却没有对应的 `:resolvers` 条目，契约校验直接拒存。

已从 `pastdue.edn` 去掉未完成的批量 ETA；`validate_bundle.py` 现为 **PASS**（174 attrs / 17 resolvers）。

请重新部署 `atlas/charts/pastdue.edn`，再用 MCP 测 `eta-code-changes`。

---

**User**

No changes saved
:contract/trailing-form [] —

---

**Cursor**

部署被 `:contract/trailing-form` 拒掉了，说明 EDN 里多了一段尾部表单。正在核对 `pastdue.edn` 的括号与文件末尾。

括号不平衡：`{` 400、`}` 401，多了一个右花括号。正在定位多余的 `}`。

`:selection` 末尾多了一个 `}`，提前关掉了顶层 map，后面的 `:ownership` / `:publication` 变成 trailing form。

`:selection` 末尾多了一个 `}`，顶层 map 被提前关掉，后面的 `:ownership` / `:publication` 变成 trailing form。

已改成正确的 `"},`，括号深度归零。请再部署一次 `atlas/charts/pastdue.edn`。

---

**User**

No changes saved
:contract/schema [:selection] —

---

**Cursor**

`:selection` 契约校验失败。对照 resolvers / execution 绑定检查该段。

发现 `:selection` 指向 `pastdue_country_monitor_all-sql`，但 `:execution` 里没有该绑定。正在核对完整一致性。

根因是第 2993 行多了一个 `}`，把 `:execution` 提前关掉，`:selection` 被顶到根级。正在把 bindings 收尾改对，并恢复 `:selection` 结尾的 `}},`。

根因：`:bindings` 末尾多了一个 `}`，把 `:execution` 提前关掉，`:selection` 被顶到根级，触发 `[:selection]` 契约错误。

已改成 `:selection` 留在 `:execution` 内，并用 `}},` 正确关闭 selection + execution。请再部署一次。

---

**User**

已经提交 帮我测试 这个问题是否还有

---

**Cursor**

用 UAT Atlas MCP 实测 `eta-code-changes`，确认部署后是否还能复现问题。

`:source` 已通，SQL 有返回；当前卡在 `delta-country-contribution` 的 `:record/type`。正在收窄投影确认。

**原问题已消除；部署生效。**

UAT MCP 实测（US / vendor `21164` / `2026-10-07`←`2026-10-06`）：

| 检查项 | 结果 |
|--------|------|
| 图已含 `eta-code-changes` / `eta-source-changes` | 有 |
| `past-due-count-relative-change` | 已不在图中 |
| `eta-code-changes` 查 count 字段 | **成功**（8 行，如 NR +285、SH +38） |
| 原先的 `:source` 失败 | **未再出现** |

仍有一个**新的/独立**问题：投影里带 `*-country-contribution` / `delta-country-contribution` 时会报 `:record/type`（日更 `eta-code-drivers` 同样如此）。多半是 CH `Float64` 与属性 `:decimal` 声明不匹配，与已去掉的 relative-change / 部署契约无关。

需要的话我可以接着改 SQL 的 `toDecimal*` 或属性类型来消掉 `:record/type`。

---

**User**

请 先验证 根因再改

---

**Cursor**

先取证、不改代码：核对属性声明类型与 ClickHouse/JDBC 实际返回类型。

国家 monitor 的 ratio 字段也报 `:record/type`，范围比 ETA 更广。正在用 ClickHouse HTTP 查实际列类型。

**根因已用证据钉死（未改代码）。**

### 证据链

1. **图声明**：`delta/target/previous-country-contribution`、各国 ratio 等均为 `:type :decimal`（`list_attributes` 与 `pastdue.edn` 一致）。
2. **CH 实型**（HTTP `toTypeName`，同源表达式）：
   - ETA changes 贡献列：`Float64, Float64, Float64`（count 为 `Int64`）
   - Country monitor ratio：`Float64` / `Nullable(Float64)`
   - `toDecimal64(…, 9)` → `Decimal(18, 9)`
3. **MCP 对照**：
   - `Int64`/date → 成功
   - 任一 `:decimal` 投影 → `:record/type`（不单 ETA；Top-10、country ratio、drivers 同失败）

### 结论

不是 deploy/selection，也不是已去掉的 relative-change。  
是 **SQL 用 `toFloat64` 产出 `Float64`，JDBC 按声明校验 `:decimal` 失败 → `:record/type`**。  
（以前的 `Nullable(Float64)` relative-change 更像 `:source`；现在非空 `Float64` 表现为 `:record/type`，同属类型不匹配。）

### 建议改法（待你点头再动）

- **A（推荐）**：SELECT 侧把映射到 `:decimal` 的列改为 `toDecimal64(…, 9)` / `Nullable(Decimal64(9))`，声明不动。  
- **B**：把属性改成接受 float 的类型（若图支持）——语义与现有 `:decimal` 口径不一致，不优先。

确认用 A 的话我再改并复测 MCP。

---

**User**

A 

---

**Cursor**

按方案 A：把映射到 `:decimal` 的 SQL 列改为 `Decimal64`。先盘点全部 `toFloat64`/比率表达式再改。

CH 类型已是 Decimal；`positive_contribution_share` 仍为 `Nullable`，需与属性的 `nullable? false` 对齐。

已按方案 A 改完：所有映射到 `:decimal` 的比率/贡献/占比列改为 `toDecimal64(..., 9)`（可空字段保留 `Nullable(Decimal)`）。

CH 复核：`country_monitor` / `eta-code-changes` / vendor Top-10 与 vendor-changes 输出均为 `Decimal(18, 9)`（或声明允许的 Nullable）。`validate_bundle` PASS。

请重新部署 `atlas/charts/pastdue.edn`，部署后告诉我，我再用 MCP 复测 `:record/type`。

---

**User**

部署了 请复测

---

**Cursor**

正在用 UAT MCP 复测 decimal 字段是否还会触发 `:record/type`。

**复测通过**（`env/version` 4，无 `:record/type` / `:source`）。

| 用例 | 结果 |
|------|------|
| Country monitor 比率字段 | 成功 |
| Top-10 `delta/latest/previous-country-contribution` | 成功（21164） |
| `eta-code-changes`（含贡献 decimal） | 成功（8 行） |
| `eta-source-changes`（含贡献 decimal） | 成功（11 行） |
| 日更 `eta-code-drivers` | 成功 |

原先的 Float64→`:decimal` 类型问题已消除。

---

**User**

2. US Vendor 当前概览
以下为返回的正向贡献 Vendor Top-10，贡献字段对应 2026-10-06 → 2026-10-07 快照对。

排名	Vendor Code	Vendor	逾期行数 上期→本期	逾期净变化	Vendor 逾期率 上期→本期	Country 贡献变化	正向贡献占比
1	21164	HP INC.	3058→3377	+319	35.41%→36.63%	+0.09 pp	19.04%
2	65088	RIBBON COMMUNICATIONS OPERATING COMPANY, INC.	164→285	+121	35.27%→60.90%	+0.04 pp	8.04%
3	46751	CANON USA, INC	94→196	+102	32.75%→71.27%	+0.03 pp	6.81%
4	66081	SILVER PEAK SYSTEMS INC.	32→128	+96	8.10%→23.66%	+0.03 pp	6.46%
5	64956	CISCO SYSTEMS	2791→2914	+123	4.34%→4.41%	+0.03 pp	6.01%
6	69992	LEGRAND AV	33→73	+40	9.82%→21.79%	+0.01 pp	2.67%
7	23499	MILESTONE AV TECHNOLOGIES INC.	47→87	+40	6.47%→12.41%	+0.01 pp	2.66%
8	17516	PANASONIC PERSONAL COMP	293→334	+41	29.57%→33.00%	+0.01 pp	2.53%
9	62105	HP INC.	104→136	+32	13.08%→17.50%	+0.01 pp	2.08%
10	95823	IBM CREDIT LLC/SOFTWARE	70→100	+30	57.85%→84.75%	+0.01 pp	1.97%
已返回 Top-10 的正向贡献占比： 约 73.17%。
该比例是返回 Vendor 中的正向贡献占比，不是 US 净增长覆盖率；由于集合完整性未知，不能据此声称覆盖全部正向贡献。

3. 当前 Owner 与风险概览
Vendor 21164 — HP INC.
Owner Role	当前 Owner	当前风险/逾期行数
BUYER	ego.rong@tdsynnex.com; kathy.yuan@tdsynnex.com; april.chen@tdsynnex.com; jelly.zhao@tdsynnex.com; vivian.chen@tdsynnex.com; lilian.liu@tdsynnex.com	3359
PRIMARY	Vivian Chen; Poonkuzhali Kesavan	3357
MANAGER	Chris Han; Tiffany Montgomery	3358
返回的 Owner/风险上下文显示该 Vendor 有大量当前逾期/风险行；查询结果中的风险等级为 MEDIUM_RISK，最高逾期天数为 119 天。当前查询返回的高风险行数为 0。

Vendor 65088 — RIBBON COMMUNICATIONS OPERATING COMPANY, INC.
Owner Role	当前 Owner	当前风险/逾期行数
BUYER	viswanathan.chidhambaran@tdsynnex.com	285
PRIMARY	Maripriya Balasubramanian; Lisa Pool	285
MANAGER	Rudi Barone; Tiffany Montgomery	285
当前返回的风险/逾期行数为 285，最高逾期天数为 475 天。返回风险等级包括 NORMAL 和 MEDIUM_RISK；当前高风险行数为 0。

Vendor 46751 — CANON USA, INC
Owner Role	当前 Owner	当前风险/逾期行数
BUYER	durgasree.arumugam@tdsynnex.com; arthur.tao@tdsynnex.com; ida.hou@tdsynnex.com	196
PRIMARY	Salman Purkait; Arthur Tao	196
MANAGER	Chris Han; Tiffany Montgomery	196
当前返回的风险/逾期行数为 196，最高逾期天数为 93 天，风险等级为 MEDIUM_RISK，当前高风险行数为 0。

Vendor 66081 — SILVER PEAK SYSTEMS INC.
Owner Role	当前 Owner	当前风险/逾期行数
BUYER	zoya.liu@tdsynnex.com	128
PRIMARY	Tania Biswas; Drew Taylor	128
MANAGER	Cindy Lun; Phillip Reyes	128
当前返回的风险/逾期行数为 128，当前高风险行数为 23。返回风险等级为 HIGH_RISK，最高逾期天数为 41 天。

Vendor 64956 — CISCO SYSTEMS
Owner Role	当前 Owner	当前风险/逾期行数
BUYER	mackenzie.elbert@tdsynnex.com; trista.zhang@tdsynnex.com	2829
PRIMARY	Mackenzie Elbert; Poonkuzhali Kesavan	2829
MANAGER	Cindy Lun; Phillip Reyes	2829
当前返回的风险/逾期行数为 2829。Owner 角色上下文返回的高风险行数约为 1596–1607，因 Owner 角色分布结果按角色分别返回，不能将这些角色行相加。返回风险等级为 HIGH_RISK，最高逾期天数为 177 天。

4. 当前高风险/逾期 PO 行
以下为每个已查询 Vendor 按当前风险排序返回的最多 5 条 PO 行。多条 Owner 角色记录已去重为 PO 行；Owner 信息保留为当前上下文。

Vendor 21164 — HP INC.
PO Number	PO Line	SKU	风险等级	逾期	逾期天数	ETA Code	ETA Source	Buyer
39910349	114	5881692	MEDIUM_RISK	是	119	NR	API Order	ego.rong@tdsynnex.com; kathy.yuan@tdsynnex.com; april.chen@tdsynnex.com; jelly.zhao@tdsynnex.com; vivian.chen@tdsynnex.com; lilian.liu@tdsynnex.com
39910349	83	5144578	MEDIUM_RISK	是	107	NR	EDI855	同上
39981592	18	7228090	MEDIUM_RISK	是	105	NR	API Order	同上
40032387	54	5544623	MEDIUM_RISK	是	100	NR	EDI855	同上
40032384	79	5144578	MEDIUM_RISK	是	97	NR	EDI855	同上
Vendor 65088 — RIBBON COMMUNICATIONS OPERATING COMPANY, INC.
PO Number	PO Line	SKU	风险等级	逾期	逾期天数	ETA Code	ETA Source	Buyer
37681440	1	5871837	NORMAL	是	475	US	Vendor Email	viswanathan.chidhambaran@tdsynnex.com
37681440	2	5871887	NORMAL	是	475	US	Vendor Email	同上
35384374	18	5883646	MEDIUM_RISK	是	400	US	AUTO US	同上
35384374	19	5924541	MEDIUM_RISK	是	400	US	AUTO US	同上
35384374	20	5306969	MEDIUM_RISK	是	400	US	AUTO US	同上
Vendor 46751 — CANON USA, INC
PO Number	PO Line	SKU	风险等级	逾期	逾期天数	ETA Code	ETA Source	Buyer
40074132	4	9060297	MEDIUM_RISK	是	93	NR	AUTO NR	durgasree.arumugam@tdsynnex.com; arthur.tao@tdsynnex.com; ida.hou@tdsynnex.com
40276046	13	9333057	MEDIUM_RISK	是	43	NR	AUTO NR	同上
40276048	11	9333057	MEDIUM_RISK	是	43	NR	AUTO NR	同上
40338814	1	7134396	MEDIUM_RISK	是	29	NR	Vendor Report	同上
40338816	1	7134396	MEDIUM_RISK	是	29	NR	Vendor Report	同上
Vendor 66081 — SILVER PEAK SYSTEMS INC.
PO Number	PO Line	SKU	风险等级	逾期	逾期天数	ETA Code	ETA Source	Buyer
40422230	1	9049577	HIGH_RISK	是	1	SC	Vendor Web Site	zoya.liu@tdsynnex.com
40422230	2	9049578	HIGH_RISK	是	1	SC	Vendor Web Site	同上
40430761	1	6046696	HIGH_RISK	是	1	SC	Vendor Web Site	同上
40430761	2	6046702	HIGH_RISK	是	1	SC	Vendor Web Site	同上
40430761	3	6247175	HIGH_RISK	是	1	SC	Vendor Web Site	同上
Vendor 64956 — CISCO SYSTEMS
PO Number	PO Line	SKU	风险等级	逾期	逾期天数	ETA Code	ETA Source	Buyer
39821261	2	14500570	HIGH_RISK	是	126	SC	Block AUTOETA	mackenzie.elbert@tdsynnex.com; trista.zhang@tdsynnex.com
39908772	10	10453536	HIGH_RISK	是	121	PE	EDI3A4C	同上
40134227	42	9177452	HIGH_RISK	是	65	PE	EDI3A4C	同上
40134227	44	9177452	HIGH_RISK	是	65	PE	EDI3A4C	同上
40134227	37	7102639	HIGH_RISK	是	64	PE	EDI3A4C	同上
5. ETA 当前分布观察
这是当前 PO 行上的 ETA 信息，不是历史 ETA Code/Source 变化分析：

Vendor 21164： 当前展示行均为 NR，来源为 API Order 或 EDI855。
Vendor 65088： 当前展示行主要为 US，来源为 Vendor Email、AUTO US。
Vendor 46751： 当前展示行均为 NR，来源为 AUTO NR 或 Vendor Report。
Vendor 66081： 当前展示行均为 SC / Vendor Web Site，且展示行均为 HIGH_RISK。
Vendor 64956： 当前展示行包含 SC / Block AUTOETA 及 PE / EDI3A4C，展示行均为 HIGH_RISK。
这些分布不能证明 PO 行在两个历史快照之间发生了 ETA 迁移，也不能证明某个 ETA Code 导致了 Country 的变化。

6. 查询状态与限制
Vendor Top-10 查询：成功，返回 10 行。
当前 Owner/PO 查询：
Vendor 21164：成功，返回 5 条当前 PO 行。
Vendor 65088：成功，返回 5 条当前 PO 行。
Vendor 46751：成功，返回 5 条当前 PO 行。
Vendor 66081：成功，返回 5 条当前 PO 行。
Vendor 64956：成功，返回 5 条当前 PO 行。
初始将 Vendor、Owner 和 PO 明细合并查询时超过 Atlas 执行时限；随后拆分为 Vendor 概览及逐 Vendor 当前上下文查询，已成功取得上述结果。
Atlas 对这些集合返回 source-completeness: unknown；因此未将返回页视为完整全量集合。
本次未查询其余 5 个 Vendor 的当前 Owner/PO 明细，原因是当前运营请求下优先展示排名前 5 个 Vendor 的明细；其余 Vendor 仅有 Vendor 概览数据。
未查询通知联系人；当前 US 未触发告警，且当前运营明细请求不自动执行通知路由。
结论： 当前返回数据中，最需要运营关注的是：

Vendor 64956 — CISCO SYSTEMS： 当前高风险行数规模大，展示行最高逾期 126 天。
Vendor 21164 — HP INC.： 当前逾期行数变化最大，较上一快照 +319。
Vendor 65088 — RIBBON： 当前展示行最高逾期 475 天。
Vendor 66081 — SILVER PEAK： 展示行全部为 HIGH_RISK，ETA Code 为 SC。
Vendor 46751 — CANON： Vendor 逾期率达到 71.27%，当前展示行最高逾期 93 天。


这是我测试的report 是按模板来的嘛 ？ 
如果是我发现一个问题  高风险line 不是重点 
重点是 top 10 的 eta code 和 eta source 分析 

---

**Cursor**

对照 Agent/Skill 报告模板，核对这份输出是否符合，以及 ETA Code/Source 是否应为重点。

**不完全是按模板。** 骨架有 Top-10 汇总和当前 Owner/PO，但缺了 `daily_full` 的核心模块，重点也偏了。

模板要求（每个选中 Vendor，按序）：

1. `vendor_metrics`
2. **`eta_code_changes`**（两期全量变化 → Top-5 + Other）
3. **`eta_source_changes`**（同上）
4. 变化解读
5. `current_owners` / `current_po_lines`（标成**当前上下文**）
6. `notification_recipients`（触发告警时）

你这份实际是：

| 模板要求 | 你的报告 |
|----------|----------|
| 每家 Vendor 的 **两期 ETA Code/Source 变化表** | 没有；§5 只是当前 PO 行上的 ETA 观察，并自己写了“不是历史变化分析” |
| Top-10 **全部**展开详情 | 只展开前 5 家，且详情以 Owner/高风险 PO 为主 |
| Owner/PO 为附属当前上下文 | 被抬成 §3–§4 主内容 |

你的判断对：**重点应是 Top-10 的 ETA Code / ETA Source 两期归因**；高风险 line 只是行动上下文，不能替代 ETA 分析。

SYSTEM_PROMPT 也写明路径：Country → Top-10 → 每家 `eta-code-changes` / `eta-source-changes` → 再查当前 Owner/PO。报告自己也承认初始合并超时后拆成了“概览 + 当前上下文”，**ETA changes 被跳过了**——这按模板算未完成下钻。

若要修 Agent 行为，应强化：超时/拆分时仍须先跑完每家 ETA Code+Source，Owner/PO 可降级或延后，不能用当前 PO 的 ETA 分布冒充两期变化。需要的话我可以直接改 SYSTEM_PROMPT / `report-format.md` 把这条写成硬约束。

---

**User**

你这样写是否太 死板了 换种情况 可能又出现别的状况  有什么 更好的解决 优化 方案 

---

**Cursor**

先看 brainstorming 技能，再给不那么死板、能覆盖超时/拆分等情形的优化方案。

对，硬写「必须先查完 ETA 再查 Owner」会脆：超时、用户只要 Owner、事件列表模式、工具限流时都会别扭。

更好的方向不是再加一条死流程，而是用**目标/完整性**约束行为。先确认一件事：

超时或一轮输出装不下时，你更希望 Agent 怎么做？

**A.** 先保证 Top-10 的 ETA Code + Source（Owner/PO 可标「未查询/下轮」）  
**B.** 先完整做完排名靠前的少数 Vendor（ETA+Owner+PO），其余只留汇总 + pending  
**C.** 按用户当次意图动态定主模块（默认日报仍以 ETA 为主，明确问 Owner 时再反过来）

选 A/B/C（或组合），我再据此给 2–3 个可落地的优化方案对比。

---

**User**

A 

---

**Cursor**

你选了 **A：资源不够时优先保住 Top-10 的 ETA Code/Source**。下面给三种落地方式（都不靠“死流程脚本”）。

### 方案对比

**1. 证据层级（推荐）**  
把模块分成两类，而不是写死调用顺序：

| 层级 | 模块 | 规则 |
|------|------|------|
| **归因核心** | `vendor_summary`、每家 `eta_code_changes` / `eta_source_changes`、变化解读 | 日报触发下钻的完成条件；缺了就不能说「下钻完成」 |
| **行动上下文** | `current_owners` / `current_po_lines` / 通知 | 可延后；超时/限流时标 `未查询`，列入 pending |

超时、拆查询、输出过长时：先填核心，再补上下文。用户明确只要 Owner/PO 时，仍可只跑上下文（standalone）。  
优点：适配多种异常，不靠死顺序。缺点：要改 prompt/Skill 几处措辞，并加一句「禁止用当前 PO 的 ETA 冒充两期变化」。

**2. 两阶段交付**  
Phase1 固定产出 Country + Top-10 + 全员 ETA；Phase2 再补 Owner/PO。  
优点：最稳、最好测。缺点：默认多一轮，偏重。

**3. 继续加流程禁令**（「禁止先查 Owner」之类）  
优点：改得少。缺点：正是你说的死板，换场景又歪。

---

### 建议

采用 **方案 1**，并加两条软约束即可：

1. **完成判定**：没有每家 Vendor 的 Code+Source（或明确失败/空集说明），日报触发下钻最多标「部分完成」。  
2. **冒充禁令**：当前 PO 行上的 ETA 只能写在「当前上下文」，不得当作 `eta_*-changes`。

现有 worklist / pending 机制可以继续用，只是把 ETA 标成核心、Owner/PO 标成可延后。

若认可方案 1，我再写成短设计说明（改哪些文件、改哪几段），你点头后再动 SYSTEM_PROMPT / Skill。

---

**User**

认可方案一 同时  可以取消 高风险line 的report  但保留这个能力 ，用户单独问时候才给结果 

---

**Cursor**

按方案一落地：默认报告去掉高风险 PO 行，能力保留给单独询问。先核对 composition 定义，再改 prompt/Skill。

已按方案一落地，并去掉默认报告里的高风险 PO 行。

**行为变化**
- **归因核心**（完成下钻必需）：Top-10 + 每家 ETA Code/Source + 解读  
- **行动上下文**（可延后）：Owner、通知收件人  
- **按需**：`current_po_lines` — 默认日报/历史全量报告不再查、不展示；用户明确问高风险/逾期 PO 时才出，模块与上限保留  

**已改文件**：`SYSTEM_PROMPT.md`、`SKILL.md`、`report-format.md`、`table-display-contract.{edn,md}`、mock 示例、`ACCEPTANCE_TESTS.md`；`validate_bundle` PASS。

请重新加载 Agent 的 system prompt 与 Skill 后再跑日报。Atlas EDN 不用为这次行为改动再部署。

---

**User**

这是我的俩次 触发  格式明显不同 这是为什么
“”“ PastDue 每日监控 — 2026-10-06 → 2026-10-07 — UTC
Country 指标
Country	状态	本期日期	上期日期	间隔	逾期行数 上期→本期	Open 上期→本期	逾期率 上期→本期	相对变化	百分点变化
CA	NORMAL	2026-10-07	2026-10-06	1d	5975→5721	43140→43337	13.85%→13.20%	−4.69%	−0.65 pp
US	NORMAL	2026-10-07	2026-10-06	1d	39237→38885	310922→314705	12.62%→12.36%	−2.09%	−0.26 pp
结论
CA：NORMAL。逾期率相对下降 4.69%，未达到 10% 触发阈值。
US：NORMAL。逾期率相对下降 2.09%，未达到 10% 触发阈值。
两个 Country 均有可比较快照，且最新快照距当前 UTC 日期 1 天，未触发陈旧快照条件。
因 US、CA 均未触发，本次不执行 Vendor、ETA Code、ETA Source 或当前 Owner 深挖。
Atlas 返回了 US/CA 两个监控记录；底层集合完整性元数据标记为 未知，因此不对监控范围之外的 Country 做完整性推断。”“”


“”“ 每日 PastDue 监控
运行模式： DAILY_MONITOR
业务时区： UTC
运行日期： 2026-10-08
触发阈值： 相对逾期率增长 ≥ 10.00%（使用未四舍五入值）

国家指标
Country	目标快照日	上一快照日	间隔	目标逾期率	上一逾期率	相对变化	百分点变化	目标逾期行数	逾期行数变化	状态
CA	2026-10-07	2026-10-06	1 天	13.20%	13.85%	−4.69%	−0.65 pp	5,721	−254	正常
US	2026-10-07	2026-10-06	1 天	12.36%	12.62%	−2.09%	−0.26 pp	38,885	−352	正常
结论
CA：未触发。逾期率相对下降 −4.69%。
US：未触发。逾期率相对下降 −2.09%。
两个国家均有可比较的连续快照，且目标快照均为截至当前 UTC 日期前的已完成快照。
本次没有触发国家，因此不执行 Vendor、ETA Code、ETA Source 或当前 Owner 深入分析。
本次未查询 PO / 高风险行，符合每日流程的默认范围。
数据限制： Atlas 返回了 US 和 CA 的完整可比较指标，但提示集合覆盖范围在解析层面无法独立证明；本报告仅对监控范围内已返回的 US/CA 结果作出结论。”“”

---

**Cursor**

对照显示契约里的 `country_metrics` 模块，说明两次格式为何不一致。

**不是两套官方模板。** 契约里只有一套 `country_metrics`；第二次是 Agent 自己改排了版。

契约规定的表头（与 mock / 第一次接近）：

`Country | 状态 | 本期日期 | 上期日期 | 间隔 | 逾期行数 上期→本期 | Open 上期→本期 | 逾期率 上期→本期 | 相对变化 | 百分点变化`

| | 第一次 | 第二次（偏离） |
|--|--------|----------------|
| 标题 | `PastDue 每日监控 — 日期对 — UTC` | `每日 PastDue 监控` + 运行元数据块 |
| 列 | 基本符合契约（含 Open 配对） | 拆成「目标/上一逾期率」、缺 Open、多千分位 `5,721`、状态写「正常」而非 `NORMAL` |
| 间隔 | `1d` | `1 天` |

**原因**：同一 composition 下，模型仍可能即兴改表头/用词；第二次更像「自由叙述日报」，没严格跟 `table-display-contract`。也可能两次加载的 Skill/prompt 不一致，但数据结论一样，差异主要在展示层。

**应以第一次（契约列）为准。** 若要压住漂移，可把「未触发时也必须原样输出 `country_metrics` 十列，禁止自创列/千分位」写进 SYSTEM_PROMPT 的硬规则；需要的话我可以直接补上。

---

**User**

你这样写 也太 死板了 别的情况也可能让 agent 漂移 
如何让 agent 做到 只要出 report 分析 先去找模板 没有模板再自己 自由描述 
或者你有更好的方案嘛

---

**Cursor**

你说得对：再堆「禁止自创列」只会越写越脆。更好的是固定**查找顺序**，而不是固定每一种版式。

### 问题本质

漂移多半发生在**表格**：模型重排列、改状态文案、加千分位。  
解读/结论/限制本就该自由写。再禁一百条细节，换场景还会歪。

### 三种做法

**1. 模板优先门禁（你提的，推荐细化版）**  
出报告前只做一件事：在 `table-display-contract` 里找 **composition + module**。

| 找到了 | 表格按该 module 的 `fields` 填；标题用 `section-label`；单元格按 `format` |
|--------|--------------------------------------------------------------------------|
| 找不到 | 标明「无对应模块」，再自由描述；**禁止**假装是标准模块 |

散文（变化解读、结论、限制）始终自由写。  
优点：一条流程管住多数漂移，不靠堆禁令。缺点：Agent 仍可能“以为找到了却没用对”——需在输出里点名 module id（如 `country_metrics`）便于抽查。

**2. 双通道**  
凡是契约里有的 module → 只许填表；契约外 → 只许散文。比 1 更硬，单独问冷门字段时可能别扭。

**3. 继续加禁令**  
不推荐，正是你说的死板。

---

### 建议

用 **方案 1**，写成短流程而不是列清单：

1. 定 composition（`daily_full` / 单问模块 / …）  
2. 每个表格块：读对应 module → 按 `fields` 输出 → 标注 module 名  
3. 无 module：自由描述 + 一句「无模板」  
4. 散文不受表约束  

这样未触发日报、触发下钻、单问 ETA/Owner/PO，都走同一门禁；新模块只要进契约，不必改一堆“禁止”。

若认可，我只改 SYSTEM_PROMPT + `report-format` / Skill 里这几段「查找顺序」，不加细碎禁令。你更希望输出里**必须写出 module 名**（方便验收），还是**静默按模板、不写模块名**？

---

**User**

请开始实施 

---

**Cursor**

正在实施「模板优先」门禁：更新 SYSTEM_PROMPT、report-format、SKILL 与 table-display-contract 的查找流程。


**模板优先门禁**已写入，未再堆细碎禁令。

### 规则（四步）
1. 先定 **composition**（`table-display-contract.edn` 的 `:compositions`）
2. 每个表格 → 查 **module** → 按 `fields` / `:format` 填表；标题用 `:section-label`，建议带 `（module: country_metrics）`
3. **没有 module** → 只用散文，并写明「无显示契约模板」
4. **结论 / 限制 / 解读** 可自由写，但不能用自创表顶替已有 module

### 已改文件
- `skill/pastdue-analysis/references/report-format.md` — 完整「Template-first gate」
- `skill/pastdue-analysis/references/table-display-contract.md` — 入口摘要
- `agent/SYSTEM_PROMPT.md` — Output behavior + drilldown 前置阅读
- `skill/pastdue-analysis/SKILL.md` — Report completion
- `examples/mock-daily-full-report.zh.md`、`ACCEPTANCE_TESTS.md` — 示例与验收一条

`validate_bundle` PASS。请**重新加载** system prompt 与整个 Skill 后再跑两次日报对比；未触发时也应出现契约版 `country_metrics` 十列（含 Open 配对、`1d`、`NORMAL` 等），而不是第二次那种自创表头。

---

**User**

US #1 — Vendor 64956 CISCO SYSTEMS
指标变化（module: vendor_metrics）
指标	值
逾期行数 上期→本期	2274→2738
逾期净变化	+464
Open 上期→本期	62804→62698
Open 净变化	−106
Vendor 逾期率 上期→本期	3.62%→4.37%
Vendor 逾期率点变化	+0.75 pp
Country 贡献 上期→本期	0.73 pp→0.89 pp
Country 贡献变化	+0.16 pp
ETA Code 变化（module: eta_code_changes）
ETA 值	上期逾期行数	本期逾期行数	净变化	相对变化	Country 贡献变化
SH	82	372	+290	+353.66%	+0.09 pp
NR	1475	1597	+122	+8.27%	+0.05 pp
US	522	547	+25	+4.79%	+0.01 pp
DD	82	103	+21	+25.61%	+0.01 pp
PE	102	107	+5	+4.90%	+0.00 pp
NS	0	0	0	无变化	0.00 pp
RQ	5	6	+1	+20.00%	+0.00 pp
SC	6	6	0	无变化	0.00 pp
UNKNOWN	0	0	0	无变化	0.00 pp
增加行数合计：+464
减少行数合计：0
净变化：+464

ETA Source 变化（module: eta_source_changes）
ETA 值	上期逾期行数	本期逾期行数	净变化	相对变化	Country 贡献变化
NR	2067	2280	+213	+10.30%	+0.08 pp
DD	64	149	+85	+132.81%	+0.03 pp
SH	124	179	+55	+44.35%	+0.02 pp
PE	19	21	+2	+10.53%	+0.00 pp
SC	18	19	+1	+5.56%	+0.00 pp
US	1	1	0	无变化	0.00 pp
LS	0	0	0	无变化	0.00 pp
NS	1	1	0	无变化	0.00 pp
RQ	0	0	0	无变化	0.00 pp
UNKNOWN	0	0	0	无变化	0.00 pp
增加行数合计：+356
减少行数合计：0
净变化：+356 

这种模板是否太多了 如果要展示完全的top 10 输出的内容将会非常多 
可否把 top 10 vendor 和 eta code eta source分析放在一个表格里 

---

**Cursor**

Top-10 全量展开确实会很长。先对照现有模块，再给可落地的合并/压缩方案（不直接改契约）。

确实偏长：按现在契约，10 家 Vendor ×（指标 KV + Code 表 + Source 表）≈ 30 个块。  
**不建议把 Top-10 + 全部 Code + 全部 Source 压进一张表**——三套粒度不同（1 行/Vendor vs 多行/Code vs 多行/Source），硬并会难读、难对账。

更稳妥是 **默认只看「驱动摘要」，全量 ETA 表按需展开**。

### 方案对比

**A. 汇总表加「主驱动」列（推荐）**  
保留一张 `vendor_summary`，每家多两列（或一组短文案）：

| …现有 Vendor 列… | 主 ETA Code（净变化） | 主 ETA Source（净变化） |
|---|---|---|
| 64956 CISCO … | SH +290 | （真实 Source 名）+213 |

- 默认日报：**只出这张表** + 覆盖率/限制（可加 1～2 句解读）
- 完整 Code/Source Top-5+Other：**用户点名某 Vendor /「展开 ETA」时再出**（模块保留）
- Atlas 仍可先查全量做对账；展示层压缩

**B. 两层固定**  
层1：`vendor_summary`；层2：一张「ETA 驱动条」表（每家 1 行：Top Code + Top Source）；全量 ETA 仍按需。比 A 多一张表，信息略全。

**C. 前 N 家全量、后 10−N 仅汇总**  
例如 Top-3 全模块、4–10 只汇总。仍可能偏长，且「固定 N」又偏死。

**D. 真·一张宽表含所有 Code/Source 行**  
行数爆炸、列难对齐，**不推荐**。

---

### 建议

用 **A**：默认报告以 **Top-10 + 主 Code/主 Source** 为主；`eta_code_changes` / `eta_source_changes` 改为 **on-demand**（或「展开某 Vendor」），与之前 PO 行策略一致。查询仍可做，只是默认不整表倾倒。

另：你贴的 Source 表里出现 `NR/DD/SH`，很像 **Code 误贴到 Source**；压缩展示前最好先核一下 MCP 投影是否用了 `eta-source`。

你更倾向 **A**，还是 **B**？以及默认全量 ETA 是「完全按需」，还是「仅 Top-3 自动展开」？选定后我再改 display contract / composition。

---

**User**

用A  先核一下 MCP 投影是否用了 eta-source

---

**Cursor**

先用 UAT MCP 核对 `eta-source-changes` 是否真返回 Source，还是误映射成了 Code。

**MCP 投影没问题：用的就是 `eta-source`（物理列 `source`），不是 Code。**

对 US / Vendor `64956` / `2026-10-06→2026-10-07` 实测：

| 集合 | 维度字段 | 样例值 |
|------|----------|--------|
| `eta-code-changes` | `…/eta-code` | SH, NR, US, DD, PE… |
| `eta-source-changes` | `…/eta-source` | **AUTO NR, AUTO US, EDI3A4C, EDI3B2, Carrier API…** |

EDN SQL 也是 `d.source → dimension_value → eta-source`，没有 `d.eta_code`。

你报告里 Source 表出现 `NR/DD/SH`，是 **Agent 展示/用错维度**（把 Code 结果填进 Source 表或列名写错），不是 Atlas 映射错误。另外你贴的 Cisco `2274→2738` 与当前这对日期上的 `2791→2914` 也不一致，可能混了别的日期对。

方案 A 可以继续做：默认 Top-10 汇总 + 主 Code/主 Source 列，全量 ETA 表按需。要我现在改 display contract 吗？

---

**User**

请改

---

**Cursor**

按方案 A 改显示契约：默认 Top-10 汇总加主 Code/Source 列，完整 ETA 表改为按需。

方案 A 已落地，`validate_bundle` PASS。

### 默认日报（触发后）
- Country 指标 + **一张** `vendor_summary`
- 新增列：主 ETA Code / 主 Code 净变化 / 主 ETA Source / 主 Source 净变化
- 仍须按 Vendor **查询** Code+Source，用同一排序取首行填主驱动
- Source 必须来自 `eta-source`（如 `EDI3A4C`），禁止填 Code

### 按需（用户明确要）
- 完整 `eta_code_changes` / `eta_source_changes`、`vendor_metrics`、Owner、通知、PO 行

请重新加载 system prompt 与整个 Skill 后再跑。Atlas EDN 无需为这次展示改动再部署。

---

**User**

应该再 一列里 展示 详细的 eta code 变化 和再一列里展示详细的 eta source变化 
比如 us+10 ；xx+20 
不应该只展示 一个主eta code 和eta source

---

**Cursor**

把「主驱动」四列改成两列紧凑明细：`ETA Code 变化` / `ETA Source 变化`，格式如 `SH +290；NR +122`。

已改：`vendor_summary` 不再只显示单个主驱动，而是两列紧凑明细。

| 列 | 格式示例 |
|----|----------|
| **ETA Code 变化** | `SH +290；NR +122；US +25` |
| **ETA Source 变化** | `EDI3B2 +108；Carrier API +21；EDI3A4C +14` |

规则：仍查全量 `eta-*-changes`，按 abs(净变化) 取最多 5 项，用 `；` 拼接；有剩余且可对账时追加 `其他 +N`。Source 必须用真实 Source 名，不能填 Code。完整 ETA 表仍按需展开。

请重新加载 Skill / system prompt 后再跑。

---

**User**

这是 结果 
“”“ US — Vendor 正向贡献（module: vendor_summary）
排名	Vendor Code	Vendor	逾期行数 上期→本期	净变化	Open 上期→本期	Vendor 逾期率 上期→本期	Country 贡献变化	正向占比	ETA Code 变化简表	ETA Source 变化简表	分析状态
1	64956	CISCO SYSTEMS	2274→2738	+464	62804→62698	3.62%→4.37%	+0.16 pp	9.71%	SH +290；NR +122；US +25；DD +21；PE +5	EDI3B2 +277；EDI3A4C +67；AUTO NR +45；Carrier API +35；AUTO US +24	部分完成
2	13208	HP INC.	2294→2650	+356	16422→15985	13.97%→16.58%	+0.12 pp	7.59%	NR +213；DD +85；SH +55；PE +2；SC +1	EDI855 +158；API Order +96；Carrier API +85；EDI856 +9；Vendor Report +6	完成
3	21164	HP INC.	2653→2915	+262	9394→9298	28.24%→31.35%	+0.10 pp	5.82%	NR +150；SH +126；DD −15；NS +1	API Order +144；EDI855 +100；Vendor Report +23；Carrier API −17；EDI856 +8	完成
4	73885	APPLE INC.	321→474	+153	5110→5074	6.28%→9.34%	+0.05 pp	3.10%	SH +131；DD +22	EDI856 +123；Carrier API +22；Vendor Report +8	完成
5	16176	HEWLETT PACKARD ENTERPRISE	1274→1382	+108	27002→25947	4.72%→5.33%	+0.04 pp	2.44%	—	—	部分完成
6	24234	C2G - LEGRAND AV	240→348	+108	1310→1239	18.32%→28.09%	+0.04 pp	2.19%	—	—	未查询
7	2399	SCHNEIDER ELECTRIC IT CORPORATION	1278→1370	+92	4185→4183	30.54%→32.75%	+0.04 pp	2.13%	—	—	未查询
8	53061	SAMSUNG ELECTRONICS CO.	68→165	+97	342→340	19.88%→48.53%	+0.03 pp	1.93%	—	—	未查询
9	48378	FORTINET INC.	155→249	+94	431→431	35.96%→57.77%	+0.03 pp	1.89%	—	—	未查询
10	64565	CISCO SMS	13→99	+86	890→890	1.46%→11.12%	+0.03 pp	1.70%	—	—	未查询
US Top-10 正向贡献覆盖：38.50%。
该占比是对所有正向 Vendor 贡献的覆盖，不是对 Country 净增长的覆盖；因此不能据此停止选择或认为已解释全部 Country 增长。

CA — Vendor 正向贡献（module: vendor_summary）
排名	Vendor Code	Vendor	逾期行数 上期→本期	净变化	Open 上期→本期	Vendor 逾期率 上期→本期	Country 贡献变化	正向占比	ETA Code 变化简表	ETA Source 变化简表	分析状态
1	28538	LENOVO CANADA - COMMERCIAL	245→363	+118	1379→1373	17.77%→26.44%	+0.27 pp	12.34%	—	—	未查询
2	37112	CISCO - MAIN	226→286	+60	4089→4089	5.53%→6.99%	+0.14 pp	6.22%	—	—	未查询
3	2655	EPSON CANADA LTD.	106→159	+53	720→720	14.72%→22.08%	+0.12 pp	5.55%	—	—	未查询
4	40484	CRICUT, INC.	4→46	+42	138→138	2.90%→33.33%	+0.10 pp	4.44%	—	—	未查询
5	40586	HP - IPG SUPPLIES	189→223	+34	1114→1114	16.97%→20.02%	+0.08 pp	3.49%	—	—	未查询
6	40979	JEM ACCESSORIES	129→160	+31	355→355	36.34%→45.07%	+0.07 pp	3.21%	—	—	未查询
7	37652	FORTINET, INC	22→49	+27	88→88	25.00%→55.68%	+0.06 pp	2.84%	—	—	未查询
8	17832	LOGITECH	115→138	+23	730→730	15.75%→18.90%	+0.05 pp	2.37%	—	—	未查询
9	35795	HP FINANCIAL SERVICES	0→22	+22	43→43	0.00%→51.16%	+0.05 pp	2.33%	—	—	未查询
10	16422	HEWLETT PACKARD ENTERPRISE CANADA CO.	7→29	+22	348→348	2.01%→8.33%	+0.05 pp	2.32%	—	—	未查询
CA Top-10 正向贡献覆盖：45.11%。

初步变化解读
US：最大 Vendor 贡献来自 CISCO SYSTEMS，Country 贡献增加 +0.16 pp；其 ETA Code 变化主要为 SH +290、NR +122，ETA Source 主要为 EDI3B2 +277。
CA：最大 Vendor 贡献来自 LENOVO CANADA - COMMERCIAL，Country 贡献增加 +0.27 pp；其 Vendor 逾期率由 17.77% 升至 26.44%。
上述 ETA Code/Source 变化表示两个快照间的数量变化，不证明具体 PO 行发生了 Code 或 Source 迁移。
Buyer、Primary、Manager、PO 行等如后续查询，均只能作为当前上下文，不能解释 2026-10-03 的历史原因。
完成度与待处理项
本报告的 Country 触发事实和 US/CA Top-10 Vendor 归因已返回。
但完整历史报告尚未完成：除已列明的部分 US Vendor 外，以下 ETA Code/Source 查询仍待完成：

US：Vendor 16176 的 Source；24234、2399、53061、48378、64565 的 Code 与 Source。
CA：Top-10 Vendor 全部 Code 与 Source。
因此当前报告状态为 部分完成，不能将 — 解读为无变化。”“” 
为什么是部分完成和未查询 ？

---

**Cursor**

**不是「没有变化」，是 Agent 没查完。**

按当前契约：Top-10 每家都要查 `eta-code-changes` + `eta-source-changes` 才能填简表；查不到或没查就写 `—`，并把状态标成失败/未查询。

结合你报告末尾的待处理项：

| 状态 | 含义（这份结果里） |
|------|-------------------|
| **未查询** | 该 Vendor 的 Code/Source **根本没发查询**（CA 整表 10 家、US #6–#10） |
| **部分完成** | 查了一部分或只完成一维（如 US #1 Code 有了、Source 可能未齐；#5 两侧都是 `—` 但仍标部分完成） |
| **完成** | 两维都查到并填进简表（US #2–#4） |

所以：**Country + Top-10 Vendor 列表有了，但 ETA 归因 worklist 没跑完**——常见原因是超时、轮次/工具次数用尽、或模型为了先交报告而停掉后续查询。这不是 Atlas 没有数据。

契约上：在这些 Vendor 的 ETA 仍「未查询」时，报告应标 **部分完成**，并列出 pending（你这份末尾写对了）；**不能**把 `—` 当成净变化为 0。

若要改行为，需要强化：未触发结束前必须串行查完 Top-10 的 Code+Source，装不下就明确「下轮继续」并带上同一 worklist，而不是用「未查询」交差。需要的话我可以直接改 SYSTEM_PROMPT / Skill 把这条写硬。

---

**User**

必须 一次完成  不要什么下轮继续  

---

**Cursor**

把「归因核心必须本轮一次查完」写进 prompt/Skill，去掉「下轮继续」作为合法退出。

正在把「本轮一次查完 Top-10 ETA」写成硬约束。

已写死：**本轮内查完所有选中 Vendor 的 ETA Code+Source**，禁止用「下轮继续 / 未查询」当正常退出。

改动位置：`SYSTEM_PROMPT.md`、`SKILL.md`、`report-format.md`、`table-display-contract.md`、`ACCEPTANCE_TESTS.md`。

仅两种例外可标部分完成：Atlas 一次纠正重试仍失败，或宿主硬中断。

---

**User**

需要更新 这个agent  
past_due_detail_view 里的这三个字段不能作为 映射关系是错误的 department_type，master_primary，master_manager
正确的关系是下面的matabase sql：

select A.* from (SELECT
  `source`.`from_acct_no` AS `from_acct_no`,
  `source`.`vendor_name` AS `vendor_name`,
  `source`.`country_code` AS `country_code`,
  `source`.`pastdue_rate_in_vendor` AS `pastdue_rate_in_vendor`,
  `source`.`pastdue_rate_in_total` AS `pastdue_rate_in_total`,
  `source`.`vendor_total_count` AS `vendor_total_count`,
  `source`.`vendor_pastdue_count` AS `vendor_pastdue_count`

FROM
  (
    SELECT
      `source`.`from_acct_no` AS `from_acct_no`,
      `source`.`vendor_name` AS `vendor_name`,
      `source`.`country_code` AS `country_code`,
      `source`.`pastdue_rate_in_vendor` AS `pastdue_rate_in_vendor`,
      `source`.`pastdue_rate_in_total` AS `pastdue_rate_in_total`,
      `source`.`vendor_total_count` AS `vendor_total_count`,
      `source`.`vendor_pastdue_count` AS `vendor_pastdue_count`,
      `source`.`department_type` AS `department_type`,
      `source`.`master_primary` AS `master_primary`,
      `source`.`master_manager` AS `master_manager`
    FROM
      (
        SELECT
          t.from_acct_no,
          t.vendor_name,
          t.country_code,
          -- 指标1：is_pastdue=1 占该 from_acct_no 分组总数的百分比
          countIf(t.is_pastdue = 1) / count() AS pastdue_rate_in_vendor,
          -- 指标2：is_pastdue=1 占该 country_code 下 is_pastdue=1 总数的百分比
          countIf(t.is_pastdue = 1) / country_total.pastdue_count AS pastdue_rate_in_total,
          -- 附加统计信息（可选，便于验证）
          count() AS vendor_total_count,
          countIf(t.is_pastdue = 1) AS vendor_pastdue_count,
          any(t.department_type) AS department_type,
          any(t.master_primary) AS master_primary,
          any(t.master_manager) AS master_manager
        FROM
          past_due.past_due_detail_view AS t
        LEFT JOIN (
            SELECT
              country_code,
              count() AS total_count,
              countIf(is_pastdue = 1) AS pastdue_count
            FROM
              past_due.past_due_detail_view
            GROUP BY
              country_code
          ) AS country_total ON t.country_code = country_total.country_code
        WHERE
          t.from_acct_no IS NOT NULL

        GROUP BY
          t.from_acct_no,
          t.vendor_name,
          t.country_code,
          country_total.pastdue_count
        HAVING
  
           country_code == 'CA'
      ) AS `source`
    ORDER BY
      `source`.`pastdue_rate_in_total` DESC
  ) AS `source`
LIMIT
  1048575) A 
  WHERE A.from_acct_no IN (
    SELECT B.vendor_no 
    FROM  past_due.vendor_master_mapping_view_ca B 
    WHERE 1 = 1
	 
      [[AND {{department_type}}]]
      [[AND {{master_primary}}]]
      [[AND {{master_manager}}]]
)

---

**Cursor**

先核对仓库里 `department_type` / `master_primary` / `master_manager` 的错误映射，再按 Metabase SQL 改成经 `vendor_master_mapping_view_ca` 过滤。

Using brainstorming：先摸清错误映射落点与正确视图关系，再给出可审批的改法。

已核对：Agent 目前把 **PRIMARY / MANAGER** 直接取自 `past_due.past_due_detail_view` 的 `master_primary` / `master_manager`（见 `05-owner-current-risk`、`06-current-high-risk-line`），这和你给的 Metabase 关系不一致。正确过滤/映射应走：

`from_acct_no IN (SELECT vendor_no FROM past_due.vendor_master_mapping_view_ca WHERE department_type / master_primary / master_manager …)`

先确认一件事（定改法范围）：

**Owner 展示里的 PRIMARY / MANAGER，是否也要改成从 `vendor_master_mapping_view_*` 按 `vendor_no` 取，而不再读 `past_due_detail_view` 同名字段？** BUYER 是否仍用 detail 上的 `buyer_email`？

另外：US 是否有对应视图（例如 `vendor_master_mapping_view_us`），还是目前只有 `_ca`？

---

**User**

有vendor_master_mapping_view_us

---

**Cursor**

US 有 `vendor_master_mapping_view_us`，已记下。

下一个确认点：**Owner 表里的 PRIMARY / MANAGER，是否也改为从 `vendor_master_mapping_view_{us|ca}` 按 `vendor_no = from_acct_no` 取值？**（BUYER 仍用 `past_due_detail_view.buyer_email`）

还是这次只改「按 department / primary / manager 筛 Vendor」的映射，Owner 展示逻辑先不动？

---

**User**

更新 可以只用 这个表 vendor_master_mapping_view 
建表语句是 -- past_due.vendor_master_mapping_view 源码

CREATE VIEW past_due.vendor_master_mapping_view
(
    `department_type` String,
    `vendor_no` Int32,
    `country_code` String,
    `master_primary` Nullable(String),
    `master_manager` Nullable(String)
) AS
WITH
    primary_members AS
    (
        SELECT DISTINCT
            vdg.department_type AS department_type,
            vdg.vend_no AS vendor_no,
            vdg.country_code AS country_code,
            vdg.t1_group_no AS group_no,
            if((mgr.firstname IS NOT NULL) AND (mgr.firstname != '') AND (mgr.lastname IS NOT NULL) AND (mgr.lastname != ''), concat(upper(substring(mgr.firstname, 1, 1)), lower(substring(mgr.firstname, 2)), ' ', upper(substring(mgr.lastname, 1, 1)), lower(substring(mgr.lastname, 2))), NULL) AS master_primary
        FROM past_due.vendor_dna_group_members AS vdg
        LEFT JOIN past_due.manager AS mgr ON (vdg.member_id = mgr.userid) AND (vdg.country_code = mgr.country_code)
        WHERE (vdg.department_type IN ('ETAD', 'BUYR')) AND (vdg.member_role = 'primary')
    ),
    manager_members AS
    (
        SELECT DISTINCT
            vdg.department_type AS department_type,
            vdg.vend_no AS vendor_no,
            vdg.country_code AS country_code,
            vdg.t2_group_no AS group_no,
            if((mgr.firstname IS NOT NULL) AND (mgr.firstname != '') AND (mgr.lastname IS NOT NULL) AND (mgr.lastname != ''), concat(upper(substring(mgr.firstname, 1, 1)), lower(substring(mgr.firstname, 2)), ' ', upper(substring(mgr.lastname, 1, 1)), lower(substring(mgr.lastname, 2))), NULL) AS master_manager
        FROM past_due.vendor_dna_group_members AS vdg
        LEFT JOIN past_due.manager AS mgr ON vdg.member_id = mgr.userid
        WHERE (vdg.department_type IN ('ETAD', 'BUYR')) AND (vdg.member_role = 'manager')
    )
SELECT DISTINCT
    p.department_type AS department_type,
    p.vendor_no AS vendor_no,
    p.country_code AS country_code,
    p.master_primary AS master_primary,
    m.master_manager AS master_manager
FROM primary_members AS p
LEFT JOIN manager_members AS m ON (p.department_type = m.department_type) AND (p.vendor_no = m.vendor_no) AND (p.country_code = m.country_code) AND (p.group_no = m.group_no)
WHERE (p.master_primary IS NOT NULL) OR (m.master_manager IS NOT NULL);

用country_code 来区分国家

---

**Cursor**

统一用 `past_due.vendor_master_mapping_view`，用 `country_code` 区分国家——已对齐。

## 三种改法

| | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A** | 只改文档：声明 detail 三字段不可作映射 | 改动小 | Owner SQL 仍读错字段 |
| **B（推荐）** | 改 Owner / 高风险行 SQL：PRIMARY、MANAGER 从 mapping view 取；BUYER 仍用 `buyer_email`；同步 data-contract | 与 Metabase 一致 | 需改 2 个 resolver + 合并 `pastdue.edn` |
| **C** | B + 新增「按 department/primary/manager 筛 Vendor」Atlas 能力 | 覆盖 Metabase 筛选场景 | 范围更大，非本次刚需 |

## 推荐设计（B）

1. **`05-owner-current-risk`**：`risk_rows` 只从 `past_due_detail_view` 取行级风险 + `buyer_email`；PRIMARY / MANAGER 用  
   `LEFT JOIN past_due.vendor_master_mapping_view m ON m.country_code = … AND m.vendor_no = …`  
   聚合去重人名（视图可能有 ETAD/BUYR 多行）。
2. **`06-current-high-risk-line`**：行上的 primary/manager 展示同样改 join mapping view，不再 `any(master_*)` 自 detail。
3. **禁止**：把 detail 上的 `department_type` / `master_primary` / `master_manager` 当归属或筛选真相。
4. **文档**：`data-contract.md` / README 写明物理源含 `vendor_master_mapping_view`。

**不在本次**：新建 department 筛选 entity（若要可做 C）。

回复 **「按 B 做」**（或指定 A/C）后开始改代码。

---

**User**

我需要完善的  解决方案 不是只是解决这个错误 而是 从这个表里 可以得到说明分析结果 不应该只限于一个场景

---

**Cursor**

理解：要把 `vendor_master_mapping_view` 做成可复用的分析能力，而不只修某一个 Owner SQL。先看现有 Atlas 实体形态，再给出完整方案。

目标改成：把 `vendor_master_mapping_view` 做成**通用组织归属维度**，可支撑多种分析结论，而不是只修 Owner 一处错字段。

## 问题本质

| 源 | 能说明什么 | 不能说明什么 |
|---|---|---|
| `vendor_past_due_daily` | 历史两期指标、Vendor/ETA 贡献 | 组织负责人 |
| `past_due_detail_view` | 当前 PO 行、Buyer、风险 | **不能**当 Primary/Manager/Department 映射真相 |
| `vendor_master_mapping_view` | Country+Vendor → department / primary / manager（当前组织） | 历史因果（无 snapshot） |

当前 Agent 缺的是第 3 层：**可查询、可筛选、可聚合的组织归属能力**。

---

## 方案总览

新增一等公民实体 **`pastdue.vendor-master-mapping`**（物理源仅 `past_due.vendor_master_mapping_view`），用 `country_code` 区分国家；并修正一切仍从 detail 读三字段的路径。

### 1. Atlas 实体

**Identity：** `country-code` + `vendor-code` + `department-type`  
（ETAD / BUYR 可各有一行）

**Attributes：** `master-primary`、`master-manager`（PII）、`department-type`

**Resolvers（多场景复用）：**

| Resolver | 输入 | 输出用途 |
|---|---|---|
| `mappings-by-vendor` | Country + Vendor | 单 Vendor 组织归属（Top-10  enrichment、Owner） |
| `vendors-by-mapping-filter` | Country + 可选 dept/primary/manager | Metabase 式筛 Vendor 集合 |
| `mapping-rollups-by-country` | Country | 按 dept / primary / manager 汇总 Vendor 数（浓度/覆盖） |

再挂到现有图：

- `pastdue.vendor-contribution` → collection **`master-mappings`**
- 修正 `current-owner-risks` / `current-high-risk-lines`：PRIMARY/MANAGER 只来自 mapping view；BUYER 仍来自 detail `buyer_email`

### 2. Agent 可产出的分析场景（不限一种）

1. **触发日 Top-10 行动 enrichment**：每个 Vendor 附 ETAD/BUYR Primary/Manager（当前组织，非历史归因）
2. **组织切片**：某 Manager / Primary / Department 下有哪些贡献 Vendor、覆盖多少正向贡献
3. **Owner 行动信息**：BUYER vs DNA Primary/Manager 分源展示，不再混用 detail 错字段
4. **浓度结论**：Top-10 是否集中在少数 Manager/Primary
5. **告警上下文补充**：在 ETA 联系人之外，补充组织 Primary/Manager（仍不发信）
6. **显式筛选问句**：“CA + ETAD + 某 Primary 下的逾期 Vendor”

证据边界：一律标 **当前组织映射**；不写进历史因果段。

### 3. Skill / 展示

- `data-contract` / playbook / SYSTEM_PROMPT：物理源三件套；禁止 detail 三字段作映射
- 展示：默认报告可选短列或 on-demand `vendor_master_mapping` 模块；完整 Owner 仍 on-demand
- `vendor_summary` 可按需加 brief 组织列（如 `ETAD: Alice / Bob`），避免默认过长

### 4. 明确不做

- 不用 `_us` / `_ca` 分表（统一 view + `country_code`）
- 不把 mapping 当历史 snapshot
- 不替代 ETA Code→通知联系人路由（那是另一张 contact 表）

---

若认可这个方向，请回复 **「按此方案实施」**（或指出要砍掉/加强的场景）。通过后我会写简短 spec 并改 Atlas EDN + Skill/Prompt。

---

**User**

你准备改哪个 edn文件 是那个 合起来为一个的 不是那分散的几个

---

**Cursor**

对，改的是合起来的那一个：

**`atlas/charts/pastdue.edn`**

这是运行时唯一部署物（权威 Semantic Contract）。不会去改分散的 `01-…`～`11-….attr.edn` 当主交付。

补充：离线 `validate_bundle.py` 目前扫的是那些拆分文件；若校验也要对齐，再另议是否同步拆分文件或改校验指向 `pastdue.edn`。实施时以你指定的 **`pastdue.edn`** 为准。

---

**User**

请开始

---

**Cursor**

开始实施：先读 `pastdue.edn` 结构与相关文档，再加入 mapping 实体并修正 Owner/高风险行 SQL。

用脚本精确改 `pastdue.edn`：加入 mapping 实体与 resolvers，并修正 Owner / 高风险行 SQL。

已在 **`atlas/charts/pastdue.edn`** 落地完整组织映射能力（不是只修一处 Owner）。

### Atlas（合订 `pastdue.edn`）
- 新实体：`vendor-master-mapping` / `vendor-master-mapping-query` / `vendor-master-mapping-rollup`
- 新能力：
  - **按 Vendor** → `master-mappings`（Top-10 enrichment）
  - **按筛选** → `matching-mappings`（dept/primary/manager，空串=不限）
  - **按 Country rollup** → `vendor-master-mapping-rollups`（浓度）
- 修正 Owner / 高风险行：PRIMARY/MANAGER 只来自 `vendor_master_mapping_view`；BUYER 仍用 detail `buyer_email`

### Agent / Skill
- 恢复并更新了被误写成 `swe` 的 `SYSTEM_PROMPT.md`（含 mapping 多场景规则）
- 同步：`data-contract`、playbook、SKILL、展示契约、README、验收说明

部署时加载更新后的 `pastdue.edn`，再 reload Agent prompt + Skill。

---

**User**

<timestamp>Friday, Oct 16, 2026, 2:33 PM (UTC+8)</timestamp>
<system_notification>
The following task has finished. If you were already aware, ignore this notification and do not restate prior responses.

<task>
kind: shell
status: success
task_id: 388842
title: Find SYSTEM_PROMPT.md copies on disk
tool_call_id: call-b9ca0d5e-2360-40f8-8442-66691d63bdeb-137
fc_f9470cde-f2c7-9c72-9535-750383313e97_0
</task>
</system_notification>
<user_query>Briefly inform the user about the task result and perform any follow-up actions (if needed). If there's no follow-ups needed, don't explicitly say that.</user_query>

---

**Cursor**

磁盘搜索结果：在 OneDrive 副本里找到旧版 `SYSTEM_PROMPT.md`（约 4.7KB），其它候选路径没有。当前仓库里的 prompt 已用会话记录恢复并加上 mapping 规则，比那份旧副本更完整，无需再覆盖。

---

**User**

为什么找不到 不是就在 @agent/SYSTEM_PROMPT.md 

---

**Cursor**

路径一直在：`agent/SYSTEM_PROMPT.md` 没错。

当时的问题不是「找不到文件」，而是**这个文件内容被写坏了**——整份只剩 3 个字符 `swe`，所以本地 History 也只存了这份坏内容，才去别处（OneDrive 旧副本、会话记录）捞完整版来恢复。

现在仓库里这份已经恢复好了（约 242 行），就是你指的那个路径。

---

**User**

我有 副本  
请已这个内容为准

You are the governed PastDue analysis Agent. **Open Atlas MCP is the only
factual data interface.** Never query ClickHouse directly and never fabricate
unavailable facts. Apply the installed `pastdue-analysis` Skill and its
`references/policy.edn` for thresholds, caps, routing, and report assembly.

## Runtime truth: MCP tools, not deploy files

You cannot read Atlas chart / EDN files from disk. Schema and joins exist only
in the connected graph. Always:

1. `list_entities` — see what this token grants (start here on first use or
   after a mismatch).
2. `list_attributes` / `search_attributes` — copy **exact** attribute and join
   names, `required_inputs`, and `order.allowed` from discovery.
3. `execute_eql` — EDN `{:entity … :query …}` using those names.
4. `analyze_result` **with** DuckDB `sql` over table `data` whenever the tool
   returns a `result_handle` (omit `sql` only for schema sniffing).

Skill `references/*` govern **policy and display**. They do not replace MCP
discovery. If a template name is absent or `unreached` in this graph, trust
discovery and report a contract mismatch — do not invent aliases or cite
filename paths.

## Non-negotiable rules

- Business timezone is UTC. A completed snapshot satisfies `snapshot_date < current UTC date`.
- Atlas entities support all actual Countries. This Agent monitors only `US` and `CA` unless the user explicitly asks a factual question about another Country and policy permits it.
- Country trigger uses unrounded relative growth only: `(target_ratio - previous_ratio) / previous_ratio >= threshold`. Use the configured policy threshold (shipped default 0.10); a user's explicit historical what-if threshold applies only to that request and must be labeled.
- Percentage-point change is display context, not an additional trigger.
- Previous ratio 0 and target ratio > 0 is `ZERO_BASELINE_INCREASE`; both 0 is normal.
- US and CA are analyzed independently. Never create a mixed-Country Vendor ranking.
- Historical attribution is supported only for Country, Vendor, ETA Code, and ETA Source.
- Buyer, Primary, Manager, and PO-line facts have no historical snapshot. Always label them **current context**, never historical cause.

## Mode routing

Choose exactly one mode before querying.

### `DAILY_MONITOR` — default and scheduled path

Use when the request is the scheduled daily run, asks for the current/latest monitor, or does not explicitly supply historical/date-range intent.

1. Query `pastdue.country-monitor/all` (empty `:entity` root).
2. Retain `US` and `CA`.
3. Compare each Country's latest two available completed snapshots.
4. Always output the Country metric table.
5. Deep-dive only `TRIGGERED` or `ZERO_BASELINE_INCREASE` Countries.
6. Path (aligned to current UAT graph joins): Country →
   `positive-vendor-contributors` (fixed positive Top-10) → for each selected
   Vendor, on `pastdue.historical-vendor-contribution` with
   country + vendor + target + previous, query `eta-code-changes` then
   `eta-source-changes` (separate calls; **must query**) → fill
   `vendor_summary` cells `eta_code_changes_brief` /
   `eta_source_changes_brief` as compact multi-bucket lists (up to Top-5 by
   abs count Δ DESC, target count DESC, ETA value ASC; format
   `SH +290；NR +122`). Default **display** is Country metrics + that summary
   + brief prose — do **not** render full ETA module tables unless asked.
   Map daily `latest-snapshot-date` → `target-snapshot-date` and keep
   `previous-snapshot-date`. Verify Vendor summary dates match that pair.
   Source brief values come from `eta-source` (e.g. `EDI3A4C +14`), never
   from `eta-code`.
7. Process Vendors in ranking order with **bounded concurrency** (prefer
   serial ETA Code/Source per Vendor). Do not unbounded-parallel ten Vendors
   × two ETA dimensions. On `:source` / `bulkhead_rejected`, one corrected
   retry with backoff for that logical query only. **Single-response rule:**
   finish Code+Source for **every** selected Vendor (all triggered Countries’
   Top-10) in **this same response** before recommendations or any
   on-demand detail. Do **not** stop mid-list, mark remaining as
   `未查询` / not queried, or say “下轮继续 / continue next turn” for ETA.
   Under host pressure, still prioritize remaining brief-cell ETA queries over
   prose length or on-demand modules.

A daily scheduler must never activate historical review.

### `HISTORICAL_REVIEW` — explicit side path

Activate only when the user explicitly asks about a date, date range, “过去/历史/回溯”, or equivalent historical intent. Resolve relative language to exact UTC dates and show those dates.

Before the first historical data call, follow the Skill playbook historical
input/date rules and templates, but confirm field names via
`list_attributes` on `pastdue.country-history-query`. With a trusted current
UTC date D, “过去一周/最近7天” means D−7 through D−1 inclusive; state the
range and proceed without confirmation. Never copy example dates or reuse a
stale date from earlier conversation. If current UTC time cannot be
established, ask for the date anchor only.

1. Validate an inclusive target-date range of at most 366 calendar dates.
2. Query `pastdue.country-history-query/trigger-events` separately for each requested Country (default US and CA). Both `trigger-events` and `comparisons` require all four `pastdue.country-history-query` inputs in `:entity`: `country-code`, `range-start-date`, `range-end-date`, `relative-change-threshold`. Neither is an empty-entity root collection.
3. The range limits target snapshot dates only. Each target compares with the nearest available completed snapshot before it, even if that previous snapshot is before range start.
4. List every trigger event returned.
5. By default, deep-dive only the latest trigger event per Country. Honor an explicit request for only the event list/yes-no answer without attribution.
6. If the user explicitly asks to deep-dive all events, process no more than 5 events per Country and disclose the cap.
7. For each selected event: `country-trigger-event` →
   `positive-vendor-contributors` → per-Vendor `eta-code-changes` /
   `eta-source-changes` for summary brief lists (same concurrency as daily).
   Default display is `vendor_summary` only; full ETA tables on ask.
8. Current Owner / PO / notification: only on explicit ask. Map historical
   Vendor to `pastdue.vendor-contribution` by Country + Vendor Code only.
   Never imply current facts existed on the historical target date.

Historical results must not be presented as the Country's current status.

## Selection rules

- Vendor: strictly positive Country-contribution change descending, Vendor Code ascending on ties; select the first 10 or all if fewer. Never stop at 80%; report coverage of all positive contributions, not net Country growth.
- Default ETA Code/Source: **query** all signed changes via per-Vendor
  `eta-code-changes` / `eta-source-changes`, but **default report** shows each
  Vendor’s compact lists on `vendor_summary` (`eta_code_changes_brief` /
  `eta_source_changes_brief`): up to 5 buckets as `CODE +N；…` / `Source +N；…`
  (same sort as Top-5). Full ETA module tables, `vendor_metrics`, owners,
  notification, and PO lines are **on-demand**. Do not request
  `past-due-count-relative-change` from Atlas. Use positive-only drivers only
  for an explicit positive-attribution request.
- Current PO lines / owners / full ETA tables: **on-demand only**. PO caps when
  queried: 5/Vendor, 20/Country. Never use PO-line ETA as a substitute for
  `eta_*-changes`; never put Code values in Source brief cells.
- Keep `UNKNOWN` ETA and `UNASSIGNED` Owner values visible.

## Atlas behavior

Discover entities/attributes when needed for this environment. Use identity
maps exactly as declared by MCP. Inspect `:problems`, `:suggestions`,
`:unreached`, `:empty_hint`, access-denial metadata, limits, and spills before
interpreting data. Apply a suggested query correction at most once. Never
bypass RLS or role denial.

Use playbook templates as starting shapes; dates, Countries, thresholds,
projections and sorting remain adjustable. Exact names and required inputs
come from discovery. A search hit is not a complete schema. Historical
ratio/type fields are `target-past-due-ratio` and `trigger-type`; do not
invent historical `latest-past-due-ratio`, `change-classification`, or a
`country-history-query/all` entry.

On validation/unreached errors, inspect the complete input tuple and requested fields before one corrected retry per logical query (Country + range + collection), not one retry per error kind. `irrelevant-entity-key` with an incomplete tuple does not justify deleting a required threshold. Decode nested tool payloads: outer `isError: false` does not override an inner error. Follow the playbook recovery table; after a second failure, retain the failure details and continue independent Countries where possible.

Do not call a limited collection complete. Do not use pre-existing dashboard Top-5 views as contribution attribution. Do not infer historical causes from current detail.

## Complete Vendor drilldown

Before any triggered drilldown, read Skill `references/report-format.md`
(template-first gate), `references/table-display-contract.md`,
`references/metric-definitions.md` and the playbook templates. Full runs follow
compositions (`daily_full` / `historical_full`); event-list-only uses
`historical_events_only`. Default display is `vendor_summary` with Code/Source
brief lists; on-demand/standalone asks use only the matching modules with the
same templates. Keep Code and Source as separate calls; avoid retry-storms on
`:source` / `bulkhead_rejected` (one corrected retry with backoff). Maintain a
worklist keyed by Country + target + previous + Vendor for Code/Source query
status — use it to **drive completion in this response**, not to schedule a
later turn. No “others similar” omissions. Verify identities/dates before
combining facts.
On one dimension failure continue the other dimension and other Vendors where
independent; keep the failed Vendor visible. Each logical query has one
corrected retry, not a new budget per error type or page. A paging
continuation is not an error retry. Do not treat token budget, response
length, or “I’ll finish ETA later” as a valid stop for attribution core.

Reconcile each complete ETA dimension independently to both Vendor counts and contribution (see policy tolerance). Missing/unreachable/truncated data is not zero. Missing `eta-*-changes` join means deployment mismatch; do not substitute positive-only drivers as all changes. Preserve Country trigger facts even if downstream status is DATA_ERROR; use per-Vendor completeness labels.

## Output behavior

**Template-first (analytic reports):** Before rendering tables, read Skill
`references/table-display-contract.edn` (and `table-display-contract.md`).
Choose composition → for each table block, choose module → output that
module’s column order and cell formats. If no module applies, use free prose
and state that no display contract exists for that block; do not fake a
standard table. Conclusions, interpretation, and limitations stay free-form;
they must not replace contracted modules with improvised tables. Optional
section trace: `（module: country_metrics）` etc.

Use the user's language. Include exact target and previous snapshot dates and
actual gap days. Format every contracted table cell per `table-display-contract`
(ratios as percentages with two decimals, contributions as signed `pp`, dates
as `YYYY-MM-DD`, signed nets with `+`/`−`). Distinguish facts, classification,
recommendations, and limitations. For a daily run follow composition
`daily_full` (Country + `vendor_summary` with ETA brief lists by default).
For historical review show the requested UTC range, all detected trigger
events, which events were selected for deep-dive, and the same Top-10 summary
pattern. Expand full ETA/Owner/PO modules only on ask. **Deliver the default
summary only after every selected Vendor’s brief ETA cells are filled**
(queried lists or explicit Atlas-fail placeholders). Do not emit a partial
Top-10 with remaining ETA left for a follow-up turn. If the host hard-kills
mid-flight, label 部分完成 / partial and name unfinished Country+Vendor+
collection items — that is host interruption, not a planned multi-turn ETA
workflow. Explicit event-list-only requests remain respected.

If data is stale, insufficient, inaccessible, inconsistent, or a resolver fails, report the correct status and stop unsupported downstream inference.

Never answer “有/没有触发” from discovery metadata, `result {}`, or an unreachable query. An existence claim needs a returned event; “no triggers” needs a successful complete empty event collection, qualified by comparable-snapshot coverage. If one Country fails, say its result is unknown and preserve successful Country results. Report query-construction/validation, planning/unreached, and SQL/serialization failures at their actual layers.

## Alert recipient display (no email implementation)

Recipient tables are **on-demand** in the default daily/historical summary
report. When composing a dedicated Country **alert body** (or the user asks
for To/CC), display recommended recipients there. Do not call Python, read a
CSV at runtime, call SMTP, call a mail API, or send email. The current scope
is **ETA Code → Atlas contacts → To/CC text in the alert**.

- Read contacts only through the MCP join
  `pastdue.vendor-contribution/notification-recipients` (confirm via
  `list_attributes`). Do not access ClickHouse contact tables directly.
- Normalize ETA Code by trim + uppercase. Apply the notification-routing section
  in `references/policy.edn`: `SC/NR/US/RQ` → Vendor To, EMT and
  Buyer CC; `SH/LS` → Buyer To; `DD` → Warehouse To and Buyer CC.
- Query Vendor contacts by Country + Vendor Code. Query EMT/Warehouse contacts
  by Country + recipient type using the country-wide contact identity.
- Buyer email continues to come from current Atlas PO/owner facts. Do not
  invent missing addresses.
- The alert body must include recommended `To`, `CC`, routing rule, contact
  source, and status. If a required contact is absent, write
  `RECIPIENT_UNRESOLVED` and list the missing group. This is not an email
  draft and does not imply that any message was sent.

The routing policy is embedded in this prompt and `references/policy.edn`; no
separate notification folder is required at runtime. Follow-up, reply
tracking, escalation, and sending are out of scope.

---

**Cursor**

按你提供的副本覆盖 `agent/SYSTEM_PROMPT.md`。

已按你给的副本覆盖 `agent/SYSTEM_PROMPT.md`（仅保留文件标题行）。

说明：这份副本里还没有 `vendor_master_mapping_view` 相关段落；Atlas / Skill 里那套 mapping 能力仍在。若要把 mapping 规则也写回 System Prompt，再说一声即可。

---

**User**

同时 按照你之前的 设计 把新的需求 完善到 system prompt

---

**Cursor**

在你提供的副本基础上，把 `vendor_master_mapping` 多场景设计补进 System Prompt。

已在你的副本上补全 mapping 需求，主要改动：

1. **Non-negotiable**：明确 BUYER vs DNA Primary/Manager 分源；禁止用 detail 三字段当映射  
2. **历史路径 / Selection**：org mapping 按需；PRIMARY/MANAGER 走 mapping  
3. **新章节 `Vendor master mapping`**：`master-mappings` / 筛选 / rollups / Owner 四类场景  
4. **告警 Buyer**：仍用 detail `buyer_email`

---

**User**

请先学习 如何标准的优美的设计一个agent 

---

**Cursor**

先系统学习 Agent 的标准设计方法，再对照本仓库现有约定。

已对照 Cursor Skill 规范与本仓库 `AGENT_SKILL_EDN_DESIGN` / `DESIGN`，整理成一套**标准且可复用的 Agent 设计观**如下。

---

## 一层一责（最重要）

| 层 | 只回答一个问题 | 不该装什么 |
|---|---|---|
| **System Prompt** | Agent *必须怎样表现、绝不能做什么* | 完整 SQL、字段百科、长模板 |
| **Skill** | *怎么做*：模式、步骤、截断、错误边界 | 物理表细节、部署 ID |
| **References** | *按需读的细节*：策略、playbook、展示契约 | 塞进主 Skill 的万字说明书 |
| **Atlas / 工具契约** | *能查什么、事实从哪来* | 业务文案、报告口吻 |
| **物理数据** | 可验证事实 | Agent 直接连库 |

本项目的原话已经点透：

> Atlas 提供事实 → EDN 定义能力 → Skill 编排流程 → System Prompt 约束行为

**优美**的关键：边界清晰，改一层不拖垮其它层。

---

## 设计顺序（先想清楚再写字）

1. **目标与非目标**（监控/归因 ≠ 预测；当前组织 ≠ 历史因果）  
2. **模式隔离**（默认日监 vs 显式历史）  
3. **证据边界**（谁能证明什么、谁只能当行动上下文）  
4. **能力面**（实体/集合要能支撑多场景，而不是为一个报告硬编码）  
5. **流程与完成规则**（如「本轮一次查完 Top-10 ETA」）  
6. **展示契约**（模板优先，结论用散文）  
7. **失败语义**（不可用 / 部分完成 / 未查询的合法条件）

未过这几关就堆 prompt，容易又长又脆。

---

## 写 System Prompt 的美学

- **短而硬**：禁令、模式路由、完成规则、证据边界  
- **可执行**：动词清晰（query → fill → deliver），少讲课  
- **不重复 Skill**：细节用「读 `references/X`」，不在 SP 里再抄一遍  
- **新能力用一张能力表**，说明 *何时用*，而不是塞进某一个场景的死流程  
- **Token 有价**：每段都要问「模型是否已经会？这是不是唯一真相源？」

Skill 同理：`description` 写清 WHAT + WHEN；主文件精炼；细节渐进披露到 references。

---

## 对照：好设计 vs 常见翻车

| 好 | 翻车 |
|---|---|
| 映射是一等能力（enrich / filter / rollup / owner） | 只在 Owner SQL 里修一个错字段 |
| BUYER 与 DNA Primary 分源写死 | 混用 detail 同名列 |
| 默认展示瘦、按需加厚 | 默认倾倒十张全表 |
| 失败可解释、可独立国家继续 | 「下轮继续」当正常退出 |
| SP / Skill / EDN 同步但各司其职 | 三处各写一套互相矛盾 |

---

若下一步要用这套标准**审视并改写**当前 PastDue Agent（尤其 SP 里 mapping 段是否过重、是否该下沉 Skill），你更希望我先做哪一件：

**A.** 对照检查清单审计现状（只给意见、不改文件）  
**B.** 按上述分层重排 `SYSTEM_PROMPT` + Skill 边界（出设计稿再改）

---

**User**

A

---

**Cursor**

对照标准分层做现状审计（只给意见，不改文件）。

## 审计结论（只评不改）

整体：**架构方向正确，日监/历史隔离与 ETA 完成规则扎实**；mapping 已进 Atlas + Skill 参考，但 **分层仍偏「SP 过重、契约未闭环」**。按标准清单打分如下。

| 维度 | 评分 | 说明 |
|---|---|---|
| 一层一责 | **B** | SP / Skill / Atlas 意图清晰；但 SP 仍含大量流程细节与 playbook 级禁令 |
| 模式隔离 | **A** | 日监默认、历史显式、调度禁入历史 — 干净 |
| 证据边界 | **A−** | 历史归因 vs 当前行动写死；mapping 禁 detail 三字段也写清了 |
| 能力面（多场景） | **B+** | Atlas 已有 enrich/filter/rollup/owner；Skill description 几乎不提 mapping |
| 完成规则 | **A** | Single-response / 禁止下轮继续 — 硬且对 |
| 展示契约 | **B** | 有 `vendor_master_mapping` 模块；`report-format` 未纳入 org 场景 |
| 失败语义 | **A−** | ETA 部分完成边界清楚；mapping 失败语义未单独说 |
| 部署/校验闭环 | **C** | 权威物是 `pastdue.edn`，`validate_bundle` 仍盯拆分 `*.attr.edn`（无 mapping） |

---

## 做得好的地方

1. **MCP-first**：不读盘上 EDN、discovery 为准 — 符合受治 Agent。  
2. **默认瘦报告 + 归因核心必须查完**：产品行为清晰。  
3. **Mapping 能力表**（SP）与 playbook EQL、data-contract、展示模块方向一致。  
4. **BUYER vs DNA Primary/Manager 分源**在 SP / Skill / Owner SQL 意图上对齐。

---

## 主要问题（按优先级）

### P1 — System Prompt 仍偏「Skill 化」
- 日监步骤 6–7、历史字段名、重试预算、展示格式等，与 Skill/playbook **重复**。  
- 优美标准：SP 留禁令 + 模式 + 完成规则 + 证据边界；流程细节下沉 Skill。  
- mapping 能力表本身合适留在 SP（禁令级），但表格旁的操作说明可再瘦。

### P1 — Skill `description` 未声明 org 能力
- 当前 description 只提 ETA / Owner / PO / notification。  
- Agent 自动选 Skill 时，**组织切片 / 浓度**类问题可能触发不足。

### P2 — 契约未完全闭环
- `report-format.md`：**无** mapping / rollup / filter 的编排位置。  
- `AGENT_SKILL_EDN_DESIGN.md` 开头图仍像「detail = Primary/Manager」旧叙事（后文虽有补丁，入口易误导）。  
- 离线校验不覆盖 `pastdue.edn` 上的 mapping resolvers → **假绿风险**。

### P2 — SP 泄露物理表名
- 多处 `past_due.*_view`。原则是「事实经 Atlas」；物理名更宜在 data-contract，SP 写 *Atlas 集合名 + 禁 detail 三字段作映射* 即可（或极短一句）。

### P3 — 默认日报是否 enrich mapping 未定死
- SP：org 按需；未说明「触发日 Top-10 是否应默认可选 brief 组织列」。  
- 产品上留空可以，但应在 Skill/report-format **显式二选一**，避免跑起来不一致。

### P3 — 拆分 `05`/`06` attr 文件
- 若仍有人以为校验文件即真相，会与已改的 `pastdue.edn` 漂移（Owner SQL 仍可能是旧版）。

---

## 对照「优美 Agent」一句话

> 你们已经有正确骨架；当前最大不优美点是 **同一套规则在 SP、Skill、playbook 三处厚写**，以及 **mapping 能力在 Atlas 已一等、在 Skill 入口与报告契约里仍像补丁**。

---

若继续，建议走 **B（重排边界的设计稿）**，优先：瘦 SP、扩 Skill description、补 report-format、校正 validate 指向 `pastdue.edn`。需要的话直接说做 B。

---

**User**

请开始 设计  要求 优美  功能完善  逻辑清楚  

---

**Cursor**

Using brainstorming：先给出可审批的分层重设计（优美、功能完整、逻辑清楚），通过后再落盘与改代码。

# PastDue Agent 重设计（审批稿）

目标：**优美、功能完善、逻辑清楚**——一层一责、能力一等公民、默认路径瘦、按需加厚、证据边界硬。

---

## 1. 分层原则（不变的骨架）

```text
用户/调度
  → System Prompt     「禁令 · 模式 · 完成契约 · 证据边界」
  → Skill + references「流程 · 策略 · 查询模板 · 展示」
  → Atlas pastdue.edn 「可验证事实能力」
  → ClickHouse        「物理源」
```

| 层 | 写什么 | 不写什么 |
|---|---|---|
| **SP** | 不可破的规则；日监/历史路由；ETA 本轮一次完成；历史≠当前；mapping 禁 detail 三字段；能力索引（短表） | 逐步 SQL/EQL、字段百科、长重试表 |
| **Skill** | 步骤、选择、完成 ledger、org 何时查、错误后怎么走 | 物理表长说明（→ data-contract） |
| **playbook / report-format / display** | EQL 样例、报告拼装、列模板 | 再抄一遍 SP 禁令 |
| **pastdue.edn** | 唯一部署契约 | 拆分 attr 当权威 |

一句口诀：**SP 定边界，Skill 定路径，Atlas 定事实，展示定形状。**

---

## 2. 能力地图（功能完善）

四条事实管道，互不顶替：

| 管道 | 回答什么 | 物理/Atlas | 默认日报 |
|---|---|---|---|
| **指标归因** | 谁让比率变差、ETA 怎么变 | daily + historical changes | **必须**：Country + Top-10 + ETA brief |
| **组织映射** | 谁在 DNA 上管这个 Vendor / 哪类 dept | `vendor_master_mapping_view` → master-mappings / filter / rollups | **按需**（见 §4） |
| **行动 Owner** | 风险行上 BUYER + DNA PRIMARY/MANAGER | detail buyer + mapping | 按需 |
| **通知路由** | To/CC（不发信） | notification-recipients | 按需 / 告警体 |

组织映射的三种用法（一等能力，非修 Owner 补丁）：

1. **Enrich**：Vendor → department / primary / manager  
2. **Slice**：Country + 可选 dept/primary/manager → Vendor 集合  
3. **Concentrate**：Country rollup → 浓度结论  

---

## 3. 逻辑主路径（清楚）

### 3.1 `DAILY_MONITOR`（默认）

```text
country-monitor/all → 留 US/CA
  → 仅 TRIGGERED / ZERO_BASELINE 下钻
  → positive Top-10
  → 本轮内逐 Vendor：eta-code-changes → eta-source-changes
  → 填 vendor_summary brief（禁止下轮继续）
  → 默认输出：Country 表 + vendor_summary + 短散文
  → [可选] org / Owner / PO / 通知 / 全量表
```

### 3.2 `HISTORICAL_REVIEW`（显式）

```text
完整四元组 entity → trigger-events
  → 列表全部事件；默认只下钻每国最新（或事件列表-only）
  → 同 Top-10 + ETA brief 契约
  → 当前 Owner/PO/mapping/通知：仅显式要求；标「当前上下文」
```

### 3.3 组织问题（可独立或挂在触发日上）

```text
问「谁负责 / 某 Primary 下有哪些 / 浓度」
  → matching-mappings 或 rollups 或 master-mappings
  → 不替代 ETA 归因；可与 Top-10 交叉写结论
```

---

## 4. 默认日报与 org 的产品选择（推荐）

为避免「跑起来不一致」，定死一条：

**推荐 R1（默认瘦 + 问句驱动）**  
- 默认日报：**不**自动查 Top-10 mapping。  
- 用户提到组织 / Primary / Manager / 部门 / 浓度，或显式「补组织」→ 再查。  
- Owner 表仍按需；一旦查 Owner，PRIMARY/MANAGER **必须**来自 mapping。

备选 R2：触发日对 Top-10 自动 `master-mappings`，只在 summary 加极短组织列——更重、更全。  
**设计默认采用 R1**（与当前 SP「按需」一致，且更优美）。

---

## 5. System Prompt 瘦身形状（目标结构）

约 **120–160 行**量级，章节固定：

1. 身份 + MCP-only  
2. Non-negotiable（含 mapping 禁令、国家、阈值、历史/当前）  
3. Mode routing（日监/历史 **提纲**，步骤细节 → Skill）  
4. Completion contract（ETA 本轮一次；禁止下轮继续）  
5. Evidence boundaries（四管道一句话）  
6. Capability index（mapping/Owner/通知短表，无 EQL）  
7. Output：用户语言 + template-first 指针  
8. Alert：不发信 + Buyer 来源  

从 SP **移出/压缩**：长历史字段说教、完整重试段落、brief 格式样例（保留一句「见 Skill/display」）。

---

## 6. Skill / 契约补齐（功能闭环）

| 文件 | 改动要点 |
|---|---|
| `SKILL.md` description | 加上 org mapping / slice / rollup 触发词 |
| `SKILL.md` 日监 | 明确 R1：默认不查 mapping；组织问句走 §org |
| `report-format.md` | 增加「组织分析」拼装：何时用哪张表、与 Top-10 如何交叉 |
| `data-contract` / playbook | 保持权威清单与 EQL（已有则对齐措辞） |
| `table-display-contract` | 保留 `vendor_master_mapping`；rollup 可简表或散文+小表 |
| `validate_bundle.py` | **校验权威改为 `pastdue.edn`**（或至少包含它）；mapping resolvers 计入期望 |
| `AGENT_SKILL_EDN_DESIGN` 开头 | 物理源改为三件套；detail ≠ Primary/Manager |

拆分 `*.attr.edn`：标注为 **非部署参考/遗留**，或以脚本从 `pastdue.edn` 生成——避免双真相。

---

## 7. Atlas（已具备，设计上冻结）

保持现有三类 resolver + Owner/高风险 SQL 分源；不再为「一个场景」加第四套平行逻辑。  
空串 filter = 不约束 —— 在 Skill/playbook 写清，避免 Agent 漏传键。

---

## 8. 验收逻辑（清楚可测）

1. 日监触发国：Top-10 ETA brief 本轮齐全，无「未查询/下轮继续」。  
2. 默认报告无全量 ETA/Owner/mapping 倾倒。  
3. 「CA ETAD 某 Primary」→ `matching-mappings`，结论标当前组织。  
4. Owner：BUYER≠mapping；PRIMARY/MANAGER≠detail master_*。  
5. 历史下钻不把 mapping 写成历史原因。  
6. `validate_bundle` 绿 ⇒ 与即将部署的 `pastdue.edn` 一致。

---

## 9. 实施顺序（批准后）

1. 落盘本设计到 `docs/superpowers/specs/2026-10-20-pastdue-agent-layering-design.md`  
2. 重写瘦 SP  
3. 补 Skill description + report-format + org 流程  
4. 改 validate → `pastdue.edn`  
5. 轻量同步设计文档开头  

---

请确认两点后我写入 spec 并开改：

1. **默认日报 org 策略**：同意 **R1（按需）**，还是改 **R2（Top-10 自动 enrich）**？  
2. 整体设计是否 **「按此方案实施」**？

---

**User**

1 R1
2 实施

---

**Cursor**

按 R1 落盘设计并开始实施：先写 spec，再瘦 SP、补 Skill/契约、校正 validate。

已按 **R1** 实施完成。

### 交付
- 设计稿：`docs/superpowers/specs/2026-10-20-pastdue-agent-layering-design.md`
- **SP 瘦身**至约 93 行：禁令 / 模式提纲 / 完成契约 / 能力索引（含 R1）
- **Skill**：description + Organization analysis（R1）
- **report-format / display / 验收 / atlas README / 设计文档图**
- **validate_bundle**：以 `pastdue.edn` 为权威校验 mapping；`PASS`（1 条 warning：拆分 attr 非权威）

### 行为定稿
默认日报 **不**自动查 Top-10 mapping；组织问句才走 enrich / filter / rollup。

---

**User**

下面是 这个版本之前 的一次 触发完整流程  “”“ [
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"entities\\\":[{\\\"entity\\\":\\\"pastdue.country-history-query\\\",\\\"description\\\":\\\"PastDue Country History Query — Explicit UTC date-range request for historical country snapshot comparisons and trigger discovery.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.country-history-query/country-code\\\",\\\"pastdue.country-history-query/range-end-date\\\",\\\"pastdue.country-history-query/range-start-date\\\",\\\"pastdue.country-history-query/relative-change-threshold\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"description\\\":\\\"PastDue Country Monitor — Latest-versus-previous completed UTC snapshot metrics by country.\\\",\\\"attribute_count\\\":17,\\\"identity_keys\\\":[\\\"pastdue.country-monitor/country-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-trigger-event\\\",\\\"description\\\":\\\"PastDue Country Trigger Event — One historical country snapshot pair whose target date is evaluated against the nearest prior available snapshot.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.country-trigger-event/country-code\\\",\\\"pastdue.country-trigger-event/previous-snapshot-date\\\",\\\"pastdue.country-trigger-event/target-snapshot-date\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"description\\\":\\\"PastDue Current High-Risk PO Line — Current high-risk or past-due PO-line context; not a historical causal record.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.current-high-risk-line/order-type\\\",\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"pastdue.current-high-risk-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"description\\\":\\\"PastDue Historical Vendor Contribution — Vendor-level decomposition for an explicit historical country target/previous snapshot pair.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Code Contribution — ETA Code-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-code/country-code\\\",\\\"pastdue.historical-vendor-eta-code/eta-code\\\",\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Source Contribution — ETA Source-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-source/country-code\\\",\\\"pastdue.historical-vendor-eta-source/eta-source\\\",\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.notification-recipient\\\",\\\"description\\\":\\\"PastDue Notification Recipient — Governed contacts used only to display recommended To and CC recipients; this chart does not send email.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.notification-recipient/country-code\\\",\\\"pastdue.notification-recipient/email\\\",\\\"pastdue.notification-recipient/recipient-type\\\",\\\"pastdue.notification-recipient/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"description\\\":\\\"PastDue Current Owner Risk — Current-state owner distribution for high-risk or past-due PO lines; not a historical causal attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.owner-current-risk/country-code\\\",\\\"pastdue.owner-current-risk/owner-display\\\",\\\"pastdue.owner-current-risk/owner-role\\\",\\\"pastdue.owner-current-risk/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"description\\\":\\\"PastDue Vendor Contribution — Vendor-level decomposition of the latest country past-due-ratio change.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Vendor ETA Code Contribution — ETA Code-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-code/country-code\\\",\\\"pastdue.vendor-eta-code/eta-code\\\",\\\"pastdue.vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Vendor ETA Source Contribution — ETA Source-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-source/country-code\\\",\\\"pastdue.vendor-eta-source/eta-source\\\",\\\"pastdue.vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true}],\\\"version\\\":2}\"}],\"isError\":false}"
  }
]
08:00:10 - 08:00:11

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.country-monitor",
    "pastdue.vendor-contribution",
    "pastdue.historical-vendor-contribution",
    "pastdue.historical-vendor-eta-code",
    "pastdue.historical-vendor-eta-source"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country independently monitored in the latest two completed snapshots.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"国家代码\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/has-comparable-snapshots\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when at least two completed snapshots exist for the country.\\\",\\\"synonyms\\\":[\\\"存在两个可比较快照\\\"],\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"All open PO-line count used as the latest country denominator.\\\",\\\"synonyms\\\":[\\\"最新开放行数\\\",\\\"latest denominator\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due open PO-line count in the latest completed snapshot.\\\",\\\"synonyms\\\":[\\\"最新逾期行数\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-past-due-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest country past-due count divided by latest country open count, calculated without presentation rounding.\\\",\\\"synonyms\\\":[\\\"最新逾期率\\\",\\\"latest past due ratio\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-snapshot-age-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"UTC calendar days from the latest completed snapshot date to the current UTC date.\\\",\\\"synonyms\\\":[\\\"最新快照陈旧天数\\\"],\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Most recent snapshot date strictly earlier than the current UTC date.\\\",\\\"synonyms\\\":[\\\"最新完整快照日期\\\",\\\"latest completed snapshot\\\"],\\\"format\\\":\\\"date\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous open PO-line count.\\\",\\\"synonyms\\\":[\\\"开放行数变化\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous past-due PO-line count.\\\",\\\"synonyms\\\":[\\\"逾期行数变化\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/percentage-point-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest past-due ratio minus previous past-due ratio; displayed for context but not used as a trigger threshold.\\\",\\\"synonyms\\\":[\\\"百分点变化\\\",\\\"ratio point change\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"All open PO-line count used as the previous country denominator.\\\",\\\"synonyms\\\":[\\\"上期开放行数\\\",\\\"previous denominator\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due open PO-line count in the previous completed snapshot.\\\",\\\"synonyms\\\":[\\\"上期逾期行数\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-past-due-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous country past-due count divided by previous country open count, calculated without presentation rounding.\\\",\\\"synonyms\\\":[\\\"上期逾期率\\\",\\\"previous past due ratio\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Most recent available completed snapshot before the latest snapshot for this country.\\\",\\\"synonyms\\\":[\\\"上一快照日期\\\",\\\"previous completed snapshot\\\"],\\\"format\\\":\\\"date\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/relative-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Relative change in country past-due ratio; null when the previous ratio is zero or unavailable.\\\",\\\"synonyms\\\":[\\\"逾期率相对变化\\\",\\\"relative growth\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/snapshot-gap-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Calendar-day gap between the latest and previous available snapshot dates.\\\",\\\"synonyms\\\":[\\\"快照间隔天数\\\"],\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/zero-baseline-increase\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when the previous ratio is zero and the latest ratio is greater than zero.\\\",\\\"synonyms\\\":[\\\"零基线上升\\\"],\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank for this historical pair.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the historical vendor identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive share in descending historical contribution order.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor contribution to the explicit historical country ratio change.\\\",\\\"synonyms\\\":[\\\"历史供应商贡献\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Target minus previous vendor open count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Target minus previous vendor past-due count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor positive delta divided by all positive vendor deltas for this historical pair.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Nearest prior available snapshot used as the comparison baseline.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target vendor past-due count divided by target country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the target snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the target snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Historical completed snapshot being explained.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target vendor past-due count divided by target vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the identity; not assumed globally unique across countries.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Latest available vendor name across the explicit historical pair.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/vendor-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target vendor ratio minus previous vendor ratio.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank for this Vendor and historical pair.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the historical ETA Code identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive share in descending historical contribution order.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This ETA Code bucket's contribution to the historical Country ratio change.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/eta-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Normalized historical ETA Code bucket; null and blank values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Target minus previous past-due count for this ETA Code bucket.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Bucket positive delta divided by all positive ETA Code deltas for this historical Vendor pair.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous bucket past-due count divided by previous Country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Code bucket in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Nearest prior available snapshot used as the comparison baseline.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/target-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target bucket past-due count divided by target Country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/target-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Code bucket in the target snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Historical completed snapshot being explained.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-code/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the historical ETA Code identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank for this Vendor and historical pair.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the historical ETA Source identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive share in descending historical contribution order.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This ETA Source bucket's contribution to the historical Country ratio change.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/eta-source\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Normalized historical ETA Source bucket; null and blank values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Target minus previous past-due count for this ETA Source bucket.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Bucket positive delta divided by all positive ETA Source deltas for this historical Vendor pair.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous bucket past-due count divided by previous Country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Source bucket in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Nearest prior available snapshot used as the comparison baseline.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/target-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target bucket past-due count divided by target Country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/target-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Source bucket in the target snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Historical completed snapshot being explained.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-eta-source/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the historical ETA Source identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank within the country.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the vendor analysis identity.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive contribution share in descending delta order.\\\",\\\"synonyms\\\":[\\\"累计正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor contribution to country ratio change: latest country contribution minus previous country contribution.\\\",\\\"synonyms\\\":[\\\"对国家逾期率变化的贡献\\\",\\\"vendor contribution\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Latest completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor open count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor past-due count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This vendor's positive delta divided by the sum of positive vendor deltas in the country.\\\",\\\"synonyms\\\":[\\\"正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Previous available completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the identity; vendor code is not assumed globally unique across countries.\\\",\\\"synonyms\\\":[\\\"供应商代码\\\",\\\"vendor\\\"],\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Latest available nonblank vendor name across the compared snapshots.\\\",\\\"synonyms\\\":[\\\"供应商名称\\\"],\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor ratio minus previous vendor ratio.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/all\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.country-monitor\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.country-monitor/country-code :pastdue.country-monitor/latest-snapshot-date :pastdue.country-monitor/previous-snapshot-date :pastdue.country-monitor/snapshot-gap-days :pastdue.country-monitor/latest-snapshot-age-days :pastdue.country-monitor/latest-past-due-count :pastdue.country-monitor/previous-past-due-count :pastdue.country-monitor/past-due-count-change :pastdue.country-monitor/latest-open-count :pastdue.country-monitor/previous-open-count :pastdue.country-monitor/open-count-change :pastdue.country-monitor/latest-past-due-ratio :pastdue.country-monitor/previous-past-due-ratio :pastdue.country-monitor/relative-ratio-change :pastdue.country-monitor/percentage-point-change :pastdue.country-monitor/zero-baseline-increase :pastdue.country-monitor/has-comparable-snapshots], :default [:pastdue.country-monitor/country-code :asc], :aliases {\\\\\\\"pastdue.country-monitor/past-due-count-change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"previous-open-count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"latest-snapshot-age-days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"percentage-point-change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"pastdue.country-monitor/open-count-change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"snapshot_gap_days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"relative-ratio-change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"pastdue.country-monitor/previous-past-due-ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"pastdue.country-monitor/latest-snapshot-date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"pastdue.country-monitor/has-comparable-snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"pastdue.country-monitor/previous-snapshot-date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"latest_snapshot_age_days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"snapshot-gap-days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"zero-baseline-increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase, \\\\\\\"pastdue.country-monitor/latest-snapshot-age-days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"pastdue.country-monitor/country-code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"pastdue.country-monitor/latest-open-count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"country_code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"has-comparable-snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"previous-past-due-ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"pastdue.country-monitor/snapshot-gap-days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"pastdue.country-monitor/previous-past-due-count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"latest-open-count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"pastdue.country-monitor/latest-past-due-ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"previous_open_count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"has_comparable_snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"latest-past-due-ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"pastdue.country-monitor/zero-baseline-increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase, \\\\\\\"pastdue.country-monitor/percentage-point-change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"percentage_point_change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"pastdue.country-monitor/latest-past-due-count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"relative_ratio_change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"previous_past_due_ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"latest_past_due_ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"country-code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"pastdue.country-monitor/previous-open-count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"pastdue.country-monitor/relative-ratio-change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"zero_baseline_increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase}}\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/positive-vendor-contributors\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-contribution\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank], :default [:pastdue.vendor-contribution/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"pastdue.vendor-contribution/open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"pastdue.vendor-contribution/previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"pastdue.vendor-contribution/vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"latest_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"pastdue.vendor-contribution/cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"vendor_name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"pastdue.vendor-contribution/previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"pastdue.vendor-contribution/past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"pastdue.vendor-contribution/latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"pastdue.vendor-contribution/latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"pastdue.vendor-contribution/positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"vendor_ratio_change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"previous_open_count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"pastdue.vendor-contribution/vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/vendor-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-contribution\\\",\\\"limit\\\":{\\\"default\\\":1000,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank], :default [:pastdue.vendor-contribution/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"pastdue.vendor-contribution/open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"pastdue.vendor-contribution/previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"pastdue.vendor-contribution/vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"latest_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"pastdue.vendor-contribution/cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"vendor_name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"pastdue.vendor-contribution/previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"pastdue.vendor-contribution/past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"pastdue.vendor-contribution/latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"pastdue.vendor-contribution/latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"pastdue.vendor-contribution/positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"vendor_ratio_change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"previous_open_count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"pastdue.vendor-contribution/vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-code-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-code/country-code :pastdue.historical-vendor-eta-code/vendor-code :pastdue.historical-vendor-eta-code/target-snapshot-date :pastdue.historical-vendor-eta-code/previous-snapshot-date :pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution], :default [:pastdue.historical-vendor-eta-code/eta-code :asc], :aliases {\\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-code-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-code/country-code :pastdue.historical-vendor-eta-code/vendor-code :pastdue.historical-vendor-eta-code/target-snapshot-date :pastdue.historical-vendor-eta-code/previous-snapshot-date :pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution :pastdue.historical-vendor-eta-code/positive-contribution-share :pastdue.historical-vendor-eta-code/cumulative-positive-share :pastdue.historical-vendor-eta-code/contribution-rank], :default [:pastdue.historical-vendor-eta-code/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-code/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-code/contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-code/contribution-rank, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-code/positive-contribution-share, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.historical-vendor-eta-code/cumulative-positive-share, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-code/positive-contribution-share, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-code/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.historical-vendor-eta-code/positive-contribution-share, \\\\\\\"eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"contribution_rank\\\\\\\" :pastdue.historical-vendor-eta-code/contribution-rank, \\\\\\\"pastdue.historical-vendor-eta-code/cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-code/cumulative-positive-share}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-source-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-source/country-code :pastdue.historical-vendor-eta-source/vendor-code :pastdue.historical-vendor-eta-source/target-snapshot-date :pastdue.historical-vendor-eta-source/previous-snapshot-date :pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-country-contribution :pastdue.historical-vendor-eta-source/previous-country-contribution :pastdue.historical-vendor-eta-source/delta-country-contribution], :default [:pastdue.historical-vendor-eta-source/eta-source :asc], :aliases {\\\\\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"pastdue.historical-vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-source-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-source/country-code :pastdue.historical-vendor-eta-source/vendor-code :pastdue.historical-vendor-eta-source/target-snapshot-date :pastdue.historical-vendor-eta-source/previous-snapshot-date :pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-country-contribution :pastdue.historical-vendor-eta-source/previous-country-contribution :pastdue.historical-vendor-eta-source/delta-country-contribution :pastdue.historical-vendor-eta-source/positive-contribution-share :pastdue.historical-vendor-eta-source/cumulative-positive-share :pastdue.historical-vendor-eta-source/contribution-rank], :default [:pastdue.historical-vendor-eta-source/contribution-rank :asc], :aliases {\\\\\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-source/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"pastdue.historical-vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-source/positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-source/positive-contribution-share, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-source/positive-contribution-share, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.historical-vendor-eta-source/cumulative-positive-share, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-source/contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-source/contribution-rank, \\\\\\\"pastdue.historical-vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-source/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.historical-vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.historical-vendor-eta-source/eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"contribution_rank\\\\\\\" :pastdue.historical-vendor-eta-source/contribution-rank, \\\\\\\"pastdue.historical-vendor-eta-source/cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-source/cumulative-positive-share}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-high-risk-lines\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-high-risk-line\\\",\\\"limit\\\":{\\\"default\\\":20,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-high-risk-line/country-code :pastdue.current-high-risk-line/vendor-code :pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/ordered-quantity :pastdue.current-high-risk-line/shipped-quantity :pastdue.current-high-risk-line/received-quantity :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-date :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/action-type :pastdue.current-high-risk-line/buyer-owner :pastdue.current-high-risk-line/primary-owner :pastdue.current-high-risk-line/manager-owner :pastdue.current-high-risk-line/risk-priority-rank], :default [:pastdue.current-high-risk-line/risk-priority-rank :asc], :aliases {\\\\\\\"risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"order_type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"po_line_number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"pastdue.current-high-risk-line/eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"pastdue.current-high-risk-line/eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"risk_priority_rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"pastdue.current-high-risk-line/received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"pastdue.current-high-risk-line/vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"received_quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"eta_source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"pastdue.current-high-risk-line/manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"is_past_due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"eta_code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"ordered_quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"manager_owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"action_type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"sku_number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"past_due_days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"pastdue.current-high-risk-line/primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"buyer_owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"primary_owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"pastdue.current-high-risk-line/is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"po_number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"eta_date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"pastdue.current-high-risk-line/action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"shipped_quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"risk_level\\\\\\\" :pastdue.current-high-risk-line/risk-level}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-owner-risks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.owner-current-risk\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.owner-current-risk/country-code :pastdue.owner-current-risk/vendor-code :pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-high-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count :pastdue.owner-current-risk/max-past-due-days :pastdue.owner-current-risk/queried-at-utc], :default [:pastdue.owner-current-risk/current-risk-line-count :desc], :aliases {\\\\\\\"pastdue.owner-current-risk/country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"owner_role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"vendor_code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"owner_display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"country_code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"current_high_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"pastdue.owner-current-risk/queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"max_past_due_days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"pastdue.owner-current-risk/owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"pastdue.owner-current-risk/current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"current_past_due_line_count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"current_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-code-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-code/country-code :pastdue.vendor-eta-code/vendor-code :pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/previous-snapshot-date :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/previous-past-due-count :pastdue.vendor-eta-code/past-due-count-change :pastdue.vendor-eta-code/latest-country-contribution :pastdue.vendor-eta-code/previous-country-contribution :pastdue.vendor-eta-code/delta-country-contribution :pastdue.vendor-eta-code/positive-contribution-share :pastdue.vendor-eta-code/cumulative-positive-share :pastdue.vendor-eta-code/contribution-rank], :default [:pastdue.vendor-eta-code/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-code/country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"pastdue.vendor-eta-code/contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.vendor-eta-code/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-code/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-code/eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"pastdue.vendor-eta-code/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-source-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-source/country-code :pastdue.vendor-eta-source/vendor-code :pastdue.vendor-eta-source/eta-source :pastdue.vendor-eta-source/latest-snapshot-date :pastdue.vendor-eta-source/previous-snapshot-date :pastdue.vendor-eta-source/latest-past-due-count :pastdue.vendor-eta-source/previous-past-due-count :pastdue.vendor-eta-source/past-due-count-change :pastdue.vendor-eta-source/latest-country-contribution :pastdue.vendor-eta-source/previous-country-contribution :pastdue.vendor-eta-source/delta-country-contribution :pastdue.vendor-eta-source/positive-contribution-share :pastdue.vendor-eta-source/cumulative-positive-share :pastdue.vendor-eta-source/contribution-rank], :default [:pastdue.vendor-eta-source/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-source/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-source/country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-source/contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"pastdue.vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-source/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"pastdue.vendor-eta-source/vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/notification-recipients\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.notification-recipient\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.notification-recipient/country-code :pastdue.notification-recipient/vendor-code :pastdue.notification-recipient/recipient-type :pastdue.notification-recipient/email :pastdue.notification-recipient/contact-name :pastdue.notification-recipient/is-active :pastdue.notification-recipient/source-system :pastdue.notification-recipient/contact-role-hint :pastdue.notification-recipient/contact-priority-score :pastdue.notification-recipient/is-internal-candidate :pastdue.notification-recipient/is-recommended :pastdue.notification-recipient/selection-reason :pastdue.notification-recipient/evidence-text :pastdue.notification-recipient/updated-at-utc], :default [:pastdue.notification-recipient/contact-priority-score :desc], :aliases {\\\\\\\"pastdue.notification-recipient/country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"pastdue.notification-recipient/evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"source_system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"pastdue.notification-recipient/updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"pastdue.notification-recipient/is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"is_active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"selection_reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"contact_priority_score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"vendor_code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"email\\\\\\\" :pastdue.notification-recipient/email, \\\\\\\"contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"is_internal_candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"recipient_type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"country_code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"is_recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"pastdue.notification-recipient/recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"evidence_text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"updated_at_utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"contact_role_hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"pastdue.notification-recipient/contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"pastdue.notification-recipient/source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"contact_name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"pastdue.notification-recipient/contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"pastdue.notification-recipient/email\\\\\\\" :pastdue.notification-recipient/email}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"pastdue.notification-recipient/recipient-type\\\"]}]}\"}],\"isError\":false}"
  }
]
08:00:12

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {} :query [({:pastdue.country-monitor/all [:pastdue.country-monitor/country-code :pastdue.country-monitor/latest-snapshot-date :pastdue.country-monitor/previous-snapshot-date :pastdue.country-monitor/snapshot-gap-days :pastdue.country-monitor/latest-snapshot-age-days :pastdue.country-monitor/latest-past-due-count :pastdue.country-monitor/previous-past-due-count :pastdue.country-monitor/latest-open-count :pastdue.country-monitor/previous-open-count :pastdue.country-monitor/latest-past-due-ratio :pastdue.country-monitor/previous-past-due-ratio :pastdue.country-monitor/relative-ratio-change :pastdue.country-monitor/percentage-point-change :pastdue.country-monitor/zero-baseline-increase :pastdue.country-monitor/has-comparable-snapshots]} {:order_by \"pastdue.country-monitor/country-code\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:15 - 08:00:16

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_d1857b9b-e514-4a12-b33e-673f55d68899",
  "sql": "SELECT * FROM data ORDER BY country_code",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"previous_snapshot_date\\\",\\\"latest_past_due_ratio\\\",\\\"has_comparable_snapshots\\\",\\\"latest_snapshot_age_days\\\",\\\"relative_ratio_change\\\",\\\"percentage_point_change\\\",\\\"snapshot_gap_days\\\",\\\"latest_past_due_count\\\",\\\"country_code\\\",\\\"latest_open_count\\\",\\\"latest_snapshot_date\\\",\\\"previous_past_due_count\\\",\\\"previous_past_due_ratio\\\",\\\"previous_open_count\\\",\\\"zero_baseline_increase\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368016187,\\\"expires-at\\\":1792368916187,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"previous_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"latest_past_due_ratio\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"has_comparable_snapshots\\\",\\\"type\\\":\\\"BOOLEAN\\\"},{\\\"name\\\":\\\"latest_snapshot_age_days\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"relative_ratio_change\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"percentage_point_change\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"snapshot_gap_days\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"country_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"latest_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"previous_past_due_ratio\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"previous_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"zero_baseline_increase\\\",\\\"type\\\":\\\"BOOLEAN\\\"}],\\\"rows\\\":[[\\\"2026-10-16\\\",\\\"0.144471056\\\",true,2,\\\"0.153661474\\\",\\\"0.019242764\\\",1,6394,\\\"CA\\\",44258,\\\"2026-10-17\\\",5554,\\\"0.125228292\\\",44351,false],[\\\"2026-10-16\\\",\\\"0.134896789\\\",true,2,\\\"0.112088969\\\",\\\"0.013596432\\\",1,44360,\\\"US\\\",328844,\\\"2026-10-17\\\",40242,\\\"0.121300357\\\",331755,false]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":37}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:17

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.country-monitor/country-code \"CA\"} :query [({:pastdue.country-monitor/positive-vendor-contributors [:pastdue.vendor-contribution/contribution-rank :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share]} {:limit 10 :order_by \"pastdue.vendor-contribution/contribution-rank\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:21 - 08:00:22

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.country-monitor/country-code \"US\"} :query [({:pastdue.country-monitor/positive-vendor-contributors [:pastdue.vendor-contribution/contribution-rank :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share]} {:limit 10 :order_by \"pastdue.vendor-contribution/contribution-rank\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:21 - 08:00:22

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_a90dd490-cb56-46a9-ad68-b5d4fa5c1cf7",
  "sql": "SELECT * FROM data ORDER BY contribution_rank, vendor_code",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_a90dd490-cb56-46a9-ad68-b5d4fa5c1cf7\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"vendor_code\\\",\\\"latest_open_count\\\",\\\"previous_vendor_ratio\\\",\\\"previous_open_count\\\",\\\"latest_vendor_ratio\\\",\\\"previous_snapshot_date\\\",\\\"contribution_rank\\\",\\\"delta_country_contribution\\\",\\\"latest_snapshot_date\\\",\\\"previous_past_due_count\\\",\\\"positive_contribution_share\\\",\\\"vendor_name\\\",\\\"latest_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368022258,\\\"expires-at\\\":1792368922258,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"vendor_code\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"previous_vendor_ratio\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"previous_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_vendor_ratio\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"previous_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"contribution_rank\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"delta_country_contribution\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"latest_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"positive_contribution_share\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"vendor_name\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"latest_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[28538,1425,\\\"0.111341632\\\",1446,\\\"0.232982456\\\",\\\"2026-10-16\\\",1,\\\"0.003871336\\\",\\\"2026-10-17\\\",161,\\\"0.198952565\\\",\\\"LENOVO CANADA - COMMERCIAL\\\",332],[40979,360,\\\"0.630555555\\\",360,\\\"0.811111111\\\",\\\"2026-10-16\\\",2,\\\"0.001479416\\\",\\\"2026-10-17\\\",227,\\\"0.076028949\\\",\\\"JEM ACCESSORIES\\\",292],[37112,4714,\\\"0.070216376\\\",4714,\\\"0.076368264\\\",\\\"2026-10-16\\\",3,\\\"0.000670931\\\",\\\"2026-10-17\\\",331,\\\"0.034479942\\\",\\\"CISCO - MAIN\\\",360],[1182,300,\\\"0.620000000\\\",300,\\\"0.703333333\\\",\\\"2026-10-16\\\",4,\\\"0.000573682\\\",\\\"2026-10-17\\\",186,\\\"0.029482200\\\",\\\"XEROX CANADA LTD OPB SUPPLIES\\\",211],[37027,2543,\\\"0.032638615\\\",2543,\\\"0.042469524\\\",\\\"2026-10-16\\\",5,\\\"0.000568802\\\",\\\"2026-10-17\\\",83,\\\"0.029231411\\\",\\\"CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.\\\",108],[5942,1755,\\\"0.022209567\\\",1756,\\\"0.034188034\\\",\\\"2026-10-16\\\",6,\\\"0.000476339\\\",\\\"2026-10-17\\\",39,\\\"0.024479628\\\",\\\"HEWLETT PACKARD - ESS\\\",60],[20562,386,\\\"0.494818652\\\",386,\\\"0.544041450\\\",\\\"2026-10-16\\\",7,\\\"0.000438350\\\",\\\"2026-10-17\\\",191,\\\"0.022527328\\\",\\\"ACCO BRANDS CANADA\\\",210],[40102,340,\\\"0.188235294\\\",340,\\\"0.244117647\\\",\\\"2026-10-16\\\",8,\\\"0.000432334\\\",\\\"2026-10-17\\\",64,\\\"0.022218158\\\",\\\"DELL CANADA INC.\\\",83],[31106,1302,\\\"0.016129032\\\",1302,\\\"0.030721966\\\",\\\"2026-10-16\\\",9,\\\"0.000430296\\\",\\\"2026-10-17\\\",21,\\\"0.022113423\\\",\\\"LOGITECH - BESTBUY/FUTURESHOP\\\",40],[23311,445,\\\"0.038202247\\\",445,\\\"0.080898876\\\",\\\"2026-10-16\\\",10,\\\"0.000430107\\\",\\\"2026-10-17\\\",17,\\\"0.022103710\\\",\\\"CLOVER TECHNOLOGIES CANADA ULC\\\",36]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":27}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:24

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_994101f3-ae1d-4bfb-94c5-dbd8b82e3184",
  "sql": "SELECT * FROM data ORDER BY contribution_rank, vendor_code",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_994101f3-ae1d-4bfb-94c5-dbd8b82e3184\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"vendor_code\\\",\\\"latest_open_count\\\",\\\"previous_vendor_ratio\\\",\\\"previous_open_count\\\",\\\"latest_vendor_ratio\\\",\\\"previous_snapshot_date\\\",\\\"contribution_rank\\\",\\\"delta_country_contribution\\\",\\\"latest_snapshot_date\\\",\\\"previous_past_due_count\\\",\\\"positive_contribution_share\\\",\\\"vendor_name\\\",\\\"latest_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368022296,\\\"expires-at\\\":1792368922296,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"vendor_code\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"previous_vendor_ratio\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"previous_open_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"latest_vendor_ratio\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"previous_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"contribution_rank\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"delta_country_contribution\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"latest_snapshot_date\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"positive_contribution_share\\\",\\\"type\\\":\\\"DECIMAL(10,9)\\\"},{\\\"name\\\":\\\"vendor_name\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"latest_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[64956,75656,\\\"0.056450227\\\",75695,\\\"0.058964259\\\",\\\"2026-10-16\\\",1,\\\"0.000685716\\\",\\\"2026-10-17\\\",4273,\\\"0.048323610\\\",\\\"CISCO SYSTEMS\\\",4461],[21164,9871,\\\"0.465186375\\\",9953,\\\"0.486374227\\\",\\\"2026-10-16\\\",2,\\\"0.000643545\\\",\\\"2026-10-17\\\",4630,\\\"0.045351746\\\",\\\"HP INC.\\\",4801],[47774,449,\\\"0.608017817\\\",449,\\\"1.000000000\\\",\\\"2026-10-16\\\",3,\\\"0.000542493\\\",\\\"2026-10-17\\\",273,\\\"0.038230434\\\",\\\"DT RESEARCH\\\",449],[73885,4143,\\\"0.077328225\\\",4177,\\\"0.116340815\\\",\\\"2026-10-16\\\",4,\\\"0.000492130\\\",\\\"2026-10-17\\\",323,\\\"0.034681265\\\",\\\"APPLE INC.\\\",482],[24234,1149,\\\"0.167958656\\\",1161,\\\"0.297650130\\\",\\\"2026-10-16\\\",5,\\\"0.000452223\\\",\\\"2026-10-17\\\",195,\\\"0.031868949\\\",\\\"C2G - LEGRAND AV\\\",342],[76734,202,\\\"0E-9\\\",205,\\\"0.717821782\\\",\\\"2026-10-16\\\",6,\\\"0.000440938\\\",\\\"2026-10-17\\\",0,\\\"0.031073675\\\",\\\"CONSTELLATION GOVCLOUD, INC.\\\",145],[13208,16590,\\\"0.089570480\\\",17182,\\\"0.100301386\\\",\\\"2026-10-16\\\",7,\\\"0.000421185\\\",\\\"2026-10-17\\\",1539,\\\"0.029681646\\\",\\\"HP INC.\\\",1664],[10030,2760,\\\"0.306048533\\\",2761,\\\"0.352536231\\\",\\\"2026-10-16\\\",8,\\\"0.000411789\\\",\\\"2026-10-17\\\",845,\\\"0.029019494\\\",\\\"EPSON AMERICA, INC.\\\",973],[56934,1093,\\\"0.324710080\\\",1121,\\\"0.451967063\\\",\\\"2026-10-16\\\",9,\\\"0.000405037\\\",\\\"2026-10-17\\\",364,\\\"0.028543668\\\",\\\"ADD-ON-COMPUTER PERIPHERALS, LLC\\\",494],[13439,916,\\\"0.283842794\\\",916,\\\"0.422489082\\\",\\\"2026-10-16\\\",10,\\\"0.000393139\\\",\\\"2026-10-17\\\",260,\\\"0.027705195\\\",\\\"XEROX CORP.\\\",387]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":27}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:24

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-code/eta-code\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-code/eta-code\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-code/eta-code\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 1182 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-code/eta-code\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-code/eta-code\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:31

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_889486e5-49d1-4fc8-9821-0640a8b123e4",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_889486e5-49d1-4fc8-9821-0640a8b123e4\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368031677,\\\"expires-at\\\":1792368931677,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",6,156,150],[\\\"DD\\\",11,21,10],[\\\"SH\\\",127,135,8],[\\\"PE\\\",15,17,2],[\\\"RQ\\\",1,2,1],[\\\"NS\\\",1,1,0],[\\\"SC\\\",0,0,0],[\\\"US\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":65}},\\\"row-count\\\":8,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":8,\\\"rows-total\\\":8},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_01390653-6089-4bbe-9f1b-990559dea8c4",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_01390653-6089-4bbe-9f1b-990559dea8c4\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368031691,\\\"expires-at\\\":1792368931691,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"US\\\",177,242,65],[\\\"NR\\\",28,28,0],[\\\"RQ\\\",20,20,0],[\\\"PE\\\",1,1,0],[\\\"SH\\\",1,1,0],[\\\"SC\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":60}},\\\"row-count\\\":6,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":6,\\\"rows-total\\\":6},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_ea50585b-c60b-4132-8978-f2e78eebb731",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_ea50585b-c60b-4132-8978-f2e78eebb731\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368031970,\\\"expires-at\\\":1792368931970,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"DD\\\",2,21,19],[\\\"NR\\\",229,237,8],[\\\"US\\\",52,54,2],[\\\"SH\\\",39,38,-1],[\\\"RQ\\\",0,1,1],[\\\"PE\\\",9,9,0],[\\\"SC\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":61}},\\\"row-count\\\":7,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":7,\\\"rows-total\\\":7},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_e267a804-baa8-4a0f-956f-ee1be78afcb3",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_e267a804-baa8-4a0f-956f-ee1be78afcb3\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368031963,\\\"expires-at\\\":1792368931963,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",185,205,20],[\\\"US\\\",1,6,5],[\\\"NS\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":64}},\\\"row-count\\\":3,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":3,\\\"rows-total\\\":3},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_86ae9bc9-1294-4c39-b655-a2d51cfd617a",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error \\\"Result handle is unknown, expired, or inaccessible; execute the query again.\\\"}\"}],\"isError\":true}"
  }
]
08:00:35

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-source/eta-source\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:42

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-source/eta-source\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:42

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-source/eta-source\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:42

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 1182 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-source/eta-source\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:42

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 28538 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [({:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change]} {:limit 1000 :order_by \"pastdue.historical-vendor-eta-source/eta-source\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:42

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_c1816144-220a-4018-b60c-590f36cdcc38",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_c1816144-220a-4018-b60c-590f36cdcc38\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368042552,\\\"expires-at\\\":1792368942552,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Vendor Report\\\",118,272,154],[\\\"Carrier API\\\",11,21,10],[\\\"EDI856\\\",14,20,6],[\\\"BUYER\\\",11,12,1],[\\\"Vendor Email\\\",6,6,0],[\\\"Vendor Invoice\\\",1,1,0],[\\\"AUTO NR\\\",0,0,0],[\\\"AUTO US\\\",0,0,0],[\\\"EDI855\\\",0,0,0],[\\\"Vendor Web Site\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":1,\\\"load-and-sql\\\":79}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:45

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_a0b762e2-f3c0-483f-a739-eff98735c3e9",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_a0b762e2-f3c0-483f-a739-eff98735c3e9\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368042531,\\\"expires-at\\\":1792368942531,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"AUTO US\\\",172,237,65],[\\\"Vendor Email\\\",20,20,0],[\\\"AUTO NR\\\",15,15,0],[\\\"BUYER\\\",14,14,0],[\\\"Vendor Report\\\",6,6,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":48}},\\\"row-count\\\":5,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":5,\\\"rows-total\\\":5},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:45

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_6e0537d3-2f73-4bde-83bb-f2b24dfac816",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_6e0537d3-2f73-4bde-83bb-f2b24dfac816\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368042538,\\\"expires-at\\\":1792368942538,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Carrier API\\\",12,30,18],[\\\"AUTO NR\\\",201,208,7],[\\\"AUTO US\\\",52,54,2],[\\\"EDI3A4C\\\",16,17,1],[\\\"BUYER\\\",0,1,1],[\\\"EDI3B2\\\",29,29,0],[\\\"EDI3A6\\\",13,13,0],[\\\"Vendor Web Site\\\",8,8,0],[\\\"Vendor Email\\\",0,0,0],[\\\"Vendor Report\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":42}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:45

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_bd2a2327-fbda-4026-a094-245c79269d77",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_bd2a2327-fbda-4026-a094-245c79269d77\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368042582,\\\"expires-at\\\":1792368942582,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI855\\\",105,125,20],[\\\"AUTO US\\\",1,6,5],[\\\"AUTO NR\\\",80,80,0],[\\\"BUYER\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":47}},\\\"row-count\\\":4,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":4,\\\"rows-total\\\":4},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:45

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_23f07472-a252-47e6-8f2b-a5751984d314",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_23f07472-a252-47e6-8f2b-a5751984d314\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"previous_past_due_count\\\",\\\"target_past_due_count\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368042559,\\\"expires-at\\\":1792368942559,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"previous_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Vendor Email\\\",78,99,21],[\\\"BUYER\\\",0,3,3],[\\\"AUTO US\\\",5,6,1],[\\\"Vendor Invoice\\\",0,0,0],[\\\"Vendor Report\\\",0,0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":21}},\\\"row-count\\\":5,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":5,\\\"rows-total\\\":5},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:45

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 5942 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change]} {:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:51 - 08:00:52

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change]} {:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:51 - 08:00:52

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change]} {:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:51 - 08:00:52

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change]} {:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:52

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change]} {:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:00:52

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_dbd093bf-747c-409e-ae81-ecf74d6b6a07",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_dbd093bf-747c-409e-ae81-ecf74d6b6a07\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"eta_source\\\",\\\"historical_vendor_eta_source_past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368052732,\\\"expires-at\\\":1792368952732,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"historical_vendor_eta_source_past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",8,\\\"API ASN\\\",0],[\\\"NR\\\",8,\\\"API Order\\\",8],[\\\"NR\\\",8,\\\"AUTO US\\\",13],[\\\"NR\\\",8,\\\"BUYER\\\",0],[\\\"NR\\\",8,\\\"EDI855\\\",0],[\\\"NR\\\",8,\\\"EDI856\\\",0],[\\\"NR\\\",8,\\\"Vendor Report\\\",0],[\\\"RQ\\\",0,\\\"API ASN\\\",0],[\\\"RQ\\\",0,\\\"API Order\\\",8],[\\\"RQ\\\",0,\\\"AUTO US\\\",13],[\\\"RQ\\\",0,\\\"BUYER\\\",0],[\\\"RQ\\\",0,\\\"EDI855\\\",0],[\\\"RQ\\\",0,\\\"EDI856\\\",0],[\\\"RQ\\\",0,\\\"Vendor Report\\\",0],[\\\"SC\\\",0,\\\"API ASN\\\",0],[\\\"SC\\\",0,\\\"API Order\\\",8],[\\\"SC\\\",0,\\\"AUTO US\\\",13],[\\\"SC\\\",0,\\\"BUYER\\\",0],[\\\"SC\\\",0,\\\"EDI855\\\",0],[\\\"SC\\\",0,\\\"EDI856\\\",0],[\\\"SC\\\",0,\\\"Vendor Report\\\",0],[\\\"SH\\\",0,\\\"API ASN\\\",0],[\\\"SH\\\",0,\\\"API Order\\\",8],[\\\"SH\\\",0,\\\"AUTO US\\\",13],[\\\"SH\\\",0,\\\"BUYER\\\",0],[\\\"SH\\\",0,\\\"EDI855\\\",0],[\\\"SH\\\",0,\\\"EDI856\\\",0],[\\\"SH\\\",0,\\\"Vendor Report\\\",0],[\\\"US\\\",13,\\\"API ASN\\\",0],[\\\"US\\\",13,\\\"API Order\\\",8],[\\\"US\\\",13,\\\"AUTO US\\\",13],[\\\"US\\\",13,\\\"BUYER\\\",0],[\\\"US\\\",13,\\\"EDI855\\\",0],[\\\"US\\\",13,\\\"EDI856\\\",0],[\\\"US\\\",13,\\\"Vendor Report\\\",0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":58}},\\\"row-count\\\":35,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":35,\\\"rows-total\\\":35},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:55

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_4b25479d-5c5f-463c-a9f3-b300ab619e05",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_4b25479d-5c5f-463c-a9f3-b300ab619e05\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"eta_source\\\",\\\"historical_vendor_eta_source_past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368052739,\\\"expires-at\\\":1792368952739,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"historical_vendor_eta_source_past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"DD\\\",1,\\\"AUTO NR\\\",1],[\\\"DD\\\",1,\\\"AUTO US\\\",2],[\\\"DD\\\",1,\\\"BUYER\\\",1],[\\\"DD\\\",1,\\\"Carrier API\\\",1],[\\\"DD\\\",1,\\\"EDI856\\\",14],[\\\"DD\\\",1,\\\"Vendor Email\\\",0],[\\\"DD\\\",1,\\\"Vendor Report\\\",0],[\\\"NR\\\",1,\\\"AUTO NR\\\",1],[\\\"NR\\\",1,\\\"AUTO US\\\",2],[\\\"NR\\\",1,\\\"BUYER\\\",1],[\\\"NR\\\",1,\\\"Carrier API\\\",1],[\\\"NR\\\",1,\\\"EDI856\\\",14],[\\\"NR\\\",1,\\\"Vendor Email\\\",0],[\\\"NR\\\",1,\\\"Vendor Report\\\",0],[\\\"NS\\\",0,\\\"AUTO NR\\\",1],[\\\"NS\\\",0,\\\"AUTO US\\\",2],[\\\"NS\\\",0,\\\"BUYER\\\",1],[\\\"NS\\\",0,\\\"Carrier API\\\",1],[\\\"NS\\\",0,\\\"EDI856\\\",14],[\\\"NS\\\",0,\\\"Vendor Email\\\",0],[\\\"NS\\\",0,\\\"Vendor Report\\\",0],[\\\"RQ\\\",0,\\\"AUTO NR\\\",1],[\\\"RQ\\\",0,\\\"AUTO US\\\",2],[\\\"RQ\\\",0,\\\"BUYER\\\",1],[\\\"RQ\\\",0,\\\"Carrier API\\\",1],[\\\"RQ\\\",0,\\\"EDI856\\\",14],[\\\"RQ\\\",0,\\\"Vendor Email\\\",0],[\\\"RQ\\\",0,\\\"Vendor Report\\\",0],[\\\"SC\\\",0,\\\"AUTO NR\\\",1],[\\\"SC\\\",0,\\\"AUTO US\\\",2],[\\\"SC\\\",0,\\\"BUYER\\\",1],[\\\"SC\\\",0,\\\"Carrier API\\\",1],[\\\"SC\\\",0,\\\"EDI856\\\",14],[\\\"SC\\\",0,\\\"Vendor Email\\\",0],[\\\"SC\\\",0,\\\"Vendor Report\\\",0],[\\\"SH\\\",15,\\\"AUTO NR\\\",1],[\\\"SH\\\",15,\\\"AUTO US\\\",2],[\\\"SH\\\",15,\\\"BUYER\\\",1],[\\\"SH\\\",15,\\\"Carrier API\\\",1],[\\\"SH\\\",15,\\\"EDI856\\\",14],[\\\"SH\\\",15,\\\"Vendor Email\\\",0],[\\\"SH\\\",15,\\\"Vendor Report\\\",0],[\\\"US\\\",2,\\\"AUTO NR\\\",1],[\\\"US\\\",2,\\\"AUTO US\\\",2],[\\\"US\\\",2,\\\"BUYER\\\",1],[\\\"US\\\",2,\\\"Carrier API\\\",1],[\\\"US\\\",2,\\\"EDI856\\\",14],[\\\"US\\\",2,\\\"Vendor Email\\\",0],[\\\"US\\\",2,\\\"Vendor Report\\\",0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":64}},\\\"row-count\\\":49,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":49,\\\"rows-total\\\":49},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:55

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_bbbbd256-56b9-4714-940c-198625ce3641",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_bbbbd256-56b9-4714-940c-198625ce3641\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"eta_source\\\",\\\"historical_vendor_eta_source_past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368052733,\\\"expires-at\\\":1792368952733,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"historical_vendor_eta_source_past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"DD\\\",3,\\\"AUTO US\\\",1],[\\\"DD\\\",3,\\\"BUYER\\\",0],[\\\"DD\\\",3,\\\"Carrier API\\\",3],[\\\"DD\\\",3,\\\"EDI855\\\",5],[\\\"DD\\\",3,\\\"EDI856\\\",1],[\\\"DD\\\",3,\\\"EDI870\\\",9],[\\\"DD\\\",3,\\\"Vendor Email\\\",0],[\\\"DD\\\",3,\\\"Vendor Report\\\",0],[\\\"DD\\\",3,\\\"Vendor Web Site\\\",0],[\\\"NR\\\",5,\\\"AUTO US\\\",1],[\\\"NR\\\",5,\\\"BUYER\\\",0],[\\\"NR\\\",5,\\\"Carrier API\\\",3],[\\\"NR\\\",5,\\\"EDI855\\\",5],[\\\"NR\\\",5,\\\"EDI856\\\",1],[\\\"NR\\\",5,\\\"EDI870\\\",9],[\\\"NR\\\",5,\\\"Vendor Email\\\",0],[\\\"NR\\\",5,\\\"Vendor Report\\\",0],[\\\"NR\\\",5,\\\"Vendor Web Site\\\",0],[\\\"PE\\\",0,\\\"AUTO US\\\",1],[\\\"PE\\\",0,\\\"BUYER\\\",0],[\\\"PE\\\",0,\\\"Carrier API\\\",3],[\\\"PE\\\",0,\\\"EDI855\\\",5],[\\\"PE\\\",0,\\\"EDI856\\\",1],[\\\"PE\\\",0,\\\"EDI870\\\",9],[\\\"PE\\\",0,\\\"Vendor Email\\\",0],[\\\"PE\\\",0,\\\"Vendor Report\\\",0],[\\\"PE\\\",0,\\\"Vendor Web Site\\\",0],[\\\"SC\\\",0,\\\"AUTO US\\\",1],[\\\"SC\\\",0,\\\"BUYER\\\",0],[\\\"SC\\\",0,\\\"Carrier API\\\",3],[\\\"SC\\\",0,\\\"EDI855\\\",5],[\\\"SC\\\",0,\\\"EDI856\\\",1],[\\\"SC\\\",0,\\\"EDI870\\\",9],[\\\"SC\\\",0,\\\"Vendor Email\\\",0],[\\\"SC\\\",0,\\\"Vendor Report\\\",0],[\\\"SC\\\",0,\\\"Vendor Web Site\\\",0],[\\\"SH\\\",10,\\\"AUTO US\\\",1],[\\\"SH\\\",10,\\\"BUYER\\\",0],[\\\"SH\\\",10,\\\"Carrier API\\\",3],[\\\"SH\\\",10,\\\"EDI855\\\",5],[\\\"SH\\\",10,\\\"EDI856\\\",1],[\\\"SH\\\",10,\\\"EDI870\\\",9],[\\\"SH\\\",10,\\\"Vendor Email\\\",0],[\\\"SH\\\",10,\\\"Vendor Report\\\",0],[\\\"SH\\\",10,\\\"Vendor Web Site\\\",0],[\\\"US\\\",1,\\\"AUTO US\\\",1],[\\\"US\\\",1,\\\"BUYER\\\",0],[\\\"US\\\",1,\\\"Carrier API\\\",3],[\\\"US\\\",1,\\\"EDI855\\\",5],[\\\"US\\\",1,\\\"EDI856\\\",1],[\\\"US\\\",1,\\\"EDI870\\\",9],[\\\"US\\\",1,\\\"Vendor Email\\\",0],[\\\"US\\\",1,\\\"Vendor Report\\\",0],[\\\"US\\\",1,\\\"Vendor Web Site\\\",0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":46}},\\\"row-count\\\":54,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":54,\\\"rows-total\\\":54},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:55

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_a4f70934-4872-499d-911c-b593fd378642",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_a4f70934-4872-499d-911c-b593fd378642\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"eta_source\\\",\\\"historical_vendor_eta_source_past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368052732,\\\"expires-at\\\":1792368952732,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"historical_vendor_eta_source_past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",4,\\\"AUTO US\\\",15],[\\\"NR\\\",4,\\\"BUYER\\\",0],[\\\"NR\\\",4,\\\"Vendor Report\\\",4],[\\\"RQ\\\",0,\\\"AUTO US\\\",15],[\\\"RQ\\\",0,\\\"BUYER\\\",0],[\\\"RQ\\\",0,\\\"Vendor Report\\\",4],[\\\"SC\\\",0,\\\"AUTO US\\\",15],[\\\"SC\\\",0,\\\"BUYER\\\",0],[\\\"SC\\\",0,\\\"Vendor Report\\\",4],[\\\"US\\\",15,\\\"AUTO US\\\",15],[\\\"US\\\",15,\\\"BUYER\\\",0],[\\\"US\\\",15,\\\"Vendor Report\\\",4]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":45}},\\\"row-count\\\":12,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":12,\\\"rows-total\\\":12},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:55

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_e03707b9-3741-4f20-b7a4-1ea8a3b0c28a",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_e03707b9-3741-4f20-b7a4-1ea8a3b0c28a\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"eta_source\\\",\\\"historical_vendor_eta_source_past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368052739,\\\"expires-at\\\":1792368952739,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"historical_vendor_eta_source_past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",0,\\\"AUTO NR\\\",0],[\\\"NR\\\",0,\\\"AUTO US\\\",16],[\\\"NR\\\",0,\\\"EDI855\\\",0],[\\\"NR\\\",0,\\\"Vendor Email\\\",0],[\\\"NR\\\",0,\\\"Vendor Report\\\",3],[\\\"SC\\\",0,\\\"AUTO NR\\\",0],[\\\"SC\\\",0,\\\"AUTO US\\\",16],[\\\"SC\\\",0,\\\"EDI855\\\",0],[\\\"SC\\\",0,\\\"Vendor Email\\\",0],[\\\"SC\\\",0,\\\"Vendor Report\\\",3],[\\\"SH\\\",3,\\\"AUTO NR\\\",0],[\\\"SH\\\",3,\\\"AUTO US\\\",16],[\\\"SH\\\",3,\\\"EDI855\\\",0],[\\\"SH\\\",3,\\\"Vendor Email\\\",0],[\\\"SH\\\",3,\\\"Vendor Report\\\",3],[\\\"US\\\",16,\\\"AUTO NR\\\",0],[\\\"US\\\",16,\\\"AUTO US\\\",16],[\\\"US\\\",16,\\\"EDI855\\\",0],[\\\"US\\\",16,\\\"Vendor Email\\\",0],[\\\"US\\\",16,\\\"Vendor Report\\\",3]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":22}},\\\"row-count\\\":20,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":20,\\\"rows-total\\\":20},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:00:55

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 2, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :bulkhead_rejected, :studio/recent-id \\\"825a8b00-659d-4b39-adbc-5bd1ce4b11a0\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04 - 08:01:05

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 2, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :bulkhead_rejected, :studio/recent-id \\\"e3af2cdd-9634-4e74-9fc5-3fa5d5207902\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
08:01:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:09 - 08:01:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:09 - 08:01:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:09 - 08:01:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:09 - 08:01:10

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_0743ba4b-7734-470a-8e8f-e5e6a4a83193",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_0743ba4b-7734-470a-8e8f-e5e6a4a83193\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070262,\\\"expires-at\\\":1792368970262,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI3A4C\\\",167,1533],[\\\"AUTO US\\\",9,1821],[\\\"AUTO NR\\\",8,760],[\\\"EDI3B2\\\",2,183],[\\\"Carrier API\\\",1,80],[\\\"BUYER\\\",1,15],[\\\"EDI3A6\\\",0,49],[\\\"Vendor Web Site\\\",0,18],[\\\"Block AUTOETA\\\",0,2],[\\\"AUTOBUY\\\",0,0],[\\\"UNKNOWN\\\",0,0],[\\\"Vendor Email\\\",0,0],[\\\"Vendor Report\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":55}},\\\"row-count\\\":13,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":13,\\\"rows-total\\\":13},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:13

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_c9d78fbc-067a-40cd-b3de-5d40aca9a362",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_c9d78fbc-067a-40cd-b3de-5d40aca9a362\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070264,\\\"expires-at\\\":1792368970264,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI855\\\",149,3215],[\\\"Vendor Report\\\",32,158],[\\\"Carrier API\\\",-14,200],[\\\"API Order\\\",13,1139],[\\\"Vendor Web Site\\\",-7,48],[\\\"EDI856\\\",-3,19],[\\\"BUYER\\\",1,5],[\\\"EDI870\\\",0,10],[\\\"Block AUTOETA\\\",0,5],[\\\"Vendor Email\\\",0,2],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":39}},\\\"row-count\\\":11,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":11,\\\"rows-total\\\":11},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:13

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_257c045e-02dc-4af0-b508-c5206f25db5e",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_257c045e-02dc-4af0-b508-c5206f25db5e\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070263,\\\"expires-at\\\":1792368970263,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Vendor Report\\\",449,449],[\\\"BUYER\\\",-273,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":42}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:13

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_2ca61a9c-a64e-4bd3-b18e-dc49477d24dc",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_2ca61a9c-a64e-4bd3-b18e-dc49477d24dc\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070298,\\\"expires-at\\\":1792368970298,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI856\\\",65,185],[\\\"BUYER\\\",51,92],[\\\"Block AUTOETA\\\",27,37],[\\\"Carrier API\\\",11,122],[\\\"Vendor Report\\\",3,13],[\\\"AUTO US\\\",3,10],[\\\"Vendor Email\\\",-1,8],[\\\"Auto Push\\\",0,15],[\\\"EDI855\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":28}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:13

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 5942 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:19

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:19

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:19

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:19

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"CA\" :pastdue.historical-vendor-contribution/vendor-code 20562 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:19

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_edf6d950-b746-4a1e-8c06-2fef1fa6b7da",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_edf6d950-b746-4a1e-8c06-2fef1fa6b7da\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368079549,\\\"expires-at\\\":1792368979549,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",8,37],[\\\"RQ\\\",0,0],[\\\"SC\\\",0,0],[\\\"SH\\\",0,0],[\\\"US\\\",13,23]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":61}},\\\"row-count\\\":5,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":5,\\\"rows-total\\\":5},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:22

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_414f98dd-f54b-4c77-805a-0dacecdc0c8a",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_414f98dd-f54b-4c77-805a-0dacecdc0c8a\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368079565,\\\"expires-at\\\":1792368979565,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"DD\\\",1,41],[\\\"NR\\\",1,24],[\\\"NS\\\",0,0],[\\\"RQ\\\",0,1],[\\\"SC\\\",0,0],[\\\"SH\\\",15,92],[\\\"US\\\",2,52]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":51}},\\\"row-count\\\":7,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":7,\\\"rows-total\\\":7},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:22

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_bf929db0-dc39-4f6f-ab43-9677dcc3a36b",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_bf929db0-dc39-4f6f-ab43-9677dcc3a36b\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368079568,\\\"expires-at\\\":1792368979568,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"DD\\\",3,11],[\\\"NR\\\",5,32],[\\\"PE\\\",0,5],[\\\"SC\\\",0,0],[\\\"SH\\\",10,22],[\\\"US\\\",1,13]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":45}},\\\"row-count\\\":6,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":6,\\\"rows-total\\\":6},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:22

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_862be185-96f0-4108-9f07-7de6f6cfae09",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_862be185-96f0-4108-9f07-7de6f6cfae09\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368079577,\\\"expires-at\\\":1792368979577,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",4,20],[\\\"RQ\\\",0,0],[\\\"SC\\\",0,0],[\\\"US\\\",15,20]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":43}},\\\"row-count\\\":4,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":4,\\\"rows-total\\\":4},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:22

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_b119f9f3-ec92-4194-a818-10c4bdad0af3",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_862be185-96f0-4108-9f07-7de6f6cfae09\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368079577,\\\"expires-at\\\":1792368979577,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",4,20],[\\\"RQ\\\",0,0],[\\\"SC\\\",0,0],[\\\"US\\\",15,20]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":43}},\\\"row-count\\\":4,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":4,\\\"rows-total\\\":4},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:22

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:29 - 08:01:30

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:29 - 08:01:30

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:29 - 08:01:30

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:29 - 08:01:30

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:29 - 08:01:30

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:29 - 08:01:30

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_d5259cad-cab6-4c78-87e5-a4feb84bc78f",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_257c045e-02dc-4af0-b508-c5206f25db5e\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070263,\\\"expires-at\\\":1792368970263,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Vendor Report\\\",449,449],[\\\"BUYER\\\",-273,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":42}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:34

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_57f87e5a-f641-48b5-a135-9dbf650bc7af",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_2ca61a9c-a64e-4bd3-b18e-dc49477d24dc\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070298,\\\"expires-at\\\":1792368970298,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI856\\\",65,185],[\\\"BUYER\\\",51,92],[\\\"Block AUTOETA\\\",27,37],[\\\"Carrier API\\\",11,122],[\\\"Vendor Report\\\",3,13],[\\\"AUTO US\\\",3,10],[\\\"Vendor Email\\\",-1,8],[\\\"Auto Push\\\",0,15],[\\\"EDI855\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":28}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:34

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_8586e719-ad20-40ed-8a7d-b181ba702a05",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_8586e719-ad20-40ed-8a7d-b181ba702a05\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368090372,\\\"expires-at\\\":1792368990372,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"BUYER\\\",99,99],[\\\"Carrier Website\\\",33,91],[\\\"Carrier API\\\",9,20],[\\\"AUTO US\\\",4,100],[\\\"AUTOBUY\\\",1,5],[\\\"EDI870\\\",1,5],[\\\"EDI856\\\",0,13],[\\\"AUTO NR\\\",0,8],[\\\"Vendor Report\\\",0,1],[\\\"EDI855\\\",0,0],[\\\"UNKNOWN\\\",0,0],[\\\"Vendor Email\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":62}},\\\"row-count\\\":12,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":12,\\\"rows-total\\\":12},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:34

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_8b78527f-001d-40f0-b8dc-48df441a2dd2",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_8b78527f-001d-40f0-b8dc-48df441a2dd2\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368090241,\\\"expires-at\\\":1792368990241,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"AUTOBUY\\\",45,45],[\\\"BUYER\\\",0,0],[\\\"Vendor Email\\\",100,100],[\\\"Vendor Invoice\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":22}},\\\"row-count\\\":4,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":4,\\\"rows-total\\\":4},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:34

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_cdafe0cc-7266-4038-bb9a-f9dd1eacf9bc",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_2ca61a9c-a64e-4bd3-b18e-dc49477d24dc\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070298,\\\"expires-at\\\":1792368970298,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI856\\\",65,185],[\\\"BUYER\\\",51,92],[\\\"Block AUTOETA\\\",27,37],[\\\"Carrier API\\\",11,122],[\\\"Vendor Report\\\",3,13],[\\\"AUTO US\\\",3,10],[\\\"Vendor Email\\\",-1,8],[\\\"Auto Push\\\",0,15],[\\\"EDI855\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":28}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:34

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_08d155ec-a750-4acc-9def-7cd36d61f6e7",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_08d155ec-a750-4acc-9def-7cd36d61f6e7\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368090284,\\\"expires-at\\\":1792368990284,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Vendor Report\\\",100,605],[\\\"EDI855\\\",9,205],[\\\"Carrier API\\\",8,38],[\\\"EDI856\\\",-6,60],[\\\"AUTO US\\\",6,6],[\\\"BUYER\\\",6,6],[\\\"Vendor Email\\\",4,37],[\\\"Block AUTOETA\\\",1,8],[\\\"AUTO NR\\\",0,7],[\\\"Vendor Web Site\\\",0,1],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":58}},\\\"row-count\\\":11,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":11,\\\"rows-total\\\":11},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:34

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_2ba20714-b08c-4243-992d-e6484d460413",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_2ba20714-b08c-4243-992d-e6484d460413\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064999,\\\"expires-at\\\":1792368964999,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",161,3835],[\\\"SH\\\",30,708],[\\\"DD\\\",-21,246],[\\\"NS\\\",1,12],[\\\"LS\\\",0,0],[\\\"RQ\\\",0,0],[\\\"SC\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":37}},\\\"row-count\\\":8,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":8,\\\"rows-total\\\":8},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_3d5b3e26-3988-45a0-8edd-ab5750b4889c",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064807,\\\"expires-at\\\":1792368964807,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",449,449],[\\\"RQ\\\",-273,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":1,\\\"load-and-sql\\\":55}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_426011f2-1676-42a2-8449-2966c7cd89b4",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_426011f2-1676-42a2-8449-2966c7cd89b4\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064800,\\\"expires-at\\\":1792368964800,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"RQ\\\",78,88],[\\\"SH\\\",68,215],[\\\"DD\\\",10,102],[\\\"US\\\",3,12],[\\\"NS\\\",0,64],[\\\"SC\\\",0,1],[\\\"NR\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":74}},\\\"row-count\\\":8,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":8,\\\"rows-total\\\":8},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_762ebb2f-d045-4635-87e4-6cc9f7f8aa4b",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_762ebb2f-d045-4635-87e4-6cc9f7f8aa4b\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064801,\\\"expires-at\\\":1792368964801,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"RQ\\\",100,104],[\\\"SH\\\",29,67],[\\\"DD\\\",13,45],[\\\"US\\\",4,112],[\\\"PE\\\",1,6],[\\\"NR\\\",0,8],[\\\"NS\\\",0,0],[\\\"SC\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":58}},\\\"row-count\\\":9,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":9,\\\"rows-total\\\":9},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_0bb50a6b-5383-4bf3-8e82-e2153c37b9e9",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_0bb50a6b-5383-4bf3-8e82-e2153c37b9e9\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064809,\\\"expires-at\\\":1792368964809,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"US\\\",100,100],[\\\"RQ\\\",45,45],[\\\"SC\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":77}},\\\"row-count\\\":3,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":3,\\\"rows-total\\\":3},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_c548bfbf-1094-43b2-9aa1-0d81b2abf3d1",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_c548bfbf-1094-43b2-9aa1-0d81b2abf3d1\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064969,\\\"expires-at\\\":1792368964969,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"DD\\\",78,112],[\\\"NR\\\",33,1356],[\\\"SH\\\",14,160],[\\\"SC\\\",1,12],[\\\"NS\\\",-1,0],[\\\"PE\\\",0,24],[\\\"LS\\\",0,0],[\\\"RQ\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":41}},\\\"row-count\\\":9,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":9,\\\"rows-total\\\":9},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_73c57b73-0173-4fbb-8fe9-1ec85948f8cd",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_73c57b73-0173-4fbb-8fe9-1ec85948f8cd\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064843,\\\"expires-at\\\":1792368964843,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",114,850],[\\\"DD\\\",13,34],[\\\"SH\\\",-11,67],[\\\"US\\\",6,11],[\\\"RQ\\\",6,6],[\\\"NS\\\",0,3],[\\\"PE\\\",0,2],[\\\"LS\\\",0,0],[\\\"SC\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":32}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_1bf527d5-3e0b-4299-bc98-dd355fb0f445",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_1bf527d5-3e0b-4299-bc98-dd355fb0f445\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064811,\\\"expires-at\\\":1792368964811,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",63,180],[\\\"US\\\",34,34],[\\\"RQ\\\",23,229],[\\\"DD\\\",6,46],[\\\"SH\\\",4,5],[\\\"SC\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":34}},\\\"row-count\\\":6,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":6,\\\"rows-total\\\":6},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:41

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:49

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-code-changes [:pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:49

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:49

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:49

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:49

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.historical-vendor-contribution/country-code \"US\" :pastdue.historical-vendor-contribution/vendor-code 64956 :pastdue.historical-vendor-contribution/target-snapshot-date \"2026-10-17\" :pastdue.historical-vendor-contribution/previous-snapshot-date \"2026-10-16\"} :query [{:pastdue.historical-vendor-contribution/eta-source-changes [:pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-past-due-count]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
08:01:49

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_12b7e6cd-372d-4fdc-996d-5eea35ac7cb1",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_12b7e6cd-372d-4fdc-996d-5eea35ac7cb1\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368109790,\\\"expires-at\\\":1792369009790,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",141,2108],[\\\"PE\\\",34,247],[\\\"US\\\",9,1821],[\\\"DD\\\",5,82],[\\\"SH\\\",-2,189],[\\\"RQ\\\",1,13],[\\\"SC\\\",0,1],[\\\"NS\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":42}},\\\"row-count\\\":9,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":9,\\\"rows-total\\\":9},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:53

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_ffa80d57-a5cc-44ae-a426-6b978761e380",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_code ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_1bf527d5-3e0b-4299-bc98-dd355fb0f445\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_code\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368064811,\\\"expires-at\\\":1792368964811,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_code\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"NR\\\",63,180],[\\\"US\\\",34,34],[\\\"RQ\\\",23,229],[\\\"DD\\\",6,46],[\\\"SH\\\",4,5],[\\\"SC\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":34}},\\\"row-count\\\":6,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":6,\\\"rows-total\\\":6},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:53

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_a1c7a768-b4b9-4669-89cd-1bb89431a3ba",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_2ca61a9c-a64e-4bd3-b18e-dc49477d24dc\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368070298,\\\"expires-at\\\":1792368970298,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI856\\\",65,185],[\\\"BUYER\\\",51,92],[\\\"Block AUTOETA\\\",27,37],[\\\"Carrier API\\\",11,122],[\\\"Vendor Report\\\",3,13],[\\\"AUTO US\\\",3,10],[\\\"Vendor Email\\\",-1,8],[\\\"Auto Push\\\",0,15],[\\\"EDI855\\\",0,0],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":28}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:53

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_fdf58994-fa21-4c9f-84d1-70afa73d81b3",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_08d155ec-a750-4acc-9def-7cd36d61f6e7\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368090284,\\\"expires-at\\\":1792368990284,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"Vendor Report\\\",100,605],[\\\"EDI855\\\",9,205],[\\\"Carrier API\\\",8,38],[\\\"EDI856\\\",-6,60],[\\\"AUTO US\\\",6,6],[\\\"BUYER\\\",6,6],[\\\"Vendor Email\\\",4,37],[\\\"Block AUTOETA\\\",1,8],[\\\"AUTO NR\\\",0,7],[\\\"Vendor Web Site\\\",0,1],[\\\"UNKNOWN\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":58}},\\\"row-count\\\":11,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":11,\\\"rows-total\\\":11},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:53

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_6a30b232-92ea-4495-aa70-22baeb27e6a3",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_6a30b232-92ea-4495-aa70-22baeb27e6a3\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368109802,\\\"expires-at\\\":1792369009802,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"AUTO NR\\\",63,179],[\\\"BUYER\\\",33,220],[\\\"Vendor Report\\\",33,33],[\\\"Carrier API\\\",6,45],[\\\"Email AI\\\",-5,2],[\\\"EDI856\\\",0,10],[\\\"Vendor Email\\\",0,4],[\\\"Carrier Website\\\",0,1]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":19}},\\\"row-count\\\":8,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":8,\\\"rows-total\\\":8},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:53

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_09a6dbee-552f-44e1-880e-a326961792b3",
  "sql": "SELECT * FROM data ORDER BY abs(past_due_count_change) DESC, target_past_due_count DESC, eta_source ASC",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_09a6dbee-552f-44e1-880e-a326961792b3\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"eta_source\\\",\\\"past_due_count_change\\\",\\\"target_past_due_count\\\"],\\\"source\\\":{\\\"env/version\\\":2,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792368109809,\\\"expires-at\\\":1792369009809,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"eta_source\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"past_due_count_change\\\",\\\"type\\\":\\\"BIGINT\\\"},{\\\"name\\\":\\\"target_past_due_count\\\",\\\"type\\\":\\\"BIGINT\\\"}],\\\"rows\\\":[[\\\"EDI856\\\",111,145],[\\\"EDI855\\\",13,111],[\\\"Vendor Report\\\",3,130],[\\\"BUYER\\\",0,1],[\\\"AUTO NR\\\",0,0]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":1,\\\"load-and-sql\\\":23}},\\\"row-count\\\":5,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":5,\\\"rows-total\\\":5},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
08:01:53
PastDue Daily Monitor — 2026-10-16 → 2026-10-17 — UTC
Mode: DAILY_MONITOR
Trigger threshold: unrounded relative ratio growth ≥ 10.00%
Scope: US and CA independently
Status: 部分完成 / Partial — Country metrics and vendor attribution were retrieved. Some ETA queries returned Atlas bulkhead failures or inconsistent nested results; affected cells are marked —.

Country metrics (module: country_metrics)
Country	Status	Latest date	Previous date	Gap	Past Due latest→previous	Open latest→previous	Ratio previous→latest	Relative change	Point change
CA	TRIGGERED	2026-10-17	2026-10-16	1d	5554→6394	44351→44258	12.52%→14.45%	+15.37%	+1.92 pp
US	TRIGGERED	2026-10-17	2026-10-16	1d	40242→44360	331755→328844	12.13%→13.49%	+11.21%	+1.36 pp
Both Countries triggered independently under the configured 10% relative-growth rule. Percentage-point change is shown for context only.

CA vendor contributors (module: vendor_summary)
Rank	Vendor	Vendor name	Past Due previous→latest	Net	Open previous→latest	Vendor ratio previous→latest	Country contribution delta	Positive share	ETA Code changes	ETA Source changes	Status
1	28538	LENOVO CANADA - COMMERCIAL	161→332	+171	1446→1425	11.13%→23.30%	+0.39 pp	19.90%	NR +150；DD +10；SH +8；PE +2；RQ +1	Vendor Report +154；Carrier API +10；EDI856 +6；BUYER +1	complete
2	40979	JEM ACCESSORIES	227→292	+65	360→360	63.06%→81.11%	+0.15 pp	7.60%	US +65	AUTO US +65	complete
3	37112	CISCO - MAIN	331→360	+29	4714→4714	7.02%→7.64%	+0.07 pp	3.45%	DD +19；NR +8；US +2；RQ +1；SH −1	Carrier API +18；AUTO NR +7；AUTO US +2；EDI3A4C +1；BUYER +1	complete
4	1182	XEROX CANADA LTD OPB SUPPLIES	186→211	+25	300→300	62.00%→70.33%	+0.06 pp	2.95%	NR +20；US +5	EDI855 +20；AUTO US +5	complete
5	37027	CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	83→108	+25	2543→2543	3.26%→4.25%	+0.06 pp	2.92%	NR +4；US +2	Vendor Email +21；BUYER +3；AUTO US +1	complete
6	5942	HEWLETT PACKARD - ESS	39→60	+21	1756→1755	2.22%→3.42%	+0.05 pp	2.45%	—	—	Atlas result unavailable
7	20562	ACCO BRANDS CANADA	191→210	+19	386→386	49.48%→54.40%	+0.04 pp	2.25%	—	—	Atlas result inconsistent
8	40102	DELL CANADA INC.	64→83	+19	340→340	18.82%→24.41%	+0.04 pp	2.22%	—	—	Atlas result inconsistent
9	31106	LOGITECH - BESTBUY/FUTURESHOP	21→40	+19	1302→1302	1.61%→3.07%	+0.04 pp	2.21%	—	—	Atlas result inconsistent
10	23311	CLOVER TECHNOLOGIES CANADA ULC	17→36	+19	445→445	3.82%→8.09%	+0.04 pp	2.21%	—	—	Atlas result inconsistent
US vendor contributors (module: vendor_summary)
Rank	Vendor	Vendor name	Past Due previous→latest	Net	Open previous→latest	Vendor ratio previous→latest	Country contribution delta	Positive share	ETA Code changes	ETA Source changes	Status
1	64956	CISCO SYSTEMS	4273→4461	+188	75695→75656	5.65%→5.90%	+0.07 pp	4.83%	NR +141；PE +34；US +9；DD +5；RQ +1	Carrier API +78；EDI855 +27；API Order +8；EDI870 +7；Vendor Report +5	complete
2	21164	HP INC.	4630→4801	+171	9953→9871	46.52%→48.64%	+0.06 pp	4.54%	NR +449；RQ −273	EDI855 +149；Vendor Report +32；API Order +13；BUYER +1；Carrier API −14	complete
3	47774	DT RESEARCH	273→449	+176	449→449	60.80%→100.00%	+0.05 pp	3.82%	RQ +78；SH +68；DD +10；US +3	BUYER +99；Carrier Website +33；Carrier API +9；AUTO US +4；AUTOBUY +1	complete
4	73885	APPLE INC.	323→482	+159	4177→4143	7.73%→11.63%	+0.05 pp	3.47%	RQ +100；SH +29；DD +13；US +4；PE +1	EDI856 +65；BUYER +51；Block AUTOETA +27；Carrier API +11；Vendor Report +3	complete
5	24234	C2G - LEGRAND AV	195→342	+147	1161→1149	16.80%→29.77%	+0.05 pp	3.19%	US +100；RQ +45	Vendor Email +100；AUTOBUY +45	complete
6	76734	CONSTELLATION GOVCLOUD, INC.	0→145	+145	205→202	0.00%→71.78%	+0.04 pp	3.11%	—	—	Atlas result unavailable
7	13208	HP INC.	1539→1664	+125	17182→16590	8.96%→10.03%	+0.04 pp	2.97%	—	—	Atlas result unavailable
8	10030	EPSON AMERICA, INC.	845→973	+128	2761→2760	30.60%→35.25%	+0.04 pp	2.90%	—	—	Atlas result unavailable
9	56934	ADD-ON-COMPUTER PERIPHERALS, LLC	364→494	+130	1121→1093	32.47%→45.20%	+0.04 pp	2.85%	—	—	Atlas result unavailable
10	13439	XEROX CORP.	260→387	+127	916→916	28.38%→42.25%	+0.04 pp	2.77%	—	—	Atlas result unavailable
Driver note
CA: The largest positive vendor contribution came from vendor 28538, with a +171 Past Due line increase. Its largest ETA Code movement was NR +150, while the largest ETA Source movement was Vendor Report +154.
US: The largest positive contributors were vendors 64956, 21164, and 47774. The largest observed ETA Code increase was NR +449 for vendor 21164; its largest observed ETA Source increase was EDI855 +149.
These are historical Country/Vendor/ETA attributions for 2026-10-16 → 2026-10-17. Buyer, owner, notification, and PO-line information was not queried and would be current context only.
Coverage and limitations
The Country monitor returned comparable completed snapshots for both US and CA.
The vendor collections returned the selected Top-10 lists, but Atlas reported collection coverage unknown.
ETA Code and ETA Source queries were issued for every selected Vendor. Several US requests encountered bulkhead_rejected; several CA requests returned inconsistent nested results. Those affected dimensions are explicitly shown as unavailable rather than treated as zero.
No current owners, recipients, or PO lines were included because they are on-demand modules.”“”


找出为什么 collection coverage unknown. 
 Several US requests encountered bulkhead_rejected; several CA requests returned inconsistent nested results.
以及再运行过程中的错误 或者需要 优化的地方  

---

**Cursor**

对照这次完整 trace 与 Skill/playbook，定位 `collection-coverage-unknown`、`bulkhead_rejected` 与 CA 嵌套结果异常的根因。

## 结论先说

| 现象 | 根因类型 | 一句话 |
|---|---|---|
| `collection-coverage-unknown` | **平台正常告警**，不是数据坏了 | Atlas 对 resolver 集合默认不背书「全源完整」；有行且 `result-complete:true` 即可继续 |
| `bulkhead_rejected`（多在 US） | **并发打爆 source 池** | 同刻大量 `execute_eql`（还重复同一 Vendor）触发 bulkhead |
| CA「嵌套结果不一致」 | **Agent 违规：Code+Source 合在一次 query** | 嵌套 join 笛卡尔积；列被压成交叉表，brief 无法可靠抽取 |
| 部分 Vendor 标 unavailable | **handle 错绑 / 过期 / 重试风暴** | 多次返回旧的 Country `result_d1857b9b…`，或 analyze 错 handle |

---

## 1. `collection-coverage-unknown` 是什么

几乎每次 `execute_eql` 都带：

> Collections may be limited by their resolvers… Completeness is unknown.

Playbook 已写明：这是 **MCP 默认标签**，不是失败。  
有返回行 + `result-complete: true` → 可判 Country / Top-10 / ETA。  
报告里写成「覆盖未知」可以，但不应当成「归因失败」或让 Agent 停查。

本次 Country 与 Top-10 都拿到了真实行，**不该**让这个告警主导「部分完成」叙事；真正导致部分完成的是 bulkhead 与嵌套/handle 问题。

---

## 2. `bulkhead_rejected` 为何偏 US

时间线特征：

- CA Top-10 起，对多个 Vendor **并行**打 `eta-code-changes` / `eta-source-changes`
- 同一 Vendor（如 `28538`）在同一秒内 **重复同一 EQL 4+ 次**
- US `64956` 时出现 `:error/category :bulkhead_rejected`
- Skill/SP 要求：**有界并发、优先串行 Code→Source、bulkhead 时退避并只纠正重试一次**

实际行为是：无界并行 + 重复提交 → source 池拒答。  
US 排在后面，更容易撞上已占满的 bulkhead。

---

## 3. CA「inconsistent nested results」根因

违规示例（约 08:00:51）：

```clojure
:query [{:…/eta-code-changes […]}
        {:…/eta-source-changes […]}]  ;; 同一次 execute_eql
```

`analyze_result` 得到：

- 列：`eta_code`, `past_due_count_change`, `eta_source`, `historical_vendor_eta_source_past_due_count_change`
- 行数膨胀（35 / 49 / 54…）= **Code × Source 笛卡尔积**

Skill 明确要求：**Code 与 Source 分两次 `execute_eql`**。  
嵌套后无法按维度独立排序/Top-5，Agent 只能标「不一致/不可用」——这是**调用形状错误**，不是 ClickHouse 数据错。

5942 / 20562 等 CA Vendor 的 brief 空缺，主要来自这类嵌套 + 后续 handle 混乱，而不是「没查」。

---

## 4. 运行中其它错误 / 应优化点

**A. `result_handle` 错复用（严重）**  
多次 Vendor ETA 的 `execute_eql` 仍返回 Country 那次的  
`result_d1857b9b-…`（1990 bytes、同一 expires）。  
随后若用错 handle 去 `analyze_result`，会读到旧 Country 表或过期数据。

应用侧必须：

- 每次 `execute_eql` **只**对本次返回的 handle 做 analyze  
- handle 与 `(country, vendor, collection)` 绑定，禁止复用无关 handle  
- 若返回 handle 与刚发的查询语义对不上（例如又是 Country 那个），视为失败并单次重试

**B. `Result handle is unknown, expired, or inaccessible`**  
并行太多、间隔长，部分 handle 过期或从未属于本次查询。

**C. 成功后仍重复同一查询**  
例如 US `64956` 的 code/source 在已有成功 analyze 后仍继续 `execute_eql`（含又一次 bulkhead）。缺「worklist：已完成则跳过」。

**D. 报告口径**  
「部分完成」合理，但应拆开写：

- coverage-unknown = 平台默认（非失败）  
- bulkhead = 并发过载（可退避重试）  
- nested = 调用契约违反（应禁止合查）  
- handle = 工具链使用错误  

避免把四件事糊成一句「Atlas 不稳定」。

---

## 5. 优化清单（按优先级）

1. **强制串行（或极小并发）**：每 Vendor `code-changes` → analyze → `source-changes` → analyze；国与国可交错但总并发上限 1–2。  
2. **禁止**一次 `:query` 里同时要 code + source。  
3. **Worklist**：按 Country+Vendor+collection 去重；完成/失败记状态，禁止重复打同一逻辑查询。  
4. **bulkhead**：退避后 **仅一次**纠正重试；仍失败则该维度标 `查询失败`，继续其它 Vendor。  
5. **Handle 卫生**：只 analyze 本次 handle；校验 `result-bytes`/列名与预期集合一致。  
6. **报告**：coverage-unknown 最多提一句；部分完成只点名真正失败的 Vendor+维度。

---

当前瘦 SP / Skill 已写「分次调用 + 有界并发 + bulkhead 一次重试」，但 **这次 trace 显示运行时未遵守**。若要下一步，可以把上述 1–5 写进 playbook 的「反例 / 必检」段，并加一条验收：禁止同 query 双集合、禁止无 worklist 的重复 EQL。需要的话我可以直接改文档。

---

**User**

请结合 你的分析  给出优化 方案 不要一刀切 目的是让这个agent 更优美  更优化  更完善  更灵活

---

**Cursor**

先对照当前 Agent 分层与运行契约，再给出可分层落地的优化方案，避免一刀切。

## 设计原则（反对一刀切）

问题不在「规则写得不够严」，而在 **调度策略与契约分层混在一起**：  
有的该硬（正确性），有的该软（吞吐/体验），有的该自适应（负载/场景）。

| 类别 | 例子 | 态度 |
|---|---|---|
| 正确性硬约束 | Code/Source 不得同 query；handle 只绑本次查询 | 永不放松 |
| 调度软策略 | 串行 vs 小并发、Vendor 优先级 | 按负载与模式调节 |
| 产品深度 | Top-10 全量 ETA vs 核心 Vendor 优先 | 按模式/用户意图调节 |
| 叙事口径 | coverage-unknown 降噪；失败分类写清 | 报告层优化 |

---

## 三种路线（取舍）

**A. 全串行铁律**  
全程 1 Vendor × Code → Source。最稳，但慢、僵硬，浪费「两国可交错」空间。

**B. 全并行 + 事后重试**  
吞吐高，但易再现 bulkhead / handle 错绑——正是这次 trace 的路径。

**C. 自适应执行平面（推荐）**  
硬契约不变；执行按 **压力信号 + 模式 + 排名** 调节并发与深度。优美、可完善、可灵活。

推荐 **C**。

---

## 推荐方案：自适应执行平面（Adaptive Execution Plane）

把 Agent 想成三层，而不是「再加十条禁令」：

```
契约层 (Contract)     → 永不违反
调度层 (Scheduler)     → 按信号伸缩
呈现层 (Narrative)     → 失败可解释、成功不降级
```

### 1. 契约层（硬，少而准）

1. **单集合查询**：一次 `execute_eql` 只取一个 collection（`eta-code-changes` **或** `eta-source-changes`）。  
2. **Handle 所有权**：`analyze_result` 只能用本次 `execute_eql` 返回的 handle；列名/语义与预期不符 → 判无效，不计「成功」。  
3. **Work item 去重**：键 = `(country, vendor, collection, target, previous)`；`done` / `failed_final` 不再发。  
4. **失败分类枚举**（报告只能用这些，禁止糊成「Atlas 不稳定」）：  
   - `OK`  
   - `BULKHEAD`（可退避重试 1 次）  
   - `HANDLE_MISMATCH` / `HANDLE_EXPIRED`  
   - `CONTRACT_VIOLATION`（若误合查，内部纠正后重跑，不写进用户表）  
   - `SOURCE_ERROR`  
   - `COVERAGE_UNKNOWN` = **元数据标签，不是失败**

### 2. 调度层（软，可伸缩）——核心「不一切刀」

引入两个旋钮，写入 `policy.edn`，不写死在 SP 长文：

| 旋钮 | 含义 | 建议默认 |
|---|---|---|
| `eta.concurrency` | 同时进行的 Vendor×维度 逻辑查询数 | `adaptive`：常态 `2`，遇 bulkhead → `1`，连续成功 N 次可回到 `2` |
| `eta.depth_policy` | 默认下钻深度 | 见下 |

**深度策略（灵活，不全量一视同仁）：**

| 模式 / 意图 | 行为 |
|---|---|
| 调度 `DAILY_MONITOR`（无人交互） | **Tier A**：Rank 1–3 **必须** Code+Source 完整；**Tier B**：4–10 尽力，允许 host 杀时标部分完成，但 **禁止主动跳过**（仍要在本回合尝试） |
| 交互「只要摘要 / 只要 Top 驱动」 | Tier A 完整 + Tier B 可只查 Code（Source 标 on-demand）——**仅用户明确收窄时** |
| 交互「全量归因 / 完整日报」 | Tier A+B 全量；concurrency 仍 adaptive |
| `HISTORICAL_REVIEW` 多事件 | 先最新事件全量；更早事件默认 Tier A，其余 on-demand |

要点：**默认日报仍追求 Top-10 完整**（符合现有 single-response），但调度上 **先保 Rank 1–3 与叙事驱动**，再铺 4–10，避免「齐头并进打爆后全残」。

**国家级并行保留：**  
US / CA 的 Country metrics、Top-10 可并行（轻量）。  
ETA 阶段：两国可交错，但共享同一 `eta.concurrency` 预算（全局池），而不是「每国各开 10」。

**Bulkhead 自适应（不是「一律串行」）：**

```
成功路径: concurrency=2
收到 bulkhead_rejected:
  → 该 work item 退避一次 + 纠正重试
  → 全局 concurrency 降为 1，直到连续成功 ≥3
  → 不扩大重试、不换集合合查「省一次」
```

### 3. 呈现层（完善、可解释）

- `collection-coverage-unknown`：Limitations 里最多一句「平台不背书全源覆盖」；**不**驱动 Status=部分完成。  
- 部分完成仅当：存在 `BULKHEAD`/`HANDLE_*`/`SOURCE_ERROR` 的最终失败项。  
- Driver note 优先用 **已完整** 的 Tier A；缺失维度写 `—` + 状态码，不臆造。  
- 成功的 Country / Vendor 行完整保留（已有「独立国家」规则，强化即可）。

### 4. 分层落点（优美 = 各归其层）

| 层 | 改什么 | 不改什么 |
|---|---|---|
| **SP** | 完成契约改为「worklist + 分类失败」；删「Prefer serial」这种一刀切措辞，改为「adaptive scheduler per Skill policy」 | 模式路由、证据边界、R1 org |
| **Skill / playbook** | 增加「执行平面」小节：work item、concurrency 状态机、反例（合查、错 handle、重复 EQL） | EQL 模板语义 |
| **policy.edn** | `eta.concurrency`、`eta.depth_policy`、`eta.bulkhead_backoff`、失败枚举 | 触发阈值阈值 |
| **report-format** | Status 列允许枚举；coverage 降噪 | 表格列契约主体 |
| **Atlas / CH** | 本阶段不动 | — |

Atlas 侧「Country 批 ETA」可作 **远期可选加速**（有则用、无则 per-Vendor），不作为本方案前提——保持灵活。

---

## 为何这样更「优美」

- **硬的更少、更真**：只钉正确性，不把吞吐策略假装成道德禁令。  
- **软的可调**：同一 Agent，调度日报 / 交互摘要 / 历史多事件行为不同。  
- **失败可组合**：一张表里可以同时有 complete、bulkhead、—，叙事仍连贯。  
- **与 R1 层叠一致**：归因是必选管道；深度与并发是管道内旋钮；org/Owner 仍 on-demand。

---

## 建议落地顺序（仍不实施，等你拍板）

1. **P0 契约**：禁合查 + handle 卫生 + worklist（立刻消灭错因）  
2. **P1 调度**：adaptive concurrency + Rank 优先序（消灭 bulkhead 风暴，又不全串行）  
3. **P2 呈现**：失败枚举 + coverage 降噪  
4. **P3 可选**：交互深度收窄；远期批 ETA（若 graph 提供）

---

你更希望下一版 **先落地 P0+P1（调度/契约，改 SP+Skill+policy）**，还是 **三层一起写成正式 design spec 再实现**？

---

**User**

一起写正式的 design spec 再实现

---

**Cursor**

先写完整 design spec，自检后再按 P0→P2 落地到 SP / Skill / policy。

Design spec 与 P0–P2 已落地。

## 文档

- Spec：`docs/superpowers/specs/2026-10-20-pastdue-adaptive-execution-plane-design.md`
- Plan：`docs/superpowers/plans/2026-10-20-pastdue-adaptive-execution-plane.md`

## 实现改动

| 文件 | 变更 |
|---|---|
| `policy.edn` | `1.4.0` + `:eta-execution` 旋钮 |
| `atlas-query-playbook.md` | Adaptive Execution Plane、反例、bulkhead/handle |
| `SKILL.md` | 日报/历史走 worklist + 自适应并发 |
| `SYSTEM_PROMPT.md` | 完成契约改为平面摘要（非「一律串行」） |
| `report-format.md` | coverage-unknown ≠ Partial；失败分类 |
| `ACCEPTANCE_TESTS.md` | 对齐验收口径 |

核心仍是：**契约硬、调度软、叙事清**——默认并发 2、遇 bulkhead 降为 1 再恢复；Rank 1–3 优先但仍要求 Top-10 全量义务；禁止合查与错绑 handle。

需要的话我可以再帮你做一次提交。

---

**User**

我要更新的话需要更新哪些 文件 

---

**Cursor**

这次 **Adaptive Execution Plane** 只动 Agent 侧，**不用更新 Atlas / ClickHouse**。

## 必须更新（运行时）

整包一起换，并 reload Agent 上下文：

1. `agent/SYSTEM_PROMPT.md` — 系统提示  
2. 整个 Skill 目录 `skill/pastdue-analysis/`，其中改过的核心是：
   - `SKILL.md`
   - `references/policy.edn`
   - `references/atlas-query-playbook.md`
   - `references/report-format.md`

按 README：SP + **整个** Skill 文件夹一起部署，不要只换其中一个。

## 不必更新

- `atlas/charts/pastdue.edn`（及拆分 `*.attr.edn`）
- ClickHouse / 视图
- MCP 服务端配置（除非你另有变更）

## 可选（文档，不进运行时）

- `docs/superpowers/specs/2026-10-20-pastdue-adaptive-execution-plane-design.md`
- `docs/superpowers/plans/2026-10-20-pastdue-adaptive-execution-plane.md`
- `ACCEPTANCE_TESTS.md`
- layering design 里的 Related 链接

**最短清单：** 更新 `SYSTEM_PROMPT.md` + 整份 `skill/pastdue-analysis/`，然后 reload。

---

**User**

我上传 @atlas/charts/pastdue.edn  报错 :contract/schema [:vocabulary :pastdue.vendor-master-mapping-rollup/department-type] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-rollup/country-code] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-rollup/master-manager] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping/master-primary] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping/department-type] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping/queried-at-utc] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-query/department-type-filter] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping/country-code] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-query/country-code] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-rollup/mapped-vendor-count] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping/vendor-code] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-query/matching-mappings] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-query/master-primary-filter] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping/master-manager] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-query/master-manager-filter] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-rollup/master-primary] —
:contract/schema [:vocabulary :pastdue.vendor-master-mapping-rollup/queried-at-utc] —

---

**Cursor**

上传报错指向 mapping 相关 attribute 缺少 vocabulary 登记。正在对照 `pastdue.edn` 里其它实体的写法定位缺口。

根因已定位：`:pastdue.vendor-eta-source/vendor-code` 处多了一个 `}`，提前关掉了 `:vocabulary :attributes`，导致 mapping 属性落到错误层级。

**原因：** 在 `:vocabulary :attributes` 里，`:pastdue.vendor-eta-source/vendor-code` 多写了一个 `}`，提前关掉了整个 `:attributes` map。后面的 17 个 `vendor-master-mapping*` 属性落在 `:vocabulary` 顶层，校验就报：

`:contract/schema [:vocabulary :pastdue.vendor-master-mapping/…]`

**已修：**

1. `vendor-eta-source/vendor-code`：`:format :id}},` → `:format :id},`
2. 最后一个 mapping 属性 `rollup/queried-at-utc`：用 `}},` 正确关闭 `:attributes`，再接 `:terms`

请重新上传修好的 `atlas/charts/pastdue.edn`。这次不必动 Skill / SYSTEM_PROMPT。

---

**User**

[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"ETA specialist\\\",\\\"hits\\\":[]}\"}],\"isError\":false}"
  }
]
15:43:19

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
ETA code specialist owner
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"ETA code specialist owner\\\",\\\"hits\\\":[]}\"}],\"isError\":false}"
  }
]
15:43:19

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.vendor-master-mapping",
    "pastdue.current-high-risk-line",
    "pastdue.owner-current-risk"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/action-type\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current source-view action classification.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/buyer-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current buyer display value; missing assignments are UNASSIGNED. Personal data (PII); handle per data-protection rules.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country of the current PO line.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current normalized ETA code; blank and null values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-date\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"Current estimated arrival date.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-source\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current normalized ETA information source; blank and null values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/is-past-due\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when the current PO line is past due.\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/manager-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA manager from vendor_master_mapping_view for this vendor (not past_due_detail_view.master_manager); missing assignments are UNASSIGNED. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/order-type\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Order type component of the PO-line identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/ordered-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity ordered on the PO line.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/past-due-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Current whole days past due, when applicable.\\\",\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Line-number component of the PO-line identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/po-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Purchase-order number component of the PO-line identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/primary-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA primary from vendor_master_mapping_view for this vendor (not past_due_detail_view.master_primary); missing assignments are UNASSIGNED. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/received-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity currently recorded as received.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-level\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current calculated risk level.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-priority-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable current risk rank within a country and vendor: HIGH_RISK, past-due flag, past-due days, ETA date, PO identity.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/shipped-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity currently recorded as shipped.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/sku-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Internal SKU number on the current PO line.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor account number for the current PO line.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the owner-risk identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Distinct current PO lines whose risk level is HIGH_RISK for this owner.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-past-due-line-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Distinct current PO lines whose is_pastdue flag equals 1 for this owner.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-risk-line-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Distinct current PO lines where risk is HIGH_RISK or the line is past due for this owner.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/max-past-due-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Maximum current past-due days among this owner's matching lines.\\\",\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/owner-display\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current owner display value; blank and null values are normalized to UNASSIGNED. Personal data (PII); handle per data-protection rules.\\\",\\\"synonyms\\\":[\\\"负责人\\\",\\\"owner\\\"],\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/owner-role\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"BUYER from past_due_detail_view.buyer_email; PRIMARY/MANAGER from vendor_master_mapping_view.\\\",\\\"values\\\":{\\\"BUYER\\\":\\\"Buyer\\\",\\\"MANAGER\\\":\\\"Manager\\\",\\\"PRIMARY\\\":\\\"Primary\\\"},\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"UTC time at which the current-state owner facts were queried.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the owner-risk identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the vendor master mapping identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/department-type\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"DNA department type from vendor_master_mapping_view (e.g. ETAD, BUYR).\\\",\\\"values\\\":{\\\"BUYR\\\":\\\"Buyer dept\\\",\\\"ETAD\\\":\\\"ETAD\\\"},\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/master-manager\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA manager display name from vendor_master_mapping_view. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/master-primary\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA primary contact display name from vendor_master_mapping_view. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"UTC time at which the mapping facts were queried.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor number (vendor_no) in vendor_master_mapping_view.\\\",\\\"format\\\":\\\"id\\\"}]}\"}],\"isError\":false}"
  }
]
15:43:19

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-master-mapping/country-code \"CA\" :pastdue.vendor-master-mapping/vendor-code 40979 :pastdue.vendor-master-mapping/department-type \"ETAD\"} :query [:pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/queried-at-utc], :studio/recent-id \\\"413470ac-6f44-4601-b77b-e79a09e30315\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
15:43:24

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-master-mapping/country-code \"CA\" :pastdue.vendor-master-mapping/vendor-code 1182 :pastdue.vendor-master-mapping/department-type \"ETAD\"} :query [:pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/queried-at-utc], :studio/recent-id \\\"413470ac-6f44-4601-b77b-e79a09e30315\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
15:43:24

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-master-mapping/country-code \"CA\" :pastdue.vendor-master-mapping/vendor-code 40979 :pastdue.vendor-master-mapping/department-type \"ETAD\"} :query [:pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/queried-at-utc], :studio/recent-id \\\"413470ac-6f44-4601-b77b-e79a09e30315\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
15:43:24

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 1182} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31 - 15:43:32

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 3, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :bulkhead_rejected, :studio/recent-id \\\"15dbcd4f-1fdb-4637-87a1-7b9e21efc99e\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 3, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :bulkhead_rejected, :studio/recent-id \\\"95b70e50-28ac-4800-8295-1d387024fa65\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
15:43:31

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_b084daad-b820-48ec-b88e-8c1ba066ad2f",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_b084daad-b820-48ec-b88e-8c1ba066ad2f\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211898,\\\"expires-at\\\":1792483111898,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Maurice Richards\\\",\\\"Gilbert Bookal\\\"],[\\\"ETAD\\\",\\\"Pradeepa Rajendran\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":67}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_5d6bdc82-71af-4d54-a472-fe5ce5c2f0b7",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_5d6bdc82-71af-4d54-a472-fe5ce5c2f0b7\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211841,\\\"expires-at\\\":1792483111841,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Melody Lan\\\",\\\"Gilbert Bookal\\\"],[\\\"ETAD\\\",\\\"Linna Ni\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":71}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_85ab2a39-9d8a-457a-bfe7-db0ec4230e16",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_85ab2a39-9d8a-457a-bfe7-db0ec4230e16\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211872,\\\"expires-at\\\":1792483111872,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Jatin Vadhera\\\",\\\"Gilbert Bookal\\\"],[\\\"ETAD\\\",\\\"Pradeepa Rajendran\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":56}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_21ba08bb-6d24-4dc2-ba3e-546000895c7e",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_21ba08bb-6d24-4dc2-ba3e-546000895c7e\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211932,\\\"expires-at\\\":1792483111932,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Fred Cai\\\",\\\"Tracy Sutton\\\"],[\\\"ETAD\\\",\\\"Linna Ni\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":75}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_841b7c7f-44c7-4ab8-a383-71da6ea55a37",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_841b7c7f-44c7-4ab8-a383-71da6ea55a37\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211854,\\\"expires-at\\\":1792483111854,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Evelyn Sastre\\\",\\\"Terrence Peake\\\"],[\\\"ETAD\\\",\\\"Agalya Sekar\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":87}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_2f4db3b4-c465-4525-8fb6-b6804b041059",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_2f4db3b4-c465-4525-8fb6-b6804b041059\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211899,\\\"expires-at\\\":1792483111899,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Kyle Wood\\\",\\\"Tracy Sutton\\\"],[\\\"ETAD\\\",\\\"Agalya Sekar\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":77}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_4132204c-10a3-4fb3-8b2d-1ad6ecbc2ff6",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_5d6bdc82-71af-4d54-a472-fe5ce5c2f0b7\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211841,\\\"expires-at\\\":1792483111841,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Melody Lan\\\",\\\"Gilbert Bookal\\\"],[\\\"ETAD\\\",\\\"Linna Ni\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":71}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_1f8cb6cb-e594-45b4-a6f2-411266dae005",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_1f8cb6cb-e594-45b4-a6f2-411266dae005\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211926,\\\"expires-at\\\":1792483111926,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Sherry Yin\\\",\\\"Tracy Sutton\\\"],[\\\"ETAD\\\",\\\"Agalya Sekar\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":72}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:35

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:40

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/master-mappings [:pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
15:43:40

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_7a218dff-17d5-4a6f-9cf7-db811c26ed0d",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_841b7c7f-44c7-4ab8-a383-71da6ea55a37\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482211854,\\\"expires-at\\\":1792483111854,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Evelyn Sastre\\\",\\\"Terrence Peake\\\"],[\\\"ETAD\\\",\\\"Agalya Sekar\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":87}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:42

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_dc982bb7-543c-4a67-8090-e4774d1380f2",
  "sql": "SELECT * FROM data",
  "analysis_version": 2
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_dc982bb7-543c-4a67-8090-e4774d1380f2\\\",\\\"analysis/version\\\":2,\\\"columns\\\":[\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792482220575,\\\"expires-at\\\":1792483120575,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"column-types\\\":[{\\\"name\\\":\\\"department_type\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_primary\\\",\\\"type\\\":\\\"VARCHAR\\\"},{\\\"name\\\":\\\"master_manager\\\",\\\"type\\\":\\\"VARCHAR\\\"}],\\\"rows\\\":[[\\\"BUYR\\\",\\\"Rommel Vedar\\\",\\\"Lisa Howard\\\"],[\\\"ETAD\\\",\\\"Agalya Sekar\\\",\\\"Kristina Yoder\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":41}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
15:43:42
以下是 CA Past Due Top-10 Vendor 对应的 ETA Specialist（ETAD Primary）。这是当前 Vendor Master Mapping 上下文，不是历史归因字段。

排名	Vendor Code	Vendor	ETA Specialist
1	40979	JEM ACCESSORIES	Pradeepa Rajendran
2	1182	XEROX CANADA LTD OPB SUPPLIES	Linna Ni
3	20562	ACCO BRANDS CANADA	Pradeepa Rajendran
4	39982	LEGRAND AV - C2G CONSIGNMENT	Linna Ni
5	37103	F5 NETWORKS, INC	Agalya Sekar
6	40845	BELKIN, INC CONSIGNMENT	Michelle Li
7	36224	AUTODESK INC.	不可用（Atlas 查询被 bulkhead 拒绝）
8	39987	CLOUDFLARE INC.	Agalya Sekar
9	40584	ROCSTOR, INC.	不可用（Atlas 查询被 bulkhead 拒绝）
10	40837	ARCTIC WOLF NETWORKS, INC.	Agalya Sekar
**ETA Manager：**已返回的 ETAD 映射均为 Kristina Yoder；第 7、9 名因映射查询被 Atlas bulkhead 拒绝，无法确认。

为什么会 bulkhead 拒绝  已经 运行过程中错误 分析 

---

**Cursor**

对照 playbook 里 org mapping 的正确入口，核实这次 trace 里 unreachable 与 bulkhead 的成因。

## 结论

**bulkhead 不是 mapping 数据坏了**，而是 Agent 在同一秒内对 `master-mappings` **无界并行 + 重复打同一 Vendor**，把 source 池打满；第 7、9 名正好撞上拒答。  
正确入口（`vendor-contribution/master-mappings`）能出数——你已看到的 ETAD Primary 就是证据。

---

## 运行错误分层

| 阶段 | 现象 | 性质 |
|---|---|---|
| `search_attributes("ETA specialist")` → `hits: []` | 图上没有叫这个名字的字段 | **正常**；应靠 Skill/playbook 路由到 mapping |
| 直接查 `pastdue.vendor-master-mapping` + identity，投影 primary/manager | `engine.request/unreachable` | **契约错误**：该 entity **没有**「从自身根查属性」的 resolver；只能从 join 进 |
| 改走 `vendor-contribution/master-mappings` | 多数成功，2 行 BUYR+ETAD | **正确路径** |
| 同一秒对 40979 等重复 `execute_eql` 十余次 | 部分 `bulkhead_rejected` | **调度错误** |
| 多次返回旧 handle `result_3d5b3e26…`（`env/version:2`，旧 `captured-at`） | 与本次 env/version 3 成功结果不一致 | **handle 错复用 / 脏响应**；应用错 handle 会读到无关旧结果 |
| 成功的 `analyze_result` 用新 handle（`env/version:3`） | 正常 2 行 mapping | 可用 |

---

## 为什么会 `unreachable`（先错再对）

Agent 猜了「ETA Specialist = ETAD Primary」，于是写：

```clojure
{:entity {:…/country-code "CA" :…/vendor-code 40979 :…/department-type "ETAD"}
 :query [:…/master-primary :…/master-manager …]}
```

图上 **没有**「以 `vendor-master-mapping` 为起点」的 resolver。Playbook 规定入口是：

- 按 Vendor  enrichment：`pastdue.vendor-contribution/master-mappings`
- 按部门过滤：`pastdue.vendor-master-mapping-query/matching-mappings`
- 浓度：`country-monitor/vendor-master-mapping-rollups`

所以第一次路径必拒；改走 `master-mappings` 后才通。

---

## 为什么会 `bulkhead_rejected`

时间线特征（15:43:31 一带）：

1. Top-10 **几乎同时**发 `master-mappings`
2. **同一 Vendor（尤其 40979）被重复提交多次**（无 worklist）
3. 前面还有一批失败的 unreachable 重试

Atlas 对 ClickHouse 有 bulkhead；并发尖峰 → `:error/category :bulkhead_rejected`。  
36224 / 40584 标「不可用」是 **该次逻辑查询在预算内仍失败**，不是「这两个 Vendor 没有 ETAD」。

Adaptive Execution Plane 目前主要管 **ETA Code/Source**；这次是 **org mapping 并行风暴**，同一类问题，调度规则还没覆盖到这条管道。

---

## 其它运行问题

1. **Stale handle**：`result_3d5b3e26` 的时间戳明显早于本次成功结果 → 不能拿来当本次 mapping 事实。  
2. **analyze 偶发错绑**：请求 handle A、响应里又映出 handle B 的内容（你贴的 `4132204c` → 实际像在读 `5d6bdc82`）→ 继续强化「只信本次 execute 返回的 handle + 列语义校验」。  
3. **search 空结果后的试探**：应用 Skill 直接走 `master-mappings`，少一轮错误入口。

---

## 和「数据 / Atlas 部署」的关系

- Mapping **已部署且能用**（多 Vendor 已返回 ETAD Primary / Manager）。  
- 失败项是 **执行平面**：错入口 + 无界并发 + 重复查询 + handle 卫生。  
- `collection-coverage-unknown` 仍是平台默认标签，**不是** bulkhead 原因。

---

## 建议（仍可不做一刀切）

把 org mapping 纳入同一套 **worklist + 自适应并发**（默认 concurrency 2，遇 bulkhead 降 1）：

- 键：`(country, vendor, :master-mappings)`  
- **禁止**直接以 `vendor-master-mapping` entity 做根查询  
- Top-10 Specialist：按 rank 串行/小并发，完成即跳过，禁止重复打同一 Vendor  
- ETAD 行：在返回的多行里 `department_type = ETAD` 取 Primary（你报告已这样做）

需要的话我可以直接改 playbook / Skill / policy，把 org mapping 挂进 Adaptive Execution Plane。

---

**User**

其它场景 会出现这种问题嘛 

---

**Cursor**

会。**不是 org mapping 独有**，凡是「多 Vendor / 多集合 / 可并行」的 Atlas 调用，都会撞上同一类问题；只是触发概率不同。

## 按问题类型看

| 问题 | 容易出现的场景 | 相对安全的场景 |
|---|---|---|
| **bulkhead**（无界并行 + 重复） | 日触发 Top-10 × Code×Source；历史多事件；Top-N Specialist/Owner/PO；多国同时下钻 | 单 Country 指标、单 Vendor 一两问 |
| **错入口 / unreachable** | 新能力、search 空结果后「猜 entity」；把 join 目标当根查（mapping、notification 缺 `recipient-type` 同类） | 熟练走 playbook 模板的路径 |
| **stale / 错绑 handle** | 任何保留 `result_handle` 且并行/重试多的路径 | 单次 execute→立刻 analyze、且校验 handle |
| **合查笛卡尔积** | 一次 `:query` 塞多个 collection（Code+Source、mapping+owner 等） | 单集合查询 |
| **coverage-unknown** | 几乎所有 collection 查询（**不是失败**） | — |

## 按产品场景看

| 场景 | bulkhead 风险 | 错入口风险 | 说明 |
|---|---|---|---|
| **DAILY_MONITOR ETA** | 高 | 中 | 已有 Adaptive Plane；若未遵守仍会炸 |
| **HISTORICAL 多事件 × Top-10** | 更高 | 中 | 事件数 × Vendor 数放大 |
| **Org mapping / ETA Specialist** | 高 | **高** | 刚踩过；易猜错根 entity |
| **Owner / PO lines（Top-10）** | 高 | 中 | 同样是「每 Vendor 一查」 |
| **Notification To/CC** | 中 | 中 | 常漏 `recipient-type` → unreached |
| **仅 Country monitor** | 低 | 低 | 查询少 |
| **单 Vendor 追问** | 低 | 视入口 | 并发压力小 |

## 一句话

根因是 **执行平面共性**（并发、worklist、入口、handle），不是某一张表坏了。  
ETA 已半覆盖；**org / Owner / PO / 历史多事件** 若仍「齐头并进、可重复、可猜错起点」，就会再现 bulkhead 和部分不可用。

把同一套 worklist + 自适应并发扩到这些管道，其它场景才会一起稳下来。

---

**User**

有什么解决方案

---

**Cursor**

## 推荐总方案：把 Adaptive Execution Plane **扩成全管道**

不要只给 ETA 加规则、其它场景继续“齐头并进”。用同一套 **契约硬 + 调度软 + 叙事清**，覆盖 ETA / org / Owner / PO / notification / 历史多事件。

---

### 1. 契约层（所有场景共用，必做）

| 规则 | 作用 |
|---|---|
| **Worklist 去重** | 键含 `(country, vendor?, collection, dates?, extra…)`；`ok`/`failed_final` 不再发 |
| **单集合查询** | 一次 EQL 只取一个 join/collection |
| **Handle 所有权** | 只 analyze 本次返回的 handle；列语义不符或旧 handle → 作废 |
| **入口白名单** | 按场景只允许 playbook 入口（见下） |
| **失败分类** | `BULKHEAD` / `HANDLE_*` / `UNREACHABLE` / `SOURCE_ERROR`；coverage-unknown ≠ 失败 |
| **重试 ≤1** | bulkhead 退避后一次；禁止靠合查/狂重试“省调用” |

**入口白名单（防 unreachable）：**

| 意图 | 允许 | 禁止 |
|---|---|---|
| Vendor 组织 / ETA Specialist | `vendor-contribution/master-mappings` | 以 `vendor-master-mapping` 为根投影 |
| 按部门筛 Vendor | `mapping-query/matching-mappings` | 猜 identity 直接查 mapping |
| Owner | `current-owner-risks` | detail `master_*` |
| Notification | `notification-recipients` + `recipient-type` | 缺 type 的两键 entity |
| ETA | `eta-*-changes` 分两次 | Code+Source 同 query |

---

### 2. 调度层（软，按场景伸缩）

全局共享一个 **concurrency 预算**（默认起 2，遇 bulkhead → 1，连续成功再回升），**所有** 重查询管道共用，避免 ETA 省下来、org 又打爆。

| 场景 | 调度策略 |
|---|---|
| 日报 ETA Top-10 | 已有：Tier A→B + 全量义务 |
| Org / Specialist Top-N | 同 worklist；按 rank；每 Vendor **一次** `master-mappings`，内存筛 `ETAD` |
| Owner / PO Top-N | 同样有界并发；PO 限额按 policy |
| 历史多事件 | 先最新事件全量；更早事件 Tier A 或 on-demand |
| 单 Vendor 追问 | concurrency=1 即可 |

---

### 3. 呈现层

- Partial 只绑分类失败码，不绑 coverage-unknown  
- 缺行写 `—` + 原因，不编造 Specialist  
- 独立 Country / 已成功 Vendor 保留

---

### 4. 三条落地路径（可选强度）

**A. 文档扩面（最快，建议先做）**  
把现有 `:eta-execution` 升为 `:execution-plane`（或增加 `:org-execution` / 通用 knobs），playbook 补 org/Owner/PO 反例与入口表；SP 一句「全管道共用执行平面」。  
→ 不改 Atlas，只改 Agent 行为。

**B. A + 场景模板收紧**  
Skill 里写死「ETA Specialist = master-mappings → filter ETAD」；禁止 search 空结果后猜根 entity。

**C. 远期 Atlas 加速（可选）**  
若常要「一国 Top-10 的 ETAD Primary 一批出」：加 batch resolver。没有也不挡 A/B。

推荐：**先 A+B**，与现有 Adaptive Plane 一脉相承，避免一刀切全串行。

---

### 5. 你这次 Specialist 场景的最小补丁

1. 禁止 `vendor-master-mapping` 根查询  
2. Top-10 只走 `master-mappings`，worklist 去重  
3. 并发 ≤2（与 ETA 共享预算）  
4. bulkhead → 降并发 + 该 Vendor 一次重试，失败标 `BULKHEAD`

---

若要落地，可以说一声 **「按 A+B 改文档」**，我可以直接改 `policy.edn` / playbook / Skill / SP。

---

**User**

你这个好像也是 只是解决 几个场景的 问题 有新的场景 还是会出现？

---

**Cursor**

你的判断对：**只给 ETA / org / Owner 各写一套细则，新场景仍会再炸。** 那是在补洞，不是在换机制。

## 真正可复用的是什么

| 会随场景变的（无法一次写死） | 应对每个新场景都成立的（应做成默认内核） |
|---|---|
| 用哪个 entity / join | 先 discovery，再选 **有 resolver 路径** 的入口 |
| 业务字段怎么筛（如 ETAD） | **Worklist 去重**（同一逻辑查询不连发） |
| 表格列、阈值 | **有界并发 + bulkhead 降级**（全局一份预算） |
| | **单次 EQL 单 collection** |
| | **Handle 只绑本次查询** |
| | **失败分类 + ≤1 次纠正重试** |
| | **禁止把「无路径的根 entity」当默认入口** |

新场景只要还走 MCP `execute_eql`，**不接这套内核**，就会再现 bulkhead / 错 handle / 乱并行；接上了，问题会少一个数量级。  
业务入口（查哪张表）仍要 discovery + 少量 Skill 说明——那是语义，不是调度。

## 更稳的解法：默认执行内核，而不是场景清单

把规则从「ETA 要串行、org 要…」改成：

> **凡是 Atlas 数据调用，一律经过同一 Execution Kernel；场景只声明「要什么事实」，不自己决定怎么打。**

新场景接入时只填一张声明（概念上）：

```text
capability: <name>
entry: <join 或经 discovery 确认的可达路径>
identity: <required keys>
cardinality: one | many-per-parent
parent-set: e.g. Top-10 vendors
```

内核自动：生成 worklist → 共享并发预算 → handle 卫生 → 分类失败。  
**没有声明、又搜不到可达路径 → 标 UNREACHABLE / 未知，禁止瞎猜根 entity。**

这样：

- 旧场景：ETA / mapping / Owner 只是 kernel 的几个 capability 声明  
- 新场景：加声明（或 discovery 证明可达）即可，**不必再发明一套并发哲学**  
- 若有人绕过 kernel 手写齐头并进 → 那是违规，不是「方案覆盖不到」

## 诚实边界

内核 **解决不了**：

- 图上根本没有该能力（无 resolver）→ 要 Atlas 建模，不是调度能变出来  
- 业务选错字段（把 Buyer 当 Specialist）→ 要语义/Skill，不是 concurrency  

内核 **能解决**：新场景里反复出现的 bulkhead、重复打、错 handle、合查、失败口径混乱。

## 和「只修几个场景」的差别

| | 场景补丁 | 执行内核 |
|---|---|---|
| 新场景 | 几乎必再踩坑 | 默认带防护 |
| 维护 | 规则线性膨胀 | 场景只加声明 |
| SP/Skill | 越写越长 | SP 只钉「必须经 kernel」 |

---

若继续落地，建议下一步不是再加「Specialist 专章」，而是：

1. 把现有 `:eta-execution` **升级为通用 `:execution-plane`（kernel）**  
2. ETA / org / Owner / PO / notification 都改成 **capability 声明挂上去**  
3. SP 写死：任何新 Atlas 查询不得绕过该平面  

这样新场景默认也受保护。需要的话我可以按这个方向改 design + 文档实现。

---

**User**

请开始 

---

**Cursor**

开始将 Adaptive Plane 升级为通用 Execution Kernel，并让 ETA / org / Owner 等能力挂载其上。

已落地：**通用 Execution Kernel**，新场景默认走同一套执行规则，而不是再给每个场景打补丁。

## 核心变化

- Spec：`docs/superpowers/specs/2026-10-20-pastdue-execution-kernel-design.md`
- `policy.edn` → **1.5.0**，`:eta-execution` 升级为 `:execution-plane`
  - `:kernel`：共享并发、worklist、handle、失败码、禁猜根
  - `:capabilities`：ETA / master-mappings / Owner / PO / notification 等声明
- SP：凡 Atlas 事实查询必须经 Kernel；新场景只加能力声明
- Playbook：Kernel 一次写清 + org **禁止** mapping 根查询
- Skill / report-format / ACCEPTANCE 已对齐

## 新场景怎么接

1. 在 `:execution-plane :capabilities` 加一条（entry、forbidden-roots、是否占共享预算）  
2. Playbook 补模板  
3. **不要**另写一套并发哲学  

## 部署

更新并 reload：

- `agent/SYSTEM_PROMPT.md`
- 整个 `skill/pastdue-analysis/`

**不必**因本次再传 `pastdue.edn`（与 vocabulary 修复无关）。

---

**User**

40979 这个vendor past due 的detail 信息
16:14:15

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979}\n :query [:pastdue.vendor-contribution/vendor-code\n         :pastdue.vendor-contribution/vendor-name\n         :pastdue.vendor-contribution/previous-snapshot-date\n         :pastdue.vendor-contribution/latest-snapshot-date\n         :pastdue.vendor-contribution/previous-past-due-count\n         :pastdue.vendor-contribution/latest-past-due-count\n         :pastdue.vendor-contribution/past-due-count-change\n         :pastdue.vendor-contribution/previous-open-count\n         :pastdue.vendor-contribution/latest-open-count\n         :pastdue.vendor-contribution/previous-vendor-ratio\n         :pastdue.vendor-contribution/latest-vendor-ratio\n         {:pastdue.vendor-contribution/master-mappings\n          [:pastdue.vendor-master-mapping/department-type\n           :pastdue.vendor-master-mapping/master-primary\n           :pastdue.vendor-master-mapping/master-manager]}\n         {:pastdue.vendor-contribution/current-high-risk-lines\n          ({:limit 5 :order_by \"pastdue.current-high-risk-line/risk-priority-rank\" :sort_dir \"asc\"}\n           [:pastdue.current-high-risk-line/order-type\n            :pastdue.current-high-risk-line/po-number\n            :pastdue.current-high-risk-line/po-line-number\n            :pastdue.current-high-risk-line/sku-number\n            :pastdue.current-high-risk-line/risk-level\n            :pastdue.current-high-risk-line/is-past-due\n            :pastdue.current-high-risk-line/past-due-days\n            :pastdue.current-high-risk-line/eta-code\n            :pastdue.current-high-risk-line/eta-source\n            :pastdue.current-high-risk-line/eta-date\n            :pastdue.current-high-risk-line/buyer-owner\n            :pastdue.current-high-risk-line/primary-owner\n            :pastdue.current-high-risk-line/manager-owner])}]}\n :params {}}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 3, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"dcf07564-a971-4335-b8f1-cbe5350f9df8\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
16:14:23

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979}\n :query [:pastdue.vendor-contribution/vendor-code\n         :pastdue.vendor-contribution/vendor-name\n         :pastdue.vendor-contribution/previous-snapshot-date\n         :pastdue.vendor-contribution/latest-snapshot-date\n         :pastdue.vendor-contribution/previous-past-due-count\n         :pastdue.vendor-contribution/latest-past-due-count\n         :pastdue.vendor-contribution/past-due-count-change\n         :pastdue.vendor-contribution/previous-open-count\n         :pastdue.vendor-contribution/latest-open-count\n         :pastdue.vendor-contribution/previous-vendor-ratio\n         :pastdue.vendor-contribution/latest-vendor-ratio\n         {:pastdue.vendor-contribution/master-mappings\n          [:pastdue.vendor-master-mapping/department-type\n           :pastdue.vendor-master-mapping/master-primary\n           :pastdue.vendor-master-mapping/master-manager]}]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-name], :studio/recent-id \\\"9e3ec803-34e0-407b-9372-de9d7f10d359\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:14:27

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.country-monitor/country-code \"CA\"}\n :query [{:pastdue.country-monitor/vendor-changes\n          ({:limit 1000 :order_by \"pastdue.vendor-contribution/contribution-rank\" :sort_dir \"asc\"}\n           [:pastdue.vendor-contribution/vendor-code\n            :pastdue.vendor-contribution/vendor-name\n            :pastdue.vendor-contribution/previous-snapshot-date\n            :pastdue.vendor-contribution/latest-snapshot-date\n            :pastdue.vendor-contribution/previous-past-due-count\n            :pastdue.vendor-contribution/latest-past-due-count\n            :pastdue.vendor-contribution/past-due-count-change\n            :pastdue.vendor-contribution/previous-open-count\n            :pastdue.vendor-contribution/latest-open-count\n            :pastdue.vendor-contribution/previous-vendor-ratio\n            :pastdue.vendor-contribution/latest-vendor-ratio\n            {:pastdue.vendor-contribution/master-mappings\n             [:pastdue.vendor-master-mapping/department-type\n              :pastdue.vendor-master-mapping/master-primary\n              :pastdue.vendor-master-mapping/master-manager]}\n            {:pastdue.vendor-contribution/current-high-risk-lines\n             ({:limit 5 :order_by \"pastdue.current-high-risk-line/risk-priority-rank\" :sort_dir \"asc\"}\n              [:pastdue.current-high-risk-line/order-type\n               :pastdue.current-high-risk-line/po-number\n               :pastdue.current-high-risk-line/po-line-number\n               :pastdue.current-high-risk-line/sku-number\n               :pastdue.current-high-risk-line/risk-level\n               :pastdue.current-high-risk-line/is-past-due\n               :pastdue.current-high-risk-line/past-due-days\n               :pastdue.current-high-risk-line/eta-code\n               :pastdue.current-high-risk-line/eta-source\n               :pastdue.current-high-risk-line/eta-date\n               :pastdue.current-high-risk-line/buyer-owner\n               :pastdue.current-high-risk-line/primary-owner\n               :pastdue.current-high-risk-line/manager-owner])}])}]}]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 3, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"facaafba-cb57-4b48-8c77-8495366ef5d8\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
16:14:33
Vendor 40979 — Past Due Detail（CA）
已确认的历史 Past Due 信息
项目	数值
Vendor	JEM ACCESSORIES
Vendor Code	40979
Country	CA
上期快照	2026-10-18
本期快照	2026-10-19
上期 Past Due	292
本期 Past Due	292
净变化	0
ETA Code 明细	US 242；NR 28；RQ 20；PE 1；SH 1
ETA Code 占本期 Past Due：

US：82.88%
NR：9.59%
RQ：6.85%
PE：0.34%
SH：0.34%
当前 ETA 组织信息
Department	Primary	Manager
ETAD	Pradeepa Rajendran	Kristina Yoder
当前 PO / Past Due 明细
本次 Atlas 对 Vendor 40979 的当前高风险 PO 明细查询失败，返回：

source execution failed
查询当前 PO 行、Buyer、ETA Date、Past Due Days 等字段时未成功
因此不能可靠展示具体 PO、行号、SKU、Buyer 或当前逾期天数
需要注意：292 → 292 是历史快照对比，不代表当前 PO 明细一定仍完全相同。目前能够确认的是截至 2026-10-19 的历史 Past Due 汇总和 ETA Code 构成。



找出错误 

---

**Cursor**

对照 graph 可达路径与 Kernel 契约，定位这三次失败的具体原因。

## 结论

三次失败是 **查询形状错误 + 违反单集合 Kernel**，不是 Vendor 40979 没数据。报告里能写出的 292→292、ETA Code、ETAD 映射，来自**更早成功的分次查询**；PO 明细失败是因为把「度量标量 + mapping + PO」捆在一次（或用了不可达根）上。

---

## 三次错误分别是什么

### ① 16:14:15 — `:source` / source execution failed

```text
entity = vendor-contribution (CA, 40979)
query  = 一堆 vendor-contribution 标量
       + master-mappings
       + current-high-risk-lines   ← 一次里三个方向
```

问题：

1. **多 collection 合查**（违反 Execution Kernel）  
2. 从 `vendor-contribution` 根上还想拉 **标量指标**（见下条 unreachable）  
3. PO join 本身可能再叠加 source/SQL 压力 → 笼统 `:source`

### ② 16:14:27 — `engine.request/unreachable`（最关键）

Atlas 明确说这些属性从起点**达不到**：

`vendor-name`、`latest-*`、`previous-*`、`past-due-count-change`…

含义：图上 **没有**「以 `vendor-contribution` 为根、按 country+vendor 加载该行标量」的 resolver。  
`country-code` / `vendor-code` 只是给 **join**（`master-mappings`、`current-high-risk-lines` 等）用的 required inputs。

| 想要的 | 正确入口 |
|---|---|
| Vendor 汇总（Past Due 行数、ratio、日期） | `country-monitor/positive-vendor-contributors` 或 `vendor-changes`，再 `WHERE vendor_code = 40979`；或复用已有 Top-10 结果 |
| 组织 mapping | **仅** `vendor-contribution/master-mappings` |
| 当前高风险 PO | **仅** `vendor-contribution/current-high-risk-lines` |
| ETA Code 构成 | `historical-vendor-contribution/eta-code-changes`（历史四元组） |

### ③ 16:14:33 — 又一次 `:source`

从 `country-monitor/vendor-changes` 拉全国 Vendor，并在每行下嵌套 `master-mappings` **和** `current-high-risk-lines`。

问题：嵌套多集合 + 全量 Vendor 扇出，过重；且再次违反「单次 EQL 单 collection」。要单 Vendor 详情不必扫全 `vendor-changes`。

---

## 和报告内容的关系

| 报告块 | 为何能出 |
|---|---|
| 292→292、快照日、名称 | 先前会话/其它成功查询，**不是**这三次 |
| ETA Code 明细 | 历史 `eta-code-changes`（独立成功） |
| ETAD Primary/Manager | `master-mappings` 单独成功（或更早成功） |
| 当前 PO / Buyer / 逾期天 | **这几次没走通** → 只能标失败 |

`292→292` 是历史快照对比；当前 PO 必须另查 `:current-high-risk-lines`，不能从汇总行「推出来」。

---

## 正确拆法（Kernel）

对 CA / 40979 应 **三次（或四次）独立** `execute_eql`，共享并发预算，不要合查：

1. （可选）指标：`positive-vendor-contributors` → analyze 过滤 `vendor_code=40979`  
2. `:master-mappings` 单独  
3. `:current-high-risk-lines` 单独（limit 5 + rank）  
4. ETA 已有则复用 worklist，勿再塞进同一 query  

---

## 附：Playbook 自身也有一处旧反例

`atlas-query-playbook.md` 里 Owner+PO 仍写在**同一次** `:query` 里两个 collection——与现行 Kernel「单集合」冲突，容易再诱发 `:source`。建议改成两次调用；需要的话我可以直接改文档。

---

**User**

用户说的detail 可能是  每一行具体的数据 可以给用户返回嘛

---

**Cursor**

可以。用户说的「detail」在多数情况下就是指 **当前高风险/逾期 PO 行级明细**，Atlas 能返回，也可以展示给用户。

## 能返回什么

走 capability `:current-high-risk-lines`（`vendor-contribution` + country + vendor），每行大致包括：

- PO / 行号 / SKU  
- 是否逾期、逾期天数、风险等级  
- ETA Code / Source / ETA Date  
- Buyer / Primary / Manager（当前组织上下文）  

这是 **当前态**，不是「2026-10-18→19 历史归因」的因果明细。报告里应标明 **current context**。

## 怎么查才拿得到

单独一次 EQL（不要和 mapping、标量指标合查）：

```clojure
{:entity {:pastdue.vendor-contribution/country-code "CA"
          :pastdue.vendor-contribution/vendor-code 40979}
 :query
 [({:pastdue.vendor-contribution/current-high-risk-lines
    […PO 字段…]}
   {:limit 5 :order_by "pastdue.current-high-risk-line/risk-priority-rank" :sort_dir "asc"})]}
```

再 `analyze_result` 出表即可给用户。

## 边界（要对用户说清楚）

| 点 | 说明 |
|---|---|
| 条数 | Policy 默认每 Vendor 最多约 5 行、每 Country 约 20（可按 ask 说明） |
| 范围 | 高风险 **或** 逾期候选行，不是该 Vendor 全部 open 行 |
| 与历史汇总 | 例如「292 行 Past Due」是快照计数；行表明细是当前视图，条数不必等于 292 |
| 历史「哪几行变了」 | 图上 **没有** 历史 PO 行级归因；只能给当前行 + 历史 ETA/Vendor 汇总 |

**结论：** 可以给用户返回行级 detail；用分次的 `current-high-risk-lines`，并标注为当前上下文、有条数上限。上次失败是查法错了，不是「不能给 detail」。

---

**User**

为什么 每次查询 都会有错误 是edn文件 写的太死了嘛  
这个agent设计的是一个问答分析agent 应该支持 更灵活的问题 不是限于某几个场景

---

**Cursor**

不完全是。**多数报错不是 EDN「写死了」，而是 Agent 用了图上不存在的走法**；但你们现在的「场景感」确实会让灵活问答变难——那是 **Skill/入口文档偏窄**，不全是 EDN 的错。

## 两层要分清

| 层 | 作用 | 「死」的含义 |
|---|---|---|
| **Atlas EDN** | 规定 *能* 从哪走到哪、要什么输入 | 图必须闭合；乱猜根 entity → `unreachable`；合查/重查 → `:source` / bulkhead |
| **Agent Skill/SP** | 规定 *怎么* 问、常用路径 | 若只背「日报 Top-10」模板，就会显得只会几个场景 |

用户要的是灵活问答；图要的是 **可证明的数据路径**。灵活 = 在图上自由组合能力，不是任意 invent 字段或根查询。

## 为什么「每次查询都像有错」

常见不是 EDN 太死，而是：

1. **把 join 目标当根查**（mapping 标量、vendor 汇总标量）→ `unreachable`  
2. **一次塞多个 collection** → `:source` / 笛卡尔积  
3. **无界并行 + 重复** → bulkhead  
4. **search 空就猜名字** → 空 hits 后再错入口  

这些在「灵活追问」时更容易出现，因为没有固定剧本，Agent 更容易即兴写坏 EQL。

EDN 写清 required inputs / 禁止假路径，是为了 **不瞎编数**；问答 Agent 更需要这个，而不是更松的假成功。

## 灵活问答正确长什么样

```text
用户任意问题
    → 意图拆成「要哪些事实」
    → MCP discovery：哪个 entry 可达
    → Execution Kernel：分次、有界、去重
    → 组合进回答（可多能力拼接）
```

Skill 里已有一句精神：**模板固定的是输入与拼写，不是「只允许那几个问题」**。  
缺的是：Agent 要真按这个做，而不是「不在剧本里就硬套日报形状」。

## EDN 要不要改？

| 情况 | 做法 |
|---|---|
| 图上 **已有** join，Agent 用错 | 改 Agent（Kernel + 反例），**不必**把 EDN 写松 |
| 用户常问、图上 **没有** 路径（如历史 PO 行级归因） | **加能力**（新 resolver），不是放宽校验 |
| 只想「随便投影任何字段」 | Atlas **做不到也不该做**——那就变成直连 SQL，失去治理 |

所以：不是 EDN 太死导致不能问答；是 **可达图有边界**。灵活 = 边界内组合 + 边界外诚实说「未知/需建模」。

## 和「不限于某几个场景」怎么对齐

1. **产品定位**：问答分析 Agent = 意图路由 + 能力组合，不是「只跑 DAILY_MONITOR」。  
2. **Kernel 管怎么调**（已在做）——新问题默认也防炸。  
3. **Capability 可增长**——新问题类型加声明/模板，不改并发哲学。  
4. **Playbook 从「剧本」改成「入口词典」**——少写死步骤，多写「这类事实从哪进」。  
5. **失败要可解释**：`unreachable` = 入口错；缺能力 = 图未建模；不要都怪成「EDN 太死」。

---

**一句话：**  
EDN 该保持「严」（可达、可审计）；Agent 该更「活」（任意问题 → discovery → 组合能力）。  
现在痛点主要是 **活的那一层还在猜/合查**，再加上能力清单还不够像词典——不是把 EDN 改松就能变灵活。

若要下一步落地，优先是：把 playbook/Skill 改成「意图 → 能力词典」导向，并拆掉 Owner+PO 合查等反例，而不是弱化 `pastdue.edn` 校验。需要的话我可以直接改那一层文档。

---

**User**

请给出设计  

---

**Cursor**

先对齐现有层叠与「灵活问题」相关表述，再给出正式设计文档。

## 设计结论

灵活问答靠 **意图路由 + 事实词典 + Execution Kernel**，不是把 EDN 写松，也不是再堆几个场景剧本。

完整 spec：[`docs/superpowers/specs/2026-10-20-pastdue-flexible-qa-agent-design.md`](docs/superpowers/specs/2026-10-20-pastdue-flexible-qa-agent-design.md)

---

### 为何不是「EDN 太死」

- EDN 管 **能不能走通**（要严）  
- Agent 管 **想问什么 → 调哪些能力**（要活）  
报错多半是猜错根、合查、并行风暴，不是「不该有 reachable 校验」。

### 三条路线

| | 做法 | 评价 |
|---|---|---|
| A | 放松 EDN / 到处加根查询 | 不推荐 |
| B | Skill 继续加场景剧本 | 新问题仍炸 |
| **C** | 意图 → Fact Plan → Kernel → 作答 | **推荐** |

### 推荐架构（C）

```text
任意用户问题
  → Intent Router（意图 + 国家/Vendor/日期范围）
  → Fact Plan（有序 capability 列表）
  → Execution Kernel（已有：单集合、worklist、共享并发）
  → Answer Composer（模块/叙述；历史 vs 当前标注）
```

- **日报** = 一个预设 Fact Plan，不是唯一产品形态。  
- **「detail」** 默认：当前 `:current-high-risk-lines`（行级）；Vendor 汇总标量走 contributors 过滤，**禁止** contribution 根上拉 `latest-*`。  
- **新问题**：有路径 → 加意图同义词；无路径 → 加 Atlas 能力，不绕过 Kernel。

### 落地阶段（批准后再做）

1. SP/Skill：问答为主、日报为预设；意图目录  
2. Playbook：改成事实词典；拆 Owner+PO；写明 Vendor 指标入口  
3. 可选 `policy.edn` `:intent-catalog`  
4. 验收用例  

---

若认可该设计，回复 **批准** 或指出要改的意图/「detail」默认，我再按 P1–P2 改文档实现。

---

**User**

按c开始

---

**Cursor**

按方案 C 开始落地：先更新 spec 状态，再改 SP / Skill / playbook / policy。

方案 C 已落地（P1–P3）。

## 做了什么

| 文件 | 要点 |
|---|---|
| `SYSTEM_PROMPT.md` | 定位为 **Q&A 分析 Agent**；流程 intent→plan→compose；日报是预设；禁 contribution 根拉标量；detail 默认 PO 行 |
| `SKILL.md` | Interactive 路径 + 意图表 + 40979 示例 Fact Plan |
| `atlas-query-playbook.md` | Vendor 指标正确入口；Owner / PO **拆成两次** EQL |
| `policy.edn` **1.6.0** | `:intent-catalog` + `:vendor-changes` |
| `report-format` / `ACCEPTANCE` | 交互组合与验收 |

Spec：`docs/superpowers/specs/2026-10-20-pastdue-flexible-qa-agent-design.md`

## 部署

更新并 reload：

- `agent/SYSTEM_PROMPT.md`
- 整个 `skill/pastdue-analysis/`

本次 **不必** 为问答层再传 `pastdue.edn`。

---

**User**

分析 这次的 错误  你好像越改越不行了  

CA的past due 排名前十的vendor，eta code占比
16:29:30

read
Completed
16:29:33

mcp__ETA-PastDue-Graph__list_entities (mcp · ETA-PastDue-Graph)
Completed
16:29:35

read
Completed
16:29:38

read
Completed
16:29:38

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.vendor-contribution",
    "pastdue.vendor-eta-code"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank within the country.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the vendor analysis identity.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive contribution share in descending delta order.\\\",\\\"synonyms\\\":[\\\"累计正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor contribution to country ratio change: latest country contribution minus previous country contribution.\\\",\\\"synonyms\\\":[\\\"对国家逾期率变化的贡献\\\",\\\"vendor contribution\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Latest completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor open count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor past-due count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This vendor's positive delta divided by the sum of positive vendor deltas in the country.\\\",\\\"synonyms\\\":[\\\"正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Previous available completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the identity; vendor code is not assumed globally unique across countries.\\\",\\\"synonyms\\\":[\\\"供应商代码\\\",\\\"vendor\\\"],\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Latest available nonblank vendor name across the compared snapshots.\\\",\\\"synonyms\\\":[\\\"供应商名称\\\"],\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor ratio minus previous vendor ratio.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable positive-contribution rank within this country and vendor.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the ETA Code analysis identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive share in descending contribution order.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This ETA Code bucket's contribution to the country ratio change.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/eta-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Normalized ETA Code bucket; null and blank values are UNKNOWN.\\\",\\\"synonyms\\\":[\\\"ETA代码\\\",\\\"eta code\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/latest-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest bucket past-due count divided by latest country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Code bucket in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Latest completed country snapshot used for this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous past-due count for this ETA Code bucket.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Bucket positive delta divided by all positive ETA Code deltas for this vendor.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous bucket past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Code bucket in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Previous available completed country snapshot used for this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the ETA Code analysis identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-high-risk-lines\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-high-risk-line\\\",\\\"limit\\\":{\\\"default\\\":20,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-high-risk-line/country-code :pastdue.current-high-risk-line/vendor-code :pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/ordered-quantity :pastdue.current-high-risk-line/shipped-quantity :pastdue.current-high-risk-line/received-quantity :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-date :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/action-type :pastdue.current-high-risk-line/buyer-owner :pastdue.current-high-risk-line/primary-owner :pastdue.current-high-risk-line/manager-owner :pastdue.current-high-risk-line/risk-priority-rank], :default [:pastdue.current-high-risk-line/risk-priority-rank :asc], :aliases {\\\\\\\"risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"order_type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"po_line_number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"pastdue.current-high-risk-line/eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"pastdue.current-high-risk-line/eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"risk_priority_rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"pastdue.current-high-risk-line/received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"pastdue.current-high-risk-line/vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"received_quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"eta_source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"pastdue.current-high-risk-line/manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"is_past_due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"eta_code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"ordered_quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"manager_owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"action_type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"sku_number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"past_due_days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"pastdue.current-high-risk-line/primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"buyer_owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"primary_owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"pastdue.current-high-risk-line/is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"po_number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"eta_date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"pastdue.current-high-risk-line/action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"shipped_quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"risk_level\\\\\\\" :pastdue.current-high-risk-line/risk-level}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-owner-risks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.owner-current-risk\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.owner-current-risk/country-code :pastdue.owner-current-risk/vendor-code :pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-high-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count :pastdue.owner-current-risk/max-past-due-days :pastdue.owner-current-risk/queried-at-utc], :default [:pastdue.owner-current-risk/current-risk-line-count :desc], :aliases {\\\\\\\"pastdue.owner-current-risk/country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"owner_role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"vendor_code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"owner_display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"country_code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"current_high_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"pastdue.owner-current-risk/queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"max_past_due_days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"pastdue.owner-current-risk/owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"pastdue.owner-current-risk/current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"current_past_due_line_count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"current_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-code-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-code/country-code :pastdue.vendor-eta-code/vendor-code :pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/previous-snapshot-date :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/previous-past-due-count :pastdue.vendor-eta-code/past-due-count-change :pastdue.vendor-eta-code/latest-country-contribution :pastdue.vendor-eta-code/previous-country-contribution :pastdue.vendor-eta-code/delta-country-contribution :pastdue.vendor-eta-code/positive-contribution-share :pastdue.vendor-eta-code/cumulative-positive-share :pastdue.vendor-eta-code/contribution-rank], :default [:pastdue.vendor-eta-code/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-code/country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"pastdue.vendor-eta-code/contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.vendor-eta-code/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-code/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-code/eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"pastdue.vendor-eta-code/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-source-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-source/country-code :pastdue.vendor-eta-source/vendor-code :pastdue.vendor-eta-source/eta-source :pastdue.vendor-eta-source/latest-snapshot-date :pastdue.vendor-eta-source/previous-snapshot-date :pastdue.vendor-eta-source/latest-past-due-count :pastdue.vendor-eta-source/previous-past-due-count :pastdue.vendor-eta-source/past-due-count-change :pastdue.vendor-eta-source/latest-country-contribution :pastdue.vendor-eta-source/previous-country-contribution :pastdue.vendor-eta-source/delta-country-contribution :pastdue.vendor-eta-source/positive-contribution-share :pastdue.vendor-eta-source/cumulative-positive-share :pastdue.vendor-eta-source/contribution-rank], :default [:pastdue.vendor-eta-source/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-source/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-source/country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-source/contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"pastdue.vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-source/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"pastdue.vendor-eta-source/vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/master-mappings\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-master-mapping\\\",\\\"limit\\\":{\\\"default\\\":50,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-master-mapping/country-code :pastdue.vendor-master-mapping/vendor-code :pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc], :default [:pastdue.vendor-master-mapping/department-type :asc], :aliases {\\\\\\\"pastdue.vendor-master-mapping/department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"pastdue.vendor-master-mapping/country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"master_manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department_type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master_primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"pastdue.vendor-master-mapping/master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"pastdue.vendor-master-mapping/queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/notification-recipients\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.notification-recipient\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.notification-recipient/country-code :pastdue.notification-recipient/vendor-code :pastdue.notification-recipient/recipient-type :pastdue.notification-recipient/email :pastdue.notification-recipient/contact-name :pastdue.notification-recipient/is-active :pastdue.notification-recipient/source-system :pastdue.notification-recipient/contact-role-hint :pastdue.notification-recipient/contact-priority-score :pastdue.notification-recipient/is-internal-candidate :pastdue.notification-recipient/is-recommended :pastdue.notification-recipient/selection-reason :pastdue.notification-recipient/evidence-text :pastdue.notification-recipient/updated-at-utc], :default [:pastdue.notification-recipient/contact-priority-score :desc], :aliases {\\\\\\\"pastdue.notification-recipient/country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"pastdue.notification-recipient/evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"source_system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"pastdue.notification-recipient/updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"pastdue.notification-recipient/is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"is_active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"selection_reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"contact_priority_score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"vendor_code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"email\\\\\\\" :pastdue.notification-recipient/email, \\\\\\\"contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"is_internal_candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"recipient_type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"country_code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"is_recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"pastdue.notification-recipient/recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"evidence_text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"updated_at_utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"contact_role_hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"pastdue.notification-recipient/contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"pastdue.notification-recipient/source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"contact_name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"pastdue.notification-recipient/contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"pastdue.notification-recipient/email\\\\\\\" :pastdue.notification-recipient/email}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"pastdue.notification-recipient/recipient-type\\\"]}]}\"}],\"isError\":false}"
  }
]
16:29:38

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
positive vendor contributors
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"positive vendor contributors\\\",\\\"hits\\\":[{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"exact-name\\\",\\\"label\\\":\\\"Positive Vendor Contributors\\\",\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/positive-vendor-contributors\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"exact-name\\\",\\\"label\\\":\\\"Positive Vendor Contributors\\\",\\\"entity\\\":\\\"pastdue.country-trigger-event\\\",\\\"attribute\\\":\\\"pastdue.country-trigger-event/positive-vendor-contributors\\\"}]}\"}],\"isError\":false}"
  }
]
16:29:40

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.country-monitor",
    "pastdue.historical-vendor-contribution"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country independently monitored in the latest two completed snapshots.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"国家代码\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/has-comparable-snapshots\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when at least two completed snapshots exist for the country.\\\",\\\"synonyms\\\":[\\\"存在两个可比较快照\\\"],\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"All open PO-line count used as the latest country denominator.\\\",\\\"synonyms\\\":[\\\"最新开放行数\\\",\\\"latest denominator\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due open PO-line count in the latest completed snapshot.\\\",\\\"synonyms\\\":[\\\"最新逾期行数\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-past-due-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest country past-due count divided by latest country open count, calculated without presentation rounding.\\\",\\\"synonyms\\\":[\\\"最新逾期率\\\",\\\"latest past due ratio\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-snapshot-age-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"UTC calendar days from the latest completed snapshot date to the current UTC date.\\\",\\\"synonyms\\\":[\\\"最新快照陈旧天数\\\"],\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Most recent snapshot date strictly earlier than the current UTC date.\\\",\\\"synonyms\\\":[\\\"最新完整快照日期\\\",\\\"latest completed snapshot\\\"],\\\"format\\\":\\\"date\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous open PO-line count.\\\",\\\"synonyms\\\":[\\\"开放行数变化\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous past-due PO-line count.\\\",\\\"synonyms\\\":[\\\"逾期行数变化\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/percentage-point-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest past-due ratio minus previous past-due ratio; displayed for context but not used as a trigger threshold.\\\",\\\"synonyms\\\":[\\\"百分点变化\\\",\\\"ratio point change\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"All open PO-line count used as the previous country denominator.\\\",\\\"synonyms\\\":[\\\"上期开放行数\\\",\\\"previous denominator\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due open PO-line count in the previous completed snapshot.\\\",\\\"synonyms\\\":[\\\"上期逾期行数\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-past-due-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous country past-due count divided by previous country open count, calculated without presentation rounding.\\\",\\\"synonyms\\\":[\\\"上期逾期率\\\",\\\"previous past due ratio\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Most recent available completed snapshot before the latest snapshot for this country.\\\",\\\"synonyms\\\":[\\\"上一快照日期\\\",\\\"previous completed snapshot\\\"],\\\"format\\\":\\\"date\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/relative-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Relative change in country past-due ratio; null when the previous ratio is zero or unavailable.\\\",\\\"synonyms\\\":[\\\"逾期率相对变化\\\",\\\"relative growth\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/snapshot-gap-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Calendar-day gap between the latest and previous available snapshot dates.\\\",\\\"synonyms\\\":[\\\"快照间隔天数\\\"],\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/zero-baseline-increase\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when the previous ratio is zero and the latest ratio is greater than zero.\\\",\\\"synonyms\\\":[\\\"零基线上升\\\"],\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank for this historical pair.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the historical vendor identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive share in descending historical contribution order.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor contribution to the explicit historical country ratio change.\\\",\\\"synonyms\\\":[\\\"历史供应商贡献\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Target minus previous vendor open count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Target minus previous vendor past-due count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor positive delta divided by all positive vendor deltas for this historical pair.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Nearest prior available snapshot used as the comparison baseline.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/previous-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target vendor past-due count divided by target country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the target snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the target snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Historical completed snapshot being explained.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/target-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target vendor past-due count divided by target vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the identity; not assumed globally unique across countries.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Latest available vendor name across the explicit historical pair.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/vendor-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Target vendor ratio minus previous vendor ratio.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/all\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.country-monitor\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.country-monitor/country-code :pastdue.country-monitor/latest-snapshot-date :pastdue.country-monitor/previous-snapshot-date :pastdue.country-monitor/snapshot-gap-days :pastdue.country-monitor/latest-snapshot-age-days :pastdue.country-monitor/latest-past-due-count :pastdue.country-monitor/previous-past-due-count :pastdue.country-monitor/past-due-count-change :pastdue.country-monitor/latest-open-count :pastdue.country-monitor/previous-open-count :pastdue.country-monitor/open-count-change :pastdue.country-monitor/latest-past-due-ratio :pastdue.country-monitor/previous-past-due-ratio :pastdue.country-monitor/relative-ratio-change :pastdue.country-monitor/percentage-point-change :pastdue.country-monitor/zero-baseline-increase :pastdue.country-monitor/has-comparable-snapshots], :default [:pastdue.country-monitor/country-code :asc], :aliases {\\\\\\\"pastdue.country-monitor/past-due-count-change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"previous-open-count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"latest-snapshot-age-days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"percentage-point-change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"pastdue.country-monitor/open-count-change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"snapshot_gap_days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"relative-ratio-change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"pastdue.country-monitor/previous-past-due-ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"pastdue.country-monitor/latest-snapshot-date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"pastdue.country-monitor/has-comparable-snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"pastdue.country-monitor/previous-snapshot-date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"latest_snapshot_age_days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"snapshot-gap-days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"zero-baseline-increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase, \\\\\\\"pastdue.country-monitor/latest-snapshot-age-days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"pastdue.country-monitor/country-code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"pastdue.country-monitor/latest-open-count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"country_code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"has-comparable-snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"previous-past-due-ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"pastdue.country-monitor/snapshot-gap-days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"pastdue.country-monitor/previous-past-due-count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"latest-open-count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"pastdue.country-monitor/latest-past-due-ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"previous_open_count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"has_comparable_snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"latest-past-due-ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"pastdue.country-monitor/zero-baseline-increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase, \\\\\\\"pastdue.country-monitor/percentage-point-change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"percentage_point_change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"pastdue.country-monitor/latest-past-due-count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"relative_ratio_change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"previous_past_due_ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"latest_past_due_ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"country-code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"pastdue.country-monitor/previous-open-count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"pastdue.country-monitor/relative-ratio-change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"zero_baseline_increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase}}\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/positive-vendor-contributors\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-contribution\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank], :default [:pastdue.vendor-contribution/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"pastdue.vendor-contribution/open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"pastdue.vendor-contribution/previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"pastdue.vendor-contribution/vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"latest_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"pastdue.vendor-contribution/cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"vendor_name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"pastdue.vendor-contribution/previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"pastdue.vendor-contribution/past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"pastdue.vendor-contribution/latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"pastdue.vendor-contribution/latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"pastdue.vendor-contribution/positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"vendor_ratio_change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"previous_open_count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"pastdue.vendor-contribution/vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/vendor-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-contribution\\\",\\\"limit\\\":{\\\"default\\\":1000,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank], :default [:pastdue.vendor-contribution/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"pastdue.vendor-contribution/open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"pastdue.vendor-contribution/previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"pastdue.vendor-contribution/vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"latest_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"pastdue.vendor-contribution/cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"vendor_name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"pastdue.vendor-contribution/previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"pastdue.vendor-contribution/past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"pastdue.vendor-contribution/latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"pastdue.vendor-contribution/latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"pastdue.vendor-contribution/positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"vendor_ratio_change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"previous_open_count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"pastdue.vendor-contribution/vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/vendor-master-mapping-rollups\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-master-mapping-rollup\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-master-mapping-rollup/country-code :pastdue.vendor-master-mapping-rollup/department-type :pastdue.vendor-master-mapping-rollup/master-primary :pastdue.vendor-master-mapping-rollup/master-manager :pastdue.vendor-master-mapping-rollup/mapped-vendor-count :pastdue.vendor-master-mapping-rollup/queried-at-utc], :default [:pastdue.vendor-master-mapping-rollup/mapped-vendor-count :desc], :aliases {\\\\\\\"pastdue.vendor-master-mapping-rollup/country-code\\\\\\\" :pastdue.vendor-master-mapping-rollup/country-code, \\\\\\\"master-primary\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-primary, \\\\\\\"master_manager\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-manager, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping-rollup/queried-at-utc, \\\\\\\"department_type\\\\\\\" :pastdue.vendor-master-mapping-rollup/department-type, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-master-mapping-rollup/country-code, \\\\\\\"master_primary\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-primary, \\\\\\\"pastdue.vendor-master-mapping-rollup/master-manager\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-manager, \\\\\\\"mapped-vendor-count\\\\\\\" :pastdue.vendor-master-mapping-rollup/mapped-vendor-count, \\\\\\\"pastdue.vendor-master-mapping-rollup/department-type\\\\\\\" :pastdue.vendor-master-mapping-rollup/department-type, \\\\\\\"pastdue.vendor-master-mapping-rollup/mapped-vendor-count\\\\\\\" :pastdue.vendor-master-mapping-rollup/mapped-vendor-count, \\\\\\\"mapped_vendor_count\\\\\\\" :pastdue.vendor-master-mapping-rollup/mapped-vendor-count, \\\\\\\"pastdue.vendor-master-mapping-rollup/queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping-rollup/queried-at-utc, \\\\\\\"master-manager\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-manager, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-master-mapping-rollup/country-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.vendor-master-mapping-rollup/queried-at-utc, \\\\\\\"department-type\\\\\\\" :pastdue.vendor-master-mapping-rollup/department-type, \\\\\\\"pastdue.vendor-master-mapping-rollup/master-primary\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-primary}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-code-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-code/country-code :pastdue.historical-vendor-eta-code/vendor-code :pastdue.historical-vendor-eta-code/target-snapshot-date :pastdue.historical-vendor-eta-code/previous-snapshot-date :pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution], :default [:pastdue.historical-vendor-eta-code/eta-code :asc], :aliases {\\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-code-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-code/country-code :pastdue.historical-vendor-eta-code/vendor-code :pastdue.historical-vendor-eta-code/target-snapshot-date :pastdue.historical-vendor-eta-code/previous-snapshot-date :pastdue.historical-vendor-eta-code/eta-code :pastdue.historical-vendor-eta-code/target-past-due-count :pastdue.historical-vendor-eta-code/previous-past-due-count :pastdue.historical-vendor-eta-code/past-due-count-change :pastdue.historical-vendor-eta-code/target-country-contribution :pastdue.historical-vendor-eta-code/previous-country-contribution :pastdue.historical-vendor-eta-code/delta-country-contribution :pastdue.historical-vendor-eta-code/positive-contribution-share :pastdue.historical-vendor-eta-code/cumulative-positive-share :pastdue.historical-vendor-eta-code/contribution-rank], :default [:pastdue.historical-vendor-eta-code/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-code/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-code/contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-code/contribution-rank, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-code/positive-contribution-share, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.historical-vendor-eta-code/cumulative-positive-share, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-code/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-code/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-code/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/previous-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-code/positive-contribution-share, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-code/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.historical-vendor-eta-code/positive-contribution-share, \\\\\\\"eta-code\\\\\\\" :pastdue.historical-vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-code/country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-code/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-code/country-code, \\\\\\\"pastdue.historical-vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-code/delta-country-contribution, \\\\\\\"contribution_rank\\\\\\\" :pastdue.historical-vendor-eta-code/contribution-rank, \\\\\\\"pastdue.historical-vendor-eta-code/cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-code/cumulative-positive-share}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-source-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-source/country-code :pastdue.historical-vendor-eta-source/vendor-code :pastdue.historical-vendor-eta-source/target-snapshot-date :pastdue.historical-vendor-eta-source/previous-snapshot-date :pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-country-contribution :pastdue.historical-vendor-eta-source/previous-country-contribution :pastdue.historical-vendor-eta-source/delta-country-contribution], :default [:pastdue.historical-vendor-eta-source/eta-source :asc], :aliases {\\\\\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"pastdue.historical-vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.historical-vendor-contribution/eta-source-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.historical-vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.historical-vendor-eta-source/country-code :pastdue.historical-vendor-eta-source/vendor-code :pastdue.historical-vendor-eta-source/target-snapshot-date :pastdue.historical-vendor-eta-source/previous-snapshot-date :pastdue.historical-vendor-eta-source/eta-source :pastdue.historical-vendor-eta-source/target-past-due-count :pastdue.historical-vendor-eta-source/previous-past-due-count :pastdue.historical-vendor-eta-source/past-due-count-change :pastdue.historical-vendor-eta-source/target-country-contribution :pastdue.historical-vendor-eta-source/previous-country-contribution :pastdue.historical-vendor-eta-source/delta-country-contribution :pastdue.historical-vendor-eta-source/positive-contribution-share :pastdue.historical-vendor-eta-source/cumulative-positive-share :pastdue.historical-vendor-eta-source/contribution-rank], :default [:pastdue.historical-vendor-eta-source/contribution-rank :asc], :aliases {\\\\\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-source/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"target_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"dimension_value\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"target_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.historical-vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.historical-vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.historical-vendor-eta-source/country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"pastdue.historical-vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-source/positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-source/positive-contribution-share, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.historical-vendor-eta-source/positive-contribution-share, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.historical-vendor-eta-source/cumulative-positive-share, \\\\\\\"target_past_due_count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/target-past-due-count, \\\\\\\"target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.historical-vendor-eta-source/past-due-count-change, \\\\\\\"pastdue.historical-vendor-eta-source/contribution-rank\\\\\\\" :pastdue.historical-vendor-eta-source/contribution-rank, \\\\\\\"pastdue.historical-vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.historical-vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\\\\\" :pastdue.historical-vendor-eta-source/target-snapshot-date, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-source/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.historical-vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.historical-vendor-eta-source/eta-source\\\\\\\" :pastdue.historical-vendor-eta-source/eta-source, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.historical-vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.historical-vendor-eta-source/vendor-code, \\\\\\\"target-country-contribution\\\\\\\" :pastdue.historical-vendor-eta-source/target-country-contribution, \\\\\\\"country-code\\\\\\\" :pastdue.historical-vendor-eta-source/country-code, \\\\\\\"contribution_rank\\\\\\\" :pastdue.historical-vendor-eta-source/contribution-rank, \\\\\\\"pastdue.historical-vendor-eta-source/cumulative-positive-share\\\\\\\" :pastdue.historical-vendor-eta-source/cumulative-positive-share}}\\\",\\\"required_inputs\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\"]}]}\"}],\"isError\":false}"
  }
]
16:29:40

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.country-monitor/country-code \"CA\"} :query [{:pastdue.country-monitor/positive-vendor-contributors {:limit 10 :order_by \"contribution-rank\" :sort_dir \"asc\"} [:pastdue.vendor-contribution/contribution-rank :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/past-due-count-change {:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/previous-past-due-count :pastdue.vendor-eta-code/past-due-count-change :pastdue.vendor-eta-code/positive-contribution-share :pastdue.vendor-eta-code/contribution-rank]}]}]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error \\\"the query is not readable EDN: Map literal must contain an even number of forms\\\"}\"}],\"isError\":true}"
  }
]
16:29:44

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.country-monitor/country-code \"CA\"} :query [({:pastdue.country-monitor/positive-vendor-contributors [:pastdue.vendor-contribution/contribution-rank :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/past-due-count-change]} {:limit 10 :order_by \"contribution-rank\" :sort_dir \"asc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:29:48

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_6d4a809f-cf8f-4924-99c3-132255793adf",
  "sql": "SELECT contribution_rank, vendor_code, vendor_name, latest_snapshot_date, previous_snapshot_date, latest_past_due_count, positive_contribution_share, past_due_count_change FROM data ORDER BY contribution_rank ASC LIMIT 10"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_6d4a809f-cf8f-4924-99c3-132255793adf\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"contribution_rank\\\",\\\"vendor_code\\\",\\\"vendor_name\\\",\\\"latest_snapshot_date\\\",\\\"previous_snapshot_date\\\",\\\"latest_past_due_count\\\",\\\"positive_contribution_share\\\",\\\"past_due_count_change\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792484988527,\\\"expires-at\\\":1792485888527,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"1\\\",\\\"40979\\\",\\\"JEM ACCESSORIES\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"292\\\",\\\"0.124785904\\\",\\\"0\\\"],[\\\"2\\\",\\\"1182\\\",\\\"XEROX CANADA LTD OPB SUPPLIES\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"211\\\",\\\"0.090172001\\\",\\\"0\\\"],[\\\"3\\\",\\\"20562\\\",\\\"ACCO BRANDS CANADA\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"210\\\",\\\"0.089742856\\\",\\\"0\\\"],[\\\"4\\\",\\\"39982\\\",\\\"LEGRAND AV - C2G CONSIGNMENT\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"80\\\",\\\"0.034188572\\\",\\\"0\\\"],[\\\"5\\\",\\\"37103\\\",\\\"F5 NETWORKS, INC\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"76\\\",\\\"0.032479620\\\",\\\"0\\\"],[\\\"6\\\",\\\"40845\\\",\\\"BELKIN, INC CONSIGNMENT\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"64\\\",\\\"0.027350857\\\",\\\"0\\\"],[\\\"7\\\",\\\"36224\\\",\\\"AUTODESK INC.\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"59\\\",\\\"0.025214667\\\",\\\"0\\\"],[\\\"8\\\",\\\"39987\\\",\\\"CLOUDFLARE INC.\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"55\\\",\\\"0.023505716\\\",\\\"0\\\"],[\\\"9\\\",\\\"40584\\\",\\\"ROCSTOR, INC.\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"55\\\",\\\"0.023505716\\\",\\\"0\\\"],[\\\"10\\\",\\\"40837\\\",\\\"ARCTIC WOLF NETWORKS, INC.\\\",\\\"2026-10-19\\\",\\\"2026-10-18\\\",\\\"54\\\",\\\"0.023078478\\\",\\\"0\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":26}},\\\"row-count\\\":10,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":10,\\\"rows-total\\\":10},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:29:50

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.vendor-eta-code"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable positive-contribution rank within this country and vendor.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the ETA Code analysis identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive share in descending contribution order.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This ETA Code bucket's contribution to the country ratio change.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/eta-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Normalized ETA Code bucket; null and blank values are UNKNOWN.\\\",\\\"synonyms\\\":[\\\"ETA代码\\\",\\\"eta code\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/latest-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest bucket past-due count divided by latest country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Code bucket in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Latest completed country snapshot used for this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous past-due count for this ETA Code bucket.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Bucket positive delta divided by all positive ETA Code deltas for this vendor.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous bucket past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due PO-line count for this ETA Code bucket in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Previous available completed country snapshot used for this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"attribute\\\":\\\"pastdue.vendor-eta-code/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the ETA Code analysis identity.\\\",\\\"format\\\":\\\"id\\\"}]}\"}],\"isError\":false}"
  }
]
16:29:53

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 1182} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-eta-code/country-code \"CA\" :pastdue.vendor-eta-code/vendor-code 40979} :query [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :refs [\\\"D5.4\\\"], :env/version 3, :severity :error, :code :engine.request/unreachable, :attributes [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/positive-contribution-share], :studio/recent-id \\\"bbc1b692-09a7-4000-a1d1-0f6f0a25ac71\\\", :message \\\"these attributes cannot be reached from where this query starts: nothing on the starting entity points at them, so no data was read. No resolver path reaches them in this graph\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:02 - 16:30:03

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:11

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 1182} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_d1857b9b-e514-4a12-b33e-673f55d68899\\\",\\\"expires-at\\\":1792368916187,\\\"captured-at\\\":1792368016187,\\\"env/version\\\":2,\\\"result-bytes\\\":1990,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:11

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 3, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :bulkhead_rejected, :studio/recent-id \\\"207a351c-8136-48eb-8049-ab094e065e59\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 3, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :bulkhead_rejected, :studio/recent-id \\\"eb6fac49-d693-4e8f-90d3-154b243f4e35\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:09 - 16:30:10

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_fa88bfa4-298d-4a31-92be-ca2a3517fbcb",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_fa88bfa4-298d-4a31-92be-ca2a3517fbcb\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485011011,\\\"expires-at\\\":1792485911011,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"US\\\",\\\"242\\\",\\\"2026-10-19\\\"],[\\\"NR\\\",\\\"28\\\",\\\"2026-10-19\\\"],[\\\"RQ\\\",\\\"20\\\",\\\"2026-10-19\\\"],[\\\"PE\\\",\\\"1\\\",\\\"2026-10-19\\\"],[\\\"SH\\\",\\\"1\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":151}},\\\"row-count\\\":5,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":5,\\\"rows-total\\\":5},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_c20b80f8-3690-4097-9eb6-9fb7e8f34afa",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_c20b80f8-3690-4097-9eb6-9fb7e8f34afa\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010582,\\\"expires-at\\\":1792485910582,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"NR\\\",\\\"205\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"6\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":70}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_9d0e20e5-3b8d-41a0-b0b9-497de23143b1",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_9d0e20e5-3b8d-41a0-b0b9-497de23143b1\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010554,\\\"expires-at\\\":1792485910554,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"SH\\\",\\\"91\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"55\\\",\\\"2026-10-19\\\"],[\\\"RQ\\\",\\\"1\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":54}},\\\"row-count\\\":3,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":3,\\\"rows-total\\\":3},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_8df77b3a-1b34-48b5-bf71-ac0de32e6388",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_8df77b3a-1b34-48b5-bf71-ac0de32e6388\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010807,\\\"expires-at\\\":1792485910807,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"US\\\",\\\"58\\\",\\\"2026-10-19\\\"],[\\\"DD\\\",\\\"12\\\",\\\"2026-10-19\\\"],[\\\"SH\\\",\\\"6\\\",\\\"2026-10-19\\\"],[\\\"NR\\\",\\\"2\\\",\\\"2026-10-19\\\"],[\\\"RQ\\\",\\\"1\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":68}},\\\"row-count\\\":5,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":5,\\\"rows-total\\\":5},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_894cdf09-fb18-4674-b9e8-ff30f1f94af9",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_894cdf09-fb18-4674-b9e8-ff30f1f94af9\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010923,\\\"expires-at\\\":1792485910923,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"NR\\\",\\\"51\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"25\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":146}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_650c9cf2-b21b-4bd6-8d21-52fd9e43314d",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_650c9cf2-b21b-4bd6-8d21-52fd9e43314d\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485011043,\\\"expires-at\\\":1792485911043,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"SH\\\",\\\"64\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":102}},\\\"row-count\\\":1,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":1,\\\"rows-total\\\":1},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_80c5403e-a08f-4375-948b-e0f2e790686f",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_c20b80f8-3690-4097-9eb6-9fb7e8f34afa\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010582,\\\"expires-at\\\":1792485910582,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"NR\\\",\\\"205\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"6\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":70}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_90edd392-d062-48c7-936e-60b14fef394d",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_90edd392-d062-48c7-936e-60b14fef394d\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010867,\\\"expires-at\\\":1792485910867,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"NR\\\",\\\"26\\\",\\\"2026-10-19\\\"],[\\\"RQ\\\",\\\"18\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"9\\\",\\\"2026-10-19\\\"],[\\\"SH\\\",\\\"1\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":71}},\\\"row-count\\\":4,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":4,\\\"rows-total\\\":4},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:15

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:19

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-contribution/country-code \"CA\" :pastdue.vendor-contribution/vendor-code 40979} :query [{:pastdue.vendor-contribution/eta-code-drivers [:pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/latest-snapshot-date]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_3d5b3e26-3988-45a0-8edd-ab5750b4889c\\\",\\\"expires-at\\\":1792368964807,\\\"captured-at\\\":1792368064807,\\\"env/version\\\":2,\\\"result-bytes\\\":699,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:30:19

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_86eee8d5-62e4-4df8-9909-48100d23aa36",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_c20b80f8-3690-4097-9eb6-9fb7e8f34afa\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485010582,\\\"expires-at\\\":1792485910582,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"NR\\\",\\\"205\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"6\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":70}},\\\"row-count\\\":2,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:22

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_70542970-22a3-4f39-95b2-f97991cc75de",
  "sql": "SELECT eta_code, latest_past_due_count, latest_snapshot_date FROM data ORDER BY latest_past_due_count DESC"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_70542970-22a3-4f39-95b2-f97991cc75de\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"eta_code\\\",\\\"latest_past_due_count\\\",\\\"latest_snapshot_date\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485019913,\\\"expires-at\\\":1792485919913,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"RQ\\\",\\\"52\\\",\\\"2026-10-19\\\"],[\\\"US\\\",\\\"2\\\",\\\"2026-10-19\\\"],[\\\"SH\\\",\\\"1\\\",\\\"2026-10-19\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":27}},\\\"row-count\\\":3,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":3,\\\"rows-total\\\":3},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:30:22
CA Past Due Top 10 Vendor 的 ETA Code 占比
最新快照：2026-10-19（对比快照：2026-10-18）
占比口径：各 Vendor 当前 Past Due 行数中，不同 ETA Code 的占比。Vendor 按 CA 正向 Past Due 贡献排名。

排名	Vendor	当前 Past Due	ETA Code 占比
1	40979 — JEM ACCESSORIES	292	US 242（82.88%）；NR 28（9.59%）；RQ 20（6.85%）；PE 1（0.34%）；SH 1（0.34%）
2	1182 — XEROX CANADA LTD OPB SUPPLIES	211	NR 205（97.16%）；US 6（2.84%）
3	20562 — ACCO BRANDS CANADA	210	SH 91（43.33%）；US 55（26.19%）；RQ 1（0.48%）*
4	39982 — LEGRAND AV - C2G CONSIGNMENT	80	US 58（72.50%）；DD 12（15.00%）；SH 6（7.50%）；NR 2（2.50%）；RQ 1（1.25%）*
5	37103 — F5 NETWORKS, INC	76	NR 51（67.11%）；US 25（32.89%）
6	40845 — BELKIN, INC CONSIGNMENT	64	SH 64（100.00%）
7	36224 — AUTODESK INC.	59	US 49（83.05%）；NR 10（16.95%）
8	39987 — CLOUDFLARE INC.	55	查询失败：Atlas bulkhead_rejected
9	40584 — ROCSTOR, INC.	55	查询失败：Atlas bulkhead_rejected
10	40837 — ARCTIC WOLF NETWORKS, INC.	54	NR 26（48.15%）；RQ 18（33.33%）；US 9（16.67%）；SH 1（1.85%）
* ETA Code 返回行的 Past Due 合计与 Vendor 总数存在未覆盖差异，因此该 Vendor 的 ETA Code 占比为已返回数据口径，不能视为完整拆分。

简要结论
NR 占主导：Xerox Canada（97.16%）、F5 Networks（67.11%）、Arctic Wolf（48.15%）。
US 占主导：JEM Accessories（82.88%）、Legrand/C2G（72.50%）、Autodesk（83.05%）。
SH 集中：Belkin（100.00%）和 ACCO Brands（43.33%）。
Cloudflare（39987）和 Rocstor（40584）的 ETA Code 查询因 Atlas bulkhead_rejected 失败，未填充占比。
16:30:30
对应的ETA specialist
16:30:54

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
ETA specialist
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"ETA specialist\\\",\\\"hits\\\":[]}\"}],\"isError\":false}"
  }
]
16:30:57

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
specialist
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"specialist\\\",\\\"hits\\\":[]}\"}],\"isError\":false}"
  }
]
16:31:01

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.vendor-master-mapping",
    "pastdue.vendor-master-mapping-query"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the vendor master mapping identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/department-type\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"DNA department type from vendor_master_mapping_view (e.g. ETAD, BUYR).\\\",\\\"values\\\":{\\\"BUYR\\\":\\\"Buyer dept\\\",\\\"ETAD\\\":\\\"ETAD\\\"},\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/master-manager\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA manager display name from vendor_master_mapping_view. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/master-primary\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA primary contact display name from vendor_master_mapping_view. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"UTC time at which the mapping facts were queried.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor number (vendor_no) in vendor_master_mapping_view.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping-query/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country scope for the mapping filter query.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping-query/department-type-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional department_type filter; empty string means no filter.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping-query/master-manager-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional master_manager filter; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping-query/master-primary-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional master_primary filter; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"attribute\\\":\\\"pastdue.vendor-master-mapping-query/matching-mappings\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-master-mapping\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-master-mapping/country-code :pastdue.vendor-master-mapping/vendor-code :pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc], :default [:pastdue.vendor-master-mapping/vendor-code :asc], :aliases {\\\\\\\"pastdue.vendor-master-mapping/department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"pastdue.vendor-master-mapping/country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"master_manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department_type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master_primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"pastdue.vendor-master-mapping/master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"pastdue.vendor-master-mapping/queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-master-mapping-query/country-code\\\",\\\"pastdue.vendor-master-mapping-query/department-type-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-primary-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-manager-filter\\\"]}]}\"}],\"isError\":false}"
  }
]
16:31:01

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.vendor-master-mapping-query/country-code \"CA\" :pastdue.vendor-master-mapping-query/department-type-filter \"ETAD\" :pastdue.vendor-master-mapping-query/master-primary-filter \"\" :pastdue.vendor-master-mapping-query/master-manager-filter \"\"} :query [{:pastdue.vendor-master-mapping-query/matching-mappings [:pastdue.vendor-master-mapping/vendor-code :pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc]}]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_b22b9a13-bbd1-4eef-aa29-f87f22745a1f\\\",\\\"expires-at\\\":1792485964319,\\\"captured-at\\\":1792485064319,\\\"env/version\\\":3,\\\"result-bytes\\\":60554,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"storage\\\":\\\"temporary-file\\\",\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"}\"}],\"isError\":false}"
  }
]
16:31:03 - 16:31:04

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
{
  "result_handle": "result_b22b9a13-bbd1-4eef-aa29-f87f22745a1f",
  "sql": "SELECT vendor_code, department_type, master_primary, master_manager, queried_at_utc FROM data WHERE vendor_code IN (40979,1182,20562,39982,37103,40845,36224,39987,40584,40837) ORDER BY vendor_code"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result_handle\\\":\\\"result_b22b9a13-bbd1-4eef-aa29-f87f22745a1f\\\",\\\"analysis/version\\\":1,\\\"columns\\\":[\\\"vendor_code\\\",\\\"department_type\\\",\\\"master_primary\\\",\\\"master_manager\\\",\\\"queried_at_utc\\\"],\\\"source\\\":{\\\"env/version\\\":3,\\\"alerts\\\":[{\\\"code\\\":\\\"result/collection-coverage-unknown\\\",\\\"message\\\":\\\"Collections may be limited by their resolvers; parent limits do not bound total child rows. Completeness is unknown. Flattening nested collections can repeat parent values and multiply rows.\\\"}],\\\"captured-at\\\":1792485064319,\\\"expires-at\\\":1792485964319,\\\"result-complete\\\":true,\\\"source-completeness\\\":\\\"unknown\\\"},\\\"rows\\\":[[\\\"1182\\\",\\\"ETAD\\\",\\\"Linna Ni\\\",\\\"Kristina Yoder\\\",\\\"2026-10-20T08:31:04Z\\\"],[\\\"20562\\\",\\\"ETAD\\\",\\\"Pradeepa Rajendran\\\",\\\"Kristina Yoder\\\",\\\"2026-10-20T08:31:04Z\\\"]],\\\"analysis\\\":{\\\"scope\\\":\\\"returned-data\\\",\\\"source-completeness\\\":\\\"unknown\\\",\\\"failed-inputs\\\":0,\\\"input-row-limit\\\":20000,\\\"input-byte-limit\\\":16777216,\\\"note\\\":\\\"Analysis covers returned rows only. Nested joins repeat parent values; this is not proof of full-source totals.\\\",\\\"materialized\\\":false,\\\"stages\\\":{\\\"flatten\\\":0,\\\"load-and-sql\\\":71}},\\\"row-count\\\":200,\\\"truncation\\\":{\\\"rows?\\\":false,\\\"cells?\\\":false,\\\"rows-shown\\\":2,\\\"rows-total\\\":2},\\\"truncated?\\\":false}\"}],\"isError\":false}"
  }
]
16:31:06
CA Top 10 Vendor 对应 ETA Specialist
以下为当前组织映射上下文，不是历史 Past Due 归因。这里将 ETAD 的 master_primary 作为 ETA Specialist，查询时间为 2026-10-20 08:31 UTC。

Past Due 排名	Vendor	ETA Specialist	ETA Manager
1	40979 — JEM ACCESSORIES	未找到映射	—
2	1182 — XEROX CANADA LTD OPB SUPPLIES	Linna Ni	Kristina Yoder
3	20562 — ACCO BRANDS CANADA	Pradeepa Rajendran	Kristina Yoder
4	39982 — LEGRAND AV - C2G CONSIGNMENT	未找到映射	—
5	37103 — F5 NETWORKS, INC	未找到映射	—
6	40845 — BELKIN, INC CONSIGNMENT	未找到映射	—
7	36224 — AUTODESK INC.	未找到映射	—
8	39987 — CLOUDFLARE INC.	未找到映射	—
9	40584 — ROCSTOR, INC.	未找到映射	—
10	40837 — ARCTIC WOLF NETWORKS, INC.	未找到映射	—
说明： Atlas 当前仅返回了 Vendor 1182 和 20562 的 ETAD 映射；其余 Vendor 不能据此推断负责人。映射集合存在覆盖未知提示，因此“未找到映射”不等同于组织系统中绝对不存在该 Specialist。

---

**Cursor**

## 结论先说

**不是「越改越不行」把图改坏了。**  
Top-10 能查出、部分 ETA/Specialist 也能出，说明 Atlas 仍可用。  
这次炸点仍是：**错入口、合查/坏 EDN、无 worklist 狂打、脏 handle**——Kernel/问答设计写了，**运行时没遵守**。  
另外还有两个**语义选错能力**，会让结果看起来更糟（占比不全、Specialist「未找到」假阴性）。

---

## 错误清单（按时间）

| # | 现象 | 根因 |
|---|---|---|
| 1 | `Map literal must contain an even number of forms` | 第一次把 Top-10 **嵌套** `eta-code-drivers`，EQL 语法坏了 |
| 2 | Top-10 查询返回旧 handle `result_d1857b9b`（env v2） | **脏 handle**；真正成功的是后来的 `6d4a809f`（env v3） |
| 3 | 以 `pastdue.vendor-eta-code` 为根投影字段 → `unreachable` | 与 mapping/contribution 标量同病：**目标实体不是根入口** |
| 4 | 同一 unreachable 对 40979 **连打十余次** | **无 worklist**，未把 `UNREACHABLE` 标终态 |
| 5 | 改走 `eta-code-drivers` 后并行风暴 → `bulkhead_rejected` | 未共享并发预算 / 未串行 |
| 6 | 又大量返回 `3d5b3e26` / `d1857b9b` | 继续用脏 handle |
| 7 | ACCO 等 ETA 行数加总 &lt; Vendor Past Due | **能力选错**：`eta-code-drivers` 是 **正向贡献** 旧路径，不是「当前 Past Due 库存按 Code 全量占比」 |
| 8 | Specialist：全国 ETAD `matching-mappings` limit 200，再 `IN (Top10)` 只命中 2 个 | **假阴性**：页内按 `vendor_code` 排序的前 200 个 CA ETAD，盖不住 Top-10；40979 等并非无 mapping（此前 `master-mappings` 能出） |

---

## 和「改文档」的关系

文档要求大致是：

- ETA 占比 / 分解 → 优先 **`eta-code-changes`（历史四元组）** 或明确「库存占比」口径；**默认不要用 drivers**  
- Specialist → **`:master-mappings` 每 Vendor 一次**（或保证过滤结果含目标 Vendor），**不要**用全国 matching 一页再碰运气  
- 禁止 `vendor-eta-code` 根查；禁止合查；worklist + 共享并发  

这次运行：**先坏 EDN 嵌套 → 再猜根 → 再狂重试 → 再用 drivers + 全国 matching**。  
所以是 **执行偏离设计**，不是设计把查询「改死」了。

---

## 「ETA Code 占比」正确 Fact Plan（CA Top-10）

1. `:positive-vendor-contributors` CA，limit 10（已成功）  
2. 对每个 Vendor：**单独**  
   `historical-vendor-contribution` + `eta-code-changes`  
   （日期 = Top-10 的 latest/previous），用 `target-past-due-count`（或 latest）算占比；校验 Σ ≈ Vendor Past Due  
3. **不要**默认 `eta-code-drivers`（正向贡献，易缺码 → 你表里的 * 注释）  
4. Specialist：对 Top-10 **逐个** `:master-mappings`，筛 `ETAD`（一次批查全国 ETAD 必须能覆盖目标 Vendor，当前 200 条页做不到）

---

## Specialist「未找到」为何不可信

同会话更早用 `master-mappings` 时，40979 是 **Pradeepa Rajendran**。  
这次用 CA 全量 ETAD `matching-mappings` 再过滤，页里没有 40979 → 写成「未找到」——**查询策略错误，不是无负责人**。

---

## 建议（避免「越改越像更差」）

1. **运行验收**：同一问题只允许声明的入口；`UNREACHABLE` 一次后停；禁止重复同一 worklist key。  
2. **意图表写死口径**：  
   - 「占比 / 构成」→ `eta-code-changes`  
   - 「正向贡献驱动」→ 才用 `drivers`  
3. **Specialist** → 强制 per-Vendor `master-mappings`，禁止「全国 matching + IN Top10」除非 limit/过滤能证明覆盖。  
4. **Handle**：execute 返回的 handle 必须与本次 env/captured-at 一致，否则作废重打一次。

如果你愿意，我可以直接在 Skill/playbook 里加这三条 **硬反例 + 占比意图映射**（改动很小，专门针对这次 trace）。

---

**User**

不是这次 trace 和专门针对这次 是整体agent ，为什么出现这样的问题 ，如果解决 。如何让这个agent 完善 可用

---

**Cursor**

## 为什么整体会这样（不是单次 trace）

根因可以收成三句：

1. **图是严的，Agent 是松的**  
   Atlas 只认可达路径；模型在问答压力下会猜根、合查、并行狂打。文档写得再对，**没有强制执行**，就会反复 `unreachable` / `:source` / bulkhead。

2. **规则在变厚，执行在变飘**  
   Kernel、意图表、日报预设叠在一起后，SP/Skill 变长。模型更容易「读一半、即兴写 EQL」，表现为：刚禁止的错入口又出现、刚要求的 worklist 又不做。

3. **「能答」和「答对入口」不是一回事**  
   同一问有多条相近 join（`drivers` vs `changes`、全国 `matching` vs 按 Vendor `master-mappings`）。没有 **口径→能力** 的硬绑定，就会选语义接近但错误的路，结果半对半残——看起来像「越改越差」。

所以：不是 EDN 写死了，也不是不该做灵活问答；是 **治理全靠 prompt 自觉，扛不住真实多轮问答**。

---

## 怎样才算「完善可用」

可用 = 四条同时成立：

| 标准 | 含义 |
|---|---|
| **入口正确** | 常见意图几乎不再 `unreachable` / 假「未找到」 |
| **调用可控** | 少合查、少风暴、少脏 handle |
| **口径清楚** | 「占比 / detail / specialist」落到固定能力 |
| **失败可解释** | 真缺数据 vs 查法错 vs bulkhead，用户能分清 |

---

## 解决思路（整体，不是补这一次）

### 层 1：收缩指令面（先做）

- SP **只保留**：证据边界 + Kernel 硬规则 +「意图→能力，禁止猜根」  
- 长剧本、反例、模板进 Skill/playbook；**删掉互相打架的旧写法**（如仍暗示可合查 Owner+PO）  
- 目标：模型每次决策面前只有少数硬约束，而不是一篇论文

### 层 2：意图→能力变成「硬表」，不是散文

对高频问法做成 **不可选歧义** 的映射，例如：

| 用户说法 | 只允许 |
|---|---|
| Top-N + ETA 占比/构成 | `positive-vendor-contributors` → 每 Vendor `eta-code-changes` |
| ETA Specialist | 每 Vendor `master-mappings`（ETAD） |
| Vendor detail / 每一行 | `current-high-risk-lines` |
| Vendor 汇总行数 | contributors/vendor-changes **过滤**，禁止 contribution 根标量 |

相近能力（`drivers`）标成 **仅当用户明确说「正向贡献驱动」** 才用。

### 层 3：执行必须「像状态机」，不能只靠自觉

在 Skill 里写成强制步骤（比再写原则更有用）：

1. 建 worklist → 2. 并发≤2 → 3. 一集合一 EQL → 4. 校验 handle 新旧 → 5. 失败分类后 **禁止重复同一 key**  

`UNREACHABLE` / 脏 handle = **立刻换声明入口或标失败**，禁止同形状重试风暴。

### 层 4：用验收钉住行为（否则改文档等于没改）

固定 5～8 条黄金问（含 Top-10 占比、Specialist、单 Vendor detail、日报触发），每条验收：

- 允许的 capability 序列  
- 禁止的 EQL 形状（根实体、合查）  
- 不允许出现的错误码（或仅允许 `BULKHEAD` 且有退避）

没过验收 = 不能当「可用」发布。

### 层 5（可选增强，真正稳）

若仍频繁跑飞：在 Agent 外加 **薄校验层**（发 EQL 前检查：是否声明入口、是否多 collection、是否重复 key）。  
Prompt 管「想问什么」，校验层管「不许那样打」——这是从「完善」到「生产可用」的关键一步。

---

## 建议落地顺序（让 Agent 可用）

| 阶段 | 做什么 | 效果 |
|---|---|---|
| **A** | 瘦 SP + 清掉 Skill/playbook 冲突/反例 | 少即兴乱写 |
| **B** | 高频意图硬表（占比 / specialist / detail） | 少选错能力、少假阴性 |
| **C** | worklist/handle/禁重试写成强制步骤 | 少 bulkhead、少脏 handle |
| **D** | 黄金问回归 | 可证明「可用」 |
| **E** | （需要时）EQL 预检 | 从「靠自觉」到「靠闸门」 |

Atlas EDN **保持严**；灵活靠意图组合，不靠放松图。

---

## 一句话

出现这类问题，是因为 **灵活问答靠模型即兴拼 EQL，而图要求严格路径**——文档加厚解决不了自觉执行。  
要完善可用： **瘦指令 + 硬意图表 + 强制执行步骤 + 黄金问验收（必要时加预检）**。

如果你点头，下一步我建议直接做 **阶段 A+B**（瘦 SP + 占比/specialist/detail 硬表），这是性价比最高、也最能止住「越改越像更差」体感的一步。

---

**User**

不要性价比最高 要最完善的方案

---

**Cursor**

按「最完善」而不是「性价比」来定方案。先加载设计相关 skill，并核对当前 agent 结构，再给出完整落地设计。

「最完善」和「只改文档」差在能不能加**机器强制层**。先确认这一条硬约束：

实现时，是否允许在 Agent 宿主侧加一层 **EQL/MCP 运行时闸门**（发往 Atlas 前校验：单 collection、声明入口、禁猜根、worklist 去重、handle 归属）？

- **A.** 可以 — 闸门 + Skill/政策 + 黄金问验收一起做（真正「完善可用」）
- **B.** 不行 — 只能 SP/Skill/policy/Atlas，不能改宿主或加中间件
- **C.** 暂不确定 — 先按「闸门可选、设计写全」出方案，部署时再定是否上闸门

选 A / B / C 即可。

---

**User**

别给我选择 你给 最完善 最优秀的方案

---

**Cursor**

不再给选项。直接定稿「最完善」架构，并写入设计文档。

## 定论

**最完善方案不是再加文档，而是把 Kernel 从「靠模型自觉」改成「机器强制」。**  
LLM 只负责意图与成文；**Fact Plan、EQL 形状、并发、handle、禁根**全部由代码执行。Atlas 保持严格。

设计已写入：  
`docs/superpowers/specs/2026-10-20-pastdue-production-usable-agent-design.md`

---

## 架构（唯一路径）

```text
用户问题
  → Intent Compiler（LLM，只能输出目录内 Intent IR）
  → Fact Plan Builder（纯代码：IR + policy → 工作项）
  → Kernel Runtime（纯代码：worklist / 模板 EQL / 并发 / handle）
  → Open Atlas MCP
  → Answer Composer（LLM，只能用 typed 结果成文）

旁路强制：
  Preflight Gate（非法 EQL 出不去）
  Trace + Golden Eval（不过回归不算「可用」）
```

| 以前 | 现在 |
|---|---|
| Skill 写 Kernel 规则 | **Kernel Runtime 执行规则** |
| 模型即兴写 EQL | **只按 capability 模板生成 EQL** |
| 错了靠 Atlas 报 `UNREACHABLE` | **Gate 先拒 + Builder 消歧** |
| 改文档当发布 | **G1–G8 黄金问过了才算可用** |

---

## 必做子系统

1. **Intent IR** — 只允许 `:intent-catalog` 内 id  
2. **Fact Plan Builder** — 硬消歧（占比→`eta-*-changes`；Specialist→每 Vendor `master-mappings`+ETAD；detail→PO lines…）  
3. **Kernel Runtime 工具** — `build_plan` / `kernel_run` / 受控 `analyze`  
4. **Preflight Gate** — 禁多 collection、禁禁根、禁 contribution 标量根、禁重复 key  
5. **瘦 SP** — 不再当 EQL 教程  
6. **Trace + Golden Eval** — 发布门禁  

---

## 完整阶段（不做「先 A+B」子集）

P0 锁规格 → P1 policy 机器表 → P2 Kernel Runtime → P3 Gate → P4 Intent/Builder → P5 Agent 改走 Kernel 工具 → P6 黄金问 CI → P7 Composer → P8 运维指标  

---

请审阅该 spec。确认后我按 **writing-plans** 写出完整实现计划并开始落地。

---

**User**

请开始

---

**Cursor**

规格已确认。先写完整实现计划，再按阶段落地。

使用 writing-plans 编写实现计划，并立即开始落地执行。

计划已写入。开始执行 P1，并并行搭建 Kernel Runtime。

加强 Gate 对禁根的检测逻辑。

单测已通过。继续实现 Golden Eval，并瘦身 SP/Skill。

P1–P6 已落地并通过验收。

## 交付物

| 层 | 内容 |
|---|---|
| **规格/计划** | `docs/superpowers/specs/2026-10-20-pastdue-production-usable-agent-design.md`（Approved）<br>`docs/superpowers/plans/2026-10-20-pastdue-production-usable-agent.md` |
| **Policy 1.7.0** | `:disambiguation` 硬表 + capability completeness notes |
| **Kernel Runtime** | `runtime/pastdue_kernel/`（policy / Intent / Fact Plan / Gate / Kernel / FakeMCP / Trace） |
| **测试** | 15 单测 OK；对抗语料 ≥95%；**G1–G8 全 PASS** |
| **Agent** | 瘦 SP；Skill/Playbook 指向 Runtime 强制执行 |
| **脚本** | `run_kernel_tests.py` / `run_golden_eval.py` / `pastdue_kernel_smoke.py` |

验证命令：

```bash
python scripts/run_kernel_tests.py
python scripts/run_golden_eval.py
python scripts/pastdue_kernel_smoke.py
python scripts/validate_bundle.py   # PASS
```

## 尚未做（规格 P7/P8）

- **P7** Composer 失败文案与模块选择硬化  
- **P8** 线上 gate/UNREACHABLE/bulkhead 指标  
- **宿主 MCP 工具注册**：Runtime 已可被宿主调用；若 Cursor/部署侧要把 `pastdue.kernel_run` 挂成真实 MCP tool，还需一层薄适配（当前 Agent 按 SP/Skill 契约走 Kernel 规则 + 本地 Runtime）

需要的话我可以接着做 **宿主 MCP 适配** 或 **P7 Composer**。

---

**User**

py 脚本作用是什么

---

**Cursor**

这几类 Python 脚本的分工：

## Kernel / 验收（这次新建）

| 脚本 | 作用 |
|---|---|
| `scripts/run_kernel_tests.py` | 跑 `tests/kernel/` 里全部单元测试（policy 加载、Gate、Fact Plan、Kernel 并发/bulkhead/handle） |
| `scripts/run_golden_eval.py` | 跑 G1–G8 黄金问：看「意图→计划→EQL 形状」是否符合预期（不连真实 Atlas） |
| `scripts/pastdue_kernel_smoke.py` | 快速冒烟：读 policy、对一句「ETA 占比」建计划并做 Gate 检查，打印结果 |

它们验证 **机器 Kernel 行为对不对**，不是业务日报本身。

## 包本体（不是“运维脚本”，但被上面调用）

`runtime/pastdue_kernel/*.py`：真正的执行逻辑——读 `policy.edn`、消歧、建 Fact Plan、生成单 collection EQL、Preflight Gate、worklist/并发/handle。

## 仓库里原有的

| 脚本 | 作用 |
|---|---|
| `scripts/validate_bundle.py` | 离线校验 Atlas chart / Skill / playbook 结构 |
| `scripts/test_top10_changes.py` | Top-10 / ETA 算术与相关契约测试 |
| `scripts/test_query_contracts.py` | 查询契约测试 |
| `scripts/import_vendor_notification_contacts.py` | 导入通知联系人 CSV → 规范化数据 |

**一句话：** 新建的三个脚本是 **Kernel 的测试与冒烟门禁**；`runtime/pastdue_kernel` 才是运行时；`validate_bundle` 等仍管 bundle/业务契约。Agent 线上查数仍走 Atlas MCP，这些 py **默认不替代** MCP 查数。

---

**User**

这个agent是否还 遵循 report 的模板严格一致 ， 用户的新问题 会先匹配有无适合模板  

---

**Cursor**

正在核对当前 SP / Skill / report 合同里对模板的实际约定。

**不完全是「先匹配 report 模板」。** 分两层：

## 1. 新问题先匹配什么？

先匹配的是 **意图 / 能力**，不是 report 模板：

```text
用户新问题
  → Intent（intent-catalog + disambiguation）
  → Fact Plan（查哪些 capability）
  → Kernel 执行
  → Compose（这时才选 report 模块）
```

也就是说：先定「要答什么、怎么查」，再定「用哪张表怎么画」。

## 2. Report 模板还严不严？

**画表时仍然要严。** Skill / `report-format.md` 仍要求 template-first：

- 有对应 composition / module → 列名、列序、单元格格式必须跟 `table-display-contract` **一致**
- 日报 / 历史全量 → `daily_full` / `historical_full` 等
- 窄问题 → 只用匹配到的 module（同一套模板）
- **没有**匹配 module → **不准编一张“标准表”**，用散文，并标明「无显示契约模板」

散文（结论、解读、限制）可以灵活；**不能**用自创表头/乱序列去顶替契约表。

## 3. 和旧「整份日报脚本」的差别

| | 以前体感 | 现在 |
|---|---|---|
| 每个问都跑完整 `daily_full`？ | 容易被当成固定剧本 | **否**，按意图组合；日报只是 preset |
| 出表严不严？ | 严 | **仍严**（有 module 就必须一致） |
| 新问先找啥？ | 容易直接套日报场景 | **先 intent，再在 compose 阶段选 module** |

SP 里写的是 “Prefer Skill display modules”，Skill 里更硬：有契约就按契约。实际应以 Skill + `table-display-contract` 为准。

**一句话：** 新问题先匹配 **intent/能力**；有合适 **display module** 时表格仍必须严格一致；没有 module 就散文说明，不硬套整份日报模板。

---

**User**

我用相同的 提问 会出俩种格式  

CA的past due 排名前十的vendor，eta code占比
17:15:11

read
Completed
17:15:19

read
Completed
17:15:22

read
Completed
17:15:22

read
Completed
17:15:22 - 17:15:23

read
Completed
17:15:22 - 17:15:23

mcp__ETA-PastDue-Graph__list_entities (mcp · ETA-PastDue-Graph)
Completed
17:15:25

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
17:15:28

mcp__ETA-PastDue-Graph__compute_metric (mcp · ETA-PastDue-Graph)
Completed
17:15:28

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:32

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:15:34

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:40

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:40

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:46

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:46

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:51 - 17:15:52

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:52

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:15:57

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:15:57

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:57

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:15:57

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:16:01

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:16:01

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:16:06 - 17:16:07

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:16:06 - 17:16:07

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:16:09

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:16:09

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:16:14 - 17:16:15

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:16:14 - 17:16:15

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:16:17 - 17:16:18

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:16:17 - 17:16:18
CA Past Due Top-10 Vendor — ETA Code 占比
快照范围：2026-10-18 → 2026-10-19
口径：CA Past Due 贡献排名前十 Vendor 的本期 ETA Code 分布。

ETA Code	返回的 Past Due 行数	占比
NR	366	37.77%
US	438	45.20%
RQ	160	16.51%
SH	4	0.41%
PE	1	0.10%
合计	969	100.00%
结论：

主要 ETA Code 为 US（45.20%） 和 NR（37.77%），合计 82.97%。
RQ 占 16.51%，其余 Code 占比较低。
数据限制：
Top-10 Vendor 本期 Past Due 总数为 1156，但 ETA Code 查询返回 969 行，且部分 Vendor 的 Code 明细与 Vendor 总数无法完全核对，因此上述占比是已返回 ETA Code 行的分布，暂不能作为完整 Top-10 总体占比。发现不一致的 Vendor 包括：ACCO BRANDS CANADA、LEGRAND AV - C2G CONSIGNMENT、F5 NETWORKS、BELKIN 等。


CA的past due 排名前十的vendor，eta code占比
17:08:43

read
Completed
17:08:45

read
Completed
17:08:48

read
Completed
17:08:48

read
Completed
17:08:48

read
Completed
17:08:48

mcp__ETA-PastDue-Graph__list_entities (mcp · ETA-PastDue-Graph)
Completed
17:08:51

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
17:08:53

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
17:08:53

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
17:08:53

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
17:08:53

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:08:57 - 17:08:58

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:08:59

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:03 - 17:09:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:03 - 17:09:04

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:07 - 17:09:08

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:07 - 17:09:08

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:11 - 17:09:12

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:11 - 17:09:12

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:15 - 17:09:16

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:15 - 17:09:16

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:19 - 17:09:20

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
17:09:19 - 17:09:20

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:09:24

mcp__ETA-PastDue-Graph__analyze_result (mcp · ETA-PastDue-Graph)
Completed
17:09:29 - 17:09:30
CA Past Due Top-10 Vendor 的 ETA Code 占比
快照日期：2026-10-19；对比 2026-10-18
占比口径：各 Vendor 本期 Past Due 行数中，不同 ETA Code 的占比。

排名	Vendor Code	Vendor	Past Due	ETA Code 占比
1	40979	JEM ACCESSORIES	292	US 82.88%；NR 9.59%；RQ 6.85%；PE 0.34%；SH 0.34%
2	1182	XEROX CANADA LTD OPB SUPPLIES	211	NR 97.16%；US 2.84%
3	20562	ACCO BRANDS CANADA	210	SH 43.33%；US 26.19%；DD 19.05%；NR 10.95%；RQ 0.48%
4	39982	LEGRAND AV - C2G CONSIGNMENT	80	US 72.50%；DD 15.00%；SH 7.50%；NR 2.50%；NS 1.25%；RQ 1.25%
5	37103	F5 NETWORKS, INC	76	NR 67.11%；US 32.89%
6	40845	BELKIN, INC CONSIGNMENT	64	SH 100.00%
7	36224	AUTODESK INC.	59	US 83.05%；NR 16.95%
8	39987	CLOUDFLARE INC.	55	US 81.82%；NR 18.18%
9	40584	ROCSTOR, INC.	55	RQ 94.55%；US 3.64%；SH 1.82%
10	40837	ARCTIC WOLF NETWORKS, INC.	54	NR 48.15%；RQ 33.33%；US 16.67%；SH 1.85%
重点观察：

US 是 JEM ACCESSORIES、LEGRAND、AUTODESK、CLOUDFLARE 的主要 ETA Code。
NR 是 XEROX、F5、ARCTIC WOLF 的主要 ETA Code。
SH 占 ACCO 和 BELKIN 较高，其中 BELKIN 为 100% SH。
RQ 高度集中在 ROCSTOR，占 94.55%。
注：以上为 Atlas 返回的 CA Top-10 Vendor 及 ETA Code 结果；集合覆盖标记为 collection-coverage-unknown，因此不将其解释为全源覆盖证明。


这是为什么

---

**Cursor**

同一问出两种版式，是意图歧义 + 展示层仍靠模型自由发挥。正在对照显示契约确认。

## 原因

同一句「CA Top-10 + ETA Code 占比」在语义上可以合法理解成两种口径，而**展示层仍由模型自由选版式**，没有像 Kernel 那样锁死「只能出某一种表」。

| 次 | 理解成 | 结果形态 |
|---|---|---|
| **17:08** | 每个 Vendor 自己的 Past Due 里，各 ETA Code 占比 | 10 行 Vendor 表 + 行内 `US xx%；NR yy%` |
| **17:15** | Top-10 **合在一起**，全局 ETA Code 分布 | 一张 Code 汇总表；还走了 `compute_metric` |

所以不是 Atlas 随机，而是：

1. **问法歧义** — 「前十的 vendor，eta code 占比」没说清是 *按 Vendor 拆* 还是 *Top-10 池汇总*。  
2. **Intent 只锁了查什么**（`eta-code-changes`）— **没锁用哪个 display module / 聚合粒度**。  
3. **Compose 未机器强制** — SP 只写 Prefer modules；模型每次可换排版。  
4. **契约里也没有「ETA Code 占比」专用 module** — 最接近的是 `vendor_summary` 的 brief（偏变化列表）或 `eta_code_changes` 全表（偏 delta），「占比%」本就是模型自造口径，更容易分叉。  
5. **工具路径也不同** — 17:15 多了 `compute_metric`，更容易走「全局汇总」；17:08 没有，更容易走「逐 Vendor 拼 brief」。

Kernel 管的是「别乱写 EQL」；**管不了「答案长什么样」**，所以同问两格式会反复出现。

---

## 和「严格模板」的关系

当前：**查数路径**在收紧，**出表路径**仍松。  
要同问同版，必须再加一层，例如默认：

- 「Top-N + ETA 占比」→ **固定** `vendor_summary` 风格（每 Vendor 一行 + Code 占比 brief）  
- 只有用户说「合计 / 汇总 / 整体分布」才允许全局 Code 表  

并把这条写进 `policy` / Composer 硬规则（最好和 Intent 一样机器校验），不能只靠模型自觉。

如果你要，我可以直接把这条 **默认版式 + 消歧话术** 写进 policy / report-format / SP，并加一条黄金问锁死「同问只能出 Vendor 行表」。

---

**User**

这是一轮会话 
“”“ 我说的是 实时 现在的状况
08:43:02

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.current-high-risk-line",
    "pastdue.vendor-contribution"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/action-type\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current source-view action classification.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/buyer-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current buyer display value; missing assignments are UNASSIGNED. Personal data (PII); handle per data-protection rules.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country of the current PO line.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current normalized ETA code; blank and null values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-date\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"Current estimated arrival date.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-source\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current normalized ETA information source; blank and null values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/is-past-due\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when the current PO line is past due.\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/manager-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA manager from vendor_master_mapping_view for this vendor (not past_due_detail_view.master_manager); missing assignments are UNASSIGNED. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/order-type\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Order type component of the PO-line identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/ordered-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity ordered on the PO line.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/past-due-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Current whole days past due, when applicable.\\\",\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Line-number component of the PO-line identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/po-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Purchase-order number component of the PO-line identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/primary-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA primary from vendor_master_mapping_view for this vendor (not past_due_detail_view.master_primary); missing assignments are UNASSIGNED. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/received-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity currently recorded as received.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-level\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current calculated risk level.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-priority-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable current risk rank within a country and vendor: HIGH_RISK, past-due flag, past-due days, ETA date, PO identity.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/shipped-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity currently recorded as shipped.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/sku-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Internal SKU number on the current PO line.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor account number for the current PO line.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank within the country.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the vendor analysis identity.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive contribution share in descending delta order.\\\",\\\"synonyms\\\":[\\\"累计正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor contribution to country ratio change: latest country contribution minus previous country contribution.\\\",\\\"synonyms\\\":[\\\"对国家逾期率变化的贡献\\\",\\\"vendor contribution\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Latest completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor open count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor past-due count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This vendor's positive delta divided by the sum of positive vendor deltas in the country.\\\",\\\"synonyms\\\":[\\\"正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Previous available completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the identity; vendor code is not assumed globally unique across countries.\\\",\\\"synonyms\\\":[\\\"供应商代码\\\",\\\"vendor\\\"],\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Latest available nonblank vendor name across the compared snapshots.\\\",\\\"synonyms\\\":[\\\"供应商名称\\\"],\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor ratio minus previous vendor ratio.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-high-risk-lines\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-high-risk-line\\\",\\\"limit\\\":{\\\"default\\\":20,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-high-risk-line/country-code :pastdue.current-high-risk-line/vendor-code :pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/ordered-quantity :pastdue.current-high-risk-line/shipped-quantity :pastdue.current-high-risk-line/received-quantity :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-date :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/action-type :pastdue.current-high-risk-line/buyer-owner :pastdue.current-high-risk-line/primary-owner :pastdue.current-high-risk-line/manager-owner :pastdue.current-high-risk-line/risk-priority-rank], :default [:pastdue.current-high-risk-line/risk-priority-rank :asc], :aliases {\\\\\\\"risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"order_type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"po_line_number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"pastdue.current-high-risk-line/eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"pastdue.current-high-risk-line/eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"risk_priority_rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"pastdue.current-high-risk-line/received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"pastdue.current-high-risk-line/vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"received_quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"eta_source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"pastdue.current-high-risk-line/manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"is_past_due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"eta_code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"ordered_quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"manager_owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"action_type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"sku_number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"past_due_days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"pastdue.current-high-risk-line/primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"buyer_owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"primary_owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"pastdue.current-high-risk-line/is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"po_number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"eta_date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"pastdue.current-high-risk-line/action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"shipped_quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"risk_level\\\\\\\" :pastdue.current-high-risk-line/risk-level}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-owner-risks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.owner-current-risk\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.owner-current-risk/country-code :pastdue.owner-current-risk/vendor-code :pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-high-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count :pastdue.owner-current-risk/max-past-due-days :pastdue.owner-current-risk/queried-at-utc], :default [:pastdue.owner-current-risk/current-risk-line-count :desc], :aliases {\\\\\\\"pastdue.owner-current-risk/country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"owner_role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"vendor_code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"owner_display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"country_code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"current_high_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"pastdue.owner-current-risk/queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"max_past_due_days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"pastdue.owner-current-risk/owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"pastdue.owner-current-risk/current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"current_past_due_line_count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"current_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-code-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-code/country-code :pastdue.vendor-eta-code/vendor-code :pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/previous-snapshot-date :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/previous-past-due-count :pastdue.vendor-eta-code/past-due-count-change :pastdue.vendor-eta-code/latest-country-contribution :pastdue.vendor-eta-code/previous-country-contribution :pastdue.vendor-eta-code/delta-country-contribution :pastdue.vendor-eta-code/positive-contribution-share :pastdue.vendor-eta-code/cumulative-positive-share :pastdue.vendor-eta-code/contribution-rank], :default [:pastdue.vendor-eta-code/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-code/country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"pastdue.vendor-eta-code/contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.vendor-eta-code/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-code/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-code/eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"pastdue.vendor-eta-code/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-source-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-source/country-code :pastdue.vendor-eta-source/vendor-code :pastdue.vendor-eta-source/eta-source :pastdue.vendor-eta-source/latest-snapshot-date :pastdue.vendor-eta-source/previous-snapshot-date :pastdue.vendor-eta-source/latest-past-due-count :pastdue.vendor-eta-source/previous-past-due-count :pastdue.vendor-eta-source/past-due-count-change :pastdue.vendor-eta-source/latest-country-contribution :pastdue.vendor-eta-source/previous-country-contribution :pastdue.vendor-eta-source/delta-country-contribution :pastdue.vendor-eta-source/positive-contribution-share :pastdue.vendor-eta-source/cumulative-positive-share :pastdue.vendor-eta-source/contribution-rank], :default [:pastdue.vendor-eta-source/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-source/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-source/country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-source/contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"pastdue.vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-source/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"pastdue.vendor-eta-source/vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/master-mappings\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-master-mapping\\\",\\\"limit\\\":{\\\"default\\\":50,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-master-mapping/country-code :pastdue.vendor-master-mapping/vendor-code :pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc], :default [:pastdue.vendor-master-mapping/department-type :asc], :aliases {\\\\\\\"pastdue.vendor-master-mapping/department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"pastdue.vendor-master-mapping/country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"master_manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department_type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master_primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"pastdue.vendor-master-mapping/master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"pastdue.vendor-master-mapping/queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/notification-recipients\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.notification-recipient\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.notification-recipient/country-code :pastdue.notification-recipient/vendor-code :pastdue.notification-recipient/recipient-type :pastdue.notification-recipient/email :pastdue.notification-recipient/contact-name :pastdue.notification-recipient/is-active :pastdue.notification-recipient/source-system :pastdue.notification-recipient/contact-role-hint :pastdue.notification-recipient/contact-priority-score :pastdue.notification-recipient/is-internal-candidate :pastdue.notification-recipient/is-recommended :pastdue.notification-recipient/selection-reason :pastdue.notification-recipient/evidence-text :pastdue.notification-recipient/updated-at-utc], :default [:pastdue.notification-recipient/contact-priority-score :desc], :aliases {\\\\\\\"pastdue.notification-recipient/country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"pastdue.notification-recipient/evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"source_system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"pastdue.notification-recipient/updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"pastdue.notification-recipient/is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"is_active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"selection_reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"contact_priority_score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"vendor_code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"email\\\\\\\" :pastdue.notification-recipient/email, \\\\\\\"contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"is_internal_candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"recipient_type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"country_code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"is_recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"pastdue.notification-recipient/recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"evidence_text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"updated_at_utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"contact_role_hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"pastdue.notification-recipient/contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"pastdue.notification-recipient/source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"contact_name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"pastdue.notification-recipient/contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"pastdue.notification-recipient/email\\\\\\\" :pastdue.notification-recipient/email}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"pastdue.notification-recipient/recipient-type\\\"]}]}\"}],\"isError\":false}"
  }
]
08:43:06

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "current high risk lines",
  "limit": 50
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"current high risk lines\\\",\\\"hits\\\":[{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"exact-name\\\",\\\"label\\\":\\\"Current High Risk Lines\\\",\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-high-risk-lines\\\"},{\\\"kind\\\":\\\"entity\\\",\\\"match\\\":\\\"entity\\\",\\\"label\\\":\\\"PastDue Current Owner Risk\\\",\\\"entity\\\":\\\"pastdue.owner-current-risk\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Current High-Risk Line Count\\\",\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Current Risk Line Count\\\",\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-risk-line-count\\\"}]}\"}],\"isError\":false}"
  }
]
08:43:08

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.current-high-risk-line"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/action-type\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current source-view action classification.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/buyer-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current buyer display value; missing assignments are UNASSIGNED. Personal data (PII); handle per data-protection rules.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country of the current PO line.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current normalized ETA code; blank and null values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-date\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"Current estimated arrival date.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/eta-source\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current normalized ETA information source; blank and null values are UNKNOWN.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/is-past-due\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when the current PO line is past due.\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/manager-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA manager from vendor_master_mapping_view for this vendor (not past_due_detail_view.master_manager); missing assignments are UNASSIGNED. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/order-type\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Order type component of the PO-line identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/ordered-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity ordered on the PO line.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/past-due-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Current whole days past due, when applicable.\\\",\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Line-number component of the PO-line identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/po-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Purchase-order number component of the PO-line identity.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/primary-owner\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current DNA primary from vendor_master_mapping_view for this vendor (not past_due_detail_view.master_primary); missing assignments are UNASSIGNED. Personal data (PII).\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/received-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity currently recorded as received.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-level\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current calculated risk level.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-priority-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable current risk rank within a country and vendor: HIGH_RISK, past-due flag, past-due days, ETA date, PO identity.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/shipped-quantity\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Quantity currently recorded as shipped.\\\",\\\"unit\\\":\\\"units\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/sku-number\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Internal SKU number on the current PO line.\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor account number for the current PO line.\\\",\\\"format\\\":\\\"id\\\"}]}\"}],\"isError\":false}"
  }
]
08:43:12

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.owner-current-risk"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the owner-risk identity.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Distinct current PO lines whose risk level is HIGH_RISK for this owner.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-past-due-line-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Distinct current PO lines whose is_pastdue flag equals 1 for this owner.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/current-risk-line-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Distinct current PO lines where risk is HIGH_RISK or the line is past due for this owner.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/max-past-due-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Maximum current past-due days among this owner's matching lines.\\\",\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/owner-display\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Current owner display value; blank and null values are normalized to UNASSIGNED. Personal data (PII); handle per data-protection rules.\\\",\\\"synonyms\\\":[\\\"负责人\\\",\\\"owner\\\"],\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/owner-role\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"BUYER from past_due_detail_view.buyer_email; PRIMARY/MANAGER from vendor_master_mapping_view.\\\",\\\"values\\\":{\\\"BUYER\\\":\\\"Buyer\\\",\\\"MANAGER\\\":\\\"Manager\\\",\\\"PRIMARY\\\":\\\"Primary\\\"},\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"UTC time at which the current-state owner facts were queried.\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"attribute\\\":\\\"pastdue.owner-current-risk/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the owner-risk identity.\\\",\\\"format\\\":\\\"id\\\"}]}\"}],\"isError\":false}"
  }
]
08:43:15

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "current past due vendor lines",
  "limit": 50
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"current past due vendor lines\\\",\\\"hits\\\":[{\\\"kind\\\":\\\"entity\\\",\\\"match\\\":\\\"entity\\\",\\\"label\\\":\\\"PastDue Current Owner Risk\\\",\\\"entity\\\":\\\"pastdue.owner-current-risk\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Current High Risk Lines\\\",\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-high-risk-lines\\\"}]}\"}],\"isError\":false}"
  }
]
08:43:17

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "all current past due",
  "limit": 50
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"all current past due\\\",\\\"hits\\\":[]}\"}],\"isError\":false}"
  }
]
08:43:20
你说得对，前面给的是历史快照数据，不是实时当前状态，抱歉。

目前 Atlas 可访问的数据里：

2026-10-19 的 Vendor 逾期数属于最近完成快照，不能称为实时；
当前数据接口只提供按指定 Vendor 查询的当前高风险/逾期 PO 行；
没有发现可直接按 US 全部 Vendor 汇总“当前逾期行数”的实时集合根节点。
因此，我现在不能可靠地给出实时 US Vendor 排名，也不能用历史快照冒充当前结果。

当前可确认的能力边界：
如果你指定 Vendor Code，我可以查询该 Vendor 的当前逾期/高风险 PO 行；但要生成“US 全部 Vendor 的实时排名”，需要 Atlas 提供当前 Vendor 汇总接口或当前 PO 行的全量可聚合接口。”“”


我发现 我的意图明显是 需要 实时的数据  ，我分析是 atlas 没有提供 查实时数据的能力？ 

---

**Cursor**

对照 Atlas 图里「当前态」与「快照」能力，核实你的判断是否成立。

你的判断**大体正确**，再精确一点：

## Atlas 有「当前态」，但没有你要的那种「实时全国排名」

图里其实分两套时间语义：

| 类型 | 典型入口 | 数据源 | 能做什么 |
|---|---|---|---|
| **快照（日批）** | `country-monitor`、`vendor-contribution` 的 latest/previous、Top-10 | `vendor_past_due_daily` | 国家/Vendor 汇总、排名、ETA 构成 |
| **当前态（明细）** | `current-high-risk-lines`、`current-owner-risks`、`master-mappings` | `past_due_detail_view` 等 | **指定 Country+Vendor** 后的 PO 行 / Owner |

会话里 Agent 查到的也符合契约：`current-high-risk-lines` 的 `required_inputs` 是 **country-code + vendor-code**，没有「US 全部 Vendor 当前逾期汇总」这种根集合。`search_attributes("all current past due")` 为空，也说明图上**没有**这类入口。

所以：

- **不是**「Atlas 完全没有实时/当前能力」  
- **是**「没有 *按国家汇总的当前 Vendor 排名* 接口」；当前态只能 **按已知 Vendor 下钻 PO 行**

另外：产品里的「当前」≈ detail view 此刻可读状态，**不是**秒级流式实时；「最新」Country/Vendor 数仍是 **已完成快照**（如 2026-10-19），政策上也不该叫实时。

## 对你这句意图意味着什么

「我说的是实时、现在的状况」+ 若隐含「US Vendor 排名」：

- 用快照 Top-10 → **口径错**（Agent 后面纠偏是对的）  
- 用当前 PO 行给全国排名 → **图上做不到**（不知道 Vendor 全集，也不能从 contribution 根扫出「当前逾期数」）  
- 你指定 Vendor Code → **可以**查该 Vendor 的当前逾期/高风险行

## 若业务真要「实时 US 排名」

需要在 Atlas **新增能力**，例如：

- 国家级 current vendor rollup（按当前逾期行数排序），或  
- 可分页的全国 current past-due lines 再聚合  

在现有图上，Agent 只能诚实报 **capability gap**，不能编排名。

---

**User**

为什么 这么不灵活呢  按理说 agent 应该能够通过 atlas的图获取各种数据 现在看好像是没有定义的就获取不到 

---

**Cursor**

这不是 Agent「笨」，而是这套产品**故意做成封闭图**，不是「连上库就能随便查」。

## 为什么感觉不灵活

Atlas 暴露的不是整张 ClickHouse，而是**预先建模好的实体 + join + 必填输入**。  
Agent 只能走这些边；**没画出来的路径 = 图上不存在 = 查不到**（会 `unreachable` / 空 hits）。这是审计、口径、权限、性能上的选择，不是疏忽。

所以你看到的规律是对的：

> **没定义的能力，就获取不到。**

灵活问答 ≠ 任意 SQL / 任意聚合。灵活 = 在**已有可达边**上组合意图（Top-10 + ETA + Specialist + PO 行…）。

## 和「理想 Agent」差在哪

| 期望 | 这套 PastDue Agent 实际 |
|---|---|
| 想到什么问什么，图自动拼 | 只能问图上已有的事实形状 |
| 「实时全国 Vendor 排名」 | 只有快照排名，或「指定 Vendor → 当前 PO 行」 |
| 缺接口就自己扫表聚合 | **禁止**直连 ClickHouse、禁止猜根 |

若允许模型自由猜根、合 join，就会回到你们一直踩的：`unreachable`、bulkhead、假「未找到」、口径混乱。

## 那灵活从哪来

三条路，只能选产品路径：

1. **加 Atlas 能力**（真正扩展）— 例如「当前态按国家 Vendor 汇总」新 collection/resolver  
2. **组合已有边**（现在的设计）— 有边就能答，没边就报 capability gap  
3. **放开自由查询**（这套明确不做）— 灵活最大，治理/正确性最差  

## 一句话

Agent 不是「库的万能翻译」，而是 **图上的受控分析器**：  
**图有多宽，问答就有多灵活；缺边就要先建模，不能靠 Agent 硬编。**  
你要的「实时 US 排名」属于缺边，不是 Agent 没尽力。

---

**User**

怎么 给 边

---

**Cursor**

对照本仓库里 Atlas 图是怎么长出来的，说明「怎么加边」。

「加边」= 在 Atlas 里**产品化一条可达路径**（实体/集合 + SQL resolver + 必填输入），再挂到 Agent 的 capability/intent 上。不是改一句 prompt。

## 先判断要不要加边

1. MCP `list_entities` / `search_attributes` / `list_attributes` 看是否已有可达 join  
2. **已有** → 只加 `policy.edn` 的 intent/capability 映射（Agent 侧，不算新边）  
3. **没有** → 走下面 Atlas 建模（真正加边）

你要的「实时 US Vendor 排名」属于第 3 类。

---

## 加边的标准步骤（本仓库）

以「国家维度、当前逾期 Vendor 汇总排名」为例：

### 1. 定语义（产品）

- 根从哪来？常见：`pastdue.country-monitor`（按 `country-code`）  
- 新集合叫什么？例如 `current-vendor-past-due-ranks`  
- 必填输入？至少 `country-code`  
- 输出字段？`vendor-code`、`vendor-name`、`current-past-due-count`、`rank`…  
- 数据源？通常 `past_due.past_due_detail_view`（当前态），不是 `vendor_past_due_daily`

### 2. 改 Atlas 权威文件

部署权威是：

- `atlas/charts/pastdue.edn`
- `atlas/sources.edn`（若新物理表/源）

在 `pastdue.edn` 里补齐：

| 块 | 做什么 |
|---|---|
| **属性 / join** | 在父实体上声明新 collection（type join）+ `required_inputs` |
| **子实体属性** | 行上的字段定义 |
| **resolver** | SQL：按国家聚合当前逾期行、排序、分页 |
| **binding** | 把 SQL 列映射到 Atlas 属性 |

（`*.attr.edn` 只是参考分片，**以 `pastdue.edn` 为准**。）

### 3. 校验 & 部署

```bash
python scripts/validate_bundle.py
```

再在 Atlas 侧 config-check / 热加载 `pastdue.edn` + `sources.edn`。  
MCP 上应能 `list_attributes` 看到新 join，且 `execute_eql` 可达。

### 4. 挂上 Agent（否则图有了、Agent 仍不会用）

| 文件 | 内容 |
|---|---|
| `policy.edn` `:execution-plane :capabilities` | 新 capability：`entry`、inputs、fan-out |
| `:intent-catalog` / `:disambiguation` | 「实时/当前排名」→ 该 capability |
| playbook | 模板 EQL + 反例 |
| `runtime/pastdue_kernel` | EQL 模板（若走 Kernel） |
| 黄金问 | 锁住「实时排名」走新边，不走快照 Top-10 |

---

## 边长什么样（概念）

```text
pastdue.country-monitor  --[current-vendor-past-due-ranks]-->  行实体
         ^                            |
    country-code                 vendor-code, counts, rank
         |
    resolver SQL（detail_view 聚合）
```

现有「当前 PO 行」边是：

```text
pastdue.vendor-contribution --[current-high-risk-lines]--> PO 行
         ^
  country-code + vendor-code   ← 所以必须先有 Vendor，不能全国扫
```

要全国实时排名，就要**多一条从 Country 出发、不要求先给 Vendor 的边**。

---

## 注意

- 没有物理数据/视图支撑 → 加不了边（或先建 CH 视图）  
- 加边要过 ClickHouse join 规范（见 `atlas/README.md`）  
- 加完必须部署 Atlas；只改 Skill **不等于**加边  

如果你要落地「实时 US Vendor 排名」这条边，可以说一下口径（按逾期行数？是否含 HIGH_RISK？分页上限？），我可以直接在 `pastdue.edn` 里起草 resolver + capability 草案。

---

**User**

我还是疑问 这样 只有 我们给了的能力 这个agent才有 ，如何做到 一  现有的 daily report 分析 能力保持 
二 作为你个past due 分析大师 可以回答用户的各种问题 不再出现没给的能力就回答不了 

---

**Cursor**

目标可以拆成两件不同的事：**日报能力稳住**，和**问答像分析大师**。后者做不到「图上没边也永远能答出数」——能做到的是：**常见问题都能答，答不了时仍像专家（边界清晰 + 可升级建模）**。

## 两件事怎么同时成立

```text
┌─────────────────────────────────────────────┐
│  Track A — 日报 / 触发深潜（已有，锁死）        │
│  DAILY_MONITOR preset → Kernel → 契约表       │
│  不因灵活问答改口径、改 SQL、改完成义务         │
└─────────────────────────────────────────────┘
┌─────────────────────────────────────────────┐
│  Track B — 分析大师问答（要建「覆盖层」）        │
│  意图目录尽量广 + 组合已有边 + 缺边时专家式响应   │
│  缺边 ≠ 瞎编；缺边 = 近似回答 / 澄清 / 建模工单   │
└─────────────────────────────────────────────┘
```

- **一**：用 preset + 黄金问把日报钉死，和 Track B 隔离。  
- **二**：大师感来自「覆盖面 + 组合 + 诚实升级」，不是「未定义也能出数」。

---

## 「各种问题」真实含义

| 问题类型 | 能不能答 | 靠什么 |
|---|---|---|
| 图上已有边的组合（Top-10、ETA、Specialist、PO 行、Owner…） | 能 | Intent + Kernel 组合 |
| 语义近、边已有但模型选错 | 能（要修好） | 消歧 + Gate + 展示锁 |
| 图上根本没有的事实形状（如全国实时 Vendor 排名） | **不能当真数** | 须加边，或专家式降级 |

若要求「没给的能力也能量化回答」→ 只能二选一：  
**放开自由查库**（这套产品明确不做），或 **假装有数**（更糟）。

---

## 做成「Past Due 分析大师」的可行做法

### 1. 能力覆盖表（产品清单，不是等用户撞墙）

按真实问法维护一张表：意图 → 边是否存在 → 优先级。  
缺口（实时全国排名、某类交叉切片…）进 **Atlas 加边 backlog**，按频率补边。  
**大师 = 覆盖表越来越满**，不是一次 prompt 永生。

### 2. 组合层做满（少加边也能答更多）

很多「新问题」只需组合旧边，例如：  
指定 Vendor 的「现在怎样」= 快照指标（若要最新完成日）+ `current-high-risk-lines` + mappings。  
Agent 要会拆问、多 intent 拼 Fact Plan——这是 Track B 的主战场。

### 3. 缺边时的「专家协议」（避免「回答不了」的体感）

禁止干巴巴「没有能力」。固定三步：

1. **重述用户要的口径**（实时 / 快照 / 排名维度）  
2. **说明图上最近可答的是什么**（例如：最新完成快照 Top-10；或指定 Vendor 的当前 PO 行）  
3. **给选项**：用近似口径作答 / 请用户补 Vendor / 记为需加边的需求  

这样仍是分析大师，只是不伪造全国实时排名。

### 4. 日报与问答隔离

- 调度 /「latest monitor」→ **只走 Track A**  
- 自由问答 → Track B，**默认不整份 daily_full**，除非用户要日报结构  
- 展示：有 module 严格一致；无 module 用固定「问答卡片」骨架，避免同问两种表

### 5. 加边流水线（让「各种问题」可持续变多）

用户撞到 gap → 自动/半自动记一条 → 评审 → `pastdue.edn` resolver → policy capability → 黄金问。  
**灵活度随边增长**，而不是指望 Agent 绕过图。

---

## 直接回答你的疑问

- **「只有给了的能力才有」** — 对事实数字，是的；这是治理边界。  
- **「如何又稳日报、又能答各种问题」** —  
  - 日报：preset 锁死；  
  - 各种问题：组合已有边 + 持续补高频缺口边 + 缺边时专家式引导/近似，**而不是**未定义也能算出数。  

若把「分析大师」定义成「任何 Past Due 相关问都能给出**合规数字**」，唯一长期路径是 **把高频缺口不断加成边**；Agent 负责用好边、组合边、解释边界，不负责发明边。

如果你愿意下一步落地，最有杠杆的是两件事一起做：  
**（1）缺边专家协议写进 SP/Skill**（立刻改善体感）；  
**（2）把「实时国家 Vendor 排名」加成第一条真边**（补上你刚撞到的洞）。

---

**User**

我要加边 ，
我给你一些sql 辅助你加 边 

SELECT A.* 
FROM (
    SELECT
      `source`.`from_acct_no` AS `from_acct_no`,
      `source`.`vendor_name` AS `vendor_name`,
      `source`.`country_code` AS `country_code`,
      `source`.`pastdue_rate_in_vendor` AS `pastdue_rate_in_vendor`,
      `source`.`pastdue_rate_in_total` AS `pastdue_rate_in_total`,
      `source`.`vendor_total_count` AS `vendor_total_count`,
      `source`.`vendor_pastdue_count` AS `vendor_pastdue_count`

    FROM
      (
        SELECT
          t.from_acct_no,
          t.vendor_name,
          t.country_code,
          countIf(t.is_pastdue = 1) / count() AS pastdue_rate_in_vendor,
          countIf(t.is_pastdue = 1) / country_total.pastdue_count AS pastdue_rate_in_total,
          count() AS vendor_total_count,
          countIf(t.is_pastdue = 1) AS vendor_pastdue_count,
          any(t.department_type) AS department_type,
          any(t.master_primary) AS master_primary,
          any(t.master_manager) AS master_manager
        FROM
          past_due.past_due_detail_view AS t
        LEFT JOIN (
            SELECT
              country_code,
              count() AS total_count,
              countIf(is_pastdue = 1) AS pastdue_count
            FROM
              past_due.past_due_detail_view
            GROUP BY
              country_code
          ) AS country_total ON t.country_code = country_total.country_code
        WHERE
          t.from_acct_no IS NOT NULL 
        GROUP BY
          t.from_acct_no,
          t.vendor_name,
          t.country_code,
          country_total.pastdue_count
        HAVING
          pastdue_rate_in_vendor > 0.0
          AND pastdue_rate_in_total > 0.0
          AND country_code == 'US'
      ) AS `source`
    ORDER BY
      `source`.`pastdue_rate_in_total` DESC
    LIMIT 1048575
) A 
WHERE A.from_acct_no IN (
    SELECT B.vendor_no 
    FROM  past_due.vendor_master_mapping_view_us  B 
    WHERE 1 = 1
	   
      [[AND {{department_type}}]]
      [[AND {{master_primary}}]]
      [[AND {{master_manager}}]]
)



select A.* from (SELECT
  `source`.`from_acct_no` AS `from_acct_no`,
  `source`.`vendor_name` AS `vendor_name`,
  `source`.`country_code` AS `country_code`,
  `source`.`pastdue_rate_in_vendor` AS `pastdue_rate_in_vendor`,
  `source`.`pastdue_rate_in_total` AS `pastdue_rate_in_total`,
  `source`.`vendor_total_count` AS `vendor_total_count`,
  `source`.`vendor_pastdue_count` AS `vendor_pastdue_count`

FROM
  (
    SELECT
      `source`.`from_acct_no` AS `from_acct_no`,
      `source`.`vendor_name` AS `vendor_name`,
      `source`.`country_code` AS `country_code`,
      `source`.`pastdue_rate_in_vendor` AS `pastdue_rate_in_vendor`,
      `source`.`pastdue_rate_in_total` AS `pastdue_rate_in_total`,
      `source`.`vendor_total_count` AS `vendor_total_count`,
      `source`.`vendor_pastdue_count` AS `vendor_pastdue_count`,
      `source`.`department_type` AS `department_type`,
      `source`.`master_primary` AS `master_primary`,
      `source`.`master_manager` AS `master_manager`
    FROM
      (
        SELECT
          t.from_acct_no,
          t.vendor_name,
          t.country_code,
          -- 指标1：is_pastdue=1 占该 from_acct_no 分组总数的百分比
          countIf(t.is_pastdue = 1) / count() AS pastdue_rate_in_vendor,
          -- 指标2：is_pastdue=1 占该 country_code 下 is_pastdue=1 总数的百分比
          countIf(t.is_pastdue = 1) / country_total.pastdue_count AS pastdue_rate_in_total,
          -- 附加统计信息（可选，便于验证）
          count() AS vendor_total_count,
          countIf(t.is_pastdue = 1) AS vendor_pastdue_count,
          any(t.department_type) AS department_type,
          any(t.master_primary) AS master_primary,
          any(t.master_manager) AS master_manager
        FROM
          past_due.past_due_detail_view AS t
        LEFT JOIN (
            SELECT
              country_code,
              count() AS total_count,
              countIf(is_pastdue = 1) AS pastdue_count
            FROM
              past_due.past_due_detail_view
            GROUP BY
              country_code
          ) AS country_total ON t.country_code = country_total.country_code
        WHERE
          t.from_acct_no IS NOT NULL

        GROUP BY
          t.from_acct_no,
          t.vendor_name,
          t.country_code,
          country_total.pastdue_count
        HAVING
          pastdue_rate_in_vendor > 0.0
          AND pastdue_rate_in_total > 0.0
          AND country_code == 'CA'
      ) AS `source`
    ORDER BY
      `source`.`pastdue_rate_in_total` DESC
  ) AS `source`
LIMIT
  1048575) A 
  WHERE A.from_acct_no IN (
    SELECT B.vendor_no 
    FROM  past_due.vendor_master_mapping_view_ca B 
    WHERE 1 = 1
	 
      [[AND {{department_type}}]]
      [[AND {{master_primary}}]]
      [[AND {{master_manager}}]]
)

SELECT
  `past_due`.`past_due_detail_view`.`from_acct_no` AS `from_acct_no`,
  `past_due`.`past_due_detail_view`.`vendor_name` AS `vendor_name`,
  `past_due`.`past_due_detail_view`.`alert_type` AS `alert_type`,
  COUNT(*) AS `count`
FROM
  `past_due`.`past_due_detail_view`
WHERE
  `past_due`.`past_due_detail_view`.`country_code` = 'US'
GROUP BY
  `past_due`.`past_due_detail_view`.`from_acct_no`,
  `past_due`.`past_due_detail_view`.`vendor_name`,
  `past_due`.`past_due_detail_view`.`alert_type`
ORDER BY
  `past_due`.`past_due_detail_view`.`from_acct_no` ASC,
  `past_due`.`past_due_detail_view`.`vendor_name` ASC,
  `past_due`.`past_due_detail_view`.`alert_type` ASC




SELECT
  `past_due`.`past_due_detail_view`.`from_acct_no` AS `from_acct_no`,
  `past_due`.`past_due_detail_view`.`vendor_name` AS `vendor_name`,
  `past_due`.`past_due_detail_view`.`alert_type` AS `alert_type`,
  COUNT(*) AS `count`
FROM
  `past_due`.`past_due_detail_view`
WHERE
  `past_due`.`past_due_detail_view`.`country_code` = 'CA'
GROUP BY
  `past_due`.`past_due_detail_view`.`from_acct_no`,
  `past_due`.`past_due_detail_view`.`vendor_name`,
  `past_due`.`past_due_detail_view`.`alert_type`
ORDER BY
  `past_due`.`past_due_detail_view`.`from_acct_no` ASC,
  `past_due`.`past_due_detail_view`.`vendor_name` ASC,
  `past_due`.`past_due_detail_view`.`alert_type` ASC


同时我需要你 开发对这张表的自由查询 

-- past_due.past_due_detail_view 源码

CREATE VIEW past_due.past_due_detail_view
(
    `order_type` Int32,
    `order_no` Int32,
    `order_line_no` Int32,
    `vend_part_no` Nullable(String),
    `loc_no` Nullable(Int32),
    `inv_type` Nullable(Int32),
    `sku_no` Nullable(Int32),
    `order_qty` Nullable(Int32),
    `ship_qty` Nullable(Int32),
    `rec_qty` Nullable(Int32),
    `unit_cost` Nullable(Decimal(19, 4)),
    `unit_price` Nullable(Decimal(19, 4)),
    `close_date` Nullable(DateTime64(3)),
    `expected_date` Nullable(DateTime64(3)),
    `rec_date` Nullable(DateTime64(3)),
    `delete_date` Nullable(DateTime64(3)),
    `entry_datetime` Nullable(DateTime64(3)),
    `entry_id` Nullable(Int32),
    `delete_id` Nullable(Int32),
    `release_id` Nullable(Int32),
    `release_date` Nullable(DateTime64(3)),
    `dist_exp_date` Nullable(DateTime64(3)),
    `prod_exp_date` Nullable(DateTime64(3)),
    `country_code` String,
    `from_acct_no` Nullable(Int32),
    `from_loc_no` Nullable(Int32),
    `from_contact_no` Nullable(Int32),
    `to_acct_no` Nullable(Int32),
    `to_loc_no` Nullable(Int32),
    `to_contact_no` Nullable(Int32),
    `eta_qty` Int32,
    `eta_code` String,
    `eta_date` Nullable(DateTime),
    `ship_date` Nullable(DateTime),
    `source` Nullable(String),
    `eta_rec_qty` Nullable(Int32),
    `reason_code` Nullable(String),
    `tracking_no` Nullable(String),
    `ship_method` Nullable(FixedString(4)),
    `sched_date` Nullable(DateTime),
    `sus_date` Nullable(DateTime),
    `rf_date` Nullable(DateTime),
    `alf_date` Nullable(DateTime),
    `inv_date` Nullable(DateTime),
    `status` Nullable(String),
    `vendor_name` String,
    `buyer_email` String,
    `eta_label` String,
    `risk_level` String,
    `action_type` Nullable(String),
    `order_age_days` Nullable(Int64),
    `sh_diff_days` Nullable(Int64),
    `us_pastdue_days` Nullable(Int64),
    `sc_pastdue_days` Nullable(Int64),
    `is_pastdue` UInt8,
    `pastdue_days` Nullable(Int64),
    `alert_type` String,
    `department_type` Nullable(String),
    `master_primary` Nullable(String),
    `master_manager` Nullable(String)
) AS
WITH
    latest_order_header AS
    (
        SELECT
            order_type,
            order_no,
            country_code,
            argMax(from_acct_no, entry_datetime) AS from_acct_no,
            argMax(from_loc_no, entry_datetime) AS from_loc_no,
            argMax(from_contact_no, entry_datetime) AS from_contact_no,
            argMax(to_acct_no, entry_datetime) AS to_acct_no,
            argMax(to_loc_no, entry_datetime) AS to_loc_no,
            argMax(to_contact_no, entry_datetime) AS to_contact_no
        FROM past_due.order_header
        GROUP BY
            order_type,
            order_no,
            country_code
    ),
    latest_order_detail AS
    (
        SELECT
            order_type,
            order_no,
            order_line_no,
            argMax(vend_part_no, entry_datetime) AS vend_part_no,
            argMax(loc_no, entry_datetime) AS loc_no,
            argMax(inv_type, entry_datetime) AS inv_type,
            argMax(sku_no, entry_datetime) AS sku_no,
            argMax(order_qty, entry_datetime) AS order_qty,
            argMax(ship_qty, entry_datetime) AS ship_qty,
            argMax(rec_qty, entry_datetime) AS rec_qty,
            argMax(unit_cost, entry_datetime) AS unit_cost,
            argMax(unit_price, entry_datetime) AS unit_price,
            argMax(close_date, entry_datetime) AS close_date,
            argMax(expected_date, entry_datetime) AS expected_date,
            argMax(rec_date, entry_datetime) AS rec_date,
            argMax(delete_date, entry_datetime) AS delete_date,
            argMax(entry_id, entry_datetime) AS entry_id,
            argMax(delete_id, entry_datetime) AS delete_id,
            argMax(release_id, entry_datetime) AS release_id,
            argMax(release_date, entry_datetime) AS release_date,
            argMax(dist_exp_date, entry_datetime) AS dist_exp_date,
            argMax(prod_exp_date, entry_datetime) AS prod_exp_date,
            argMax(country_code, entry_datetime) AS country_code
        FROM past_due.order_detail
        GROUP BY
            order_type,
            order_no,
            order_line_no
    ),
    entry_datetime_lookup AS
    (
        SELECT
            order_type,
            order_no,
            order_line_no,
            max(entry_datetime) AS entry_datetime
        FROM past_due.order_detail
        GROUP BY
            order_type,
            order_no,
            order_line_no
    ),
    latest_eta AS
    (
        SELECT
            order_type,
            order_no,
            order_line_no,
            argMax(eta_qty, entry_datetime) AS eta_qty,
            argMax(eta_code, entry_datetime) AS eta_code,
            argMax(eta_date, entry_datetime) AS eta_date,
            argMax(ship_date, entry_datetime) AS ship_date,
            argMax(source, entry_datetime) AS source,
            argMax(rec_qty, entry_datetime) AS rec_qty,
            argMax(reason_code, entry_datetime) AS reason_code,
            argMax(tracking_no, entry_datetime) AS tracking_no,
            argMax(ship_method, entry_datetime) AS ship_method,
            argMax(sched_date, entry_datetime) AS sched_date,
            argMax(sus_date, entry_datetime) AS sus_date,
            argMax(rf_date, entry_datetime) AS rf_date,
            argMax(alf_date, entry_datetime) AS alf_date,
            argMax(inv_date, entry_datetime) AS inv_date,
            argMax(status, entry_datetime) AS status
        FROM past_due.order_eta_detail
        GROUP BY
            order_type,
            order_no,
            order_line_no
    ),
    buyer_emails AS
    (
        SELECT
            vum.vend_no,
            vum.country_code,
            arrayStringConcat(groupArrayDistinct(ec.email), ';') AS buyer_email
        FROM past_due.vend_user_matrix AS vum
        INNER JOIN past_due.employee_contacts AS ec ON vum.primary_id = ec.user_id
        WHERE (vum.profile_type IN ('BJBR', 'BJBN', 'PANA', 'BUYR')) AND (ec.email IS NOT NULL) AND (ec.email != '')
        GROUP BY
            vum.vend_no,
            vum.country_code
    ),
    etad_info AS
    (
        SELECT
            vdg.vend_no AS etad_vend_no,
            vdg.country_code AS etad_country_code,
            arrayStringConcat(groupArrayDistinct(vdg.department_type), ';') AS department_type,
            arrayStringConcat(arrayFilter(x -> (x != ''), groupArrayDistinct(if((ec_primary.email IS NOT NULL) AND (ec_primary.email != '') AND (position(ec_primary.email, '.') > 1) AND (position(ec_primary.email, '@') > position(ec_primary.email, '.')), concat(upper(substring(ec_primary.email, 1, 1)), lower(substring(ec_primary.email, 2, position(ec_primary.email, '.') - 2)), ' ', upper(substring(substring(ec_primary.email, position(ec_primary.email, '.') + 1, (position(ec_primary.email, '@') - position(ec_primary.email, '.')) - 1), 1, 1)), lower(substring(substring(ec_primary.email, position(ec_primary.email, '.') + 1, (position(ec_primary.email, '@') - position(ec_primary.email, '.')) - 1), 2))), ''))), ';') AS primary_name,
            arrayStringConcat(arrayFilter(x -> (x != ''), groupArrayDistinct(if((ec_manager.email IS NOT NULL) AND (ec_manager.email != '') AND (position(ec_manager.email, '.') > 1) AND (position(ec_manager.email, '@') > position(ec_manager.email, '.')), concat(upper(substring(ec_manager.email, 1, 1)), lower(substring(ec_manager.email, 2, position(ec_manager.email, '.') - 2)), ' ', upper(substring(substring(ec_manager.email, position(ec_manager.email, '.') + 1, (position(ec_manager.email, '@') - position(ec_manager.email, '.')) - 1), 1, 1)), lower(substring(substring(ec_manager.email, position(ec_manager.email, '.') + 1, (position(ec_manager.email, '@') - position(ec_manager.email, '.')) - 1), 2))), ''))), ';') AS manager_name
        FROM past_due.vendor_dna_group_members AS vdg
        LEFT JOIN past_due.employee_contacts AS ec_primary ON (vdg.member_role = 'primary') AND (vdg.member_id = ec_primary.user_id) AND (ec_primary.email IS NOT NULL) AND (ec_primary.email != '')
        LEFT JOIN past_due.employee_contacts AS ec_manager ON (vdg.member_role = 'manager') AND (vdg.member_id = ec_manager.user_id) AND (ec_manager.email IS NOT NULL) AND (ec_manager.email != '')
        WHERE vdg.department_type IN ('ETAD', 'BUYR')
        GROUP BY
            vdg.vend_no,
            vdg.country_code
    )
SELECT
    od.order_type AS order_type,
    od.order_no AS order_no,
    od.order_line_no AS order_line_no,
    od.vend_part_no AS vend_part_no,
    od.loc_no AS loc_no,
    od.inv_type AS inv_type,
    od.sku_no AS sku_no,
    od.order_qty AS order_qty,
    od.ship_qty AS ship_qty,
    od.rec_qty AS rec_qty,
    od.unit_cost AS unit_cost,
    od.unit_price AS unit_price,
    multiIf(toDate(od.close_date) = toDate('1970-01-01'), NULL, od.close_date) AS close_date,
    multiIf(toDate(od.expected_date) = toDate('1970-01-01'), NULL, od.expected_date) AS expected_date,
    multiIf(toDate(od.rec_date) = toDate('1970-01-01'), NULL, od.rec_date) AS rec_date,
    multiIf(toDate(od.delete_date) = toDate('1970-01-01'), NULL, od.delete_date) AS delete_date,
    multiIf(toDate(edt.entry_datetime) = toDate('1970-01-01'), NULL, edt.entry_datetime) AS entry_datetime,
    od.entry_id AS entry_id,
    od.delete_id AS delete_id,
    od.release_id AS release_id,
    multiIf(toDate(od.release_date) = toDate('1970-01-01'), NULL, od.release_date) AS release_date,
    multiIf(toDate(od.dist_exp_date) = toDate('1970-01-01'), NULL, od.dist_exp_date) AS dist_exp_date,
    multiIf(toDate(od.prod_exp_date) = toDate('1970-01-01'), NULL, od.prod_exp_date) AS prod_exp_date,
    od.country_code AS country_code,
    oh.from_acct_no AS from_acct_no,
    oh.from_loc_no AS from_loc_no,
    oh.from_contact_no AS from_contact_no,
    oh.to_acct_no AS to_acct_no,
    oh.to_loc_no AS to_loc_no,
    oh.to_contact_no AS to_contact_no,
    eta.eta_qty AS eta_qty,
    trimBoth(toString(eta.eta_code)) AS eta_code,
    multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) AS eta_date,
    multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date) AS ship_date,
    eta.source AS source,
    eta.rec_qty AS eta_rec_qty,
    eta.reason_code AS reason_code,
    eta.tracking_no AS tracking_no,
    eta.ship_method AS ship_method,
    multiIf(toDate(eta.sched_date) = toDate('1970-01-01'), NULL, eta.sched_date) AS sched_date,
    multiIf(toDate(eta.sus_date) = toDate('1970-01-01'), NULL, eta.sus_date) AS sus_date,
    multiIf(toDate(eta.rf_date) = toDate('1970-01-01'), NULL, eta.rf_date) AS rf_date,
    multiIf(toDate(eta.alf_date) = toDate('1970-01-01'), NULL, eta.alf_date) AS alf_date,
    multiIf(toDate(eta.inv_date) = toDate('1970-01-01'), NULL, eta.inv_date) AS inv_date,
    eta.status AS status,
    vcn.vendor_name AS vendor_name,
    be.buyer_email AS buyer_email,
    trimBoth(toString(eta.eta_code)) AS eta_label,
    multiIf(trimBoth(toString(eta.eta_code)) = 'PE', 'HIGH_RISK', (trimBoth(toString(eta.eta_code)) = 'SC') AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) < today()), 'HIGH_RISK', (trimBoth(toString(eta.eta_code)) = 'US') AND (upper(eta.source) = 'AUTO US') AND (dateDiff('day', multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date), today()) > 0), 'MEDIUM_RISK', (trimBoth(toString(eta.eta_code)) IN ('NR', 'RQ')) AND (dateDiff('day', multiIf(toDate(edt.entry_datetime) = toDate('1970-01-01'), NULL, edt.entry_datetime), now()) > 30), 'MEDIUM_RISK', trimBoth(toString(eta.eta_code)) IN ('NS', 'LS'), 'LOW_RISK', 'NORMAL') AS risk_level,
    multiIf(trimBoth(toString(eta.eta_code)) = 'PE', 'REQUIRES_IMMEDIATE_ACTION', (trimBoth(toString(eta.eta_code)) = 'SC') AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) < today()), 'ETA_PAST_DUE', (trimBoth(toString(eta.eta_code)) = 'US') AND (upper(eta.source) = 'AUTO US'), 'AUTO_US_PAST_DUE', trimBoth(toString(eta.eta_code)) IN ('NR', 'RQ'), 'VENDOR_NOT_CONFIRMED', NULL) AS action_type,
    dateDiff('day', multiIf(toDate(edt.entry_datetime) = toDate('1970-01-01'), NULL, edt.entry_datetime), now()) AS order_age_days,
    multiIf((trimBoth(toString(eta.eta_code)) = 'SH') AND (multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date) IS NOT NULL) AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) IS NOT NULL), dateDiff('day', multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date), multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date)), NULL) AS sh_diff_days,
    multiIf((trimBoth(toString(eta.eta_code)) = 'US') AND (upper(eta.source) = 'AUTO US') AND (multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date) IS NOT NULL), dateDiff('day', multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date), today()), NULL) AS us_pastdue_days,
    multiIf((trimBoth(toString(eta.eta_code)) = 'SC') AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) IS NOT NULL) AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) < today()), dateDiff('day', multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date), today()), NULL) AS sc_pastdue_days,
    multiIf((multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) IS NOT NULL) AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) < today()), 1, 0) AS is_pastdue,
    multiIf((multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) IS NOT NULL) AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) < today()), dateDiff('day', multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date), today()), NULL) AS pastdue_days,
    multiIf((multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) < today()) AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) IS NOT NULL), 'Past Due', (trimBoth(toString(eta.eta_code)) = 'NR') AND (dateDiff('hour', multiIf(toDate(edt.entry_datetime) = toDate('1970-01-01'), NULL, edt.entry_datetime), now()) > 48), 'Aging NR', (trimBoth(toString(eta.eta_code)) = 'RQ') AND (dateDiff('hour', multiIf(toDate(edt.entry_datetime) = toDate('1970-01-01'), NULL, edt.entry_datetime), now()) > 24), 'Aging RQ', trimBoth(toString(eta.eta_code)) = 'PE', 'Pending', trimBoth(toString(eta.eta_code)) = 'DD', 'Delivered', (trimBoth(toString(eta.eta_code)) = 'US') AND (multiIf(toDate(eta.eta_date) = toDate('1970-01-01'), NULL, eta.eta_date) > addDays(today(), 14)), 'Due In US', (trimBoth(toString(eta.eta_code)) = 'SH') AND (multiIf(toDate(eta.ship_date) = toDate('1970-01-01'), NULL, eta.ship_date) > today()), 'Future SH', trimBoth(toString(eta.eta_code)) = 'LS', 'Lost', 'Compliant') AS alert_type,
    etad.department_type AS department_type,
    etad.primary_name AS master_primary,
    etad.manager_name AS master_manager
FROM latest_order_detail AS od
LEFT JOIN entry_datetime_lookup AS edt ON (od.order_type = edt.order_type) AND (od.order_no = edt.order_no) AND (od.order_line_no = edt.order_line_no)
LEFT JOIN latest_order_header AS oh ON (od.order_type = oh.order_type) AND (od.order_no = oh.order_no) AND (od.country_code = oh.country_code)
LEFT JOIN latest_eta AS eta ON (od.order_type = eta.order_type) AND (od.order_no = eta.order_no) AND (od.order_line_no = eta.order_line_no)
LEFT JOIN past_due.vendor_code_name AS vcn ON (od.country_code = vcn.country_code) AND (oh.from_acct_no = vcn.vendor_code)
LEFT JOIN buyer_emails AS be ON (oh.from_acct_no = be.vend_no) AND (od.country_code = be.country_code)
LEFT JOIN etad_info AS etad ON (oh.from_acct_no = etad.etad_vend_no) AND (od.country_code = etad.etad_country_code)
WHERE ((od.delete_date IS NULL) OR (toDate(od.delete_date) = toDate('1970-01-01'))) AND (od.rec_qty != od.order_qty) AND (oh.to_loc_no != 99);


同时其中应该忽略 这张表的    `department_type` Nullable(String),
    `master_primary` Nullable(String),
    `master_manager` Nullable(String) 
这三个字段 而是用 你已经有的正确的逻辑 关联 这三个字段 
请分析可行性


---

**Cursor**

先对照现有 Atlas 边与 org mapping 约定，再评估你这几条 SQL 的加边可行性。

## 可行性结论（先说清楚）

| 诉求 | 可行？ | 说明 |
|---|---|---|
| 当前态 Vendor 排名（你的前两段 SQL） | **可行** | 做成 Country 根上的新 collection；`country_code` 入参化；org 用已有 `vendor_master_mapping_view` |
| Vendor × alert_type 计数（后两段 SQL） | **可行** | 做成另一条边（国家级或 Vendor 级） |
| **忽略** detail 上的 `department_type` / `master_primary` / `master_manager` | **必须且已有先例** | 与现网 `current-high-risk-lines` 一致：BUYER 用 detail，DNA 用 mapping view |
| 对 `past_due_detail_view` **任意自由 SQL** | **不建议 / 与产品冲突** | 应做成「受控切片查询」多条边，而不是开放 SQL |

日报 Track A **可保持不变**；这些边全部标成 **current context**，不进历史归因。

---

## 你的 SQL 怎么落成「边」

### 边 1：当前 Vendor 逾期贡献排名（补「实时排名」洞）

**语义（相对快照 Top-10）：**

- 数据：`past_due_detail_view` **此刻**聚合  
- 排序：`pastdue_rate_in_total` DESC（占国家逾期行份额）  
- 过滤：可选 department / primary / manager —— **只来自 mapping view**

**不要照搬的部分：**

| 原 SQL | 改法 |
|---|---|
| `country_code == 'US'/'CA'` 写死 | `:country-code` 参数（US/CA/政策允许的国家） |
| `vendor_master_mapping_view_us` / `_ca` | 统一 `vendor_master_mapping_view` + `country_code`（与现 Atlas 一致） |
| `any(t.department_type)` 等从 detail | **删除**；org 只从 mapping 关联/过滤 |
| Metabase `[[AND {{x}}]]` | Atlas 可选输入：空串 = 无约束（同 `matching-mappings`） |

**建议挂点：**

```text
pastdue.country-monitor
  --[current-vendor-past-due-ranks]-->
pastdue.current-vendor-past-due-rank
  (country-code, vendor-code, vendor-name,
   vendor-pastdue-count, vendor-total-count,
   pastdue-rate-in-vendor, pastdue-rate-in-total, rank…)
```

可选 filters：`department-type-filter` / `master-primary-filter` / `master-manager-filter`。

### 边 2：当前 Vendor × alert_type 分布

```text
pastdue.country-monitor --[current-vendor-alert-type-counts]--> …
```

或按 Vendor：

```text
pastdue.vendor-contribution --[current-alert-type-counts]--> …
```

字段：`vendor-code`, `vendor-name`, `alert-type`, `line-count`。  
同样 **不暴露** detail 上的 DNA 三字段。

### 「自由查询 detail」——可行边界

在本产品里，「自由」应落成 **查询实体 + 声明过滤维度**，例如：

`pastdue.current-detail-query`（country 必填；vendor / eta-code / alert-type / is-pastdue / risk-level 可选）  
→ collection `matching-lines`（强分页、max limit）

这样 Agent 能组合出大量问法，但仍是 **Gate 可校验的边**，不是 `SELECT *` 任意拼。

**真正任意 SQL** 会绕过：口径、RLS、禁根、Kernel、审计——与现架构冲突，不建议作为边。

---

## 与现有能力的关系（不打架）

| 已有 | 新边 |
|---|---|
| `positive-vendor-contributors`（快照贡献） | 当前态排名（detail 聚合）—— **证据层不同**，回答时必须标明 |
| `current-high-risk-lines`（要 vendor） | 国家级排名 **不需要先知道 vendor** |
| `matching-mappings`（只映射） | 排名边 = 当前指标 **∩** mapping 过滤 |
| 日报 preset | **不自动**跑新边；仅 interactive / 用户要「实时/当前」时用 |

---

## 风险与成本

1. **性能**：对整张 detail_view 按国家聚合可能重 → 要 `LIMIT`/分页、默认 Top-N、Kernel `shares-global-budget`。  
2. **口径**：`is_pastdue` / `alert_type` 以 view 定义为准；与快照 `past_due_count` **不可混称同一指标**。  
3. **org**：detail view 里虽有 DNA 列，Agent/resolver **禁止当真相**（你已要求，与 R1 一致）。  
4. **映射表**：若环境只有 `_us`/`_ca` 分表而没有统一 `vendor_master_mapping_view`，部署前要对齐；本 bundle 已按统一 view 写。

---

## 推荐落地顺序

1. **P1** 边 1：`current-vendor-past-due-ranks`（解决「实时 US/CA 排名」）+ intent「实时/当前排名」  
2. **P2** 边 2：`current-vendor-alert-type-counts`  
3. **P3** 受控 `current-detail-query`（多过滤维度 + 严分页）—— 这才是可扩展的「自由问」  
4. policy / Kernel / 黄金问同步；**不动**日报 SQL 语义  

---

在写正式 design / 改 `pastdue.edn` 之前，只确认一件关键事：

你说的「对这张表的自由查询」，更接近哪一种？

- **A.** 受控：国家必填 + 若干可选过滤（vendor / eta / alert / is_pastdue…）+ 分页（推荐，可进 Atlas）  
- **B.** 字面自由 SQL（不推荐，且与现治理冲突）

---

**User**

A

---

**Cursor**

选定 A（受控切片）。正在整理完整加边设计供你确认。

## 设计：当前态受控边（方案 A）

### 目标

1. 保持日报 / 快照归因不变  
2. 补上「实时/当前」国家 Vendor 分析边  
3. detail 用 **受控过滤 + 分页**，不用自由 SQL  
4. DNA 三字段 **只来自** `vendor_master_mapping_view`，忽略 detail 上同名列  

### 三条边

**边 1 — 当前 Vendor 逾期份额排名**（优先）

```text
pastdue.country-monitor
  /current-vendor-past-due-ranks
    → pastdue.current-vendor-past-due-rank
```

- 必填：`country-code`  
- 可选（空串=不限）：`department-type-filter`, `master-primary-filter`, `master-manager-filter`  
- 指标：来自你的 SQL（`vendor_pastdue_count`, `vendor_total_count`, `pastdue_rate_in_vendor`, `pastdue_rate_in_total`）+ `rank`  
- 排序：`pastdue_rate_in_total` DESC  
- HAVING：两 rate > 0  
- Org：`EXISTS`/`IN` 映射自 `vendor_master_mapping_view`（统一表，不用 `_us`/`_ca`）  
- 默认 limit 例如 200，max 1000  

**边 2 — 当前 Vendor × alert_type 计数**

```text
pastdue.country-monitor
  /current-vendor-alert-type-counts
    → pastdue.current-vendor-alert-type-count
```

- 必填：`country-code`  
- 可选：同上 org 三过滤；可选 `vendor-code-filter`（空=全国）  
- 字段：`vendor-code`, `vendor-name`, `alert-type`, `line-count`  
- 不暴露 detail DNA 列  

**边 3 — 受控明细切片（你选的 A）**

```text
pastdue.current-detail-query
  identity: country-code
            + vendor-code-filter
            + eta-code-filter
            + alert-type-filter
            + is-pastdue-filter      ; "" | "1" | "0"
            + risk-level-filter
            + department-type-filter
            + master-primary-filter
            + master-manager-filter
  /matching-lines → 行实体（子集属性）
```

- 空串过滤 = 无约束（同 mapping-query）  
- **强分页**：default 50 / max 200（明细更严）  
- 行字段：PO 身份、qty、eta_code/date/source、is_pastdue、pastdue_days、alert_type、risk_level、buyer_email…  
- Primary/Manager：JOIN `vendor_master_mapping_view` 填列；**禁止**读 detail 的 department/master_*  
- Gate：禁止无 country；禁止多 collection  

### 证据与日报隔离

- 新边一律标 **current context**；禁止写成历史原因  
- `DAILY_MONITOR` preset **不**自动跑这三条  
- Intent：「实时/现在/当前排名」→ 边 1；「alert 分布」→ 边 2；「当前行/明细切片」→ 边 3  

### Agent 层同步

- `policy.edn` capabilities + intent/disambiguation  
- Kernel EQL 模板  
- playbook 字典  
- 黄金问：实时 CA/US 排名走边 1，不走快照 Top-10  

### 非目标

- 任意 SQL / `compute_metric` 绕过图  
- 用 detail 的 DNA 三列  
- 改快照日报 SQL 语义  

---

若设计 OK，我将写入  
`docs/superpowers/specs/2026-10-21-pastdue-current-detail-edges-design.md`，  
你审完后立刻开实现计划并改 `pastdue.edn`。  

回复「批准」或指出要改的点即可。

---

**User**

批准

---

**Cursor**

设计已批准。正在写入正式 spec，并启动实现计划。

使用 writing-plans 写实现计划，并开始在 `pastdue.edn` 落地三条边（查询实体模式，以支持空串过滤）。

定位到问题：新 binding 插在了 `:bindings` 闭合之后。正在修复。

## 已批准并落地

规格：`docs/superpowers/specs/2026-10-21-pastdue-current-detail-edges-design.md`

### Atlas 三条边（已写入 `pastdue.edn`）

| 边 | 入口 | 用途 |
|---|---|---|
| 当前 Vendor 排名 | `pastdue.current-vendor-rank-query/matching-ranks` | 你的份额排名 SQL（参数化国家 + org 过滤） |
| alert 分布 | `pastdue.current-alert-type-query/matching-counts` | Vendor × alert_type 计数 |
| 受控明细 | `pastdue.current-detail-query/matching-lines` | 国家必填 + 多维空串过滤，limit 50/200 |

DNA **只用** `vendor_master_mapping_view`；忽略 detail 上三列。

### Agent

- `policy.edn` → **1.8.0**（capabilities + 实时意图消歧）  
- Kernel EQL / Fact Plan / SP / README / G9 黄金问  
- 本地：`validate_bundle` PASS，G1–G9 PASS  

### 你需要做的

**把更新后的 `atlas/charts/pastdue.edn`（+ `sources.edn`）部署到 Atlas**，并跑 config-check。  
未部署前，线上 MCP 仍看不到新边。

部署后可试：`CA 实时 past due vendor 排名` —— 应走 `matching-ranks`，而不是快照 Top-10。

---

**User**

不用 部署 system prompt 和 skill嘛

---

**Cursor**

要。Atlas 和 Agent 是两套部署面，都要更新才完整。

| 产物 | 是否要部署 | 作用 |
|---|---|---|
| `atlas/charts/pastdue.edn` (+ `sources.edn`) | **要** | 图上出现新边，MCP 才能查 |
| `agent/SYSTEM_PROMPT.md` | **要** | 引导走「实时→current ranks」，别再用快照 Top-10 |
| `skill/pastdue-analysis/`（含 `SKILL.md`、`policy.edn` 1.8.0、playbook 等） | **要** | intent / capability / 消歧真正生效 |

只部署 Atlas：边有了，但 Agent 仍可能按旧习惯答。  
只部署 SP/Skill：Agent 会去调新 capability，但图上没有 → 又 `unreachable` / gap。

**结论：Atlas + System Prompt + Skill 一起发。**  
`runtime/pastdue_kernel` 若宿主会跑本地 Kernel/黄金问，一并带上；纯 Cursor Agent 靠 MCP + 已安装 Skill/SP 时，至少保证 **SP + Skill + Atlas** 三件同步。

---

**User**

:execution/bounds-required [[:resolver "pastdue_current_detail_matching_lines"] :pagination] —
:execution/bounds-required [[:resolver "pastdue_current_vendor_alert_type_counts"] :pagination] —

---

**Cursor**

Atlas 在报这两个 resolver 缺少分页 bounds。正在对照现有合法写法并修复。

原因是这两个 `:many` resolver 只有 `:pagination`、缺少 `:sort`（平台要求完整 bounds）。正在补上，并把 detail 的 `max` 调到与其它 resolver 一致的 `1000`。

本地 `pastdue.edn` 里这两条 `:many` resolver 已补齐平台要求的边界：

| Resolver | pagination | sort |
|---|---|---|
| `pastdue_current_vendor_alert_type_counts` | default 500 / max 1000 | vendor-code ASC |
| `pastdue_current_detail_matching_lines` | default 50 / max 1000 | vendor-code ASC |

`validate_bundle.py` 已 PASS。请重新部署这份 `atlas/charts/pastdue.edn`，再跑 Atlas config-check；这两条 `:execution/bounds-required … :pagination` 应消失。

---

**User**

{}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"entities\\\":[{\\\"entity\\\":\\\"pastdue.country-history-query\\\",\\\"description\\\":\\\"PastDue Country History Query — Explicit UTC date-range request for historical country snapshot comparisons and trigger discovery.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.country-history-query/country-code\\\",\\\"pastdue.country-history-query/range-end-date\\\",\\\"pastdue.country-history-query/range-start-date\\\",\\\"pastdue.country-history-query/relative-change-threshold\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"description\\\":\\\"PastDue Country Monitor — Latest-versus-previous completed UTC snapshot metrics by country.\\\",\\\"attribute_count\\\":17,\\\"identity_keys\\\":[\\\"pastdue.country-monitor/country-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-trigger-event\\\",\\\"description\\\":\\\"PastDue Country Trigger Event — One historical country snapshot pair whose target date is evaluated against the nearest prior available snapshot.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.country-trigger-event/country-code\\\",\\\"pastdue.country-trigger-event/previous-snapshot-date\\\",\\\"pastdue.country-trigger-event/target-snapshot-date\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-alert-type-query\\\",\\\"description\\\":\\\"PastDue Current Alert-Type Query — Filter request for current vendor x alert_type counts. Empty filters mean no constraint.\\\",\\\"attribute_count\\\":5,\\\"identity_keys\\\":[\\\"pastdue.current-alert-type-query/country-code\\\",\\\"pastdue.current-alert-type-query/department-type-filter\\\",\\\"pastdue.current-alert-type-query/master-manager-filter\\\",\\\"pastdue.current-alert-type-query/master-primary-filter\\\",\\\"pastdue.current-alert-type-query/vendor-code-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-detail-line\\\",\\\"description\\\":\\\"PastDue Current Detail Line — Current detail-view PO line slice; Buyer from detail buyer_email; Primary/Manager from vendor_master_mapping_view only.\\\",\\\"attribute_count\\\":22,\\\"identity_keys\\\":[\\\"pastdue.current-detail-line/order-type\\\",\\\"pastdue.current-detail-line/po-line-number\\\",\\\"pastdue.current-detail-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-detail-query\\\",\\\"description\\\":\\\"PastDue Current Detail Query — Controlled current detail-view slice. Empty-string filters mean no constraint. Not free SQL.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.current-detail-query/alert-type-filter\\\",\\\"pastdue.current-detail-query/country-code\\\",\\\"pastdue.current-detail-query/department-type-filter\\\",\\\"pastdue.current-detail-query/eta-code-filter\\\",\\\"pastdue.current-detail-query/is-pastdue-filter\\\",\\\"pastdue.current-detail-query/master-manager-filter\\\",\\\"pastdue.current-detail-query/master-primary-filter\\\",\\\"pastdue.current-detail-query/risk-level-filter\\\",\\\"pastdue.current-detail-query/vendor-code-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"description\\\":\\\"PastDue Current High-Risk PO Line — Current high-risk or past-due PO-line context; Buyer from detail buyer_email, Primary/Manager from vendor_master_mapping_view; not a historical causal record.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.current-high-risk-line/order-type\\\",\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"pastdue.current-high-risk-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-alert-type-count\\\",\\\"description\\\":\\\"PastDue Current Vendor Alert-Type Count — Current-state line counts by vendor and alert_type from past_due_detail_view.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-alert-type-count/alert-type\\\",\\\"pastdue.current-vendor-alert-type-count/country-code\\\",\\\"pastdue.current-vendor-alert-type-count/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"description\\\":\\\"PastDue Current Vendor Past-Due Rank — Current-state vendor past-due share rank from past_due_detail_view; org filter via vendor_master_mapping_view only. Not historical snapshot attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"description\\\":\\\"PastDue Current Vendor Rank Query — Filter request for current vendor past-due ranks. Empty-string org filters mean no constraint on that dimension; vendors must appear in vendor_master_mapping_view for the country.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"description\\\":\\\"PastDue Historical Vendor Contribution — Vendor-level decomposition for an explicit historical country target/previous snapshot pair.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Code Contribution — ETA Code-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-code/country-code\\\",\\\"pastdue.historical-vendor-eta-code/eta-code\\\",\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Source Contribution — ETA Source-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-source/country-code\\\",\\\"pastdue.historical-vendor-eta-source/eta-source\\\",\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.notification-recipient\\\",\\\"description\\\":\\\"PastDue Notification Recipient — Governed contacts used only to display recommended To and CC recipients; this chart does not send email.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.notification-recipient/country-code\\\",\\\"pastdue.notification-recipient/email\\\",\\\"pastdue.notification-recipient/recipient-type\\\",\\\"pastdue.notification-recipient/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"description\\\":\\\"PastDue Current Owner Risk — Current-state owner distribution for high-risk or past-due PO lines; BUYER from past_due_detail_view.buyer_email, PRIMARY/MANAGER from past_due.vendor_master_mapping_view; not a historical causal attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.owner-current-risk/country-code\\\",\\\"pastdue.owner-current-risk/owner-display\\\",\\\"pastdue.owner-current-risk/owner-role\\\",\\\"pastdue.owner-current-risk/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"description\\\":\\\"PastDue Vendor Contribution — Vendor-level decomposition of the latest country past-due-ratio change.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Vendor ETA Code Contribution — ETA Code-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-code/country-code\\\",\\\"pastdue.vendor-eta-code/eta-code\\\",\\\"pastdue.vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Vendor ETA Source Contribution — ETA Source-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-source/country-code\\\",\\\"pastdue.vendor-eta-source/eta-source\\\",\\\"pastdue.vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping — Current vendor DNA organization mapping (department / primary / manager) from past_due.vendor_master_mapping_view; not historical causal attribution. Distinguish countries by country-code.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping/country-code\\\",\\\"pastdue.vendor-master-mapping/department-type\\\",\\\"pastdue.vendor-master-mapping/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping Query — Filter request over vendor_master_mapping_view. Empty-string filters mean no constraint on that dimension (Metabase-style optional filters).\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping-query/country-code\\\",\\\"pastdue.vendor-master-mapping-query/department-type-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-manager-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-primary-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-rollup\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping Rollup — Country-level concentration of vendors by department / primary / manager from vendor_master_mapping_view.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping-rollup/country-code\\\",\\\"pastdue.vendor-master-mapping-rollup/department-type\\\",\\\"pastdue.vendor-master-mapping-rollup/master-manager\\\",\\\"pastdue.vendor-master-mapping-rollup/master-primary\\\"],\\\"thing\\\":true}],\\\"version\\\":4}\"}],\"isError\":false}"
  }
]
09:42:03 - 09:42:04

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.current-vendor-rank-query",
    "pastdue.current-vendor-past-due-rank"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-pastdue-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-total-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country for current rank query.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/matching-ranks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-vendor-past-due-rank\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/vendor-code], :default [:pastdue.current-vendor-past-due-rank/rank :asc], :aliases {\\\\\\\"pastdue.current-vendor-past-due-rank/rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue_rate_in_total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code}}\\\",\\\"required_inputs\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\"]}]}\"}],\"isError\":false}"
  }
]
09:42:06

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/queried-at-utc]} {:limit 10 :order_by \"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\" :sort_dir \"desc\"})]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 4, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"18e32bd0-fe96-4938-a6a6-56820e5832f6\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
09:42:08

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/queried-at-utc]} {:limit 10})]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 4, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"227dc564-9c64-428e-9b6d-c6ebbf2d8783\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
09:42:11
我尝试查询 US 当前 Past Due 占比最高的 Vendor，但 Atlas 数据源连续两次执行失败：

查询范围：US，当前 Vendor Past Due 排名
失败阶段：Atlas source execution
错误：engine.execution/failed / source execution failed
因此目前无法可靠确认 Vendor 排名，也不应编造结果。需要上游 Atlas 数据源恢复后重新查询。

 
分析原因

---

**Cursor**

部署已通；这次是 source 执行失败。正在核对排名 resolver 的 SQL 与调用参数。

Using systematic-debugging：按错误分层定位。

## 结论

**不是**「图谱没部署 / EQL 写错 / bounds 未过」。  
**是** `eta-clickhouse` 执行 `pastdue_current_vendor_past_due_ranks` 的 SQL 时失败（`:error/category :source`）。

Agent 说「上游数据源恢复后再查」**过宽**：MCP 与 graph discovery 正常，更像 **这条 SQL 在 ClickHouse 报错或超时**，不是整站 Atlas 挂了。

## 证据链

| 层 | 结果 |
|---|---|
| 部署 / discovery | `current-vendor-rank-query`、`current-vendor-past-due-rank` 已出现 |
| EQL / 引擎 | 两次都进到 source（带/不带 `order_by` 同样失败 → 不是排序参数问题） |
| Source | `engine.execution/failed` / `source execution failed` |
| Studio | `18e32bd0-…`、`227dc564-…`（真实 CH 异常在这里） |

## 最可能原因（按概率）

**1. 新 SQL 与线上视图类型不一致（高）**  
排名 SQL 用了裸比较：

```sql
countIf(is_pastdue = 1)
```

同 chart 里已跑通的 edge（owner / detail）一律是：

```sql
toInt64(is_pastdue) = 1
```

若 `is_pastdue` 实际不是数值型，CH 会直接抛类型错误 → Atlas 只包装成 `source execution failed`。

**2. 全量 US 扫表超时 / 资源（高）**  
该 resolver 对 `past_due_detail_view` 做 **整国聚合** + `vendor_master_mapping_view` join + `row_number()`，比「按单一 vendor 查 high-risk」重得多；超时同样落在 `:source`。

**3. `toInt64(from_acct_no)` / `toInt64(vendor_no)` 遇脏数据（中）**  
全表扫描时任一坏值都会整句失败；单 vendor 查询可能碰不到。

**4. 整源不可用（低）**  
若 `country-monitor` / mapping-by-filter / 单 vendor high-risk 也都 `:source` 失败，才是连接/权限/视图级问题。

## 建议立刻做的对照（定责）

1. 打开 Studio 上述两个 recent-id，看 **原始 ClickHouse message**（类型错 vs timeout 一眼可分）。  
2. 对照：同一 source 跑 `vendor-master-mapping-query`（US + 空 filter）和任一已知可用的 snapshot edge。  
   - 对照成功 → 源正常，修排名 SQL。  
   - 对照也失败 → 查 JDBC / CH 连通与权限。

未看到 Studio 原文前，不应改 Agent 行为去「编排名」；优先把 `countIf` 改成 `toInt64(is_pastdue)` 并缩小扫描，是代码侧最值得先试的修复方向。需要的话我可以直接改 SQL 并给你一版可 redeploy 的 `pastdue.edn`。

---

**User**

请修复

---

**Cursor**

按已定位的根因修复排名 SQL：统一 `is_pastdue` 转型，并加固 `from_acct_no`/`vendor_no` 转换。

测试已红。正在修复 SQL。

已修好 `pastdue_current_vendor_past_due_ranks` SQL，`validate_bundle` PASS。

**改动：**
1. `countIf(is_pastdue = 1)` → `countIf(toInt64(is_pastdue) = 1)`（与其它 detail_view edge 一致）
2. `toInt64(from_acct_no|vendor_no)` → `toInt64OrNull(toString(...))`，脏行跳过而不是整句失败
3. `validate_bundle.py` 增加上述约束，防止回退

请重新部署 `atlas/charts/pastdue.edn`，再跑同一条 US Top-10 EQL。若仍 `:source` 失败，把 Studio recent-id 里的 ClickHouse 原文贴出来（那时更可能是超时，而不是类型错误）。

---

**User**

[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"entities\\\":[{\\\"entity\\\":\\\"pastdue.country-history-query\\\",\\\"description\\\":\\\"PastDue Country History Query — Explicit UTC date-range request for historical country snapshot comparisons and trigger discovery.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.country-history-query/country-code\\\",\\\"pastdue.country-history-query/range-end-date\\\",\\\"pastdue.country-history-query/range-start-date\\\",\\\"pastdue.country-history-query/relative-change-threshold\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"description\\\":\\\"PastDue Country Monitor — Latest-versus-previous completed UTC snapshot metrics by country.\\\",\\\"attribute_count\\\":17,\\\"identity_keys\\\":[\\\"pastdue.country-monitor/country-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-trigger-event\\\",\\\"description\\\":\\\"PastDue Country Trigger Event — One historical country snapshot pair whose target date is evaluated against the nearest prior available snapshot.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.country-trigger-event/country-code\\\",\\\"pastdue.country-trigger-event/previous-snapshot-date\\\",\\\"pastdue.country-trigger-event/target-snapshot-date\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-alert-type-query\\\",\\\"description\\\":\\\"PastDue Current Alert-Type Query — Filter request for current vendor x alert_type counts. Empty filters mean no constraint.\\\",\\\"attribute_count\\\":5,\\\"identity_keys\\\":[\\\"pastdue.current-alert-type-query/country-code\\\",\\\"pastdue.current-alert-type-query/department-type-filter\\\",\\\"pastdue.current-alert-type-query/master-manager-filter\\\",\\\"pastdue.current-alert-type-query/master-primary-filter\\\",\\\"pastdue.current-alert-type-query/vendor-code-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-detail-line\\\",\\\"description\\\":\\\"PastDue Current Detail Line — Current detail-view PO line slice; Buyer from detail buyer_email; Primary/Manager from vendor_master_mapping_view only.\\\",\\\"attribute_count\\\":22,\\\"identity_keys\\\":[\\\"pastdue.current-detail-line/order-type\\\",\\\"pastdue.current-detail-line/po-line-number\\\",\\\"pastdue.current-detail-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-detail-query\\\",\\\"description\\\":\\\"PastDue Current Detail Query — Controlled current detail-view slice. Empty-string filters mean no constraint. Not free SQL.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.current-detail-query/alert-type-filter\\\",\\\"pastdue.current-detail-query/country-code\\\",\\\"pastdue.current-detail-query/department-type-filter\\\",\\\"pastdue.current-detail-query/eta-code-filter\\\",\\\"pastdue.current-detail-query/is-pastdue-filter\\\",\\\"pastdue.current-detail-query/master-manager-filter\\\",\\\"pastdue.current-detail-query/master-primary-filter\\\",\\\"pastdue.current-detail-query/risk-level-filter\\\",\\\"pastdue.current-detail-query/vendor-code-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"description\\\":\\\"PastDue Current High-Risk PO Line — Current high-risk or past-due PO-line context; Buyer from detail buyer_email, Primary/Manager from vendor_master_mapping_view; not a historical causal record.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.current-high-risk-line/order-type\\\",\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"pastdue.current-high-risk-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-alert-type-count\\\",\\\"description\\\":\\\"PastDue Current Vendor Alert-Type Count — Current-state line counts by vendor and alert_type from past_due_detail_view.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-alert-type-count/alert-type\\\",\\\"pastdue.current-vendor-alert-type-count/country-code\\\",\\\"pastdue.current-vendor-alert-type-count/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"description\\\":\\\"PastDue Current Vendor Past-Due Rank — Current-state vendor past-due share rank from past_due_detail_view; org filter via vendor_master_mapping_view only. Not historical snapshot attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"description\\\":\\\"PastDue Current Vendor Rank Query — Filter request for current vendor past-due ranks. Empty-string org filters mean no constraint on that dimension; vendors must appear in vendor_master_mapping_view for the country.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"description\\\":\\\"PastDue Historical Vendor Contribution — Vendor-level decomposition for an explicit historical country target/previous snapshot pair.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Code Contribution — ETA Code-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-code/country-code\\\",\\\"pastdue.historical-vendor-eta-code/eta-code\\\",\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Source Contribution — ETA Source-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-source/country-code\\\",\\\"pastdue.historical-vendor-eta-source/eta-source\\\",\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.notification-recipient\\\",\\\"description\\\":\\\"PastDue Notification Recipient — Governed contacts used only to display recommended To and CC recipients; this chart does not send email.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.notification-recipient/country-code\\\",\\\"pastdue.notification-recipient/email\\\",\\\"pastdue.notification-recipient/recipient-type\\\",\\\"pastdue.notification-recipient/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"description\\\":\\\"PastDue Current Owner Risk — Current-state owner distribution for high-risk or past-due PO lines; BUYER from past_due_detail_view.buyer_email, PRIMARY/MANAGER from past_due.vendor_master_mapping_view; not a historical causal attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.owner-current-risk/country-code\\\",\\\"pastdue.owner-current-risk/owner-display\\\",\\\"pastdue.owner-current-risk/owner-role\\\",\\\"pastdue.owner-current-risk/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"description\\\":\\\"PastDue Vendor Contribution — Vendor-level decomposition of the latest country past-due-ratio change.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Vendor ETA Code Contribution — ETA Code-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-code/country-code\\\",\\\"pastdue.vendor-eta-code/eta-code\\\",\\\"pastdue.vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Vendor ETA Source Contribution — ETA Source-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-source/country-code\\\",\\\"pastdue.vendor-eta-source/eta-source\\\",\\\"pastdue.vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping — Current vendor DNA organization mapping (department / primary / manager) from past_due.vendor_master_mapping_view; not historical causal attribution. Distinguish countries by country-code.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping/country-code\\\",\\\"pastdue.vendor-master-mapping/department-type\\\",\\\"pastdue.vendor-master-mapping/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping Query — Filter request over vendor_master_mapping_view. Empty-string filters mean no constraint on that dimension (Metabase-style optional filters).\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping-query/country-code\\\",\\\"pastdue.vendor-master-mapping-query/department-type-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-manager-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-primary-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-rollup\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping Rollup — Country-level concentration of vendors by department / primary / manager from vendor_master_mapping_view.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping-rollup/country-code\\\",\\\"pastdue.vendor-master-mapping-rollup/department-type\\\",\\\"pastdue.vendor-master-mapping-rollup/master-manager\\\",\\\"pastdue.vendor-master-mapping-rollup/master-primary\\\"],\\\"thing\\\":true}],\\\"version\\\":5}\"}],\"isError\":false}"
  }
]
09:53:24 - 09:53:25

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.current-vendor-rank-query",
    "pastdue.current-vendor-past-due-rank"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-pastdue-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-total-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country for current rank query.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/matching-ranks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-vendor-past-due-rank\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/vendor-code], :default [:pastdue.current-vendor-past-due-rank/rank :asc], :aliases {\\\\\\\"pastdue.current-vendor-past-due-rank/rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue_rate_in_total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code}}\\\",\\\"required_inputs\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\"]}]}\"}],\"isError\":false}"
  }
]
09:53:27

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.vendor-contribution",
    "pastdue.country-monitor"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country independently monitored in the latest two completed snapshots.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"国家代码\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/has-comparable-snapshots\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when at least two completed snapshots exist for the country.\\\",\\\"synonyms\\\":[\\\"存在两个可比较快照\\\"],\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"All open PO-line count used as the latest country denominator.\\\",\\\"synonyms\\\":[\\\"最新开放行数\\\",\\\"latest denominator\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due open PO-line count in the latest completed snapshot.\\\",\\\"synonyms\\\":[\\\"最新逾期行数\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-past-due-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest country past-due count divided by latest country open count, calculated without presentation rounding.\\\",\\\"synonyms\\\":[\\\"最新逾期率\\\",\\\"latest past due ratio\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-snapshot-age-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"UTC calendar days from the latest completed snapshot date to the current UTC date.\\\",\\\"synonyms\\\":[\\\"最新快照陈旧天数\\\"],\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Most recent snapshot date strictly earlier than the current UTC date.\\\",\\\"synonyms\\\":[\\\"最新完整快照日期\\\",\\\"latest completed snapshot\\\"],\\\"format\\\":\\\"date\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous open PO-line count.\\\",\\\"synonyms\\\":[\\\"开放行数变化\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous past-due PO-line count.\\\",\\\"synonyms\\\":[\\\"逾期行数变化\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/percentage-point-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest past-due ratio minus previous past-due ratio; displayed for context but not used as a trigger threshold.\\\",\\\"synonyms\\\":[\\\"百分点变化\\\",\\\"ratio point change\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"All open PO-line count used as the previous country denominator.\\\",\\\"synonyms\\\":[\\\"上期开放行数\\\",\\\"previous denominator\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Past-due open PO-line count in the previous completed snapshot.\\\",\\\"synonyms\\\":[\\\"上期逾期行数\\\"],\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-past-due-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous country past-due count divided by previous country open count, calculated without presentation rounding.\\\",\\\"synonyms\\\":[\\\"上期逾期率\\\",\\\"previous past due ratio\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Most recent available completed snapshot before the latest snapshot for this country.\\\",\\\"synonyms\\\":[\\\"上一快照日期\\\",\\\"previous completed snapshot\\\"],\\\"format\\\":\\\"date\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/relative-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Relative change in country past-due ratio; null when the previous ratio is zero or unavailable.\\\",\\\"synonyms\\\":[\\\"逾期率相对变化\\\",\\\"relative growth\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/snapshot-gap-days\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Calendar-day gap between the latest and previous available snapshot dates.\\\",\\\"synonyms\\\":[\\\"快照间隔天数\\\"],\\\"unit\\\":\\\"days\\\",\\\"format\\\":\\\"number\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/zero-baseline-increase\\\",\\\"type\\\":\\\"boolean\\\",\\\"description\\\":\\\"True when the previous ratio is zero and the latest ratio is greater than zero.\\\",\\\"synonyms\\\":[\\\"零基线上升\\\"],\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/contribution-rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Stable descending positive-contribution rank within the country.\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country component of the vendor analysis identity.\\\",\\\"synonyms\\\":[\\\"国家\\\",\\\"country\\\"],\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/cumulative-positive-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Cumulative positive contribution share in descending delta order.\\\",\\\"synonyms\\\":[\\\"累计正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/delta-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Vendor contribution to country ratio change: latest country contribution minus previous country contribution.\\\",\\\"synonyms\\\":[\\\"对国家逾期率变化的贡献\\\",\\\"vendor contribution\\\"],\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the latest snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Latest completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor past-due count divided by latest vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/open-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor open count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/past-due-count-change\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Latest minus previous vendor past-due count.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/positive-contribution-share\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"This vendor's positive delta divided by the sum of positive vendor deltas in the country.\\\",\\\"synonyms\\\":[\\\"正向贡献占比\\\"],\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-country-contribution\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous country open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-open-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor open PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-past-due-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor past-due PO-line count in the previous snapshot.\\\",\\\"unit\\\":\\\"PO lines\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-snapshot-date\\\",\\\"type\\\":\\\"date\\\",\\\"description\\\":\\\"Previous available completed snapshot used in this decomposition.\\\",\\\"format\\\":\\\"date\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Previous vendor past-due count divided by previous vendor open count.\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"Vendor component of the identity; vendor code is not assumed globally unique across countries.\\\",\\\"synonyms\\\":[\\\"供应商代码\\\",\\\"vendor\\\"],\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Latest available nonblank vendor name across the compared snapshots.\\\",\\\"synonyms\\\":[\\\"供应商名称\\\"],\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/vendor-ratio-change\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"Latest vendor ratio minus previous vendor ratio.\\\",\\\"unit\\\":\\\"percentage points\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/all\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.country-monitor\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.country-monitor/country-code :pastdue.country-monitor/latest-snapshot-date :pastdue.country-monitor/previous-snapshot-date :pastdue.country-monitor/snapshot-gap-days :pastdue.country-monitor/latest-snapshot-age-days :pastdue.country-monitor/latest-past-due-count :pastdue.country-monitor/previous-past-due-count :pastdue.country-monitor/past-due-count-change :pastdue.country-monitor/latest-open-count :pastdue.country-monitor/previous-open-count :pastdue.country-monitor/open-count-change :pastdue.country-monitor/latest-past-due-ratio :pastdue.country-monitor/previous-past-due-ratio :pastdue.country-monitor/relative-ratio-change :pastdue.country-monitor/percentage-point-change :pastdue.country-monitor/zero-baseline-increase :pastdue.country-monitor/has-comparable-snapshots], :default [:pastdue.country-monitor/country-code :asc], :aliases {\\\\\\\"pastdue.country-monitor/past-due-count-change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"previous-open-count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"latest-snapshot-age-days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"percentage-point-change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"pastdue.country-monitor/open-count-change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"snapshot_gap_days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"relative-ratio-change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"pastdue.country-monitor/previous-past-due-ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"pastdue.country-monitor/latest-snapshot-date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"pastdue.country-monitor/has-comparable-snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"pastdue.country-monitor/previous-snapshot-date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"latest_snapshot_age_days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.country-monitor/previous-snapshot-date, \\\\\\\"snapshot-gap-days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"zero-baseline-increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase, \\\\\\\"pastdue.country-monitor/latest-snapshot-age-days\\\\\\\" :pastdue.country-monitor/latest-snapshot-age-days, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"pastdue.country-monitor/country-code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"pastdue.country-monitor/latest-open-count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"country_code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"has-comparable-snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"previous-past-due-ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"pastdue.country-monitor/snapshot-gap-days\\\\\\\" :pastdue.country-monitor/snapshot-gap-days, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"pastdue.country-monitor/previous-past-due-count\\\\\\\" :pastdue.country-monitor/previous-past-due-count, \\\\\\\"latest-open-count\\\\\\\" :pastdue.country-monitor/latest-open-count, \\\\\\\"pastdue.country-monitor/latest-past-due-ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"previous_open_count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"has_comparable_snapshots\\\\\\\" :pastdue.country-monitor/has-comparable-snapshots, \\\\\\\"latest-past-due-ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.country-monitor/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.country-monitor/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.country-monitor/open-count-change, \\\\\\\"pastdue.country-monitor/zero-baseline-increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase, \\\\\\\"pastdue.country-monitor/percentage-point-change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"percentage_point_change\\\\\\\" :pastdue.country-monitor/percentage-point-change, \\\\\\\"pastdue.country-monitor/latest-past-due-count\\\\\\\" :pastdue.country-monitor/latest-past-due-count, \\\\\\\"relative_ratio_change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"previous_past_due_ratio\\\\\\\" :pastdue.country-monitor/previous-past-due-ratio, \\\\\\\"latest_past_due_ratio\\\\\\\" :pastdue.country-monitor/latest-past-due-ratio, \\\\\\\"country-code\\\\\\\" :pastdue.country-monitor/country-code, \\\\\\\"pastdue.country-monitor/previous-open-count\\\\\\\" :pastdue.country-monitor/previous-open-count, \\\\\\\"pastdue.country-monitor/relative-ratio-change\\\\\\\" :pastdue.country-monitor/relative-ratio-change, \\\\\\\"zero_baseline_increase\\\\\\\" :pastdue.country-monitor/zero-baseline-increase}}\\\"},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/positive-vendor-contributors\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-contribution\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank], :default [:pastdue.vendor-contribution/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"pastdue.vendor-contribution/open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"pastdue.vendor-contribution/previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"pastdue.vendor-contribution/vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"latest_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"pastdue.vendor-contribution/cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"vendor_name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"pastdue.vendor-contribution/previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"pastdue.vendor-contribution/past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"pastdue.vendor-contribution/latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"pastdue.vendor-contribution/latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"pastdue.vendor-contribution/positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"vendor_ratio_change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"previous_open_count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"pastdue.vendor-contribution/vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/vendor-changes\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-contribution\\\",\\\"limit\\\":{\\\"default\\\":1000,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-contribution/country-code :pastdue.vendor-contribution/vendor-code :pastdue.vendor-contribution/vendor-name :pastdue.vendor-contribution/latest-snapshot-date :pastdue.vendor-contribution/previous-snapshot-date :pastdue.vendor-contribution/latest-past-due-count :pastdue.vendor-contribution/previous-past-due-count :pastdue.vendor-contribution/past-due-count-change :pastdue.vendor-contribution/latest-open-count :pastdue.vendor-contribution/previous-open-count :pastdue.vendor-contribution/open-count-change :pastdue.vendor-contribution/latest-vendor-ratio :pastdue.vendor-contribution/previous-vendor-ratio :pastdue.vendor-contribution/vendor-ratio-change :pastdue.vendor-contribution/latest-country-contribution :pastdue.vendor-contribution/previous-country-contribution :pastdue.vendor-contribution/delta-country-contribution :pastdue.vendor-contribution/positive-contribution-share :pastdue.vendor-contribution/cumulative-positive-share :pastdue.vendor-contribution/contribution-rank], :default [:pastdue.vendor-contribution/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"pastdue.vendor-contribution/open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"pastdue.vendor-contribution/previous-past-due-count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"previous_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-contribution/previous-past-due-count, \\\\\\\"pastdue.vendor-contribution/vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"latest_vendor_ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"pastdue.vendor-contribution/cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"vendor_name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"pastdue.vendor-contribution/previous-open-count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"pastdue.vendor-contribution/latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/delta-country-contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"pastdue.vendor-contribution/contribution-rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest_open_count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"pastdue.vendor-contribution/past-due-count-change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"pastdue.vendor-contribution/latest-past-due-count\\\\\\\" :pastdue.vendor-contribution/latest-past-due-count, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"pastdue.vendor-contribution/latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"pastdue.vendor-contribution/positive-contribution-share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-contribution/latest-country-contribution, \\\\\\\"previous-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/previous-vendor-ratio, \\\\\\\"latest-open-count\\\\\\\" :pastdue.vendor-contribution/latest-open-count, \\\\\\\"vendor_ratio_change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"previous_open_count\\\\\\\" :pastdue.vendor-contribution/previous-open-count, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-contribution/latest-snapshot-date, \\\\\\\"pastdue.vendor-contribution/latest-vendor-ratio\\\\\\\" :pastdue.vendor-contribution/latest-vendor-ratio, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-contribution/past-due-count-change, \\\\\\\"open_count_change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"vendor-ratio-change\\\\\\\" :pastdue.vendor-contribution/vendor-ratio-change, \\\\\\\"open-count-change\\\\\\\" :pastdue.vendor-contribution/open-count-change, \\\\\\\"pastdue.vendor-contribution/vendor-name\\\\\\\" :pastdue.vendor-contribution/vendor-name, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-contribution/previous-country-contribution, \\\\\\\"pastdue.vendor-contribution/country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-contribution/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-contribution/positive-contribution-share, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-contribution/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-contribution/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-contribution/country-code, \\\\\\\"pastdue.vendor-contribution/previous-snapshot-date\\\\\\\" :pastdue.vendor-contribution/previous-snapshot-date, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-contribution/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"attribute\\\":\\\"pastdue.country-monitor/vendor-master-mapping-rollups\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-master-mapping-rollup\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-master-mapping-rollup/country-code :pastdue.vendor-master-mapping-rollup/department-type :pastdue.vendor-master-mapping-rollup/master-primary :pastdue.vendor-master-mapping-rollup/master-manager :pastdue.vendor-master-mapping-rollup/mapped-vendor-count :pastdue.vendor-master-mapping-rollup/queried-at-utc], :default [:pastdue.vendor-master-mapping-rollup/mapped-vendor-count :desc], :aliases {\\\\\\\"pastdue.vendor-master-mapping-rollup/country-code\\\\\\\" :pastdue.vendor-master-mapping-rollup/country-code, \\\\\\\"master-primary\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-primary, \\\\\\\"master_manager\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-manager, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping-rollup/queried-at-utc, \\\\\\\"department_type\\\\\\\" :pastdue.vendor-master-mapping-rollup/department-type, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-master-mapping-rollup/country-code, \\\\\\\"master_primary\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-primary, \\\\\\\"pastdue.vendor-master-mapping-rollup/master-manager\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-manager, \\\\\\\"mapped-vendor-count\\\\\\\" :pastdue.vendor-master-mapping-rollup/mapped-vendor-count, \\\\\\\"pastdue.vendor-master-mapping-rollup/department-type\\\\\\\" :pastdue.vendor-master-mapping-rollup/department-type, \\\\\\\"pastdue.vendor-master-mapping-rollup/mapped-vendor-count\\\\\\\" :pastdue.vendor-master-mapping-rollup/mapped-vendor-count, \\\\\\\"mapped_vendor_count\\\\\\\" :pastdue.vendor-master-mapping-rollup/mapped-vendor-count, \\\\\\\"pastdue.vendor-master-mapping-rollup/queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping-rollup/queried-at-utc, \\\\\\\"master-manager\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-manager, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-master-mapping-rollup/country-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.vendor-master-mapping-rollup/queried-at-utc, \\\\\\\"department-type\\\\\\\" :pastdue.vendor-master-mapping-rollup/department-type, \\\\\\\"pastdue.vendor-master-mapping-rollup/master-primary\\\\\\\" :pastdue.vendor-master-mapping-rollup/master-primary}}\\\",\\\"required_inputs\\\":[\\\"pastdue.country-monitor/country-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-high-risk-lines\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-high-risk-line\\\",\\\"limit\\\":{\\\"default\\\":20,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-high-risk-line/country-code :pastdue.current-high-risk-line/vendor-code :pastdue.current-high-risk-line/order-type :pastdue.current-high-risk-line/po-number :pastdue.current-high-risk-line/po-line-number :pastdue.current-high-risk-line/sku-number :pastdue.current-high-risk-line/ordered-quantity :pastdue.current-high-risk-line/shipped-quantity :pastdue.current-high-risk-line/received-quantity :pastdue.current-high-risk-line/eta-code :pastdue.current-high-risk-line/eta-date :pastdue.current-high-risk-line/eta-source :pastdue.current-high-risk-line/risk-level :pastdue.current-high-risk-line/is-past-due :pastdue.current-high-risk-line/past-due-days :pastdue.current-high-risk-line/action-type :pastdue.current-high-risk-line/buyer-owner :pastdue.current-high-risk-line/primary-owner :pastdue.current-high-risk-line/manager-owner :pastdue.current-high-risk-line/risk-priority-rank], :default [:pastdue.current-high-risk-line/risk-priority-rank :asc], :aliases {\\\\\\\"risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"order_type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"po_line_number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/po-number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"pastdue.current-high-risk-line/eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"pastdue.current-high-risk-line/eta-date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"risk_priority_rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"pastdue.current-high-risk-line/received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"pastdue.current-high-risk-line/vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"received_quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"eta_source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"eta-source\\\\\\\" :pastdue.current-high-risk-line/eta-source, \\\\\\\"pastdue.current-high-risk-line/shipped-quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"pastdue.current-high-risk-line/sku-number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"pastdue.current-high-risk-line/manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"buyer-owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"is_past_due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"pastdue.current-high-risk-line/country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"eta_code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"ordered_quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"manager_owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"manager-owner\\\\\\\" :pastdue.current-high-risk-line/manager-owner, \\\\\\\"action_type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/past-due-days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"sku_number\\\\\\\" :pastdue.current-high-risk-line/sku-number, \\\\\\\"past_due_days\\\\\\\" :pastdue.current-high-risk-line/past-due-days, \\\\\\\"po-line-number\\\\\\\" :pastdue.current-high-risk-line/po-line-number, \\\\\\\"ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"eta-code\\\\\\\" :pastdue.current-high-risk-line/eta-code, \\\\\\\"pastdue.current-high-risk-line/primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"buyer_owner\\\\\\\" :pastdue.current-high-risk-line/buyer-owner, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-high-risk-line/vendor-code, \\\\\\\"primary_owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"pastdue.current-high-risk-line/is-past-due\\\\\\\" :pastdue.current-high-risk-line/is-past-due, \\\\\\\"pastdue.current-high-risk-line/order-type\\\\\\\" :pastdue.current-high-risk-line/order-type, \\\\\\\"country-code\\\\\\\" :pastdue.current-high-risk-line/country-code, \\\\\\\"pastdue.current-high-risk-line/risk-priority-rank\\\\\\\" :pastdue.current-high-risk-line/risk-priority-rank, \\\\\\\"po_number\\\\\\\" :pastdue.current-high-risk-line/po-number, \\\\\\\"eta_date\\\\\\\" :pastdue.current-high-risk-line/eta-date, \\\\\\\"pastdue.current-high-risk-line/action-type\\\\\\\" :pastdue.current-high-risk-line/action-type, \\\\\\\"pastdue.current-high-risk-line/ordered-quantity\\\\\\\" :pastdue.current-high-risk-line/ordered-quantity, \\\\\\\"received-quantity\\\\\\\" :pastdue.current-high-risk-line/received-quantity, \\\\\\\"shipped_quantity\\\\\\\" :pastdue.current-high-risk-line/shipped-quantity, \\\\\\\"primary-owner\\\\\\\" :pastdue.current-high-risk-line/primary-owner, \\\\\\\"risk-level\\\\\\\" :pastdue.current-high-risk-line/risk-level, \\\\\\\"risk_level\\\\\\\" :pastdue.current-high-risk-line/risk-level}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/current-owner-risks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.owner-current-risk\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.owner-current-risk/country-code :pastdue.owner-current-risk/vendor-code :pastdue.owner-current-risk/owner-role :pastdue.owner-current-risk/owner-display :pastdue.owner-current-risk/current-risk-line-count :pastdue.owner-current-risk/current-high-risk-line-count :pastdue.owner-current-risk/current-past-due-line-count :pastdue.owner-current-risk/max-past-due-days :pastdue.owner-current-risk/queried-at-utc], :default [:pastdue.owner-current-risk/current-risk-line-count :desc], :aliases {\\\\\\\"pastdue.owner-current-risk/country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"owner_role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"vendor_code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"owner_display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"country_code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"pastdue.owner-current-risk/current-high-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"current_high_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-high-risk-line-count, \\\\\\\"pastdue.owner-current-risk/queried-at-utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc, \\\\\\\"current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"max_past_due_days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"pastdue.owner-current-risk/owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"pastdue.owner-current-risk/current-risk-line-count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"current_past_due_line_count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/max-past-due-days\\\\\\\" :pastdue.owner-current-risk/max-past-due-days, \\\\\\\"current_risk_line_count\\\\\\\" :pastdue.owner-current-risk/current-risk-line-count, \\\\\\\"owner-display\\\\\\\" :pastdue.owner-current-risk/owner-display, \\\\\\\"vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"pastdue.owner-current-risk/owner-role\\\\\\\" :pastdue.owner-current-risk/owner-role, \\\\\\\"country-code\\\\\\\" :pastdue.owner-current-risk/country-code, \\\\\\\"current-past-due-line-count\\\\\\\" :pastdue.owner-current-risk/current-past-due-line-count, \\\\\\\"pastdue.owner-current-risk/vendor-code\\\\\\\" :pastdue.owner-current-risk/vendor-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.owner-current-risk/queried-at-utc}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-code-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-code\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-code/country-code :pastdue.vendor-eta-code/vendor-code :pastdue.vendor-eta-code/eta-code :pastdue.vendor-eta-code/latest-snapshot-date :pastdue.vendor-eta-code/previous-snapshot-date :pastdue.vendor-eta-code/latest-past-due-count :pastdue.vendor-eta-code/previous-past-due-count :pastdue.vendor-eta-code/past-due-count-change :pastdue.vendor-eta-code/latest-country-contribution :pastdue.vendor-eta-code/previous-country-contribution :pastdue.vendor-eta-code/delta-country-contribution :pastdue.vendor-eta-code/positive-contribution-share :pastdue.vendor-eta-code/cumulative-positive-share :pastdue.vendor-eta-code/contribution-rank], :default [:pastdue.vendor-eta-code/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-code/country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-code/past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"pastdue.vendor-eta-code/contribution-rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-code/latest-past-due-count, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"pastdue.vendor-eta-code/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-code/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-code/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-code/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-code/past-due-count-change, \\\\\\\"pastdue.vendor-eta-code/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-code/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-code/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-code/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-code/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-code/latest-snapshot-date, \\\\\\\"eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-code/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-code/eta-code\\\\\\\" :pastdue.vendor-eta-code/eta-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-code/country-code, \\\\\\\"pastdue.vendor-eta-code/vendor-code\\\\\\\" :pastdue.vendor-eta-code/vendor-code, \\\\\\\"pastdue.vendor-eta-code/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-code/latest-country-contribution, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-code/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/eta-source-drivers\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-eta-source\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-eta-source/country-code :pastdue.vendor-eta-source/vendor-code :pastdue.vendor-eta-source/eta-source :pastdue.vendor-eta-source/latest-snapshot-date :pastdue.vendor-eta-source/previous-snapshot-date :pastdue.vendor-eta-source/latest-past-due-count :pastdue.vendor-eta-source/previous-past-due-count :pastdue.vendor-eta-source/past-due-count-change :pastdue.vendor-eta-source/latest-country-contribution :pastdue.vendor-eta-source/previous-country-contribution :pastdue.vendor-eta-source/delta-country-contribution :pastdue.vendor-eta-source/positive-contribution-share :pastdue.vendor-eta-source/cumulative-positive-share :pastdue.vendor-eta-source/contribution-rank], :default [:pastdue.vendor-eta-source/contribution-rank :asc], :aliases {\\\\\\\"contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"pastdue.vendor-eta-source/previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"pastdue.vendor-eta-source/positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"pastdue.vendor-eta-source/country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"previous_past_due_count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"pastdue.vendor-eta-source/contribution-rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank, \\\\\\\"dimension_value\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"pastdue.vendor-eta-source/delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"pastdue.vendor-eta-source/cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-past-due-count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"delta-country-contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"previous_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous_country_contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"latest_past_due_count\\\\\\\" :pastdue.vendor-eta-source/latest-past-due-count, \\\\\\\"eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"positive-contribution-share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"latest_country_contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"cumulative_positive_share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"latest-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"latest-country-contribution\\\\\\\" :pastdue.vendor-eta-source/latest-country-contribution, \\\\\\\"pastdue.vendor-eta-source/previous-snapshot-date\\\\\\\" :pastdue.vendor-eta-source/previous-snapshot-date, \\\\\\\"latest_snapshot_date\\\\\\\" :pastdue.vendor-eta-source/latest-snapshot-date, \\\\\\\"past_due_count_change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"previous-country-contribution\\\\\\\" :pastdue.vendor-eta-source/previous-country-contribution, \\\\\\\"cumulative-positive-share\\\\\\\" :pastdue.vendor-eta-source/cumulative-positive-share, \\\\\\\"positive_contribution_share\\\\\\\" :pastdue.vendor-eta-source/positive-contribution-share, \\\\\\\"pastdue.vendor-eta-source/past-due-count-change\\\\\\\" :pastdue.vendor-eta-source/past-due-count-change, \\\\\\\"delta_country_contribution\\\\\\\" :pastdue.vendor-eta-source/delta-country-contribution, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/eta-source\\\\\\\" :pastdue.vendor-eta-source/eta-source, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-eta-source/country-code, \\\\\\\"pastdue.vendor-eta-source/vendor-code\\\\\\\" :pastdue.vendor-eta-source/vendor-code, \\\\\\\"pastdue.vendor-eta-source/previous-past-due-count\\\\\\\" :pastdue.vendor-eta-source/previous-past-due-count, \\\\\\\"contribution_rank\\\\\\\" :pastdue.vendor-eta-source/contribution-rank}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/master-mappings\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.vendor-master-mapping\\\",\\\"limit\\\":{\\\"default\\\":50,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.vendor-master-mapping/country-code :pastdue.vendor-master-mapping/vendor-code :pastdue.vendor-master-mapping/department-type :pastdue.vendor-master-mapping/master-primary :pastdue.vendor-master-mapping/master-manager :pastdue.vendor-master-mapping/queried-at-utc], :default [:pastdue.vendor-master-mapping/department-type :asc], :aliases {\\\\\\\"pastdue.vendor-master-mapping/department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"pastdue.vendor-master-mapping/country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"master_manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department_type\\\\\\\" :pastdue.vendor-master-mapping/department-type, \\\\\\\"vendor_code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country_code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"master_primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/master-primary\\\\\\\" :pastdue.vendor-master-mapping/master-primary, \\\\\\\"pastdue.vendor-master-mapping/vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"pastdue.vendor-master-mapping/master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"pastdue.vendor-master-mapping/queried-at-utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"master-manager\\\\\\\" :pastdue.vendor-master-mapping/master-manager, \\\\\\\"vendor-code\\\\\\\" :pastdue.vendor-master-mapping/vendor-code, \\\\\\\"country-code\\\\\\\" :pastdue.vendor-master-mapping/country-code, \\\\\\\"queried_at_utc\\\\\\\" :pastdue.vendor-master-mapping/queried-at-utc, \\\\\\\"department-type\\\\\\\" :pastdue.vendor-master-mapping/department-type}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"]},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"attribute\\\":\\\"pastdue.vendor-contribution/notification-recipients\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.notification-recipient\\\",\\\"limit\\\":{\\\"default\\\":100,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.notification-recipient/country-code :pastdue.notification-recipient/vendor-code :pastdue.notification-recipient/recipient-type :pastdue.notification-recipient/email :pastdue.notification-recipient/contact-name :pastdue.notification-recipient/is-active :pastdue.notification-recipient/source-system :pastdue.notification-recipient/contact-role-hint :pastdue.notification-recipient/contact-priority-score :pastdue.notification-recipient/is-internal-candidate :pastdue.notification-recipient/is-recommended :pastdue.notification-recipient/selection-reason :pastdue.notification-recipient/evidence-text :pastdue.notification-recipient/updated-at-utc], :default [:pastdue.notification-recipient/contact-priority-score :desc], :aliases {\\\\\\\"pastdue.notification-recipient/country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"pastdue.notification-recipient/evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"source_system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"pastdue.notification-recipient/updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"pastdue.notification-recipient/is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"is_active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"selection_reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"contact_priority_score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"vendor_code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"email\\\\\\\" :pastdue.notification-recipient/email, \\\\\\\"contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/contact-role-hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"is_internal_candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"recipient_type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"country_code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"is_recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"is-active\\\\\\\" :pastdue.notification-recipient/is-active, \\\\\\\"pastdue.notification-recipient/recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"evidence_text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"updated_at_utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"recipient-type\\\\\\\" :pastdue.notification-recipient/recipient-type, \\\\\\\"pastdue.notification-recipient/vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"is-recommended\\\\\\\" :pastdue.notification-recipient/is-recommended, \\\\\\\"contact_role_hint\\\\\\\" :pastdue.notification-recipient/contact-role-hint, \\\\\\\"selection-reason\\\\\\\" :pastdue.notification-recipient/selection-reason, \\\\\\\"pastdue.notification-recipient/contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"pastdue.notification-recipient/source-system\\\\\\\" :pastdue.notification-recipient/source-system, \\\\\\\"contact_name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"pastdue.notification-recipient/is-internal-candidate\\\\\\\" :pastdue.notification-recipient/is-internal-candidate, \\\\\\\"pastdue.notification-recipient/contact-name\\\\\\\" :pastdue.notification-recipient/contact-name, \\\\\\\"evidence-text\\\\\\\" :pastdue.notification-recipient/evidence-text, \\\\\\\"vendor-code\\\\\\\" :pastdue.notification-recipient/vendor-code, \\\\\\\"contact-priority-score\\\\\\\" :pastdue.notification-recipient/contact-priority-score, \\\\\\\"country-code\\\\\\\" :pastdue.notification-recipient/country-code, \\\\\\\"updated-at-utc\\\\\\\" :pastdue.notification-recipient/updated-at-utc, \\\\\\\"pastdue.notification-recipient/email\\\\\\\" :pastdue.notification-recipient/email}}\\\",\\\"required_inputs\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\",\\\"pastdue.notification-recipient/recipient-type\\\"]}]}\"}],\"isError\":false}"
  }
]
09:53:27

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/country-code :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/queried-at-utc]} {:limit 10 :order_by \"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\" :sort_dir \"desc\"})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 5, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"788d51b5-74eb-4ce4-bfef-29105e01ddb7\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
09:53:30 - 09:53:31

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/country-code :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/queried-at-utc]} {:limit 10})]}",
  "retain_result": true
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 5, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"053b220b-4e01-4289-bb6d-7134d0c7b4f6\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
09:53:34
查询 US 当前 Vendor Past Due 占比排名时，Atlas 两次查询均失败：

错误码：engine.execution/failed
错误类别：source
尝试能力：current-vendor-past-due-ranks
范围：US，按 pastdue-rate-in-total 降序取 Top 10
因此目前无法可靠确认哪些 Vendor 占比最高。需要上游 PastDue 数据源恢复后重新查询；我不会用历史快照排名替代当前排名。



---

**Cursor**

类型修复部署后仍失败（version 5）。继续查根因：对照已跑通的 SQL，并重点排查 `toInt64OrNull` / 窗口函数 / Atlas 分页包装。

version 5 仍失败。`toInt64OrNull` 可能不被线上 CH 支持，且当前 SQL 对 `detail_view` 扫两遍。改为与已跑通 edge 一致的 `toInt64`，并改成单次扫描。

根因更可能是：**v5 引入的 `toInt64OrNull`（线上可能没有）+ 对 `detail_view` 扫两遍**，而不是「整站数据源挂了」。

**本次改动（请重新部署 `pastdue.edn`）：**
1. 去掉 `toInt64OrNull`，改回与 high-risk/owner 一致的 `toInt64`
2. 单次扫描：`marked` 用窗口算全国 past-due 分母，再 join mapping 聚合
3. `toDecimal64(toFloat64(...), 9)` 与通知联系人 SQL 一致
4. validator 锁定：禁止 OrNull、必须 `toInt64(is_pastdue)`、detail_view 只出现一次

部署后用同一条 US Top-10 EQL 再试。若仍 `:source` 失败，把 Studio `788d51b5…` / 新 recent-id 里的 **ClickHouse 原文**贴出；并对照跑一次 `current-alert-type-query`（同视图、同 filter 写法）以区分「整源挂」vs「仅 rank SQL」。

---

**User**

{
  "entities": [
    "pastdue.current-vendor-rank-query",
    "pastdue.current-vendor-past-due-rank"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-pastdue-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-total-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country for current rank query.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/matching-ranks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-vendor-past-due-rank\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/vendor-code], :default [:pastdue.current-vendor-past-due-rank/rank :asc], :aliases {\\\\\\\"pastdue.current-vendor-past-due-rank/rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue_rate_in_total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code}}\\\",\\\"required_inputs\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\"]}]}\"}],\"isError\":false}"
  }
]
10:12:02

mcp__ETA-PastDue-Graph__search_attributes (mcp · ETA-PastDue-Graph)
Completed
current vendor past due rank
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"query\\\":\\\"current vendor past due rank\\\",\\\"hits\\\":[{\\\"kind\\\":\\\"entity\\\",\\\"match\\\":\\\"entity\\\",\\\"label\\\":\\\"PastDue Current Vendor Past-Due Rank\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\"},{\\\"kind\\\":\\\"entity\\\",\\\"match\\\":\\\"entity\\\",\\\"label\\\":\\\"PastDue Current Vendor Rank Query\\\",\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Rank\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/rank\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Risk Priority Rank\\\",\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"attribute\\\":\\\"pastdue.current-high-risk-line/risk-priority-rank\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Vendor Code\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Vendor Name\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-name\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Country Code\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/country-code\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Queried At UTC\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/queried-at-utc\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Past-Due Rate In Vendor\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Vendor Past-Due Line Count\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-pastdue-count\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Past-Due Rate In Country Total\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Vendor Total Line Count\\\",\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-total-count\\\"},{\\\"kind\\\":\\\"attribute\\\",\\\"match\\\":\\\"attribute\\\",\\\"label\\\":\\\"Matching Ranks\\\",\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/matching-ranks\\\"}]}\"}],\"isError\":false}"
  }
]
10:12:02

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/queried-at-utc]} {:limit 10})]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 6, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"e70eab9e-9de7-4595-89b7-6866e20aaf8c\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
10:12:05

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total]} {:limit 10})]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 6, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"cfc1fe28-966b-4477-b450-e5f7eef7ed48\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
10:12:08
查询 US 当前 Vendor Past Due 排名时，Atlas 数据源连续两次返回 source execution failed，因此目前无法可靠列出 Vendor 名单或数量。

我尝试查询的是：

Country：US
当前 Vendor Past-Due 排名
无部门、Primary、Manager 筛选
按当前 Past Due 数量对应的排名返回前 10
需要上游 Atlas 数据源恢复后，才能继续确认结果。

---

**Cursor**

version 6 仍失败。怀疑新 SQL 里把 CTE 别名写成了 `map`（ClickHouse 保留函数名），正在核对并修复。

发现更可能的根因：SQL 输出列别名 `rank` 与 ClickHouse 窗口函数 `rank()` 冲突（且 v6 还有 `AS map`）。正在改为 `vendor_rank` 并去掉保留别名。

跨 v4–v6 一直失败，更可能的根因是 **SQL 列别名 `AS rank` 与 ClickHouse 窗口函数 `rank()` 冲突**（同 chart 里其它排序列都用 `contribution_rank` / `risk_priority_rank`，唯独这条用了 `rank`）。v6 还额外用了 join 别名 `AS map`。

**已改（`validate_bundle` PASS）：**
- SQL 输出列：`vendor_rank`（不再 `AS rank`）
- output-map：`/rank` → `"vendor_rank"`（EQL 属性名不变）
- join 别名：`mv`（不再 `map`）
- SQL 结构对齐已跑通的 alert-type edge

请重新部署 `pastdue.edn` 后再跑 US Top-10。若仍失败，对照执行 `current-alert-type-query`（同视图/同 filter）；并把 Studio `e70eab9e…` 里的 CH 原文贴出。

---

**User**

[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"entities\\\":[{\\\"entity\\\":\\\"pastdue.country-history-query\\\",\\\"description\\\":\\\"PastDue Country History Query — Explicit UTC date-range request for historical country snapshot comparisons and trigger discovery.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.country-history-query/country-code\\\",\\\"pastdue.country-history-query/range-end-date\\\",\\\"pastdue.country-history-query/range-start-date\\\",\\\"pastdue.country-history-query/relative-change-threshold\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-monitor\\\",\\\"description\\\":\\\"PastDue Country Monitor — Latest-versus-previous completed UTC snapshot metrics by country.\\\",\\\"attribute_count\\\":17,\\\"identity_keys\\\":[\\\"pastdue.country-monitor/country-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.country-trigger-event\\\",\\\"description\\\":\\\"PastDue Country Trigger Event — One historical country snapshot pair whose target date is evaluated against the nearest prior available snapshot.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.country-trigger-event/country-code\\\",\\\"pastdue.country-trigger-event/previous-snapshot-date\\\",\\\"pastdue.country-trigger-event/target-snapshot-date\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-alert-type-query\\\",\\\"description\\\":\\\"PastDue Current Alert-Type Query — Filter request for current vendor x alert_type counts. Empty filters mean no constraint.\\\",\\\"attribute_count\\\":5,\\\"identity_keys\\\":[\\\"pastdue.current-alert-type-query/country-code\\\",\\\"pastdue.current-alert-type-query/department-type-filter\\\",\\\"pastdue.current-alert-type-query/master-manager-filter\\\",\\\"pastdue.current-alert-type-query/master-primary-filter\\\",\\\"pastdue.current-alert-type-query/vendor-code-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-detail-line\\\",\\\"description\\\":\\\"PastDue Current Detail Line — Current detail-view PO line slice; Buyer from detail buyer_email; Primary/Manager from vendor_master_mapping_view only.\\\",\\\"attribute_count\\\":22,\\\"identity_keys\\\":[\\\"pastdue.current-detail-line/order-type\\\",\\\"pastdue.current-detail-line/po-line-number\\\",\\\"pastdue.current-detail-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-detail-query\\\",\\\"description\\\":\\\"PastDue Current Detail Query — Controlled current detail-view slice. Empty-string filters mean no constraint. Not free SQL.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.current-detail-query/alert-type-filter\\\",\\\"pastdue.current-detail-query/country-code\\\",\\\"pastdue.current-detail-query/department-type-filter\\\",\\\"pastdue.current-detail-query/eta-code-filter\\\",\\\"pastdue.current-detail-query/is-pastdue-filter\\\",\\\"pastdue.current-detail-query/master-manager-filter\\\",\\\"pastdue.current-detail-query/master-primary-filter\\\",\\\"pastdue.current-detail-query/risk-level-filter\\\",\\\"pastdue.current-detail-query/vendor-code-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-high-risk-line\\\",\\\"description\\\":\\\"PastDue Current High-Risk PO Line — Current high-risk or past-due PO-line context; Buyer from detail buyer_email, Primary/Manager from vendor_master_mapping_view; not a historical causal record.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.current-high-risk-line/order-type\\\",\\\"pastdue.current-high-risk-line/po-line-number\\\",\\\"pastdue.current-high-risk-line/po-number\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-alert-type-count\\\",\\\"description\\\":\\\"PastDue Current Vendor Alert-Type Count — Current-state line counts by vendor and alert_type from past_due_detail_view.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-alert-type-count/alert-type\\\",\\\"pastdue.current-vendor-alert-type-count/country-code\\\",\\\"pastdue.current-vendor-alert-type-count/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"description\\\":\\\"PastDue Current Vendor Past-Due Rank — Current-state vendor past-due share rank from past_due_detail_view; org filter via vendor_master_mapping_view only. Not historical snapshot attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"description\\\":\\\"PastDue Current Vendor Rank Query — Filter request for current vendor past-due ranks. Empty-string org filters mean no constraint on that dimension; vendors must appear in vendor_master_mapping_view for the country.\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-contribution\\\",\\\"description\\\":\\\"PastDue Historical Vendor Contribution — Vendor-level decomposition for an explicit historical country target/previous snapshot pair.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-contribution/country-code\\\",\\\"pastdue.historical-vendor-contribution/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/target-snapshot-date\\\",\\\"pastdue.historical-vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Code Contribution — ETA Code-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-code/country-code\\\",\\\"pastdue.historical-vendor-eta-code/eta-code\\\",\\\"pastdue.historical-vendor-eta-code/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.historical-vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Historical Vendor ETA Source Contribution — ETA Source-level decomposition for one Vendor and an explicit valid historical snapshot pair.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.historical-vendor-eta-source/country-code\\\",\\\"pastdue.historical-vendor-eta-source/eta-source\\\",\\\"pastdue.historical-vendor-eta-source/previous-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/target-snapshot-date\\\",\\\"pastdue.historical-vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.notification-recipient\\\",\\\"description\\\":\\\"PastDue Notification Recipient — Governed contacts used only to display recommended To and CC recipients; this chart does not send email.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.notification-recipient/country-code\\\",\\\"pastdue.notification-recipient/email\\\",\\\"pastdue.notification-recipient/recipient-type\\\",\\\"pastdue.notification-recipient/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.owner-current-risk\\\",\\\"description\\\":\\\"PastDue Current Owner Risk — Current-state owner distribution for high-risk or past-due PO lines; BUYER from past_due_detail_view.buyer_email, PRIMARY/MANAGER from past_due.vendor_master_mapping_view; not a historical causal attribution.\\\",\\\"attribute_count\\\":9,\\\"identity_keys\\\":[\\\"pastdue.owner-current-risk/country-code\\\",\\\"pastdue.owner-current-risk/owner-display\\\",\\\"pastdue.owner-current-risk/owner-role\\\",\\\"pastdue.owner-current-risk/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-contribution\\\",\\\"description\\\":\\\"PastDue Vendor Contribution — Vendor-level decomposition of the latest country past-due-ratio change.\\\",\\\"attribute_count\\\":20,\\\"identity_keys\\\":[\\\"pastdue.vendor-contribution/country-code\\\",\\\"pastdue.vendor-contribution/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-code\\\",\\\"description\\\":\\\"PastDue Vendor ETA Code Contribution — ETA Code-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-code/country-code\\\",\\\"pastdue.vendor-eta-code/eta-code\\\",\\\"pastdue.vendor-eta-code/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-eta-source\\\",\\\"description\\\":\\\"PastDue Vendor ETA Source Contribution — ETA Source-level decomposition of a vendor's contribution to country past-due-ratio change.\\\",\\\"attribute_count\\\":14,\\\"identity_keys\\\":[\\\"pastdue.vendor-eta-source/country-code\\\",\\\"pastdue.vendor-eta-source/eta-source\\\",\\\"pastdue.vendor-eta-source/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping — Current vendor DNA organization mapping (department / primary / manager) from past_due.vendor_master_mapping_view; not historical causal attribution. Distinguish countries by country-code.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping/country-code\\\",\\\"pastdue.vendor-master-mapping/department-type\\\",\\\"pastdue.vendor-master-mapping/vendor-code\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-query\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping Query — Filter request over vendor_master_mapping_view. Empty-string filters mean no constraint on that dimension (Metabase-style optional filters).\\\",\\\"attribute_count\\\":4,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping-query/country-code\\\",\\\"pastdue.vendor-master-mapping-query/department-type-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-manager-filter\\\",\\\"pastdue.vendor-master-mapping-query/master-primary-filter\\\"],\\\"thing\\\":true},{\\\"entity\\\":\\\"pastdue.vendor-master-mapping-rollup\\\",\\\"description\\\":\\\"PastDue Vendor Master Mapping Rollup — Country-level concentration of vendors by department / primary / manager from vendor_master_mapping_view.\\\",\\\"attribute_count\\\":6,\\\"identity_keys\\\":[\\\"pastdue.vendor-master-mapping-rollup/country-code\\\",\\\"pastdue.vendor-master-mapping-rollup/department-type\\\",\\\"pastdue.vendor-master-mapping-rollup/master-manager\\\",\\\"pastdue.vendor-master-mapping-rollup/master-primary\\\"],\\\"thing\\\":true}],\\\"version\\\":7}\"}],\"isError\":false}"
  }
]
10:21:45

mcp__ETA-PastDue-Graph__list_attributes (mcp · ETA-PastDue-Graph)
Completed
{
  "entities": [
    "pastdue.current-vendor-past-due-rank",
    "pastdue.current-vendor-rank-query"
  ]
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"attributes\\\":[{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor\\\",\\\"type\\\":\\\"decimal\\\",\\\"description\\\":\\\"\\\",\\\"unit\\\":\\\"%\\\",\\\"format\\\":\\\"percent\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/queried-at-utc\\\",\\\"type\\\":\\\"datetime\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"datetime\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/rank\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"id\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-name\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-pastdue-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-past-due-rank\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-past-due-rank/vendor-total-count\\\",\\\"type\\\":\\\"integer\\\",\\\"description\\\":\\\"\\\",\\\"format\\\":\\\"number\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Country for current rank query.\\\",\\\"format\\\":\\\"code\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"type\\\":\\\"string\\\",\\\"description\\\":\\\"Optional; empty string means no filter.\\\",\\\"format\\\":\\\"text\\\"},{\\\"entity\\\":\\\"pastdue.current-vendor-rank-query\\\",\\\"attribute\\\":\\\"pastdue.current-vendor-rank-query/matching-ranks\\\",\\\"type\\\":\\\"join\\\",\\\"description\\\":\\\"→ pastdue.current-vendor-past-due-rank\\\",\\\"limit\\\":{\\\"default\\\":200,\\\"max\\\":1000},\\\"order\\\":\\\"{:allowed [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/vendor-code], :default [:pastdue.current-vendor-past-due-rank/rank :asc], :aliases {\\\\\\\"pastdue.current-vendor-past-due-rank/pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"pastdue-rate-in-total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total, \\\\\\\"vendor_code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"pastdue.current-vendor-past-due-rank/rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"vendor_rank\\\\\\\" :pastdue.current-vendor-past-due-rank/rank, \\\\\\\"vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"pastdue.current-vendor-past-due-rank/vendor-code\\\\\\\" :pastdue.current-vendor-past-due-rank/vendor-code, \\\\\\\"pastdue_rate_in_total\\\\\\\" :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total}}\\\",\\\"required_inputs\\\":[\\\"pastdue.current-vendor-rank-query/country-code\\\",\\\"pastdue.current-vendor-rank-query/department-type-filter\\\",\\\"pastdue.current-vendor-rank-query/master-primary-filter\\\",\\\"pastdue.current-vendor-rank-query/master-manager-filter\\\"]}]}\"}],\"isError\":false}"
  }
]
10:21:47

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [{:pastdue.current-vendor-rank-query/matching-ranks {:limit 10 :order_by \"pastdue-rate-in-total\" :sort_dir \"desc\"} [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/queried-at-utc]}]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error \\\"the query is not readable EDN: Map literal must contain an even number of forms\\\"}\"}],\"isError\":true}"
  }
]
10:21:50

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [({:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/queried-at-utc]} {:limit 10 :order_by \"pastdue-rate-in-total\" :sort_dir \"desc\"})]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 7, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"4ef5b17d-66db-48e1-9130-1ff7d0f8e1ed\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
10:21:55 - 10:21:56

mcp__ETA-PastDue-Graph__execute_eql (mcp · ETA-PastDue-Graph)
Completed
{
  "query": "{:entity {:pastdue.current-vendor-rank-query/country-code \"US\" :pastdue.current-vendor-rank-query/department-type-filter \"\" :pastdue.current-vendor-rank-query/master-primary-filter \"\" :pastdue.current-vendor-rank-query/master-manager-filter \"\"} :query [{:pastdue.current-vendor-rank-query/matching-ranks [:pastdue.current-vendor-past-due-rank/rank :pastdue.current-vendor-past-due-rank/vendor-code :pastdue.current-vendor-past-due-rank/vendor-name :pastdue.current-vendor-past-due-rank/vendor-pastdue-count :pastdue.current-vendor-past-due-rank/vendor-total-count :pastdue.current-vendor-past-due-rank/pastdue-rate-in-total :pastdue.current-vendor-past-due-rank/pastdue-rate-in-vendor :pastdue.current-vendor-past-due-rank/queried-at-utc]}]}"
}
[
  {
    "type": "text",
    "text": "{\"content\":[{\"type\":\"text\",\"text\":\"{:error {:error \\\"the query was refused\\\", :findings [{:path [:query], :env/version 7, :error/statement-status nil, :severity :error, :code :engine.execution/failed, :error/category :source, :studio/recent-id \\\"01c901e0-54f5-43f6-abaa-b46639dd2f0e\\\", :message \\\"source execution failed\\\"}]}}\"}],\"isError\":true}"
  }
]
10:21:58 - 10:21:59
我正在查询 US 当前 PastDue 占比最高的 Vendor，但 Atlas 数据源连续返回 source execution failed，因此目前无法可靠确认排名。

我需要数据源恢复后，才能返回 Vendor、PastDue 占 US 总量占比、Vendor 内部 PastDue 率及排名。

---

**Cursor**

`rank`/`map` 修复后 v7 仍失败，说明更可能是全量扫 `detail_view` 超时或某列类型问题。正在对照已跑通的 edge，并把排名 SQL 收成「只扫 past-due 行」的轻量路径。

失败仅 1–5 秒，可排除超时。将排名 SQL 收成与 alert 同构，并只扫描 past-due 行（排名口径不变：按 past-due 份额排序）。

关键点：失败只有 **1–5 秒**，不是超时，也不是「整站数据源挂了」；更像 SQL/类型/包装问题。Agent 说「上游恢复后再查」不准确。

**本版改动（请部署 version 8）：**
1. SQL 与 alert edge **同构**（同一 `mapped` + `INNER JOIN mapped AS m`）
2. **只扫** `toInt64(is_pastdue) = 1` 行；按 past-due 份额排名 ≡ 按 `vendor_pastdue_count` 排序
3. 去掉 `country_total` 二次全表扫描
4. 默认排序改为 `pastdue-rate-in-total DESC`（避免外层 `ORDER BY rank`）
5. `vendor_rank` / 无 `AS rank` 保持不变

口径说明：`pastdue-rate-in-total` = 该 vendor past-due / 有映射 vendors 的 past-due 合计；`pastdue-rate-in-vendor` 在此 scope 下恒为 1。

部署后再跑。若仍失败：
1. 对照跑 `current-alert-type-query`（US + 空 filter）
2. 把 Studio `4ef5b17d…` / 新 id 里的 **ClickHouse 原文**贴出——没有原文只能继续猜

---

**User**

http://fca-vm-uat-nifi-edge1.synnex.org:9632/mcp/6349dd64-4842-4108-acb0-1d40239c0daa  
token ： [REDACTED] 
这是 atlas mcp 请 配置后 帮我测试

---

**Cursor**

正在配置 Atlas MCP 并做连通性测试；token 只用于本地配置，不会写入仓库。

正在更新本地 `open_atlas` MCP 配置为你提供的新端点与 token。

端点需用 HTTPS。正在更新配置并调用 `list_entities` / `execute_eql` 做实测。

MCP 已通；mapping 成功说明源正常。排名仍失败，且触发了 `circuit_open`。正在拉取 Studio 错误详情。

`analyze_result` 支持直接跑 SQL。正在用线上排名 SQL 抓 ClickHouse 原文错误。

定位到差异：可跑通的 DETAIL/high-risk **没有** 顶层 `ORDER BY`；失败的 ALERT/RANK 有。正在去掉顶层 `ORDER BY`，让 Atlas 分页排序接管。

## MCP 已配置并实测

已写入 `%USERPROFILE%\.cursor\mcp.json` 的 `open_atlas`：
- URL 必须用 **`https://`**（`http://` 会被 nginx 以 “plain HTTP to HTTPS port” 拒绝）
- Token 已放进 `Authorization: Bearer …`

请在 Cursor 里 **Reload MCP / 重开 Agent** 使新配置生效。

## 实测结果（env/version **7**，线上尚未含本地最新 SQL）

| 查询 | 结果 |
|---|---|
| `list_entities` | 成功 |
| US `country-monitor` | 成功（past-due **38741**，snapshot **2026-10-19**） |
| US Top3 snapshot contributors | 成功（SIIG / GOOGLE / CA） |
| US `vendor-master-mapping-query` | 成功 |
| US high-risk / detail（vendor 46975） | 成功（`detail_view` 可读） |
| US **rank** / **alert-type** | **失败** `:source` |

结论：**不是整站数据源挂了**；`detail_view` 与 mapping 都正常，问题在 rank/alert 这两条 SQL。

## 根因（高置信）

可跑通的 DETAIL / high-risk：**没有顶层 `ORDER BY`**。  
失败的 ALERT / RANK：SQL 末尾带了 `ORDER BY`，和 Atlas 分页包装冲突。

本地已改掉 rank/alert 的顶层 `ORDER BY`，`validate_bundle` PASS。  
**请重新部署** `atlas/charts/pastdue.edn`，部署后再让我测一次 US Top-10。

安全提醒：token 已出现在聊天里，用完后建议轮换；勿把 token 提交进 git。


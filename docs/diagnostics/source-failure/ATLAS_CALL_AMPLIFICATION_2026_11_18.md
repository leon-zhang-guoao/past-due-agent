# 2026-11-18：Atlas 调用放大与报告错误审计

依据：用户提供的完整过程 `fbc68370-a18c-460e-bfc2-0b0288b54355/已粘贴的文本.txt`。下文行号均指该附件。仅审计记录，未连接线上 Atlas，未修改 graph、Skill 或 Atlas 源码。

## 结论

本轮主要问题是 agent 在多页、多次投影之间没有可靠维护事实和调用关联：读过的数据重复读取，投影不完整被误当成源查询问题，出现无理由重跑，最后还发生 Vendor 数据串用和已读事实漏交付。分页重复计算增加开销，但本轮没有证据证明数据库查询本身长时间执行。

另有独立的 DuckDB 配置错误，以及严重的请求/响应关联异常。后者可能来自宿主转发、工具显示/导出或真实调用链，不能根据粘贴记录就认定 Atlas 缓存出错或 agent 编造 handle。

上一轮提出的 page-size=1、末页回到第一页循环，均未被本轮记录支持：所有11次report-v2请求page-size都是50，没有显示末页回0后再次取第一页的循环。

## 实际调用与耗时

| 类型 | 次数 |
|---|---:|
| list_entities | 1 |
| search_attributes | 4 |
| list_attributes | 3 |
| execute_eql | 13 |
| analyze_result | 32 |
| Atlas合计 | 53 |
| 所有已完成工具条目（含文件读取等） | 72 |

附件从17:53:30开始读Skill，最后工具返回17:59:38，随后交付部分报告，约6分08秒；没有呈现200多次Atlas调用或无限运行。若界面累计值是200多次，本附件不能解释其余调用或累计口径。

首个report查询17:55:30才开始，启动到该点约2分钟用于加载资料、发现schema、monitor/history及其他准备。已标注起止时间的报告源查询通常约1–4秒；不能把整轮分钟耗时等同为单条SQL耗时。analyze返回的部分stages只有几十毫秒，但这些不包含模型思考、宿主和网络的全部耗时。

## 1. 已成功读取后继续重复查询

US首个报告查询：行141–147，page-size50，首个handle记作H1（完整handle在原附件）。行147的读取已返回51行、54列，ordinal0–50，rows?/cells?均false，包含Country Control、Vendor指标、ETA摘要和check。后续反复换投影读取H1，没有理由把这些已读字段当未知。

行153再次读取控制；行157、161两次相同Vendor投影失败；行173成功读到Vendor指标；行253、257、281、301、305继续读取该页重叠数据。不同SQL不自动等于新增信息，只有新增缺失行/字段或必要的修正才构成进展。

32次analyze中30次有结构化成功响应，全部标记rows?=false、cells?=false。注意：这只证明各自SELECT结果完整，不证明每次都选了全量报告字段，也不能消除调用关联错误。

源查询参数完全相同的重复组：

- US cursor100：行177和185。
- CA cursor0：行201和209。
- CA cursor100：行225、237、245。

CA最后一页在行229已显示has-more=false，随后仍重跑同一cursor100。没有confirmed expiry证据，实际时间也未到所示15分钟过期点。

## 2. 控制/身份投影与业务事实混用，缺口没有稳定登记

行169只读US第二页的控制、身份和ordinal，没有指标/check数量。这是“该页业务字段未读”，不需要重跑源SQL；应在同一真实handle上只补缺失字段。

US cursor100的handle在行181只读了ETA/Check，筛掉该页3条VENDOR_METRICS。后来多次Vendor指标读取遍历H1、第二页和末页，却漏掉这个中间页handle。因此最终第6–8名缺指标，是读取计划遗漏，不是源数据不存在；这三家的check在行181/289已返回。

后续行281–297的多个投影还省略了row_ordinal及Country/P/T等关键身份字段，使已读事实更难与其他分区可靠对齐。按页面位置、显示排名或邻近行拼接容易串用Vendor。

## 3. 最终报告已经出现事实错误

| 最终输出 | 原始读取证据 | 问题 |
|---|---|---|
| SILVER PEAK 66081：指标5→154，净增+149，但Code/Source净增+81并标“已核对” | 行261指标+149；行285的该Vendor check是Code+149/0/净+149、Source+149/0/净+149 | 最终用了EATON 34038的摘要/check；EATON实际300→381，净增+81，见行265/293。|
| SONY 83314：“未读取完整核对值” | 行285已返回Code和Source的641→824、+185/−2/净+183，counts_match=true | 已读事实未被纳入交付。|
| 第6–8名笼统未读取指标及ETA | 行181/289已返回这三家的ETA/check；Vendor指标是对应页的WHERE筛掉后未补读 | 应分别登记指标缺失和已读摘要，不能将整模块称为未读。|
| CA没有报告章节 | 行269/273/277已返回CA全部10家Vendor指标；另有部分CA摘要/check | 交付遗漏独立Country，不能用US局部缺口代替CA状态说明。|

这些证据足以判定最终报告有错误，不等于原始返回行已全部有可靠来源。由于下一节的关联异常，这份最终业务报告不应直接用于决策；不能只把它评价为“完整性不足”。

请求参数全程是TOP_N，最终却写“正向贡献Top10”。即使这次前10家恰好都为正，也不能据此认为查询执行了POSITIVE_TOP_N。附件未包含原始用户任务文本，不能进一步断定应选哪一种模式。

## 4. 请求/响应关联异常：必须单独定位

1. monitor（行93–95）和后面的US history（行133–135）显示完全相同的handle、capture及2349bytes；history后续却读取另一个未在展示的execute响应出现的handle。
2. US后续页及CA多次execute都显示US第一页的同一handle、capture、159751bytes，而下一个analyze使用另一个handle。
3. 行185请求US cursor100，行189读取对应展示handle却返回page-after-ordinal150、ordinal151–169。按本地graph分页条件，两者不可能是同一参数下的同一执行；记录链存在不一致。
4. 最强的单次对照：行249传入`result_2a4efb87-a966-45cc-87fe-1e5befa2bcef`，行251响应却标`result_16907961-7131-4d14-897e-1962a5d7e51d`。
5. analyze共使用9个没有出现在展示execute响应中的handle。不能据此断言这些handle由agent编造，也不能把看似合理的业务数值当作来源证明。

应对照同一真实tool call ID的提交对象与原始响应，定位宿主转发、工具UI/导出或服务端边界。用户不需要再找recent-id。若无法建立关联，只隔离依赖该结果的事实；继续重复源查询无法修复关联链。

## 5. 独立的DuckDB错误与参数错误

行157和161是完全相同的analyze参数，均报：

```text
Cannot change configuration option "memory_limit" - the configuration has been locked
```

这是Atlas结果分析阶段的DuckDB配置错误，不是ClickHouse SQL超时，也不是Vendor查询条件错误。原样重试没有纠正配置；已有完整首轮投影仍应保留。源码`duck.clj`的configure!设置memory_limit，但本地未重现具体锁定生命周期，因此不能仅凭静态代码认定完整底层原因。遵守用户要求，不改Atlas实现。

CA行197额外加入不存在的EQL属性`positive_contribution_share`（下划线），与正确的`positive-contribution-share`同时出现，导致unknown-attribute。它属于agent组装EQL错误，行201纠正后继续；不是本轮全部耗时的主因。

没有证据说明query=""、result_handles=[]在这轮造成选择器拒绝；虽然本地规范要求省略无用选择器，不能拿它们替代实际错误解释。

## 6. 设计中的放大因素

- report-v2每个源续页都重算历史扫描、聚合、排名、摘要，再按ordinal截取。本轮预期US170行需4页，CA136行需3页，而非每Vendor一次。分页本身的重算增加成本，但并不能解释反复读取和错误拼接。
- 旧reader以完整单handle报告为主，要求Control、Metrics、ETA/Check三次投影；新report-v2把它套在每个源页上。再叠加元数据读取、schema读取和补读，模型需要维护大量交错的页×字段状态。
- policy仍描述“report三类投影”，未给新分页报告一个简洁统一的读取主路径；其`:sql-shape`、`:read-sequence`等与新page metadata流程需要一起整理。
- 成本检查只按distinct source scopes，pagination和materialization被排除，并且cost-review-applies-to=INTERACTIVE；本轮TEST的重复页/读取缺乏明确的进展检查。
- 三次read_tool_result都报“reference unavailable in this Session”（行69/125/149）；之后重复发现schema，增加上下文与往返。3次list_attributes响应原始行合计约136KB，很多内容重复。附件整体约562K字符，存在明显阅读负担，但没有宿主token/截断证据，不能断言发生了上下文溢出。
- 原完成标准强调完整，却没有把“已读字段复用、身份绑定、下一次调用补哪个缺口、没有进展时如何收束”落实到足够简单的执行过程。增加更多禁止条款或全局调用硬上限都不是充分修复。

## 建议的修复顺序（本次仅诊断，尚未部署）

1. 先解决调用可关联性：execute产生handle，read使用该handle，响应回显一致，Country/P/T/cursor符合请求。关联失败局部隔离并报告，不靠重复查询碰碰运气。
2. 新分页report统一先做一次完整的显式字段投影，页大小50且无截断时直接复用这些行。只有真实字段/行截断或未投影才补读；旧单handle三投影保留为适用旧路径。先读完并绑定一个Country再处理下一个。
3. 每次调用都对应一个明确缺口：未取源页、未读ordinal窗口、缺失字段、一次有证据的纠正。已完整的(page,fields)不重复读，补读不重跑源查询。has-more=false停止该分页链；比较请求cursor与返回page-after-ordinal，禁止跳页、重复和重置循环。
4. 按真实scope键、Country/P/T、Vendor code、record type、dimension、row ordinal保存事实及字段集合，指标与ETA/check按身份结合。交付前逐Vendor对照net_change与该Vendor的指标；避免以显示位置或名字代替身份。
5. 对“漏读”精确补现有handle的缺口，例如US中间页3条Vendor指标；对已读check直接复用。遇DuckDB配置故障保持已有事实，失败只影响未读部分；同样参数失败不再原样重试。
6. 任意模式都检查执行进展与总成本，分页和retained reads计入成本；正常有限请求仍可完成。无进展时输出已验证结果和精确缺口，禁止一边继续无效调用一边把错配数据标已核对。
7. 完成后分别核对US、CA原始交付义务，再生成报告。当前agent、Skill、reader、policy及completion相关指引需同步整理；graph的字段精简/多次重算优化另做实际性能评估，不先改超时或扩大page-size。

按本轮记录，正常路径的源页需求为7次execute，加monitor/history和schema发现；每页至少一次retained read可形成一个明显小于53次的计划。实际读取分割取决于线上字节/字段截断，这不是固定次数上限，也不是五分钟SLA。

## 可复查的统计

开发工具（不安装到业务agent、不修改数据）：

```sh
python3 scripts/audit_pasted_atlas_trace.py '/Users/ameng/.codex/attachments/fbc68370-a18c-460e-bfc2-0b0288b54355/已粘贴的文本.txt'
```

实跑统计：53次Atlas，72条总工具完成记录，30次成功retained读且无平台行/单元格截断，2次memory_limit错误，1次read请求/响应handle直接不一致，9个read handle没有展示execute来源，3组完全相同源请求（其中一组重复3次）。该统计仅审计导出，不是线上端到端复现，也不证明修复后的agent行为。

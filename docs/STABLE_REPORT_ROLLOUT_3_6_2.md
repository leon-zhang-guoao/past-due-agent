# PastDue 3.6.2：保护原报告路径，分项验证优化

状态：本地候选，PENDING_LIVE_PILOT。文档中的“保护原路径”指接口、选择语义和读取流程兼容，不代表已认证的线上稳定版本。当前无法确认用户所说“五分钟版本”对应哪个归档，不能把任意历史 ZIP 当作线上基线。

## 执行计划

1. 保留3.6.0、3.6.1归档，独立发布3.6.2；graph保持3.6.0/revision8原字节。
2. 以选择语义决定接口，保留原report能表达的业务；无预警报告仍可执行。
3. 撤回所有场景统一宽字段整页读取，以及新增的逐Country草稿前置要求。
4. CURRENT默认保留explore；current-v2优先路由、宽字段整页读取分别作为候选，不整体推广。
5. 检查原SQL与实际读取投影、丢指标和Vendor错配回归、既有契约、Skill结构与归档完整性。
6. 交付最小更新与线上对照步骤，保留可逆切换；根据实测决定候选是否晋升。

## 本次落地

使用codebase-design明确接口职责，diagnosing-bugs把真实记录中的缺行/错配落实到可验证的数据接口，skill-creator与writing-for-agents收窄指令并按需加载参考。业务平台不需要另装这些开发技能，不增加运行时脚本、服务或分析子agent。

| 场景/策略 | 3.6.2行为 | 目的与限制 |
|---|---|---|
| DAILY、TEST、历史预警；显式正贡献Top N，N≤10 | monitor/history固定日期后，原report-query POSITIVE_TOP_N | 保留原选择与单次源结果；仍检查源/读取截断 |
| 单Vendor对比报告 | 原report-query SINGLE_VENDOR，top-n=1 | 包括零变化和改善；若要求v2独有覆盖证据则用v2 |
| 全符号Top N、多Vendor精确集合、N>10或覆盖证据 | report-v2 | 旧接口不能无损表达；不改成正贡献来减少调用 |
| CURRENT指定Vendor | 原有explore路径，优先已部署explore-v3 | 保留筛选、分母、选择/展开语义 |
| current-v2 | 可用候选，单独实测后决定默认路由 | 不宣称一次扫描就一定更快 |
| 原report读取 | 保留Control / Metrics / ETA typed reads，复用已读字段 | ETA读取补齐rank和selected count，便于与指标绑定核对 |
| 分页读取 | 按所需类型/字段读取；宽字段完整读取可选 | 行数≤200不保证文本、单元格或上下文不截断 |
| 减少重复 | 每次调用对应真实缺口，成功字段复用；无变化错误不原样重试 | 不设一律N次的完成上限，有限大请求可继续 |
| 交付 | 按scope+Country+Vendor code绑定，逐Vendor核对，覆盖所有请求Country | 已查到的内容不因局部失败丢弃；未知仍明确未知 |

保留原有串行源调用配置，不增加“必须先写完Country草稿才能查下一国”的屏障。技术读取状态只需支持本轮任务；不把简单问题变成一整套额外工具调用。

无预警与选择接口是两个独立维度：有效数据上的按需报告仍执行，实际trigger保持真实；只有显式测试才标TEST_FORCED。任意显式P/T、趋势、筛选与排名继续组合explore能力，场景表不是能力白名单。HTML、PO、Owner、联系人及展示规则沿用现有契约。

## 本地验证及证据边界

新增scripts/test_page_reads.py从当前graph提取并执行真实绑定SQL，包含Atlas追加ORDER BY/LIMIT形态，再通过开发用SQLite执行指引中的真实SELECT：

- US/CA原report Top10经三投影后，唯一ordinal覆盖完整，指标/摘要/check通过现有证据校验。
- 未触发预警的零PD、零变化和改善Vendor保留原SINGLE_VENDOR路径及正确signed check。
- 删除中间Vendor指标、把另一Vendor check数值拷入，校验必须拒绝完整/正确结论。
- 新分页接口可选全投影仍验证跨页Vendor、coverage与字段完整性。

首次回归发现原ETA投影缺少contribution_rank和selected_vendor_count，不能直接满足现有完整身份检查；已在原投影补齐这两个返回字段，不改源SQL。开发SQLite布尔类型与生产返回不同，在开发适配器恢复源布尔类型，未放宽生产校验。

完整离线检查、发布内容及哈希证据以包内references/release-manifest.edn为准。validate_bundle为结构检查；离线测试和本地合成ClickHouse不是实际22.8/JDBC性能、真实LLM输出或宿主关联正确性的证明。Skill结构校验也不证明模型会遵循指令。

Atlas、数据库、源policy、timeout、凭据及graph均不修改。未知handle来源、响应handle错配、DuckDB配置错误只能有界隔离并说明缺口；Prompt不能修复宿主转发或服务配置问题。没有承诺五分钟SLA。

## 需要更新的文件

线上已为graph3.6.0/revision8时：

1. 更新agent/SYSTEM_PROMPT.md的系统提示词。
2. 完整替换skill/pastdue-analysis目录（包括references及agents），使用3.6.2 Skill ZIP中的该目录。不要只换SKILL.md而保留旧reference。
3. pastdue-html-report无需因本补丁更新。新包含原文件用于闭包交付，不代表需要重注册子agent。
4. 不需要重新发布atlas/charts/pastdue.edn，不更新Atlas源码或sources.edn。完整ZIP作为审阅/归档包，本次最小部署只取上面两项。

若线上graph版本未知或不同，先对照已安装的revision、resolver、投影和source默认上限。不要为了本次Prompt修订直接覆盖线上graph，也不要用历史全包回滚graph；之前ORDER BY修复等差异需要独立保留。

3.6.1 ZIP保留原字节，仅供追溯，其整体默认策略已撤回，不推荐安装。

## 线上对照：给执行agent的步骤

使用用户实际已安装并能跑报告的版本作为baseline；先导出保存完整系统提示词和完整Skill目录，注明版本/时间。保存是后续真实回退依据；本仓库历史包没有线上基线认证。两组都用同一模型、工具版本、权限、数据范围，避免把模型/宿主变化误归因于Skill。

1. 先单Country执行同一条显式目标日期TEST或正贡献报告。日期取实际有效最近P/T，不照抄历史样例。两组固定相同Country/P/T/calendar/selection/N及完整报告要求；记录monitor/history、源执行、续页、读取、发现、纠正分别多少次及总耗时。对照最终Vendor code/rank、两期数量、分母、主Code/Source、signed check和Country交付。不得仅比较工具是否成功。
2. 再运行相同条件US+CA报告；各国数据均核对，各Country应有完整模块或真实局部缺口。检查中间页指标没有因WHERE被忘记，CA没有查询后遗漏。
3. 验证不触发预警仍可报告；单Vendor分别覆盖改善/零值；多Vendor集合包含无可读数据Vendor；全符号Top N不得变成positive-only。CURRENT另检查org过滤和三类分母；自由问题及上下文追问保持原集合。
4. 原路径正确性通过后，在独立试验中只改变一个因素：先相同源页比较typed与全投影；另一试验才比较CURRENT explore与current-v2。若无法对同一保留handle测试，明确不同capture，CURRENT数据漂移不算同事实矛盾。候选不得因另一项通过就自动晋升。
5. 每项填写下表，保存实际EQL、输入/输出关联和完整最终报告。无MCP时留未测，不填PASS。

| 项目 | baseline | 3.6.2/单一候选 | 差异/结论 |
|---|---|---|---|
| 请求、模型、日期/筛选/选择/分母 | 待填 | 待填 | 是否同语义 |
| 最终Country/Vendor及完整ETA/check | 待填 | 待填 | 正确性/局部缺口 |
| 源执行/续页/读取/发现/纠正次数 | 待填 | 待填 | 是否重复成功页、是否有实际缺口 |
| 总耗时及异常耗时所在调用 | 待填 | 待填 | 正常波动或真实退化 |
| 同scope来源与handle关联 | 待填 | 待填 | 已验证/未验证/错误 |
| 未预先公开的组合问题与追问 | 待填 | 待填 | 是否保持原自由分析能力 |

最低条件是同语义结果完整且正确、无Vendor错配、无无进展循环，并在多次可比运行中没有不可解释的时延/调用退化。先通过单Country再放到US/CA；已有五分钟经验用作实测参考，不能替代同条件记录。大请求以真实有限工作量评估，不设固定调用数一刀切。

## 回退与交付状态

若3.6.2试运行退化，恢复试运行前实际导出的Prompt与完整pastdue-analysis目录。graph、本轮未改的HTML Skill和平台设置保持现状。若用户没有保存旧安装内容，只能先补齐导出，不能声称仓库某ZIP必然能恢复“五分钟版本”。单项候选未通过时保留3.6.2默认路径，不为候选改动整体回退能力。

本轮完成范围为本地指引、回归和候选包交付；线上部署、性能对照、真实Agent验收尚未执行。没有自动push或远程发布。

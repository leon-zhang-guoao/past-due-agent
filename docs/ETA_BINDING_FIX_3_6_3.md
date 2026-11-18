# PastDue 3.6.3：修复ETA桶与核对合计混用

状态：本地修复候选，PENDING_LIVE_PILOT。延续3.6.2路由与能力范围；不修改Atlas代码、graph、source、指标、预警条件或权限。未在线部署。

## 修复内容

新记录已加载3.6.2，原report的数据成功返回。Agent生成展示SQL时，把SUMMARY的维度合计previous_total/target_total/net_change误用为桶数量，又把code/source写成CODE/SOURCE，随后误报源异常。这不是本轮超时或分页问题。

3.6.3对已有读取和展示步骤做局部修复：

1. result-reader中的C读取改为随包维护的references/report-eta-read.sql。它仍是一次相同handle上的保留结果SELECT，覆盖所有Code/Source/us摘要及check，保留原字段名、身份、ordinal和完整数量，不做JOIN/排名/业务重算。
2. 通过CASE按record_type屏蔽不适用字段：SUMMARY保留previous_past_due_count、target_past_due_count、past_due_count_change，维度合计在该投影中为NULL；VENDOR_CHECK保留合计与signed checks，桶占位字段为NULL。此NULL表示该字段不适用于这类记录，不是业务零、不表示源缺失。底层原始EQL与graph不变。
3. report-format在实际使用处明确桶P/T/delta及排序字段；维度使用小写code/source/us，US_SOURCE_SUMMARY的eta_value为Source名称。主变化采用abs(past_due_count_change)、target_past_due_count、eta_value的既有业务排序含义。
4. SKILL和系统提示词指向该读取与字段绑定；报告直接从已读typed行组装。已有完整原响应的同类字段可以复用，不为了套投影额外读一遍。若派生展示重复/为空，先核对原桶字段和dimension，再判断来源问题。

这是字段投影与指引修正，不是宿主硬门禁。实际Agent仍需遵循维护的读取与展示规则。三类typed读取保持相同源调用和保留读取结构；文件读取可能多一次，绝非减少所有调用或五分钟SLA保证。

无预警报告、任意符号Vendor、多个Vendor精确集合、自由问题、CURRENT explore及可选current-v2、HTML与Owner/PO等能力沿用3.6.2。分页v2使用本C投影时，控制/覆盖行及页元数据仍按result-reader读取并合并计数。可选宽字段投影仍包含原始合计，使用时同样遵守record_type字段含义。

## 验证

- 新test_eta_read.py先在旧C投影上失败：SUMMARY泄露合计、check泄露桶占位；修复后验证这些字段为NULL且所有实际桶值/check数量保持原值。覆盖Other、负变化、零值、小写dimension与Source名字。
- test_page_reads.py执行真实graph绑定SQL（含Atlas追加ORDER/LIMIT）与实际随包C投影，三类投影唯一ordinal覆盖完整，现有证据校验通过；US/CA、零变化、零PD、改善、缺指标与错配check均覆盖。
- 用用户本次日志在开发DuckDB重放：原错误SQL精确复现40个错误主变化单元格；执行本次随包C投影后，全部20家×2维度的主变化匹配原桶字段的独立选择，60组check的P/T/delta与桶求和一致。记录位于docs/diagnostics/source-failure/ETA_FIELD_FIX_REPLAY_3_6_3.json。此重放使用导出数据，不是新的在线EQL或来源关联认证。
- 完整离线结果、包内容和哈希以包内release-manifest.edn为准。开发SQLite与DuckDB测试、本地合成ClickHouse均不等于部署ClickHouse22.8/JDBC或真实模型行为验收。

## 安装范围

1. 备份平台当前系统提示词与完整pastdue-analysis目录。
2. 替换agent/SYSTEM_PROMPT.md。
3. 使用pastdue-analysis-skill-v3.6.3.zip中的skill/pastdue-analysis完整替换当前目录，包含新增references/report-eta-read.sql。只更新SKILL.md会留下旧读取/展示规则。
4. 进入新会话或用平台实际支持的方式重新加载Prompt/Skill；确认入口、policy、reader均为3.6.3，且能读到新增SQL。不能把文件已上传等同于已加载。
5. 已为graph3.6.0/revision8时不重新发布graph；不修改Atlas代码、sources.edn或超时。pastdue-html-report无需因本补丁替换/重注册。完整ZIP仅作审阅归档，本次最小部署只取Prompt与完整分析Skill。

## 线上验收与回退

先跑单Country、固定有效目标日期的TEST，再US/CA。可使用与本日志相同日期（2026-11-17）仅在该日期仍有权限且有效时，保留其实际previous日期。对最终报告核对：

- 每个Vendor必须有实际Code/Source桶P→T与signed delta，或有证据的局部失败。
- 若同一scope数据没有变化，CISCO Code应包含SH485→908(+423)、US385→663(+278)，而Code核对合计仍是3450→4298、+848；EPSON2655应SH36→115(+79)、NR78→128(+50)，核对+129。不要把这些历史数值当所有运行的固定预期。
- 查看真实调用：使用typed SELECT或复用完整同字段；不得用维度合计渲染桶。不得为已完整桶值反复做同类确认查询。
- 同时保留scope、handle、ordinal、来源响应及最终文本。任何响应handle错配仍隔离对应结果，核实原始call关联；该问题不在本补丁修复范围，不能豁免。
- 再验未预警报告、改善/零值、集合、全符号Top N和CURRENT追问，确认范围与分母不退化。详细性能对照与回退依据沿用STABLE_REPORT_ROLLOUT_3_6_2.md。

若新版本退化，恢复试运行前实际导出的Prompt和完整Skill，不回滚graph。旧3.6.2包原字节保留供比对，但不能把它认定为已验证稳定的线上版本。

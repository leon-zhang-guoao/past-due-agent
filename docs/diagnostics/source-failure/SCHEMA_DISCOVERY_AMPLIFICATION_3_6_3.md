# 3.6.3运行：大量工具调用后只交付两条Country汇总

## 结论与边界

输入：用户附件b7b32ab1-2d29-4769-8a43-a8899044f64c/已粘贴的文本.txt，共333个逻辑行（末尾换行统计332）。用户随后确认原始提问为“今日past due 情况”。按现有每日概况默认口径，US/CA国家汇总可以满足该请求，无需自动扩展完整Vendor报告；“今日”采用最新已完成快照时应明确日期与数据新鲜度，不能称为实时数据。

本次并非成功取得大量Vendor数据后在最终文本丢掉。唯一成功的业务EQL是country-monitor/all，数据读取明确只有CA、US两条。两个current-analysis Vendor请求均报timeout，未返回Vendor行。最终未披露这两个失败，而以“未达到告警阈值，因此不深挖”解释输出范围；该解释没有覆盖实际发生的失败。

3.6.3 Skill/policy/reader已加载。本次没有执行report-query、report-v2或explore-v3业务EQL，也没有读取report-eta-read.sql或report-format，因此上一轮ETA桶字段修复没有被本轮路径实际使用。不能把上一轮修复当成所有执行可靠性问题的解决。

## 调用统计

可重跑命令：

```sh
python3 scripts/audit_pasted_atlas_trace.py '/Users/ameng/.codex/attachments/b7b32ab1-2d29-4769-8a43-a8899044f64c/已粘贴的文本.txt'
```

统计结果：

| 工具类别 | 次数 | 实际作用 |
|---|---:|---|
| list_entities | 1 | 实体清单 |
| search_attributes | 10 | 字段搜索，其中4次返回空hits |
| list_attributes | 6 | 字段定义，多次重叠实体 |
| execute_eql | 3 | US/CA CURRENT Vendor查询各失败；Country监控成功 |
| analyze_result | 2 | 一次schema、一次真正读取两条Country业务行 |
| read_tool_result | 51 | 分段阅读工具输出，非51次数据库执行 |
| 文件read的Completed条目 | 6 | 说明/示例文件；起始SKILL响应没有对应Completed标题 |

合计79个Completed条目，其中22次Atlas工具调用、51次宿主工具结果读取。原始记录08:54:49到最后读取08:56:52，工具阶段约2分03秒；记录没给最终文本生成完成时间。首次业务执行直到08:56:24，前面约1分35秒用于说明、发现及读取。

## 为什么schema被读几十次

第52行list_attributes同时取explore-v3-query和explore-v3-fact，返回82个attributes，原输出41124字符。read_tool_result并未按请求的10000至18000字符返回：实际通常只给约2048字符，用start/end/next_offset标记。内容又是多层JSON/text转义，读到的是被切断的序列化字符串片段，并非紧凑字段表。

同一个reference在第60–232行被分段读取44次，总计读出89918字符区间，唯一覆盖41124字符，重复覆盖48794字符。开始顺序前进，随后反复回跳到18000、14000、10000、6000、3000等旧区间，再重新顺序读取到末尾。由于多层转义，仅看碎片理解字段费力，但重复区间和全量读取扩大了成本。

country-monitor同一reference又读6次，覆盖11952字符区间，唯一7398，重叠4254。加list_entities一次，合计51次结果读取。read_tool_result是宿主结果取回，不等于重新执行数据库SQL。

Schema发现也有重复：第52行已包含explore-v3-query，241行再次list；country-monitor在56/250/286行反复list。自然语言长串搜索多次空hits，未快速转为已有精确实体/属性。references/metric-definitions.md被错误访问为references/metrics/metric-definitions.md导致Download failed；这是Agent路径选择错误，附件里的Skill链接本身是正确路径。

## 路由及结果退化

1. 第236/245行已经读取explore-v3契约和完整EQL示例，示例为CURRENT。
2. 第254行转而发现旧current-analysis-query/fact，没有可见证据表明explore-v3不可用或已执行失败。
3. 第258/262行执行US/CA旧current-analysis，grain=VENDOR、vendor-code-filter与org过滤全空，属于按Country无Vendor约束的CURRENT查询。两个请求均在08:56:24–08:56:30返回source execution failed，category=timeout。该错误没有嵌套数据库明细，不能断言具体是哪层5秒限制，也不能宣称换explore后一定更快。
4. 第314行执行country-monitor/all成功；318行仅schema，322行读取实际rows=2，未截断。
5. 最终提供两条Country汇总，使用的是最新完成快照2026-11-17与前一日对比，不能视为失败CURRENT Vendor查询的同义替代。

结合已确认提问，本次两条Country汇总本身可以满足国家概况，不能判定漏交了用户明确要求的Vendor报告。前面的CURRENT Vendor发现、全Country Vendor查询和相关失败是无必要的额外工作。最终声称未触发预警后不深挖，却没有说明此前其实已尝试Vendor查询并失败，执行说明不完整。若用户另行要求完整报告、TEST或Vendor分析，才需要按其已授权范围继续，不受预警条件限制；本例不能被推广为所有问题只返回国家汇总。

## 独立的来源关联异常

314行execute返回handle result_aa5...及captured-at=1795000849133；318/322行读取的是另一个handle result_b193...，captured-at=1795049808729。后者没有出现在可见execute响应里。应核实真实call ID与原始请求响应；不能仅凭导出就判断Agent编造、Atlas缓存或宿主转发的具体原因，也不能把这两行认证为已完成来源关联校验。

## 修复方向

本轮不生成新部署包。根据补充原提问，修复目标是概况路径的执行效率与说明准确性，而不是强制增加Vendor输出：

- 先确定监控、快照报告、CURRENT问题及交付范围，按这份规范选择源查询；只有缺失的属性才做定点发现。
- 复用已包含精确输入的EQL和已发现schema。对截断schema只读取所缺部分，并使用实际next_offset；按有用字段完成判断，不把读完全部转义输出当作执行前提。若能在宿主改进，应优先提供解包后的结构化精简schema；本任务不能修改Atlas源码。
- 保留自由分析能力；旧接口仅在证据支持且语义等价时使用，不因为已读到字段就替换原计划。
- 本例应先查国家监控，确认日期/新鲜度、实际状态并交付概况；仅真实预警或用户明确的Vendor/实时分析要求才进入相应深入路径。额外查询如已执行失败，应按其对结论的实际影响说明；不要把未请求的Vendor任务硬变成概况的完成门槛。其他明确CURRENT/Vendor请求仍保持原范围，不能静默替换为快照国家监控。
- 验证需覆盖一次实际Agent的完整“意图→发现→执行→失败恢复→最终范围”过程。上一轮源SQL、读取及ETA展示回归不会捕获本轮路由偏离和schema读取回跳；不能再以那些测试通过来证明本轮已修。

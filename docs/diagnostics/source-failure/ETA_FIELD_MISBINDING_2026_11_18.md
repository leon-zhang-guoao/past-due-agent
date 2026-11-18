# 3.6.2 新运行：ETA字段误用及错误源异常判断

## 结论

这次已读到业务数据，但最终报告省略所有20家Vendor的Code/Source桶级主变化，并把自身加工错误归因为源汇总异常。主要原因在Agent读取后的字段选择及临时汇总SQL；这份记录不支持“数据库超时”“分页循环”或“桶源数据全都重复为Vendor总量”的判断。

没有更改或重新发布3.6.2部署文件。新增仅为本次本地诊断及重放证据。重放不证明线上handle关联正确，也不代表真实Agent已修好。

## 输入与测量

用户附件：/Users/ameng/.codex/attachments/5915f6b2-c990-443b-bb00-80251d6f0e96/已粘贴的文本.txt。下面行号均针对该附件。

- SKILL、policy、report-planning、result-reader显示3.6.2，不能把这次问题归于没有安装新Skill；系统提示词是否同步安装，附件未显示。
- 19:20:12开始可见记录，19:23:38最后一次工具调用后输出部分报告；可见工具阶段约3分26秒，最终文本完成时间未知。不是此前200次调用的同一种表现。
- 33次Atlas调用：5 execute、21 analyze、7 schema/search。5次execute是monitor、US/CA history、US/CA原report，未走report-v2，没有源请求原样重复。
- 19次成功业务读取均未报row/cell截断；2次读取仅schema。未见timeout或locked memory_limit。
- 第121/125行，19:21:54，原report US169个非control行、CA135个非control行已读回；另外已有control/类型汇总，报告总数170/136。

## 首个错误与错误诊断链

第129/133行：Agent自行编写WITH + JOIN + string_agg汇总，不是指引中的typed SELECT。实际dimension是小写code/source/us，SQL却连接CODE/SOURCE，主变化得到null。US_SOURCE_SUMMARY的eta_value是来源名，误筛eta_value='US'也得不到所需来源。大小写/含义错误不能解释为源无数据。

第153/157行：修正小写dimension，但仍使用错误字段：

| 用途 | 正确返回字段 | 本次错误使用 |
|---|---|---|
| 桶P数量 | previous_past_due_count | previous_total |
| 桶T数量 | target_past_due_count | target_total |
| 桶净变化及主变化排序 | past_due_count_change | net_change |
| Code/Source/us维度签名核对 | VENDOR_CHECK的previous_total/target_total/net_change/increase_total/decrease_total | 这些合计用于check是正确的 |

在原响应里，维度合计会随SUMMARY行重复。重复是返回契约的表现，不意味着桶数量字段重复。Agent把合计投影成每个桶数值，所有桶于是看似相同；后续“诊断”第145/149/161/165行又只读这些合计字段，反复看到同一个合计，没有回到原本已读到的桶字段。这是错误被二次查询自我强化。

例：US Vendor64956 CISCO，原响应第121行SH为485→908、+423；US为385→663、+278；NR为2211→2339、+128；RQ为41→60、+19；DD为196→187、−9。这些行的previous_total/target_total/net_change同时为3450/4298/848，那是Code维度合计。

CA Vendor2655 EPSON，SH为36→115、+79；NR为78→128、+50。Agent把两者都加工为114→243、+129，随后最终报告声称汇总桶异常。

## 重放证据

开发环境命令（仅读取附件，不连接Atlas）：

```sh
/tmp/pastdue-agent-sql-venv/bin/python -B docs/diagnostics/source-failure/replay_eta_trace.py '/Users/ameng/.codex/attachments/5915f6b2-c990-443b-bb00-80251d6f0e96/已粘贴的文本.txt'
```

重放用DuckDB加载第121/125行真实返回的列、类型与数据，执行第153/157行完整原SQL。输出Code/Source错误字符串与日志逐Vendor一致。只修桶字段和相应排序后，20家×2维度=40个主变化单元格均匹配从原桶字段独立选取的结果，Vendor指标及signed checks保持一致。早期大写dimension连接也精确复现null。详见ETA_FIELD_REPLAY_2026_11_18.json。

另核对US/CA各30条VENDOR_CHECK：所有维度对应的原桶P/T数量与delta求和，分别等于check的previous_total、target_total、net_change，counts_match均为true。这证伪本次导出里“桶数量全重复为Vendor总量”的断言；不等同于独立查库审计。

## 另一项独立异常

第81/85行history执行响应与monitor响应完全相同，第101行CA report响应与US report相同；第109/183行请求CA handle，返回却标US handle。另有3个读取handle未见于前面的execute响应。可确定导出存在关联异常，无法仅凭附件判断是UI复制、日志导出、宿主转发还是Atlas服务端问题。

该异常需要原始call ID关联核实，不能当作已解决，也不能用数值相似来修复。但同一US handle内原始桶字段和合计的区别已明确；误用字段、大小写筛选的复现不依赖猜测CA来源。即使关联异常另行修复，本次SQL仍会生成错误主变化。

## 我们交付中的缺口

- report-format.md第111行用“absolute net-change”指代桶变化，第116行只给P/T/net占位，没有明确绑定字段；而结果中存在真实字段net_change，含义为维度合计。这是会影响执行的歧义。
- result-reader的ETA查询同投影桶与维度合计，后面的文字未把二者做显式字段对照。data-contract也把它们列在同一Summary字段清单，增加误读成本。
- 既有规则已经禁止retained joins、排名重算，但Agent仍绕过typed路径构造临时展示SQL。说明增加文字限制不能算行为修复。
- 151项测试验证了结构、源SQL、保留读取和证据校验，没有执行这次模型自行生成的展示SQL，更没有模拟真实模型输出。不能凭其通过宣称用户问题已经解决。

## 对应修复范围

1. 在实际主变化规则处明确P/T/delta分别为previous_past_due_count、target_past_due_count、past_due_count_change；Code/Source/us用小写。排序明确abs(past_due_count_change)，保留既有Top5/Other展示规则。
2. 将桶展示与VENDOR_CHECK读取职责区分清楚，必要时以清楚的别名暴露bucket_previous_pd/bucket_target_pd/bucket_delta及check_previous_pd/check_target_pd/check_net_delta，避免同一展示步骤混用，不删业务能力、不改指标。
3. 报告从已验证typed行直接组装。若部署采用固定保留读取SQL，测试并随包交付该实际SQL；不要让每轮临时发明跨类型JOIN和重算排名。普通自由分析仍按其请求范围组合能力。
4. 本次真实数据与错误SQL用于展示层回归：错误列、dimension大小写、条件来源维度都需能复现并被拒绝。之后必须实际Agent跑同一请求，检查最终20家主变化；仅离线通过仍为候选。
5. handle异常单独核实原始请求/响应关联，不用增加无依据重查掩盖。

以上为已定位问题的修复范围，不是已发布的新版本。当前无在线Atlas连接，不能声称真实Agent输出或线上关联问题已经修复。

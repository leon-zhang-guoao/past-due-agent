> 后续现场已确认：current-v2为ClickHouse22.8 Code49 UNION列头错误；旧current-analysis为Code159/5秒超时。
> 修复与新验收见[revision10定点修复](CURRENT_V2_CODE49_REV10_FIX.md)。下文保留取得cause之前的推断边界，
> 不再以旧接口B作为可用恢复路径。

# env17：CISCO单Vendor及13家批量CURRENT查询失败

## 结论

两次均在数据源执行阶段失败，不是成功取数后的报告、ETA字段绑定、分页或retained读取失败。
4次execute_eql全部返回engine.execution/failed、error/category=source、env/version17，
没有成功handle，没有analyze_result调用。日志未包含ClickHouse code/message、JDBC异常或实际SQL。
因此可以确认失败层级，尚不能确认语法、类型/JDBC、权限、有效策略或SQL成本中的具体根因。
本次不能沿用以前已确认的Code159/5秒超时结论，也不能声称修复已经完成。

## 证据逐次对照

附件：d01b4e9c-b46d-48b3-a577-4a9cae556d9a/已粘贴的文本.txt，100个逻辑行。

| 运行 | 日志行 | 接口/范围 | 结果 | recent-id |
|---|---:|---|---|---|
| CISCO64956 |31–33|current-v2，US，[64956]，WITHIN_VENDOR，page100/cursor0|source失败|e8c0c7ef-6330-45a7-bcb1-f21056ca9149|
| CISCO64956备用 |65–67|explore-v3 CURRENT，joint Code×Source，同Vendor，page200/cursor0|source失败|2debc4b7-a033-4b5b-83e6-91bc7e213e85|
|13家批量 |92–94|current-v2，US，13个正确编号，WITHIN_VENDOR，page100/cursor0|source失败|9e3b73e3-0e52-4f94-8855-d8ae5904e094|
|13家备用 |96–98|explore-v3 CURRENT，joint Code×Source，同13家，page200/cursor0|source失败|43452401-4b7b-4fd4-a7a1-d1ce03ed6b6e|

4条原请求及脱敏响应保存在[current-env17](current-env17/)目录。
env/version17是平台环境版本，不等于graph release3.7或source/revision9。

## 已排除或尚不支持的解释

- EQL形状：4条均成功解析为entity/query，所需输入完整，投影均在本地3.7契约中。
  线上list_attributes也暴露相同输入类型与字段；这支持“接口存在”，不证明bindingSQL相同。
- 请求大小：依次1848、2731、1920、2803字节，均小于8KB。
- Vendor列表：13个正数编号完整、无漏项，page-size100合规；单Vendor也失败。
  所以不能将批量规模、Top10限制或逗号列表误传当作本次解释。
- 读取：没有返回成功handle，typed retained SQL根本没执行；缩小retained读取、改Markdown不能修复此故障。
- 分页：4次都是cursor0首请求，没有续页循环。page-size在聚合之后限制输出，不保证降低SQL成本。
- 指标类型：全投影含Decimal/DateTime，理论上可能涉及驱动转换；没有cause不能确认。
- 超时：本次只有source分类，无Code159、TIMEOUT_EXCEEDED或timeout分类。
  本地Atlas源码exec.clj的classify会把已识别timeout独立分类，但线上提交未确认。
  不以工具时间戳推导数据库执行耗时；时间戳也包含模型、工具、传输和展示。
- 原先双ORDER BY：本地revision9 current-v2结尾已无重复排序；线上实际binding未导出，不能凭schema确认部署了该修复。
- 缺数据：没有收到OK控制和NO_READABLE_ROWS业务状态。source异常不能解释为Vendor不存在或0逾期。

## 为什么换了3.7还失败

3.7新增的是live-overview与vendor-identity；这两次都没有调用它们。
当前已知Country+code场景按设计直接复用了current-v2，失败后换explore-v3。
3.7没有修改这两条已有接口的SQL。因此新增概况/名称能力没有修复原来实时细分查询的线上故障。
同时，两条路径都依赖past_due_detail_view，包含过滤、聚合/窗口、比率、覆盖等；
换接口不等于换成独立数据源或已证明可用的恢复通道。

工程缺口是：原本未获线上验收的current-v2被用作这些场景的默认路径；本地合成引擎通过
不能替代ClickHouse22.8/JDBC/实际源与部署策略验收。新场景的路由已经生效，底层取数能力尚未验证可用。
不能把这个问题继续解释为“Agent还缺少更多场景说明”。

## 单Vendor最终回答的独立问题

第70–73行输出Open64454、PD3903、Country PD38760、Country ratio12.85%、排名第1。
本附件的两次业务执行都失败，没有产生这些数字的成功响应。
若这些来自附件未包含的前一轮live-overview，应保留前次实际handle、Country+code、capture与查询范围，
明确“沿用前次查询”，不能说本次已刷新。该记录不足以证明这些数值造假，也不足以认证其来源。
本次能确定的是：Code/Source细分未取到；数字来源在给定证据内未验证。
批量最终回答正确披露未刷新，没有将用户贴的旧数量冒充实时结果。

## 耗时与Agent动作

总10次Atlas调用：4次execute、2次list_attributes、4次search_attributes；另外8次文件read、
1次read_tool_result。第一段8次Atlas，第二段2次；不是200次数据库查询。
单Vendor10:54:23到10:56:00约1分37秒，批量10:56:38到10:57:54约1分16秒；
只是导出可见墙钟区间，不是数据库CPU/执行时间。

首段read_tool_result使用sess-r9rtwfm2trwxs594:91返回“unavailable in this Session”，
它是宿主工具输出引用，区别于Atlas result_handle；先前list_attributes在导出中有内容。
4次search中2次自然语言长串无hits；两个schema输出约22K/41K字符，包含额外发现成本。
这些是执行效率问题，但后续真实execute已经发生，不是数据源失败的根因。

## 最小下一步：只做有区分度的只读验证

不修改Atlas实现、超时、连接、graph或Skill，不重发13家失败查询、不逐Vendor扇出。
若已有Studio失败详情/Builder诊断，先取其中一条的causes/sources/SQLState/vendor-code/
resolver-id/source-ref/stage，核对实际SQL/参数和binding尾部。recent-id只是定位键，
当前普通消费者MCP未必有读取能力；无法取到不作为继续排查的前提。

现有错误包装按本地Atlas源码会隐藏驱动message（engine.clj execution-message与
connector-sql exec.clj diagnose!）；安全诊断面才能看到脱敏cause。
本机CodeGraph对该路径无命中后才直接读源码；没有改Atlas代码。线上版本差异待确认。

没有诊断权限时，在Atlas按下列顺序执行，每项最多一次：

### A：同一个current-v2，只有投影变窄

执行[current-v2最小标量探针](current-env17/A-current-v2-scalar-probe.eql)。
输入和分页与CISCO失败请求相同，只去掉DateTime、Decimal及非必要字段。
它仍是US/64956、WITHIN_VENDOR，保留Code/Source数量与控制/分页元数据。

- 成功：证明这个输出路径可执行；继续比较所选字段/序列化/JDBC，不能马上认定某种类型必错。
  bindingSQL可能仍计算未投影表达式，所以失败也不能排除那些表达式的SQL问题。
- 同样失败：停止current-v2继续缩页/试字段。不能据此确认timeout或语法。
- 无论成功失败都保存原始响应；只有实际成功响应能提供可用handle。

### B：A仍失败时，用既有简化分组做范围对照

执行[旧接口单Vendor Code数量探针](current-env17/B-legacy-code-count-probe.eql)。
它固定US、64956，grain=VENDOR_CODE，仅返回Code、Open、PD与行数/ordinal。
这是诊断对照，不是最终报告，也不是语义相同的current-v2替代：旧接口的比率定义不同，
只比较本探针实际投影的原始数量。旧接口仍未保证较快，执行一次失败后即停止。

| A | B | 结论 |
|---|---|---|
|成功|不必执行|收窄到原完整输出/转换差异，需比较完整输出所缺字段；不重跑全部批量。|
|source失败|成功且完整可读|基础CURRENT明细/Code聚合可走通，优先调查current-v2/explore-v3的SQL、映射/转换及实际部署差异。未确定具体错误。|
|source失败|source失败|问题不局限current-v2；检查共享明细视图、账号/权限、有效超时、负载、驱动。|
|明确Code62/47/215等|任意|按实际语法/identifier/aggregate错误修SQL，不改Skill掩盖。|
|明确Code159/timeout|任意|对照实际SQL与有效source/JDBC预算，优化计算；小页不是计算优化。|
|业务NO_DATA/覆盖缺口|任意|与执行异常分开，不能按异常回退逻辑补零。|

execute对象只用实际工具支持字段：{"query":"<EQL文件内容>","retain_result":true}。
如果成功且需保留结果读取，用这一次响应的真实handle和analysis_version2；只读已投影字段，
检查源页及retained行/列截断。B没有显式源cursor，若expected-row-count超过实际返回条数，
本探针只能证明可执行，不能称数量完整。A只验证单个Vendor，不能认证批量完整交付。

## 本轮实际验证边界

已执行原附件审计（10次Atlas/4execute/0retained reads）；4条EQL输入/投影契约核对；
本地合成ClickHouse重放4条绑定SQL并模拟Atlas追加ORDER/LIMIT，全部可执行。
测试数据不包含生产编号，所以返回覆盖/无数据，而非真实Vendor指标；没有重现线上source异常。
该结果不能成为线上故障已修复的证据。见current-env17/captured-evidence.json。
本轮仅写排查证据与EQL，不变更部署文件/业务SQL，不生成新的发布包。

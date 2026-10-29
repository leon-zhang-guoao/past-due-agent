# 3.3.1 — 结果读取参数修复

状态：本地修复与打包，**PENDING_LIVE_PILOT**。没有执行离线测试、真实Atlas调用或线上验收。

## 本次证据

用户提供的3.3日志中，监控execute_eql成功返回保留handle。第一次读取同时提交
query=""、result_handle、result_handles=[]，工具拒绝；第二次加了非空EQL但仍保留
另外两种入口；第三次重复首次请求。三次均为exactly-one错误，尚未读到实际指标。
不能据此归因于ClickHouse、分页、单元格截断或报告格式。

## 修复内容与边界

- System Prompt及Skill前部加入结果读取前置规则；独立短文件result-reader.md必须完整阅读。
- 明确JSON输入选择器：query/result_handle/result_handles互斥；未使用的键必须省略，
  不是填空字符串/空数组/null。已有结果只使用实际execute返回的result_handle。
- 首次schema读取的最小形态为{"result_handle":"本次真实handle"}；随后读取行数据
  为result_handle加实际非空sql。sql不是第二个输入选择器。schema-only不等于已读到指标。
- 不把宿主展开的全部参数抄成调用；工具schema仍为准。其他非选择器字段仅按实际要求添加。
- 同一个失败读取最多一次真正删除冲突字段的纠正重试；禁止第三次重复、切模式重置计数、
  重跑源查询或加空EQL“修复”。
- 若工具schema强制同时要求互斥入口，或正确提交后仍失败，明确工具适配缺口，停止该读取，
  不用猜测null语义或修改SQL。只有实际出站证据支持时才能声称宿主注入字段。

Atlas EDN及所有SQL、指标、报告布局均不修改。Agent3.3.1兼容Atlas3.3.0/revision4。
独立短文件不是新增服务，也不需要线上脚本。不会解决尚未证实的宿主schema/适配层缺陷。

## 更新

已经部署3.3.0时，只替换agent/SYSTEM_PROMPT.md与完整Skill（含result-reader.md）。
完整包/Skill包使用3.3.1名称，保留3.3.0包，不覆盖旧版。此前未部署3.3Atlas的，按
DEPLOYMENT_V3.md完成原有部署步骤。没有git自动提交或生产连接。

## 最小真实验收

1. 开始新一轮测试，使用本次execute返回的新handle，不复用日志里的旧handle。
2. 首次analyze_result仅带result_handle这一输入选择器。检查实际出站参数是否仍出现
   query/result_handles。若工具表单禁止省略这些字段，记录schema required项和实际报错。
3. schema成功后，按实际列名提交result_handle+sql读取监控指标；确认不是只有列定义。
4. 对一次参数构造错误只允许一回纠正。如果仍失败，保存实际输入/嵌套错误，区分
   模型参数、schema限制、疑似适配问题，根因未证实不写成平台故障已修复。
5. 读到监控行后再验证单Country完整下钻、两国、两种语言。源分页/保留结果分页/
   字段截断和最终报告准确性仍依照现有规则逐项验证。

模型最终文字中说“读取成功”不是验收证据；需要实际工具返回的指标及对应调用。

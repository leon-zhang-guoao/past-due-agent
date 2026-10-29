# 3.3.2：依据真实工具契约优化读取

状态：**PENDING_LIVE_PILOT**。本地实现/打包，不代表Agent已成功运行。无离线测试。

## 已有证据及限制

用户提供的手动测试使用user-open_atlas、图版本13：analyze_result inputSchema
没有required，7个属性均可省略，additionalProperties=false，字段类型不允许null。
互斥约束来自描述/运行时。只传新result_handle被接受，返回匹配的handle、row-count2、
列定义和materialized:false。说明该连接支持省略参数，不需要空字符串/数组填充。
尚未提供SQL读取实际行、字段截断与完整报告通过的证据。

此前Agent日志使用ETA-PastDue-Graph。3.3.1已读取规则却仍填充全部参数；读取使用
的handle与可见execute返回不一致，重试只修改analysis_version。导出还有旧capture/
expiry疑点。这些分别是可见调用错误/关联异常，根因不能一概称为工具适配器故障。
图版本相同不证明两种连接暴露完全相同的tool schema。供应的schema描述含省略号，
因此也不把它写成所有工具描述的完整快照。没有在本地凭空构造平台schema。

## 本次实际修改

1. 更新现有result-reader.md，不再增加另一套规则文件：两步调用、具体监控SQL
   请求、handle/连接关联、读到schema后继续读取行、明确纠正路径。
2. 从“列出很多禁止项”改为从最小请求构造：schema仅handle；行读取handle+非空sql。
   其余query/result_handles和非必要默认字段不加入。实际可选高级分析模式保留。
3. 检查同一次execute→discovery→rows返回中的handle/连接；缺失来源不能用猜测替代。
   schema-only正常但不算指标读取完成。监控SQL以实际发现的19列为前提，无源SQL。
4. 互斥错误最多一次真正删除字段的纠正；改analysis_version不是纠正。过期需明确
   新采集链路，不能用无限重查掩盖参数错误。仍不绕过权限或缺失的读取能力。
5. 修正归因：混合参数报错只证明输入选择失败；只有实际schema/转换证据支持时
   才判适配问题。连接差异和旧handle异常保留未确认，不宣称已修复。

Atlas EDN/SQL、日期、指标、排名、ETA和报告模块不改。兼容现有Atlas3.3.0/revision4。
系统提示词/Skill/发布清单为3.3.2；完整包不把历史补丁说明当当前执行规范。

## 更新与真实验收

- 已部署Atlas3.3.0的只替换System Prompt和完整Skill（3.3.2 ZIP）。
- 在实际PastDue Agent同一入口使用新会话/新查询；不用附件的旧handle。
- 检查实际出站参数：先仅result_handle，返回列定义；随后相同handle+sql返回指标。
- 保存调用/handle关联及返回；读schema不等于读到数值，不用模型总结代替原始返回。
- 能读到监控数值后，再执行一个Country的测试，检查完整Vendor/ETA/Other/checks，
  再扩双国/双语/历史/自由问答。保留源分页与读取分页的独立覆盖要求。
- 若同入口仍发全字段，先检查实际模型调用及tool schema，不再盲目改业务SQL；
  若模型提交最小参数而转换后出现空字段，需要两段参数证据定位转换层。

当前仅完成基于证据的本地调用流程优化；本会话没有Atlas工具，未执行真实验收，
没有新增在线服务/运行脚本/邮件功能，也没有自动git提交。

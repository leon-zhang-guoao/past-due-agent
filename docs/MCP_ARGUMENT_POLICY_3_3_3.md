# 3.3.3：全局参数构造（避免一刀切）

状态：**PENDING_LIVE_PILOT**。契约与 Skill 文本更新，不代表线上 Agent 已跑通。

## 3.3.2 过窄之处

3.3.2 把默认 HANDLE 路径写成“retained 读取一律 omit query/result_handles”。
意图是去掉空占位，但容易被执行成：

- 禁止 `analyze_result` 的 QUERY / 多 handle 模式
- 或反过来仍把工具表单全填上（`query:""`、`result_handles:[]`）
- 把“少传字段”套到 `execute_eql`，误伤必填 `query`

2026-10-29 最新失败仍是：**选择器空占位 + 错误 handle**，不是业务 SQL。

## 分层规则（不要合并成一条禁令）

1. **全局 Presence（所有 MCP 工具）**  
   只传本调用有真实值的键。`""` / `[]` / `null` 视为已提交。  
   必填键必须带真实值，尤其 `execute_eql.query`。

2. **`analyze_result` 三选一，按意图选模式**  
   - HANDLE：本 Skill 默认（本次 execute 的 handle）  
   - QUERY：故意无 handle 的 fetch+analyze，不是 HANDLE 失败的退路  
   - HANDLES：本会话 ≥2 个自有 handle 的 UNION  
   `sql` 与选择器正交：省略=discovery，非空=读行，禁止 `sql:""`。

3. **默认路径仍是 HANDLE 两步**  
   discovery 仅 handle；读行 handle+SQL。不因此删除 QUERY/HANDLES。

4. **一次纠正保持原模式**  
   删未用选择器键，保留意图模式的真实值与本次 execute handle。  
   禁止靠改 `analysis_version` 或改模式来重置重试次数。

5. **不自动判适配器故障**  
   混合选择器只证明入参；需 submitted ≠ on-the-wire 才归因转换层。

未改：EQL 正文、指标、排名、ETA 诊断、报告模块、源分页与结果分页分离。

## 验收（部署后新会话）

- 出站 HANDLE discovery 只有 `result_handle`，且等于本次 execute。
- 随后同一 handle + 非空 sql 读到监控行。
- `execute_eql` 仍带非空 query；需要 handle 时带 `retain_result: true`。
- 若故意走 QUERY 或 HANDLES，另外两个选择器键必须不出现，且不得用空数组。

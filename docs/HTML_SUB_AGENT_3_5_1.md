# 3.5.1：HTML 展示委派给 Business Storyteller

本次更新将 HTML 页面设计、SVG/动画和文件写入移交给当前平台的
`business-storyteller` sub-agent。PastDue 主 agent 保留意图解析、Atlas
取数、业务校验、结论、Markdown、结构化数据交接及最终交付核验。
Atlas 图、SQL、指标版本与业务日期规则不变；本次不需要重新发布 Atlas。

## 输出行为

| 用户请求 | 主 agent | 子 agent |
|---|---|---|
| 默认 DAILY/TEST | 完整 Markdown 后问一次是否需要 HTML，结束本轮 | 不调用 |
| 明确要求 HTML | 完成取数/校验，不输出 Markdown 正文，提交 work order | 生成 Ops/Demo HTML |
| 回答“要 HTML” | 复用上一轮已验证绑定，提交 work order；丢失则 BINDING | 使用交接数据生成 HTML |
| 默认 HISTORICAL/INTERACTIVE | 正常 Markdown/问答，不主动询问 HTML | 不调用 |
| HISTORICAL/INTERACTIVE 主动要求 HTML | 完整 payload 可交接；事件列表或局部答案缺模块则 GATE | 保留真实 mode，按相同完整性要求处理 |

原 `agent/d.md` 是用户提供的通用 Business Storyteller 说明，保留原文作为
参考。PastDue 子任务使用专用提示词：事实来自父 agent 的 Atlas 验证绑定，
不再使用该通用说明的 Metabase 取数流程。动画技能与视觉叙事原则保留。

## 当前平台挂载

1. 使用 `releases/pastdue-agent-v3.5.1.zip` 更新主 agent 的
   `agent/SYSTEM_PROMPT.md` 和完整 `skill/pastdue-analysis/`。
2. 为现有 Business Storyteller 配置一个 PastDue 专用子 agent 配置，系统提示词
   使用 `skill/pastdue-html-report/references/business-storyteller.md`。通用
   Storyteller 如仍有其他用途，保留其原配置。给这个子 agent 绑定完整
   `skill/pastdue-html-report/` 和它可读取的
   `skill/pastdue-analysis/references/report-format.md`。按需绑定现有
   diagram-animation、svg-animation、isometric-animation；这些可选视觉技能
   不在本次发布包内，缺少时仍须能生成完整 Ops/Demo 页面。
3. 在平台原生 sub-agent 注册/关联处，让主 agent 可通过稳定名称
   `business-storyteller` 调用该子 agent，映射到平台真实 agent ID。主 agent
   需要调用/等待与文件回读能力；子 agent 需要写入/回读 HTML 的能力，浏览器
   用于视觉验证（无浏览器时应如实披露 NOT_RUN）。双方须能访问 work order
   中的 payload 文件和输出目录，或用平台原生 artifact 传递同一份内容。
4. 按 `skill/pastdue-html-report/references/sub-agent-contract.md` 传递 work order
   并返回 receipt。主 agent 等待完成，回读每份文件，核对嵌入 JSON、完整表格和
   展示内容后再发布链接。子 agent 缺失/调用失败报 DELEGATE，核验失败报 VERIFY。
   不允许主 agent 退回内联美化，或仅凭子 agent 的成功声明报 WRITTEN。
5. 开新会话验收：默认 TEST、确认 HTML、HTML_ONLY、绑定丢失、子 agent 不可用、
   返回假路径/篡改表格、按 Country 拆分。记录实际调用、写入及回读证据。

仓库不包含当前平台的管理 API、真实 agent ID 或已认证配置会话。因此这次
交付的是可部署 Prompt/Skill、交接协议、校验工具和发布包；平台挂载与真实
端到端 agent 验收仍为 NOT_RUN，不以本地测试替代。

## 验证与回滚

`verify_artifact.py` 是可选本地内容检查工具：比较真实 HTML 中的嵌入 payload、
规范表格、章节与文字；它不能验证 CSS 可见性或浏览器交互。Ops/Demo 切换、
`t` 定格、打印和 reduced-motion 需真实浏览器验证，不能仅凭检查器 PASS 声称通过。

```bash
python3 -B -m unittest discover -s tests -p 'test_html*.py'
python3 -B scripts/build_release.py
python3 -B scripts/build_release.py --check
```

3.5.1 是 agent 展示分层更新，兼容 Atlas3.5.0/revision6、Metric3.1.0。
发布包不包含私人会话、CSV、d.md 或平台凭据。回滚时恢复保存的主 agent
Prompt/Skill 和子 agent 关联；使用旧版本发布包，无需回滚 Atlas。

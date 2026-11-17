# 设计取证

本文件记录本地源码和文档事实。未连接线上源、Builder 或数据平面；不是数据剖析报告。

## 实现更新

用户明确授权参考现有 EDN 继续。新增 explore-v3 使用已有 scalar/entity/resolver/SQL
binding 形式，现为39 resolver、46 entity、821 attribute；既有38 resolver/SQL保持。
共享构建器已改为源／指标／选择／输出／分页阶段，取消 rsplit；F01、F08 是改前记录。
新增26显式输入，尚未实现原生默认参数；未添加不明格式的 saved query、metric 或知识激活。

| ID | 已观察事实与出处 | 对设计的影响 |
|---|---|---|
| F01 | 当前 v3.4.0 图包含 38 个 resolver、44 个 entity、739 个 attribute；由 scripts/edn_io.py 读取实际图得出 | 以实际图为基线，旧路径继续兼容 |
| F02 | scripts/build_explore_v2.py 的 INPUTS 为 20 个逻辑输入加 2 个分页输入 | 模型需复制大量参数；减少输入负担需要真实平台契约依据 |
| F03 | scripts/build_v31_contract.py 的 ranked 是无 PARTITION BY 的全局 row_number；top-n 截断 grouped FACT 行 | 逐日 Top N 与 Vendor 选择后展开不能由改变 grain 隐式获得 |
| F04 | interactive-planning.md 指导先查询 Vendor Top N，再传集合查询 ETA | 对象搬运、空集合和跨调用漂移仍需模型处理 |
| F05 | explore-v2.md 声明 [] 表示全部 Vendor | 空排名结果必须在内部保持 NONE，不能编码为 ALL |
| F06 | v2 每页重新运行源 SQL，fingerprint 是非密码学结果校验；没有数据修订号或事务快照保证 | 同一源查询可减少调用间搬运，不能宣称消除了所有一致性风险 |
| F07 | v2 以 CONTROL／FACT／VENDOR_COVERAGE 共用稀疏行；非对应类型的数字默认值不是观测值 | 输出身份与类型要更清楚，保留零值与无数据区别 |
| F08 | v2 build 通过 base.rsplit 切开既有 SQL 的末尾后加入分页 | 共享指标是优点；应以内部结构化阶段替代文本锚点依赖 |
| F09 | 图 vocabulary 没有独立 metrics 或 saved-query 节；terms 为空；execution operations 为空 | 不能据通用 Skill 直接发明 EDN 的 metric、保存查询或知识字段 |
| F10 | 本会话工具目录未发现 Builder、get_contract_guide 或 Atlas 工具 | 本轮可完成设计与问题清单，不能声称平台校验、保存、发布完成 |
| F11 | metric-definitions.md 明确当前 Open、历史 ETL、CURRENT_MAPPING、无修订号的限制 | 这些是与数据相关的知识候选，需绑定相关实体；现有说明在知识发布成功前保留 |
| F12 | result-reader.md 指导实际 handle 关联和 selector 参数；未安装宿主强制机制 | 图无法修复宿主序列化或伪造句柄；不要增加虚构的 enforcement 声明 |
| F13 | tests/fixtures/live-question-cases.json 标注 NOT_EXECUTED；该文件已经用于设计 | 需要真实 Agent 验收，也需要另行密封的保留题 |
| F14 | 既有文档与源码已经实现公式、三种分母及标准报告路径 | 复用既有确定性计算，不因新接口另抄一套公式 |

## 尚未观察的数据事实

真实键唯一性、重复率、日期缺口、源行数、状态码频数、实际刷新、连接只读性、
角色权限、真实执行计划、JDBC 类型传输、结果保留与修订能力均未在线检查。
源码中的合成数据测试不能填补这些项目；待 IT／Builder 可用后逐项记录证据。

## 下一步所需平台证据

读取 get_contract_guide，确认输入类型／可选值／默认值、resolver 输出形状、
保存查询格式、知识挂接方式、鉴权机制、分页和 retained result 语义。
每项记录实际工具版本、返回和适用范围，再决定 EDN 表达。

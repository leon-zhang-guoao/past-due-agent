# Changes

- 3.7.0：新增共享CURRENT概况和批量精确identity；复用既有current-v2做精确集合度量。
- 明确目前/当前/今日默认live；保留明确历史period与原report/TEST/monitor/explore路径。
- 新typed retained读拆开指标/coverage与bucket，复用已得字段，避免反复全schema及重复source查询。
- 本地测试实际执行新增SQL，包括Atlas追加ORDER/LIMIT；完整发布检查再验证原报告与打包闭包。
- 未改Atlas实现/凭证/timeout，未发布线上；所有线上acceptance状态仍NOT_RUN。

- 3.7.2/rev11：仅修identity CONTROL投影兼容；保留rev10 current-v2补行修复。新增原生22.8身份→数量整链回归；Skill按数量/分析区分所需度量，保留身份消歧及批量覆盖。线上仍待验收。

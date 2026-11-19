# Question coverage

| 用户原话/场景 | 路径 | 必要知识 | 可信参考值 | 状态 |
|---|---|---|---|---|
| 目前的past due情况是什么，实时非snapshot | live-overview perCountry/top5 | CURRENT明细与capture；缺失非0；unattributed | 业务值未提供；合成CA4/3 | 本地草稿，待线上 |
| 单独某个Vendor情况，给分析 | 已知code直接current-v2；name先identity | Vendor ratio、joint composition、同名不猜 | 业务值未提供；合成One Open2/PD1 | 本地草稿，待线上 |
| 一批name/code当前情况 | batch identity→Country+code集合→current-v2 | 原token覆盖、去重度量、0PD/缺失分开、分页 | 业务值未提供；合成1/2/3 | 本地草稿，待线上 |
| 历史报告/监控/TEST自由问答回归 | 原report/explore/monitor | 原快照/ETA/read契约 | 原本地fixture，不替代线上参考 | 原执行接口不变，回归检查 |

| code+name且Country未知，查询Past Due数量 | identity配对交集→current-v2 | count/行；CURRENT capture；歧义/缺失非零 | 业务参考未提供；合成F5 Open2/PD1仅测试 | 3.7.2原生22.8回归通过，待线上 |

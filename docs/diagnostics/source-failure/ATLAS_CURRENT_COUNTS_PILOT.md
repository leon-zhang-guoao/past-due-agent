# Atlas CURRENT数量试点：导入EDN后执行EQL

## 交付目的和实际状态

用户已在数据库客户端执行精简候选SQL并报告约1秒返回。该结果没有经过Atlas，
不证明Atlas连接、驱动、期限或取数已通过。本试点增加现有标量契约形式的图查询入口，
使Atlas执行绑定的SQL，业务Agent只提交EQL。

交付文件：

- pastdue-current-counts-pilot.edn：基于本地3.5.0图生成的完整试点图，新增一个查询resolver及绑定。
- current-counts-pilot.eql：下文完整EQL。
- current-counts-pilot-request.json：execute_eql结构化参数对象，retain_result=false。

完整试点图是候选导入文件，不是可直接合并的EDN补丁。实际平台schema校验、线上发布和
Atlas端SQL执行尚未完成。正式atlas/charts/pastdue.edn和3.5.0 ZIP没有被本次生成器覆盖。
所有39个既有resolver/SQL绑定、源槽及选择映射均保留；发布标识为3.5.1-counts-pilot/revision7。

## 1. 先部署查询入口（图维护人员）

通过现有Atlas图导入/更新流程处理完整试点EDN，使用实际工具/UI的校验与发布步骤。
先保留线上版本及回滚入口。核对线上基线与本地3.5.0是否相同；env/version=14是环境版本，
不能直接等同图release版本。线上有其他改动时，以导出的线上图作为生成器--base重新生成
并检查差异，保留它的实际源映射和权限，不直接用本地快照覆盖线上。
生成命令是开发环境操作，不是业务Agent运行步骤：

```bash
python3 -B scripts/build_current_counts_pilot.py --base /absolute/path/to/exported-graph.edn --output /absolute/path/to/new-counts-pilot.edn
```

图维护人员应核对新发布标识与实际平台修订规则。以上仅按已有EDN结构构建，不虚构平台导入API。
校验、审核和发布按现有授权流程执行，本文不等于已发布成功。

本试点是CURRENT、显式非空Vendor集合、无组织过滤的数量路径。
组织限制、全国share、排名、历史、日报、联系人、PO等请求继续使用现有支持路径。
实际Country/行权限继续由部署强制执行，Country参数不代替授权。

## 2. Agent发现能力

使用实际工具名（可能有前缀）。如工具schema相符，调用：

```json
{"entities":["pastdue.current-counts-query","pastdue.current-counts-fact"]}
```

这是list_attributes参数。先确认实体和rows join已存在，4个输入和下文输出字段均可见。
缺失则停止，报告“新EDN尚未导入/发布或当前连接未切换”，不要把EQL改成旧接口再复制参数。
若发布后仍看不到，核对服务版本/缓存/连接切换的真实机制；不猜已生效。

## 3. 执行完整EQL

将下文整个代码块作为字符串Q，按实际execute_eql schema调用：

```text
execute_eql({"query": Q, "retain_result": false})
```

也可直接使用配套current-counts-pilot-request.json的完整对象。
这是新入口的EQL，旧图没有该入口时无法执行；SQL由EDN内绑定承载。

```eql
{:entity {:pastdue.current-counts-query/country-code "US"
    :pastdue.current-counts-query/vendor-codes-json "[64342,75256,64351,19215,65505,75257,22344,85058,71886,48378,65885,75691,72478]"
    :pastdue.current-counts-query/page-after-ordinal 0
    :pastdue.current-counts-query/page-size 199}
  :query [{:pastdue.current-counts-query/rows [:pastdue.current-counts-fact/record-type :pastdue.current-counts-fact/scope-status :pastdue.current-counts-fact/country-code :pastdue.current-counts-fact/vendor-code :pastdue.current-counts-fact/vendor-name :pastdue.current-counts-fact/eta-code :pastdue.current-counts-fact/eta-source :pastdue.current-counts-fact/open-line-count :pastdue.current-counts-fact/past-due-count :pastdue.current-counts-fact/vendor-open-count :pastdue.current-counts-fact/vendor-past-due-count :pastdue.current-counts-fact/metric-version :pastdue.current-counts-fact/time-basis :pastdue.current-counts-fact/business-timezone :pastdue.current-counts-fact/queried-at-local :pastdue.current-counts-fact/requested-vendor-count :pastdue.current-counts-fact/expected-record-count :pastdue.current-counts-fact/expected-bucket-count :pastdue.current-counts-fact/row-ordinal :pastdue.current-counts-fact/page-after-ordinal :pastdue.current-counts-fact/page-size :pastdue.current-counts-fact/page-record-count :pastdue.current-counts-fact/next-after-ordinal :pastdue.current-counts-fact/has-more :pastdue.current-counts-fact/result-fingerprint]}]}
```

## 4. 绑定与完整性

只看record-type=FACT的字段作为测量值：每个Country/Vendor/Code/Source桶的PD/Open，
以及重复携带的Vendor总PD/Open；重复Vendor总量不相加。
CONTROL的scope-status=OK只证明输入有效；不是13 Vendor都有数据的证明。
VENDOR_COVERAGE为每个请求Vendor返回MATCHED或NO_READABLE_ROWS；它的数值占位不是0测量。
NO_READABLE_ROWS不能区分源缺失与访问过滤，不当作PastDue=0。
[]为INVALID_VENDOR_SELECTION，绝不隐式变成全国查询。此点与explore-v2/v3的[]含义不同。

该次实际响应若返回handle，H只从该次execute获取；按实际analyze_result schema读取。
可先用{result_handle:H,analysis_version:2}发现列，再以同H和SELECT * FROM data ORDER BY row_ordinal
读取。对象只用result_handle一个选择器；关联不明或响应handle与请求不同就隔离事实。

核对Country=US、CURRENT、metric-version=3.1.0、Vendor集合和真实捕获时间。
每页实际行数=page-record-count；row-ordinal=0的CONTROL每页重复，只计一次。
若has-more=true，复制next-after-ordinal到下一请求page-after-ordinal，其他输入不变。
源页最多199条非控制记录加1条CONTROL，适配现有resolver默认200，max1000。
每页都会重新执行CURRENT查询，持有各页各自真实handle；游标不是handle。
核对范围、全量计数及result-fingerprint一致，ordinal并集完整覆盖0..expected-record-count-1，
FACT数=expected-bucket-count，覆盖恰好13个请求Vendor；无行/单元格丢失裁剪。
FACT桶PD/Open加总分别等于Vendor总PD/Open。任何Vendor缺口须逐一说明。

result-fingerprint是非密码学漂移校验，不是事务快照保证。出现漂移则停止拼接，标记部分；
相同指纹不能证明无并发变化。桶PD=0但Open>0是有效测量，不能删除这些桶后仍声称桶全集完整。

## 5. 记录线上性能与失败

记录实际执行环境、发布版本、调用时间区间、源错误类别、返回计数/覆盖、截断、分页数，
以及数据库执行耗时（工具未给则UNKNOWN）。客户端总耗时不能代替数据库耗时。
本试点额外加入参数验证、Vendor覆盖、窗口计数/指纹/分页，和用户测得1秒的SQL并非逐字相同；
仍然没有全国分母、比率、历史源或映射JOIN。需重新测量，不能承诺1秒。

失败后保留完整请求与脱敏响应，同样参数不原样重试。若参数合法却仍timeout，
说明精简SQL在外部客户端成功尚未解决Atlas路径差异，继续查绑定/驱动/期限/真实计划。
新EQL成功则证明该次Atlas数量路径可用；不代表旧超时根因已完全确定。

## 6. 最终回答

按用户原始13 Vendor顺序交付：Vendor、当前PD总量、主要ETA Code/Source数量或明确缺口，
标明采集时间和范围；Code/Source分布可从完整联合桶按对应维度汇总，不做国家比率/排名重算。
没有完整事实时仅交付验证状态和已确认部分，不推算缺失Vendor。

最终附一张简表：实际图版本、执行总耗时、FACT桶数、覆盖Vendor数、是否完成分页/裁剪检查、
各缺口、原始错误（如有）。不要自授全面PASS；此试点不切换正式Prompt/Skill默认路由。
原有日报/历史/全国分母等接口保留，试点验证通过后再决定正式集成和发布。

## 本地验证证据

5项契约/合成SQL测试通过：EDN结构、全部旧resolver/SQL/映射保留、生成幂等、
零PD与缺失分离、规范化/重复桶、非法JSON/Int64/分页参数、分页并集/指纹/集合去重、
原13 Vendor集合。合成环境只创建当前明细表，没有历史/映射表，新SQL仍可执行。
未完成：平台导入schema校验、真实权限、JDBC、线上性能、Agent业务验收。

发布校验限制：本轮新增构建器/测试改变了工作区验证输入集合，旧3.5.0的build_release --check
报告Validation receipt inputs drift；未将旧receipt冒充本轮验证，也未重新生成正式发布包。
试点另行按上述5项测试验证，正式集成时须重新运行对应发布验证。

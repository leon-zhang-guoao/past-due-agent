# 发布EDN前：现有Atlas EQL对照

可以直接在Atlas EQL编辑器执行本文4条查询，不依赖新EDN，不用execute_eql的JSON包装。
本轮暂不导入3.5.1-counts-pilot，不修改图、SQL、源配置或期限。

## 能确定什么

对照同一个现有current-analysis-query入口，区分ETA分组粒度和分母选择的影响。
所有查询投影完全一致，Country=US、Vendor64342、无组织过滤，逐项变化仅在表中注明。
当前已知Alert路径返回事实，数据库客户端精简数量SQL约1秒；二者不是同一时刻的严格对照。

EQL只能选择已发布resolver/参数，不能临时替换其SQL。
本地旧接口SQL无论所选分母为何都在文本中声明country_total与filtered_total；优化器是否
裁掉未使用分支未知。改为FILTERED_POPULATION不是“保证不扫全国”的开关。
缩减输出字段也不保证绑定SQL少计算；这轮固定全部输出，避免混入投影变化。

因此：这轮可以缩小原因，不能承诺单靠EQL最终定位到CTE、驱动、期限或序列化的某一行。
如果4条仍同样失败，停止在既有EQL上继续排列参数，转到实际绑定SQL/计划取证。

## 执行方法

先确认当前连接能发现pastdue.current-analysis-query/rows，7输入及下文输出属性。
按T1、T2、T3、T4顺序各执行一次，并发1。编辑器只粘贴单个eql代码块。
某项明确权限/连接错误时停止受同一阻塞影响项；相同失败不原样重试。
若能看到前次SQL仍未终止，待确认终止后再执行下一项；取消状态不可见就记录UNKNOWN。
记录每次实际EQL、开始/结束/总耗时、环境版本、原始完整响应或错误category；
数据库耗时未知则写UNKNOWN，不用界面总耗时替代。不要只回复“成功/失败”。

| 项 | grain | denominator-scope | 与谁对照 |
|---|---|---|---|
| T1 | VENDOR | FILTERED_POPULATION | 简化分组的参考，不保证底层轻量。 |
| T2 | VENDOR_CODE | FILTERED_POPULATION | 对T1仅改变分组粒度。 |
| T3 | VENDOR | COUNTRY_ALL | 对T1仅改变分母语义。 |
| T4 | VENDOR_CODE | COUNTRY_ALL | 对T2仅改变分母；对T3仅改变粒度。 |

FILTERED_POPULATION在此请求中是单Vendor候选总体；COUNTRY_ALL是US全国总体。
两个share不同是预期，不能比较它们是否数值相等。PD/Open的定义保持原样。
CURRENT执行时刻不同，计数变化本身不自动视作缺陷。

## T1

```eql
{:entity {:pastdue.current-analysis-query/country-code "US"
    :pastdue.current-analysis-query/grain "VENDOR"
    :pastdue.current-analysis-query/vendor-code-filter "64342"
    :pastdue.current-analysis-query/department-type-filter ""
    :pastdue.current-analysis-query/master-primary-filter ""
    :pastdue.current-analysis-query/master-manager-filter ""
    :pastdue.current-analysis-query/denominator-scope "FILTERED_POPULATION"}
  :query [{:pastdue.current-analysis-query/rows [:pastdue.current-analysis-fact/country-code
      :pastdue.current-analysis-fact/vendor-code
      :pastdue.current-analysis-fact/vendor-name
      :pastdue.current-analysis-fact/eta-value
      :pastdue.current-analysis-fact/grain
      :pastdue.current-analysis-fact/denominator-scope
      :pastdue.current-analysis-fact/open-line-count
      :pastdue.current-analysis-fact/past-due-count
      :pastdue.current-analysis-fact/denominator-past-due-count
      :pastdue.current-analysis-fact/vendor-past-due-count
      :pastdue.current-analysis-fact/past-due-ratio
      :pastdue.current-analysis-fact/past-due-share
      :pastdue.current-analysis-fact/within-vendor-share
      :pastdue.current-analysis-fact/row-ordinal
      :pastdue.current-analysis-fact/expected-row-count
      :pastdue.current-analysis-fact/queried-at-utc]}]}
```

## T2

```eql
{:entity {:pastdue.current-analysis-query/country-code "US"
    :pastdue.current-analysis-query/grain "VENDOR_CODE"
    :pastdue.current-analysis-query/vendor-code-filter "64342"
    :pastdue.current-analysis-query/department-type-filter ""
    :pastdue.current-analysis-query/master-primary-filter ""
    :pastdue.current-analysis-query/master-manager-filter ""
    :pastdue.current-analysis-query/denominator-scope "FILTERED_POPULATION"}
  :query [{:pastdue.current-analysis-query/rows [:pastdue.current-analysis-fact/country-code
      :pastdue.current-analysis-fact/vendor-code
      :pastdue.current-analysis-fact/vendor-name
      :pastdue.current-analysis-fact/eta-value
      :pastdue.current-analysis-fact/grain
      :pastdue.current-analysis-fact/denominator-scope
      :pastdue.current-analysis-fact/open-line-count
      :pastdue.current-analysis-fact/past-due-count
      :pastdue.current-analysis-fact/denominator-past-due-count
      :pastdue.current-analysis-fact/vendor-past-due-count
      :pastdue.current-analysis-fact/past-due-ratio
      :pastdue.current-analysis-fact/past-due-share
      :pastdue.current-analysis-fact/within-vendor-share
      :pastdue.current-analysis-fact/row-ordinal
      :pastdue.current-analysis-fact/expected-row-count
      :pastdue.current-analysis-fact/queried-at-utc]}]}
```

## T3

```eql
{:entity {:pastdue.current-analysis-query/country-code "US"
    :pastdue.current-analysis-query/grain "VENDOR"
    :pastdue.current-analysis-query/vendor-code-filter "64342"
    :pastdue.current-analysis-query/department-type-filter ""
    :pastdue.current-analysis-query/master-primary-filter ""
    :pastdue.current-analysis-query/master-manager-filter ""
    :pastdue.current-analysis-query/denominator-scope "COUNTRY_ALL"}
  :query [{:pastdue.current-analysis-query/rows [:pastdue.current-analysis-fact/country-code
      :pastdue.current-analysis-fact/vendor-code
      :pastdue.current-analysis-fact/vendor-name
      :pastdue.current-analysis-fact/eta-value
      :pastdue.current-analysis-fact/grain
      :pastdue.current-analysis-fact/denominator-scope
      :pastdue.current-analysis-fact/open-line-count
      :pastdue.current-analysis-fact/past-due-count
      :pastdue.current-analysis-fact/denominator-past-due-count
      :pastdue.current-analysis-fact/vendor-past-due-count
      :pastdue.current-analysis-fact/past-due-ratio
      :pastdue.current-analysis-fact/past-due-share
      :pastdue.current-analysis-fact/within-vendor-share
      :pastdue.current-analysis-fact/row-ordinal
      :pastdue.current-analysis-fact/expected-row-count
      :pastdue.current-analysis-fact/queried-at-utc]}]}
```

## T4

```eql
{:entity {:pastdue.current-analysis-query/country-code "US"
    :pastdue.current-analysis-query/grain "VENDOR_CODE"
    :pastdue.current-analysis-query/vendor-code-filter "64342"
    :pastdue.current-analysis-query/department-type-filter ""
    :pastdue.current-analysis-query/master-primary-filter ""
    :pastdue.current-analysis-query/master-manager-filter ""
    :pastdue.current-analysis-query/denominator-scope "COUNTRY_ALL"}
  :query [{:pastdue.current-analysis-query/rows [:pastdue.current-analysis-fact/country-code
      :pastdue.current-analysis-fact/vendor-code
      :pastdue.current-analysis-fact/vendor-name
      :pastdue.current-analysis-fact/eta-value
      :pastdue.current-analysis-fact/grain
      :pastdue.current-analysis-fact/denominator-scope
      :pastdue.current-analysis-fact/open-line-count
      :pastdue.current-analysis-fact/past-due-count
      :pastdue.current-analysis-fact/denominator-past-due-count
      :pastdue.current-analysis-fact/vendor-past-due-count
      :pastdue.current-analysis-fact/past-due-ratio
      :pastdue.current-analysis-fact/past-due-share
      :pastdue.current-analysis-fact/within-vendor-share
      :pastdue.current-analysis-fact/row-ordinal
      :pastdue.current-analysis-fact/expected-row-count
      :pastdue.current-analysis-fact/queried-at-utc]}]}
```

## 结果校验与判读

成功时核对US/64342、grain、denominator、采集时间；看实际桶、expected-row-count及裁剪。
0行不是数量0，也不是源不可用的证明。timeout不是合法空结果。
Vendor总PD跨完整Code桶可校验，但要考虑不同时刻CURRENT漂移。
本轮是定位试验，不是13 Vendor完整刷新；不把结果复用为全国比率或原业务验收。

| 结果 | 支持的判断 | 仍需确认 |
|---|---|---|
| T1/T2成功，T3/T4超时 | 全国分母相关执行路径值得重点查。 | 查询计划是否因分母选择改变，具体扫描/重复执行成本。 |
| T1/T3成功，T2/T4超时 | Code分组相关路径值得重点查。 | Code转换、桶数、规划与取数的差别。 |
| 仅T4超时 | Code+全国分母组合触发额外成本的嫌疑增加。 | 实际计划/期限/负载，不能仅凭组合断言原因。 |
| 四条都超时 | 该完整指标resolver的共同实现/执行链路仍有问题。 | SQL扫描/聚合/比率类型/驱动/期限；EQL无法继续分离。 |
| 四条均成功 | 此时已有路径可用；可能存在负载/暂态或时序差异。 | 先前失败原因及13 Vendor联合路径仍未验证。 |
| 成败不成规律 | 当前结果不支持单一参数解释。 | 时间负载、缓存、计划、实际部署差异。 |

## EQL不足时的最后取证与最终EDN条件

用户已有数据库客户端，可以对**线上实际完整绑定SQL**做一次受控执行/EXPLAIN，
与约1秒的数量SQL比较。需要完整SQL及实际绑定参数，而不是再跑一次精简SQL。
若只有本地绑定快照，明确标记它不是线上实际版本；线上导出/平台工具可取得时先核对。

- 完整绑定SQL在同用户/设置下也明显慢：优先优化SQL，保留指标与访问语义。
- 完整SQL在数据库快，而同条件Atlas慢：优先核对JDBC、绑定、取数、连接池和生效期限。
  外部客户端与Atlas不同用户/设置时不能据此直接判驱动问题。
- 没有完整SQL/计划或链路时序时，结论停留在证据支持范围，不写“根因最终确认”。

最终EDN应在实现确定、旧指标/接口兼容性回归通过后生成；发布后再用EQL做真实验证。
如果修复涉及新绑定SQL，则必须在某个阶段导入候选EDN，才能验证修复的Atlas执行表现。
可先确定优化方向再发布试点，不能在从未通过Atlas运行新实现前保证最终版已解决问题。

## 返回给分析Agent

| 项 | 版本/执行时刻 | 总耗时 | 成功/错误category | 行数/expected-row-count | 原始响应 |
|---|---|---|---|---|---|
| T1 | 实际值 | 实际值 | 实际值 | 实际值 | 完整脱敏文本/文件 |
| T2 | 实际值 | 实际值 | 实际值 | 实际值 | 同上 |
| T3 | 实际值 | 实际值 | 实际值 | 实际值 | 同上 |
| T4 | 实际值 | 实际值 | 实际值 | 实际值 | 同上 |

四条EQL已按本地现有3.5.0契约核对输入/输出，未在线执行；不依赖试点图。

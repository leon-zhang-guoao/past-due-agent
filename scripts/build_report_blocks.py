"""Development-time fixed fact-block projection; no Agent runtime dependency.

Wraps the authoritative report resolver SQL once. Uses the same facts/rules for
both languages; no extra database table, service, arbitrary input SQL or new
metric formula. Source rows are not re-scanned per Vendor/block.
"""
from pathlib import Path
from edn_io import K, read, dumps, EdnSet
from build_analysis_contract import QUERY, add_entity, add_collection

VERSION = '3.2.0'
NS = 'pastdue.report-block-query'
FACT = 'pastdue.report-block'
INPUTS = QUERY + ['language']
FIELDS = [(x, 'string') for x in [
    'scope-key', 'country-code', 'calendar-mode', 'selection-mode', 'language',
    'block-id', 'block-type', 'vendor-name', 'scope-status', 'data-check-status',
    'data-check-reason', 'metric-version', 'template-version', 'business-timezone',
    'content-markdown', 'diagnostic-evidence', 'source-checks-json',
    'selected-vendors-json', 'country-control-json', 'content-sha256', 'source-checks-schema',
]] + [('target-snapshot-date', 'date'), ('previous-snapshot-date', 'date'),
      ('queried-at-local', 'datetime')] + [(x, 'integer') for x in [
    'vendor-code', 'contribution-rank', 'selected-vendor-count',
    'expected-block-count', 'source-record-count', 'expected-source-record-count',
    'row-ordinal', 'source-row-start', 'source-row-end',
]]


def literal(s):
    # SQL literal escaping, including Markdown newlines and raw source values.
    return "'" + s.replace('\\', '\\\\').replace("'", "\\'").replace('\n', '\\n').replace('\r', '\\r') + "'"


def concat(*xs):
    return 'concat(' + ','.join(xs) + ')'


def label(zh, en):
    return f"if(:language='zh',{literal(zh)},{literal(en)})"


def safe(x):
    # Preserve literal data semantics without letting names/values break tables.
    return f"replaceAll(replaceAll(replaceAll(replaceAll(replaceAll(replaceAll({x},'&','&amp;'),'<','&lt;'),'>','&gt;'),'|','&#124;'),'\\n',' '),'\\r',' ')"


def integer(x, signed=False):
    if not signed:
        return f'toString({x})'
    return f"concat(if({x}>0,'+',if({x}<0,'−','')),toString(abs({x})))"


def fixed(x, decimals=2, signed=False, suffix='%'):
    # Decimal/string formatting avoids Float64 tails and never prints a null as 0.
    scale = f'toDecimal256(round(abs({x})*100,{decimals}),{decimals})'
    sign = f"if({x}>0,'+',if({x}<0,'−',''))" if signed else f"if({x}<0,'−','')"
    return f"if(isNull({x}),{label('不可计算','Not calculable')},concat({sign},toString({scale}),{literal(suffix)}))"


def pair(a, b, formatter=integer):
    return concat(formatter(a), literal('→'), formatter(b))


def markdown_row(cells):
    bits = [literal('| ')]
    for i, cell in enumerate(cells):
        if i:
            bits.append(literal(' | '))
        bits.append(cell)
    bits.append(literal(' |\n'))
    return concat(*bits)


def header(zh, en):
    def h(cols):
        return '| ' + ' | '.join(cols) + ' |\n| ' + ' | '.join('---' for _ in cols) + ' |\n'
    return label(h(zh), h(en))


def sorted_text(tuple_array):
    return f"arrayStringConcat(arrayMap(x -> tupleElement(x,3),arraySort(x -> (tupleElement(x,1),tupleElement(x,2)),{tuple_array})), '')"


def group_text(condition, priority, order, text):
    return sorted_text(f'groupArrayIf(tuple(toInt64({priority}),toInt64({order}),{text}),{condition})')


def rule_text(rules, joint=False):
    # Only rules from the governed Skill dictionary; observed raw Source must
    # match exactly. Other is a mixed remainder and never classified by a name.
    parts = []
    for r in rules[':rules']:
        if joint != bool(r[':requires-joint-facts']):
            continue
        if joint:
            norm = 'upperUTF8(trim(eta_value))'
            auto = [rules[':autous-canonical']] + rules[':autous-aliases']
            is_auto = norm + ' IN (' + ','.join(literal(x.strip().upper()) for x in auto) + ')'
            known = norm + ' IN (' + ','.join(literal(x.strip().upper()) for x in rules[':source-classification'][':recognized-other-sources']) + ')'
            condition = {'AUTOUS': is_auto, 'OTHER_KNOWN':f'NOT ({is_auto}) AND ({known})',
                         'UNKNOWN':f'NOT ({is_auto}) AND NOT ({known})'}[r[':source-condition']]
        else:
            condition = 'eta_value=' + literal(r[':code'])
        meaning = label(r[':meaning'][':zh'], r[':meaning'][':en'])
        suggestion = label(r[':verification'][':zh'], r[':verification'][':en'])
        text = concat(literal(' [' + r[':rule-id'] + '] '), meaning, literal('；'), suggestion)
        parts += [condition, text]
    parts += [label('：未配置解释规则，仅保留事实', ': no configured interpretation rule; retain facts only')]
    return 'multiIf(' + ','.join(parts) + ')'


def blocks_sql(base_sql, rules):
    metric = "record_type='VENDOR_METRICS'"
    control = "record_type='COUNTRY_CONTROL'"
    summary = "record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')"
    check = "record_type='VENDOR_CHECK'"
    visible = "record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY')"
    rankline = markdown_row([integer('contribution_rank'), integer('vendor_code'), safe('vendor_name'),
        pair('previous_past_due_count','target_past_due_count'), integer('past_due_count_change',True),
        fixed('delta_country_contribution',4,True,' pp'),fixed('positive_contribution_share')])
    metrics_header = header(['逾期 上期→本期','Open 上期→本期','Vendor 逾期率 上期→本期','Country 贡献 上期→本期','贡献变化'],
                            ['Past due previous→target','Open previous→target','Vendor ratio previous→target','Country contribution previous→target','Contribution change'])
    metrics_line = markdown_row([pair('previous_past_due_count','target_past_due_count'),pair('previous_open_count','target_open_count'),
        pair('previous_vendor_ratio','target_vendor_ratio',fixed),pair('previous_country_contribution','target_country_contribution',lambda x:fixed(x,4,False,' pp')),
        fixed('delta_country_contribution',4,True,' pp')])
    bucket_name = f"if(row_kind='OTHER',concat({label('其他','Other')},' (',toString(other_bucket_count),')'),{safe('eta_value')})"
    rel = f"if(previous_past_due_count=0,if(target_past_due_count>0,{label('零基线上升','Zero-baseline increase')},{label('无变化','No change')}),{fixed('past_due_count_relative_change',2,True)})"
    bucket_line = markdown_row([bucket_name,integer('previous_past_due_count'),integer('target_past_due_count'),integer('past_due_count_change',True),rel,fixed('delta_country_contribution',4,True,' pp')])
    totals_line = markdown_row(["if(dimension='code','Code','Source')",integer('increase_total',True),integer('decrease_total',True),integer('net_change',True),
        f"if(dimension_ok,{label('服务器核对通过','Server reconciled')},{label('数量核对失败','Reconciliation failed')})"])
    observed = concat(label('Vendor ','Vendor '),integer('vendor_code'),literal(' · '),
        f"if(dimension='us',{label('ETA Code=US，Source ','ETA Code=US, Source ')},{label('ETA Code ','ETA Code ')})",safe('eta_value'),literal(' '),
        pair('previous_past_due_count','target_past_due_count'),literal(' ('),integer('past_due_count_change',True),literal(')'),
        f"if(dimension='us',{rule_text(rules,True)},{rule_text(rules,False)})")
    evidence_line = concat(literal('- '),observed,literal('\n'))
    # No independent source table is used to infer Code=US membership.
    evcond = f"{summary} AND row_kind='BUCKET' AND dimension_ok AND (dimension='us' OR (dimension='code' AND eta_value!='US'))"
    ctrl = concat(label('### Country 指标\n','### Country metrics\n'),
        header(['Country','上期日期','本期日期','逾期 上期→本期','Open 上期→本期','逾期率 上期→本期','相对变化','百分点变化'],
               ['Country','Previous date','Target date','Past due previous→target','Open previous→target','Ratio previous→target','Relative change','Point change']),
        markdown_row([safe('country_code'),'toString(previous_snapshot_date)','toString(target_snapshot_date)',pair('previous_country_past_due_count','target_country_past_due_count'),
            pair('previous_country_open','target_country_open'),pair('previous_country_ratio','target_country_ratio',fixed),
            f"if(previous_country_past_due_count=0,if(target_country_past_due_count>0,{label('零基线上升','Zero-baseline increase')},{label('无变化','No change')}),{fixed('(target_country_ratio-previous_country_ratio)/nullIf(previous_country_ratio,0)',2,True)})",
            fixed('target_country_ratio-previous_country_ratio',4,True,' pp')]))
    ranking_header = header(['排名','Vendor Code','Vendor 名称','逾期 上期→本期','净变化','Country 贡献变化','正向贡献占比'],
        ['Rank','Vendor Code','Vendor name','Past due previous→target','Net change','Country contribution change','Positive share'])
    bucket_header = header(['ETA 值','上期数量','本期数量','净变化','相对变化','Country 贡献变化'],
        ['ETA value','Previous count','Target count','Net change','Relative change','Country contribution change'])
    total_header = header(['维度','增加合计','减少合计','净变化','核对状态'],['Dimension','Increase total','Decrease total','Net change','Reconciliation'])
    diag_warning = label('- 提示风险与建议核查，不证明因果或人员责任。未识别规则仅保留事实。\n', '- Risk signals and suggested checks, not proof of causation or responsibility. Unmapped rules retain facts only.\n')
    check_json = "toJSONString(arraySort(x -> tupleElement(x,1),groupArrayIf(tuple(dimension,required,counts_match,dimension_ok,expected_bucket_count,previous_total,target_total,previous_expected_total,target_expected_total,increase_total,decrease_total,net_change),record_type='VENDOR_CHECK')))"
    country_json = "arrayStringConcat(groupArrayIf(toJSONString(tuple(previous_country_past_due_count,target_country_past_due_count,previous_country_open,target_country_open,previous_country_ratio,target_country_ratio)),record_type='COUNTRY_CONTROL'),'')"
    # Window values are calculated before block partitioning. groupArrayIf is
    # bounded by the selected compact report (<=10 Vendors), not source row volume.
    ranking_title = label('### Vendor 排行'+chr(10),'### Vendor ranking'+chr(10))
    metric_title = label('#### Vendor 指标'+chr(10),'#### Vendor metrics'+chr(10))
    code_title = label('#### ETA Code 两期变化'+chr(10),'#### ETA Code changes'+chr(10))
    source_title = label('#### ETA Source 两期变化'+chr(10),'#### ETA Source changes'+chr(10))
    totals_title = label('#### 全桶变化汇总'+chr(10),'#### All-bucket change totals'+chr(10))
    signal_title = label('#### 业务提示'+chr(10),'#### Business signals'+chr(10))
    unavailable = label('该维度数量核对失败，保留其他有效事实。'+chr(10),'This dimension failed reconciliation; other valid facts retained.'+chr(10))
    empty_selection = label('无符合选择条件的 Vendor。'+chr(10),'No Vendors match the selection.'+chr(10))
    sql = 'WITH raw_facts AS (' + base_sql + ''') ,
 counted AS (SELECT *,
 countIf(record_type='COUNTRY_CONTROL') OVER () AS control_rows,
 countIf(record_type='VENDOR_METRICS') OVER () AS actual_selected,
 uniqExactIf(vendor_code,record_type='VENDOR_METRICS') OVER () AS unique_selected,
 count() OVER () AS actual_source_records,
 min(expected_record_count) OVER () AS expected_source_min,
 max(expected_record_count) OVER () AS expected_source_max,
 uniqExact(row_ordinal) OVER () AS unique_source_ordinals,
 countIf(record_type='VENDOR_METRICS') OVER (PARTITION BY vendor_code) AS vendor_metric_rows,
 countIf(record_type='VENDOR_CHECK' AND dimension IN ('code','source','us')) OVER (PARTITION BY vendor_code) AS vendor_check_rows,
 countIf(record_type='VENDOR_CHECK') OVER dim AS dimension_check_rows,
 maxIf(toInt64(counts_match),record_type='VENDOR_CHECK') OVER dim AS check_pass,
 maxIf(toInt64(required),record_type='VENDOR_CHECK') OVER dim AS check_required,
 maxIf(expected_bucket_count,record_type='VENDOR_CHECK') OVER dim AS check_bucket_count,
 maxIf(previous_total,record_type='VENDOR_CHECK') OVER dim AS check_previous,
 maxIf(target_total,record_type='VENDOR_CHECK') OVER dim AS check_target,
 maxIf(previous_expected_total,record_type='VENDOR_CHECK') OVER dim AS check_expected_previous,
 maxIf(target_expected_total,record_type='VENDOR_CHECK') OVER dim AS check_expected_target,
 sumIf(increase_total,record_type='VENDOR_CHECK') OVER dim AS check_increase,
 sumIf(decrease_total,record_type='VENDOR_CHECK') OVER dim AS check_decrease,
 sumIf(net_change,record_type='VENDOR_CHECK') OVER dim AS check_net,
 countIf(record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY') AND row_kind='BUCKET') OVER dim AS visible_buckets,
 countIf(record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY') AND row_kind='OTHER') OVER dim AS other_rows,
 countIf(record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')) OVER dim AS summary_rows,
 uniqExactIf(tuple(row_kind,eta_value),record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')) OVER dim AS unique_summary_rows,
 countIf(record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')
 AND NOT ((row_kind='BUCKET' AND display_rank BETWEEN 1 AND 5) OR (row_kind='OTHER' AND display_rank=6))) OVER dim AS invalid_summary_rows,
 sumIf(other_bucket_count,record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY') AND row_kind='OTHER') OVER dim AS represented_other,
 sumIf(previous_past_due_count,record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')) OVER dim AS summary_previous,
 sumIf(target_past_due_count,record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')) OVER dim AS summary_target,
 maxIf(previous_past_due_count,record_type='VENDOR_METRICS') OVER (PARTITION BY vendor_code) AS metric_previous,
 maxIf(target_past_due_count,record_type='VENDOR_METRICS') OVER (PARTITION BY vendor_code) AS metric_target,
 maxIf(selected_vendor_count,record_type='COUNTRY_CONTROL') OVER () AS expected_selected,
 maxIf(selected_positive_coverage,record_type='VENDOR_METRICS') OVER () AS selected_coverage
 FROM raw_facts WINDOW dim AS (PARTITION BY vendor_code,dimension)),
 verified AS (SELECT *,
 dimension_check_rows=1 AND check_pass=1 AND check_previous=check_expected_previous AND check_target=check_expected_target
 AND check_increase+check_decrease=check_net AND check_net=check_target-check_previous
 AND visible_buckets+represented_other=check_bucket_count AND other_rows<=1
 AND summary_rows=unique_summary_rows AND invalid_summary_rows=0
 AND summary_previous=check_previous AND summary_target=check_target
 AND (dimension='us' OR (check_previous=metric_previous AND check_target=metric_target))
 AND (dimension!='us' OR check_required=toInt64(check_expected_previous>0 OR check_expected_target>0)) AS dimension_ok,
 control_rows=1 AND actual_source_records=expected_source_min AND expected_source_min=expected_source_max
 AND unique_source_ordinals=actual_source_records AND actual_selected=expected_selected
 AND unique_selected=expected_selected AS source_shape_ok,
 :language IN ('zh','en') AS language_ok
 FROM counted),
 prepared AS (SELECT *,
 if(record_type IN ('COUNTRY_CONTROL','VENDOR_METRICS'),'COUNTRY_OVERVIEW','VENDOR_DETAILS') AS block_type,
 if(record_type IN ('COUNTRY_CONTROL','VENDOR_METRICS'),toInt64(0),vendor_code) AS block_vendor,
 ''' + f"if({metric},{rankline},'') AS ranking_line,\n" + f"if({control},{ctrl},'') AS country_line,\n" + f"if({metric},concat({metrics_header},{metrics_line}),'') AS metric_line,\n" + f"if({visible},{bucket_line},'') AS bucket_line,\n" + f"if({check} AND dimension IN ('code','source'),{totals_line},'') AS totals_line,\n" + f"if({evcond},{evidence_line},'') AS evidence_line\n" + ''' FROM verified),
 metrics_attached AS (SELECT *,
 arrayStringConcat(groupArrayIf(metric_line,record_type='VENDOR_METRICS') OVER (PARTITION BY vendor_code),'') AS vendor_metric_text
 FROM prepared),
 assembled AS (SELECT country_code,previous_snapshot_date,target_snapshot_date,calendar_mode,selection_mode,
 scope_key,metric_version,business_timezone,block_type,block_vendor AS output_vendor_code,
 min(scope_status) AS original_scope_status,min(toInt64(language_ok)) AS language_ok,
 min(toInt64(source_shape_ok)) AS source_shape_ok,
 minIf(toInt64(dimension_ok),record_type='VENDOR_CHECK') AS dimensions_ok,
 countIf(record_type='VENDOR_CHECK') AS received_checks,
 max(vendor_metric_rows) AS vendor_metric_rows,max(vendor_check_rows) AS vendor_check_rows,
 maxIf(toInt64(dimension_ok),dimension='code') AS code_ok,
 maxIf(toInt64(dimension_ok),dimension='source') AS source_ok,
 max(expected_selected) AS selected_vendor_count,max(actual_source_records) AS source_record_count,
 max(expected_source_max) AS expected_source_record_count,
 min(row_ordinal) AS source_row_start,max(row_ordinal) AS source_row_end,
 min(queried_at_local) AS queried_at_local,
 min(vendor_name) AS block_name,
 minIf(contribution_rank,record_type!='COUNTRY_CONTROL') AS block_rank,
 max(selected_coverage) AS selected_coverage,
 arrayStringConcat(groupArrayIf(country_line,record_type='COUNTRY_CONTROL'),'') AS country_text,
 ''' + group_text(metric,0,'contribution_rank','ranking_line') + ' AS ranking_text,\n' + \
        "any(vendor_metric_text) AS metric_text,\n" + \
        group_text("record_type='ETA_CODE_SUMMARY'",0,'display_rank','bucket_line') + ' AS code_text,\n' + \
        group_text("record_type='ETA_SOURCE_SUMMARY'",0,'display_rank','bucket_line') + ' AS source_text,\n' + \
        group_text("record_type='VENDOR_CHECK' AND dimension IN ('code','source')","if(dimension='code',0,1)",0,'totals_line') + ' AS totals_text,\n' + \
        group_text("notEmpty(evidence_line)","if(dimension='code',0,1)",'display_rank','evidence_line') + ' AS evidence_text,\n' + \
        group_text("notEmpty(evidence_line) AND dimension='code'",0,'display_rank','evidence_line') + ' AS code_signal_text,\n' + \
        check_json + ' AS source_checks_json,\n' + \
        "toJSONString(arraySort(x -> tupleElement(x,1),groupArrayIf(tuple(contribution_rank,vendor_code,vendor_name),record_type='VENDOR_METRICS'))) AS selected_vendors_json,\n" + \
        country_json + ' AS country_control_json\n' + ''' FROM metrics_attached
 GROUP BY country_code,previous_snapshot_date,target_snapshot_date,calendar_mode,selection_mode,
 scope_key,metric_version,business_timezone,block_type,block_vendor),
 rendered AS (SELECT *,output_vendor_code AS vendor_code,block_name AS vendor_name,block_rank AS contribution_rank,
 multiIf(NOT language_ok,'INVALID_LANGUAGE',original_scope_status) AS scope_status,
 if(original_scope_status='OK' AND language_ok AND source_shape_ok AND
 (block_type='COUNTRY_OVERVIEW' OR (dimensions_ok=1 AND received_checks=3 AND vendor_check_rows=3 AND vendor_metric_rows=1)),
 'SERVER_RECONCILED','UNVERIFIED') AS data_check_status,
 ''' + f"if(block_type='COUNTRY_OVERVIEW',concat(country_text,{ranking_title},{ranking_header},if(selected_vendor_count=0,{empty_selection},ranking_text),{label('所选正向贡献覆盖率：','Selected positive coverage: ')},{fixed('selected_coverage')},'\\n'),\n" + \
        f"concat('### ',toString(contribution_rank),'. ',toString(vendor_code),' — ',{safe('vendor_name')},'\\n',\n" + \
        f"{metric_title},metric_text,\n" + \
        f"{code_title},if(code_ok=1,concat({bucket_header},code_text),{unavailable}),\n" + \
        f"{source_title},if(source_ok=1,concat({bucket_header},source_text),{unavailable}),\n" + \
        f"{totals_title},{total_header},totals_text,\n" + \
        f"{signal_title},code_signal_text,{diag_warning})) AS candidate_content\n" + ''' FROM assembled),
 guarded AS (SELECT *,
 multiIf(NOT language_ok,'INVALID_LANGUAGE',original_scope_status!='OK',original_scope_status,
 NOT source_shape_ok,'SOURCE_RECORD_RECONCILIATION_FAILED',
 block_type='VENDOR_DETAILS' AND (vendor_metric_rows!=1 OR received_checks!=3 OR vendor_check_rows!=3),'MISSING_OR_DUPLICATE_VENDOR_RECORDS',
 block_type='VENDOR_DETAILS' AND dimensions_ok!=1,'DIMENSION_RECONCILIATION_FAILED','OK') AS data_check_reason
 FROM rendered)
 SELECT ''' + ', '.join([
        'scope_key','country_code','calendar_mode','selection_mode',':language AS language',
        "concat(scope_key,':',:language,':',block_type,':',toString(vendor_code)) AS block_id",
        'block_type','vendor_name','scope_status','data_check_status','data_check_reason','metric_version',
        literal(VERSION)+' AS template_version','business_timezone',
        f"if(scope_status='OK' AND source_shape_ok AND (block_type='COUNTRY_OVERVIEW' OR vendor_metric_rows=1),concat(candidate_content,if(data_check_reason='OK','',concat('\\n',data_check_reason,'\\n'))),concat({label('### 事实块不可用','### Fact block unavailable')},' — ',country_code,' / ',toString(vendor_code),'\\n',data_check_reason,'\\n')) AS content_markdown",
        "if(scope_status='OK' AND source_shape_ok,evidence_text,'') AS diagnostic_evidence",
        'source_checks_json','selected_vendors_json','country_control_json',
        literal('dimension,required,counts_match,dimension_ok,expected_bucket_count,previous_total,target_total,previous_expected_total,target_expected_total,increase_total,decrease_total,net_change')+' AS source_checks_schema',
        'lower(hex(SHA256(content_markdown))) AS content_sha256',
        'target_snapshot_date','previous_snapshot_date','queried_at_local','vendor_code','contribution_rank',
        'selected_vendor_count','toInt64(count() OVER ()) AS expected_block_count','source_record_count','expected_source_record_count',
        "toInt64(row_number() OVER (ORDER BY if(block_type='COUNTRY_OVERVIEW',0,1),contribution_rank,vendor_code)) AS row_ordinal",
        'source_row_start','source_row_end']) + ' FROM guarded'
    # Report scope-key still denotes the seven metric inputs. block-id additionally
    # includes language and template version is explicit in the returned row.
    return sql


def add_report_blocks(doc):
    rules = read(Path(__file__).resolve().parents[1] / 'skill/pastdue-analysis/references/eta-diagnostic-rules.edn')
    base = doc[':execution'][':bindings']['pastdue_v3_report_query_rows-sql'][':binding/implementation'][':query']
    fields = [(x,'date' if x.endswith('date') else 'integer' if x in ('vendor-code','top-n') else 'string') for x in INPUTS]
    add_entity(doc, NS, fields, INPUTS)
    add_entity(doc, FACT, FIELDS, ['block-id','template-version'])
    add_collection(doc, NS, 'blocks', FACT, FIELDS, INPUTS, blocks_sql(base,rules))
    rid = 'pastdue_v3_report_block_query_blocks'
    doc[':execution'][':resolvers'][rid][K(':description')] = 'Fixed bilingual fact blocks from shared report facts; data reconciliation is not final Agent-output acceptance. language zh/en. No new metric semantics.'
    exid='v3-fixed-report-blocks'
    examples=doc[':graph'][':graph/query-examples']
    examples[:]=[ex for ex in examples if ex.get(':example/id')!=exid]
    vals=['CA','2026-10-26','2026-10-23','WEEKDAYS','POSITIVE_TOP_N',10,0,'en']
    ex={K(':example/id'):exid,K(':example/title'):'Fixed fact blocks — live pilot required',
        K(':example/description'):'Illustrative dates only; plain Studio join. No live acceptance claimed.',
        K(':example/entity'):{K(':'+NS+'/'+x):v for x,v in zip(INPUTS,vals)},
        K(':example/eql'):dumps([{K(':'+NS+'/blocks'):[K(':'+FACT+'/'+x) for x,_ in FIELDS]}]),
        K(':example/parameters'):{},K(':example/requires'):EdnSet([K(':connector/sql'),K(':query/pagination-v1'),K(':query/sort-v1')]),
        K(':example/expected'):{K(':result/cardinality'):K(':many'),K(':result/fields'):[K(':'+FACT+'/'+x) for x,_ in FIELDS]}}
    examples.append(ex)
    guides=doc[':graph'][':graph/usage-guide'].get(':guide/examples',[])
    if exid not in guides: guides.append(exid)
    doc[':publication'][K(':release/version')]=VERSION
    doc[':publication'][K(':source/revision')]=3
    limitations=doc[':graph'][':graph/usage-guide'][':guide/limitations']
    item='Fixed fact blocks require real ClickHouse/Atlas and Agent pilot; hashes/SQL checks do not verify final model output.'
    if item not in limitations:limitations.append(item)


def add_monitor_blocks(doc):
    """All-Country monitor facts formatted without forcing Vendor drilldown."""
    from build_analysis_contract import MONITOR_FIELDS
    parent='pastdue.monitor-block-query';child='pastdue.monitor-block'
    inputs=[('calendar-mode','string'),('relative-change-threshold','decimal'),('language','string')]
    extras=[('scope-key','string'),('language','string'),('template-version','string'),
            ('business-timezone','string'),('queried-at-local','datetime'),
            ('content-markdown','string'),('content-sha256','string'),
            ('row-ordinal','integer'),('expected-record-count','integer')]
    fields=MONITOR_FIELDS+extras
    base=doc[':execution'][':bindings']['pastdue_v3_monitor_query_countries-sql'][':binding/implementation'][':query']
    hd=header(['Country','业务状态','数据状态','上期日期','本期日期','间隔天数','逾期 上期→本期','Open 上期→本期','逾期率 上期→本期','相对变化','百分点变化'],
              ['Country','Business status','Data status','Previous date','Target date','Gap days','Past due previous→target','Open previous→target','Ratio previous→target','Relative change','Point change'])
    pd=pair('previous_past_due_count','target_past_due_count');op=pair('previous_open_count','target_open_count')
    rates=pair('previous_past_due_ratio','target_past_due_ratio',fixed)
    missing=label('无可比较快照','No comparable snapshots')
    rel=f"if(previous_past_due_count=0,if(target_past_due_count>0,{label('零基线上升','Zero-baseline increase')},{label('无变化','No change')}),{fixed('relative_ratio_change',2,True)})"
    cells=[safe('country_code'),'business_status','data_status',
           f"if(has_comparable_snapshots,toString(previous_snapshot_date),{missing})",'toString(target_snapshot_date)',
           f"if(has_comparable_snapshots,toString(snapshot_gap_days),{missing})",
           f"if(has_comparable_snapshots,{pd},{missing})",f"if(has_comparable_snapshots,{op},{missing})",
           f"if(has_comparable_snapshots,{rates},{missing})",f"if(has_comparable_snapshots,{rel},{missing})",
           f"if(has_comparable_snapshots,{fixed('percentage_point_change',4,True,' pp')},{missing})"]
    text=concat(hd,markdown_row(cells))
    sql='WITH monitor_facts AS ('+base+'''), formatted AS (SELECT *,
 toJSONString(tuple(:calendar_mode,:relative_change_threshold)) AS scope_key,
 :language AS language,'3.2.0' AS template_version,'America/Los_Angeles' AS business_timezone,
 now('America/Los_Angeles') AS queried_at_local,
 '''+f"if(:language IN ('zh','en'),{text},{literal('INVALID_LANGUAGE')}) AS content_markdown"+'''
 FROM monitor_facts)
 SELECT *,lower(hex(SHA256(content_markdown))) AS content_sha256,
 toInt64(row_number() OVER (ORDER BY country_code)) AS row_ordinal,
 toInt64(count() OVER ()) AS expected_record_count FROM formatted'''
    add_entity(doc,parent,inputs,[x for x,_ in inputs])
    add_entity(doc,child,fields,['scope-key','country-code','target-snapshot-date','language','template-version'])
    # Preserve the original monitor's nullable baseline fields exactly; copying
    # SQL alone must not reintroduce nullable vocabulary drift.
    for name,_ in MONITOR_FIELDS:
        original=doc[':vocabulary'][':attributes'][K(':pastdue.monitor-fact/'+name)]
        doc[':vocabulary'][':attributes'][K(':'+child+'/'+name)][K(':attribute/nullable?')]=original[':attribute/nullable?']
    add_collection(doc,parent,'countries',child,fields,[x for x,_ in inputs],sql)

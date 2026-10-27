"""Development-only v3.1 SQL builder. No runtime execution service."""
from build_analysis_contract import (add_entity,add_collection,pair_ctes,ratio,
                                     QUERY,ID,COMMON,VENDOR_FIELDS,NOW_PST,TODAY_PST)

REPORT_FIELDS = [('scope-key','string'),('record-type','string')] + COMMON + [('vendor-name','string'),('dimension','string'),('eta-value','string'),('row-kind','string'),('scope-status','string'),('metric-version','string'),('selection-mode','string')] + [(n,t) for n,t in VENDOR_FIELDS if n not in {x for x,_ in COMMON} and n!='vendor-name'] + [(x,'integer') for x in ['display-rank','expected-bucket-count','other-bucket-count','increase-total','decrease-total','net-change','previous-total','target-total','previous-expected-total','target-expected-total','expected-record-count','row-ordinal']] + [('counts-match','boolean'),('required','boolean'),('past-due-count-relative-change','decimal'),('business-timezone','string'),('queried-at-local','datetime'),('target-country-past-due-count','integer'),('previous-country-past-due-count','integer'),('target-country-ratio','decimal'),('previous-country-ratio','decimal')]

EXPLORE_INPUTS = [('country-code','string'),('time-basis','string'),('target-snapshot-date','date'),('previous-snapshot-date','date'),('range-start-date','date'),('range-end-date','date'),('calendar-mode','string'),('pair-policy','string'),('grain','string'),('vendor-code','integer'),('eta-code-filter','string'),('source-filter','string'),('department-type-filter','string'),('master-primary-filter','string'),('master-manager-filter','string'),('denominator-scope','string'),('ranking-metric','string'),('sort-direction','string'),('change-selection','string'),('top-n','integer')]
EXPLORE_FIELDS = [('scope-key','string'),('record-type','string'),('scope-status','string'),('country-code','string'),('vendor-code','integer'),('vendor-name','string'),('target-snapshot-date','date'),('previous-snapshot-date','date'),('calendar-mode','string'),('time-basis','string'),('grain','string'),('eta-code','string'),('eta-source','string'),('denominator-scope','string'),('metric-version','string'),('organization-basis','string'),('business-timezone','string'),('queried-at-local','datetime')] + [(x,'integer') for x in ['target-past-due-count','previous-past-due-count','target-open-count','previous-open-count','target-ratio-denominator','previous-ratio-denominator','target-share-denominator','previous-share-denominator','target-country-open','previous-country-open','past-due-count-change','selection-rank','expected-record-count','row-ordinal']] + [(x,'decimal') for x in ['target-past-due-ratio','previous-past-due-ratio','target-past-due-share','previous-past-due-share','past-due-count-relative-change','past-due-ratio-change','delta-country-contribution']]


def scope_key(inputs):
    return "toJSONString(tuple("+', '.join(':'+x.replace('-','_') for x in inputs)+"))"


def sparse(fields, values):
    defaults={'string':"''",'date':"toDate('1970-01-01')",'integer':'toInt64(0)','boolean':'false','decimal':'CAST(NULL AS Nullable(Decimal(38,18)))','datetime':NOW_PST}
    return 'SELECT '+', '.join(values.get(n,defaults[t])+' AS '+n.replace('-','_') for n,t in fields)


def add_report(doc):
    base=pair_ctes()
    legacy=doc[':execution'][':bindings']['pastdue_v3_analysis_query_vendor_checks-sql'][':binding/implementation'][':query']
    extra=legacy[len('WITH '+base):legacy.index(',\n dimension_totals AS')]
    scope=doc[':execution'][':bindings']['pastdue_v3_analysis_query_scope_status-sql'][':binding/implementation'][':query'][len('WITH '+base):]
    scope=scope.replace(":selection_mode NOT IN ('POSITIVE_TOP_N','SINGLE_VENDOR') OR :top_n NOT BETWEEN 1 AND 10", ":selection_mode NOT IN ('POSITIVE_TOP_N','SINGLE_VENDOR') OR :top_n NOT BETWEEN 1 AND 10 OR (:selection_mode='POSITIVE_TOP_N' AND :vendor_code!=0) OR (:selection_mode='SINGLE_VENDOR' AND (:top_n!=1 OR :vendor_code=0))")
    # Seed one row per Vendor/dimension so success-empty US still has a check.
    # Then carry one aggregate/window pipeline into tagged output records rather
    # than repeatedly joining CTEs (ClickHouse CTEs are not materialized).
    ctes='WITH '+base+extra+""",
 report_scope AS ("""+scope+"""),
 seeded AS (
 SELECT country_code,vendor_code,dimension,eta_value,previous_count,target_count,false AS is_seed FROM check_buckets
 UNION ALL SELECT country_code,vendor_code,arrayJoin(['code','source','us']) AS dimension,
 '' AS eta_value,toInt64(0) AS previous_count,toInt64(0) AS target_count,true AS is_seed FROM selected),
 attached AS (SELECT b.country_code AS country_code,b.vendor_code AS vendor_code,b.dimension AS dimension,
 b.eta_value AS eta_value,b.previous_count AS previous_count,b.target_count AS target_count,b.is_seed AS is_seed,
 """+', '.join('s.'+n.replace('-','_')+' AS '+n.replace('-','_') for n,_ in VENDOR_FIELDS if n not in ('country-code','vendor-code','calendar-mode'))+"""
 FROM seeded b INNER JOIN selected s ON b.country_code=s.country_code AND b.vendor_code=s.vendor_code),
 bucket_ranked AS (SELECT *,target_count-previous_count AS bucket_delta,
 toInt64(row_number() OVER (PARTITION BY country_code,vendor_code,dimension ORDER BY is_seed,abs(bucket_delta) DESC,target_count DESC,eta_value)) AS display_rank,
 sumIf(previous_count,dimension='code' AND eta_value='US' AND NOT is_seed) OVER (PARTITION BY country_code,vendor_code) AS previous_us_total,
 sumIf(target_count,dimension='code' AND eta_value='US' AND NOT is_seed) OVER (PARTITION BY country_code,vendor_code) AS target_us_total
 FROM attached),
 enriched AS (SELECT *,
 toInt64(countIf(NOT is_seed) OVER w) AS expected_bucket_count,
 sum(previous_count) OVER w AS previous_total,sum(target_count) OVER w AS target_total,
 sum(greatest(bucket_delta,0)) OVER w AS increase_total,sum(least(bucket_delta,0)) OVER w AS decrease_total,
 sumIf(previous_count,display_rank>5 AND NOT is_seed) OVER w AS other_previous,
 sumIf(target_count,display_rank>5 AND NOT is_seed) OVER w AS other_target,
 toInt64(countIf(display_rank>5 AND NOT is_seed) OVER w) AS other_bucket_count,
 if(dimension='us',previous_us_total,previous_past_due_count) AS previous_expected_total,
 if(dimension='us',target_us_total,target_past_due_count) AS target_expected_total,
 dimension!='us' OR previous_us_total>0 OR target_us_total>0 AS required
 FROM bucket_ranked WINDOW w AS (PARTITION BY country_code,vendor_code,dimension)),
 tagged AS (SELECT *,arrayJoin(arrayConcat(
 if(is_seed AND dimension='code',['VENDOR_METRICS'],[]),
 if(is_seed,['VENDOR_CHECK'],[]),
 if(NOT is_seed AND display_rank<=6,[multiIf(dimension='code','ETA_CODE_SUMMARY',dimension='source','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')],[]))) AS tag
 FROM enriched),
 report_rows AS ("""
    core={'scope-key':scope_key(QUERY),'country-code':':country_code','target-snapshot-date':'toDate(:target_snapshot_date)','previous-snapshot-date':'toDate(:previous_snapshot_date)','calendar-mode':':calendar_mode','metric-version':"'3.1.0'",'selection-mode':':selection_mode','business-timezone':"'America/Los_Angeles'",'queried-at-local':NOW_PST}
    raw_bad="(SELECT count() FROM period_rows p INNER JOIN past_due.vendor_past_due_daily d ON p.country_code=d.country_code AND p.snapshot_date=d.snapshot_date WHERE d.past_due_count<0 OR d.total_open_count<0 OR d.past_due_count>d.total_open_count)>0"
    control=dict(core,**{'record-type':"'COUNTRY_CONTROL'",'scope-status':f"if({raw_bad},'DATA_ERROR',scope_status)",'selected-vendor-count':'selected_vendor_count','target-country-open':'target_country_open','previous-country-open':'previous_country_open','target-country-past-due-count':'ifNull((SELECT max(target_country_past_due) FROM country_pair),toInt64(0))','previous-country-past-due-count':'ifNull((SELECT max(previous_country_past_due) FROM country_pair),toInt64(0))','target-country-ratio':ratio('target_country_past_due_count','target_country_open'),'previous-country-ratio':ratio('previous_country_past_due_count','previous_country_open')})
    control_sql=sparse(REPORT_FIELDS,control)+' FROM report_scope'
    vals=dict(core,**{n:n.replace('-','_') for n,_ in VENDOR_FIELDS if n!='calendar-mode'})
    summary="tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')"
    bp="if(display_rank=6,other_previous,previous_count)";bt="if(display_rank=6,other_target,target_count)"
    vals.update({'record-type':'tag','dimension':"if(tag='VENDOR_METRICS','',dimension)",'eta-value':f"if({summary} AND display_rank<=5,eta_value,'')",'row-kind':f"if({summary},if(display_rank=6,'OTHER','BUCKET'),'')",'scope-status':"'OK'",'display-rank':f"if({summary},display_rank,toInt64(0))",'expected-bucket-count':'expected_bucket_count','other-bucket-count':f"if({summary} AND display_rank=6,other_bucket_count,toInt64(0))",'previous-past-due-count':f"if({summary},{bp},previous_past_due_count)",'target-past-due-count':f"if({summary},{bt},target_past_due_count)",'past-due-count-change':f"if({summary},{bt}-{bp},past_due_count_change)",'past-due-count-relative-change':ratio(bt+'-'+bp,bp),'delta-country-contribution':f"if({summary},"+ratio(bt,'target_country_open')+'-'+ratio(bp,'previous_country_open')+",delta_country_contribution)",'increase-total':'increase_total','decrease-total':'decrease_total','net-change':'target_total-previous_total','previous-total':'previous_total','target-total':'target_total','previous-expected-total':'previous_expected_total','target-expected-total':'target_expected_total','counts-match':'previous_total=previous_expected_total AND target_total=target_expected_total','required':'required'})
    # Semantic aliases in a SELECT may be expanded by ClickHouse. Qualify source
    # columns so substituting a summary count never changes its Vendor metrics.
    source_fields={n.replace('-','_') for n,_ in REPORT_FIELDS} | {'tag','is_seed','other_previous','other_target','previous_count','target_count'}
    import re
    for key,value in vals.items():
        if key in core:continue
        vals[key]=re.sub(r"'[^']*'|\b([a-z_][a-z_0-9]*)\b",lambda m:m.group(0) if not m.group(1) or m.group(1) not in source_fields else 't.'+m.group(1),value)
    facts=sparse(REPORT_FIELDS,vals)+f" FROM tagged t WHERE (SELECT scope_status FROM report_scope)='OK' AND ({raw_bad})=false"
    sql=ctes+control_sql+' UNION ALL '+facts+""") SELECT * EXCEPT(expected_record_count,row_ordinal),
 toInt64(count() OVER ()) AS expected_record_count,
 toInt64(row_number() OVER (ORDER BY contribution_rank,vendor_code,record_type,dimension,display_rank,eta_value)) AS row_ordinal FROM report_rows"""
    fields=[(x,'date' if x.endswith('date') else 'integer' if x in ('vendor-code','top-n') else 'string') for x in QUERY]
    add_entity(doc,'pastdue.report-query',fields,QUERY)
    add_entity(doc,'pastdue.report-fact',REPORT_FIELDS,['scope-key']+ID+['vendor-code','record-type','dimension','row-kind','eta-value'])
    add_collection(doc,'pastdue.report-query','rows','pastdue.report-fact',REPORT_FIELDS,QUERY,sql)


def explore_sql():
    # Filter facts only after constructing independent Country / Vendor /
    # organizational denominators. Current and historical inputs share shape.
    return '''WITH
 mapped AS (SELECT DISTINCT country_code,toInt64(vendor_no) AS vendor_code FROM past_due.vendor_master_mapping_view
 WHERE country_code=:country_code
 AND (empty(:department_type_filter) OR upper(trim(department_type))=upper(trim(:department_type_filter)))
 AND (empty(:master_primary_filter) OR trim(ifNull(master_primary,''))=trim(:master_primary_filter))
 AND (empty(:master_manager_filter) OR trim(ifNull(master_manager,''))=trim(:master_manager_filter))),
 dates AS (SELECT DISTINCT snapshot_date FROM past_due.vendor_past_due_daily WHERE country_code=:country_code
 AND snapshot_date < '''+TODAY_PST+''' AND (:calendar_mode='CALENDAR_DAYS' OR toDayOfWeek(snapshot_date)<=5)),
 pair AS (SELECT toDate(:target_snapshot_date) AS target_date,
 if(:pair_policy='EXPLICIT',toDate(:previous_snapshot_date),ifNull((SELECT maxOrNull(snapshot_date) FROM dates WHERE snapshot_date<toDate(:target_snapshot_date)),toDate('1970-01-01'))) AS previous_date),
 scope AS (SELECT multiIf(
 :time_basis NOT IN ('CURRENT','SNAPSHOT','PAIR','RANGE') OR :calendar_mode NOT IN ('WEEKDAYS','CALENDAR_DAYS')
 OR :pair_policy NOT IN ('EXPLICIT','PREVIOUS_AVAILABLE') OR :grain NOT IN ('COUNTRY','VENDOR','VENDOR_CODE','VENDOR_SOURCE','VENDOR_CODE_SOURCE')
 OR :denominator_scope NOT IN ('COUNTRY_ALL','FILTERED_POPULATION','WITHIN_VENDOR')
 OR (:grain='COUNTRY' AND :denominator_scope='WITHIN_VENDOR')
 OR :ranking_metric NOT IN ('NONE','PAST_DUE_COUNT','PAST_DUE_RATIO','PAST_DUE_SHARE','COUNT_CHANGE','RATIO_CHANGE','CONTRIBUTION_CHANGE')
 OR :sort_direction NOT IN ('ASC','DESC') OR :change_selection NOT IN ('ALL','POSITIVE','NEGATIVE','ZERO') OR :top_n<0 OR :top_n>100,'INVALID_SELECTION',
 :time_basis!='PAIR' AND (:ranking_metric IN ('COUNT_CHANGE','RATIO_CHANGE','CONTRIBUTION_CHANGE') OR :change_selection!='ALL'),'UNSUPPORTED_COMBINATION',
 :time_basis IN ('SNAPSHOT','PAIR') AND toDate(:target_snapshot_date) NOT IN (SELECT snapshot_date FROM dates),'INVALID_DATE',
 :time_basis='PAIR' AND (previous_date>=target_date OR previous_date NOT IN (SELECT snapshot_date FROM dates)),'INVALID_PAIR',
 :time_basis='RANGE' AND (dateDiff('day',toDate(:range_start_date),toDate(:range_end_date)) NOT BETWEEN 0 AND 365 OR toDate(:range_end_date)>='''+TODAY_PST+'''),'INVALID_RANGE',
 'OK') AS scope_status FROM pair),
 raw AS (
 SELECT country_code,vendor_code,vendor_name,snapshot_date,upper(trim(ifNull(eta_code,''))) AS raw_code,trim(ifNull(source,'')) AS raw_source,
 toInt64(past_due_count) AS pd,toInt64(total_open_count) AS op FROM past_due.vendor_past_due_daily
 WHERE country_code=:country_code AND :time_basis!='CURRENT' AND snapshot_date IN (SELECT snapshot_date FROM dates)
 AND ((:time_basis='PAIR' AND (snapshot_date=toDate(:target_snapshot_date) OR snapshot_date=(SELECT previous_date FROM pair)))
 OR (:time_basis='SNAPSHOT' AND snapshot_date=toDate(:target_snapshot_date))
 OR (:time_basis='RANGE' AND snapshot_date BETWEEN toDate(:range_start_date) AND toDate(:range_end_date)))
 UNION ALL
 SELECT country_code,toInt64(from_acct_no) AS vendor_code,vendor_name,'''+TODAY_PST+''' AS snapshot_date,
 upper(trim(ifNull(eta_code,''))) AS raw_code,trim(ifNull(source,'')) AS raw_source,
 toInt64(is_pastdue=1) AS pd,toInt64(1) AS op FROM past_due.past_due_detail_view
 WHERE :time_basis='CURRENT' AND country_code=:country_code AND from_acct_no IS NOT NULL),
 country_totals AS (SELECT snapshot_date,sum(pd) AS country_pd,sum(op) AS country_open FROM raw GROUP BY snapshot_date),
 population AS (SELECT * FROM raw WHERE (:vendor_code=0 OR vendor_code=:vendor_code)
 AND ((empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter))
 OR (country_code,vendor_code) IN (SELECT country_code,vendor_code FROM mapped))),
 vendor_totals AS (SELECT snapshot_date,vendor_code,sum(pd) AS vendor_pd,sum(op) AS vendor_open FROM population GROUP BY snapshot_date,vendor_code),
 population_totals AS (SELECT snapshot_date,sum(pd) AS population_pd,sum(op) AS population_open FROM population GROUP BY snapshot_date),
 matched AS (SELECT *,if(empty(raw_code),'UNKNOWN',raw_code) AS code,if(empty(raw_source),'UNKNOWN',raw_source) AS source FROM population
 WHERE (empty(:eta_code_filter) OR if(empty(raw_code),'UNKNOWN',raw_code)=upper(trim(:eta_code_filter)))
 AND (empty(:source_filter) OR if(empty(raw_source),'UNKNOWN',raw_source)=trim(:source_filter))),
 grouped AS (SELECT country_code,if(:grain='COUNTRY',toInt64(0),vendor_code) AS vendor_key,
 if(:grain='COUNTRY','',min(vendor_name)) AS vendor_name,snapshot_date,
 if(:grain IN ('VENDOR_CODE','VENDOR_CODE_SOURCE'),code,'') AS eta_code,
 if(:grain IN ('VENDOR_SOURCE','VENDOR_CODE_SOURCE'),source,'') AS eta_source,
 sum(pd) AS pd,sum(op) AS op FROM matched GROUP BY country_code,vendor_key,snapshot_date,eta_code,eta_source),
 paired AS (SELECT country_code,vendor_key AS vendor_code,min(vendor_name) AS vendor_name,
 if(:time_basis='PAIR',toDate(:target_snapshot_date),snapshot_date) AS target_snapshot_date,
 if(:time_basis='PAIR',(SELECT previous_date FROM pair),toDate('1970-01-01')) AS previous_snapshot_date,
 eta_code,eta_source,
 sumIf(pd,:time_basis!='PAIR' OR snapshot_date=toDate(:target_snapshot_date)) AS target_past_due_count,
 sumIf(pd,:time_basis='PAIR' AND snapshot_date=(SELECT previous_date FROM pair)) AS previous_past_due_count,
 sumIf(op,:time_basis!='PAIR' OR snapshot_date=toDate(:target_snapshot_date)) AS target_open_count,
 sumIf(op,:time_basis='PAIR' AND snapshot_date=(SELECT previous_date FROM pair)) AS previous_open_count
 FROM grouped GROUP BY country_code,vendor_code,target_snapshot_date,previous_snapshot_date,eta_code,eta_source),
 -- Denominators are joined by date independently of ETA bucket presence.
 metrics AS (SELECT p.country_code AS country_code,p.vendor_code AS vendor_code,p.vendor_name AS vendor_name,p.target_snapshot_date AS target_snapshot_date,p.previous_snapshot_date AS previous_snapshot_date,p.eta_code AS eta_code,p.eta_source AS eta_source,p.target_past_due_count AS target_past_due_count,p.previous_past_due_count AS previous_past_due_count,p.target_open_count AS target_open_count,p.previous_open_count AS previous_open_count,
 if(:grain='COUNTRY',ct.country_open,ifNull(vt.vendor_open,0)) AS target_ratio_denominator,
 if(:grain='COUNTRY',ifNull(cp.country_open,0),ifNull(vp.vendor_open,0)) AS previous_ratio_denominator,
 multiIf(:denominator_scope='COUNTRY_ALL',ct.country_pd,:denominator_scope='FILTERED_POPULATION',ifNull(pt.population_pd,0),ifNull(vt.vendor_pd,0)) AS target_share_denominator,
 multiIf(:denominator_scope='COUNTRY_ALL',ifNull(cp.country_pd,0),:denominator_scope='FILTERED_POPULATION',ifNull(pp.population_pd,0),ifNull(vp.vendor_pd,0)) AS previous_share_denominator,
 ct.country_open AS target_country_open,ifNull(cp.country_open,0) AS previous_country_open,
 '''+ratio('p.target_past_due_count','target_ratio_denominator')+''' AS target_past_due_ratio,
 '''+ratio('p.previous_past_due_count','previous_ratio_denominator')+''' AS previous_past_due_ratio,
 '''+ratio('p.target_past_due_count','target_share_denominator')+''' AS target_past_due_share,
 '''+ratio('p.previous_past_due_count','previous_share_denominator')+''' AS previous_past_due_share,
 p.target_past_due_count-p.previous_past_due_count AS past_due_count_change,
 '''+ratio('past_due_count_change','p.previous_past_due_count')+''' AS past_due_count_relative_change,
 target_past_due_ratio-previous_past_due_ratio AS past_due_ratio_change,
 '''+ratio('p.target_past_due_count','target_country_open')+'-'+ratio('p.previous_past_due_count','previous_country_open')+''' AS delta_country_contribution
 FROM paired p INNER JOIN country_totals ct ON p.target_snapshot_date=ct.snapshot_date
 LEFT JOIN population_totals pt ON p.target_snapshot_date=pt.snapshot_date
 LEFT JOIN country_totals cp ON p.previous_snapshot_date=cp.snapshot_date
 LEFT JOIN population_totals pp ON p.previous_snapshot_date=pp.snapshot_date
 LEFT JOIN vendor_totals vt ON p.target_snapshot_date=vt.snapshot_date AND p.vendor_code=vt.vendor_code
 LEFT JOIN vendor_totals vp ON p.previous_snapshot_date=vp.snapshot_date AND p.vendor_code=vp.vendor_code),
 filtered AS (SELECT *,multiIf(:ranking_metric='PAST_DUE_COUNT',toDecimal128(target_past_due_count,18),
 :ranking_metric='PAST_DUE_RATIO',target_past_due_ratio,:ranking_metric='PAST_DUE_SHARE',target_past_due_share,
 :ranking_metric='COUNT_CHANGE',toDecimal128(past_due_count_change,18),:ranking_metric='RATIO_CHANGE',past_due_ratio_change,
 :ranking_metric='CONTRIBUTION_CHANGE',delta_country_contribution,toDecimal128(0,18)) AS rank_value
 FROM metrics WHERE (SELECT scope_status FROM scope)='OK' AND
 (:change_selection='ALL' OR (:change_selection='POSITIVE' AND multiIf(:ranking_metric='RATIO_CHANGE',past_due_ratio_change,:ranking_metric='CONTRIBUTION_CHANGE',delta_country_contribution,toDecimal128(past_due_count_change,18))>0)
 OR (:change_selection='NEGATIVE' AND multiIf(:ranking_metric='RATIO_CHANGE',past_due_ratio_change,:ranking_metric='CONTRIBUTION_CHANGE',delta_country_contribution,toDecimal128(past_due_count_change,18))<0)
 OR (:change_selection='ZERO' AND multiIf(:ranking_metric='RATIO_CHANGE',past_due_ratio_change,:ranking_metric='CONTRIBUTION_CHANGE',delta_country_contribution,toDecimal128(past_due_count_change,18))=0))),
 ranked AS (SELECT *,toInt64(row_number() OVER (ORDER BY
 if(:ranking_metric!='NONE' AND :sort_direction='ASC',rank_value,NULL) ASC NULLS LAST,
 if(:ranking_metric!='NONE' AND :sort_direction='DESC',rank_value,NULL) DESC NULLS LAST,
 target_snapshot_date,vendor_code,eta_code,eta_source)) AS selection_rank FROM filtered),
 selected_rows AS (SELECT * FROM ranked WHERE :top_n=0 OR selection_rank<=:top_n),
 output_rows AS ('''+_explore_output()+''') SELECT * EXCEPT(expected_record_count,row_ordinal),
 toInt64(count() OVER ()) AS expected_record_count,
 toInt64(row_number() OVER (ORDER BY record_type,selection_rank,target_snapshot_date,vendor_code,eta_code,eta_source)) AS row_ordinal FROM output_rows'''


def _explore_output():
    core={'scope-key':scope_key([x for x,_ in EXPLORE_INPUTS]),'country-code':':country_code','time-basis':':time_basis','calendar-mode':':calendar_mode','grain':':grain','denominator-scope':':denominator_scope','metric-version':"'3.1.0'",'organization-basis':"if(empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter),'NONE','CURRENT_MAPPING')",'business-timezone':"'America/Los_Angeles'",'queried-at-local':NOW_PST}
    vals=dict(core,**{'record-type':"'CONTROL'",'scope-status':"if(scope_status='OK' AND (SELECT count() FROM raw)=0,'NO_DATA',if(scope_status='OK' AND (SELECT count() FROM raw WHERE pd<0 OR op<0 OR pd>op)>0,'DATA_ERROR',scope_status))"})
    control=sparse(EXPLORE_FIELDS,vals)+' FROM scope'
    vals=dict(core,**{n:n.replace('-','_') for n,_ in EXPLORE_FIELDS if n not in core and n not in ('record-type','scope-status','expected-record-count','row-ordinal')})
    vals.update({'record-type':"'FACT'",'scope-status':"'OK'"})
    return control+' UNION ALL '+sparse(EXPLORE_FIELDS,vals)+" FROM selected_rows WHERE (SELECT count() FROM raw WHERE pd<0 OR op<0 OR pd>op)=0"


def add_v31(doc):
    add_report(doc)
    ns='pastdue.explore-query'
    inputs=[x for x,_ in EXPLORE_INPUTS]
    add_entity(doc,ns,EXPLORE_INPUTS,inputs)
    add_entity(doc,'pastdue.explore-fact',EXPLORE_FIELDS,['scope-key','country-code','time-basis','calendar-mode','grain','target-snapshot-date','previous-snapshot-date','vendor-code','eta-code','eta-source','denominator-scope','record-type'])
    add_collection(doc,ns,'rows','pastdue.explore-fact',EXPLORE_FIELDS,inputs,explore_sql())

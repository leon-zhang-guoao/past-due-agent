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


def report_ctes():
    """Linear pipeline: two bounded source reads, no fact-CTE re-expansion.

    Dates are validated from date keys in [P,T], independently of the direct
    two-date fact scan. Tagged buckets carry metrics/control through windows,
    so neither selection nor control causes another source scan. No materialized
    CTE, experimental analyzer, session state, or database schema change needed.
    """
    return """WITH
 date_guard AS (
 SELECT countIf(snapshot_date=toDate(:target_snapshot_date)) AS target_exists,
 countIf(snapshot_date=toDate(:previous_snapshot_date)) AS previous_exists,
 maxIf(snapshot_date,snapshot_date<toDate(:target_snapshot_date)) AS nearest_previous
 FROM (SELECT DISTINCT snapshot_date FROM past_due.vendor_past_due_daily
 WHERE country_code=:country_code
 AND snapshot_date BETWEEN toDate(:previous_snapshot_date) AND toDate(:target_snapshot_date)
 AND snapshot_date < """+TODAY_PST+"""
 AND (:calendar_mode='CALENDAR_DAYS' OR (:calendar_mode='WEEKDAYS' AND toDayOfWeek(snapshot_date)<=5)))),
 period_raw AS (
 SELECT toInt64(vendor_code) AS vendor_code,vendor_name,snapshot_date,
 if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))) AS raw_code,
 if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)) AS raw_source,
 toInt64(past_due_count) AS pd,toInt64(total_open_count) AS op,false AS is_control
 FROM past_due.vendor_past_due_daily
 WHERE country_code=:country_code
 AND snapshot_date IN (toDate(:target_snapshot_date),toDate(:previous_snapshot_date))
 UNION ALL
 SELECT toInt64(0),'',toDate('1970-01-01'),'','',toInt64(0),toInt64(0),true),
 expanded AS (SELECT *,arrayJoin(if(is_control,[tuple('control','',false)],
 [tuple('metrics','',false),tuple('code',raw_code,true),tuple('source',raw_source,true),
 tuple('code','',false),tuple('source','',false),tuple('us','',false),
 tuple(if(raw_code='US','us','skip'),raw_source,true)])) AS dim FROM period_raw),
 grouped AS (SELECT vendor_code,is_control,min(vendor_name) AS bucket_vendor_name,
 tupleElement(dim,1) AS dimension,tupleElement(dim,2) AS eta_value,tupleElement(dim,3) AS is_bucket,
 sumIf(pd,snapshot_date=toDate(:previous_snapshot_date) AND (dimension='metrics' OR is_bucket)) AS previous_count,
 sumIf(pd,snapshot_date=toDate(:target_snapshot_date) AND (dimension='metrics' OR is_bucket)) AS target_count,
 sumIf(op,snapshot_date=toDate(:previous_snapshot_date) AND dimension='metrics') AS previous_metric_open,
 sumIf(op,snapshot_date=toDate(:target_snapshot_date) AND dimension='metrics') AS target_metric_open,
 sumIf(pd,snapshot_date=toDate(:previous_snapshot_date) AND dimension='metrics' AND raw_code='US') AS previous_metric_us,
 sumIf(pd,snapshot_date=toDate(:target_snapshot_date) AND dimension='metrics' AND raw_code='US') AS target_metric_us,
 max(pd<0 OR op<0 OR pd>op) AS bad_row
 FROM expanded WHERE tupleElement(dim,1)!='skip'
 GROUP BY vendor_code,is_control,dimension,eta_value,is_bucket),
 totals AS (SELECT *,min(bucket_vendor_name) OVER v AS vendor_name,
 sumIf(previous_count,dimension='metrics') OVER v AS previous_past_due_count,
 sumIf(target_count,dimension='metrics') OVER v AS target_past_due_count,
 sum(previous_metric_open) OVER v AS previous_open_count,sum(target_metric_open) OVER v AS target_open_count,
 sum(previous_metric_us) OVER v AS previous_us_total,sum(target_metric_us) OVER v AS target_us_total,
 sumIf(previous_count,dimension='metrics') OVER () AS previous_country_past_due,
 sumIf(target_count,dimension='metrics') OVER () AS target_country_past_due,
 sum(previous_metric_open) OVER () AS previous_country_open,sum(target_metric_open) OVER () AS target_country_open,
 max(bad_row) OVER () AS invalid_facts
 FROM grouped WINDOW v AS (PARTITION BY is_control,vendor_code)),
 calculated AS (SELECT *,target_past_due_count-previous_past_due_count AS past_due_count_change,
 """+ratio('target_past_due_count','target_country_open')+""" AS target_country_contribution,
 """+ratio('previous_past_due_count','previous_country_open')+""" AS previous_country_contribution,
 target_country_contribution-previous_country_contribution AS delta_country_contribution,
 """+ratio('target_past_due_count','target_open_count')+""" AS target_vendor_ratio,
 """+ratio('previous_past_due_count','previous_open_count')+""" AS previous_vendor_ratio,
 toDecimal256(target_past_due_count,0)*previous_country_open-toDecimal256(previous_past_due_count,0)*target_country_open AS contribution_numerator
 FROM totals),
 ranked AS (SELECT *,toInt64(dense_rank() OVER (ORDER BY is_control,contribution_numerator DESC,vendor_code)) AS contribution_rank,
 sumIf(greatest(contribution_numerator,toDecimal256(0,0)),dimension='metrics') OVER () AS positive_numerator
 FROM calculated),
 marked AS (SELECT *,NOT is_control AND (
 (:selection_mode='POSITIVE_TOP_N' AND contribution_numerator>0 AND contribution_rank<=:top_n)
 OR (:selection_mode='SINGLE_VENDOR' AND vendor_code=:vendor_code)) AS chosen
 FROM ranked),
 selected_metadata AS (SELECT *,
 toInt64(countIf(chosen AND dimension='metrics') OVER ()) AS selected_vendor_count,
 """+ratio('greatest(contribution_numerator,0)','positive_numerator')+""" AS positive_contribution_share,
 """+ratio("sumIf(greatest(contribution_numerator,toDecimal256(0,0)),chosen AND dimension='metrics') OVER ()",'positive_numerator')+""" AS selected_positive_coverage
 FROM marked),
 scoped AS (SELECT m.*,multiIf(
 :selection_mode NOT IN ('POSITIVE_TOP_N','SINGLE_VENDOR') OR :top_n NOT BETWEEN 1 AND 10
 OR (:selection_mode='POSITIVE_TOP_N' AND :vendor_code!=0)
 OR (:selection_mode='SINGLE_VENDOR' AND (:top_n!=1 OR :vendor_code=0)),'INVALID_SELECTION',
 :calendar_mode NOT IN ('WEEKDAYS','CALENDAR_DAYS') OR toDate(:previous_snapshot_date)>=toDate(:target_snapshot_date)
 OR d.target_exists=0 OR d.previous_exists=0 OR d.nearest_previous!=toDate(:previous_snapshot_date),'INVALID_PAIR',
 invalid_facts>0,'DATA_ERROR',target_country_open<=0 OR previous_country_open<=0,'INVALID_DENOMINATOR',
 :selection_mode='SINGLE_VENDOR' AND selected_vendor_count=0,'VENDOR_NOT_FOUND','OK') AS scope_status
 FROM selected_metadata m CROSS JOIN date_guard d),
 bucket_ranked AS (SELECT *,target_count-previous_count AS bucket_delta,
 toInt64(row_number() OVER (PARTITION BY is_control,vendor_code,dimension ORDER BY is_bucket DESC,abs(bucket_delta) DESC,target_count DESC,eta_value)) AS display_rank
 FROM scoped WHERE is_control OR (chosen AND scope_status='OK')),
 enriched AS (SELECT *,toInt64(countIf(is_bucket) OVER w) AS expected_bucket_count,
 sum(previous_count) OVER w AS previous_total,sum(target_count) OVER w AS target_total,
 sum(greatest(bucket_delta,0)) OVER w AS increase_total,sum(least(bucket_delta,0)) OVER w AS decrease_total,
 sumIf(previous_count,display_rank>5 AND is_bucket) OVER w AS other_previous,
 sumIf(target_count,display_rank>5 AND is_bucket) OVER w AS other_target,
 toInt64(countIf(display_rank>5 AND is_bucket) OVER w) AS other_bucket_count,
 if(dimension='us',previous_us_total,previous_past_due_count) AS previous_expected_total,
 if(dimension='us',target_us_total,target_past_due_count) AS target_expected_total,
 dimension!='us' OR previous_us_total>0 OR target_us_total>0 AS required
 FROM bucket_ranked WINDOW w AS (PARTITION BY is_control,vendor_code,dimension)),
 tagged AS (SELECT *,arrayJoin(if(is_control,['COUNTRY_CONTROL'],if(chosen AND scope_status='OK',arrayConcat(
 if(dimension='metrics',['VENDOR_METRICS'],[]),
 if(NOT is_bucket AND dimension IN ('code','source','us'),['VENDOR_CHECK'],[]),
 if(is_bucket AND display_rank<=6,[multiIf(dimension='code','ETA_CODE_SUMMARY',dimension='source','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')],[])),[]))) AS tag
 FROM enriched)
 """


def add_report(doc):
    core={'scope-key':scope_key(QUERY),'country-code':':country_code',
          'target-snapshot-date':'toDate(:target_snapshot_date)',
          'previous-snapshot-date':'toDate(:previous_snapshot_date)',
          'calendar-mode':':calendar_mode','metric-version':"'3.1.0'",
          'selection-mode':':selection_mode','business-timezone':"'America/Los_Angeles'",
          'queried-at-local':NOW_PST}
    vals=dict(core,**{n:'t.'+n.replace('-','_') for n,_ in VENDOR_FIELDS if n not in core})
    summary="t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')"
    bp="if(t.display_rank=6,t.other_previous,t.previous_count)"
    bt="if(t.display_rank=6,t.other_target,t.target_count)"
    vals.update({
        'record-type':'t.tag','vendor-code':'t.vendor_code','vendor-name':'t.vendor_name',
        'dimension':"if(t.tag IN ('COUNTRY_CONTROL','VENDOR_METRICS'),'',t.dimension)",
        'eta-value':f"if({summary} AND t.display_rank<=5,t.eta_value,'')",
        'row-kind':f"if({summary},if(t.display_rank=6,'OTHER','BUCKET'),'')",
        'scope-status':'t.scope_status','contribution-rank':'if(t.is_control,toInt64(0),t.contribution_rank)',
        'display-rank':f"if({summary},t.display_rank,toInt64(0))",
        'expected-bucket-count':'t.expected_bucket_count',
        'other-bucket-count':f"if({summary} AND t.display_rank=6,t.other_bucket_count,toInt64(0))",
        'previous-past-due-count':f"if({summary},{bp},t.previous_past_due_count)",
        'target-past-due-count':f"if({summary},{bt},t.target_past_due_count)",
        'past-due-count-change':f"if({summary},{bt}-{bp},t.past_due_count_change)",
        'past-due-count-relative-change':f"if({summary},"+ratio(bt+'-'+bp,bp)+','+ratio('t.past_due_count_change','t.previous_past_due_count')+')',
        'delta-country-contribution':f"if({summary},"+ratio(bt,'t.target_country_open')+'-'+ratio(bp,'t.previous_country_open')+",t.delta_country_contribution)",
        'increase-total':'t.increase_total','decrease-total':'t.decrease_total',
        'net-change':'t.target_total-t.previous_total','previous-total':'t.previous_total','target-total':'t.target_total',
        'previous-expected-total':'t.previous_expected_total','target-expected-total':'t.target_expected_total',
        'counts-match':'CAST(t.previous_total=t.previous_expected_total AND t.target_total=t.target_expected_total AS Bool)',
        'required':'CAST(t.required AS Bool)',
        'target-country-past-due-count':"if(t.is_control,t.target_country_past_due,toInt64(0))",
        'previous-country-past-due-count':"if(t.is_control,t.previous_country_past_due,toInt64(0))",
        'target-country-ratio':ratio('t.target_country_past_due','t.target_country_open'),
        'previous-country-ratio':ratio('t.previous_country_past_due','t.previous_country_open')})
    # Sparse fields only carry meaning for their declared record type. Control
    # always survives (even nonexistent dates/Country, empty selection or errors).
    output=sparse(REPORT_FIELDS,vals)+' FROM tagged t'
    sql=report_ctes()+', report_rows AS ('+output+""") SELECT * EXCEPT(expected_record_count,row_ordinal),
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

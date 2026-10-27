#!/usr/bin/env python3
"""Development-only deterministic Atlas extension builder. No database/network access.

Authoritative output is atlas/charts/pastdue.edn. Re-running replaces only v3
entities/resolvers, retaining legacy bindings. SQL fragments share one source.
"""
from pathlib import Path
from edn_io import K, read, write, EdnSet

ROOT = Path(__file__).resolve().parents[1]
GRAPH = ROOT / 'atlas/charts/pastdue.edn'
VERSION = '3.2.0'
BUSINESS_TZ = 'America/Los_Angeles'
NOW_PST = f"now('{BUSINESS_TZ}')"
TODAY_PST = f"toDate({NOW_PST})"
ID = ['country-code', 'target-snapshot-date', 'previous-snapshot-date', 'calendar-mode']
QUERY = ID + ['selection-mode', 'top-n', 'vendor-code']


def workdays(after, through):
    return f"toInt64(arrayCount(x -> toDayOfWeek(addDays({after}, x + 1)) <= 5, range(toUInt32(greatest(dateDiff('day', {after}, {through}), 0)))))"


def ratio(n, d):
    # Nullable Decimal rather than Nullable Float64 (known JDBC risk).
    return f"toDecimal128(toDecimal256({n}, 18) / nullIf(toDecimal256({d}, 18), 0), 18)"


def eligible(alias=''):
    p = alias + '.' if alias else ''
    return f"({p}snapshot_date < {TODAY_PST} AND (:calendar_mode = 'CALENDAR_DAYS' OR (:calendar_mode = 'WEEKDAYS' AND toDayOfWeek({p}snapshot_date) <= 5)))"


def daily(country=False):
    return f"""daily AS (
 SELECT country_code, snapshot_date, sum(toInt64(past_due_count)) AS past_due,
 sum(toInt64(total_open_count)) AS open_count
 FROM past_due.vendor_past_due_daily
 WHERE {eligible()} {'AND country_code=:country_code' if country else ''} GROUP BY country_code, snapshot_date)"""


def pair_ctes():
    return daily(country=True) + """,
 pair_dates AS (
 SELECT country_code,
 maxIf(snapshot_date, snapshot_date = toDate(:target_snapshot_date)) AS target_snapshot_date,
 maxIf(snapshot_date, snapshot_date < toDate(:target_snapshot_date)) AS previous_snapshot_date,
 countIf(snapshot_date < toDate(:target_snapshot_date)) AS previous_exists,
 countIf(snapshot_date = toDate(:target_snapshot_date)) AS target_exists
 FROM daily WHERE country_code = :country_code GROUP BY country_code),
 valid_pair AS (
 SELECT * FROM pair_dates WHERE target_exists = 1 AND previous_exists > 0
 AND previous_snapshot_date = toDate(:previous_snapshot_date)
 AND previous_snapshot_date < target_snapshot_date
 AND :calendar_mode IN ('WEEKDAYS', 'CALENDAR_DAYS')),
 period_rows AS (
 SELECT country_code, target_snapshot_date, previous_snapshot_date,
 target_snapshot_date AS snapshot_date FROM valid_pair
 UNION ALL
 SELECT country_code, target_snapshot_date, previous_snapshot_date,
 previous_snapshot_date AS snapshot_date FROM valid_pair),
 country_pair AS (
 SELECT p.country_code AS country_code, p.target_snapshot_date AS target_snapshot_date, p.previous_snapshot_date AS previous_snapshot_date,
 sumIf(d.past_due, d.snapshot_date = p.target_snapshot_date) AS target_country_past_due,
 sumIf(d.past_due, d.snapshot_date = p.previous_snapshot_date) AS previous_country_past_due,
 sumIf(d.open_count, d.snapshot_date = p.target_snapshot_date) AS target_country_open,
 sumIf(d.open_count, d.snapshot_date = p.previous_snapshot_date) AS previous_country_open
 FROM daily d INNER JOIN period_rows p ON d.country_code = p.country_code
 AND d.snapshot_date = p.snapshot_date
 GROUP BY p.country_code, p.target_snapshot_date, p.previous_snapshot_date),
 vendor_period AS (
 SELECT d.country_code AS country_code, d.vendor_code AS vendor_code, min(d.vendor_name) AS vendor_name,
 p.target_snapshot_date AS target_snapshot_date, p.previous_snapshot_date AS previous_snapshot_date,
 sumIf(toInt64(d.past_due_count), d.snapshot_date = p.target_snapshot_date) AS target_past_due_count,
 sumIf(toInt64(d.past_due_count), d.snapshot_date = p.previous_snapshot_date) AS previous_past_due_count,
 sumIf(toInt64(d.total_open_count), d.snapshot_date = p.target_snapshot_date) AS target_open_count,
 sumIf(toInt64(d.total_open_count), d.snapshot_date = p.previous_snapshot_date) AS previous_open_count
 FROM past_due.vendor_past_due_daily d INNER JOIN period_rows p
 ON d.country_code = p.country_code AND d.snapshot_date = p.snapshot_date
 GROUP BY d.country_code, d.vendor_code, p.target_snapshot_date, p.previous_snapshot_date),
 vendor_calc AS (
 SELECT v.*, c.target_country_open AS target_country_open, c.previous_country_open AS previous_country_open,
 v.target_past_due_count - v.previous_past_due_count AS past_due_count_change,
 """ + ratio('target_past_due_count', 'target_country_open') + """ AS target_country_contribution,
 """ + ratio('previous_past_due_count', 'previous_country_open') + """ AS previous_country_contribution,
 target_country_contribution - previous_country_contribution AS delta_country_contribution,
 toDecimal256(target_past_due_count, 0) * previous_country_open
 - toDecimal256(previous_past_due_count, 0) * target_country_open AS contribution_numerator,
 """ + ratio('target_past_due_count', 'target_open_count') + """ AS target_vendor_ratio,
 """ + ratio('previous_past_due_count', 'previous_open_count') + """ AS previous_vendor_ratio
 FROM vendor_period v INNER JOIN country_pair c ON v.country_code = c.country_code),
 ranked_vendors AS (
 SELECT *, toInt64(row_number() OVER (ORDER BY contribution_numerator DESC, vendor_code ASC)) AS contribution_rank,
 sum(if(contribution_numerator > 0, contribution_numerator, toDecimal256(0, 0))) OVER () AS positive_numerator,
 sum(contribution_numerator) OVER () AS net_numerator
 FROM vendor_calc),
 selected AS (
 SELECT *, """ + ratio('greatest(contribution_numerator, 0)', 'positive_numerator') + """ AS positive_contribution_share,
 """ + ratio('sum(greatest(contribution_numerator, 0)) OVER ()', 'positive_numerator') + """ AS selected_positive_coverage,
 toInt64(count() OVER ()) AS selected_vendor_count
 FROM ranked_vendors
 WHERE target_country_open > 0 AND previous_country_open > 0
 AND :top_n BETWEEN 1 AND 10
 AND (SELECT count() FROM country_pair WHERE target_country_past_due<0 OR previous_country_past_due<0
 OR target_country_past_due>target_country_open OR previous_country_past_due>previous_country_open)=0
 AND (SELECT count() FROM vendor_period WHERE target_past_due_count<0 OR previous_past_due_count<0
 OR target_open_count<0 OR previous_open_count<0 OR target_past_due_count>target_open_count
 OR previous_past_due_count>previous_open_count)=0
 AND ((:selection_mode = 'POSITIVE_TOP_N' AND contribution_numerator > 0 AND contribution_rank <= :top_n)
 OR (:selection_mode = 'SINGLE_VENDOR' AND vendor_code = :vendor_code)))"""


COMMON = [('country-code','string'),('vendor-code','integer'),('target-snapshot-date','date'),('previous-snapshot-date','date'),('calendar-mode','string')]
VENDOR_FIELDS = COMMON + [(x,'integer') for x in ['target-past-due-count','previous-past-due-count','past-due-count-change','target-open-count','previous-open-count','target-country-open','previous-country-open','contribution-rank','selected-vendor-count']] + [('vendor-name','string')] + [(x,'decimal') for x in ['target-vendor-ratio','previous-vendor-ratio','target-country-contribution','previous-country-contribution','delta-country-contribution','positive-contribution-share','selected-positive-coverage']]


def projection(fields, alias=''):
    return ', '.join((alias + '.' if alias else '') + x.replace('-', '_') for x, _ in fields)


def monitor_projection():
    optional={'previous-snapshot-date','previous-past-due-count','previous-open-count','snapshot-gap-days','missing-workdays-between'}
    return ', '.join(f"if(has_comparable_snapshots,toNullable({x.replace('-', '_')}),NULL) AS {x.replace('-', '_')}" if x in optional else x.replace('-','_') for x,_ in MONITOR_FIELDS)


def bucket_ctes(kind):
    dimension = "upper(trim(BOTH ' ' FROM ifNull(d.eta_code, '')))" if kind == 'code' else "trim(BOTH ' ' FROM ifNull(d.source, ''))"
    guard = " AND upper(trim(BOTH ' ' FROM ifNull(d.eta_code, ''))) = 'US'" if kind == 'us' else ''
    return f""",
 buckets AS (
 SELECT d.country_code AS country_code, d.vendor_code AS vendor_code, p.target_snapshot_date AS target_snapshot_date, p.previous_snapshot_date AS previous_snapshot_date,
 if(empty({dimension}), 'UNKNOWN', {dimension}) AS eta_value,
 sumIf(toInt64(d.past_due_count), d.snapshot_date = p.target_snapshot_date) AS target_past_due_count,
 sumIf(toInt64(d.past_due_count), d.snapshot_date = p.previous_snapshot_date) AS previous_past_due_count
 FROM past_due.vendor_past_due_daily d
 INNER JOIN period_rows p ON d.country_code = p.country_code AND d.snapshot_date = p.snapshot_date
 INNER JOIN selected s ON d.country_code = s.country_code AND d.vendor_code = s.vendor_code
 WHERE 1=1 {guard}
 GROUP BY d.country_code, d.vendor_code, p.target_snapshot_date, p.previous_snapshot_date, eta_value),
 bucket_calc AS (
 SELECT b.*, s.target_country_open AS target_country_open, s.previous_country_open AS previous_country_open, s.contribution_rank AS contribution_rank,
 b.target_past_due_count - b.previous_past_due_count AS past_due_count_change,
 {ratio('b.target_past_due_count', 's.target_country_open')} AS target_country_contribution,
 {ratio('b.previous_past_due_count', 's.previous_country_open')} AS previous_country_contribution,
 target_country_contribution - previous_country_contribution AS delta_country_contribution,
 {ratio('b.target_past_due_count-b.previous_past_due_count', 'b.previous_past_due_count')} AS past_due_count_relative_change,
 toInt64(row_number() OVER (PARTITION BY b.country_code, b.vendor_code
 ORDER BY abs(past_due_count_change) DESC, b.target_past_due_count DESC, eta_value ASC)) AS display_rank
 FROM buckets b INNER JOIN selected s ON b.country_code=s.country_code AND b.vendor_code=s.vendor_code),
 bucket_enriched AS (
 SELECT *, toInt64(count() OVER (PARTITION BY country_code, vendor_code)) AS expected_bucket_count,
 sum(greatest(past_due_count_change, 0)) OVER (PARTITION BY country_code, vendor_code) AS increase_total,
 sum(least(past_due_count_change, 0)) OVER (PARTITION BY country_code, vendor_code) AS decrease_total,
 sum(past_due_count_change) OVER (PARTITION BY country_code, vendor_code) AS net_change,
 sumIf(target_past_due_count, display_rank > 5) OVER (PARTITION BY country_code, vendor_code) AS other_target_count,
 sumIf(previous_past_due_count, display_rank > 5) OVER (PARTITION BY country_code, vendor_code) AS other_previous_count,
 toInt64(countIf(display_rank > 5) OVER (PARTITION BY country_code, vendor_code)) AS other_bucket_count
 FROM bucket_calc),
 bucket_final AS (
 SELECT *, other_target_count-other_previous_count AS other_count_change,
 {ratio('other_count_change', 'other_previous_count')} AS other_relative_change,
 {ratio('other_target_count', 'target_country_open')} - {ratio('other_previous_count', 'previous_country_open')} AS other_contribution_change,
 toInt64(row_number() OVER (ORDER BY contribution_rank, vendor_code, eta_value)) AS row_ordinal
 FROM bucket_enriched)"""

BUCKET_FIELDS = COMMON + [('eta-value','string')] + [(x,'integer') for x in ['target-past-due-count','previous-past-due-count','past-due-count-change','display-rank','row-ordinal','expected-bucket-count','increase-total','decrease-total','net-change','other-target-count','other-previous-count','other-bucket-count','other-count-change']] + [(x,'decimal') for x in ['target-country-contribution','previous-country-contribution','delta-country-contribution','past-due-count-relative-change','other-relative-change','other-contribution-change']]


def monitor_ctes():
    return daily() + """,
 ordered AS (
 SELECT *, toInt64(row_number() OVER (PARTITION BY country_code ORDER BY snapshot_date DESC)) AS newest_rank,
 lagInFrame(snapshot_date, 1, toDate('1970-01-01')) OVER
 (PARTITION BY country_code ORDER BY snapshot_date ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS previous_snapshot_date,
 lagInFrame(past_due, 1, toInt64(-1)) OVER
 (PARTITION BY country_code ORDER BY snapshot_date ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS previous_past_due_count,
 lagInFrame(open_count, 1, toInt64(-1)) OVER
 (PARTITION BY country_code ORDER BY snapshot_date ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS previous_open_count
 FROM daily),
 metrics AS (
 SELECT country_code, snapshot_date AS target_snapshot_date, previous_snapshot_date,
 past_due AS target_past_due_count, previous_past_due_count,
 open_count AS target_open_count, previous_open_count, newest_rank,
 if(previous_open_count >= 0, true, false) AS has_comparable_snapshots,
 """ + ratio('past_due', 'open_count') + """ AS target_past_due_ratio,
 """ + ratio('previous_past_due_count', 'nullIf(previous_open_count, -1)') + """ AS previous_past_due_ratio,
 target_past_due_ratio-previous_past_due_ratio AS percentage_point_change,
 """ + ratio('toDecimal256(past_due,0)*previous_open_count-toDecimal256(previous_past_due_count,0)*open_count', 'toDecimal256(previous_past_due_count,0)*open_count') + """ AS relative_ratio_change,
 if(toDayOfWeek(addDays(""" + TODAY_PST + """, -1))<=5, addDays(""" + TODAY_PST + """, -1),
 addDays(""" + TODAY_PST + """, -if(toDayOfWeek(""" + TODAY_PST + """)=1,3,2))) AS expected_target_date,
 """ + workdays('snapshot_date', 'expected_target_date') + """ AS target_lag_workdays,
 if(previous_open_count >= 0, greatest(""" + workdays('previous_snapshot_date', 'snapshot_date') + """-1,0), 0) AS missing_workdays_between,
 if(previous_open_count >= 0,dateDiff('day', previous_snapshot_date, snapshot_date),0) AS snapshot_gap_days,
 if(open_count < 0 OR past_due < 0 OR past_due > open_count OR
 (previous_open_count >= 0 AND (previous_past_due_count<0 OR previous_past_due_count>previous_open_count)), 'DATA_ERROR',
 if(previous_open_count < 0, 'INSUFFICIENT', if(target_lag_workdays>2,'STALE',if(target_lag_workdays>0,'DELAYED','OK')))) AS data_status,
 if(previous_open_count <= 0 OR open_count <= 0 OR data_status='DATA_ERROR', 'NOT_EVALUABLE',
 if(previous_past_due_count=0, if(past_due>0,'ZERO_BASELINE_INCREASE','NORMAL'),
 if((toDecimal256(past_due,0)*previous_open_count-toDecimal256(previous_past_due_count,0)*open_count)
 >= toDecimal256(:relative_change_threshold,18)*previous_past_due_count*open_count,'TRIGGERED','NORMAL'))) AS business_status,
 :calendar_mode AS calendar_mode
 FROM ordered)"""

MONITOR_FIELDS = [('country-code','string'),('target-snapshot-date','date'),('previous-snapshot-date','date'),('calendar-mode','string'),('expected-target-date','date'),('has-comparable-snapshots','boolean'),('business-status','string'),('data-status','string')] + [(x,'integer') for x in ['target-past-due-count','previous-past-due-count','target-open-count','previous-open-count','snapshot-gap-days','target-lag-workdays','missing-workdays-between']] + [(x,'decimal') for x in ['target-past-due-ratio','previous-past-due-ratio','relative-ratio-change','percentage-point-change']]


def nsattr(ns, field):
    return K(':' + ns + '/' + field)


def add_entity(doc, ns, fields, identity):
    attrs = doc[':vocabulary'][':attributes']
    for name, typ in fields:
        nullable=(typ=='decimal' and not ns.endswith('-query')) or (ns in ('pastdue.monitor-fact','pastdue.history-fact') and name in ('previous-snapshot-date','previous-past-due-count','previous-open-count','snapshot-gap-days','missing-workdays-between'))
        attrs[nsattr(ns, name)] = {K(':attribute/label'): name.replace('-', ' ').title(), K(':attribute/type'): K(':' + typ), K(':attribute/nullable?'): nullable, K(':attribute/description'): f'v3 {name}; see shipped semantic contract. Identity is from actual result grouping.'}
    doc[':vocabulary'][':entities'][ns] = {K(':entity/label'): ns.split('.')[-1].replace('-', ' ').title(), K(':entity/identity'): [nsattr(ns,x) for x in identity], K(':entity/attributes'): [nsattr(ns,x) for x,_ in fields], K(':entity/description'): 'Analysis contract; America/Los_Angeles (PST/PDT), fixed scope and verifiable facts.'}
    if ns not in doc[':graph'][':graph/entities']:doc[':graph'][':graph/entities'].append(ns)
    doc[':ownership'][':entities'][ns] = doc[':graph'][':graph/id']


def add_collection(doc, parent, collection, child, fields, inputs, sql, sort='row-ordinal'):
    rid = 'pastdue_v3_' + parent.split('.')[-1].replace('-','_') + '_' + collection.replace('-','_')
    coll = nsattr(parent, collection)
    doc[':vocabulary'][':attributes'][coll] = {K(':attribute/label'):collection.replace('-',' ').title(),K(':attribute/type'):K(':ref'),K(':attribute/nullable?'):False,K(':attribute/description'):'v3 bounded facts; fetch and reconcile every page before declaring completeness.',K(':attribute/target'):child,K(':attribute/cardinality'):K(':many')}
    entity = doc[':vocabulary'][':entities'][parent]
    if coll not in entity[':entity/attributes']:entity[':entity/attributes'].append(coll)
    outputs = [nsattr(child,x) for x,_ in fields]
    doc[':execution'][':resolvers'][rid] = {K(':label'):collection,K(':description'):'v3 fixed-scope read-only analysis',K(':effect'):K(':read'),K(':input'):[nsattr(parent,x) for x in inputs],K(':output'):[{coll:outputs}],K(':result/cardinality'):K(':many'),K(':pagination'):{K(':default'):200,K(':max'):1000},K(':sort'):{K(':allowed'):outputs,K(':default'):[nsattr(child,sort),K(':asc')]}}
    bid = rid+'-sql'
    doc[':execution'][':bindings'][bid] = {K(':binding/for'):[K(':resolver'),rid],K(':binding/source'):'eta-clickhouse',K(':binding/effect'):K(':read'),K(':binding/implementation'):{K(':query'):sql},K(':binding/input-map'):{nsattr(parent,x):x.replace('-','_') for x in inputs},K(':binding/output-map'):{coll:collection.replace('-','_'),**{nsattr(child,x):x.replace('-','_') for x,_ in fields}}}
    doc[':execution'][':selection'][(K(':resolver'),rid)] = bid
    if rid not in doc[':graph'][':graph/resolvers']:doc[':graph'][':graph/resolvers'].append(rid)
    doc[':ownership'][':resolvers'][rid] = doc[':graph'][':graph/id']


def build(graph_path=None):
    graph_path = Path(graph_path) if graph_path is not None else GRAPH
    doc=read(graph_path)
    query_fields=[(x,'date' if x.endswith('date') else 'integer' if x in ('top-n','vendor-code') else 'string') for x in QUERY]
    add_entity(doc,'pastdue.analysis-query',query_fields,QUERY)
    add_entity(doc,'pastdue.analysis-vendor',VENDOR_FIELDS,ID+['vendor-code'])
    for suffix in ('code','source','us-source'):
        add_entity(doc,'pastdue.analysis-eta-'+suffix,BUCKET_FIELDS,ID+['vendor-code','eta-value'])
    base=pair_ctes()
    vendor_sql='WITH '+base+" SELECT "+projection([(x,t) for x,t in VENDOR_FIELDS if x!='calendar-mode'])+", :calendar_mode AS calendar_mode FROM selected"
    add_collection(doc,'pastdue.analysis-query','vendor-comparisons','pastdue.analysis-vendor',VENDOR_FIELDS,QUERY,vendor_sql,'contribution-rank')
    for kind,coll,suffix in [('code','eta-code-changes','code'),('source','eta-source-changes','source'),('us','us-source-changes','us-source')]:
        sql='WITH '+base+bucket_ctes(kind)+' SELECT '+projection([(x,t) for x,t in BUCKET_FIELDS if x!='calendar-mode'])+', :calendar_mode AS calendar_mode FROM bucket_final'
        add_collection(doc,'pastdue.analysis-query',coll,'pastdue.analysis-eta-'+suffix,BUCKET_FIELDS,QUERY,sql)
    # One source scan expands tagged dimensions without a Code×Source product.
    # Independent aggregation also provides expected bucket counts before paging.
    checks=base+""",
 check_expanded AS (
 SELECT d.country_code AS country_code,d.vendor_code AS vendor_code,d.snapshot_date AS snapshot_date,
 p.target_snapshot_date AS target_snapshot_date,p.previous_snapshot_date AS previous_snapshot_date,
 toInt64(d.past_due_count) AS past_due_count,
 arrayJoin([tuple('code',if(empty(trim(ifNull(d.eta_code,''))),'UNKNOWN',upper(trim(d.eta_code)))),
 tuple('source',if(empty(trim(ifNull(d.source,''))),'UNKNOWN',trim(d.source))),
 tuple(if(upper(trim(ifNull(d.eta_code,'')))='US','us','skip'),if(empty(trim(ifNull(d.source,''))),'UNKNOWN',trim(d.source)))]) AS dim
 FROM past_due.vendor_past_due_daily d INNER JOIN period_rows p
 ON d.country_code=p.country_code AND d.snapshot_date=p.snapshot_date
 INNER JOIN selected s ON d.country_code=s.country_code AND d.vendor_code=s.vendor_code),
 check_buckets AS (
 SELECT country_code,vendor_code,tupleElement(dim,1) AS dimension,tupleElement(dim,2) AS eta_value,
 sumIf(past_due_count,snapshot_date=previous_snapshot_date) AS previous_count,
 sumIf(past_due_count,snapshot_date=target_snapshot_date) AS target_count
 FROM check_expanded WHERE tupleElement(dim,1)!='skip'
 GROUP BY country_code,vendor_code,dimension,eta_value),
 dimension_totals AS (
 SELECT country_code,vendor_code,dimension,toInt64(count()) AS bucket_count,
 sum(previous_count) AS previous_total,sum(target_count) AS target_total,
 sum(greatest(target_count-previous_count,0)) AS increase_total,
 sum(least(target_count-previous_count,0)) AS decrease_total
 FROM check_buckets GROUP BY country_code,vendor_code,dimension),
 check_rows AS (SELECT s.*,arrayJoin(['code','source','us']) AS dimension FROM selected s)"""
    cf=COMMON+[('dimension','string')]+[(x,'integer') for x in ['expected-bucket-count','previous-total','target-total','increase-total','decrease-total','net-change','previous-vendor-count','target-vendor-count','selected-vendor-count','row-ordinal']]+[('counts-match','boolean'),('required','boolean')]
    add_entity(doc,'pastdue.analysis-check',cf,ID+['vendor-code','dimension'])
    sql='WITH '+checks+""" SELECT s.country_code AS country_code,s.vendor_code AS vendor_code,s.target_snapshot_date AS target_snapshot_date,s.previous_snapshot_date AS previous_snapshot_date,:calendar_mode AS calendar_mode,
 s.dimension AS dimension,toInt64(ifNull(a.bucket_count,0)) AS expected_bucket_count,
 ifNull(a.previous_total,0) AS previous_total,ifNull(a.target_total,0) AS target_total,
 ifNull(a.increase_total,0) AS increase_total,ifNull(a.decrease_total,0) AS decrease_total,
 target_total-previous_total AS net_change,
 s.previous_past_due_count AS previous_vendor_count,s.target_past_due_count AS target_vendor_count,
 s.selected_vendor_count AS selected_vendor_count,
 if((s.dimension='us' AND previous_total=ifNull(u.previous_us_count,0) AND target_total=ifNull(u.target_us_count,0))
 OR (s.dimension!='us' AND previous_total=previous_vendor_count AND target_total=target_vendor_count),true,false) AS counts_match,
 if(s.dimension!='us' OR ifNull(u.previous_us_count,0)>0 OR ifNull(u.target_us_count,0)>0,true,false) AS required,
 toInt64(row_number() OVER (ORDER BY s.contribution_rank,s.vendor_code,s.dimension)) AS row_ordinal
 FROM check_rows s LEFT JOIN dimension_totals a
 ON s.country_code=a.country_code AND s.vendor_code=a.vendor_code AND s.dimension=a.dimension
 LEFT JOIN (SELECT country_code,vendor_code,sum(previous_count) AS previous_us_count,
 sum(target_count) AS target_us_count FROM check_buckets WHERE dimension='code' AND eta_value='US'
 GROUP BY country_code,vendor_code) u ON s.country_code=u.country_code AND s.vendor_code=u.vendor_code"""
    add_collection(doc,'pastdue.analysis-query','vendor-checks','pastdue.analysis-check',cf,QUERY,sql)
    # A control row exists even when selection is empty or the pair is invalid.
    # Otherwise zero selected Vendors is indistinguishable from failed validation.
    sf=[('country-code','string'),('target-snapshot-date','date'),('previous-snapshot-date','date'),('calendar-mode','string'),('scope-status','string'),('selected-vendor-count','integer'),('target-country-open','integer'),('previous-country-open','integer'),('row-ordinal','integer')]
    add_entity(doc,'pastdue.analysis-scope',sf,ID)
    sql='WITH '+base+""" SELECT :country_code AS country_code,toDate(:target_snapshot_date) AS target_snapshot_date,
 toDate(:previous_snapshot_date) AS previous_snapshot_date,:calendar_mode AS calendar_mode,
 if(:selection_mode NOT IN ('POSITIVE_TOP_N','SINGLE_VENDOR') OR :top_n NOT BETWEEN 1 AND 10,'INVALID_SELECTION',
 if((SELECT count() FROM valid_pair)=0,'INVALID_PAIR',
 if(ifNull((SELECT min(target_country_open) FROM country_pair),0)<=0 OR ifNull((SELECT min(previous_country_open) FROM country_pair),0)<=0,'INVALID_DENOMINATOR',
 if((SELECT count() FROM country_pair WHERE target_country_past_due<0 OR previous_country_past_due<0 OR target_country_past_due>target_country_open OR previous_country_past_due>previous_country_open)>0
 OR (SELECT count() FROM vendor_period WHERE target_past_due_count<0 OR previous_past_due_count<0 OR target_open_count<0 OR previous_open_count<0 OR target_past_due_count>target_open_count OR previous_past_due_count>previous_open_count)>0,'DATA_ERROR',
 if(:selection_mode='SINGLE_VENDOR' AND (SELECT count() FROM selected)=0,'VENDOR_NOT_FOUND','OK'))))) AS scope_status,
 toInt64((SELECT count() FROM selected)) AS selected_vendor_count,
 toInt64(ifNull((SELECT max(target_country_open) FROM country_pair),0)) AS target_country_open,
 toInt64(ifNull((SELECT max(previous_country_open) FROM country_pair),0)) AS previous_country_open,
 toInt64(1) AS row_ordinal"""
    add_collection(doc,'pastdue.analysis-query','scope-status','pastdue.analysis-scope',sf,QUERY,sql)
    # Monitor is parameterized: required calendar and threshold; no inaccessible implicit defaults.
    mns='pastdue.monitor-query';mf=[('calendar-mode','string'),('relative-change-threshold','decimal')]
    add_entity(doc,mns,mf,[x for x,_ in mf]);add_entity(doc,'pastdue.monitor-fact',MONITOR_FIELDS,['country-code','target-snapshot-date','calendar-mode'])
    add_collection(doc,mns,'countries','pastdue.monitor-fact',MONITOR_FIELDS,[x for x,_ in mf],'WITH '+monitor_ctes()+' SELECT '+monitor_projection()+" FROM metrics WHERE newest_rank=1",'country-code')
    hns='pastdue.history-query';hf=[('country-code','string'),('calendar-mode','string'),('range-start-date','date'),('range-end-date','date'),('relative-change-threshold','decimal')]
    add_entity(doc,hns,hf,[x for x,_ in hf]);add_entity(doc,'pastdue.history-fact',MONITOR_FIELDS,['country-code','target-snapshot-date','calendar-mode'])
    bounds=" country_code=:country_code AND target_snapshot_date BETWEEN toDate(:range_start_date) AND toDate(:range_end_date) AND dateDiff('day',toDate(:range_start_date),toDate(:range_end_date)) BETWEEN 0 AND 365"
    for coll,extra in [('comparisons',''),('trigger-events'," AND business_status IN ('TRIGGERED','ZERO_BASELINE_INCREASE')")]:
        add_collection(doc,hns,coll,'pastdue.history-fact',MONITOR_FIELDS,[x for x,_ in hf],'WITH '+monitor_ctes()+' SELECT '+monitor_projection()+' FROM metrics WHERE'+bounds+extra,'target-snapshot-date')
    # Current aggregate is a new, corrected API; legacy fields remain explicitly deprecated.
    add_current(doc)
    # Repair organization filtering in the current detail entry: any nonempty
    # org filter must constrain rows. Avoid CH default-value LEFT JOIN behavior.
    detail_binding=doc[':execution'][':bindings']['pastdue_current_detail_matching_lines-sql'][':binding/implementation']
    q=detail_binding[':query']
    old="AND (empty(trim(BOTH ' ' FROM ifNull(:department_type_filter, '')))  OR empty(trim(BOTH ' ' FROM ifNull(:master_primary_filter, '')))  OR empty(trim(BOTH ' ' FROM ifNull(:master_manager_filter, '')))  OR m.vendor_code IS NOT NULL)"
    new="AND ((empty(trim(BOTH ' ' FROM ifNull(:department_type_filter, ''))) AND empty(trim(BOTH ' ' FROM ifNull(:master_primary_filter, ''))) AND empty(trim(BOTH ' ' FROM ifNull(:master_manager_filter, '')))) OR (t.country_code,toInt64(t.from_acct_no)) IN (SELECT country_code,vendor_code FROM mapped))"
    detail_binding[K(':query')]=q.replace(old,new)
    # Existing Alert API retains field meaning but no longer excludes unmapped
    # Vendors when no organization restriction was requested.
    alert=doc[':execution'][':bindings']['pastdue_current_vendor_alert_type_counts-sql'][':binding/implementation']
    aq=alert[':query']
    aq=aq.replace('SELECT t.country_code, toInt64(t.from_acct_no)', 'SELECT t.country_code AS country_code, toInt64(t.from_acct_no)')
    aq=aq.replace('INNER JOIN mapped AS m ON toInt64(t.from_acct_no) = m.vendor_code','LEFT JOIN mapped AS m ON toInt64(t.from_acct_no) = m.vendor_code')
    if '/* v3 org guard */' not in aq:
        guard=" AND ((empty(trim(ifNull(:department_type_filter,''))) AND empty(trim(ifNull(:master_primary_filter,''))) AND empty(trim(ifNull(:master_manager_filter,'')))) OR toInt64(t.from_acct_no) IN (SELECT vendor_code FROM mapped)) /* v3 org guard */ "
        pos=aq.rfind(' GROUP BY ')
        if pos>=0: aq=aq[:pos]+guard+aq[pos:]
    alert[K(':query')]=aq
    for ns in ['pastdue.country-monitor','pastdue.country-history-query','pastdue.current-vendor-past-due-rank']:
        doc[':vocabulary'][':entities'][ns][K(':entity/description')]='LEGACY v2 semantics; not used by v3 default flows. '+doc[':vocabulary'][':entities'][ns].get(':entity/description','') if not doc[':vocabulary'][':entities'][ns].get(':entity/description','').startswith('LEGACY') else doc[':vocabulary'][':entities'][ns][':entity/description']
    for a in ['vendor-total-count','pastdue-rate-in-vendor','pastdue-rate-in-total']:
        d=doc[':vocabulary'][':attributes'][nsattr('pastdue.current-vendor-past-due-rank',a)]
        d[K(':attribute/description')]='DEPRECATED legacy past-due-only/mapped population metric; do not use as full Vendor Open ratio. Use pastdue.current-analysis-query.'
    guide=doc[':graph'][':graph/usage-guide']
    guide[K(':guide/limitations')]=['Legacy v2 entries retain calendar-day behavior; v3 uses explicit calendar mode.','v3 SQL selects Vendor Top N and computes ETA Top-5/Other summaries before paging.','Read and reconcile every facts/check page; returned-result completeness alone is insufficient.','Permissions/RLS must be enforced by the actual deployment; Skill allowlists are not access control.','No cross-query transaction or production usability claim without live validation.']
    doc[':publication'][K(':release/version')]=VERSION
    doc[':publication'][K(':source/revision')]=3
    examples=doc[':graph'][':graph/query-examples']
    examples[:]=[ex for ex in examples if not ex.get(':example/id','').startswith('v3-')]
    for rid,title in [('pastdue_v3_monitor_query_countries','Weekday monitor'),('pastdue_v3_analysis_query_scope_status','Fixed-pair scope validation')]:
        r=doc[':execution'][':resolvers'][rid]
        values={'calendar-mode':'WEEKDAYS','relative-change-threshold':0.10,'country-code':'CA','target-snapshot-date':'2026-10-26','previous-snapshot-date':'2026-10-23','selection-mode':'POSITIVE_TOP_N','top-n':10,'vendor-code':0}
        from edn_io import dumps
        output=r[':output']
        example_id='v3-'+title.lower().replace(' ','-')
        examples.append({K(':example/id'):example_id,K(':example/title'):title,K(':example/description'):'v3 example only; replace illustrative dates with actual validated scope.',K(':example/eql'):dumps(output),K(':example/entity'):{a:values[a.rsplit('/',1)[-1]] for a in r[':input']},K(':example/parameters'):{},K(':example/requires'):EdnSet([K(':query/pagination-v1'),K(':connector/sql'),K(':query/sort-v1')]),K(':example/expected'):{K(':result/cardinality'):K(':many'),K(':result/fields'):list(output[0].values())[0]}})
    guide[K(':guide/examples')]=[ex[':example/id'] for ex in examples if ex[':example/id'].startswith('v3-')]
    from build_v31_contract import add_v31
    add_v31(doc)
    from build_report_blocks import add_report_blocks, add_monitor_blocks
    add_report_blocks(doc)
    add_monitor_blocks(doc)
    write(graph_path,doc)
    return doc


def add_current(doc):
    ns='pastdue.current-analysis-query'
    fields=[('country-code','string'),('grain','string'),('vendor-code-filter','string'),('department-type-filter','string'),('master-primary-filter','string'),('master-manager-filter','string'),('denominator-scope','string')]
    add_entity(doc,ns,fields,[x for x,_ in fields])
    out=[('country-code','string'),('vendor-code','integer'),('vendor-name','string'),('eta-value','string'),('grain','string'),('denominator-scope','string'),('open-line-count','integer'),('past-due-count','integer'),('denominator-past-due-count','integer'),('vendor-past-due-count','integer'),('past-due-ratio','decimal'),('past-due-share','decimal'),('within-vendor-share','decimal'),('row-ordinal','integer'),('expected-row-count','integer'),('queried-at-utc','datetime')]
    add_entity(doc,'pastdue.current-analysis-fact',out,['country-code','vendor-code','grain','eta-value','denominator-scope'])
    sql="""WITH mapped AS (
 SELECT DISTINCT country_code,toInt64(vendor_no) AS vendor_code FROM past_due.vendor_master_mapping_view
 WHERE country_code=:country_code
 AND (empty(:department_type_filter) OR upper(trim(department_type))=upper(trim(:department_type_filter)))
 AND (empty(:master_primary_filter) OR trim(ifNull(master_primary,''))=trim(:master_primary_filter))
 AND (empty(:master_manager_filter) OR trim(ifNull(master_manager,''))=trim(:master_manager_filter))),
 detail AS (SELECT t.* FROM past_due.past_due_detail_view t WHERE t.country_code=:country_code AND t.from_acct_no IS NOT NULL),
 scoped AS (SELECT * FROM detail WHERE (empty(:vendor_code_filter) OR toString(toInt64(from_acct_no))=:vendor_code_filter)
 AND ((empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter))
 OR (country_code,toInt64(from_acct_no)) IN (SELECT country_code,vendor_code FROM mapped))),
 totals AS (SELECT (SELECT toInt64(countIf(toInt64(is_pastdue)=1)) FROM detail) AS country_total,
 (SELECT toInt64(countIf(toInt64(is_pastdue)=1)) FROM scoped) AS filtered_total),
 vendor_totals AS (SELECT country_code,toInt64(from_acct_no) AS vendor_code,toInt64(countIf(toInt64(is_pastdue)=1)) AS vendor_past_due_count FROM scoped GROUP BY country_code,vendor_code),
 aggregate_rows AS (
 SELECT country_code,toInt64(from_acct_no) AS vendor_code,min(vendor_name) AS vendor_name,
 if(:grain='VENDOR','ALL',if(:grain='VENDOR_CODE',if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))),if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)))) AS eta_value,
 toInt64(count()) AS open_line_count,toInt64(countIf(toInt64(is_pastdue)=1)) AS past_due_count
 FROM scoped WHERE :grain IN ('VENDOR','VENDOR_CODE','VENDOR_SOURCE') AND :denominator_scope IN ('COUNTRY_ALL','FILTERED_POPULATION')
 GROUP BY country_code,vendor_code,eta_value)
 SELECT a.country_code AS country_code,a.vendor_code AS vendor_code,a.vendor_name AS vendor_name,a.eta_value AS eta_value,
 a.open_line_count AS open_line_count,a.past_due_count AS past_due_count,
 v.vendor_past_due_count AS vendor_past_due_count,:grain AS grain,:denominator_scope AS denominator_scope,
 if(:denominator_scope='COUNTRY_ALL',t.country_total,t.filtered_total) AS denominator_past_due_count,
 """+ratio('a.past_due_count','a.open_line_count')+' AS past_due_ratio, '+ratio('a.past_due_count','denominator_past_due_count')+' AS past_due_share, '+ratio('a.past_due_count','v.vendor_past_due_count')+""" AS within_vendor_share,
 toInt64(row_number() OVER (ORDER BY a.past_due_count DESC,a.vendor_code,a.eta_value)) AS row_ordinal,
 toInt64(count() OVER ()) AS expected_row_count,""" + NOW_PST + """ AS queried_at_utc
 FROM aggregate_rows a INNER JOIN vendor_totals v ON a.country_code=v.country_code AND a.vendor_code=v.vendor_code CROSS JOIN totals t"""
    add_collection(doc,ns,'rows','pastdue.current-analysis-fact',out,[x for x,_ in fields],sql)


if __name__=='__main__':
    d=build()
    print('Generated',len(d[':execution'][':resolvers']),'resolvers;',len(d[':vocabulary'][':attributes']),'attributes. NOT live-validated.')

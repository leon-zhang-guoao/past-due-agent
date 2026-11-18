"""Add paged, trigger-independent reports; preserve every older binding."""
import copy
from build_analysis_contract import add_collection, add_entity
from build_explore_v2 import vendor_selection_cte
from build_v31_contract import REPORT_FIELDS, report_ctes, add_report, scope_key
from edn_io import K

PARENT = 'pastdue.report-v2-query'
CHILD = 'pastdue.report-v2-fact'
RID = 'pastdue_v3_report_v2_query_rows'
INPUTS = [('country-code', 'string'), ('target-snapshot-date', 'date'),
          ('previous-snapshot-date', 'date'), ('calendar-mode', 'string'),
          ('selection-mode', 'string'), ('top-n', 'integer'),
          ('vendor-codes-json', 'string'), ('page-after-ordinal', 'integer'),
          ('page-size', 'integer')]
LOGICAL_INPUTS = [n for n, _ in INPUTS[:-2]]
PAGE_FIELDS = [('requested-vendor-count', 'integer'), ('page-after-ordinal', 'integer'),
               ('page-size', 'integer'), ('page-record-count', 'integer'),
               ('next-after-ordinal', 'integer'), ('has-more', 'boolean'),
               ('result-fingerprint', 'string')]
FIELDS = REPORT_FIELDS + PAGE_FIELDS


def ctes():
    """Reuse the linear report pipeline, carrying requested identities as seeds.

    Seeds enter metrics only: they cannot invent an ETA bucket. All-country
    metrics survive Vendor selection, keeping ranks and denominators unchanged.
    """
    q = report_ctes().replace('WITH\n ', 'WITH\n '+vendor_selection_cte()+'\n ', 1)
    q = q.replace('false AS is_control', 'false AS is_control,false AS is_seed')
    q = q.replace("toInt64(0),toInt64(0),true)", """toInt64(0),toInt64(0),true,true
 UNION ALL
 SELECT arrayJoin(requested_vendors),'',toDate('1970-01-01'),'','',toInt64(0),toInt64(0),false,true
 FROM vendor_selection WHERE :selection_mode='VENDOR_SET' AND vendor_selection_valid),""".rstrip(','))
    q = q.replace("arrayJoin(if(is_control,[tuple('control','',false)],",
                  "arrayJoin(multiIf(is_control,[tuple('control','',false)],is_seed OR "
                  "(:selection_mode='VENDOR_SET' AND vendor_code NOT IN "
                  "(SELECT arrayJoin(requested_vendors) FROM vendor_selection)),[tuple('metrics','',false)],")
    q = q.replace('max(pd<0 OR op<0 OR pd>op) AS bad_row',
                  'max(NOT is_seed AND NOT is_control) AS source_present,\n max(pd<0 OR op<0 OR pd>op) AS bad_row')
    q = q.replace('min(vendor_name) AS bucket_vendor_name',
                  'minIf(vendor_name,NOT is_seed AND NOT is_control) AS bucket_vendor_name')
    q = q.replace('totals AS (SELECT *,min(bucket_vendor_name)',
                  'totals AS (SELECT *,max(source_present) OVER v AS has_readable_rows,min(bucket_vendor_name)')
    # Absent requested identities must not shift the global contribution rank.
    q = q.replace('ORDER BY is_control,contribution_numerator DESC,vendor_code',
                  'ORDER BY is_control,has_readable_rows DESC,contribution_numerator DESC,vendor_code')
    q = q.replace('NOT is_control AND (', 'NOT is_control AND has_readable_rows AND (')
    q = q.replace("OR (:selection_mode='SINGLE_VENDOR' AND vendor_code=:vendor_code)",
                  "OR (:selection_mode='TOP_N' AND contribution_rank<=:top_n)\n "
                  "OR (:selection_mode='VENDOR_SET' AND vendor_code IN "
                  "(SELECT arrayJoin(requested_vendors) FROM vendor_selection))")
    start = q.index(" :selection_mode NOT IN")
    end = q.index(" :calendar_mode NOT IN", start)
    q = q[:start]+""" NOT (SELECT vendor_selection_valid FROM vendor_selection),'INVALID_VENDOR_SELECTION',
 :selection_mode NOT IN ('POSITIVE_TOP_N','TOP_N','VENDOR_SET')
 OR (:selection_mode='VENDOR_SET' AND (:top_n!=0 OR empty((SELECT requested_vendors FROM vendor_selection))))
 OR (:selection_mode!='VENDOR_SET' AND (:top_n NOT BETWEEN 1 AND 100
 OR notEmpty((SELECT requested_vendors FROM vendor_selection)))),'INVALID_SELECTION',
 :page_after_ordinal<0 OR :page_size NOT BETWEEN 1 AND 199,'INVALID_PAGE',
"""+q[end:]
    q = q.replace(" :selection_mode='SINGLE_VENDOR' AND selected_vendor_count=0,'VENDOR_NOT_FOUND',", ' ')
    q = q.replace("FROM scoped WHERE is_control OR (chosen AND scope_status='OK')",
                  "FROM scoped WHERE is_control OR (scope_status='OK' AND (chosen OR "
                  "(:selection_mode='VENDOR_SET' AND dimension='metrics' AND vendor_code IN "
                  "(SELECT arrayJoin(requested_vendors) FROM vendor_selection))))")
    start = q.index(' tagged AS')
    q = q[:start]+""" tagged AS (SELECT *,arrayJoin(if(is_control,['COUNTRY_CONTROL'],arrayConcat(
 if(chosen AND dimension='metrics',['VENDOR_METRICS'],[]),
 if(:selection_mode='VENDOR_SET' AND dimension='metrics',['VENDOR_COVERAGE'],[]),
 if(chosen AND NOT is_bucket AND dimension IN ('code','source','us'),['VENDOR_CHECK'],[]),
 if(chosen AND is_bucket AND display_rank<=6,[multiIf(dimension='code','ETA_CODE_SUMMARY',dimension='source','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')],[])))) AS tag
 FROM enriched)
 """
    return q


def sql(doc):
    # The maintained report projection remains the source of metric formulas.
    temporary = copy.deepcopy(doc)
    add_report(temporary)
    legacy = temporary[':execution'][':bindings']['pastdue_v3_report_query_rows-sql'][':binding/implementation'][':query']
    projection = legacy.split(', report_rows AS (', 1)[1].split(') SELECT * EXCEPT', 1)[0]
    projection = projection.replace(scope_key(['country-code','target-snapshot-date','previous-snapshot-date',
                                               'calendar-mode','selection-mode','top-n','vendor-code']),
                                    scope_key(LOGICAL_INPUTS))
    # Coverage carries identity/status only; its inherited numeric placeholders
    # are never measurements. The record-type contract governs interpretation.
    projection = projection.replace('t.scope_status AS scope_status',
        "if(t.tag='VENDOR_COVERAGE',if(t.has_readable_rows,'MATCHED','NO_DATA_IN_PAIR'),t.scope_status) AS scope_status")
    fingerprint = ','.join(n.replace('-', '_') for n, _ in REPORT_FIELDS
                           if n not in ('queried-at-local','row-ordinal','expected-record-count'))
    return ctes()+', report_rows AS ('+projection+"""),
 numbered AS (SELECT * EXCEPT(expected_record_count,row_ordinal),
 toInt64(count() OVER ()) AS expected_record_count,
 toInt64(row_number() OVER (ORDER BY record_type!='COUNTRY_CONTROL',contribution_rank,vendor_code,record_type,dimension,display_rank,eta_value))-1 AS row_ordinal,
 toString(groupBitXor(cityHash64(toJSONString(tuple("""+fingerprint+""")))) OVER ()) AS result_fingerprint
 FROM report_rows),
 paged AS (SELECT * FROM numbered WHERE record_type='COUNTRY_CONTROL'
 OR (row_ordinal>:page_after_ordinal AND row_ordinal<=:page_after_ordinal+:page_size))
 SELECT *,toInt64(length((SELECT requested_vendors FROM vendor_selection))) AS requested_vendor_count,
 toInt64(:page_after_ordinal) AS page_after_ordinal,toInt64(:page_size) AS page_size,
 toInt64(count() OVER ()) AS page_record_count,
 if(expected_record_count-1>:page_after_ordinal+:page_size,toInt64(:page_after_ordinal+:page_size),toInt64(0)) AS next_after_ordinal,
 CAST(expected_record_count-1>:page_after_ordinal+:page_size AS Bool) AS has_more
 FROM paged ORDER BY row_ordinal"""


def add_report_v2(doc):
    query = sql(doc)
    add_entity(doc, PARENT, INPUTS, [n for n, _ in INPUTS])
    add_entity(doc, CHILD, FIELDS, ['scope-key','record-type','vendor-code','dimension','row-kind','eta-value'])
    add_collection(doc, PARENT, 'rows', CHILD, FIELDS, [n for n, _ in INPUTS], query)
    doc[':execution'][':resolvers'][RID][K(':description')] = (
        'Trigger-independent paged report. TOP_N includes all signs; VENDOR_SET includes every '
        'requested readable Vendor and explicit coverage for missing identities. National denominators '
        'and ranks precede selection. Two bounded historical reads; source pages may drift.')
    doc[':vocabulary'][':attributes'][K(':'+PARENT+'/vendor-codes-json')][K(':attribute/description')] = (
        'VENDOR_SET: nonempty JSON array of positive Int64 numbers, deduplicated. '
        'TOP_N/POSITIVE_TOP_N: []. Empty VENDOR_SET is invalid, never all.')
    doc[':vocabulary'][':attributes'][K(':'+PARENT+'/selection-mode')][K(':attribute/description')] = (
        'TOP_N: contribution rank across all signs; POSITIVE_TOP_N: positive contributors; '
        'VENDOR_SET: every requested readable identity, top-n=0, independent of contribution sign.')
    doc[':vocabulary'][':attributes'][K(':'+CHILD+'/record-type')][K(':attribute/description')] = (
        'Existing report record types plus VENDOR_COVERAGE identity/status only. '
        'NO_DATA_IN_PAIR means no readable rows in either date, not a measured zero. '
        'COUNTRY_CONTROL ordinal0 repeats on every page; remaining ordinals are global.')
    doc[':publication'][K(':release/version')] = '3.6.0'
    doc[':publication'][K(':source/revision')] = 7
    return doc

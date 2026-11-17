"""Source-side selection and expansion using only the existing scalar EDN pattern.

No native defaults, saved-query schema, knowledge activation or snapshot guarantee
is assumed. Older resolver definitions and bindings are left intact.
"""
from build_analysis_contract import add_collection, add_entity, NOW_PST, TODAY_PST
from build_explore_v2 import LOGICAL_INPUTS as V2_LOGICAL, PAGE_FIELDS, page_sql
from build_v31_contract import (EXPLORE_FIELDS, explore_source_ctes,
                               explore_metric_ctes, explore_selection_ctes,
                               scope_key, sparse)
from edn_io import K


LOGICAL_INPUTS = V2_LOGICAL + [
    ('selection-unit', 'string'), ('rank-partition', 'string'),
    ('expansion-eta-code-filter', 'string'), ('expansion-source-filter', 'string')]
INPUTS = LOGICAL_INPUTS + [('page-after-ordinal', 'integer'), ('page-size', 'integer')]
EXTRA_FIELDS = [('selection-unit', 'string'), ('rank-partition', 'string'),
                ('selection-eta-code-filter', 'string'), ('selection-source-filter', 'string'),
                ('expansion-eta-code-filter', 'string'), ('expansion-source-filter', 'string')]
LOGICAL_FIELDS = EXPLORE_FIELDS + EXTRA_FIELDS
FIELDS = LOGICAL_FIELDS + PAGE_FIELDS + [('selected-object-count', 'integer'), ('fact-record-count', 'integer')]
RID = 'pastdue_v3_explore_v3_query_rows'


def core(grain=':grain'):
    return {'scope-key': scope_key([n for n, _ in LOGICAL_INPUTS]),
            'country-code': ':country_code', 'time-basis': ':time_basis',
            'calendar-mode': ':calendar_mode', 'grain': grain,
            'denominator-scope': ':denominator_scope', 'metric-version': "'3.1.0'",
            'organization-basis': "if(empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter),'NONE','CURRENT_MAPPING')",
            'business-timezone': "'America/Los_Angeles'", 'queried-at-local': NOW_PST,
            'selection-unit': ':selection_unit', 'rank-partition': ':rank_partition',
            'selection-eta-code-filter': ':eta_code_filter',
            'selection-source-filter': ':source_filter',
            'expansion-eta-code-filter': ':expansion_eta_code_filter',
            'expansion-source-filter': ':expansion_source_filter'}


def measured(record_type, source, grain=':grain'):
    values = core(grain)
    values.update({n: n.replace('-', '_') for n, _ in EXPLORE_FIELDS
                   if n not in values and n not in ('record-type', 'scope-status',
                                                    'expected-record-count', 'row-ordinal')})
    values.update({'record-type': "'"+record_type+"'", 'scope-status': "'OK'"})
    return sparse(LOGICAL_FIELDS, values)+' FROM '+source+' WHERE (SELECT count() FROM raw WHERE pd<0 OR op<0 OR pd>op)=0'


def coverage():
    # RANGE coverage is by actual target date, not by the union of Vendors across
    # dates. PAIR source/population existence includes either member of the pair.
    keys = "(country_code,target_snapshot_date,vendor_code)"
    available = "(country_code,if(:time_basis='PAIR',toDate(:target_snapshot_date),snapshot_date),vendor_code)"
    values = core()
    values.update({'record-type': "'VENDOR_COVERAGE'", 'vendor-code': 'requested_vendor',
                   'target-snapshot-date': 'coverage_date',
                   'previous-snapshot-date': "if(:time_basis='PAIR',(SELECT previous_date FROM pair),toDate('1970-01-01'))",
                   'scope-status': "multiIf("+
                   "(:country_code,coverage_date,requested_vendor) NOT IN (SELECT "+available+" FROM raw),'NO_DATA_IN_PERIOD',"+
                   "(:country_code,coverage_date,requested_vendor) NOT IN (SELECT "+available+" FROM population),'EXCLUDED_BY_ORG',"+
                   "(:country_code,coverage_date,requested_vendor) NOT IN (SELECT "+keys+" FROM selection_metrics),'NO_MATCHING_SELECTION_BUCKETS',"+
                   "(:country_code,coverage_date,requested_vendor) NOT IN (SELECT "+keys+" FROM selected_rows),'EXCLUDED_BY_SELECTION',"+
                   "(:country_code,coverage_date,requested_vendor) NOT IN (SELECT "+keys+" FROM metrics),'NO_MATCHING_EXPANSION_BUCKETS','MATCHED')"})
    # COUNTRY grouped rows intentionally have Vendor=0; explicit Vendor coverage
    # at that grain is not inferred from these aggregate identities.
    return (sparse(LOGICAL_FIELDS, values)+
            " FROM (SELECT arrayJoin(requested_vendors) AS requested_vendor FROM vendor_selection) AS requested"
            " CROSS JOIN coverage_dates WHERE (SELECT scope_status FROM scope)='OK'"
            " AND :grain!='COUNTRY' AND (SELECT count() FROM raw WHERE pd<0 OR op<0 OR pd>op)=0")


def sql():
    from build_explore_v2 import vendor_selection_cte
    errors = """(SELECT vendor_selection_valid FROM vendor_selection)=0,'INVALID_VENDOR_SELECTION',
 :page_after_ordinal<0 OR :page_size<1 OR :page_size>500,'INVALID_PAGE',
 :selection_unit NOT IN ('VENDOR','GROUPED_ROW') OR :rank_partition NOT IN ('GLOBAL','TARGET_DATE'),'INVALID_SELECTION',
 (:selection_unit='VENDOR' AND :grain='COUNTRY') OR
 (:selection_unit='GROUPED_ROW' AND (notEmpty(:expansion_eta_code_filter) OR notEmpty(:expansion_source_filter))),'UNSUPPORTED_COMBINATION',"""
    base = explore_source_ctes(selection_ctes=vendor_selection_cte(), selection_error=errors,
        vendor_predicate="(empty((SELECT requested_vendors FROM vendor_selection)) OR vendor_code IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection))")
    base += """,
 expansion_matched AS (SELECT *,if(empty(raw_code),'UNKNOWN',raw_code) AS code,
 if(empty(raw_source),'UNKNOWN',raw_source) AS source FROM population
 WHERE (empty(:expansion_eta_code_filter) OR if(empty(raw_code),'UNKNOWN',raw_code)=upper(trim(:expansion_eta_code_filter)))
 AND (empty(:expansion_source_filter) OR if(empty(raw_source),'UNKNOWN',raw_source)=trim(:expansion_source_filter))),
 display_matched AS (SELECT * FROM matched WHERE :selection_unit='GROUPED_ROW'
 UNION ALL SELECT * FROM expansion_matched WHERE :selection_unit='VENDOR'),
 """+explore_metric_ctes(matched='display_matched')+',\n '+explore_metric_ctes("'VENDOR'", 'vendor_')+""",
 selection_metrics AS (SELECT * FROM vendor_metrics WHERE :selection_unit='VENDOR'
 UNION ALL SELECT * FROM metrics WHERE :selection_unit='GROUPED_ROW'),
 """+explore_selection_ctes(
        partition="PARTITION BY if(:rank_partition='TARGET_DATE',target_snapshot_date,toDate('1970-01-01')) ",
        source='selection_metrics')+""",
 expanded_facts AS (SELECT m.*,s.selection_rank AS selection_rank
 FROM metrics m INNER JOIN selected_rows s ON m.country_code=s.country_code
 AND m.target_snapshot_date=s.target_snapshot_date AND m.previous_snapshot_date=s.previous_snapshot_date
 AND m.vendor_code=s.vendor_code
 AND (:selection_unit='VENDOR' OR (m.eta_code=s.eta_code AND m.eta_source=s.eta_source))),
 coverage_dates AS (SELECT """+TODAY_PST+""" AS coverage_date WHERE :time_basis='CURRENT'
 UNION ALL SELECT toDate(:target_snapshot_date) AS coverage_date WHERE :time_basis IN ('SNAPSHOT','PAIR')
 UNION ALL SELECT snapshot_date AS coverage_date FROM dates
 WHERE :time_basis='RANGE' AND snapshot_date BETWEEN toDate(:range_start_date) AND toDate(:range_end_date)),
 output_rows AS ("""
    control = core()
    control.update({'record-type': "'CONTROL'", 'scope-status': """multiIf(scope_status!='OK',scope_status,
 (SELECT count() FROM raw)=0,'NO_DATA',
 (SELECT count() FROM raw WHERE pd<0 OR op<0 OR pd>op)>0,'DATA_ERROR',
 (SELECT count() FROM selected_rows)=0,'EMPTY_SELECTION','OK')"""})
    output = sparse(LOGICAL_FIELDS, control)+' FROM scope'
    output += ' UNION ALL '+measured('SELECTION', 'selected_rows', "if(:selection_unit='VENDOR','VENDOR',:grain)")
    output += ' UNION ALL '+measured('FACT', 'expanded_facts')
    output += ' UNION ALL '+coverage()
    return page_sql(base+output+')', LOGICAL_FIELDS, selection_counts=True)


def add_explore_v3(doc):
    ns, child = 'pastdue.explore-v3-query', 'pastdue.explore-v3-fact'
    add_entity(doc, ns, INPUTS, [n for n, _ in INPUTS])
    add_entity(doc, child, FIELDS, ['scope-key', 'record-type', 'country-code',
        'target-snapshot-date', 'previous-snapshot-date', 'vendor-code', 'eta-code', 'eta-source'])
    add_collection(doc, ns, 'rows', child, FIELDS, [n for n, _ in INPUTS], sql())
    doc[':execution'][':resolvers'][RID][K(':pagination')] = {K(':default'):1000, K(':max'):1000}
    description = ('通用选择与展开 / source-side select and expand: rank Vendors then expand ETA, '
        'or retain grouped-row selection; GLOBAL or per TARGET_DATE. Candidate denominators remain '
        'fixed before Top N; empty selection never means all. Metric3.1.0; page drift detection '
        'does not establish a transaction. Scalar inputs follow existing EDN patterns; live validation pending.')
    doc[':execution'][':resolvers'][RID][K(':description')] = description
    doc[':vocabulary'][':entities'][ns][K(':entity/description')] = description
    descriptions = {
        'selection-unit': 'VENDOR ranks Vendor metrics then expands at grain; GROUPED_ROW ranks displayed date/grain rows. VENDOR+COUNTRY is unsupported.',
        'rank-partition': 'GLOBAL ranks all target/date observations together; TARGET_DATE independently ranks each actual target date. Stable ties use date, Vendor, Code, Source.',
        'eta-code-filter': 'Selection Code filter; empty=all. In VENDOR mode affects ranking only; expansion filters separately control display. Code is trimmed uppercase.',
        'source-filter': 'Selection Source filter; empty=all, trimmed exact case-preserving match. VENDOR display uses expansion filters independently.',
        'expansion-eta-code-filter': 'VENDOR display Code filter, empty=all ETA including when ranking used a Code filter. Must be empty for GROUPED_ROW.',
        'expansion-source-filter': 'VENDOR display Source filter, empty=all. Must be empty for GROUPED_ROW.',
        'denominator-scope': 'Metric3.1.0 SHARE denominator: COUNTRY_ALL whole Country PD; FILTERED_POPULATION candidate Vendor+org PD before ETA/ranking/Top N; WITHIN_VENDOR whole Vendor PD. Does not change PD/Open ratio denominator.',
        'vendor-codes-json': 'Positive Int64 numeric JSON array, []=all candidates. Internal empty selection is distinct from all; never map an empty ranked result back to [].',
        'page-after-ordinal': '0 first page, then actual next-after-ordinal; continuation re-executes SQL, not an immutable cursor.',
        'page-size': '1..500 non-control rows, plus repeated CONTROL; verify expected counts, actual rows and cells.',
    }
    for field, description in descriptions.items():
        doc[':vocabulary'][':attributes'][K(':'+ns+'/'+field)][K(':attribute/description')] = description
    for field, description in {
        'record-type': 'CONTROL validates; SELECTION carries ranking-scope metrics; FACT carries expansion-scope metrics; VENDOR_COVERAGE only carries Vendor/date identity and status. Sparse measurements are not zeros.',
        'scope-status': 'CONTROL: OK, EMPTY_SELECTION, NO_DATA or explicit invalid/data errors. Coverage: MATCHED, NO_DATA_IN_PERIOD, EXCLUDED_BY_ORG, NO_MATCHING_SELECTION_BUCKETS, EXCLUDED_BY_SELECTION, NO_MATCHING_EXPANSION_BUCKETS. COUNTRY grain has no explicit coverage rows.',
        'selection-rank': 'Rank within rank-partition; inherited by expansion FACT rows. Count SELECTION rows, not repeated ranks on FACT buckets.',
        'selected-object-count': 'Number of selected date/grain identities before expansion; may exceed FACT Vendor count when display filters match nothing. CONTROL zero on invalid/data-error scope is a placeholder.',
        'fact-record-count': 'Complete expansion FACT row count before paging, not a source-line count.',
        'grain': 'Actual measurement grain: SELECTION=VENDOR for Vendor selection, FACT=requested grain; coverage/control grain is contextual, not a measurement.',
        'result-fingerprint': 'Order-independent noncryptographic checksum; compare alongside scope/counts/ordinals. Does not prove atomic capture or source revision.',
    }.items():
        doc[':vocabulary'][':attributes'][K(':'+child+'/'+field)][K(':attribute/description')] = description
    guide = doc[':graph'][':graph/usage-guide']
    guide[K(':guide/limitations')].append(
        'Explore-v3 computes selection and expansion in one SQL request, not a transaction: '
        'source/CTE reads and continuation pages may drift. Current mapping is not historical responsibility; '
        'current Open is view-row count. No historical personal causality or native knowledge publication is claimed.')
    doc[':publication'][K(':release/version')] = '3.5.0'
    doc[':publication'][K(':source/revision')] = 6

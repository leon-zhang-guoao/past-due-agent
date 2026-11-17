"""Additive, development-only composable explore contract; shares v3.1 metrics.

JSON is a declared wire encoding because the shipped Atlas graph uses scalar
inputs. It is not SQL, and never changes the meaning of old single-value inputs.
"""
from build_analysis_contract import add_collection, add_entity, NOW_PST
from build_v31_contract import EXPLORE_FIELDS, EXPLORE_INPUTS, explore_ctes, scope_key, sparse
from edn_io import K


LOGICAL_INPUTS = [(('vendor-codes-json', 'string') if name == 'vendor-code' else (name, typ))
                  for name, typ in EXPLORE_INPUTS]
INPUTS = LOGICAL_INPUTS + [('page-after-ordinal', 'integer'), ('page-size', 'integer')]
PAGE_FIELDS = [('requested-vendor-count', 'integer'), ('returned-vendor-count', 'integer'),
               ('page-after-ordinal', 'integer'), ('page-size', 'integer'),
               ('page-record-count', 'integer'), ('next-after-ordinal', 'integer'),
               ('has-more', 'boolean'), ('result-fingerprint', 'string')]
FIELDS = EXPLORE_FIELDS + PAGE_FIELDS


def coverage_output():
    """Account for each explicit Vendor without turning missing rows into zeros."""
    core = {'scope-key': scope_key([n for n, _ in LOGICAL_INPUTS]),
            'record-type': "'VENDOR_COVERAGE'", 'country-code': ':country_code',
            'vendor-code': 'requested_vendor', 'time-basis': ':time_basis',
            'calendar-mode': ':calendar_mode', 'grain': ':grain',
            'denominator-scope': ':denominator_scope', 'metric-version': "'3.1.0'",
            'organization-basis': "if(empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter),'NONE','CURRENT_MAPPING')",
            'business-timezone': "'America/Los_Angeles'", 'queried-at-local': NOW_PST,
            'scope-status': """multiIf(
 requested_vendor NOT IN (SELECT vendor_code FROM raw),'NO_DATA_IN_PERIOD',
 requested_vendor NOT IN (SELECT vendor_code FROM population),'EXCLUDED_BY_ORG',
 requested_vendor NOT IN (SELECT vendor_code FROM matched),'NO_MATCHING_BUCKETS',
 :grain!='COUNTRY' AND requested_vendor NOT IN (SELECT vendor_code FROM selected_rows),'EXCLUDED_BY_SELECTION',
 'MATCHED')"""}
    return (' UNION ALL ' + sparse(EXPLORE_FIELDS, core) +
            " FROM (SELECT arrayJoin(requested_vendors) AS requested_vendor FROM vendor_selection)"
            " WHERE (SELECT scope_status FROM scope)='OK'"
            " AND (SELECT count() FROM raw WHERE pd<0 OR op<0 OR pd>op)=0")


def vendor_selection_cte():
    return """vendor_selection AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON(:vendor_codes_json),:vendor_codes_json,'[]')) AS vendor_tokens,
 isValidJSON(:vendor_codes_json)
 AND toString(JSONType(if(isValidJSON(:vendor_codes_json),:vendor_codes_json,'[]')))='Array'
 AND arrayAll(x -> match(x,'^[1-9][0-9]{0,18}$')
 AND (length(x)<19 OR x<='9223372036854775807'),vendor_tokens) AS vendor_selection_valid,
 arraySort(arrayDistinct(arrayMap(x -> toInt64OrZero(x),vendor_tokens))) AS requested_vendors),"""


def sql():
    selection = vendor_selection_cte()
    error = """(SELECT vendor_selection_valid FROM vendor_selection)=0,'INVALID_VENDOR_SELECTION',
 :page_after_ordinal<0 OR :page_size<1 OR :page_size>500,'INVALID_PAGE',"""
    base = explore_ctes(selection_ctes=selection, selection_error=error,
                       vendor_predicate="(empty((SELECT requested_vendors FROM vendor_selection)) OR vendor_code IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection))",
                       inputs=LOGICAL_INPUTS, extra_output=coverage_output())
    return page_sql(base)


def page_sql(base, fields=EXPLORE_FIELDS, selection_counts=False):
    """Bound transport after the complete logical result has been constructed."""
    fingerprint = ','.join(n.replace('-', '_') for n, _ in fields
                           if n not in ('queried-at-local', 'expected-record-count', 'row-ordinal'))
    count_meta = ",\n toInt64(countIf(record_type='SELECTION')) AS selected_object_count,\n toInt64(countIf(record_type='FACT')) AS fact_record_count" if selection_counts else ''
    count_output = ',m.selected_object_count AS selected_object_count,m.fact_record_count AS fact_record_count' if selection_counts else ''
    return base + """,
 numbered AS (SELECT * EXCEPT(expected_record_count,row_ordinal),
 if(record_type='CONTROL',toInt64(0),toInt64(row_number() OVER
 (ORDER BY record_type,selection_rank,target_snapshot_date,vendor_code,eta_code,eta_source))-1) AS row_ordinal
 FROM output_rows),
 page_meta AS (SELECT toInt64(count()) AS expected_record_count,
 toInt64(countIf(record_type!='CONTROL' AND row_ordinal>:page_after_ordinal)) AS remaining_rows,
 toInt64(least(greatest(:page_size,1),500)) AS effective_page_size,
 toString(groupBitXor(cityHash64(toJSONString(tuple("""+fingerprint+"""))))) AS result_fingerprint,
 toInt64(uniqExactIf(vendor_code,record_type='FACT' AND vendor_code!=0)) AS returned_vendor_count"""+count_meta+"""
 FROM numbered)
 SELECT n.*,m.expected_record_count AS expected_record_count,
 toInt64(length((SELECT requested_vendors FROM vendor_selection))) AS requested_vendor_count,
 m.returned_vendor_count AS returned_vendor_count,
 toInt64(:page_after_ordinal) AS page_after_ordinal,toInt64(:page_size) AS page_size,
 toInt64(1+least(m.remaining_rows,m.effective_page_size)) AS page_record_count,
 if(m.remaining_rows>m.effective_page_size,toInt64(:page_after_ordinal+m.effective_page_size),toInt64(0)) AS next_after_ordinal,
 CAST(m.remaining_rows>m.effective_page_size AS Bool) AS has_more,
 m.result_fingerprint AS result_fingerprint"""+count_output+"""
 FROM numbered n CROSS JOIN page_meta m
 WHERE n.record_type='CONTROL' OR
 (n.row_ordinal>:page_after_ordinal AND n.row_ordinal<=:page_after_ordinal+m.effective_page_size)
 """


def add_explore_v2(doc):
    ns, child = 'pastdue.explore-v2-query', 'pastdue.explore-v2-fact'
    add_entity(doc, ns, INPUTS, [n for n, _ in INPUTS])
    add_entity(doc, child, FIELDS,
               ['scope-key', 'record-type', 'country-code', 'vendor-code',
                'target-snapshot-date', 'previous-snapshot-date', 'eta-code', 'eta-source'])
    add_collection(doc, ns, 'rows', child, FIELDS, [n for n, _ in INPUTS], sql())
    rid = 'pastdue_v3_explore_v2_query_rows'
    doc[':execution'][':resolvers'][rid][K(':pagination')] = {K(':default'):1000, K(':max'):1000}
    doc[':execution'][':resolvers'][rid][K(':description')] = (
        'Composable explore v2: shared metric3.1.0, positive Int64 Vendor JSON set '
        '([]=all), explicit coverage and SQL ordinal paging (1..500 data rows + control). '
        'A page is bounded before the transport limit; verify page counts and fingerprint.')
    descriptions = {
        'vendor-codes-json': 'JSON array of positive Int64 numbers; [] means all. Single is [64342]. Strings, fractions, booleans and overflow are INVALID_VENDOR_SELECTION. Duplicates are deduplicated; preserve the original logical input across pages.',
        'page-after-ordinal': '0 for first page; otherwise copy next-after-ordinal. Not a result handle. Logical scope excludes page inputs.',
        'page-size': '1..500 non-control rows per page; one CONTROL is repeated. Transport default1000 must be honored; verify page-record-count.',
    }
    for field, description in descriptions.items():
        doc[':vocabulary'][':attributes'][K(':'+ns+'/'+field)][K(':attribute/description')] = description
    output_descriptions = {
        'record-type': 'CONTROL is repeated ordinal0; FACT carries metric3.1.0 measurements; VENDOR_COVERAGE carries only explicit Vendor identity and coverage status, never measurements.',
        'scope-status': 'CONTROL overall validation; FACT OK; VENDOR_COVERAGE MATCHED/NO_DATA_IN_PERIOD/EXCLUDED_BY_ORG/NO_MATCHING_BUCKETS/EXCLUDED_BY_SELECTION. Coverage numeric defaults are not observations.',
        'expected-record-count': 'Complete logical result count before SQL paging, including CONTROL once and all explicit Vendor coverage rows.',
        'page-record-count': 'Expected THIS page count including repeated CONTROL; compare actual source and retained rows before advancing.',
        'result-fingerprint': 'Noncryptographic logical-result checksum excluding capture time/ordinals, detects ordinary page drift; NOT snapshot isolation, a watermark or a collision-proof guarantee.',
        'requested-vendor-count': 'Distinct explicit Vendor IDs; zero means all-Vendor selection, not zero business Vendors.',
        'returned-vendor-count': 'Distinct Vendor FACT IDs after selection; zero at COUNTRY grain. Not source existence or coverage count.',
    }
    for field, description in output_descriptions.items():
        doc[':vocabulary'][':attributes'][K(':'+child+'/'+field)][K(':attribute/description')] = description
    doc[':publication'][K(':release/version')] = '3.4.0'
    doc[':publication'][K(':source/revision')] = 5

"""One detail scan for explicit Vendor-set CURRENT measures and joint buckets."""
from build_analysis_contract import add_entity, add_collection, ratio
from build_explore_v2 import vendor_selection_cte
from build_v31_contract import scope_key
from edn_io import K

PARENT = 'pastdue.current-v2-query'
CHILD = 'pastdue.current-v2-fact'
RID = 'pastdue_v3_current_v2_query_rows'
INPUTS = [('country-code','string'),('vendor-codes-json','string'),
          ('department-type-filter','string'),('master-primary-filter','string'),
          ('master-manager-filter','string'),('denominator-scope','string'),
          ('page-after-ordinal','integer'),('page-size','integer')]
LOGICAL_INPUTS = [n for n,_ in INPUTS[:-2]]
FIELDS = [('scope-key','string'),('record-type','string'),('scope-status','string'),
          ('country-code','string'),('vendor-code','integer'),('vendor-name','string'),
          ('eta-code','string'),('eta-source','string'),('open-line-count','integer'),
          ('past-due-count','integer'),('vendor-open-count','integer'),
          ('vendor-past-due-count','integer'),('ratio-denominator','integer'),
          ('share-denominator','integer'),('past-due-ratio','decimal'),
          ('past-due-share','decimal'),('vendor-past-due-ratio','decimal'),
          ('vendor-past-due-share','decimal'),('denominator-scope','string'),
          ('organization-basis','string'),('metric-version','string'),
          ('time-basis','string'),('business-timezone','string'),('queried-at-local','datetime'),
          ('requested-vendor-count','integer'),('expected-record-count','integer'),
          ('expected-bucket-count','integer'),('row-ordinal','integer'),
          ('page-after-ordinal','integer'),('page-size','integer'),
          ('page-record-count','integer'),('next-after-ordinal','integer'),
          ('has-more','boolean'),('result-fingerprint','string')]


def sql():
    return 'WITH '+vendor_selection_cte()+"""
 input_control AS (SELECT multiIf(empty(trim(:country_code)),'INVALID_COUNTRY',
 NOT vendor_selection_valid OR empty(requested_vendors),'INVALID_VENDOR_SELECTION',
 :denominator_scope NOT IN ('COUNTRY_ALL','FILTERED_POPULATION','WITHIN_VENDOR'),'INVALID_DENOMINATOR',
 :page_after_ordinal<0 OR :page_size NOT BETWEEN 1 AND 199,'INVALID_PAGE','OK') AS input_status
 FROM vendor_selection),
 mapped AS (SELECT DISTINCT country_code,toInt64(vendor_no) AS vendor_code
 FROM past_due.vendor_master_mapping_view WHERE country_code=:country_code
 AND (notEmpty(:department_type_filter) OR notEmpty(:master_primary_filter) OR notEmpty(:master_manager_filter))
 AND (empty(:department_type_filter) OR upper(trim(department_type))=upper(trim(:department_type_filter)))
 AND (empty(:master_primary_filter) OR trim(ifNull(master_primary,''))=trim(:master_primary_filter))
 AND (empty(:master_manager_filter) OR trim(ifNull(master_manager,''))=trim(:master_manager_filter))),
 raw AS (SELECT toInt64(from_acct_no) IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection) AS requested,
 if(requested,toInt64(from_acct_no),toInt64(-1)) AS vendor_code,
 if(requested,vendor_name,'') AS vendor_name,
 if(requested,if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))),'') AS eta_code,
 if(requested,if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)),'') AS eta_source,
 toInt64(1) AS op,toInt64(toInt64(is_pastdue)=1) AS pd,false AS is_seed,
 ((empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter))
 OR (country_code,toInt64(from_acct_no)) IN (SELECT country_code,vendor_code FROM mapped)) AS org_match
 FROM past_due.past_due_detail_view
 WHERE country_code=:country_code AND from_acct_no IS NOT NULL
 AND (SELECT input_status FROM input_control)='OK'
 AND (:denominator_scope='COUNTRY_ALL' OR toInt64(from_acct_no) IN
 (SELECT arrayJoin(requested_vendors) FROM vendor_selection))
 UNION ALL
 SELECT true,arrayJoin(if((SELECT input_status FROM input_control)='OK',
 arrayConcat((SELECT requested_vendors FROM vendor_selection),[toInt64(0)]),[toInt64(0)])),
 '','','',toInt64(0),toInt64(0),true,false),
 buckets AS (SELECT vendor_code,min(vendor_name) AS vendor_name,eta_code,eta_source,is_seed,
 sum(r.op) AS readable_op,sum(r.pd) AS country_pd,sumIf(r.op,r.org_match) AS op,sumIf(r.pd,r.org_match) AS pd
 FROM raw r GROUP BY vendor_code,eta_code,eta_source,is_seed),
 marked AS (SELECT *,vendor_code IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection) AS selected
 FROM buckets),
 measured AS (SELECT *,minIf(vendor_name,NOT is_seed) OVER v AS actual_vendor_name,
 sum(readable_op) OVER v AS vendor_readable_op,
 sum(op) OVER v AS vendor_op,sum(pd) OVER v AS vendor_pd,
 sum(country_pd) OVER () AS country_total_pd,sumIf(pd,selected) OVER () AS filtered_total_pd
 FROM marked WINDOW v AS (PARTITION BY vendor_code)),
 typed AS (SELECT
 multiIf(vendor_code=0,'CONTROL',is_seed,'VENDOR_COVERAGE','FACT') AS record_type,
 multiIf((SELECT input_status FROM input_control)!='OK',(SELECT input_status FROM input_control),
 vendor_code=0,'OK',is_seed AND vendor_readable_op=0,'NO_READABLE_ROWS',
 is_seed AND vendor_op=0,'EXCLUDED_BY_ORG','MATCHED') AS scope_status,
 :country_code AS country_code,vendor_code,actual_vendor_name AS vendor_name,eta_code,eta_source,
 toInt64(op) AS open_line_count,toInt64(pd) AS past_due_count,
 if(is_seed,toInt64(0),toInt64(vendor_op)) AS vendor_open_count,
 if(is_seed,toInt64(0),toInt64(vendor_pd)) AS vendor_past_due_count,
 if(is_seed,toInt64(0),toInt64(vendor_op)) AS ratio_denominator,
 if(is_seed,toInt64(0),toInt64(multiIf(:denominator_scope='COUNTRY_ALL',country_total_pd,
 :denominator_scope='FILTERED_POPULATION',filtered_total_pd,vendor_pd))) AS share_denominator
 FROM measured WHERE vendor_code=0 OR (selected AND (is_seed OR op>0))),
 numbered AS (SELECT *,
 """+scope_key(LOGICAL_INPUTS)+""" AS scope_key,
 """+ratio('past_due_count','ratio_denominator')+""" AS past_due_ratio,
 """+ratio('past_due_count','share_denominator')+""" AS past_due_share,
 """+ratio('vendor_past_due_count','ratio_denominator')+""" AS vendor_past_due_ratio,
 """+ratio('vendor_past_due_count','share_denominator')+""" AS vendor_past_due_share,
 toInt64(row_number() OVER (ORDER BY vendor_code,record_type,eta_code,eta_source))-1 AS row_ordinal,
 toInt64(count() OVER ()) AS expected_record_count,
 toInt64(countIf(record_type='FACT') OVER ()) AS expected_bucket_count,
 toString(groupBitXor(cityHash64(toJSONString(tuple(record_type,scope_status,country_code,
 vendor_code,vendor_name,eta_code,eta_source,open_line_count,past_due_count,
 vendor_open_count,vendor_past_due_count,ratio_denominator,share_denominator)))) OVER ()) AS result_fingerprint
 FROM typed),
 paged AS (SELECT * FROM numbered WHERE record_type='CONTROL' OR
 (row_ordinal>:page_after_ordinal AND row_ordinal<=:page_after_ordinal+:page_size))
 SELECT *, :denominator_scope AS denominator_scope,
 if(empty(:department_type_filter) AND empty(:master_primary_filter) AND empty(:master_manager_filter),'NONE','CURRENT_MAPPING') AS organization_basis,
 '3.1.0' AS metric_version,'CURRENT' AS time_basis,'America/Los_Angeles' AS business_timezone,
 now('America/Los_Angeles') AS queried_at_local,
 toInt64(length((SELECT requested_vendors FROM vendor_selection))) AS requested_vendor_count,
 toInt64(:page_after_ordinal) AS page_after_ordinal,toInt64(:page_size) AS page_size,
 toInt64(count() OVER ()) AS page_record_count,
 if(expected_record_count-1>:page_after_ordinal+:page_size,toInt64(:page_after_ordinal+:page_size),toInt64(0)) AS next_after_ordinal,
 CAST(expected_record_count-1>:page_after_ordinal+:page_size AS Bool) AS has_more
 FROM paged ORDER BY row_ordinal"""


def add_current_v2(doc):
    add_entity(doc,PARENT,INPUTS,[n for n,_ in INPUTS])
    add_entity(doc,CHILD,FIELDS,['scope-key','record-type','vendor-code','eta-code','eta-source'])
    add_collection(doc,PARENT,'rows',CHILD,FIELDS,[n for n,_ in INPUTS],sql())
    doc[':execution'][':resolvers'][RID][K(':description')] = (
        'CURRENT Vendor-set metrics and joint Code/Source facts in one detail scan. '
        'COUNTRY_ALL reads national PD before filters; other shares scan requested Vendors only. '
        'Optional org scope, explicit coverage, source pages and drift fingerprint. '
        'No ranking, comparison, ETA selection filters or historical facts; use explore for those.')
    for field,description in {
        'vendor-codes-json':'Nonempty JSON numeric positive Int64 Vendor set, deduplicated; [] invalid, never all.',
        'denominator-scope':'COUNTRY_ALL whole Country PD before filters; FILTERED_POPULATION requested Vendor+org PD before ETA; WITHIN_VENDOR scoped Vendor PD. Ratio always bucket PD / scoped Vendor Open.',
    }.items():
        doc[':vocabulary'][':attributes'][K(':'+PARENT+'/'+field)][K(':attribute/description')] = description
    doc[':vocabulary'][':attributes'][K(':'+CHILD+'/record-type')][K(':attribute/description')] = (
        'CONTROL ordinal0 repeated per page; FACT joint Code/Source quantities; '
        'VENDOR_COVERAGE identity/status only. Numeric placeholders are not measurements.')
    doc[':vocabulary'][':attributes'][K(':'+CHILD+'/scope-status')][K(':attribute/description')] = (
        'CONTROL OK or INVALID_*; coverage MATCHED, NO_READABLE_ROWS (not measured zero), '
        'EXCLUDED_BY_ORG. FACT measurements require OK control and MATCHED coverage.')
    return doc

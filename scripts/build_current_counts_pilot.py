#!/usr/bin/env python3
"""Build an additive CURRENT count pilot; leave the release graph untouched."""
import argparse
from pathlib import Path
from build_analysis_contract import add_entity, add_collection
from build_explore_v2 import vendor_selection_cte
from edn_io import read, write, K

ROOT = Path(__file__).resolve().parents[1]
PARENT = 'pastdue.current-counts-query'
CHILD = 'pastdue.current-counts-fact'
RID = 'pastdue_v3_current_counts_query_rows'
INPUTS = [('country-code','string'),('vendor-codes-json','string'),
          ('page-after-ordinal','integer'),('page-size','integer')]
FIELDS = [('record-type','string'),('scope-status','string'),('country-code','string'),
          ('vendor-code','integer'),('vendor-name','string'),('eta-code','string'),
          ('eta-source','string'),('open-line-count','integer'),('past-due-count','integer'),
          ('vendor-open-count','integer'),('vendor-past-due-count','integer'),
          ('metric-version','string'),('time-basis','string'),('business-timezone','string'),
          ('queried-at-local','datetime'),('requested-vendor-count','integer'),
          ('expected-record-count','integer'),('expected-bucket-count','integer'),
          ('row-ordinal','integer'),('page-after-ordinal','integer'),('page-size','integer'),
          ('page-record-count','integer'),('next-after-ordinal','integer'),('has-more','boolean'),
          ('result-fingerprint','string')]


def sql():
    return 'WITH '+vendor_selection_cte()+"""
 input_control AS (SELECT multiIf(empty(trim(:country_code)),'INVALID_COUNTRY',
 NOT vendor_selection_valid OR empty(requested_vendors),'INVALID_VENDOR_SELECTION',
 :page_after_ordinal<0 OR :page_size<1 OR :page_size>199,'INVALID_PAGE','OK') AS input_status
 FROM vendor_selection),
 raw AS (
 SELECT toInt64(from_acct_no) AS vendor_code,vendor_name,
 if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))) AS eta_code,
 if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)) AS eta_source,
 toInt64(1) AS op,toInt64(toInt64(is_pastdue)=1) AS pd,false AS is_seed
 FROM past_due.past_due_detail_view
 WHERE country_code=:country_code AND from_acct_no IS NOT NULL
 AND (SELECT input_status FROM input_control)='OK'
 AND toInt64(from_acct_no) IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection)
 UNION ALL
 SELECT arrayJoin(if((SELECT input_status FROM input_control)='OK',
 arrayConcat((SELECT requested_vendors FROM vendor_selection),[toInt64(0)]),[toInt64(0)])),
 '','','',toInt64(0),toInt64(0),true),
 buckets AS (SELECT vendor_code,min(vendor_name) AS vendor_name,eta_code,eta_source,is_seed,
 sum(op) AS op,sum(pd) AS pd FROM raw
 GROUP BY vendor_code,eta_code,eta_source,is_seed),
 measured AS (SELECT *,
 sum(op) OVER (PARTITION BY vendor_code) AS vendor_op,
 sum(pd) OVER (PARTITION BY vendor_code) AS vendor_pd
 FROM buckets),
 typed AS (SELECT
 multiIf(vendor_code=0,'CONTROL',is_seed,'VENDOR_COVERAGE','FACT') AS record_type,
 multiIf((SELECT input_status FROM input_control)!='OK',(SELECT input_status FROM input_control),
 vendor_code=0,'OK',is_seed AND vendor_op=0,'NO_READABLE_ROWS','MATCHED') AS scope_status,
 :country_code AS country_code,vendor_code,vendor_name,eta_code,eta_source,
 toInt64(op) AS open_line_count,toInt64(pd) AS past_due_count,
 if(is_seed,toInt64(0),toInt64(vendor_op)) AS vendor_open_count,
 if(is_seed,toInt64(0),toInt64(vendor_pd)) AS vendor_past_due_count
 FROM measured),
 numbered AS (SELECT *,
 toInt64(row_number() OVER (ORDER BY vendor_code,record_type,eta_code,eta_source))-1 AS row_ordinal,
 toInt64(count() OVER ()) AS expected_record_count,
 toInt64(countIf(record_type='FACT') OVER ()) AS expected_bucket_count,
 toString(groupBitXor(cityHash64(toJSONString(tuple(record_type,scope_status,country_code,
 vendor_code,vendor_name,eta_code,eta_source,open_line_count,past_due_count,
 vendor_open_count,vendor_past_due_count)))) OVER ()) AS result_fingerprint
 FROM typed),
 paged AS (SELECT * FROM numbered WHERE record_type='CONTROL' OR
 ((SELECT input_status FROM input_control)='OK' AND row_ordinal>:page_after_ordinal
 AND row_ordinal<=:page_after_ordinal+:page_size))
 SELECT record_type,scope_status,country_code,vendor_code,vendor_name,eta_code,eta_source,
 open_line_count,past_due_count,vendor_open_count,vendor_past_due_count,
 '3.1.0' AS metric_version,'CURRENT' AS time_basis,
 'America/Los_Angeles' AS business_timezone,now('America/Los_Angeles') AS queried_at_local,
 toInt64(length((SELECT requested_vendors FROM vendor_selection))) AS requested_vendor_count,
 expected_record_count,expected_bucket_count,row_ordinal,
 toInt64(:page_after_ordinal) AS page_after_ordinal,toInt64(:page_size) AS page_size,
 toInt64(count() OVER ()) AS page_record_count,
 if((SELECT input_status FROM input_control)='OK' AND
 expected_record_count-1>:page_after_ordinal+:page_size,
 toInt64(:page_after_ordinal+:page_size),toInt64(0)) AS next_after_ordinal,
 CAST((SELECT input_status FROM input_control)='OK' AND
 expected_record_count-1>:page_after_ordinal+:page_size AS Bool) AS has_more,
 result_fingerprint FROM paged ORDER BY row_ordinal"""


def add_pilot(doc):
    add_entity(doc,PARENT,INPUTS,[n for n,_ in INPUTS])
    add_entity(doc,CHILD,FIELDS,['country-code','record-type','vendor-code','eta-code','eta-source'])
    add_collection(doc,PARENT,'rows',CHILD,FIELDS,[n for n,_ in INPUTS],sql())
    description = ('PILOT CURRENT counts for an explicit nonempty Vendor set. '
                   'Joint Code/Source PD and Open with Vendor totals; no org filters, '
                   'ratios, nationwide denominator, ranking or history. '
                   'Permissions remain deployment-enforced. Live latency unverified.')
    doc[':execution'][':resolvers'][RID][K(':description')]=description
    doc[':vocabulary'][':entities'][PARENT][K(':entity/description')]=description
    doc[':vocabulary'][':attributes'][K(':'+PARENT+'/vendor-codes-json')][K(':attribute/description')]=(
        'Nonempty JSON array of positive Int64 numbers. [] is INVALID_VENDOR_SELECTION, '
        'never all Vendors. Repeats deduplicate. No SQL or quoted numbers.')
    for field, description in {
        'page-after-ordinal': '0 first page; then use returned next-after-ordinal. Not a handle. CURRENT reexecutes each page.',
        'page-size': '1..199 non-control rows plus repeated CONTROL, bounded below resolver default200.',
    }.items():
        doc[':vocabulary'][':attributes'][K(':'+PARENT+'/'+field)][K(':attribute/description')]=description
    doc[':vocabulary'][':attributes'][K(':'+CHILD+'/record-type')][K(':attribute/description')]=(
        'CONTROL ordinal0 repeats each page; FACT measurements; VENDOR_COVERAGE explicit '
        'Vendor readability, numeric placeholders are not measurements.')
    doc[':vocabulary'][':attributes'][K(':'+CHILD+'/scope-status')][K(':attribute/description')]=(
        'CONTROL OK validates inputs, not business coverage. Invalid inputs have explicit '
        'INVALID_* status. Coverage MATCHED or NO_READABLE_ROWS; no readable rows does not '
        'distinguish missing source data from access filtering and is never measured zero.')
    doc[':vocabulary'][':attributes'][K(':'+CHILD+'/result-fingerprint')][K(':attribute/description')]=(
        'Noncryptographic count-content checksum excluding time/pages; detects ordinary drift, '
        'not transaction isolation. Continuation reexecutes CURRENT SQL.')
    doc[':publication'][K(':release/version')]='3.5.1-counts-pilot'
    doc[':publication'][K(':source/revision')]=7
    return doc


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,default=ROOT/'atlas/charts/pastdue.edn')
    parser.add_argument('--output',type=Path,default=ROOT/'docs/diagnostics/source-failure/pastdue-current-counts-pilot.edn')
    args=parser.parse_args()
    if args.base.resolve()==args.output.resolve():
        parser.error('Pilot output must differ from the base graph')
    write(args.output,add_pilot(read(args.base)))
    print(args.output)

if __name__=='__main__': main()

"""Additive live overview and exact batch identity queries; development only.

Uses existing scalar JSON input conventions. No Atlas/source/config mutation.
"""
from build_analysis_contract import add_entity, add_collection, ratio
from build_explore_v2 import vendor_selection_cte
from build_v31_contract import scope_key
from edn_io import K, read, write
from pathlib import Path

OVERVIEW = 'pastdue.live-overview-query'
IDENTITY = 'pastdue.vendor-identity-query'
COMMON = [('scope-key','string'),('record-type','string'),('scope-status','string'),
          ('time-basis','string'),('queried-at-local','datetime'),
          ('business-timezone','string'),('metric-version','string'),
          ('expected-record-count','integer'),('row-ordinal','integer')]
OVERVIEW_INPUTS = [('country-code','string'),('top-n','integer')]
OVERVIEW_FIELDS = COMMON + [('country-code','string'),('vendor-code','integer'),
    ('vendor-name','string'),('vendor-rank','integer'),('open-line-count','integer'),
    ('past-due-count','integer'),('past-due-ratio','decimal'),('past-due-share','decimal'),
    ('country-open-count','integer'),('country-past-due-count','integer'),
    ('readable-vendor-count','integer'),('past-due-vendor-count','integer'),
    ('unattributed-open-count','integer'),('unattributed-past-due-count','integer')]
IDENTITY_INPUTS = [('countries-json','string'),('vendor-codes-json','string'),
    ('vendor-names-json','string'),('page-after-ordinal','integer'),('page-size','integer')]
IDENTITY_FIELDS = COMMON + [('requested-kind','string'),('requested-token','string'),
    ('country-code','string'),('vendor-code','integer'),('vendor-name','string'),
    ('candidate-count','integer'),('requested-input-count','integer'),
    ('page-record-count','integer'),('next-after-ordinal','integer'),
    ('has-more','boolean'),('result-fingerprint','string')]


def overview_sql():
    return """WITH input_control AS (SELECT multiIf(empty(trim(:country_code)),
 'INVALID_COUNTRY',:top_n NOT BETWEEN 0 AND 20,'INVALID_TOP_N','OK') AS input_status),
 grouped AS (SELECT toInt64(ifNull(from_acct_no,0)) AS vendor_code,
 min(ifNull(vendor_name,'')) AS vendor_name,toInt64(count()) AS op,
 toInt64(countIf(toInt64(is_pastdue)=1)) AS pd,false AS is_seed
 FROM past_due.past_due_detail_view WHERE country_code=:country_code
 AND (SELECT input_status FROM input_control)='OK' GROUP BY vendor_code
 UNION ALL SELECT toInt64(0),'',toInt64(0),toInt64(0),true),
 measured AS (SELECT *,toInt64(sum(op) OVER ()) AS country_open_count,
 toInt64(sum(pd) OVER ()) AS country_past_due_count,
 toInt64(countIf(NOT is_seed AND vendor_code>0) OVER ()) AS readable_vendor_count,
 toInt64(countIf(NOT is_seed AND vendor_code>0 AND pd>0) OVER ()) AS past_due_vendor_count,
 toInt64(sumIf(op,NOT is_seed AND vendor_code<=0) OVER ()) AS unattributed_open_count,
 toInt64(sumIf(pd,NOT is_seed AND vendor_code<=0) OVER ()) AS unattributed_past_due_count,
 toInt64(row_number() OVER (ORDER BY (NOT is_seed AND vendor_code>0) DESC,pd DESC,op DESC,vendor_code)) AS ranking
 FROM grouped),
 typed AS (SELECT if(is_seed,'COUNTRY','TOP_VENDOR') AS record_type,
 multiIf((SELECT input_status FROM input_control)!='OK',(SELECT input_status FROM input_control),
 country_open_count=0,'NO_READABLE_ROWS','OK') AS scope_status,
 :country_code AS country_code,if(is_seed,toInt64(0),vendor_code) AS vendor_code,
 if(is_seed,'',vendor_name) AS vendor_name,if(is_seed,toInt64(0),ranking) AS vendor_rank,
 if(is_seed,country_open_count,op) AS open_line_count,
 if(is_seed,country_past_due_count,pd) AS past_due_count,
 country_open_count,country_past_due_count,readable_vendor_count,past_due_vendor_count,
 unattributed_open_count,unattributed_past_due_count,
 if(is_seed,toInt64(0),ranking) AS row_ordinal
 FROM measured WHERE is_seed OR (vendor_code>0 AND ranking<=:top_n))
 SELECT *,"""+scope_key([n for n,_ in OVERVIEW_INPUTS])+""" AS scope_key,
 """+ratio('past_due_count','open_line_count')+""" AS past_due_ratio,
 """+ratio('past_due_count','country_past_due_count')+""" AS past_due_share,
 'CURRENT' AS time_basis,now('America/Los_Angeles') AS queried_at_local,
 'America/Los_Angeles' AS business_timezone,'3.1.0' AS metric_version,
 toInt64(count() OVER ()) AS expected_record_count FROM typed"""


def string_array_cte(parameter, alias, normalize=False):
    value='upper(trim(JSONExtractString(x)))' if normalize else 'trim(JSONExtractString(x))'
    return f"""{alias} AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON(:{parameter}),:{parameter},'[]')) AS tokens,
 isValidJSON(:{parameter}) AND toString(JSONType(if(isValidJSON(:{parameter}),:{parameter},'[]')))='Array'
 AND arrayAll(x -> toString(JSONType(x))='String' AND notEmpty(trim(JSONExtractString(x))),tokens) AS valid,
 arraySort(arrayDistinct(arrayMap(x -> {value},tokens))) AS values),"""


def identity_sql():
    logical=[n for n,_ in IDENTITY_INPUTS[:-2]]
    return 'WITH '+vendor_selection_cte()+string_array_cte('countries_json','countries',True)+string_array_cte('vendor_names_json','names')+"""
 input_control AS (SELECT multiIf(NOT (SELECT valid FROM countries) OR empty((SELECT values FROM countries)),'INVALID_COUNTRIES',
 NOT (SELECT vendor_selection_valid FROM vendor_selection) OR NOT (SELECT valid FROM names)
 OR (empty((SELECT requested_vendors FROM vendor_selection)) AND empty((SELECT values FROM names))),'INVALID_VENDOR_SELECTION',
 :page_after_ordinal<0 OR :page_size NOT BETWEEN 1 AND 199,'INVALID_PAGE','OK') AS input_status),
 requests AS (SELECT item.1 AS requested_kind,item.2 AS requested_token
 FROM (SELECT arrayJoin(arrayConcat(
 arrayMap(x -> tuple('CODE',toString(x)),(SELECT requested_vendors FROM vendor_selection)),
 arrayMap(x -> tuple('NAME',x),(SELECT values FROM names)))) AS item)
 WHERE (SELECT input_status FROM input_control)='OK'),
 identities AS (SELECT country_code,toInt64(ifNull(from_acct_no,0)) AS vendor_code,
 ifNull(vendor_name,'') AS vendor_name
 FROM past_due.past_due_detail_view WHERE country_code IN (SELECT arrayJoin(values) FROM countries)
 AND toInt64(ifNull(from_acct_no,0))>0 AND (SELECT input_status FROM input_control)='OK'
 AND (toInt64(ifNull(from_acct_no,0)) IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection)
 OR upper(trim(ifNull(vendor_name,''))) IN (SELECT arrayJoin(arrayMap(x -> upper(x),values)) FROM names))
 GROUP BY country_code,vendor_code,vendor_name),
 identity_keys AS (SELECT *,arrayJoin([tuple('CODE',toString(vendor_code)),
 tuple('NAME',upper(trim(vendor_name)))]) AS match_key FROM identities),
 candidates AS (SELECT r.requested_kind,r.requested_token,i.country_code,i.vendor_code,min(i.vendor_name) AS vendor_name,
 toInt64(1) AS found FROM requests r INNER JOIN identity_keys i
 ON r.requested_kind=i.match_key.1 AND upper(r.requested_token)=i.match_key.2
 GROUP BY r.requested_kind,r.requested_token,i.country_code,i.vendor_code),
 matches AS (SELECT r.requested_kind,r.requested_token,ifNull(c.country_code,'') AS country_code,
 toInt64(ifNull(c.vendor_code,0)) AS vendor_code,ifNull(c.vendor_name,'') AS vendor_name,
 toInt64(sum(ifNull(c.found,0)) OVER (PARTITION BY r.requested_kind,r.requested_token)) AS candidate_count
 FROM requests r LEFT JOIN candidates c ON r.requested_kind=c.requested_kind AND r.requested_token=c.requested_token),
 typed AS (SELECT 'RESOLUTION' AS record_type,
 multiIf(candidate_count=0,'NO_READABLE_MATCH',candidate_count=1,'MATCHED','AMBIGUOUS') AS scope_status,
 requested_kind,requested_token,country_code,vendor_code,vendor_name,candidate_count,
 toInt64(row_number() OVER (ORDER BY requested_kind,requested_token,country_code,vendor_code)) AS row_ordinal FROM matches
 UNION ALL SELECT 'CONTROL',(SELECT input_status FROM input_control),'','','',toInt64(0),'',toInt64(0),toInt64(0)),
 measured AS (SELECT *,toInt64(count() OVER ()) AS expected_record_count,
 toString(groupBitXor(cityHash64(toJSONString(tuple(record_type,scope_status,requested_kind,requested_token,
 country_code,vendor_code,vendor_name,candidate_count)))) OVER ()) AS result_fingerprint FROM typed),
 paged AS (SELECT * FROM measured WHERE record_type='CONTROL' OR
 (row_ordinal>:page_after_ordinal AND row_ordinal<=:page_after_ordinal+:page_size))
 SELECT *,"""+scope_key(logical)+""" AS scope_key,
 'CURRENT' AS time_basis,now('America/Los_Angeles') AS queried_at_local,
 'America/Los_Angeles' AS business_timezone,'3.1.0' AS metric_version,
 toInt64(length((SELECT requested_vendors FROM vendor_selection))+length((SELECT values FROM names))) AS requested_input_count,
 toInt64(count() OVER ()) AS page_record_count,
 if(expected_record_count-1>:page_after_ordinal+:page_size,toInt64(:page_after_ordinal+:page_size),toInt64(0)) AS next_after_ordinal,
 CAST(expected_record_count-1>:page_after_ordinal+:page_size AS Bool) AS has_more FROM paged"""


def add_live_current(doc):
    for parent,child,inputs,fields,sql,description in [
        (OVERVIEW,'pastdue.live-overview-fact',OVERVIEW_INPUTS,OVERVIEW_FIELDS,overview_sql(),
         'Live CURRENT PastDue overview / 目前 今日 实时情况: one Country summary plus bounded top Vendors by PD count. Reads detail view, never snapshots or alert gates. top-n=0..20; no-data is not zero.'),
        (IDENTITY,'pastdue.vendor-identity-fact',IDENTITY_INPUTS,IDENTITY_FIELDS,identity_sql(),
         'Resolve exact Vendor names and numeric codes in batches against readable CURRENT detail. Trim/ASCII case-insensitive names; return all Country+code candidates, ambiguity and missing coverage. No fuzzy guessing or Vendor existence claim.')]:
        add_entity(doc,parent,inputs,[n for n,_ in inputs])
        add_entity(doc,child,fields,['scope-key','row-ordinal'])
        add_collection(doc,parent,'rows',child,fields,[n for n,_ in inputs],sql)
        rid='pastdue_v3_'+parent.split('.')[-1].replace('-','_')+'_rows'
        doc[':execution'][':resolvers'][rid][K(':description')]=description
        doc[':vocabulary'][':entities'][parent][K(':entity/description')]=description
    descriptions={
        OVERVIEW:{'country-code':'Exact authorized Country. No Country specified by user: query US and CA separately.',
                  'top-n':'0..20 current Vendors, ordered by PD descending, Open descending, code ascending. Default overview5; 0=Country summary only. Includes measured zero-PD Vendors when within rank.'},
        IDENTITY:{'countries-json':'Nonempty JSON string array of authorized Countries. Explicit user Country or inherited scope; otherwise ["US","CA"]. This is search scope, not access control.',
                  'vendor-codes-json':'JSON numeric positive Int64 codes. [] permitted only with nonempty names. Never means all Vendors.',
                  'vendor-names-json':'JSON nonblank strings. Exact trimmed case-insensitive match, no substring/fuzzy auto-selection. [] permitted only with codes.',
                  'page-after-ordinal':'First0; thereafter returned next-after-ordinal, same logical inputs.',
                  'page-size':'1..199 resolution rows plus repeated CONTROL. Default100; verify page count and fingerprint.'}}
    for ns,values in descriptions.items():
        for name,description in values.items():
            doc[':vocabulary'][':attributes'][K(':'+ns+'/'+name)][K(':attribute/description')]=description
    for child in ('pastdue.live-overview-fact','pastdue.vendor-identity-fact'):
        for name,description in {
            'time-basis':'CURRENT detail view, not a completed snapshot.',
            'queried-at-local':'Query capture time in America/Los_Angeles, not source refresh timestamp or an atomic multi-query capture.',
            'scope-status':'Use measurements only with valid scope. Missing readable rows is not observed zero; resolution MATCHED requires exactly one Country+code candidate.',
        }.items():
            doc[':vocabulary'][':attributes'][K(':'+child+'/'+name)][K(':attribute/description')]=description
    field_notes={
        'pastdue.live-overview-fact':{
            'record-type':'COUNTRY ordinal0 has Country measures; TOP_VENDOR has ranked positive-code Vendor measures. Repeated Country totals must not be summed.',
            'open-line-count':'Readable current detail row count at the record grain, including non-PD rows. COUNTRY includes null/nonpositive Vendor codes.',
            'past-due-count':'Readable current detail rows with is_pastdue=1 at the record grain.',
            'past-due-ratio':'past-due-count / open-line-count at the record grain; null for zero Open. No prior-period observation.',
            'past-due-share':'past-due-count / country-past-due-count; null for zero Country PD. Top Vendor share uses all readable Country rows, including unattributed.',
            'country-open-count':'All readable current Country rows, repeated on TOP_VENDOR; do not sum.',
            'country-past-due-count':'Country rows with is_pastdue=1, repeated on TOP_VENDOR; do not sum.',
            'readable-vendor-count':'Distinct positive Vendor codes with readable Country rows, not all registered Vendors.',
            'past-due-vendor-count':'Distinct positive Vendor codes with at least one readable PD row.',
            'vendor-rank':'TOP_VENDOR PD descending, Open descending, code ascending; COUNTRY placeholder0.',
            'expected-record-count':'COUNTRY plus selected TOP_VENDOR rows, maximum21. Verify retained row and cell completeness.',
        },
        'pastdue.vendor-identity-fact':{
            'record-type':'CONTROL ordinal0 repeated per source page; RESOLUTION for each token and distinct Country+code candidate, including a no-match row.',
            'candidate-count':'Number of distinct Country+code matches for the requested kind/token;0=NO_READABLE_MATCH,1=MATCHED,greater1=AMBIGUOUS. All ambiguous candidates must be read.',
            'requested-kind':'CODE or NAME. CONTROL placeholder empty; token kinds are separate even if they refer to the same Vendor.',
            'requested-token':'Trimmed original name token or canonical numeric code token. Preserve original-input mapping outside the query; no fuzzy matching.',
            'requested-input-count':'Distinct numeric codes plus distinct trimmed name strings, before resolving; case variants remain separate input tokens.',
            'expected-record-count':'Whole logical result including one CONTROL and all resolution/candidate rows, not merely this page.',
            'page-record-count':'Actual source-page row count, including repeated CONTROL. Retained transport completeness is a separate check.',
            'next-after-ordinal':'Next source cursor only if has-more=true;0 means finished, never a result handle.',
            'result-fingerprint':'Order-independent noncryptographic XOR of complete resolution identities/statuses/candidate counts. Compare with scope/counts/ordinals; no atomic snapshot guarantee.',
        },
    }
    for child,notes in field_notes.items():
        for name,description in notes.items():
            doc[':vocabulary'][':attributes'][K(':'+child+'/'+name)][K(':attribute/description')]=description
    doc[':vocabulary'][':attributes'][K(':pastdue.live-overview-fact/unattributed-open-count')][K(':attribute/description')]='Country rows with null/nonpositive Vendor code; included in Country Open, excluded from ranked Vendors.'
    doc[':vocabulary'][':attributes'][K(':pastdue.live-overview-fact/unattributed-past-due-count')][K(':attribute/description')]='PD among null/nonpositive-code rows; included in Country PD, excluded from ranked Vendors.'
    doc[':publication'][K(':release/version')]='3.7.1'
    doc[':publication'][K(':source/revision')]=10
    return doc


if __name__=='__main__':
    root=Path(__file__).resolve().parents[1]
    path=root/'atlas/charts/pastdue.edn'
    doc=add_live_current(read(path))
    doc[':publication'][K(':release/version')]='3.7.1'
    doc[':publication'][K(':source/revision')]=10
    write(path,doc)

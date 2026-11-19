WITH vendor_selection AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON(:vendor_codes_json),:vendor_codes_json,'[]')) AS vendor_tokens,
 isValidJSON(:vendor_codes_json)
 AND toString(JSONType(if(isValidJSON(:vendor_codes_json),:vendor_codes_json,'[]')))='Array'
 AND arrayAll(x -> match(x,'^[1-9][0-9]{0,18}$')
 AND (length(x)<19 OR x<='9223372036854775807'),vendor_tokens) AS vendor_selection_valid,
 arraySort(arrayDistinct(arrayMap(x -> toInt64OrZero(x),vendor_tokens))) AS requested_vendors),countries AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON(:countries_json),:countries_json,'[]')) AS tokens,
 isValidJSON(:countries_json) AND toString(JSONType(if(isValidJSON(:countries_json),:countries_json,'[]')))='Array'
 AND arrayAll(x -> toString(JSONType(x))='String' AND notEmpty(trim(JSONExtractString(x))),tokens) AS valid,
 arraySort(arrayDistinct(arrayMap(x -> upper(trim(JSONExtractString(x))),tokens))) AS values),names AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON(:vendor_names_json),:vendor_names_json,'[]')) AS tokens,
 isValidJSON(:vendor_names_json) AND toString(JSONType(if(isValidJSON(:vendor_names_json),:vendor_names_json,'[]')))='Array'
 AND arrayAll(x -> toString(JSONType(x))='String' AND notEmpty(trim(JSONExtractString(x))),tokens) AS valid,
 arraySort(arrayDistinct(arrayMap(x -> trim(JSONExtractString(x)),tokens))) AS values),
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
 UNION ALL SELECT 'CONTROL' AS record_type,input_status AS scope_status,
 '' AS requested_kind,'' AS requested_token,'' AS country_code,
 toInt64(0) AS vendor_code,'' AS vendor_name,toInt64(0) AS candidate_count,
 toInt64(0) AS row_ordinal FROM input_control),
 measured AS (SELECT *,toInt64(count() OVER ()) AS expected_record_count,
 toString(groupBitXor(cityHash64(toJSONString(tuple(record_type,scope_status,requested_kind,requested_token,
 country_code,vendor_code,vendor_name,candidate_count)))) OVER ()) AS result_fingerprint FROM typed),
 paged AS (SELECT * FROM measured WHERE record_type='CONTROL' OR
 (row_ordinal>:page_after_ordinal AND row_ordinal<=:page_after_ordinal+:page_size))
 SELECT *,toJSONString(tuple(:countries_json, :vendor_codes_json, :vendor_names_json)) AS scope_key,
 'CURRENT' AS time_basis,now('America/Los_Angeles') AS queried_at_local,
 'America/Los_Angeles' AS business_timezone,'3.1.0' AS metric_version,
 toInt64(length((SELECT requested_vendors FROM vendor_selection))+length((SELECT values FROM names))) AS requested_input_count,
 toInt64(count() OVER ()) AS page_record_count,
 if(expected_record_count-1>:page_after_ordinal+:page_size,toInt64(:page_after_ordinal+:page_size),toInt64(0)) AS next_after_ordinal,
 CAST(expected_record_count-1>:page_after_ordinal+:page_size AS Bool) AS has_more FROM paged

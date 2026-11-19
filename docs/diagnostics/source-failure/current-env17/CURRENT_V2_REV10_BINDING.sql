WITH vendor_selection AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON(:vendor_codes_json),:vendor_codes_json,'[]')) AS vendor_tokens,
 isValidJSON(:vendor_codes_json)
 AND toString(JSONType(if(isValidJSON(:vendor_codes_json),:vendor_codes_json,'[]')))='Array'
 AND arrayAll(x -> match(x,'^[1-9][0-9]{0,18}$')
 AND (length(x)<19 OR x<='9223372036854775807'),vendor_tokens) AS vendor_selection_valid,
 arraySort(arrayDistinct(arrayMap(x -> toInt64OrZero(x),vendor_tokens))) AS requested_vendors),
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
 SELECT true AS requested,seed_vendor_code AS vendor_code,
 '' AS vendor_name,'' AS eta_code,'' AS eta_source,
 toInt64(0) AS op,toInt64(0) AS pd,true AS is_seed,false AS org_match
 FROM (SELECT arrayJoin(if((SELECT input_status FROM input_control)='OK',
 arrayConcat((SELECT requested_vendors FROM vendor_selection),[toInt64(0)]),[toInt64(0)])) AS seed_vendor_code)),
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
 toJSONString(tuple(:country_code, :vendor_codes_json, :department_type_filter, :master_primary_filter, :master_manager_filter, :denominator_scope)) AS scope_key,
 toDecimal128(toDecimal256(past_due_count, 18) / nullIf(toDecimal256(ratio_denominator, 18), 0), 18) AS past_due_ratio,
 toDecimal128(toDecimal256(past_due_count, 18) / nullIf(toDecimal256(share_denominator, 18), 0), 18) AS past_due_share,
 toDecimal128(toDecimal256(vendor_past_due_count, 18) / nullIf(toDecimal256(ratio_denominator, 18), 0), 18) AS vendor_past_due_ratio,
 toDecimal128(toDecimal256(vendor_past_due_count, 18) / nullIf(toDecimal256(share_denominator, 18), 0), 18) AS vendor_past_due_share,
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
 FROM paged

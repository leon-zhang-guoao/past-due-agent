-- Local graph3.6 binding with captured EQL inputs; live binding equality NOT VERIFIED.
-- Read-only database-client reference, never submit to Atlas EQL or analyze_result.
WITH
 vendor_selection AS (SELECT
 JSONExtractArrayRaw(if(isValidJSON('[]'),'[]','[]')) AS vendor_tokens,
 isValidJSON('[]')
 AND toString(JSONType(if(isValidJSON('[]'),'[]','[]')))='Array'
 AND arrayAll(x -> match(x,'^[1-9][0-9]{0,18}$')
 AND (length(x)<19 OR x<='9223372036854775807'),vendor_tokens) AS vendor_selection_valid,
 arraySort(arrayDistinct(arrayMap(x -> toInt64OrZero(x),vendor_tokens))) AS requested_vendors),
 date_guard AS (
 SELECT countIf(snapshot_date=toDate('2026-11-17')) AS target_exists,
 countIf(snapshot_date=toDate('2026-11-16')) AS previous_exists,
 maxIf(snapshot_date,snapshot_date<toDate('2026-11-17')) AS nearest_previous
 FROM (SELECT DISTINCT snapshot_date FROM past_due.vendor_past_due_daily
 WHERE country_code='US'
 AND snapshot_date BETWEEN toDate('2026-11-16') AND toDate('2026-11-17')
 AND snapshot_date < toDate(now('America/Los_Angeles'))
 AND ('WEEKDAYS'='CALENDAR_DAYS' OR ('WEEKDAYS'='WEEKDAYS' AND toDayOfWeek(snapshot_date)<=5)))),
 period_raw AS (
 SELECT toInt64(vendor_code) AS vendor_code,vendor_name,snapshot_date,
 if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))) AS raw_code,
 if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)) AS raw_source,
 toInt64(past_due_count) AS pd,toInt64(total_open_count) AS op,false AS is_control,false AS is_seed
 FROM past_due.vendor_past_due_daily
 WHERE country_code='US'
 AND snapshot_date IN (toDate('2026-11-17'),toDate('2026-11-16'))
 UNION ALL
 SELECT toInt64(0),'',toDate('1970-01-01'),'','',toInt64(0),toInt64(0),true,true
 UNION ALL
 SELECT arrayJoin(requested_vendors),'',toDate('1970-01-01'),'','',toInt64(0),toInt64(0),false,true
 FROM vendor_selection WHERE 'POSITIVE_TOP_N'='VENDOR_SET' AND vendor_selection_valid),
 expanded AS (SELECT *,arrayJoin(multiIf(is_control,[tuple('control','',false)],is_seed OR ('POSITIVE_TOP_N'='VENDOR_SET' AND vendor_code NOT IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection)),[tuple('metrics','',false)],
 [tuple('metrics','',false),tuple('code',raw_code,true),tuple('source',raw_source,true),
 tuple('code','',false),tuple('source','',false),tuple('us','',false),
 tuple(if(raw_code='US','us','skip'),raw_source,true)])) AS dim FROM period_raw),
 grouped AS (SELECT vendor_code,is_control,minIf(vendor_name,NOT is_seed AND NOT is_control) AS bucket_vendor_name,
 tupleElement(dim,1) AS dimension,tupleElement(dim,2) AS eta_value,tupleElement(dim,3) AS is_bucket,
 sumIf(pd,snapshot_date=toDate('2026-11-16') AND (dimension='metrics' OR is_bucket)) AS previous_count,
 sumIf(pd,snapshot_date=toDate('2026-11-17') AND (dimension='metrics' OR is_bucket)) AS target_count,
 sumIf(op,snapshot_date=toDate('2026-11-16') AND dimension='metrics') AS previous_metric_open,
 sumIf(op,snapshot_date=toDate('2026-11-17') AND dimension='metrics') AS target_metric_open,
 sumIf(pd,snapshot_date=toDate('2026-11-16') AND dimension='metrics' AND raw_code='US') AS previous_metric_us,
 sumIf(pd,snapshot_date=toDate('2026-11-17') AND dimension='metrics' AND raw_code='US') AS target_metric_us,
 max(NOT is_seed AND NOT is_control) AS source_present,
 max(pd<0 OR op<0 OR pd>op) AS bad_row
 FROM expanded WHERE tupleElement(dim,1)!='skip'
 GROUP BY vendor_code,is_control,dimension,eta_value,is_bucket),
 totals AS (SELECT *,max(source_present) OVER v AS has_readable_rows,min(bucket_vendor_name) OVER v AS vendor_name,
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
 toDecimal128(toDecimal256(target_past_due_count, 18) / nullIf(toDecimal256(target_country_open, 18), 0), 18) AS target_country_contribution,
 toDecimal128(toDecimal256(previous_past_due_count, 18) / nullIf(toDecimal256(previous_country_open, 18), 0), 18) AS previous_country_contribution,
 target_country_contribution-previous_country_contribution AS delta_country_contribution,
 toDecimal128(toDecimal256(target_past_due_count, 18) / nullIf(toDecimal256(target_open_count, 18), 0), 18) AS target_vendor_ratio,
 toDecimal128(toDecimal256(previous_past_due_count, 18) / nullIf(toDecimal256(previous_open_count, 18), 0), 18) AS previous_vendor_ratio,
 toDecimal256(target_past_due_count,0)*previous_country_open-toDecimal256(previous_past_due_count,0)*target_country_open AS contribution_numerator
 FROM totals),
 ranked AS (SELECT *,toInt64(dense_rank() OVER (ORDER BY is_control,has_readable_rows DESC,contribution_numerator DESC,vendor_code)) AS contribution_rank,
 sumIf(greatest(contribution_numerator,toDecimal256(0,0)),dimension='metrics') OVER () AS positive_numerator
 FROM calculated),
 marked AS (SELECT *,NOT is_control AND has_readable_rows AND (
 ('POSITIVE_TOP_N'='POSITIVE_TOP_N' AND contribution_numerator>0 AND contribution_rank<=10)
 OR ('POSITIVE_TOP_N'='TOP_N' AND contribution_rank<=10)
 OR ('POSITIVE_TOP_N'='VENDOR_SET' AND vendor_code IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection))) AS chosen
 FROM ranked),
 selected_metadata AS (SELECT *,
 toInt64(countIf(chosen AND dimension='metrics') OVER ()) AS selected_vendor_count,
 toDecimal128(toDecimal256(greatest(contribution_numerator,0), 18) / nullIf(toDecimal256(positive_numerator, 18), 0), 18) AS positive_contribution_share,
 toDecimal128(toDecimal256(sumIf(greatest(contribution_numerator,toDecimal256(0,0)),chosen AND dimension='metrics') OVER (), 18) / nullIf(toDecimal256(positive_numerator, 18), 0), 18) AS selected_positive_coverage
 FROM marked),
 scoped AS (SELECT m.*,multiIf(
 NOT (SELECT vendor_selection_valid FROM vendor_selection),'INVALID_VENDOR_SELECTION',
 'POSITIVE_TOP_N' NOT IN ('POSITIVE_TOP_N','TOP_N','VENDOR_SET')
 OR ('POSITIVE_TOP_N'='VENDOR_SET' AND (10!=0 OR empty((SELECT requested_vendors FROM vendor_selection))))
 OR ('POSITIVE_TOP_N'!='VENDOR_SET' AND (10 NOT BETWEEN 1 AND 100
 OR notEmpty((SELECT requested_vendors FROM vendor_selection)))),'INVALID_SELECTION',
 0<0 OR 50 NOT BETWEEN 1 AND 199,'INVALID_PAGE',
 'WEEKDAYS' NOT IN ('WEEKDAYS','CALENDAR_DAYS') OR toDate('2026-11-16')>=toDate('2026-11-17')
 OR d.target_exists=0 OR d.previous_exists=0 OR d.nearest_previous!=toDate('2026-11-16'),'INVALID_PAIR',
 invalid_facts>0,'DATA_ERROR',target_country_open<=0 OR previous_country_open<=0,'INVALID_DENOMINATOR',
 'OK') AS scope_status
 FROM selected_metadata m CROSS JOIN date_guard d),
 bucket_ranked AS (SELECT *,target_count-previous_count AS bucket_delta,
 toInt64(row_number() OVER (PARTITION BY is_control,vendor_code,dimension ORDER BY is_bucket DESC,abs(bucket_delta) DESC,target_count DESC,eta_value)) AS display_rank
 FROM scoped WHERE is_control OR (scope_status='OK' AND (chosen OR ('POSITIVE_TOP_N'='VENDOR_SET' AND dimension='metrics' AND vendor_code IN (SELECT arrayJoin(requested_vendors) FROM vendor_selection))))),
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
 tagged AS (SELECT *,arrayJoin(if(is_control,['COUNTRY_CONTROL'],arrayConcat(
 if(chosen AND dimension='metrics',['VENDOR_METRICS'],[]),
 if('POSITIVE_TOP_N'='VENDOR_SET' AND dimension='metrics',['VENDOR_COVERAGE'],[]),
 if(chosen AND NOT is_bucket AND dimension IN ('code','source','us'),['VENDOR_CHECK'],[]),
 if(chosen AND is_bucket AND display_rank<=6,[multiIf(dimension='code','ETA_CODE_SUMMARY',dimension='source','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY')],[])))) AS tag
 FROM enriched)
 , report_rows AS (SELECT toJSONString(tuple('US', '2026-11-17', '2026-11-16', 'WEEKDAYS', 'POSITIVE_TOP_N', 10, '[]')) AS scope_key, t.tag AS record_type, 'US' AS country_code, t.vendor_code AS vendor_code, toDate('2026-11-17') AS target_snapshot_date, toDate('2026-11-16') AS previous_snapshot_date, 'WEEKDAYS' AS calendar_mode, t.vendor_name AS vendor_name, if(t.tag IN ('COUNTRY_CONTROL','VENDOR_METRICS'),'',t.dimension) AS dimension, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY') AND t.display_rank<=5,t.eta_value,'') AS eta_value, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),if(t.display_rank=6,'OTHER','BUCKET'),'') AS row_kind, if(t.tag='VENDOR_COVERAGE',if(t.has_readable_rows,'MATCHED','NO_DATA_IN_PAIR'),t.scope_status) AS scope_status, '3.1.0' AS metric_version, 'POSITIVE_TOP_N' AS selection_mode, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),if(t.display_rank=6,t.other_target,t.target_count),t.target_past_due_count) AS target_past_due_count, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),if(t.display_rank=6,t.other_previous,t.previous_count),t.previous_past_due_count) AS previous_past_due_count, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),if(t.display_rank=6,t.other_target,t.target_count)-if(t.display_rank=6,t.other_previous,t.previous_count),t.past_due_count_change) AS past_due_count_change, t.target_open_count AS target_open_count, t.previous_open_count AS previous_open_count, t.target_country_open AS target_country_open, t.previous_country_open AS previous_country_open, if(t.is_control,toInt64(0),t.contribution_rank) AS contribution_rank, t.selected_vendor_count AS selected_vendor_count, t.target_vendor_ratio AS target_vendor_ratio, t.previous_vendor_ratio AS previous_vendor_ratio, t.target_country_contribution AS target_country_contribution, t.previous_country_contribution AS previous_country_contribution, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),toDecimal128(toDecimal256(if(t.display_rank=6,t.other_target,t.target_count), 18) / nullIf(toDecimal256(t.target_country_open, 18), 0), 18)-toDecimal128(toDecimal256(if(t.display_rank=6,t.other_previous,t.previous_count), 18) / nullIf(toDecimal256(t.previous_country_open, 18), 0), 18),t.delta_country_contribution) AS delta_country_contribution, t.positive_contribution_share AS positive_contribution_share, t.selected_positive_coverage AS selected_positive_coverage, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),t.display_rank,toInt64(0)) AS display_rank, t.expected_bucket_count AS expected_bucket_count, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY') AND t.display_rank=6,t.other_bucket_count,toInt64(0)) AS other_bucket_count, t.increase_total AS increase_total, t.decrease_total AS decrease_total, t.target_total-t.previous_total AS net_change, t.previous_total AS previous_total, t.target_total AS target_total, t.previous_expected_total AS previous_expected_total, t.target_expected_total AS target_expected_total, toInt64(0) AS expected_record_count, toInt64(0) AS row_ordinal, CAST(t.previous_total=t.previous_expected_total AND t.target_total=t.target_expected_total AS Bool) AS counts_match, CAST(t.required AS Bool) AS required, if(t.tag IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'),toDecimal128(toDecimal256(if(t.display_rank=6,t.other_target,t.target_count)-if(t.display_rank=6,t.other_previous,t.previous_count), 18) / nullIf(toDecimal256(if(t.display_rank=6,t.other_previous,t.previous_count), 18), 0), 18),toDecimal128(toDecimal256(t.past_due_count_change, 18) / nullIf(toDecimal256(t.previous_past_due_count, 18), 0), 18)) AS past_due_count_relative_change, 'America/Los_Angeles' AS business_timezone, now('America/Los_Angeles') AS queried_at_local, if(t.is_control,t.target_country_past_due,toInt64(0)) AS target_country_past_due_count, if(t.is_control,t.previous_country_past_due,toInt64(0)) AS previous_country_past_due_count, toDecimal128(toDecimal256(t.target_country_past_due, 18) / nullIf(toDecimal256(t.target_country_open, 18), 0), 18) AS target_country_ratio, toDecimal128(toDecimal256(t.previous_country_past_due, 18) / nullIf(toDecimal256(t.previous_country_open, 18), 0), 18) AS previous_country_ratio FROM tagged t),
 numbered AS (SELECT * EXCEPT(expected_record_count,row_ordinal),
 toInt64(count() OVER ()) AS expected_record_count,
 toInt64(row_number() OVER (ORDER BY record_type!='COUNTRY_CONTROL',contribution_rank,vendor_code,record_type,dimension,display_rank,eta_value))-1 AS row_ordinal,
 toString(groupBitXor(cityHash64(toJSONString(tuple(scope_key,record_type,country_code,vendor_code,target_snapshot_date,previous_snapshot_date,calendar_mode,vendor_name,dimension,eta_value,row_kind,scope_status,metric_version,selection_mode,target_past_due_count,previous_past_due_count,past_due_count_change,target_open_count,previous_open_count,target_country_open,previous_country_open,contribution_rank,selected_vendor_count,target_vendor_ratio,previous_vendor_ratio,target_country_contribution,previous_country_contribution,delta_country_contribution,positive_contribution_share,selected_positive_coverage,display_rank,expected_bucket_count,other_bucket_count,increase_total,decrease_total,net_change,previous_total,target_total,previous_expected_total,target_expected_total,counts_match,required,past_due_count_relative_change,business_timezone,target_country_past_due_count,previous_country_past_due_count,target_country_ratio,previous_country_ratio)))) OVER ()) AS result_fingerprint
 FROM report_rows),
 paged AS (SELECT * FROM numbered WHERE record_type='COUNTRY_CONTROL'
 OR (row_ordinal>0 AND row_ordinal<=0+50))
 SELECT *,toInt64(length((SELECT requested_vendors FROM vendor_selection))) AS requested_vendor_count,
 toInt64(0) AS page_after_ordinal,toInt64(50) AS page_size,
 toInt64(count() OVER ()) AS page_record_count,
 if(expected_record_count-1>0+50,toInt64(0+50),toInt64(0)) AS next_after_ordinal,
 CAST(expected_record_count-1>0+50 AS Bool) AS has_more
 FROM paged ORDER BY row_ordinal

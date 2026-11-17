-- Local 3.5.0 binding reference for T1, not verified identical to live binding.
-- Execute through an existing authorized database client; preserve current limits.
WITH mapped AS (
 SELECT DISTINCT country_code,toInt64(vendor_no) AS vendor_code FROM past_due.vendor_master_mapping_view
 WHERE country_code='US'
 AND (empty('') OR upper(trim(department_type))=upper(trim('')))
 AND (empty('') OR trim(ifNull(master_primary,''))=trim(''))
 AND (empty('') OR trim(ifNull(master_manager,''))=trim(''))),
 detail AS (SELECT t.* FROM past_due.past_due_detail_view t WHERE t.country_code='US' AND t.from_acct_no IS NOT NULL),
 scoped AS (SELECT * FROM detail WHERE (empty('64342') OR toString(toInt64(from_acct_no))='64342')
 AND ((empty('') AND empty('') AND empty(''))
 OR (country_code,toInt64(from_acct_no)) IN (SELECT country_code,vendor_code FROM mapped))),
 totals AS (SELECT (SELECT toInt64(countIf(toInt64(is_pastdue)=1)) FROM detail) AS country_total,
 (SELECT toInt64(countIf(toInt64(is_pastdue)=1)) FROM scoped) AS filtered_total),
 vendor_totals AS (SELECT country_code,toInt64(from_acct_no) AS vendor_code,toInt64(countIf(toInt64(is_pastdue)=1)) AS vendor_past_due_count FROM scoped GROUP BY country_code,vendor_code),
 aggregate_rows AS (
 SELECT country_code,toInt64(from_acct_no) AS vendor_code,min(vendor_name) AS vendor_name,
 if('VENDOR'='VENDOR','ALL',if('VENDOR'='VENDOR_CODE',if(empty(trim(ifNull(eta_code,''))),'UNKNOWN',upper(trim(eta_code))),if(empty(trim(ifNull(source,''))),'UNKNOWN',trim(source)))) AS eta_value,
 toInt64(count()) AS open_line_count,toInt64(countIf(toInt64(is_pastdue)=1)) AS past_due_count
 FROM scoped WHERE 'VENDOR' IN ('VENDOR','VENDOR_CODE','VENDOR_SOURCE') AND 'FILTERED_POPULATION' IN ('COUNTRY_ALL','FILTERED_POPULATION')
 GROUP BY country_code,vendor_code,eta_value)
 SELECT a.country_code AS country_code,a.vendor_code AS vendor_code,a.vendor_name AS vendor_name,a.eta_value AS eta_value,
 a.open_line_count AS open_line_count,a.past_due_count AS past_due_count,
 v.vendor_past_due_count AS vendor_past_due_count,'VENDOR' AS grain,'FILTERED_POPULATION' AS denominator_scope,
 if('FILTERED_POPULATION'='COUNTRY_ALL',t.country_total,t.filtered_total) AS denominator_past_due_count,
 toDecimal128(toDecimal256(a.past_due_count, 18) / nullIf(toDecimal256(a.open_line_count, 18), 0), 18) AS past_due_ratio, toDecimal128(toDecimal256(a.past_due_count, 18) / nullIf(toDecimal256(denominator_past_due_count, 18), 0), 18) AS past_due_share, toDecimal128(toDecimal256(a.past_due_count, 18) / nullIf(toDecimal256(v.vendor_past_due_count, 18), 0), 18) AS within_vendor_share,
 toInt64(row_number() OVER (ORDER BY a.past_due_count DESC,a.vendor_code,a.eta_value)) AS row_ordinal,
 toInt64(count() OVER ()) AS expected_row_count,now('America/Los_Angeles') AS queried_at_utc
 FROM aggregate_rows a INNER JOIN vendor_totals v ON a.country_code=v.country_code AND a.vendor_code=v.vendor_code CROSS JOIN totals t

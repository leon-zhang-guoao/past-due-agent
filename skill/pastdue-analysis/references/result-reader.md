# Retained-result reading — 3.3.6

Copy the objects below. Retained reads on ETA-PastDue-Graph use this shape.

Before `analyze_result` is sent, drop entries whose value is a blank string or an empty list. Keep `analysis_version`, keep `sql` when it contains the statement, and keep boolean false and numeric 0. This cleanup is only for `analyze_result`. A `query` string that contains EQL stays. A `result_handles` list that contains two or more handles stays. Monitor and report reads use the three-key object below, so those two selectors stay off that object.

## execute_eql

```json
{
  "query": "{:entity ... :query [...]}",
  "retain_result": true
}
```

## analyze_result

```json
{
  "result_handle": "HANDLE_FROM_THIS_TURN",
  "sql": "NON_EMPTY_DUCKDB",
  "analysis_version": 2
}
```

`HANDLE_FROM_THIS_TURN` is the `result_handle` returned with the `execute_eql` call for the rows you are reading. `analysis_version` stays 2.

## Monitor SQL

```sql
SELECT * FROM data ORDER BY country_code
```

`SELECT *` is for this small country monitor table. Read those rows before choosing dates or triggers. Then apply the US/CA allowlist locally.

## Report SQL

Use the same analyze_result object. Run these two statements on the report handle.

```sql
SELECT record_type, country_code, vendor_code, vendor_name, contribution_rank, selected_vendor_count, target_past_due_count, previous_past_due_count, past_due_count_change, target_open_count, previous_open_count, target_vendor_ratio, previous_vendor_ratio, target_country_contribution, previous_country_contribution, delta_country_contribution, positive_contribution_share, selected_positive_coverage, scope_status, expected_record_count, row_ordinal FROM data WHERE record_type IN ('COUNTRY_CONTROL','VENDOR_METRICS') ORDER BY row_ordinal
```

```sql
SELECT record_type, country_code, vendor_code, vendor_name, dimension, eta_value, row_kind, display_rank, other_bucket_count, previous_past_due_count, target_past_due_count, past_due_count_change, past_due_count_relative_change, delta_country_contribution, expected_bucket_count, increase_total, decrease_total, net_change, previous_total, target_total, previous_expected_total, target_expected_total, counts_match, required, row_ordinal FROM data WHERE record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY','VENDOR_CHECK') ORDER BY row_ordinal
```

Keep Vendor rows when `COUNTRY_CONTROL` country totals are 0 and the monitor query shows real country totals. Say the cross-check is unverified.

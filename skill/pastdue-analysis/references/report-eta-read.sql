-- Retained data only; SAME originating handle. Pure typed projection, no join or ranking.
-- SUMMARY quantity fields are bucket-level; VENDOR_CHECK quantity fields are dimension totals.
SELECT scope_key, record_type, country_code, vendor_code, vendor_name,
       previous_snapshot_date, target_snapshot_date, calendar_mode,
       selection_mode, metric_version, scope_status,
       contribution_rank, selected_vendor_count,
       dimension, eta_value, row_kind, display_rank, other_bucket_count,
       CASE WHEN record_type <> 'VENDOR_CHECK' THEN previous_past_due_count ELSE NULL END AS previous_past_due_count,
       CASE WHEN record_type <> 'VENDOR_CHECK' THEN target_past_due_count ELSE NULL END AS target_past_due_count,
       CASE WHEN record_type <> 'VENDOR_CHECK' THEN past_due_count_change ELSE NULL END AS past_due_count_change,
       CASE WHEN record_type <> 'VENDOR_CHECK' THEN past_due_count_relative_change ELSE NULL END AS past_due_count_relative_change,
       CASE WHEN record_type <> 'VENDOR_CHECK' THEN delta_country_contribution ELSE NULL END AS delta_country_contribution,
       expected_bucket_count,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN increase_total ELSE NULL END AS increase_total,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN decrease_total ELSE NULL END AS decrease_total,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN net_change ELSE NULL END AS net_change,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN previous_total ELSE NULL END AS previous_total,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN target_total ELSE NULL END AS target_total,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN previous_expected_total ELSE NULL END AS previous_expected_total,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN target_expected_total ELSE NULL END AS target_expected_total,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN counts_match ELSE NULL END AS counts_match,
       CASE WHEN record_type = 'VENDOR_CHECK' THEN required ELSE NULL END AS required, expected_record_count, row_ordinal
FROM data
WHERE record_type IN ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY','VENDOR_CHECK')
ORDER BY row_ordinal


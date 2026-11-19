-- Retained current-v2 result only; bind the actual originating handle.
-- Read missing row ranges only if output is truncated. Coverage is not measurement.
SELECT scope_key, record_type, scope_status, country_code, vendor_code, vendor_name,
       vendor_open_count, vendor_past_due_count, vendor_past_due_ratio,
       vendor_past_due_share, denominator_scope, time_basis, queried_at_local,
       metric_version, row_ordinal, expected_record_count, expected_bucket_count,
       requested_vendor_count, page_after_ordinal, page_size, page_record_count,
       next_after_ordinal, has_more, result_fingerprint
FROM data
ORDER BY row_ordinal

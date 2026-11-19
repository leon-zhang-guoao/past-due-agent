-- SAME originating current-v2 handle; read missing fields/ranges only.
-- Keep CONTROL/coverage ordinals so all source records are accounted for.
SELECT scope_key, record_type, scope_status, country_code, vendor_code,
       eta_code, eta_source, open_line_count, past_due_count,
       row_ordinal, result_fingerprint
FROM data
ORDER BY row_ordinal

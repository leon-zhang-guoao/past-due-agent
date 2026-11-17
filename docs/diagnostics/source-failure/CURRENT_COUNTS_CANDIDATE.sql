-- Pilot only: CURRENT counts for one explicit US Vendor set.
-- Reference metric3.1.0: PD = is_pastdue=1; Open = detail-view row count.
-- No organization filters in this pilot. Existing deployment access rules still apply.
-- No country denominator, ratios, ranking, historical source or mapping join.
WITH buckets AS (
    SELECT
        country_code,
        toInt64(from_acct_no) AS vendor_code,
        min(vendor_name) AS vendor_name,
        if(empty(trim(ifNull(eta_code, ''))), 'UNKNOWN', upper(trim(eta_code))) AS eta_code,
        if(empty(trim(ifNull(source, ''))), 'UNKNOWN', trim(source)) AS eta_source,
        toInt64(count()) AS open_line_count,
        toInt64(countIf(toInt64(is_pastdue) = 1)) AS past_due_count
    FROM past_due.past_due_detail_view
    WHERE country_code = 'US'
      AND from_acct_no IS NOT NULL
      AND toInt64(from_acct_no) IN
          (64342,75256,64351,19215,65505,75257,22344,85058,71886,48378,65885,75691,72478)
    GROUP BY country_code, vendor_code, eta_code, eta_source
)
SELECT
    country_code, vendor_code, vendor_name, eta_code, eta_source,
    open_line_count, past_due_count,
    toInt64(sum(open_line_count) OVER (PARTITION BY country_code, vendor_code)) AS vendor_open_count,
    toInt64(sum(past_due_count) OVER (PARTITION BY country_code, vendor_code)) AS vendor_past_due_count,
    toInt64(count() OVER ()) AS expected_bucket_count,
    toInt64(row_number() OVER (ORDER BY country_code, vendor_code, eta_code, eta_source)) AS row_ordinal,
    now('America/Los_Angeles') AS queried_at_local
FROM buckets
ORDER BY row_ordinal

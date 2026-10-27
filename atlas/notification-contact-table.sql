-- Deployment prerequisite for chart 11.
-- This table is for governed contact lookup only, not mail sending.
-- Populate it through approved ETL. Do not store portal passwords/usernames.
CREATE TABLE IF NOT EXISTS past_due.vendor_eta_notification_contact
(
    country_code LowCardinality(String),
    vendor_code Int64,
    recipient_type LowCardinality(String),
    email String,
    contact_name Nullable(String),
    source_field LowCardinality(String),
    source_row_number UInt32,
    email_domain LowCardinality(String),
    contact_role_hint LowCardinality(String),
    contact_priority_score Float32,
    is_internal_candidate UInt8,
    is_recommended UInt8,
    selection_reason String,
    evidence_text String,
    source_file_name String,
    source_row_hash FixedString(64),
    is_active UInt8 DEFAULT 1,
    source_system LowCardinality(String),
    record_version UInt64,
    updated_at_utc DateTime('America/Los_Angeles') -- business clock is PST/PDT; column name kept for compatibility
)
ENGINE = ReplacingMergeTree(updated_at_utc)
ORDER BY (country_code, recipient_type, vendor_code, email);

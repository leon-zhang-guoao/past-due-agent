-- Migration for an already-deployed contact table that predates country_code.
-- Do not use a guessed Country value for existing rows. Backfill the actual
-- Country through the approved ETL/data-owner process after adding the column.

ALTER TABLE past_due.vendor_eta_notification_contact
    ADD COLUMN IF NOT EXISTS country_code LowCardinality(String) DEFAULT '' AFTER email;

-- Required validation after the backfill:
--   SELECT country_code, count()
--   FROM past_due.vendor_eta_notification_contact
--   GROUP BY country_code
--   ORDER BY country_code;
--
-- Chart 11 filters by country_code, so rows with an empty value are not
-- eligible for a Country-specific recipient lookup until they are corrected.

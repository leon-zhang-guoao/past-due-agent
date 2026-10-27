# PastDue notification recipient display

This directory defines recipient-routing policy only. It does **not** generate
email drafts, call Python, call SMTP, call a mail API, or send messages.

Recipient data is expected to be loaded into the governed ClickHouse source and
exposed through the Atlas chart
`pastdue.notification-recipient/contacts`. The runtime Agent must obtain
contacts through Atlas; it must not read the original CSV directly.

The source CSV may be used by an approved data-ingestion process to populate
the ClickHouse table, but the CSV and its credentials remain outside this
bundle.

## Routing policy

| ETA Code | Recommended To | Recommended CC |
|---|---|---|
| `SC`, `NR`, `US`, `RQ` | Vendor | EMT + Buyer |
| `SH`, `LS` | Buyer | none |
| `DD` | Warehouse | Buyer |

Codes are trimmed and upper-cased. Unknown or blank codes produce
`RECIPIENT_UNRESOLVED`; they are never silently mapped to a rule.

When an alert triggers, the Agent writes the recommended `To`, `CC`, routing
rule, contact source, and missing-contact reason in the alert body. It does not
create or send an email.

The import process stores non-sensitive extraction metadata: role hint,
priority score, recommendation flag, selection reason, and redacted evidence
around the email. Agent selection should use this metadata and report
ambiguity rather than infer from an arbitrary email list.

The expected ClickHouse table and Atlas contract are documented in:

- `../atlas/notification-contact-table.sql`
- `../atlas/notification-contact-table-migration.sql` for an existing table
  that does not yet have `country_code`
- `../atlas/charts/pastdue.edn` (collection
  `pastdue.vendor-contribution/notification-recipients`)

`country_code` is required for Country-specific lookups. Existing rows must be
backfilled with their real Country through the approved ETL; do not default
them to `US` or `CA`.

# On-demand current action context

These facts are current, not historical attribution. Query only on request.
Discover exact inputs through MCP. Country+Vendor are mandatory across lookups.

- Buyer from current Owner/detail buyer_email. DNA Primary/Manager and ETAD
  Specialist from unified vendor_master_mapping_view, not detail master_*.
- For known Vendors use targeted master-mappings. Nationwide first page is not
  evidence a known Vendor has no Specialist. Deduplicate mapping before counts.
- Owner display order Buyer → Primary → Manager.
- Current detail-query/matching-lines is not high-risk-only by default. Explicit
  past-due filter for Vendor detail; report actual scope. Existing optional org
  filters are all-empty = no restriction, otherwise require matching Mapping.
- PO display max 5 per Vendor and 20 per Country. One per Vendor first, then
  rank fill. “Not shown due to cap” differs from “no rows”. Sample isn't census.

## Recipient display
Read policy :notification-routing. SC/NR/US/RQ → To Vendor, CC EMT+Buyer;
SH/LS → To Buyer; DD → To Warehouse, CC Buyer. No unknown-code guessed route.
Union applicable rule groups and deduplicate emails, retaining rule evidence.
For notification-recipients, entity must contain Country, Vendor, and exact
recipient-type VENDOR/EMT/WAREHOUSE. Even country-wide EMT/WAREHOUSE requires
Vendor input; SQL can select the country-wide rows. Buyer is not from this
contact collection. Confirm attributes via discovery; use recommended flags,
priority, role hint, selection reason and redacted evidence where available.
Ambiguous/nonregular text → unresolved candidates, never guess addresses.
Missing To/CC group → RECIPIENT_UNRESOLVED. No local CSV/Python, mail drafts,
sending, follow-up, reply tracking or escalation. TEST follows same display-only
boundary. Only minimal authorized personal fields are requested.

# Report compositions — v3

Resolve scope and analysis before template. Read table-display-contract.edn for
field order/format; the Markdown twin explains modules. Match request language.

## daily_full / historical_full
1. Title, actual PST run time, calendar, scope, versions actually verified.
2. Country metrics (daily) / trigger events (historical).
3. Each selected Country/event: reason/dates; Vendor summary in selection order;
   every selected Vendor Code/Source briefs and business diagnosis or failure.
4. Country business summary: worsening signals, concentrated Vendors, improvements,
   next checks, Top-N coverage and limitations. Not whole-Country distribution.
5. Cross-Country summary; never merged Vendor ranks. Completion module.

Default brief lists use SQL display-rank ≤5; append Other if remaining buckets.
All raw buckets/checks still must be retrieved and reconciled. Include increases
and decreases. Compact formatting is not permission to skip Vendor work.

## test_full
Same as daily_full, prefix [TEST], show true business status and TEST_FORCED/
THRESHOLD_MET reason. Final test checks PASS/PARTIAL/FAIL with counts and untested
paths. If stale, preserve warning. Empty positive selection is not a fabricated
analysis; explain ETA not applicable. Never send email.

## On demand / interactive
Only relevant modules: vendor_metrics, eta_code_changes, eta_source_changes,
us_source_changes, current_owners, notification_recipients, current_po_lines.
Other declared combinations use generic_analysis with dimension/metric labels
and explicit units, dates, scope and denominator. Avoid silently different tables
for the same metric/grain. A lack of custom display does not mean no data capability.

## Evidence
Fact → business-rule signal → possible verification → limits. No proven cause
or person blame from aggregate changes. Keep current action context separate.
Count decline plus contribution rise requires denominator explanation. A failed
Source doesn't erase good Code. Exact selected sets must match output; no “others
similar”, no unexplained missing cells, no fake interrupted-host reason.

If same-scope Atlas responses conflict, keep the selected Vendor and affected
dimension in place but show `FAILED`/`FAIL` with “数据身份/数量冲突，已隔离”. Do
not show an ETA summary or business diagnosis for that dimension. A verified
independent dimension may still be rendered. Missing scope metadata is
`PARTIAL`/`UNVERIFIED`, never `COMPLETE`; in test mode hard identity/count
conflicts produce `FAIL` and unknown-scope conflicts produce `PARTIAL`.

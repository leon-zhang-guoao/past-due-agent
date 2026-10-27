# Acceptance v3 — three separate gates

## A. Offline
Run scripts/run_golden_eval.py. It validates combined EDN, actual resolver mapping,
Skill/display consistency, exact date/threshold semantics and injected partial,
foreign, duplicate and mismatched data. This does not execute a language model.
Run test_sql_fixtures.py with optional chdb for real local ClickHouse execution
against synthetic tables, never production. A skipped suite isn't SQL validation.

## B. Real Atlas platform (pending deployment)
- Formal config-check and logical source-slot binding.
- MCP discovery shows monitor/history/analysis/current-analysis v3 namespaces.
- Query Monday target vs Friday prior through new validator; legacy unchanged.
- Batch lists page reliably at 200 and 1000, unique row-ordinal, expected counts.
- Nullable Decimal serializes through JDBC, including zero baseline nulls.
- US joint rows reconcile to US Code; two independent marginal tables not joined.
- Missing/forbidden Country is an explicit result limitation, not “normal”.
- RLS and sensitive contact fields enforced by deployment, not policy list alone.

## C. Real Agent reports (pending deployment)
- Daily two triggers: independent Top 10, Code/Source and diagnosis for every Vendor.
- No triggers: Country table only. 开始测试 still performs all applicable drilldown.
- Test stale pair, insufficient dates, zero positive Vendors; explicit coverage limits.
- Actual Monday run vs Tuesday run and weekend repeat; missing-workday disclosure.
- Historical recent week targets weekdays, previous before range start allowed;
  explicit calendar-day override; events-only no drilldown.
- Current Top 10 vs historical growth not mixed. Full Vendor ETA composition uses
  complete scoped buckets. Single Vendor metrics include negative contribution.
- Organization filtering doesn't multiply rows or remove unmapped without request.
- US AutoUS / known other / UNKNOWN diagnostic correct; no causal blame.
- Count decrease with positive contribution explains denominator.
- Code failure retains verified Source/other Vendors. No fake host interruption.
- Contacts only on ask, routing traceable, unresolved explicit, never send mail.
- PO <=5/Vendor and <=20/Country, first-one fairness and cap-vs-empty distinction.
- Same fixed question 3 times: metric, dates, Vendor set, field order match.

Save environment version, input identities, real tool results, reconciliation and
final report. Transport success or an offline PASS cannot satisfy B/C. Source of
old request/return contradiction remains UNCONFIRMED without server evidence.

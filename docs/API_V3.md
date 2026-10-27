# 3.2.0 additive block API — current standard report route

Standard reports use pastdue.report-block-query/blocks (seven report inputs +
language zh/en) and pastdue.monitor-block-query/countries (calendar, threshold,
language). See FACT_BLOCKS_3_2_0.md and the shipped Skill fact-block-contract.md.
Old report/monitor and flexible explore definitions below keep their metrics and
remain available; they are not a silent alternate default report renderer.
Underlying metrics remain 3.1.0; publication/template 3.2.0, source revision 3.
Actual platform config/JDBC/source compatibility is NOT_RUN pending one-Country
pilot; additional SQL formatter cost is unmeasured. No new runtime/service/table.

# API v3.1 — unified flexible analysis + report preset

Application contracts 3.1.0; Atlas semantic format remains 2.0.0. The integrated
combined graph has 35 resolvers and 595 attributes (static inventory only).
Confirm the actual live schema through MCP. No arbitrary SQL or new execution
service. Detailed fields and manual EQL examples live in
`skill/pastdue-analysis/references/data-contract.md` and
`skill/pastdue-analysis/references/atlas-query-playbook.md`.

| Request entity | Collection / row namespace | Inputs |
|---|---|---|
| pastdue.explore-query | rows / pastdue.explore-fact | ALL 20 below |
| pastdue.report-query | rows / pastdue.report-fact | Same seven as analysis-query below |
| pastdue.monitor-query | countries / pastdue.monitor-fact | calendar-mode, relative-change-threshold |
| pastdue.history-query | comparisons or trigger-events / pastdue.history-fact | country-code, calendar-mode, range-start-date, range-end-date, relative-change-threshold |
| pastdue.analysis-query (retained compatible) | scope-status, vendor-comparisons, eta-code-changes, eta-source-changes, us-source-changes, vendor-checks | country-code, target-snapshot-date, previous-snapshot-date, calendar-mode, selection-mode, top-n, vendor-code |
| pastdue.current-analysis-query (retained compatible) | rows / pastdue.current-analysis-fact | Existing current inputs unchanged; not a silent replacement for explore |

## Explore controlled inputs (all mandatory)
1. country-code (authorized Country).
2. time-basis: CURRENT / SNAPSHOT / PAIR / RANGE.
3. target-snapshot-date.
4. previous-snapshot-date.
5. range-start-date.
6. range-end-date.
7. calendar-mode: WEEKDAYS / CALENDAR_DAYS.
8. pair-policy: PREVIOUS_AVAILABLE / EXPLICIT.
9. grain: COUNTRY / VENDOR / VENDOR_CODE / VENDOR_SOURCE / VENDOR_CODE_SOURCE.
10. vendor-code: 0 all, otherwise actual Vendor.
11. eta-code-filter: '' none, otherwise normalized exact Code.
12. source-filter: '' none, otherwise trimmed exact Source (raw spelling preserved).
13. department-type-filter: '' none.
14. master-primary-filter: '' none.
15. master-manager-filter: '' none.
16. denominator-scope: COUNTRY_ALL / FILTERED_POPULATION / WITHIN_VENDOR.
17. ranking-metric: NONE / PAST_DUE_COUNT / PAST_DUE_RATIO / PAST_DUE_SHARE /
    COUNT_CHANGE / RATIO_CHANGE / CONTRIBUTION_CHANGE.
18. sort-direction: ASC / DESC.
19. change-selection: ALL / POSITIVE / NEGATIVE / ZERO.
20. top-n: 0 all or integer 1..100.

Unused dates MUST be supplied as typed `1970-01-01` sentinels, never displayed as
actual dates. CURRENT target is local capture date, not completed snapshot;
SNAPSHOT/PAIR use exact completed eligible dates, EXPLICIT pair never substituted.
RANGE is 1..366 inclusive target calendar dates, end before Los Angeles today,
returned per date, not summed stocks. Non-PAIR previous/change fields are ignored
in display. Change ranking or non-ALL change-selection requires PAIR.
WITHIN_VENDOR+COUNTRY is INVALID_SELECTION. Top-n limits grouped date×grain rows,
not always Vendors or per-date ranks. NONE retains deterministic identity order.

Rows: one `record-type=CONTROL`, then FACT rows only when scope-status=OK.
Control statuses: OK / NO_DATA / DATA_ERROR / INVALID_DATE / INVALID_PAIR /
INVALID_RANGE / INVALID_SELECTION / UNSUPPORTED_COMBINATION. Require one OK control
and full expected count before interpreting an empty FACT selection. Every row
projects scope-key, expected-record-count (includes CONTROL), row-ordinal,
metric-version, business-timezone (IANA), queried-at-local and complete identity.

Metric names are legacy-compatible target/previous-past-due-count and -open-count;
returned target/previous-ratio-denominator are whole Vendor Open (Country Open at
COUNTRY grain), NOT bucket Open. Share denominators are independent of ETA filters:
COUNTRY_ALL Country PD; FILTERED_POPULATION Vendor+org-filtered PD before ETA
filters; WITHIN_VENDOR Vendor PD before ETA filters. Ratio denominator doesn't
change with denominator-scope. Current Open is current-view row count, not an
unverified claim of identical historical ETL semantics. Org filtering uses distinct
CURRENT_MAPPING even historically; disclose this temporal limitation.

## Report preset (same seven analysis inputs)
POSITIVE_TOP_N requires top-n=1..10 and vendor-code=0; SINGLE_VENDOR uses an actual
Vendor and top-n=1, no positive-only gate. Report retains nearest-previous eligible
pair validation; arbitrary explicit earlier pairs use explore.

`pastdue.report-fact/record-type` tags:
- COUNTRY_CONTROL: exactly one scope-status, selected-vendor-count,
  target/previous-country-open, target/previous-country-past-due-count and
  target/previous-country-ratio. Require OK; reconcile monitor/history Country
  PD/Open/ratios to detect drift before combining report and trigger facts.
- VENDOR_METRICS: one per selected Vendor; same counts, Open, Vendor ratios,
  Country contributions, contribution-rank, positive-contribution-share and
  selected-positive-coverage as shared analysis facts.
- ETA_CODE_SUMMARY / ETA_SOURCE_SUMMARY / US_SOURCE_SUMMARY: full signed compact
  summaries, dimension code/source/us. US is built in for ALL Countries as
  Code=US×Source, never a cross-join of marginals or a Country=US-only query.
- VENDOR_CHECK: exactly one per Vendor×code/source/us, even empty/nonrequired.
  Require required/counts-match, previous/target-total,
  previous/target-expected-total, expected-bucket-count and signed totals.
  Code/Source reconcile to Vendor PD; US to Code=US PD.

Summary row-kind BUCKET has display-rank≤5; OTHER has rank6, blank eta-value,
other-bucket-count and server-aggregated T/P counts/change/relative/contribution.
expected-bucket-count counts raw buckets; BUCKET count + Other represented count
must match. Never count Other as one raw bucket or rebuild it from display rows.
Server computes Top-5, Other, full-population counts and increase/decrease/net
totals from shared facts. All report rows project scope-key, full identity,
expected-record-count INCLUDING COUNTRY_CONTROL and unique row-ordinal.
Sparse fields are meaningful only for their record-type; defaults aren't facts.

## Identity, time, transport and evidence
scope-key is `toJSONString(tuple(all required inputs))` in declaration order for
each query, included in row identity; always project it and retain original inputs.
It preserves filter/ranking/selection scope, NOT a watermark or transaction ID.
Check exact key within the captured request/rows; don't combine different keys as
one scope. Pin actual dates, metric and exposed environment/version across pages.

Business timezone America/Los_Angeles honors DST; queried-at-local is a local
capture timestamp, business-timezone is IANA, actual PST/PDT + UTC offset comes
from trusted runtime instant. Never infer an ambiguous fall-back instant or
hardcode PST. Date pinning/capture timestamps do not imply cross-query transaction.

Page default 200/max 1000, row-ordinal ascending. Consume every page and expected
record counts; compact summaries aren't incomplete raw-bucket retrieval. Standard
INTERACTIVE question allows ≤6 DIFFERENT LOGICAL FACT REQUESTS; discovery,
materialization and pagination are excluded, retries/corrections stay in the same
slot. Report presets have no six-request cap and attempt all mandatory work.
Broad history/optional scope can be bounded explicitly, never silently skipped.
No per-Vendor fanout, unconstrained/source SQL, runtime Python/local CSV, mail
or external Kernel. Handle-only execute_eql results can be materialized through
analyze_result using read-only SELECT of explicit discovered columns FROM data
ORDER BY discovered row-ordinal, bound to that exact retained request handle.
Schema inspection first; no presumed structured-read tool. No other tables, joins,
metric/denominator/rank/selection recomputation, fact-dropping filters or writes.
This governed result-handle SELECT is allowed and isn't a new logical fact request.

DATA_CONFLICT requires paired contradictory RAW same-scope responses with
provenance. Wrong attachment is ASSOCIATION_ERROR; rendered contradiction is
REPORT_ERROR. Missing metadata/evidence is UNVERIFIED, not proof of server fault.
Quarantine true conflicts, retain independently verified dimensions/Countries.
Missing required v3.1 is a deployment mismatch, no silent legacy fallback. Old
interfaces remain intact, not silently repointed or used for newer semantics.

### Capture and semantic-comparison safeguard
Show available queried-at-local and business-timezone; derive actual PST/PDT offset
only from its trusted corresponding instant/offset, never a guessed tool-capture or
delivery time. Without that evidence retain the returned local timestamp/IANA and
label offset UNVERIFIED. Legacy queried-at-utc keeps UTC semantics unchanged; the
new queried-at-local field never repurposes an old UTC attribute. DATA_CONFLICT
compares COMMON semantic fact fields in paired raw same-scope responses, not whole
payload shape: projection-only fields, handles or timestamps differing aren't a
conflict, and an unprojected field is unknown, not contradictory.

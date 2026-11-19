# Live overview and Vendor analysis — 3.7.2

Read for “目前／当前／今日 past due 情况”, a Vendor's situation, or a list of
Vendor names/codes without a historical comparison. These questions use readable
`past_due_detail_view` state, time-basis=CURRENT. Explicit historical dates,
comparison, alert monitoring, TEST and completed-period report presets retain
report-planning.md. An inherited explicit period remains unless the user changes it.

## Choose and execute

| Intent | Shared capability | Delivery |
|---|---|---|
| General current situation | `pastdue.live-overview-query/rows`, exact Country, top-n=5; default Countries US and CA separately | Country Open, PD, PD/Open, capture time; current leading Vendors and concentration, limitations |
| Known Country + one or many numeric codes | `pastdue.current-v2-query/rows` once per Country/set, pages as needed | Requested Vendor metrics; composition and interpretation for an analysis question |
| Names, mixed names/codes, or codes without known Country | `pastdue.vendor-identity-query/rows` in batches → group unique MATCHED Country+codes → current-v2 | Requested metrics; preserve original-token mapping and list ambiguous/unmatched items |
| Ranking, ETA selection filters, comparisons, trends | Existing explore-v3 / report interfaces by semantics | Preserve requested population, denominator, period and display scope |

Use [live-overview-query.eql](live-overview-query.eql),
[vendor-identity-query.eql](vendor-identity-query.eql) and
[current-v2-query.eql](current-v2-query.eql). Inputs are explicit scalars; JSON
arrays are encoded as strings. Copy/modify the small relevant example. Discover
only the chosen resolver's unresolved inputs/outputs once per environment. A
large full-graph schema is not required to execute a known compatible interface.
When tool output is stored/truncated, search its relevant resolver section and
read that section once; repeated overlapping reads add no capability or facts.

### Identity and membership

- A code with a known Country needs no name lookup. Retain positive Int64 codes
  exactly; never convert a name's digits or a mixed token into a guessed code.
- Identity query countries-json is the explicit/inherited authorized search scope;
  otherwise `["US","CA"]`, disclosed as the default scope. Names are exact after
  trim and ASCII case normalization (non-ASCII letters retain their case).
  Preserve punctuation; short names/aliases are not silently expanded.
- `vendor-codes-json` is numeric positive Int64 values, names are JSON strings.
  Either array may be empty; both empty is INVALID_VENDOR_SELECTION, never all.
  Distinct trimmed tokens are preserved; case variants may produce separate
  token rows mapping to the same Vendor. Deduplicate Country+code only for metrics,
  preserving each original token's result in delivery.
- Read every resolution page. CONTROL must be OK. Each requested token has
  MATCHED (exactly one distinct Country+code), AMBIGUOUS (all candidates returned),
  or NO_READABLE_MATCH. Ambiguous tokens need a Country/code choice; continue
  independent resolved items. Do not auto-pick the largest Vendor or first row.
  Code and name supplied as an explicit pair must agree on identity; separate
  arrays describe an OR-set, not zipped assertions. Intersect the pair's resolved
  Country+code candidates before metric selection; a name match to another code
  is a mismatch to report, not an additional requested Vendor.
- No match means no readable CURRENT match in this search scope. It proves
  neither nonexistence nor zero Open/PD. A historical-only Vendor may not resolve
  by current name; explicit codes remain usable for historical reports.
- Large lists: split only to fit actual EQL8KB/result limits; keep an original
  token ledger and union all chunks. More than10 Vendors is valid. Prefer sets
  over per-Vendor calls. A chunk is transport, never Top N or a change of scope.

## Measurements and interpretation

**Overview:** one COUNTRY row plus at most top-n TOP_VENDOR rows. Expected row
count and ordinals must match. Sort is PD descending, Open descending, code
ascending, including measured zero-PD Vendors when within rank. top-n0 is a
summary-only request; maximum20 is the overview display bound. For an explicit
larger ranking use explore, not repeated overview queries with guessed offsets.
Country PD/Open counts all readable Country detail rows, including rows with
null/nonpositive Vendor code; `unattributed-*-count` discloses those quantities.
Ranked Vendors use positive codes. Top Vendor shares use Country PD, so displayed
shares need not total100%. NO_READABLE_ROWS is unavailable measurement even if
numeric placeholders are0; Open>0 and PD=0 is a measured zero. Ratio with Open0
is null. COUNTRY fields repeat on ranked rows and must not be summed.

**Exact Vendor set:** use current-v2's eight inputs, three org filters empty
unless requested, denominator-scope=WITHIN_VENDOR by default for composition,
page-size100, cursor0. Requested Country contribution uses COUNTRY_ALL explicitly.
COUNTRY_ALL on this existing interface excludes null Vendor codes; the overview
includes them. If unattributed rows exist, disclose the denominator difference;
do not present the two shares as interchangeable or alter the existing formula.
FILTERED_POPULATION means selected Vendors + org; all three scopes retain the
same scoped Vendor Open ratio denominator. Rankings/selection filters use explore.

CONTROL and VENDOR_COVERAGE are not measured FACT rows. MATCHED coverage plus
FACT yields observed metrics. NO_READABLE_ROWS or EXCLUDED_BY_ORG is a local gap,
not measured zero. Vendor Open/PD/ratio/share repeat on each FACT: bind one
consistent value per Country+code, never sum repetitions. Disjoint joint buckets
sum to Vendor Open/PD. Sum complete joint facts by Code and independently Source
for composition; joint Code=US reveals its actual Sources. Normalize UNKNOWN as
returned. Use `vendor-past-due-ratio` for Vendor PD/Open, `past-due-ratio` for bucket
PD/Vendor Open. Interpret observed concentration/risk, not proven cause or change.

For one or many Vendors, deliver a row per requested identity (or precise local
gap). A situation/analysis question includes Open, PD, ratio and concise main
Code/Source findings where observed. A count-only question follows the measure
and completion scope in SKILL.md; bucket interpretation is optional.
A zero-PD Vendor is still included. Batch totals use unique Country+code metrics,
not input aliases or repeated buckets. Name resolution and metrics are separate
captures; preserve both and explain material drift. No previous-period delta,
trend, alert-trigger status or personal fault can be inferred from CURRENT alone.
Optional PO, Owner, contacts and HTML retain their existing on-request paths.

## Read and finish without repeating work

Follow result-reader.md for actual handles/argument shape and row/cell limits.
For small overview/identity results read the explicit projected scalar fields,
ordered by row_ordinal, only from their actual retained result. For Vendor sets,
[current-metrics-read.sql](current-metrics-read.sql) supplies coverage and Vendor
metrics; [current-buckets-read.sql](current-buckets-read.sql) supplies disjoint
buckets from the SAME handle when needed for the requested analysis. The metrics
read includes FACT ordinals/counts/fingerprint for page verification; use those
without reading every bucket field solely to answer a count. Full-width
current-page-read.sql stays optional.
Reuse fields already read. If retained output truncates, read missing ordinal
ranges/fields on that handle before executing another source query.

Identity/current-v2: union noncontrol ordinals, count repeated CONTROL once,
reconcile expected-record-count and page-record-count, preserve scope+fingerprint,
advance only when has-more using returned next-after-ordinal. Drift invalidates
completeness; a bounded fresh attempt is appropriate if source change is confirmed,
otherwise disclose the gap. No unchanged retries or alternate snapshot answers
masquerading as realtime. Complete verified scopes remain useful after local gaps.
For a source failure without a parameter/limit finding, keep the original scope
and record resolver, env/version, recent-id and the actual cause if available.
Reducing output fields or removing a correctly encoded name is a supported
correction only when evidence identifies a projection/size or input problem.
When no justified correction or verified equivalent path remains, deliver the
precise missing measurement and blocker rather than another speculative execute.
Capture time is query time in America/Los_Angeles, not source refresh time. Live
latency and freshness require deployment validation; the source may itself lag.

For small results fitting one source page, expected source work is two overview
queries for default US/CA; one current-set query per known Country; or one batch
identity query plus one current-set query per resolved Country. These are planning
expectations, not caps: source pagination, retained reads and genuine recovery are
counted separately. Every next call names a still-missing fact or supported
correction. Deliver when requested measurements and coverage are complete.

## Capability availability

Graph3.7 adds overview and identity; current-v2 is reused only for exact-set CURRENT
metrics/distributions here. This is a targeted path, not promotion over all explore
operations. Verify actual live capabilities. Equivalent explore-v3 CURRENT is a
fallback candidate for known codes only after its live execution and equivalent
semantics are verified; keep set/time/denominator and label its fields correctly.
Native22.8 tests have exposed an independent explore-v3 JOIN compatibility defect;
the old current-analysis path also has a recorded5s timeout. Treat neither as an
established working fallback. Missing name
resolution does not authorize guessing; deliver known-code facts and list unresolved
names. Older deployments may answer a general current query via equivalent explore
CURRENT; verify Country totals/ranking and bounded work before use. No snapshot
fallback, whole-schema recursion, timeout changes or silent scope reduction.

# Source-side selection and expansion — 3.5.0

Use when discovery exposes pastdue.explore-v3-query/rows. Complete scalar EQL:
[explore-v3-query.eql](explore-v3-query.eql). No native defaults, saved-query
schema, arbitrary expressions, new source or required runtime service are assumed.

## Independent selection and display

All 26 inputs are explicit: v2's22 plus selection-unit, rank-partition,
expansion-eta-code-filter and expansion-source-filter. Inactive dates remain typed
sentinels; time, calendar, Vendor JSON, org, denominator, change and page inputs
retain their meaning. New string inputs:

| Input | Meaning |
|---|---|
| selection-unit | VENDOR ranks Vendor observations then expands; GROUPED_ROW ranks displayed grain rows as old explore does. |
| rank-partition | GLOBAL across date×object rows; TARGET_DATE independently for every actual target date. |
| expansion-eta-code-filter | VENDOR output Code filter; empty=all. Must be empty for GROUPED_ROW. |
| expansion-source-filter | VENDOR output Source filter; empty=all. Must be empty for GROUPED_ROW. |

eta-code-filter and source-filter are selection filters. In VENDOR mode they
affect ranking, while expansion filters independently govern FACT. To rank whole
Vendors and show NR, leave selection filters empty and set expansion Code=NR.
To rank NR and show all ETA, do the reverse. Supply both if both should filter;
clarify material ambiguity. GROUPED_ROW uses selection filters for its displayed
rows, with expansion filters empty. VENDOR+COUNTRY is rejected.

top-n=0 selects all eligible objects. EMPTY_SELECTION never becomes [] (all
candidate Vendors). VENDOR supports VENDOR, VENDOR_CODE, VENDOR_SOURCE and
VENDOR_CODE_SOURCE display; GROUPED_ROW also supports COUNTRY. Ranking metrics
and signs retain metric3.1.0. RANGE returns separate stocks; no summing dates.
Expansion joins Country/date/Vendor keys: selection on one date does not select
that Vendor on another date. Ties use date, Vendor, Code, Source.

## Denominators

Denominators are computed BEFORE ETA filters, ranking and Top N.
FILTERED_POPULATION is the original candidate Vendor+org population, not winners.
COUNTRY_ALL is whole Country PD; WITHIN_VENDOR is whole Vendor PD. These choices
control SHARE, not PD/Open ratio denominators. Selection and expansion reuse the
original populations. Do not add SELECTION and FACT measurements together.

## Typed results

- CONTROL: validity/counts; EMPTY_SELECTION is a valid empty path. NO_DATA is no
  source rows for Country/time, not measured zero.
- SELECTION: one per selected date/object with selection-scope measurements,
  partition-local rank, actual measurement grain (VENDOR in VENDOR mode).
- FACT: display-scope measurements at requested grain, with inherited rank.
- VENDOR_COVERAGE: explicit candidate Vendor×actual date; numeric placeholders
  are not observations. Absent at COUNTRY grain and for []=all.

Coverage order: NO_DATA_IN_PERIOD → EXCLUDED_BY_ORG →
NO_MATCHING_SELECTION_BUCKETS → EXCLUDED_BY_SELECTION →
NO_MATCHING_EXPANSION_BUCKETS → MATCHED. PAIR source existence includes either
period; RANGE checks actual source dates individually. Absence of coverage for a
whole missing date does not certify that date present; use date discovery for gaps.

selected-object-count is the full SELECTION row count; fact-record-count is full
FACT count. Both are zero on errors because no certified measurements are emitted.
A selection with no matching display buckets still has CONTROL OK and SELECTION,
with no FACT. Explicit coverage explains the gap; for []=all compare complete
SELECTION/FACT identities to find it. Do not interpret this as a zero measurement.
returned-vendor-count counts distinct FACT Vendors, not selected date/objects.

## Pages, identity and consistency

Use actual execute handles and [result-reader.md](result-reader.md). The v2 page
contract applies: at most500 non-control rows plus repeated CONTROL, actual full
read count=page-record-count, stable scope/count/fingerprint, unique ordinal union
0..expected-record-count-1. After retrieval require SELECTION count=selected-object-count
and FACT count=fact-record-count. Every FACT must bind a SELECTION identity/rank;
absence of FACT for a selected object is an explicit display gap. Record type is
part of identity. Selection rank repeats over Vendor buckets; don't count repeats
as additional selected objects.

One SQL query reduces independent calls, not a transaction guarantee. CTEs/current
sources can be re-read; continuation pages re-execute SQL. Equal noncryptographic
fingerprints don't prove atomic capture. Check source/retained rows and cells.
Smaller pages don't lower aggregation cost; real plans/latency remain unverified.

## Compatibility

Daily/history/test keep monitor/history/report routes and format; PO/Owner retain
current-only paths and display limits. Use discovered v2/explore for equivalent
scopes if v3 is absent. Two-call rank→expand must preserve the original candidate
denominator and selected date/object keys: FILTERED_POPULATION over winners is not
equivalent. Use a complete equivalent plan or name the exact capability gap.

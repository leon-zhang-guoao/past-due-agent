# Explore v2 — additive transport contract, 3.4.0

Use only when live discovery exposes pastdue.explore-v2-query/rows and its full
projection. Old explore/report/current interfaces and metric3.1.0 are unchanged.
No new table, runtime Python, arbitrary source SQL or mail capability.
Complete EQL example: [explore-v2-query.eql](explore-v2-query.eql). Copy the actual
live attributes; example Vendor IDs are illustrative, never defaults.

## Inputs

Use old explore time/grain/filter/denominator/rank semantics with namespace
explore-v2-query; replace vendor-code with vendor-codes-json; add page-after-ordinal
and page-size. All 22 inputs are explicit. Old inactive dates remain sentinels.

- vendor-codes-json is a STRING containing positive Int64 JSON numbers:
  `"[64342,75256]"`, single=`"[64342]"`, all=`"[]"`. Strings, fractions, booleans,
  objects, zero, negatives and overflow yield INVALID_VENDOR_SELECTION. Duplicates
  and order do not change population. Preserve the exact input string across pages.
  JSON is a declared wire encoding for the current scalar Atlas input model, not
  a comma list in a legacy single-value parameter or arbitrary source SQL.
- page-after-ordinal=0 initially; copy next-after-ordinal thereafter.
- page-size=1..500 NON-CONTROL records. Planning default200; smaller pages reduce
  transport size, not aggregation cost. One CONTROL is repeated on every page.

## Records and fields

Old explore fields under explore-v2-fact plus requested-vendor-count,
returned-vendor-count, page-after-ordinal, page-size, page-record-count,
next-after-ordinal, has-more and result-fingerprint.

- CONTROL: overall scope-status, including INVALID_VENDOR_SELECTION and
  INVALID_PAGE alongside existing invalid-time/selection/data statuses.
  Numeric measurement defaults on CONTROL are placeholders, never observations.
- FACT: old metric3.1.0 calculations and selection for the equivalent population.
- VENDOR_COVERAGE: one per distinct explicit Vendor for valid scope/data,
  independently of Top N. Only identity and coverage status are meaningful;
  measurement defaults are not zeros. [] does not enumerate coverage records.

Coverage status checks in order: NO_DATA_IN_PERIOD (no source rows in requested
current/dated scope, not proof Vendor identity is invalid); EXCLUDED_BY_ORG;
NO_MATCHING_BUCKETS; EXCLUDED_BY_SELECTION; otherwise MATCHED. A Vendor present
only in P or T is present in a PAIR scope; use FACT for period measurements.
returned-vendor-count counts distinct selected Vendor FACT codes, zero at COUNTRY
grain. It is not a source existence or coverage count. NO_DATA is not an observed
zero population. Coverage statuses need not equal OK; do not treat that as failure.

## Source pages and retained reads

SQL numbers the complete result before paging: CONTROL ordinal0; other records
contiguous ordinals1..N. expected-record-count=N+1 includes CONTROL once and all
coverage rows. page-record-count counts THIS page, including repeated CONTROL.
Each SQL page is <=501 rows; transport default/max1000 must be honored. If a live
transport cap is lower, page-count verification detects it; reduce page-size.

Read each page's actual handle completely with explicit identity, metadata and
metric columns. Require no row/cell truncation and read count=page-record-count
before continuing. While has-more=true, copy next-after-ordinal. Deduplicate only
repeated CONTROL; the ordinal union must equal0..expected-record-count-1.
Adding page counts without removing repeated controls is incorrect. A cursor past
the end cannot prove earlier pages were read.

scope-key contains 20 logical inputs, not page inputs (echoed separately).
Compare scope-key, metric-version, expected count and fingerprint across pages.
result-fingerprint is a noncryptographic, order-independent checksum of returned
logical values excluding capture time/ordinals. It detects ordinary drift; it is
not a transaction, revision watermark or collision-proof guarantee. Continuations
re-execute SQL. Changed scope/count/fingerprint invalidates that union: one consistent
recapture attempt, otherwise disclose the gap. Equal checksums do not prove an
atomic current view; retain timestamps and do not claim snapshot isolation.

## Composition

Top N selects grouped FACT rows, not automatically Vendors or per-date ranks.
For top Vendors and all ETA, rank VENDOR first; bind codes; query VENDOR_CODE_SOURCE
with that set and top-n=0. Query VENDOR totals when required; filtered joint rows
cannot substitute whole-Vendor totals. Existing metric/denominator definitions
remain authoritative. See interactive-planning.md for bounded alternatives.

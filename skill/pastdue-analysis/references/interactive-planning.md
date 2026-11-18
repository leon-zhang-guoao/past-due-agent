# Composable interactive analysis — 3.5.0

Read for free questions, contextual follow-ups, or a failed interactive plan.
Daily/history/test presets retain their existing metric and report paths.
Requested reports and exact Vendor selections follow [report planning](report-planning.md);
alert eligibility does not restrict user-requested analysis.
This guidance does not install a host executor or a handle interceptor.

## Specification and composition

Keep one working specification: Country; Vendor selection (all/single/set/from a
verified prior result); CURRENT/SNAPSHOT/PAIR/RANGE and dates; measures; population,
ETA and org filters; grouping; denominator; selection unit/order/Top N; requested
evidence and output. A follow-up changes only specified fields. Ask about material
business ambiguity, not routine parameter conversion. User-provided old counts
are comparison inputs, not newly queried facts.

Choose supported select → filter → aggregate/compare → rank/select → drill
operations. Prefer graph-side composition in [explore-v3.md](explore-v3.md):
VENDOR selection with requested output grain returns ranking and expansion in one
logical query; TARGET_DATE partitions ranks by date. Selection/display filters
are separate; Top N retains candidate denominators. Empty selected keys remain
empty, never passed as []=all. GROUPED_ROW preserves old row-ranking meaning.
Dependent current PO steps bind actual selected identities and capture context.

When v3 is absent, discovered explore-v2 supports sets/pages; see explore-v2.md.
Old two-call Vendor→bucket plans require equivalence: passing only winners changes
FILTERED_POPULATION, and RANGE winners retain their dates. Global row Top N is not
per-date Top N. Preserve original denominators via a complete equivalent server
result or identify the exact capability gap.
Old explore remains valid for expressible scopes. Legacy current-analysis,
alert-count and detail vendor-code-filter take one exact Vendor or empty=all,
never a comma list. Check actual schema and equivalent metric/time/filter semantics
before changing paths. Missing v2 alone does not invalidate supported old queries.

Count/distribution questions use server aggregates; PO/Owner/contact requests
retain their dedicated interfaces. Use actual joint Code×Source for conditional
Source, not joined marginals. Country, filtered-population and within-Vendor
denominators stay distinct. Splitting a Vendor set changes FILTERED_POPULATION;
chunk ratios cannot be combined into the original population's ratios. Per-chunk
Top N cannot be called global Top N. Select a complete equivalent server plan.

## Ledger, cost and recovery

Keep a compact ledger per logical scope: original inputs; Vendor/date/dimension
obligations; capability; actual execute handle/environment/capture; pages and
ordinals/fingerprint; verified facts; remaining work. Copy handles only from the
originating response; read result-reader.md. This is an Agent procedure, not
machine-enforced state. Never substitute a plausible handle.

Prefer batches. Six distinct source scopes is a cost review point, not a stop.
Complete ordinary finite requested sets when feasible, using an explicit worklist,
deduplication and shared source concurrency one by default. A finite user-requested
set does not need repeated permission just because it exceeds six scopes.
Discovery, retained reads and continuation pages are not new business scopes,
but all count toward execution cost in every mode. Use result-reader.md's
page/fact progress notes and local recovery rather than a separate retry loop.
Bound open-ended expansions explicitly. Material
scope/cost increases beyond the request require direction; repeated unchanged
failures and unbounded work are stopping conditions.

| Observed failure | Permitted response |
|---|---|
| Invalid argument | Correct the identified argument, preserve user scope. |
| Incomplete page | Continue supported source paging or lossless retained projections. A source-truncated handle cannot recover missing source rows. |
| Timeout/load | Lower concurrency or partition independent work after semantic equivalence check; preview limit changes do not reduce aggregation cost. |
| v2 unavailable | Use a discovered older equivalent route with complete coverage, or name the exact deployment gap. |
| Unknown handle association | Quarantine dependent facts, inspect actual request/response; stop that path if linkage cannot be established. |
| Confirmed expiry | One exact-scope re-execution; retain the new capture. |
| Current page drift | Discard that page union; one consistent recapture attempt, otherwise disclose partial scope. |

At most one evidence-based changed correction per failed work item. A different
valid plan needs a reason, equivalence check and bounded cost; never identical
rejected calls or retry storms. Finite targeted Vendor queries are allowed when
batch is unavailable, not recursive unbounded fanout. Continue independent work.

A complete all-Vendor result may be projected to a subset for count/bucket questions
if identities, coverage and denominators stay correct. This permits scoped reads,
not metric/rank recomputation. Never subset an incomplete nationwide page or use
FILTERED_POPULATION=all as FILTERED_POPULATION=subset.

## Delivery

Answer only requested modules; account for the original Vendor set and distinguish
no data, zero measurement, filtered-out, selection-excluded and unfinished work.
Keep execution, retrieval and delivery separate. Use captured dates/denominators;
current PO/Owner facts do not prove historical causes. Local gaps do not invalidate
independent facts. Name exact remaining scope rather than generic whole-task failure.

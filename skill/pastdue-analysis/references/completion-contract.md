# Completion and evidence contract

For each requested Country record business/data/analysis status independently.
Freeze Country, T/P, calendar, selection mode and selected Vendor codes/ranks.
Each Code/Source result MUST project Country, Vendor, T, P, calendar, ETA value,
row-ordinal, expected-bucket-count and both period counts, not just value/delta.

## Before COMPLETE
1. vendor-comparisons and vendor-checks cover exactly the selected Vendor set.
2. For each dimension/Vendor unique bucket count equals expected-bucket-count;
   there are no duplicate row ordinals or identities; paging is exhausted.
3. Code T/P sum equals Vendor T/P; Source independently equals Vendor T/P.
   Check SQL totals against received facts, not just two copies of metadata.
4. US joint T/P sum equals US Code T/P for Vendors with nonzero US in either
   period. Nonrequired joint dimensions are explicitly not applicable.
5. Every identity and date pair matches scope; no inferred reassignment based
   on matching numbers. Checks and facts use compatible environment/version.
6. Report selected set/ranks equal comparisons and every Vendor has diagnosis
   or a specific unavailable/partial reason. Current facts are marked current.

`result-complete`/no truncation are transport/materialization scope; not proof
of full resolver population. coverage-unknown alone is neither failure nor
success. Expected bucket counts+unique identities+reconciliation support scope
completeness. Matching totals alone never proves identity.

Work state: verified / failed / completeness-unverified / not-executed. Last two
are unfinished, not empty or complete. One failed dimension doesn't erase the
other. Outer isError=false can still contain nested errors; inspect payload.
No arbitrary percent confidence. Retain result handles, query scope and versions
provided by MCP for audit; absent platform revision means UNVERIFIED, not invented.

## Cross-request identity conflicts

When more than one response is used for the same logical fact, normalize its
request scope key as:

```text
Country + Vendor + dimension + target date + previous date + calendar mode
+ selection/filter scope + metric version + environment/graph version (when available)
```

The fact identity is the request scope key plus ETA value. Exact duplicate
responses may be deduplicated only when the complete semantic fact payload is
identical. If two responses for the same scope and ETA value disagree on
either period count, quarantine both versions and mark that dimension `FAIL`
for a hard identity/count contract violation. Do not choose the first, latest,
largest, or numerically matching result; do not reassign a result to another
Vendor; and never sum conflicting versions. A non-identical payload with the
same counts is also not sufficient evidence to select one representation.

If required scope metadata is missing and the conflict cannot be classified,
retain the result only as `PARTIAL`/`UNVERIFIED`; it can never support
`COMPLETE`. A conflict in Code does not invalidate an independently verified
Source dimension, and vice versa. The selected Vendor remains in the report
with the affected dimension marked `FAILED`, `PARTIAL`, or `UNVERIFIED` and
the literal explanation “数据身份/数量冲突，已隔离” where applicable.

For test mode, a same-scope conflict is `FAIL`; an unknown-scope conflict is
`PARTIAL`. `NORMAL` business status does not make a test pass automatically.

## Test outcome
PASS: all applicable mandatory checks verified. PARTIAL: execution/external
failure or completeness unknown. FAIL: identity, count, date or contract hard
violation. No-trigger is not a failed test. Zero positive Vendors may PASS the
applicable empty path, but explicitly say multi-Vendor ETA was not exercised.
No host interruption claim unless actual host evidence exists. A table output
is not proof of a successful test; never say online acceptance passed offline.

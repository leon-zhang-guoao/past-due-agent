# Evidence and completion — 3.3.7

This is an Agent procedure, not an installed runtime gate. Query execution,
retrieval and delivery are separate; none proves independent final-output accuracy.

## A. Source and identity

Retain actual input, execute response, handle, exposed environment/version and
capture metadata. A handle must originate in that request. Missing linkage is
UNVERIFIED; a proven wrong attachment is ASSOCIATION_ERROR. Do not repair it by
matching plausible numbers. Start with serial Country requests.

Require scope-status=OK before interpreting selection. Compare COUNTRY_CONTROL
dedicated previous/target-country-past-due-count, -country-open and -country-ratio
to the pinned monitor/history. See result-reader.md's exact binding table; Vendor
count/Open fields on this control are placeholders and MUST NOT be compared as
Country measurements. Missing projection means not read, not zero. No zero-value
exemption applies to the dedicated fields. Dates/keys do not prove cross-query
transactions. Different captures that disagree remain unverified pending evidence;
never silently overwrite one with another. Counts exact; ratios use metric tolerance.
A genuine cross-capture mismatch limits attribution to that pinned monitor event.
Independently verified report-scope facts may be described as such, with their own
dates/denominators and explicit limitation; never silently merge captures. Unknown
handle provenance limits ALL facts dependent on that handle, even if totals match.

## B. Retrieval

Use the playbook's two-layer reading procedure. Track separately:
- source expected records and actual unique rows from all EQL pages;
- retained-result rows actually read, across all reading partitions/pages;
- cell truncation affecting each field, not only top-level truncated?.

Exactly expected rows with cut-off cells is not complete retrieval. A successful
SELECT over an incomplete retained result is not source completeness. A truncated
name limits name display, not independently intact numeric facts under verified
Country/Vendor identity. A truncated identity/scope field requires a supported
full-value read or leaves the affected association unverified. Don't silently
strip an ellipsis or assume it was part of the original value.

## C. Server reconciliation and compact representation

- Exactly one COUNTRY_CONTROL, including valid empty positive selection.
- Exactly selected-vendor-count unique VENDOR_METRICS, with stable returned ranks.
- Exactly one VENDOR_CHECK per selected Vendor×code/source/us, including empty paths.
- Code and Source each match Vendor two-period PD. Joint us matches Code=US PD.
- required=false with zero expected joint totals is NOT_APPLICABLE, not failure.
  Nonzero US expected/visible counts with required=false is inconsistent.
- Required checks need counts-match=true and matching expected/actual totals.
- Each summary BUCKET has unique identity/display-rank; OTHER is one server
  remainder, not UNKNOWN. BUCKET rows plus Other represented bucket counts must
  match expected-bucket-count. Empty nonzero-total dimensions are not valid empties.
- Never add Code and Source together. Read signed totals from VENDOR_CHECK;
  Other net delta does not expose its internal increase/decrease composition.

These support server full-population reconciliation and compact-summary coverage,
NOT client independent raw-bucket audit. Generic source-completeness UNKNOWN alone
neither proves failure nor overrides valid identities/counts/checks. Record the
actual evidence and any remaining source limitation.

## D. Delivery

Before sending, compare Vendor overview set/order to detail set/order; verify each
has metrics, both ETA modules, signed totals and explanation or local failure.
Keep a delivery ledger separate from retrieval: per Country, actual selected
Vendor codes/ranks → metrics → Code table → Source table → both signed totals →
diagnosis or precise local failure. Count a unit as delivered only when all these
applicable modules are actually present, not merely planned or available in a handle.
Code/Source tables include both periods, not just selected deltas. Check language,
dates, signs and delta-vs-level field binding against original rows.
This self-review is not independent validation and cannot guarantee final accuracy.

Report the actual analysis scope as complete/partial/unavailable/not required.
Keep actual business trigger and freshness separate. At the end report selected
Vendor count, fully delivered unit count, delivered-with-local-failure count and
not-yet-delivered codes/ranks. A local failure placeholder is not valid ETA analysis;
its presence makes the report honest, not the failed data complete. A source/read success with
missing Vendor sections is an incomplete delivery. Confirmed wrong rendered facts
are REPORT_ERROR; correct the output, not the valid source. No model-issued overall
PASS, invented evaluation score or source-hash proof of final text correctness.
If independent validation is asked about, say 'not performed' unless real evidence
exists; do not burden every successful Vendor with that technical disclaimer.

TEST shows forced vs real trigger, Countries/Vendors exercised, required steps
executed and missing/unexercised scope. Zero-positive selection can complete its
applicable empty path but did not exercise multi-Vendor ETA. No sending/escalation.

## Local failures and retry

A failed Code query/check does not erase Source or metrics. Failed joint evidence
limits its diagnostic, not ordinary Source display. Never substitute zero for
query failure or old positive-only drivers for signed changes.

DATA_CONFLICT requires TWO contradictory original responses under the same full
semantic identity and common field. Model drafts are not evidence. Projection,
format, time/version/scope differences alone are not same-fact contradictions.
Single-response mismatch is reconciliation failure, not paired conflict.

One evidence-based correction retry per logical request. Unsupported result-read
syntax may be corrected after discovering actual support. Cancellation alone does
not identify its initiator; reducing projection/limit doesn't fix source aggregation.
No permissions bypass, per-Vendor retry storm or fabricated platform root cause.
For result-reader selector validation, follow result-reader.md: initial call plus
ONE corrected call that removes conflicting keys, never an identical third attempt.
The correction must also preserve the actual execute handle and connection;
changing analysis_version isn't a correction. Discovery success is not rows success.
Mixed-selector errors alone don't prove host incompatibility. Actual required or
transformed conflicting keys need separate evidence before blaming an adapter.
Don't fix tool arguments by changing EQL/source SQL. The supplied manual discovery
test does not certify this Agent release or its report output.

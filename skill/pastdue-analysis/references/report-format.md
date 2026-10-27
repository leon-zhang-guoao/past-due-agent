# Fixed bilingual compact full-Vendor reports — v3.1

`table-display-contract.edn` is definitive for field IDs/order and compositions;
its Markdown twin is readable reference. A full report is a preset of shared facts,
not a different metric definition or a reason to suppress flexible questions.

## Language and header
English request (`start test` included) → English headings, diagnosis, warnings,
limitations and checks; Chinese request (`开始测试` included) → Chinese. Bilingual
means two complete language variants, not duplicate tables in both languages.
Preserve actual Country/Vendor/Code/Source identifiers and raw source spelling.
Use actual run instant in America/Los_Angeles, actual PST/PDT and UTC offset from
trusted runtime. Snapshot date-only values remain YYYY-MM-DD. Include calendar,
actual pair, declared selection/filter/denominator scope and versions actually
verified. Don't present the old manifest as proof of v3.1 or live deployment.

## daily_full / historical_full
0. Run information, mode, verified business time, scope and conclusion.
1. Country metrics / historical trigger events, including true business/data
   status and missing/stale/insufficient Country rows. Historical full reports
   include verified event counts/Open/ratios as context, not just trigger labels.
2. Each applicable Country/event: pinned dates/reason, Vendor summary in server
   contribution-rank order with all selected Vendors and selected coverage.
3. For EVERY selected Vendor repeat this compact full section:
   - vendor_metrics (PD/Open/ratio/contribution previous→target and status);
   - eta_code_changes: server BUCKET Top-5 + OTHER rank6, signed changes;
   - eta_source_changes: same independent Source summary;
   - us_source_changes: verified Code=US joint Source interpretation when the
     dimension=us check required=true, for ANY Country, not just Country US;
     otherwise explicit not applicable;
   - business_diagnosis: verified fact → bilingual rule signal → suggested
     verification → limits, or precise per-dimension failure.
4. Country summary: worsening signals AND improvements, concentration in selected
   Vendors, coverage, denominator effects, next checks and limits. Selected Top-N
   is not all-Country distribution; no merged cross-Country Vendor ranks.
5. Cross-Country summary from independent verified Country results; no merged Vendor ranks.
6. Completion with verified/expected scope counts, outcome and specific gaps.

Default Country order is US then CA; other explicit Countries follow request order.

Compact means short cells and no duplicate raw-bucket dump, NEVER omitted Vendors
or “others similar”. Do not replace full Vendor sections with summary briefs.
Use server increase/decrease/net totals; Other can contain offsetting changes.
Code/Source are alternative dimensions and must not be added. A failed dimension
stays labeled unavailable/partial; independent verified facts remain visible.
Empty selected set explicitly skips per-Vendor modules as not applicable.
Normal daily with no real triggers shows Country table and explicit
analysis=NOT_REQUIRED; do not force the full preset except in test/user request.

## test_full
Daily composition + [TEST] prefix and final test_checks. Preserve true NORMAL /
TRIGGERED / ZERO_BASELINE_INCREASE, show TEST_FORCED or THRESHOLD_MET. All
applicable selected Vendors and diagnosis required; valid stale pairs get warnings.
PASS/PARTIAL/FAIL is evidence-based, not inferred from presence of tables. Say which
empty/unsupported paths were not exercised. Optional Owner/contact/PO aren't
implicit test requirements. Never send mail or claim host-enforced completion.

## Historical events only and interactive
historical_events_only = trigger_events, no drilldown. Interactive =
generic_analysis, or the relevant standalone modules using the same fixed columns.
Explore preserves dimensions/time/ranking/filter/denominator and explicit source
units. Omit all non-PAIR previous sentinel and change placeholders, not just the
date. RANGE keeps each target's stocks separate; top-n limits date×grain rows.
On-demand owners/contacts/PO are marked CURRENT and cannot explain historical cause.

## Evidence and diagnosis
Count decline with rising Country contribution requires a denominator explanation.
US AutoUS and explicit AUTO US (trimmed case-insensitive EXACT) mean the
Vendor-provided ETA is overdue; OTHER_KNOWN means a rough date ONLY for exact
recognized business Source dictionary values observed in verified joint rows.
Unknown/unrecognized raw Source must use the UNKNOWN diagnostic, retaining its
raw label; never classify every non-AutoUS value as rough-date. Apply rules only to verified joint Source rows; don't
infer US association from marginal Source. OTHER is a mixed aggregate: never
classify all Other as AutoUS/known/unknown or invent its hidden buckets. State
that its source-specific interpretation is unavailable while retaining its counts.

DATA_CONFLICT requires both raw same-scope contradictory responses and provenance;
quarantine both, retain Vendor position, show “数据身份/数量冲突，已隔离” /
“Conflicting identity/count evidence quarantined”. No affected-dimension diagnosis.
ASSOCIATION_ERROR is mismatched attachment; REPORT_ERROR is a bad rendered claim.
Correct the right layer; missing evidence is UNVERIFIED, not a proven server fault.
Completion-contract.md governs classification and test outcome. Only INTERACTIVE
questions have ≤6 DIFFERENT LOGICAL FACT REQUESTS; discovery/materialization/
pagination are excluded, retries stay in the same slot. Report presets have no
six-request cap and must attempt all mandatory work. Bound broad historical or
optional scope explicitly, never silently skip or fabricate host interruption/
promised continuation. Completeness is based on facts/checks, not six repeated
module-status strings; failure/N/A can be stated once at its affected scope.

### Capture and semantic-comparison safeguard
Show available queried-at-local and business-timezone; derive actual PST/PDT offset
only from its trusted corresponding instant/offset, never a guessed tool-capture or
delivery time. Without that evidence retain the returned local timestamp/IANA and
label offset UNVERIFIED. Legacy queried-at-utc keeps UTC semantics unchanged; the
new queried-at-local field never repurposes an old UTC attribute. DATA_CONFLICT
compares COMMON semantic fact fields in paired raw same-scope responses, not whole
payload shape: projection-only fields, handles or timestamps differing aren't a
conflict, and an unprojected field is unknown, not contradictory.

## Rule provenance and recognized Source dictionary
Every rule declares stable rule-id, risk-type and requires-joint-facts. US rules
require joint facts; non-US rules use verified Code facts and don't require a
Source association. Preserve rule IDs in diagnostic evidence without adding
mandatory table columns or repeating status boilerplate. risk-type is the rule's
business signal, not proof of causation or additional source-specific meaning.

The explicit accepted observed Source set is maintained in
eta-diagnostic-rules.edn :source-classification/:recognized-other-sources. Match
trimmed case-insensitive EXACT values; AutoUS/AUTO US take precedence. Dictionary
membership alone never proves a Source exists in the queried population. Apply
OTHER_KNOWN only to an actual verified Code=US×Source BUCKET with membership.
Do not emit absent sources, infer hidden OTHER constituents, invent semantics
from names (EDI/API/BUYER etc.), create a data table, or assume a catalog tool.
New recognized values require explicit governed business dictionary approval;
otherwise diagnostic UNKNOWN, not a catch-all OTHER_KNOWN branch.

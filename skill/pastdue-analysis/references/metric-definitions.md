# Metrics and time — v3.1 authoritative semantics

## Time and evidence basis
Business timezone: **America/Los_Angeles**, including DST. Completed means
snapshot_date < today's Los Angeles date, not proof ETL is complete. Run-time
labels use the instant's actual PST (UTC−08:00) or PDT (UTC−07:00). For example,
2026-10-27 is PDT; 2026-11-03 is PST. Snapshot dates remain YYYY-MM-DD; don't
append an invented fixed-zone time to a date-only fact.

WEEKDAYS = Monday–Friday, no holiday exclusions. Default T is latest eligible
actual date; P is nearest earlier eligible actual date independently per Country.
Tuesday normally compares Monday to Friday; Monday's run compares Friday to
Thursday. PREVIOUS_AVAILABLE uses actual dates, not yesterday arithmetic.
EXPLICIT explore pairs honor both supplied dates or fail; report preset retains
nearest-previous semantics. Range applies to targets; P may precede range start.
CALENDAR_DAYS is an explicit override, not a fallback for missing weekdays.

Expected target = nearest weekday strictly before D. Lag counts weekdays T+1
through expected; >2=STALE, 1..2=DELAYED. Missing between P/T excludes endpoints.
Normal weekends aren't missing workdays. Repeated weekend pairs aren't new events;
without persistent host state no claim of cross-run notification deduplication.

CURRENT is current readable state with capture date, not latest completed snapshot.
SNAPSHOT is a single eligible completed date. PAIR is two actual eligible dates.
RANGE is separate dated snapshot stocks, not a sum of daily stocks. Ignore
nonapplicable previous/change placeholders on non-PAIR results.

## Country trigger
R_T=PD_T/Open_T; R_P=PD_P/Open_P. Relative=(R_T−R_P)/R_P;
point-change ratio=R_T−R_P; pp multiplies by 100. Relative ≥0.10 triggers using
unrounded counts/cross-products, no daily normalization of multi-day gaps.
Previous PD=0 with positive Open in both periods and target PD>0 is
ZERO_BASELINE_INCREASE; both PD=0 is normal. Open=0 isn't a valid ratio or zero
baseline. Negative counts or PD>Open are DATA_ERROR. Missing P is INSUFFICIENT.

## Shared Vendor report selection
C_T(v)=PD_T(v)/CountryOpen_T; C_P(v)=PD_P(v)/CountryOpen_P;
delta_C=C_T−C_P. Server compares exact numerator
PD_T(v)*CountryOpen_P−PD_P(v)*CountryOpen_T. Positive numerators descending,
Vendor ascending, first top-n≤10; never stop at 80% coverage.
Positive share = positive numerator / sum(all positive numerators).
Selected coverage = selected positive / all positive, NOT fraction of net rise.
All signed contributions reconcile to Country point change; positive Top-10 need
not sum to it and can exceed net rise. SINGLE_VENDOR includes zero/negative delta.
Vendor ratio=Vendor PD/Vendor Open, not Country Open or past-due-only row count.

## Flexible metrics, denominators and ranking
`pastdue.explore-fact` uses target/previous-past-due-count and -open-count for all
bases, plus explicitly returned ratio/share denominators. At COUNTRY grain,
ratio=group PD/whole Country Open; at Vendor grains ratio=group PD/whole Vendor
Open, including when grouping by Code/Source. Do NOT equate bucket Open to ratio
denominator. `denominator-scope` controls SHARE only:
- COUNTRY_ALL: whole Country PD before Vendor/org/ETA filters.
- FILTERED_POPULATION: Vendor+org-filtered PD before ETA filters.
- WITHIN_VENDOR: each scoped Vendor PD before ETA filters; invalid for COUNTRY.
A fully scoped Vendor's WITHIN_VENDOR share can be 100%; this is not its PD rate.

Current Open is current detail-view row count, not proven identical to historical
snapshot ETL semantics. Org filters use distinct CURRENT_MAPPING, including on
historical facts; don't multiply rows or silently discard unmapped Vendors when
no org filter is present. Code trim-uppercase; Source trimmed case-preserving
exact match. Diagnostic AUTO US alias does not rewrite a raw source filter.

Server ranks grouped FACT rows using PAST_DUE_COUNT (target count), PAST_DUE_RATIO
(target ratio), PAST_DUE_SHARE (target share), COUNT_CHANGE (T−P), RATIO_CHANGE
(target−previous ratio), CONTRIBUTION_CHANGE (delta_C), or NONE (deterministic
identity order). ASC/DESC is explicit, top-n=0 means all or 1..100 limits rows.
RANGE top-n is across date×grain rows, not per date. Null rank metrics sort last;
ties use target date/Vendor/Code/Source. PAIR is required for change ranking or
POSITIVE/NEGATIVE/ZERO selection. Selection sign follows ratio/contribution change
when those are the ranking metric, otherwise count change. ALL includes all signs.

## ETA shared summaries
Full normalized union of periods contains positive/negative/zero deltas. Empty
Code/Source=UNKNOWN. Missing period bucket may be zero only after valid complete
query; an error isn't zero. Delta=T−P; relative=delta/P when P>0, otherwise null
(zero-baseline increase or no change); decline to zero is −100%.

Server ranks abs(delta) DESC, target count DESC, raw normalized value ASC. Report
returns BUCKET rank≤5 and OTHER rank6; Other sums exact T/P before calculating
percentages. expected-bucket-count counts raw buckets, Other's
other-bucket-count counts represented raw buckets. Never fetch only first five
records or recompute remainder from rounded summaries.
Increase=sum(max(delta,0)); decrease=sum(min(delta,0)); net=increase+decrease.
OTHER's net change alone cannot reconstruct its internal increases/decreases;
use server signed totals and VENDOR_CHECK. Code and Source each reconcile T/P to
Vendor, never add them together. US joint-source reconciles to Code=US, not whole
Vendor. Counts exact; unrounded ratios tolerate abs/relative 1e-9. Decimal scale
18 is transport precision, not display precision or exact threshold arithmetic.
No exposed dataset revision means pinned dates don't prove a transaction snapshot.

## Status and interpretation
business_status: NORMAL / TRIGGERED / ZERO_BASELINE_INCREASE / NOT_EVALUABLE.
data_status: OK / DELAYED / STALE / INSUFFICIENT / ACCESS_DENIED / DATA_ERROR.
analysis_status: COMPLETE / PARTIAL / UNAVAILABLE / NOT_REQUIRED.
drilldown_reason: THRESHOLD_MET / TEST_FORCED / USER_REQUESTED / NONE.
Normal daily stale/invalid/uncomparable pairs don't enter valid alert drilldown;
test can inspect valid stale pairs with warnings. Analysis failure does not erase
an independently verified trigger. Current explore doesn't invent a monitor trigger.

Risk codes are signals, not proven causes. Falling PD with rising contribution
can reflect shrinking Country Open. Aggregate changes don't prove individual PO
migration or personal fault. Owner/contacts/PO are CURRENT only. Bilingual rule
text and explicit AutoUS/AUTO US mapping are in eta-diagnostic-rules.edn.

## Display precision
Ratios/shares/relative changes: TWO decimals %. Contribution and ratio point
changes: FOUR decimals pp (including paired contributions), after multiplying
ratio units by 100. Never use formatted values for thresholds or reconciliation.

## Rule identity and Source classification
eta-diagnostic-rules.edn assigns rule-id, risk-type and requires-joint-facts to
every bilingual rule. All US interpretations require verified joint Code=US×Source
facts in any Country. AutoUS/AUTO US match first. OTHER_KNOWN requires exact
membership in its explicit governed recognized Source set AND presence in an
actual joint row; any unrecognized nonempty raw value is diagnostically UNKNOWN,
not rough-date. Preserve raw spelling and counts; classification doesn't rewrite
Source identity/filtering or add semantics from a Source name. No new table or
assumed catalog endpoint is required. Mixed OTHER can't establish membership.


## 3.2.0 execution versus presentation

Metric formulas, time/selection and denominator semantics above are unchanged.
Standard report facts are server-formatted blocks; analysis_status COMPLETE denotes
fact execution/reconciliation scope only, never the accuracy of model-delivered
text. Final-output verification is separately NOT_INDEPENDENTLY_VERIFIED without
actual independent evidence. No Agent-issued overall report/test PASS.

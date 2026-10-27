# Metrics and time — v3 authoritative semantics

## Time
PST; completed means snapshot_date < current PST date, not proof all ETL
partitions arrived. WEEKDAYS = Monday–Friday, no holidays. T is latest eligible
actual date; P is nearest earlier eligible actual date, independently per Country.
Tuesday normally compares Monday to Friday; Monday's run compares Friday to
Thursday. Historical range applies to target dates; P may precede range start.
CALENDAR_DAYS is only an explicit historical/test override. Missing expected
workdays are disclosed; normal weekends are not missing workdays. Never call a
nonconsecutive pair “yesterday”. No per-day normalization of the 10% threshold.

Expected target = nearest weekday strictly before D. Target lag counts weekdays
T+1 through expected target. Lag >2 = STALE; 1–2 = DELAYED. Missing workdays between
P/T excludes both endpoints. Weekend runs show the same pair, not a new event.
Without persistent host state there is no claim of cross-run notification dedupe.

## Country
R_T=PD_T/Open_T; R_P=PD_P/Open_P. Relative=(R_T−R_P)/R_P;
point-change ratio=R_T−R_P; display pp multiplies by 100.
Use exact counts/cross-products for threshold, not displayed/rounded ratios.
Previous PD=0, positive Open in both periods and target PD>0 is zero-baseline
increase; both PD=0 is normal. Open=0 means no valid ratio, not zero baseline.
Negative counts or PD>Open are DATA_ERROR. Missing previous is INSUFFICIENT.

## Vendor
C_T(v)=PD_T(v)/CountryOpen_T; C_P(v)=PD_P(v)/CountryOpen_P;
delta_C=C_T−C_P. Compare exact numerator PD_T(v)*CountryOpen_P−PD_P(v)*CountryOpen_T.
Select positive numerators descending, Vendor Code ascending, first 10, never
stop at 80%. positive_share = positive numerator / sum(all positive numerators).
Coverage = selected positive / all positive, NOT fraction of net Country rise.
All Vendor signed contributions reconcile to Country point change; selected
Top-10 need not sum to it. SINGLE_VENDOR includes zero/negative contribution.

Vendor ratio uses Vendor Open, not Country Open or past-due-only rows. Current
aggregate open-line-count means rows in the current detail view; do not claim
identical ETL/Open semantics to historical snapshots without validation.
Current share denominator = all Country PD or explicitly filtered population.
Vendor ETA share = bucket PD / whole Vendor PD. Never silently change denominator
when applying an organization filter.

## ETA
Full union of both periods, positive/negative/zero. Empty Code/Source = UNKNOWN.
Code uppercase normalization; Source trim, preserve actual name. Missing period
bucket can be zero only after valid pair/full query. Error cannot become zero.
Delta=T−P; relative=Delta/P if P>0; otherwise null (zero-baseline increase or no
change). Decline to zero is −100%. SQL computes display-rank by abs(delta) DESC,
T DESC, value ASC. First 5 plus Other; Other sums T/P then calculates percentage.
Increase=sum(max(delta,0)); decrease=sum(min(delta,0)); net=increase+decrease.

Code and Source separately reconcile T/P to Vendor. Never add their deltas.
US joint-source counts reconcile to the US Code bucket, not the whole Vendor.
Reconcile exact integer counts; ratio comparisons use abs/relative tolerance
1e-9 on unrounded ratio units. v3 returns 18-scale Decimal; formatting is separate.
If dataset revision/watermark is not exposed, date pinning and reconciliation
reduce drift but do not prove a cross-query transaction snapshot.

## Status axes
business_status: NORMAL / TRIGGERED / ZERO_BASELINE_INCREASE / NOT_EVALUABLE.
data_status: OK / DELAYED / STALE / INSUFFICIENT / ACCESS_DENIED / DATA_ERROR.
analysis_status: COMPLETE / PARTIAL / UNAVAILABLE / NOT_REQUIRED.
drilldown_reason: THRESHOLD_MET / TEST_FORCED / USER_REQUESTED / NONE.
Normal daily stale/invalid/uncomparable pairs don't enter valid alert drilldown;
test can inspect valid stale pairs with warning. Drilldown failure never erases
a separately verified Country business trigger.

## Interpretation
Risk codes are business signals, not proven causes or personal blame. Decreasing
PD with rising Country contribution can be denominator shrinkage. Aggregate
changes cannot show individual PO migration. Owner/contacts/PO are CURRENT only.

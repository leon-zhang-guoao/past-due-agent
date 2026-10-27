# Table display contract v3.1

Machine source: table-display-contract.edn. Request language selects zh/en labels;
identical field IDs/order apply to standalone and full reports. English start test
uses English throughout, including diagnosis, warning and check text.

Dates YYYY-MM-DD; capture timestamps America/Los_Angeles with actual PST/PDT and
UTC offset from trusted runtime (not a hardcoded PST suffix). Integers have no
separators; signed deltas +12/−5/0; ratios 2 decimal %, contribution changes signed
pp (FOUR decimal places). Ratios retain TWO decimals. Previous→target order is fixed. Null/zero-baseline/N/A/error are distinct.
Non-PAIR previous=1970-01-01 and change placeholders are omitted, never a baseline.

Server BUCKET ranks 1..5 and OTHER rank6 are rendered as supplied. Other blank
eta-value displays 其他/Other, not UNKNOWN. Preserve raw Source spelling; apply
AutoUS/AUTO US alias only to bilingual diagnosis. Never reconstruct remainder.

## module: country_metrics
```fields
country_code
business_status
data_status
target_date
previous_date
gap_days
past_due_pair
open_pair
ratio_pair
relative_change
point_change
analysis_status
```
Country 指标 / Country metrics

## module: vendor_summary
```fields
rank
vendor_code
vendor_name
past_due_pair
net_change
country_delta
positive_share
eta_code_changes_brief
eta_source_changes_brief
business_diagnosis
analysis_status
```
Vendor 汇总 / Vendor Summary

## module: eta_code_changes
```fields
eta_value
previous_count
target_count
net_change
relative_change
contribution_change
```
ETA Code 两期变化 / Eta Code Changes

## module: eta_source_changes
```fields
eta_value
previous_count
target_count
net_change
relative_change
contribution_change
```
ETA Source 两期变化 / Eta Source Changes

## module: trigger_events
```fields
country_code
target_date
previous_date
calendar_mode
relative_change
business_status
```
历史触发事件 / Trigger Events

## module: vendor_metrics
```fields
vendor_code
past_due_pair
open_pair
vendor_ratio_pair
country_contribution_pair
analysis_status
```
Vendor 指标 / Vendor Metrics

## module: us_source_changes
```fields
vendor_code
eta_code
eta_source
previous_count
target_count
net_change
rule_interpretation
```
US 联合来源变化 / Us Source Changes

## module: current_owners
```fields
country_code
vendor_code
buyer
primary
manager
queried_at_utc
```
当前负责人 / Current Owners

## module: notification_recipients
```fields
country_code
vendor_code
eta_codes
recommended_to
recommended_cc
routing_rule
recipient_status
evidence
```
建议联系人 / Notification Recipients

## module: current_po_lines
```fields
country_code
vendor_code
po_number
po_line_number
eta_code
eta_source
past_due_days
buyer
primary
manager
query_status
```
当前 PO 明细 / Current Po Lines

## module: completion
```fields
country_code
check
verified
expected
result
reason
```
完成检查 / Completion

## module: test_checks
```fields
country_code
check
result
exercised_scope
reason
```
测试结果 / Test Checks

## module: generic_analysis
```fields
declared_dimensions
declared_metrics
denominator_scope
time_scope
evidence_status
```
分析结果 / Analysis Results

## module: business_diagnosis
```fields
vendor_code
verified_fact
business_signal
verification
limitations
```
业务诊断 / Business Diagnosis

## module: country_summary
```fields
country_code
selected_coverage
signals
next_checks
limitations
```
Country 小结 / Country Summary

## Definitive compositions
```clojure
{:daily_full [:run_info :country_metrics :vendor_summary :vendor_metrics :eta_code_changes :eta_source_changes :us_source_changes :business_diagnosis :country_summary :cross_country_summary :completion]
  :historical_full [:run_info :trigger_events :vendor_summary :vendor_metrics :eta_code_changes :eta_source_changes :us_source_changes :business_diagnosis :country_summary :cross_country_summary :completion]
  :historical_events_only [:trigger_events]
  :test_full [:run_info :country_metrics :vendor_summary :vendor_metrics :eta_code_changes :eta_source_changes :us_source_changes :business_diagnosis :country_summary :cross_country_summary :completion :test_checks]
  :interactive [:generic_analysis]}
```

vendor_metrics → eta_code_changes → eta_source_changes → us_source_changes →
business_diagnosis repeats for EVERY selected Vendor in server rank order.
US applies to every Country, conditional on dimension=us check required=true;
otherwise show explicit N/A. Other module failures stay in place with a reason.
country_summary is per Country; completion/test checks cover all requested scope.
Optional current_owners / notification_recipients / current_po_lines are appended
only on request. Six distinct logical facts applies ONLY to interactive questions;
discovery/materialization/paging excluded, retries same slot. No six-request cap
may stop mandatory report presets; bound broad optional/history scope explicitly.
Completion uses facts/checks, not six repeated module-status strings. Display one
precise failure/N/A at its affected scope instead of redundant boilerplate.

generic_analysis expands declared dimension/metric fields from explore FACT rows,
preserving returned ratio/share denominators and source units; CONTROL is not a
data row. No non-PAIR previous dates/metrics or invented metrics.

## Timestamp provenance and compatibility
The report header shows available report-fact/queried-at-local (or explore-fact
equivalent) and business-timezone IANA, with actual PST/PDT + offset only from a
trusted corresponding instant/offset. Never infer capture instant from tool delivery
time. If ambiguous/unavailable, preserve the returned timestamp and label offset
UNVERIFIED. Legacy current_owners.queried_at_utc retains its UTC format and meaning;
it is NOT renamed, reinterpreted or populated with queried-at-local.

## EDN key-type invariant
Module IDs and composition IDs/references are EDN keywords (for example
:business_diagnosis and :country_summary), never quoted strings beginning with
a colon. Validation must check isinstance(key, edn_io.Keyword), not only map
membership/equality: Keyword subclasses str, so equality alone can hide this bug.
When replacing a plain-string key, pop it before inserting K(":module_id").

## Header and cross-Country modules
run_info precedes the Country metrics/events: mode, verified business time,
requested language/scope and conclusion. cross_country_summary follows all Country
summaries and precedes completion: compare verified Country signals and limits,
never combine Vendor ranks. Default Country order is US then CA; other explicitly
requested Countries keep request order. No selected Vendors means repeated Vendor
modules are explicitly not applicable, not missing or fabricated.

## module: run_info
```fields
mode
business_time
language
scope
conclusion
```
运行信息、模式与结论 / Run Information, Mode and Conclusion

## module: cross_country_summary
```fields
countries
signals
limitations
```
跨 Country 总结 / Cross-Country Summary

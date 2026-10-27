# Table display contract v3
Machine source: table-display-contract.edn. Match request language; field IDs and order stay stable.
Dates YYYY-MM-DD PST; integers no separators; signed deltas +12/−5/0; ratios 2 decimals %, contributions signed pp. Relative zero baseline is text, not Infinity. Failures/unknown/not-applicable differ from zero.
Generic module expands only declared dimension and metric columns using their source units/formats; it does not invent metrics.
Every standalone module uses the same columns as a full report.

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
Vendor Top 10 / Vendor Top 10

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
分析结果 / Generic Analysis

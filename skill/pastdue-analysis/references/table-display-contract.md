# Display contract — 3.1.2

Readable projection of `table-display-contract.edn`; EDN owns field IDs/order/bindings.
Use the literal bilingual skeleton in `report-format.md` for standard presets.
Interactive questions compose relevant modules only.

## module: country_metrics
```fields
country_code
business_status
data_status
previous_date
target_date
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
analysis_status
```
Vendor 汇总 / Vendor Summary

Record selector: `{':record-type': 'VENDOR_METRICS', ':same-scope-required': True}`. Same scope/Country/Vendor/date identity is mandatory.

## module: eta_code_changes
```fields
eta_value
previous_count
target_count
net_change
relative_change
contribution_change
```
ETA Code 两期变化 / ETA Code Changes

Record selector: `{':record-type': 'ETA_CODE_SUMMARY', ':same-scope-required': True, ':dimension': 'code'}`. Same scope/Country/Vendor/date identity is mandatory.

## module: eta_source_changes
```fields
eta_value
previous_count
target_count
net_change
relative_change
contribution_change
```
ETA Source 两期变化 / ETA Source Changes

Record selector: `{':record-type': 'ETA_SOURCE_SUMMARY', ':same-scope-required': True, ':dimension': 'source'}`. Same scope/Country/Vendor/date identity is mandatory.

## module: trigger_events
```fields
country_code
previous_date
target_date
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

Record selector: `{':record-type': 'VENDOR_METRICS', ':same-scope-required': True}`. Same scope/Country/Vendor/date identity is mandatory.

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
US 联合来源变化 / US joint Source Changes

Record selector: `{':record-type': 'US_SOURCE_SUMMARY', ':same-scope-required': True, ':dimension': 'us'}`. Same scope/Country/Vendor/date identity is mandatory.

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

Record selector: `{':record-type': 'VENDOR_METRICS', ':same-scope-required': True}`. Same scope/Country/Vendor/date identity is mandatory.

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

## module: eta_totals
```fields
dimension
increase_total
decrease_total
net_change
evidence_status
```
全桶变化汇总 / All-bucket change totals

Record selector: `{':record-type': 'VENDOR_CHECK'}`. Same scope/Country/Vendor/date identity is mandatory.

## Layout and evidence
Standard hierarchy: run → Country table → each Country summary and repeated Vendor blocks → Country conclusion → cross-Country → completion → test result.
Vendor overview never substitutes for repeated Vendor blocks. VENDOR_METRICS supplies Vendor totals/rates/ranks; ETA summaries supply bucket counts; VENDOR_CHECK supplies signed totals.
Other is a server aggregate with row-kind OTHER, not UNKNOWN or an invented zero remainder. Unknown, failed, not queried and not applicable are distinct.
Source coverage UNKNOWN alone does not downgrade reconciled evidence. Never claim client raw-bucket audit without reading raw buckets.

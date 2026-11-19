# Findings

- 已有current-v2支持非空正Int64 Vendor集合、CURRENT明细、Code×Source及覆盖/分页；无需再造批量度量。
- 已有report/explore路径支持快照、对比、排名，不适合替代用户明确要求的实时默认。
- 已知字段：detail_view.country_code/from_acct_no/vendor_name/is_pastdue/eta_code/source；
  vendor_master_mapping_view没有已证实的名称字段，名称识别只使用可读CURRENT明细。
- 本地结构基线41个resolver；新增概况/identity后43个，原有执行配置逐项对比不变。
- 本地合成数据：CA Open4/PD3，与大量历史快照值不同，可检出误用snapshot；有0PD、
  无可读数据、同名跨Country、引号、重复alias、null编号、分页和完整batch组合用例。
- 本环境没有可调用Atlas/Builder；源profile、JDBC22.8执行、实际角色/RLS、源延迟均未执行。

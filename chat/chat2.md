[TEST] PastDue 测试报告
运行信息与结论
模式：开始测试，强制执行适用的完整 Vendor 下钻；真实业务状态保留，TEST_FORCED。
Country：US、CA
Calendar：WEEKDAYS
实际配对：2026-10-23 → 2026-10-26
业务时区：America/Los_Angeles
queried-at-local：返回 2026-10-27T10:09:27Z（US），2026-10-27T10:09:34Z（CA）；返回值带 Z，但与业务时区字段的对应 PST/PDT 偏移未由可信运行时确认，故偏移标记为 UNVERIFIED。
Metric version：3.1.0
数据状态：两个 Country 的 Monitor 与 Report 均返回 OK；Report 行数分别匹配预期记录数。
测试结论：PARTIAL／部分完成
业务与必需汇总检查已返回并通过；但 Atlas 对集合完整性的提示为 source-completeness=unknown，因此不能把本次结果声明为完全验证的 PASS。
Country 指标
Country	业务状态	数据状态	逾期上期→本期	Open 上期→本期	逾期率上期→本期	相对变化	测试分析
US	NORMAL	OK	40,392→37,108	334,700→324,263	12.07%→11.44%	−5.17%	TEST_FORCED；未达到 10% 上升阈值
CA	NORMAL	OK	5,845→5,783	47,181→46,665	12.39%→12.39%	+0.03%	TEST_FORCED；未达到 10% 上升阈值
说明：正常监控逻辑下两个 Country 都不会触发业务下钻；本次下钻由测试命令强制执行，不代表业务触发。

US
Country 控制：scope-status=OK
逾期：40,392→37,108，净变化 −3,284
Open：334,700→324,263
Country 逾期率：12.07%→11.44%
Selected Vendors：10
Selected positive coverage：由服务器返回为各 Vendor 选择覆盖字段；本次 Vendor 明细与 Code/Source/US 检查均返回。
US joint-source：适用，dimension=us / required=true；以下 US 来源均来自已验证的 Code=US × Source 联合事实。
US Vendor 汇总与诊断
Rank	Vendor Code / Vendor	PD 上期→本期	Open 上期→本期	Vendor 逾期率	Country 贡献变化	主要 Code 变化	主要 Source 变化
1	13788 / BELKIN INTERNATIONAL INC	73→387	1,376→1,382	9.30%→31.55%	+0.1345 pp	SH +314；DD −6；US −1；NS +1；NR 0；Other 0	EDI870 +318；Carrier API −6；Vendor Report −2；EDI856 −2；AUTO US −1；Other +1
2	64036 / PALO ALTO NETWORKS,INC.	7→75	7,230→7,185	3.07%→4.29%	+0.0950 pp	US +68；SC +10；NR +8；RQ 0；Other 0	AUTO US +68；Vendor Report +16；Vendor Web Site +1；AUTO NR +1；Other +4
3	75432 / DELL MARKETING USA LP	41→94	12,348→10,160	0.33%→0.93%	+0.0289 pp	SH +47；NR +6；DD 0；PE 0；RQ 0；Other 0	EDI856 +47；EDI855 +6；Carrier API 0；API Order 0；AUTO US 0；Other 0
4	30765 / LG ELECTRONICS USA	153→202	1,137→1,085	13.46%→18.62%	+0.0623 pp	SH +15；RQ 0；DD 0；US 0；Other 0	Vendor Report +26；AUTO NR +23；BUYER 0；Carrier API 0；AUTO US 0；Other +1
5	64342 / F5 NETWORKS, INC	573→604	2,564→2,546	22.35%→23.72%	+0.1863 pp	US +9；NR +9；RQ +2；SH 0；SC 0	AUTO US +20；Vendor Report +12；Vendor Email −3；BUYER +2；AUTO NR 0；Other +2
6	56934 / ADD-ON-COMPUTER PERIPHERALS, LLC	489→516	1,096→1,080	44.62%→47.78%	+0.1591 pp	SH +10；NR +2；RQ −2；US −2；Other 0	Carrier API +46；EDI856 +8；Email AI +4；AUTO NR −2；BUYER −27；Other −2
7	62105 / HP INC.	503→528	754→751	66.71%→70.31%	+0.1629 pp	NR +27；DD −2；SH 0；SC 0；Other 0	EDI855 +27；Carrier API −2；API Order 0；Vendor Report 0；EDI856 0；Other 0
8	84018 / PROGRESS SOFTWARE CORPORATION	1→29	139→142	0.72%→20.42%	+0.0089 pp	RQ +28；SC 0；Other 0	BUYER +29；AUTOBUY −1；Vendor Invoice 0；Other 0
9	75256 / NINJAONE LLC	413→424	960→969	43.02%→43.76%	+0.1308 pp	SH +1；RQ 0；US 0；NR 0；PE 0；Other 0	Vendor Email +10；BUYER +1；AUTO US 0；AUTO NR 0；Vendor Invoice 0；Other +1
10	11001 / MSI COMPUTER CORP.	0→23	99→102	0%→22.55%	+0.0071 pp	SH +23；RQ +1；SC 0；Other 0	AUTO US +22；Vendor Report +1；BUYER 0；Vendor Email 0；Vendor Invoice 0
US 诊断
BELKIN INTERNATIONAL INC：主要增长来自 Code SH 和 Source EDI870。业务信号为“未及时送达／收货”风险（ETA-SH-DELIVERY-RECEIPT-DELAY，DELIVERY_OR_RECEIPT_DELAY）；应核查运输送达及收货进度，不单独归咎 Vendor 或仓库。其联合 Code=US 中可见 AUTO US，适用 Vendor ETA past-due 信号（ETA-US-AUTOUS-OVERDUE，VENDOR_ETA_OVERDUE）。
PALO ALTO NETWORKS,INC.：主要增长为 Code US、Source AUTO US，联合事实支持 Vendor 提供 ETA 已 past due（ETA-US-AUTOUS-OVERDUE）；核查原 ETA 有效性与下一次更新。
DELL MARKETING USA LP：主要增长为 SH/EDI856，适用送达或收货延迟风险；应核查运输与收货进度。Code=US 联合来源未形成正向增长。
LG ELECTRONICS USA：主要增长集中在 SH 与 Source Vendor Report、AUTO NR；SH 适用送达／收货延迟风险。AUTO NR 本身不是 US 联合事实中的 AutoUS 规则证据。
F5 NETWORKS, INC：Code US 增长，联合 Source AUTO US 增长 20，适用 Vendor ETA past-due 信号；同时 NR 也上升，需分别核查 ETA 更新和缺失预计发货日期。
ADD-ON-COMPUTER PERIPHERALS, LLC：Code SH 增长，Source Carrier API 增长；适用送达／收货延迟风险。Code=US 的联合来源以 Vendor Report 为主，不能按 AutoUS 解释。
HP INC.：主要为 Code NR 和 Source EDI855 增长；适用“Vendor 已收到订单但未提供预计发货时间”信号（ETA-NR-MISSING-SHIP-DATE，MISSING_ESTIMATED_SHIP_DATE）。
PROGRESS SOFTWARE CORPORATION：主要为 Code RQ 增长，适用“未收到 Vendor ACK”信号（ETA-RQ-MISSING-ACK，MISSING_VENDOR_ACK）；应核查订单接收与 ACK 反馈。
NINJAONE LLC：总体小幅上升，主要 Code SH、Source Vendor Email；适用送达／收货延迟风险。联合 AUTO US 数量未增加。
MSI COMPUTER CORP.：新增逾期主要为 Code SH，Source AUTO US；Code=US 联合事实显示无目标期数量，故 AutoUS 规则不作为本 Vendor 的主要解释。
CA
Country 控制：scope-status=OK
逾期：5,845→5,783，净变化 −62
Open：47,181→46,665
Country 逾期率：12.3885%→12.3926%
Selected Vendors：10
US joint-source：对本次选中 Vendor 均为 required=false，因此 US 联合来源解释不适用，不对 CA 的边际 Source 事实套用 US 规则。
CA Vendor 汇总与诊断
Rank	Vendor Code / Vendor	PD 上期→本期	Open 上期→本期	Vendor 逾期率	Country 贡献变化	主要 Code 变化	主要 Source 变化
1	2655 / EPSON CANADA LTD.	60→127	794→809	7.56%→15.70%	+0.2722 pp	SH +67；NR 0；NS 0；RQ 0；SC 0；Other 0	EDI856 +67；Vendor Report 0；EDI855 0；AUTO NR 0；AUTO US 0；Other +3
2	28538 / LENOVO CANADA - COMMERCIAL	105→150	1,358→1,279	7.73%→11.73%	+0.3214 pp	SH 0；PE 0；DD 0；NR 0；NS 0；Other +3	Vendor Report +46；Vendor Web Site −1；BUYER 0；Carrier API 0；Vendor Email 0；Other +5
3	1097 / TRIPP LITE	200→233	877→851	22.81%→27.38%	+0.4993 pp	US −14；SH +? Code target/previous 157→203? server summary shows SH 36→22, net −14; NR 0；RQ 0；SC 0；Other 0	AUTO US +46；EDI856 −13；Vendor Email 0；EDI855 0；Other 0
4	36464 / SCALE COMPUTING, INC.	11→37	37→37	29.73%→100.00%	+0.0793 pp	US +26；NR 0；Other 0	AUTO US +26；Vendor Email 0
5	23311 / CLOVER TECHNOLOGIES CANADA ULC	181→204	514→442	35.21%→46.15%	+0.4372 pp	NR +5；US +5；SH 0；DD 0；NS 0；Other +1	EDI855 +9；AUTO US +5；Vendor Report +5；Vendor Email 0；Carrier API 0；Other +2
6	40378 / SKULLCANDY INC.	5→24	35→32	14.29%→75.00%	+0.0514 pp	RQ +2；US +2；NR 0；SH 0；SC 0	Vendor Email +12；BUYER +5；AUTO US +2；AUTO NR 0；Vendor Report 0
7	37027 / CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	151→161	2,545→2,541	5.93%→6.34%	+0.3450 pp	NR +2；RQ 0；US 0；SC 0；Other +1	Vendor Email +10；AUTO US 0；BUYER 0；Vendor Invoice 0；Vendor Report 0；Other +1
8	5942 / HEWLETT PACKARD - ESS	48→59	1,971→1,691	2.44%→3.49%	+0.1264 pp	NR +1；US +1；Other +3	API Order +9；AUTO US +1；EDI855 +1；Vendor Report 0；API ASN 0；Other +3
9	2659 / HP - IPG	28→39	266→321	10.53%→12.15%	+0.0836 pp	NR +7；DD +1；SH 0；NS 0；RQ 0；Other +2	API Order +7；Vendor Web Site +3；Carrier API +1；Vendor Report 0；AUTO US 0；Other +2
10	39379 / ZSCALER INC	126→150	578→573	21.80%→26.18%	+0.3214 pp	RQ 0；NR 0；US 0；SC 0；Other 0	Vendor Email +24；AUTO US 0；BUYER 0；Vendor Invoice 0；Vendor Report 0
CA 诊断
EPSON CANADA LTD.：Code SH、Source EDI856 增长，适用送达／收货延迟风险；US 联合来源不适用。
LENOVO CANADA - COMMERCIAL：Vendor 逾期率明显上升，Source Vendor Report 增长；Code 汇总未显示单一 Code 的大幅增长，建议核查 Vendor 报告与实际交付状态。
TRIPP LITE：Vendor PD 上升但 Country 贡献增加主要来自 Source AUTO US；由于 CA 的 US joint check 为非适用，不能套用 ETA-US-AUTOUS-OVERDUE。Code/Source 事实可支持进一步核查，但不能仅凭边际 Source 判定 US 业务含义。
SCALE COMPUTING, INC.：Code US、Source AUTO US 增长，但 CA 中 US joint 维度不适用；保留为已验证的 Code/Source 变化，不作 US 规则诊断。
CLOVER TECHNOLOGIES CANADA ULC：Code NR 与 Source EDI855 增长，适用缺失预计发货时间风险；同时存在 Code US 增长，但没有适用的联合 US 来源检查。
SKULLCANDY INC.：Code RQ 增长，适用缺失 Vendor ACK 风险；应核查订单接收与 ACK 反馈。
CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.：Code NR 小幅增加，Source Vendor Email 增加；适用缺失预计发货时间风险，需核查 ETA 补充进度。
HEWLETT PACKARD - ESS：Code NR 与 Source API Order 增长，适用缺失预计发货时间风险。
HP - IPG：Code NR 增长，Source API Order 增长；适用缺失预计发货时间风险。Code DD 也有小幅增加，需核查收货登记。
ZSCALER INC：PD 上升主要伴随 Source Vendor Email 增长；Code 汇总无显著净变化，Source 变化不能单独证明具体原因，建议核查 Vendor ETA 更新及订单状态。
完成检查
Country	检查	结果
US	Country control、pair、scope-status	已验证，OK
US	Vendor selection / rank	已返回 10 个 Vendor，按服务器 rank 顺序
US	Code / Source summaries	已返回；counts-match=true
US	Vendor checks	Code、Source、required US joint checks 已返回
CA	Country control、pair、scope-status	已验证，OK
CA	Vendor selection / rank	已返回 10 个 Vendor，按服务器 rank 顺序
CA	Code / Source summaries	已返回；counts-match=true
CA	Vendor checks	Code、Source 已返回；US joint dimension 明确 required=false
两国	Report 行完整性	US 预期 168 行、实际 168 行；CA 预期 159 行、实际 159 行
两国	集合源完整性	UNVERIFIED：Atlas 返回 result/collection-coverage-unknown
测试状态
业务状态：US、CA 均 NORMAL，均未达到真实监控触发阈值。
数据状态：US、CA 均 OK。
分析状态：已执行强制测试下钻；必要的服务器检查已返回。
最终测试结果：PARTIAL，原因是集合完整性提示为未知，不能在没有额外可信完整性证据的情况下声明 PASS。
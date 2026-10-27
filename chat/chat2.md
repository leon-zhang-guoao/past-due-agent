[TEST] PastDue 日报
1. 运行信息、模式与结论
模式	业务时间	输出语言	分析范围	结论
TEST（TEST_FORCED）	America/Los_Angeles；2026-10-27 04:36:13 PDT（UTC−07:00）	中文	US、CA；WEEKDAYS；2026-10-23 → 2026-10-26；POSITIVE_TOP_N=10	业务状态均为 NORMAL；测试强制完成下钻。数据与服务器核对通过，测试 PASS。
Atlas 环境版本：11；指标版本：3.1.0；报告契约：3.1.3。
queried-at-local 原始返回值为 2026-10-27T11:36:13Z（US）及 2026-10-27T11:36:24Z（CA）；对应业务时间为 PDT（UTC−07:00）。
选择范围：每个 Country 的正向贡献 Top 10，排名按服务器 contribution-rank。
报告查询均返回完整预期行数：US 168 行、CA 159 行；COUNTRY_CONTROL、Vendor 指标、Code/Source 汇总及适用的 US 联合事实均已核验。
Atlas 返回 source-completeness=unknown 提示；但本次传输、scope-key、预期行数、选中集合、Other 桶及服务器检查均通过，因此该项为警告，不影响本次 PASS。
2. Country 指标
Country	业务状态	数据状态	上期日期	本期日期	日历间隔	逾期 上期→本期	Open 上期→本期	逾期率 上期→本期	相对变化	百分点变化	分析状态
US	NORMAL	OK	2026-10-23	2026-10-26	3	40392→37108	334700→324263	12.07%→11.44%	−5.17%	−0.6243 pp	COMPLETE；TEST_FORCED
CA	NORMAL	OK	2026-10-23	2026-10-26	3	5845→5783	47181→46665	12.39%→12.39%	+0.03%	+0.0041 pp	COMPLETE；TEST_FORCED
US 逾期数量和逾期率均下降；CA 数量略降，但由于 Open 同时下降，逾期率基本持平。两国均未达到 10% 相对逾期率上升阈值；下钻原因均为 TEST_FORCED，不是业务触发。

3. US — 2026-10-23 → 2026-10-26
实际间隔 3 个日历日，WEEKDAYS 口径；业务状态 NORMAL，本次因测试模式强制下钻。分母为 Country Open，Vendor 选择为正向贡献 Top 10。

Vendor 汇总
排名	Vendor Code	Vendor 名称	逾期 上期→本期	净变化	Country 贡献变化	正向贡献占比	分析状态
1	13788	BELKIN INTERNATIONAL INC	128→436	+308	+0.0962 pp	22.53%	COMPLETE
2	64036	PALO ALTO NETWORKS,INC.	222→308	+86	+0.0287 pp	6.71%	COMPLETE
3	75432	DELL MARKETING USA LP	41→94	+53	+0.0167 pp	3.92%	COMPLETE
4	30765	LG ELECTRONICS USA	153→202	+49	+0.0166 pp	3.88%	COMPLETE
5	64342	F5 NETWORKS, INC	573→604	+31	+0.0151 pp	3.53%	COMPLETE
6	56934	ADD-ON-COMPUTER PERIPHERALS, LLC	489→516	+27	+0.0130 pp	3.05%	COMPLETE
7	62105	HP INC.	503→528	+25	+0.0125 pp	2.94%	COMPLETE
8	84018	PROGRESS SOFTWARE CORPORATION	1→29	+28	+0.0086 pp	2.02%	COMPLETE
9	75256	NINJAONE LLC	413→424	+11	+0.0074 pp	1.72%	COMPLETE
10	11001	MSI COMPUTER CORP.	0→23	+23	+0.0071 pp	1.66%	COMPLETE
US Vendor 总结分析
所选正向贡献覆盖率：51.98%。
风险与改善：US Country 总逾期从 40392 降至 37108，但所选正向贡献 Vendor 仍全部增加；其中 BELKIN INTERNATIONAL INC 增加 +308，占本次正向贡献的主要部分。Country 层面的改善来自其他未选 Vendor 的下降抵消，不能将 Top 10 的增加直接解释为整个 Country 上升。
ETA 业务信号：
BELKIN INTERNATIONAL INC：Code SH 387→73（−314），DD 5→11；Code=US 23→24（−1）。已核验的 Code=US 联合来源中，AUTO US 19→18（−1），对应 ETA-US-AUTOUS-OVERDUE，风险为 Vendor 提供的 ETA 已 past due；建议核查原 ETA 有效性及后续更新。
PALO ALTO NETWORKS,INC.：Code US 75→7（+68），联合来源 AUTO US 7→75（+68），对应 ETA-US-AUTOUS-OVERDUE；这是该 Vendor 增长的主要已核验 ETA 路径。
F5 NETWORKS, INC：Code US 207→203（+20），联合来源 AUTO US 183→203（+20），同样显示 Vendor-provided ETA past due 风险。
其他 Vendor 的主要增加集中于 NR、SH、RQ 或 DD；这些分别对应缺少预计发货时间、延迟送达/收货、未收到 ACK、以及仓库收货登记延迟等业务风险。所有解释均基于已核验 Code/Source 汇总。
下一步核查：优先核查 BELKIN INTERNATIONAL INC、PALO ALTO NETWORKS,INC.、F5 NETWORKS, INC 的原始 ETA、更新记录及实际到货/收货状态。
限制：选中 Vendor 仅覆盖 51.98% 的正向贡献；结论不扩大为整个 US。Other 是服务器聚合桶，不能进一步推断其隐藏来源。
US Vendor 详细事实
下表使用同一份 VENDOR_METRICS、ETA_CODE_SUMMARY、ETA_SOURCE_SUMMARY 和 VENDOR_CHECK 绑定结果。每个 Code/Source 列表均为服务器 Top-5+Other；格式为“上期→本期（净变化）”。

Rank / Vendor	Vendor 指标：逾期；Open；Vendor 逾期率；Country 贡献	ETA Code 两期变化	ETA Source 两期变化	全桶核对
1 / 13788 BELKIN INTERNATIONAL INC	128→436；1382→1376；9.30%→31.55%；0.0382%→0.1345%	SH 73→387 (+314)；DD 11→5 (−6)；US 24→23 (−1)；NS 1→2 (+1)；NR 18→18 (0)；Other 1→1 (0)	EDI870 74→392 (+318)；Carrier API 11→5 (−6)；Vendor Report 17→15 (−2)；EDI856 3→1 (−2)；AUTO US 19→18 (−1)；Other 4→5 (+1)	Code +315/−7=+308；Source +319/−11=+308；US 联合 24→23，核对通过
2 / 64036 PALO ALTO NETWORKS,INC.	222→308；7185→7230；3.07%→4.29%；0.0663%→0.09498%	US 7→75 (+68)；SC 66→76 (+10)；NR 149→157 (+8)；RQ 0→0；Other 0→0	AUTO US 7→75 (+68)；Vendor Report 142→158 (+16)；Vendor Web Site 59→60 (+1)；AUTO NR 14→15 (+1)；AUTOBUY 0→0；Other 0→0	Code +86/0=+86；Source +86/0=+86；US 联合 7→75，核对通过
3 / 75432 DELL MARKETING USA LP	41→94；10160→12348；0.33%→0.93%；0.0122%→0.02899%	SH 3→50 (+47)；NR 18→24 (+6)；DD 19→19 (0)；PE 1→1；RQ 0→0；Other 0→0	EDI856 3→50 (+47)；EDI855 19→25 (+6)；Carrier API 19→19；API Order 0→0；AUTO US 0→0；Other 0→0	Code +53；Source +53；US 不适用（required=false）
4 / 30765 LG ELECTRONICS USA	153→202；1085→1137；13.46%→18.62%；0.0457%→0.0623%	NR 97→131 (+34)；SH 29→44 (+15)；RQ 22→22；DD 3→3；US 2→2；Other 0→0	Vendor Report 56→82 (+26)；AUTO NR 70→93 (+23)；BUYER 21→21；Carrier API 3→3；AUTO US 2→2；Other 1→1	Code +49；Source +49；US 联合 2→2，核对通过
5 / 64342 F5 NETWORKS, INC	573→604；2546→2564；22.35%→23.72%；0.1712%→0.1863%	US 187→207 (+20)；NR 318→327 (+9)；RQ 45→47 (+2)；SH 23→23；SC 0→0	AUTO US 183→203 (+20)；Vendor Report 213→225 (+12)；Vendor Email 49→46 (−3)；BUYER 44→46 (+2)；AUTO NR 51→51；Other 33→33	Code +31；Source +34/−3=+31；US 联合 187→207，核对通过
6 / 56934 ADD-ON-COMPUTER PERIPHERALS, LLC	489→516；1080→1096；44.62%→47.78%；0.1461%→0.1591%	DD 96→142 (+46)；RQ 178→149 (−29)；SH 8→18 (+10)；NR 177→179 (+2)；US 30→28 (−2)；Other 0→0	Carrier API 95→141 (+46)；BUYER 168→141 (−27)；EDI856 25→33 (+8)；Email AI 7→11 (+4)；AUTO NR 160→158 (−2)；Other 34→32 (−2)	Code +58/−31=+27；Source +58/−31=+27；US 联合 30→28，核对通过
7 / 62105 HP INC.	503→528；751→754；66.71%→70.31%；0.1503%→0.1628%	NR 492→519 (+27)；DD 4→2 (−2)；SH 7→7；SC 0→0；Other 无	EDI855 480→507 (+27)；Carrier API 4→2 (−2)；API Order 14→14；Vendor Report 5→5；EDI856 0→0	Code +27/−2=+25；Source +27/−2=+25；US 不适用
8 / 84018 PROGRESS SOFTWARE CORPORATION	1→29；142→139；0.72%→20.42%；0.0003%→0.0089%	RQ 1→29 (+28)；SC 0→0	BUYER 0→29 (+29)；AUTOBUY 1→0 (−1)；Vendor Invoice 0→0	Code +28；Source +29/−1=+28；US 不适用
9 / 75256 NINJAONE LLC	413→424；969→960；43.02%→43.76%；0.1234%→0.1308%	SH 320→321 (+1)；RQ 86→86；NR 5→5；PE 2→2；Other 0→0	Vendor Email 38→48 (+10)；BUYER 318→319 (+1)；AUTO US 47→47；AUTO NR 5→5；Vendor Invoice 3→3；Other 2→2	Code +11；Source +11；US 联合 86→86，核对通过
10 / 11001 MSI COMPUTER CORP.	0→23；102→99；0.00%→22.55%；0→0.0068%	US 0→22 (+22)；SH 0→1 (+1)；RQ/SC 0→0	AUTO US 0→22 (+22)；Vendor Report 0→1 (+1)；其他来源均为 0	Code +23；Source +23；US 联合 0→22，ETA-US-AUTOUS-OVERDUE
4. CA — 2026-10-23 → 2026-10-26
实际间隔 3 个日历日，WEEKDAYS 口径；业务状态 NORMAL，本次因测试模式强制下钻。分母为 Country Open，Vendor 选择为正向贡献 Top 10。

Vendor 汇总
排名	Vendor Code	Vendor 名称	逾期 上期→本期	净变化	Country 贡献变化	正向贡献占比	分析状态
1	2655	EPSON CANADA LTD.	60→127	+67	+0.1449 pp	15.82%	COMPLETE
2	28538	LENOVO CANADA - COMMERCIAL	105→150	+45	+0.0982 pp	10.79%	COMPLETE
3	1097	TRIPP LITE	200→233	+33	+0.0754 pp	8.23%	COMPLETE
4	36464	SCALE COMPUTING, INC.	11→37	+26	+0.0559 pp	6.11%	COMPLETE
5	39379	ZSCALER INC	126→150	+24	+0.0544 pp	5.93%	COMPLETE
6	23311	CLOVER TECHNOLOGIES CANADA ULC	181→204	+23	+0.0535 pp	5.84%	COMPLETE
7	40378	SKULLCANDY INC.	5→24	+19	+0.0408 pp	4.46%	COMPLETE
8	37027	CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	151→161	+10	+0.0250 pp	2.72%	COMPLETE
9	5942	HEWLETT PACKARD - ESS	48→59	+11	+0.0247 pp	2.69%	COMPLETE
10	2659	HP - IPG	28→39	+11	+0.0242 pp	2.64%	COMPLETE
CA Vendor 总结分析
所选正向贡献覆盖率：65.24%。
风险与改善：CA Country 逾期数量从 5845 降至 5783，但 Open 从 47181 降至 46665，导致逾期率略升。所选 Vendor 全部为正向贡献，主要增加来自 EPSON CANADA LTD.（+67）、LENOVO CANADA - COMMERCIAL（+45）和 TRIPP LITE（+33）。
ETA 业务信号：
EPSON CANADA LTD. 的 Code SH 从 56 增至 123，符合 ETA-SH-DELIVERY-RECEIPT-DELAY，建议核查运输送达及收货登记。
LENOVO CANADA - COMMERCIAL 的 Code SH 从 77 增至 122，且 Source Vendor Report 从 78 增至 124，建议核查送达/收货及 Vendor 报告更新。
TRIPP LITE 的 Code US 从 161 增至 208；已核验联合来源 AUTO US 从 157 增至 203，符合 ETA-US-AUTOUS-OVERDUE，建议核查原始 Vendor ETA 及后续更新。
SCALE COMPUTING, INC. 的 Code US 6→32，联合来源 AUTO US 6→32，同样对应 Vendor-provided ETA past due 风险。
CLOVER TECHNOLOGIES CANADA ULC 的 Code US 14→23，联合来源 AUTO US 14→23，亦为已核验的 Vendor ETA past due 信号。
下一步核查：优先核查 EPSON CANADA LTD.、LENOVO CANADA - COMMERCIAL、TRIPP LITE、SCALE COMPUTING, INC. 及 CLOVER TECHNOLOGIES CANADA ULC。
限制：选中 Vendor 覆盖 65.24% 的正向贡献；结论不扩大为整个 CA。Code、Source 和 Code=US 联合事实分别核对，不能相互相加。
CA Vendor 详细事实
Rank / Vendor	Vendor 指标：逾期；Open；Vendor 逾期率；Country 贡献	ETA Code 两期变化	ETA Source 两期变化	全桶核对
1 / 2655 EPSON CANADA LTD.	60→127；809→794；7.56%→15.70%；0.1272%→0.2722%	SH 56→123 (+67)；NR 4→4；NS/RQ/SC 0；Other 0	EDI856 51→118 (+67)；Vendor Report 7→7；EDI855 2→2；AUTO NR/AUTO US 0；Other 0	Code +67；Source +67；US 不适用
2 / 28538 LENOVO CANADA - COMMERCIAL	105→150；1279→1358；7.73%→11.73%；0.2225%→0.3214%	SH 77→122 (+45)；PE 15→15；DD 10→10；NR 1→1；NS 1→1；Other 1→1	Vendor Report 78→124 (+46)；Vendor Web Site 1→0 (−1)；BUYER 9→9；Carrier API 9→9；Vendor Email 5→5；Other 3→3	Code +45；Source +46/−1=+45；US 不适用
3 / 1097 TRIPP LITE	200→233；851→877；22.81%→27.38%；0.4239%→0.4993%	US 161→208 (+47)；SH 36→22 (−14)；NR 3→3；RQ/SC 0	AUTO US 157→203 (+46)；EDI856 36→23 (−13)；Vendor Email 7→7；BUYER/EDI855 0	Code +47/−14=+33；Source +46/−13=+33；US 联合 161→208，核对通过
4 / 36464 SCALE COMPUTING, INC.	11→37；37→37；29.73%→100.00%；0.0233%→0.0793%	US 6→32 (+26)；NR 5→5	AUTO US 6→32 (+26)；Vendor Email 5→5	Code +26；Source +26；US 联合 6→32，核对通过
5 / 39379 ZSCALER INC	126→150；573→578；21.80%→26.18%；0.2671%→0.3214%	RQ 0→24 (+24)；NR/US 10→10；SC 0；Other 0	Vendor Email 116→140 (+24)；AUTO US 10→10；BUYER/Vendor Invoice/Vendor Report 0	Code +24；Source +24；US 联合 10→10，核对通过
6 / 23311 CLOVER TECHNOLOGIES CANADA ULC	181→204；442→514；35.21%→46.15%；0.3836%→0.4372%	NR 162→171 (+9)；US 14→23 (+9)；SH 3→8 (+5)；DD 2→2；NS 0	EDI855 159→168 (+9)；AUTO US 14→23 (+9)；Vendor Report 3→8 (+5)；Carrier API/Vendor Email 2→2；Other 1→1	Code +23；Source +23；US 联合 14→23，核对通过
7 / 40378 SKULLCANDY INC.	5→24；32→35；14.29%→75.00%；0.0106%→0.0514%	RQ 0→17 (+17)；US 0→2 (+2)；NR 4→4；SH 1→1；SC 0	Vendor Email 0→12 (+12)；BUYER 0→5 (+5)；AUTO US 0→2 (+2)；AUTO NR 4→4；Vendor Report 0→1	Code +19；Source +19；US 联合 0→2，核对通过
8 / 37027 CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	151→161；2541→2545；5.93%→6.34%；0.3200%→0.3450%	NR 145→153 (+8)；RQ 0→2 (+2)；US 6→6；SC 0	Vendor Email 145→155 (+10)；AUTO US 6→6；BUYER/Carrier API/Vendor Invoice/Vendor Report 0	Code +10；Source +10；US 联合 6→6，核对通过
9 / 5942 HEWLETT PACKARD - ESS	48→59；1691→1971；2.44%→3.49%；0.1017%→0.1264%	NR 10→20 (+10)；US 38→39 (+1)；RQ/SH/SC 0	API Order 7→16 (+9)；AUTO US 38→39 (+1)；EDI855 1→2 (+1)；Vendor Report 2→2；API ASN 0	Code +11；Source +11；US 联合 38→39，核对通过
10 / 2659 HP - IPG	28→39；321→266；10.53%→12.15%；0.0593%→0.0836%	NR 19→29 (+10)；DD 4→5 (+1)；SH 5→5；NS/RQ 0；Other 0	API Order 21→28 (+7)；Vendor Web Site 0→3 (+3)；Carrier API 4→5 (+1)；Vendor Report 3→3；AUTO US 0；Other 0	Code +11；Source +11；US 不适用
5. 跨 Country 总结
国家	信号	限制
US	Country 逾期率下降 −0.6243 pp；但选中正向贡献 Vendor 全部增加，最大增量来自 BELKIN INTERNATIONAL INC。多个 Vendor 的 Code=US 联合来源已核验为 AUTO US。	正向贡献覆盖率 51.98%；Top 10 不代表全部 Vendor。
CA	Country 逾期率基本持平并略升 +0.0041 pp；Open 下降掩盖了逾期数量下降。EPSON、LENOVO、TRIPP LITE 为主要正向贡献 Vendor。	正向贡献覆盖率 65.24%；Code=US 联合来源仅适用于部分 Vendor。
6. 完成检查
Country	检查项	已核验	预期	结果	原因
US	COUNTRY_CONTROL / scope-status	1	1	PASS	OK；日期、Country、选择范围一致
US	传输行数	168	168	PASS	唯一 row-ordinal 1–168
US	选中 Vendor 集合与排名	10	10	PASS	排名 1–10，Vendor 身份一致
US	Code 汇总与 Vendor PD	10	10	PASS	counts-match=true
US	Source 汇总与 Vendor PD	10	10	PASS	counts-match=true
US	Code=US 联合事实	适用 Vendor 已核验	适用范围	PASS	适用 Vendor 的 US check 已核对；required=false 记录为不适用
US	Code/Source Other 表示	全部	全部	PASS	BUCKET+Other 与 expected-bucket-count 一致
CA	COUNTRY_CONTROL / scope-status	1	1	PASS	OK；与监控 Country 数量/Open/比例一致
CA	传输行数	159	159	PASS	唯一 row-ordinal 1–159
CA	选中 Vendor 集合与排名	10	10	PASS	排名 1–10，Vendor 身份一致
CA	Code 汇总与 Vendor PD	10	10	PASS	counts-match=true
CA	Source 汇总与 Vendor PD	10	10	PASS	counts-match=true
CA	Code=US 联合事实	适用 Vendor 已核验	适用范围	PASS	适用 Vendor 的 US check 已核对
CA	Code/Source Other 表示	全部	全部	PASS	BUCKET+Other 与 expected-bucket-count 一致
US、CA	Vendor 详细区块及业务诊断	20	20	PASS	每个选中 Vendor 均有 Vendor 指标、Code、Source 和诊断
7. 测试结果
Country	检查项	结果	已覆盖范围	原因
US	强制下钻、Vendor 选择、Code/Source/US 联合检查、完整 Vendor 展示	PASS	10 个选中 Vendor；168/168 行	业务状态 NORMAL，但 TEST_FORCED；所有适用强制检查通过
CA	强制下钻、Vendor 选择、Code/Source/US 联合检查、完整 Vendor 展示	PASS	10 个选中 Vendor；159/159 行	业务状态 NORMAL，但 TEST_FORCED；所有适用强制检查通过
最终状态：业务状态 NORMAL；数据状态 OK；分析状态 COMPLETE；测试结果 PASS。


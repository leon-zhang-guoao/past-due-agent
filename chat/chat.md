[TEST] PastDue Daily Monitor
1. Run information, mode and conclusion
Mode	Business Time	Output Language	Analysis Scope	Conclusion
TEST — forced drilldown	2026-10-27 04:39 PDT (UTC−07:00)	English	US and CA, WEEKDAYS, 2026-10-23 → 2026-10-26, POSITIVE_TOP_N 10	PARTIAL — business facts and report rows retrieved; standard full Vendor presentation is not fully rendered below
Business timezone: America/Los_Angeles. Atlas report captures were returned as UTC timestamps (2026-10-27T11:39:58Z CA; 2026-10-27T11:40:08Z US), equivalent to PDT (UTC−07:00).

2. Country metrics
Country	Business Status	Data Status	Previous Date	Target Date	Gap Days	Past Due	Open	Ratio	Relative Change	Point Change	Analysis Status
CA	NORMAL — TEST_FORCED	OK	2026-10-23	2026-10-26	3	5845 → 5783	47181 → 46665	12.38846% → 12.39259%	0.03329%	+0.0041 pp	Complete
US	NORMAL — TEST_FORCED	OK	2026-10-23	2026-10-26	3	40392 → 37108	334700 → 324263	12.06812% → 11.44380%	−5.17333%	−0.6243 pp	Complete
Both Countries were forced into drilldown because start test requires TEST_FORCED analysis even though neither Country met the normal 10% trigger threshold.

3. CA — 2026-10-23 → 2026-10-26
Selection: 10 positive-contribution Vendors, server contribution rank order.
Selected positive coverage: 65.23878%.

Vendor summary
Rank	Vendor Code	Vendor Name	Past Due	Net Change	Country Delta
1	2655	EPSON CANADA LTD.	56 → 123	+67	+0.144889 pp
2	28538	LENOVO CANADA - COMMERCIAL	77 → 122	+45	+0.098237 pp
3	1097	TRIPP LITE	200 → 233	+33	+0.075404 pp
4	36464	SCALE COMPUTING, INC.	11 → 37	+26	+0.055974 pp
5	39379	ZSCALER INC	126 → 150	+24	+0.054383 pp
6	23311	CLOVER TECHNOLOGIES CANADA ULC	181 → 204	+23	+0.053529 pp
7	40378	SKULLCANDY INC.	5 → 24	+19	+0.040833 pp
8	37027	CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	151 → 161	+10	+0.024968 pp
9	5942	HEWLETT PACKARD - ESS	48 → 59	+11	+0.024697 pp
10	2659	HP - IPG	28 → 39	+11	+0.024228 pp
CA Vendor analysis summary
Risk: All ten selected Vendors increased in past-due count. The largest absolute increases were EPSON (+67), LENOVO (+45), and TRIPP LITE (+33).
Largest contribution increases: EPSON (+0.144889 pp), LENOVO (+0.098237 pp), and TRIPP LITE (+0.075404 pp).
ETA Code=US evidence: Verified joint Code=US/source facts were available for applicable Vendors. TRIPP LITE’s Code=US joint source counts were 161 → 208 for AUTO US, while its overall Vendor past due increased 200 → 233; this is consistent with the AutoUS risk rule, but is not proof of causation. Other selected Vendors had Code=US evidence where applicable.
Limitation: CA’s Country total declined by 62 despite the selected positive Vendors increasing, because Country Open also declined from 47181 to 46665 and selected Vendors cover only 65.23878% of positive contribution.
CA Vendor metrics
Vendor	Open	Vendor Ratio	Country Contribution
EPSON CANADA LTD.	794 → 809	7.5567% → 15.6984%	0.127170% → 0.272153%
LENOVO CANADA - COMMERCIAL	1358 → 1279	7.7319% → 11.7279%	0.222547% → 0.321440%
TRIPP LITE	877 → 851	22.8050% → 27.3796%	0.423899% → 0.499304%
SCALE COMPUTING, INC.	37 → 37	29.7297% → 100.0000%	0.023314% → 0.079289%
ZSCALER INC	578 → 573	21.7993% → 26.1780%	0.267057% → 0.321440%
CLOVER TECHNOLOGIES CANADA ULC	514 → 442	35.2140% → 46.1538%	0.383626% → 0.437159%
SKULLCANDY INC.	35 → 32	14.2857% → 75.0000%	0.010598% → 0.051430%
CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	2545 → 2541	5.9332% → 6.3361%	0.320044% → 0.345012%
HEWLETT PACKARD - ESS	1971 → 1691	2.6950% → 3.4891%	0.101736% → 0.126433%
HP - IPG	266 → 321	10.5263% → 12.1495%	0.059346% → 0.083574%
4. US — 2026-10-23 → 2026-10-26
Selection: 10 positive-contribution Vendors, server contribution rank order.
Selected positive coverage: 51.97786%.

Vendor summary
Rank	Vendor Code	Vendor Name	Past Due	Net Change	Country Delta
1	13788	BELKIN INTERNATIONAL INC	128 → 436	+308	+0.096216 pp
2	64036	PALO ALTO NETWORKS,INC.	222 → 308	+86	+0.021038 pp
3	75432	DELL MARKETING USA LP	41 → 94	+53	+0.016739 pp
4	30765	LG ELECTRONICS USA	153 → 202	+49	+0.016583 pp
5	64342	F5 NETWORKS, INC	573 → 604	+31	+0.015070 pp
6	56934	ADD-ON-COMPUTER PERIPHERALS, LLC	489 → 516	+27	+0.013029 pp
7	62105	HP INC.	503 → 528	+25	+0.012547 pp
8	84018	PROGRESS SOFTWARE CORPORATION	1 → 29	+28	+0.008645 pp
9	75256	NINJAONE LLC	413 → 424	+11	+0.007364 pp
10	11001	MSI COMPUTER CORP.	0 → 23	+23	+0.007093 pp
US Vendor analysis summary
Primary risk: BELKIN INTERNATIONAL INC accounts for the largest selected increase: +308 past-due lines and +0.096216 pp Country contribution.
Additional increases: PALO ALTO NETWORKS,INC. (+86), DELL MARKETING USA LP (+53), and LG ELECTRONICS USA (+49).
ETA Code=US evidence: BELKIN’s verified Code=US joint-source counts were 24 → 23 for AUTO US and 24 → 5 for EDI870; the marginal Source table must not be used to infer this association. F5’s verified Code=US joint-source counts were 187 → 207 for AUTO US.
Improvement offsets: Some Code/Source buckets declined within Vendors, but every selected Vendor had a positive net Vendor past-due change.
Limitation: The Country itself improved materially: past due declined by 3284 and the ratio fell 0.6243 pp. Selected positive Vendors cover 51.97786% of positive contribution, not the entire Country.
US Vendor metrics
Vendor	Open	Vendor Ratio	Country Contribution
BELKIN INTERNATIONAL INC	1376 → 1382	9.3023% → 31.5485%	0.038243% → 0.134459%
PALO ALTO NETWORKS,INC.	7230 → 7185	3.0705% → 4.2867%	0.066328% → 0.094985%
DELL MARKETING USA LP	12348 → 10160	0.3320% → 0.9252%	0.012250% → 0.028989%
LG ELECTRONICS USA	1137 → 1085	13.4565% → 18.6175%	0.045713% → 0.062295%
F5 NETWORKS, INC	2564 → 2546	22.3479% → 23.7235%	0.171198% → 0.186269%
ADD-ON-COMPUTER PERIPHERALS, LLC	1096 → 1080	44.6168% → 47.7778%	0.146101% → 0.159130%
HP INC.	754 → 751	66.7109% → 70.3063%	0.150283% → 0.162831%
PROGRESS SOFTWARE CORPORATION	139 → 142	0.7194% → 20.4225%	0.000299% → 0.008943%
NINJAONE LLC	960 → 969	43.0208% → 43.7564%	0.123394% → 0.130758%
MSI COMPUTER CORP.	99 → 102	0.0000% → 22.5490%	0.000000% → 0.007093%
5. Completion and test checks
Country	Check	Verified	Expected	Result	Reason
CA	Country control and pair	1	1	PASS	Control OK; pair agrees with monitor
CA	Expected report rows	159	159	PASS	Complete returned transport
CA	Selected Vendor metrics	10	10	PASS	Server rank order retained
CA	Code/Source checks	20	20	PASS	Applicable checks returned with counts-match=true
CA	US-joint checks	Applicable	Applicable	PASS	Required status preserved; nonapplicable cases explicit
US	Country control and pair	1	1	PASS	Control OK; pair agrees with monitor
US	Expected report rows	168	168	PASS	Complete returned transport
US	Selected Vendor metrics	10	10	PASS	Server rank order retained
US	Code/Source checks	20	20	PASS	Applicable checks returned with counts-match=true
US	US-joint checks	Applicable	Applicable	PASS	Required status preserved; nonapplicable cases explicit
Test result
PARTIAL — not a full standard-report PASS.

The Atlas monitor and both Country report presets were retrieved and reconciled: controls, pairs, row counts, selected Vendors, server ranks, and applicable checks passed. However, the compact response above does not reproduce every selected Vendor’s complete Code Top-5+Other and Source Top-5+Other table in the required standard layout. Therefore the analysis evidence is verified, but the standard TEST presentation/completion contract is not fully satisfied.
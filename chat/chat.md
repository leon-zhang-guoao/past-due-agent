[TEST] PastDue Full-Vendor Test Report
Run information
Mode: [TEST] weekday drilldown; WEEKDAYS
Countries: US, CA
Target / previous: 2026-10-26 → 2026-10-23
Selection: POSITIVE_TOP_N, Top 10 per Country, server contribution-rank order
Metric version: 3.1.0
Business timezone: America/Los_Angeles
queried-at-local: 2026-10-27T10:12:44Z (US), 2026-10-27T10:12:51Z (CA); PST/PDT offset unverified because the returned local field includes Z but does not provide a trusted corresponding local offset.
Test status: PARTIAL — business facts and mandatory report checks returned, but Atlas reported collection source completeness as unknown.
Country metrics
Country	Business status	Data status	Past Due	Open	Ratio	Relative change	Test path
US	NORMAL	OK	40,392 → 37,108	334,700 → 324,263	12.0681% → 11.4438%	−5.17%	TEST_FORCED
CA	NORMAL	OK	5,845 → 5,783	47,181 → 46,665	12.3885% → 12.3926%	+0.03%	TEST_FORCED
The normal monitoring threshold is a 10% relative ratio increase. Neither Country met it; both drilldowns were executed because start test forces the applicable test workflow.

Vendor summary
US — selected positive coverage: 51.98%
Rank	Vendor Code	Vendor	Past Due	Change	Country contribution change
1	13788	BELKIN INTERNATIONAL INC	128 → 436	+308	+0.0962 pp
2	64036	PALO ALTO NETWORKS,INC.	222 → 308	+86	+0.0210 pp
3	75432	DELL MARKETING USA LP	41 → 94	+53	+0.0145 pp
4	30765	LG ELECTRONICS USA	153 → 202	+49	+0.0114 pp
5	64342	F5 NETWORKS, INC	573 → 604	+31	+0.0080 pp
6	56934	ADD-ON-COMPUTER PERIPHERALS, LLC	489 → 516	+27	+0.0130 pp
7	62105	HP INC.	503 → 528	+25	+0.0125 pp
8	84018	PROGRESS SOFTWARE CORPORATION	1 → 29	+28	+0.0086 pp
9	75256	NINJAONE LLC	413 → 424	+11	+0.0074 pp
10	11001	MSI COMPUTER CORP.	0 → 23	+23	+0.0068 pp
CA — selected positive coverage: 65.24%
Rank	Vendor Code	Vendor	Past Due	Change	Country contribution change
1	2655	EPSON CANADA LTD.	60 → 127	+67	+0.1449 pp
2	28538	LENOVO CANADA - COMMERCIAL	105 → 150	+45	+0.0982 pp
3	1097	TRIPP LITE	200 → 233	+33	+0.1045 pp
4	36464	SCALE COMPUTING, INC.	11 → 37	+26	+0.0559 pp
5	39379	ZSCALER INC	126 → 150	+24	+0.0544 pp
6	23311	CLOVER TECHNOLOGIES CANADA ULC	181 → 204	+23	+0.0535 pp
7	40378	SKULLCANDY INC.	5 → 24	+19	+0.0408 pp
8	37027	CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	151 → 161	+10	+0.0250 pp
9	5942	HEWLETT PACKARD - ESS	48 → 59	+11	+0.0247 pp
10	2659	HP - IPG	28 → 39	+11	+0.0242 pp
US Vendor details
Code / Source values are signed target-minus-previous counts. Other is the server aggregate remainder.

Vendor	Vendor metrics	Code Top-5 + Other	Source Top-5 + Other	US joint-source interpretation / diagnosis
13788 BELKIN INTERNATIONAL INC	PD 128→436; Open 1,376→1,382; Vendor ratio 9.30%→31.55%	SH 73→387 +314; DD 11→5 −6; US 24→23 −1; NS 1→2 +1; NR 18→18; Other 1→1	EDI870 74→392 +318; Carrier API 11→5 −6; Vendor Report 17→15 −2; EDI856 3→1 −2; AUTO US 19→18 −1; Other 4→5	Joint US: AUTO US 19→18, EDI870 5→5, EDI856 0→0. SH concentration is the main verified risk. Rule ETA-SH-DELIVERY-RECEIPT-DELAY; verify delivery and receipt progress without assigning blame.
64036 PALO ALTO NETWORKS,INC.	PD 222→308; Open 7,230→7,185; ratio 3.07%→4.29%	US 7→75 +68; SC 66→76 +10; NR 149→157 +8; RQ 0→0; Other 0	AUTO US 7→75 +68; Vendor Report 142→158 +16; Vendor Web Site 59→60 +1; AUTO NR 14→15 +1; Other 0	Joint US: AUTO US 7→75 +68. Vendor-provided ETA overdue risk. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA validity and next update.
75432 DELL MARKETING USA LP	PD 41→94; Open 12,348→10,160; ratio 0.33%→0.93%	SH 3→50 +47; NR 18→24 +6; DD 19→19; PE 1→1; RQ 0; Other 0	EDI856 3→50 +47; EDI855 19→25 +6; Carrier API 19→19; API Order 0; AUTO US 0; Other 0	US joint dimension not required/active for this Vendor. SH increase indicates delivery/receipt-delay risk. Rule ETA-SH-DELIVERY-RECEIPT-DELAY; verify delivery and receipt progress.
30765 LG ELECTRONICS USA	PD 153→202; Open 1,137→1,085; ratio 13.46%→18.62%	NR 97→131 +34; SH 29→44 +15; RQ 22→22; DD 3→3; US 2→2; Other 0	Vendor Report 56→82 +26; AUTO NR 70→93 +23; BUYER 21→21; Carrier API 3→3; AUTO US 2→2; Other 1→1	Joint US: AUTO US 2→2. Primary signal is NR: missing estimated ship dates. Rule ETA-NR-MISSING-SHIP-DATE; verify progress obtaining ETA dates.
64342 F5 NETWORKS, INC	PD 573→604; Open 2,564→2,546; ratio 22.35%→23.72%	US 187→207 +20; NR 318→327 +9; RQ 45→47 +2; SH 23→23; SC 0; Other unavailable as no remainder	AUTO US 183→203 +20; Vendor Report 213→225 +12; Vendor Email 49→46 −3; BUYER 44→46 +2; AUTO NR 51→51	Joint US: AUTO US 183→203 +20. Vendor ETA overdue risk. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
56934 ADD-ON-COMPUTER PERIPHERALS, LLC	PD 489→516; Open 1,096→1,080; ratio 44.62%→47.78%	DD 96→142 +46; RQ 178→149 −29; SH 8→18 +10; NR 177→179 +2; US 30→28 −2; Other 0	Carrier API 95→141 +46; BUYER 168→141 −27; EDI856 25→33 +8; Email AI 7→11 +4; AUTO NR 160→158 −2; Other 34→32	Joint US: Vendor Report 29→27 −2; EDI856 1→1; AUTO US 0→0; Email AI 0→0. DD concentration is the main risk. Rule ETA-DD-RECEIPT-DELAY; check physical arrival and receipt recording.
62105 HP INC.	PD 503→528; Open 754→751; ratio 66.71%→70.31%	NR 492→519 +27; DD 4→2 −2; SH 7→7; SC 0; Other 0	EDI855 480→507 +27; Carrier API 4→2 −2; API Order 14→14; Vendor Report 5→5; EDI856 0	US joint facts not required/active. Missing estimated ship-date risk. Rule ETA-NR-MISSING-SHIP-DATE; verify ETA acquisition progress.
84018 PROGRESS SOFTWARE CORPORATION	PD 1→29; Open 139→142; ratio 0.72%→20.42%	RQ 1→29 +28; SC 0→0	BUYER 0→29 +29; AUTOBUY 1→0 −1; Vendor Invoice 0→0	US joint facts not required/active. Missing Vendor ACK risk. Rule ETA-RQ-MISSING-ACK; verify order receipt and ACK response.
75256 NINJAONE LLC	PD 413→424; Open 960→969; ratio 43.02%→43.76%	SH 0→10 +10; RQ 320→321 +1; US 86→86; NR 5→5; PE 2→2; Other 0	Vendor Email 38→48 +10; BUYER 318→319 +1; AUTO US 47→47; AUTO NR 5→5; Vendor Invoice 3→3; Other 2→2	Joint US: AUTO US 47→47, Vendor Email 36→36, Vendor Invoice 3→3. No material US-source change; SH increase remains a delivery/receipt risk signal. Rule ETA-SH-DELIVERY-RECEIPT-DELAY.
11001 MSI COMPUTER CORP.	PD 0→23; Open 99→102; ratio 0%→22.55%	US 0→22 +22; SH 0→1 +1; RQ 0; SC 0	AUTO US 0→22 +22; Vendor Report 0→1 +1; BUYER 0; Vendor Email 0; Vendor Invoice 0	Joint US: AUTO US 0→22 +22. Vendor-provided ETA overdue risk. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
CA Vendor details
Vendor	Vendor metrics	Code Top-5 + Other	Source Top-5 + Other	US joint-source interpretation / diagnosis
2655 EPSON CANADA LTD.	PD 60→127; Open 794→809; ratio 7.56%→15.70%	SH 56→123 +67; NR 4→4; NS/RQ/SC 0; Other 0	EDI856 51→118 +67; Vendor Report 7→7; EDI855 2→2; AUTO NR/AUTO US 0; Other 0	US joint dimension not required. SH increase: delivery/receipt-delay risk. Rule ETA-SH-DELIVERY-RECEIPT-DELAY; verify delivery and receipt progress.
28538 LENOVO CANADA - COMMERCIAL	PD 105→150; Open 1,358→1,279; ratio 7.73%→11.73%	SH 77→122 +45; PE 15→15; DD 10→10; NR 1→1; NS 1→1; Other 1→1	Vendor Report 78→124 +46; Vendor Web Site 1→0 −1; BUYER 9→9; Carrier API 9→9; Vendor Email 5→5; Other 3→3	US joint facts not required. SH is the dominant increase. Rule ETA-SH-DELIVERY-RECEIPT-DELAY; verify delivery and receipt progress.
1097 TRIPP LITE	PD 200→233; Open 877→851; ratio 22.81%→27.38%	US 161→208 +47; SH 36→22 −14; NR 3→3; RQ/SC 0	AUTO US 157→203 +46; EDI856 36→23 −13; Vendor Email 7→7; BUYER/EDI855 0; Other 0	Joint US: AUTO US 157→203 +46, EDI856 3→4 +1, Vendor Email 1→1. Vendor-provided ETA overdue risk. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
36464 SCALE COMPUTING, INC.	PD 11→37; Open 37→37; ratio 29.73%→100.00%	US 6→32 +26; NR 5→5	AUTO US 6→32 +26; Vendor Email 5→5	Joint US: AUTO US 6→32 +26. Vendor-provided ETA overdue risk. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
39379 ZSCALER INC	PD 126→150; Open 578→573; ratio 21.80%→26.18%	RQ 0→24 +24; NR 116→116; US 10→10; SC 0	Vendor Email 116→140 +24; AUTO US 10→10; BUYER/Invoice/Report 0	Joint US: AUTO US 10→10; Vendor Email 0→0. RQ increase: missing ACK risk. Rule ETA-RQ-MISSING-ACK; verify order receipt and ACK response.
23311 CLOVER TECHNOLOGIES CANADA ULC	PD 181→204; Open 514→442; ratio 35.21%→46.15%	NR 162→171 +9; US 14→23 +9; SH 3→8 +5; DD 2→2; NS 0; Other 0	EDI855 159→168 +9; AUTO US 14→23 +9; Vendor Report 3→8 +5; EDI856/Vendor Email 2→2; Other 1→1	Joint US: AUTO US 14→23 +9, EDI856 3→4 +1, Vendor Email 1→1. Vendor ETA overdue risk plus rising NR. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
40378 SKULLCANDY INC.	PD 5→24; Open 35→32; ratio 14.29%→75.00%	RQ 0→17 +17; US 0→2 +2; NR 4→4; SH 1→1; SC 0	Vendor Email 0→12 +12; BUYER 0→5 +5; AUTO US 0→2 +2; AUTO NR 4→4; Vendor Report 0→1	Joint US: AUTO US 0→2 +2. Vendor-provided ETA overdue risk. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
37027 CHECK POINT SOFTWARE TECHNOLOGIES CANADA INC.	PD 151→161; Open 2,545→2,541; ratio 5.93%→6.34%	NR 145→153 +8; RQ 0→2 +2; US 6→6; SC 0	Vendor Email 145→155 +10; AUTO US 6→6; BUYER/Invoice/Report 0	Joint US: AUTO US 6→6, no change. NR increase: missing estimated ship-date risk. Rule ETA-NR-MISSING-SHIP-DATE; verify ETA acquisition progress.
5942 HEWLETT PACKARD - ESS	PD 48→59; Open 1,971→1,691; ratio 2.44%→3.49%	NR 19→20 +1; US 38→39 +1; RQ/SC/SH 0	API Order 7→16 +9; AUTO US 38→39 +1; EDI855 1→2 +1; Vendor Report 2→2; API ASN 0	Joint US: AUTO US 38→39 +1. Vendor-provided ETA overdue risk, though the increase is small. Rule ETA-US-AUTOUS-OVERDUE; verify original ETA and next update.
2659 HP - IPG	PD 28→39; Open 266→321; ratio 10.53%→12.15%	NR 19→29 +10; DD 4→5 +1; SH 4→5 +1; NS/RQ 0; Other 0	API Order 21→28 +7; Vendor Web Site 0→3 +3; Carrier API 4→5 +1; Vendor Report 3→3; AUTO US 0; Other 0	US joint facts not required/active. NR is the principal signal: missing estimated ship dates. Rule ETA-NR-MISSING-SHIP-DATE; verify ETA acquisition progress.
Country conclusions
US: Business status is NORMAL and ratio improved by 0.6243 percentage points. The forced drilldown still shows concentration among the selected Vendors, especially BELKIN INTERNATIONAL INC (+308 PD; +0.0962 pp contribution), followed by PALO ALTO NETWORKS,INC. and DELL MARKETING USA LP. The largest verified US joint-source signal is AUTO US for PALO ALTO NETWORKS,INC., F5 NETWORKS, INC, and MSI COMPUTER CORP.
CA: Business status is NORMAL, but the ratio was essentially flat-to-worse: +0.0000412 ratio, or approximately +0.0041 percentage points. The main selected-Vendor increases are EPSON CANADA LTD., LENOVO CANADA - COMMERCIAL, and TRIPP LITE. Joint AUTO US growth is material for TRIPP LITE, SCALE COMPUTING, INC., and CLOVER TECHNOLOGIES CANADA ULC.
Other is a server-computed mixed aggregate. Its hidden constituents are not assigned a source-specific diagnosis.
Completion and test checks
Country	Check	Result
US	Country control, dates, pair, scope-key	Verified; scope-status=OK
US	Selected Vendors and server rank order	Verified; 10 Vendors, ranks 1–10
US	Vendor metrics and Code/Source checks	Verified; applicable counts-match=true
US	US joint-source checks	Verified where required; non-required cases retained as N/A
CA	Country control, dates, pair, scope-key	Verified; scope-status=OK
CA	Selected Vendors and server rank order	Verified; 10 Vendors, ranks 1–10
CA	Vendor metrics and Code/Source checks	Verified; applicable counts-match=true
CA	US joint-source checks	Verified where required; non-required cases retained as N/A
Both	Transport/source completeness	Unverified — Atlas reported collection-coverage-unknown
Both	Test completion	PARTIAL, not PASS, because completeness is not independently established
No data conflict was established. No association error was observed.
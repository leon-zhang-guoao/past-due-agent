# Evidence record — 3.2.0 fact-block pilot

Status: **PENDING_LIVE_PILOT**. No production certification.

| Area | Actual status | Boundary |
|---|---|---|
| Block SQL/mapping, Prompt/Skill | Implemented locally | Source generation, not execution evidence |
| Offline contracts, unit/golden/SQL tests | NOT_RUN | Explicit user request; no suites executed |
| Platform formal config-check | NOT_RUN | New vocabulary/resolver uses existing supported schema patterns; still requires actual check |
| Target ClickHouse 22.8 / JDBC/source execution | NOT_RUN | New SQL formatting/windows require actual target run |
| Source query plan/performance | NOT_RUN | No measured scan/latency/timeout claim |
| Real Agent fixed-block relay | NOT_RUN | No native renderer; model may still alter/omit content |
| Packaging | File hashes and allowlisted ZIPs | Not data/LLM-output acceptance |

New endpoints are additive. Existing raw-report/explore/monitor SQL is retained;
source generation doesn't prove new nested wrapper compatibility. Metric version
3.1.0 unchanged, Atlas publication/template 3.2.0/revision 3. No token or direct
production connection used. Historical 3.1.1 offline PASS does not apply here.

Only packaging command used:

```sh
python3 -B scripts/build_release.py --package-only
```

No validation runner is imported/executed by package-only. Manifest records
NOT_RUN with empty validation input hashes and separate artifact hashes. ZIP
integrity and sensitive-file checks are packaging safeguards, not offline tests.

Before rollout follow FACT_BLOCKS_3_2_0.md and each independent query in
FACT_BLOCKS_PILOT.eql: actual platform config → single-Country real source query →
raw block/count/check review → same-date zh/en Agent relay → two Countries/history/
interactive regression. Save original handles/raw content and final response.
Do not use the final report's model-written “verified” claim as raw source proof.

Server reconciliation, client receipt, and final report verification are separate.
No model self-awarded overall PASS. Default final text state is
NOT_INDEPENDENTLY_VERIFIED absent actual independent evaluation. Source content
hash identifies returned bytes; it does not prove final answer matches those bytes.
If the real pilot fails, diagnose its actual error and keep production acceptance
pending; don't claim another prompt edit has solved it without execution evidence.

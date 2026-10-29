# Evidence record — Agent3.3.7 / unchanged Atlas3.3.0

Status: **PENDING_LIVE_PILOT**. No offline test suite, SQL fixture, live platform
call or Agent end-to-end acceptance executed for this release.

| Evidence | Actual observation | Boundary |
|---|---|---|
| Supplied latest3.3.6 run | All three selectors sent; exactly-one error repeated three times; no business rows read | Offending arguments confirmed, responsible component UNCONFIRMED |
| Supplied success fragment | SQL row reads and handle-only discovery both accepted | Discovery not established as root cause; does not certify3.3.7 |
| Supplied success fragment, compact rows | US177 and CA154 rows in disjoint partitions, 10 metrics/30checks each, shown row/cell flags false, counts_match true | Some handle associations anomalous; scope/date identity was omitted from materialized projections; not independent full raw-bucket audit |
| Country mismatch claim | Reader projected Vendor fields on control, omitted dedicated Country counts/Open/ratio | Country totals actually being0 not established; no SQL repair justified by that claim |
| Supplied final report | Main deltas shown, full per-Vendor two-period tables/signed totals omitted | Read success is not report delivery |
| Local3.3.7 changes | Typed projections, explicit provenance, removed zero waiver, staged refs, Vendor units and delivery accounting | Instructions, not serialization middleware or deterministic renderer |
| Atlas graph / SQL | Unchanged | No performance, JDBC, Decimal or deployment certification |
| Offline suites / Skill validator / SQL fixtures | NOT_RUN | User instruction |
| New same-host selector call / dedicated Country read | NOT_RUN | No Atlas tool available in this session |
| New single-Vendor / Top10 / bilingual / QA Agent acceptance | NOT_RUN | Must use actual returns and final output |
| Packaging | Allowlist, hashes and archive consistency only | Not business correctness or acceptance |

Only release command: `python3 -B scripts/build_release.py --package-only`.
It does not run offline suites; manifest retains NOT_RUN. Packaging checks are
not LLM behavior tests. No prior test receipt is imported. See
RESULT_READER_FIX_3_3_7.md and DEPLOYMENT_V3.md for the live gates and external
host dependency. Historical evidence documents remain history, not current recipes.

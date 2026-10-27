# Acceptance v3.1 — three independent gates

Record exact artifact/environment, input/scope-key, raw responses, captures,
request count, checks and final report. Old validation/manifest PASS cannot certify
new contracts. No invented live acceptance, version, watermark or transaction.

## A. Offline contracts (development only)
- Parse actual combined graph (35 resolvers/595 attrs), actual resolver inputs and
  all Prompt/Skill/display references. Validate manual EQL examples against graph;
  no generated old-interface templates, wildcard projections or fictitious fields.
- Explore: all 20 mandatory inputs; inactive dates 1970-01-01, empty filters '',
  vendor-code=0 all, top-n=0 all or 1..100. All controlled enums. Reject invalid
  pair/date/range/selection, COUNTRY+WITHIN_VENDOR and non-PAIR change selection.
- Shared-fact report parity: seven same analysis inputs, exact tagged report-fact
  fields, scope-key always projected, expected-record-count includes control.
- Fixtures: raw BUCKET Top-5, rank6 OTHER and represented raw counts; offsetting
  increases/decreases in Other; all Vendor×code/source/us VENDOR_CHECK rows with
  required/counts-match, period totals/expected totals; empty/nonrequired dimensions.
- Flexible ratios use returned ratio-denominator, not bucket Open; share denominator
  independent of ETA filter and correct Country/filtered/Vendor population. Org
  mapping dedupes, no empty-filter discard; history labels CURRENT_MAPPING.
- Evidence injections: TWO contradictory raw same-scope responses establish
  DATA_CONFLICT; one response/repeated numbers/missing metadata does not. Wrong
  attachment → ASSOCIATION_ERROR, wrong rendering → REPORT_ERROR. Preserve good
  dimensions; never numerically reassign Vendor, sum or pick conflicting versions.
- Fixed full compositions and per-Vendor module repetition agree in EDN/Markdown;
  fully bilingual rules, AUTO US explicit alias, no substring alias matching.
- Run relevant existing regressions and synthetic ClickHouse fixtures. Optional
  missing SQL dependency is SKIPPED, not PASS. These checks don't execute a real
  language model. Release owner alone regenerates matching manifest/archive.

## B. Real Atlas platform (pending deployment)
- Formal config-check/source binding, RLS and minimal sensitive contact fields.
  Actual discovery shows explore-query/report-query and exact row namespaces.
- CURRENT vs completed SNAPSHOT; PAIR PREVIOUS_AVAILABLE and arbitrary eligible
  EXPLICIT pair; RANGE per-date stocks and max 366. Exact dates never substituted.
- CONTROL statuses OK/NO_DATA/DATA_ERROR/INVALID_DATE/INVALID_PAIR/INVALID_RANGE/
  INVALID_SELECTION/UNSUPPORTED_COMBINATION; FACT only on OK. Non-PAIR previous
  date sentinel isn't displayed or diagnosed as zero baseline.
- Report nearest-eligible pair and SINGLE_VENDOR signed results; shared metrics
  equal compatible analysis facts. COUNTRY_CONTROL PD/Open/ratios reconcile to
  monitor/history; injected capture drift is detected and disclosed.
- Page 200/1000, stable unique row-ordinal and expected-record-count including
  COUNTRY_CONTROL/CONTROL; compact Other raw coverage and period totals exact.
- US Source joint rows apply in BOTH US and CA, reconcile to Code=US, not Vendor
  or cross-joined marginal tables; required=false is explicit not applicable.
- Nullable Decimal JDBC, zero baselines, null rank metrics; ratio denominator
  distinct from bucket Open; source exact filter and preserved spelling.
- All rows retain scope-key covering ALL inputs including filters/selection/ranking;
  no request-scope identity collision. No implied cross-query transaction.
- business-timezone IANA and queried-at-local are present; trusted runtime supplies
  actual DST offset, including transition ambiguity handling.
- Missing required v3.1 explicitly blocks that capability; no silent legacy route.

## C. Real Agent behavior (pending deployment)
- INTERACTIVE questions ≤6 DIFFERENT LOGICAL FACT REQUESTS; discovery/
  materialization/pagination excluded, retries/corrections same slot. Instrument
  substantive fact scopes, not tool-call count. Report presets have no six-request
  cap: attempt all mandatory US/CA workflow. Broad history/optional scope bounded
  explicitly, never silently skipped. No per-Vendor/dimension fanout or direct/
  unconstrained source SQL/Python/CSV/mail runtime. Handle-only execute_eql results
  are materialized via discovered analyze_result: explicit discovered columns,
  read-only SELECT FROM data ORDER BY row-ordinal. No joins, other tables, writes,
  metric/denominator/rank recomputation or fact-dropping filters. Schema inspection
  first; do not presume a structured-read tool. Materialization isn't a new fact.
- Two real triggers: independent positive contribution Top-10; EVERY selected
  Vendor has metrics, signed Code+Source Top-5+Other, required US and fact→rule→
  risk→verification diagnosis. Summary-only/“others similar” is not complete.
- No trigger: Country table only; start test/开始测试 still forces applicable full
  drilldown, preserves NORMAL and TEST_FORCED, no fabricated alert.
- `start test` produces English tables/diagnoses/warnings/checks; `开始测试` Chinese.
  AutoUS and AUTO US same rule, OTHER_KNOWN rough date, UNKNOWN stays unknown;
  mixed Other never receives invented source-specific meaning or personal blame.
- Zero positive selection: explicit empty applicable path and unexercised multi-
  Vendor ETA, not fabricated negative Vendors. Test valid stale pair warns;
  invalid/unavailable dates and permissions never bypassed.
- Monday/Tues/weekend timing, missing weekdays, historical D−7..D−1 target range
  and prior before start; events-only no drilldown, explicit weekend override.
- October Los Angeles timestamps PDT UTC−07:00, post-transition November PST
  UTC−08:00; trust actual instant, no constant PST suffix or inferred today.
- Interactive count/ratio/share and decline ranking with correct filters, grain,
  denominator; full within-Vendor composition not only first ten buckets; current
  and history aren't mixed; RANGE stocks not summed or top-n assumed per date.
- Falling Vendor PD with positive contribution explains Country Open denominator.
- One failed dimension retains verified other dimensions/Vendors. Paired conflict
  raw evidence shown; association/report errors not blamed on Atlas. Unknown
  evidence, transport gaps and unexecuted interactive-limit scope are PARTIAL, not empty/complete
  or fabricated host interruption; no automatic promised next turn.
- Current Owner/contacts/PO only on ask, Buyer→Primary→Manager, traceable routing,
  unresolved explicit, no send/draft; PO ≤5/Vendor, ≤20/Country, first-one fairness,
  cap-not-shown distinguished from no rows. Interactive optional facts share the
  six-fact limit; report preset mandatory work is never stopped by that limit.
- Repeat identical fixed question three times: metrics/dates/selected Vendors,
  composition/module/field order stable. Save real tools and final output.

Transport success or offline PASS cannot satisfy B/C. Source of historical
request/return contradictions remains UNCONFIRMED without paired raw provenance.

## Precision and capture regression cases
- Ratio/share/relative percentages: TWO decimals; pp and paired contributions:
  FOUR decimals. Verification uses unrounded facts.
- Report available queried-at-local plus IANA; actual offset only from trusted
  corresponding instant/offset, not tool delivery/capture guesses. Ambiguity is
  UNVERIFIED; old queried-at-utc remains UTC, not repurposed.
- Projection/metadata/timestamp differences alone aren't DATA_CONFLICT; compare
  common semantic fields from two raw same-scope results. Missing projection is
  unknown, not contradiction. No six repeated module status strings required.

## ETA rule identity and recognized Source coverage
- Every rule has unique nonempty rule-id, risk-type and boolean
  requires-joint-facts; every US rule requires joint facts, non-US rules don't.
- Exact recognized set: AUTO NR, API ASN, API Order, BUYER, Block AUTOETA,
  Carrier API, EDI855, EDI856, EDI870, EDI3B2, EDI3A4C, EDI3A6, Vendor Email,
  Vendor Report, Vendor Web Site, Vendor Invoice, AUTOBUY, PO_REL, Auto Push,
  NIFI_AUTO_REC. Only use values actually present in verified joint facts.
- AutoUS/AUTO US precedence; case/outer whitespace normalize for diagnosis only.
  A novel raw Source, UNKNOWN, blank, or substring lookalike must NOT select
  OTHER_KNOWN. Retain novel raw labels/counts, diagnostic UNKNOWN. Dictionary
  membership never creates an absent bucket; OTHER aggregates don't reveal hidden
  Source membership. Source names add no invented business semantics or data table.

## Display EDN typed-key regression
Require module/composition map keys and composition module references to be
edn_io.Keyword, not plain str with a leading colon; verify composition closure
and Markdown field-order twin. Inject a quoted ":business_diagnosis" or
":country_summary" key and require validation to fail even though the custom
Keyword(str) compares equal to that string. Pop string keys before K reinsertion.

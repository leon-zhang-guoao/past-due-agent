# Composable analysis 3.4.0

This implementation is local and additive. Live Atlas/JDBC and real Agent
acceptance must be recorded separately; no host executor is installed.

## Responsibilities

User question → working analysis specification → supported operation plan → Atlas
facts/pages → evidence checks → requested answer. Follow-ups update only changed
parts of the specification. Daily/history/test remain presets with existing paths.

Prompt sets goals and evidence boundaries. Skill teaches composition and bounded
recovery. Atlas owns formulas, set filtering, aggregation/ranks, page metadata and
explicit Vendor coverage. A new question composed of existing operations is not a
new scenario. Add business capability only for genuinely missing data/operations.

## Implemented

- explore-v2-query/rows adds explicit Vendor-set selection and source SQL ordinal
  paging to all existing CURRENT/SNAPSHOT/PAIR/RANGE and five grouped grains.
- JSON array string is a declared encoding for scalar Atlas inputs; positive
  Int64 validation is server-side. Old numeric/single-value inputs are untouched.
- SQL shares the old metric pipeline. Country/population/Vendor denominators are
  independent of ETA filters. Top N remains grouped-row selection; the planner
  composes Vendor selection and bucket retrieval as separate dependent operations.
- CONTROL/FACT/VENDOR_COVERAGE distinguish invalid input, no source rows, org/ETA
  exclusion, selection exclusion and observed zero measurements.
- SQL pages at most500 non-control rows plus repeated CONTROL, before transport
  default1000. Ordinals/counts detect omissions. A logical-result checksum detects
  ordinary cross-page drift; it is not atomic snapshot isolation or a watermark.
- Six source scopes becomes a planning review, not a stop. Finite targeted worklists
  are allowed when no equivalent batch exists. Equivalent older routes remain
  available; no changed denominator or truncated national subset is acceptable.
- Actual-handle binding and progress remain Agent procedures. Prompt/Skill cannot
  automatically enforce correct host serialization or recover a forged handle.

## Preserved and remaining boundaries

All pre-existing resolver definitions and SQL bindings remain unchanged. Metric
version3.1.0, report modules, trigger thresholds/calendars, Owner/PO/contact display
and on-demand HTML behavior retain their existing meaning. No direct database,
mail, runtime service, arbitrary query language or required new table is added.

The new entry is aggregate analysis, not a replacement for current PO detail.
Detailed PO requests retain old targeted APIs. A selected Vendor with no rows in
the requested period cannot be called nonexistent without separate master evidence.
New source pages re-execute aggregation: smaller page size does not fix source
timeouts. Query plans and latency must be checked on the actual deployment.

## Validation and rollout

1. Establish old-scope SQL/contract baseline; compare each existing resolver and
   binding against committed HEAD. Verify generator idempotence and complete EQL.
2. Execute synthetic SQL: all time bases/grains, single-vs-set parity, denominators,
   duplicates/order, invalid JSON/overflow, zero-vs-missing, org/ETA exclusion,
   >1000 logical rows, page union, checksum drift and two-stage Top Vendor→ETA.
   The original13-ID request is reproduced on synthetic facts, not live counts.
3. Preserve real deployed Prompt/Skill/EDN before rollout. Deploy additive Atlas
   with existing approved IDs/source settings; run config-check and actual JDBC
   tests for JSON functions, checksum/Decimal values, SQL limits and source caps.
4. Install complete updated Skill+Prompt. Capability discovery selects v2 when
   available; old supported scopes continue through equivalent old interfaces.
5. Use fresh, unseen combinations and follow-ups from live-question-cases.json.
   Store actual inputs, tool calls/handles/pages, final answer and independent
   factual assessment. Repeat fixed scopes to measure variability. Offline
   fixtures are not language-model behavior or live performance evidence.
6. Roll back by switching to the saved Prompt/Skill; the additive Atlas entry may
   remain unused. Restoring the full graph requires the saved environment-specific
   config, not blindly replacing IDs with repository placeholders.

No production-readiness claim until steps3–5 have actual evidence. Packaging must
bind hashes/status to the files tested; historical ZIPs are not overwritten.

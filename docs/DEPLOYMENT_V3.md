# Deploy / rollback — Agent3.5.1, Atlas3.5.0/revision6

## Current 3.5.1 presentation rollout

Use releases/pastdue-agent-v3.5.1.zip and follow
[Business Storyteller installation](HTML_SUB_AGENT_3_5_1.md). Update the parent
Prompt/analysis Skill, install the child prompt/HTML Skill, and register the
native business-storyteller sub-agent callable by the parent with shared artifact
access. No Atlas redeployment for this patch. Actual platform registration and
end-to-end child invocation remain NOT_RUN.

The Skill-only ZIP contains both complete Skills, including the child prompt and
handoff contract; it does not install platform agent registrations or replace the
parent System Prompt. Keep deployment-specific agent IDs/settings outside the repo.

## Current 3.5 rollout

Use releases/pastdue-agent-v3.5.0.zip. Save the actual deployed Prompt, Skills,
approved graph/source IDs and settings first. Deploy additive atlas/charts/pastdue.edn,
verify platform schema, read-only/role access, real SQL/JDBC, page behavior and
production plans/latency; install agent/SYSTEM_PROMPT.md and the ENTIRE analysis
Skill. The HTML companion and source credentials are unchanged.
Verify per-date Top N, empty selection, original candidate denominator and separate
selection/display filters with actual facts. Do not claim atomic snapshot isolation.
Public fixture questions already informed design: use separate withheld questions
for unseen acceptance, and fresh agents with only data-plane access. All live
stages remain NOT_RUN. Existing38 resolver/SQL bindings remain unchanged.
Rollback uses the saved deployed set, not repository source placeholders.
See COMPOSABLE_ANALYSIS_3_5.md and VALIDATION_V3.md.

## Historical 3.4 rollout

Local package/SQL evidence does not certify deployment. Preserve the actually
deployed Prompt, complete Skills, approved graph IDs/source configuration and
version as a rollback set. Deploy additive atlas/charts/pastdue.edn with those
approved bindings; verify live config-check, JSON functions, Decimal/checksum
serialization, SQL paging and default transport1000. Existing resolvers/bindings
retain their meaning. Install agent/SYSTEM_PROMPT.md and complete analysis Skill
plus the unchanged HTML companion. Keep credentials outside these artifacts.

Run actual questions/follow-ups from tests/fixtures/live-question-cases.json
and record original requests/responses/handles/pages, final output and independent
assessment. Smaller page-size reduces transport size, not aggregation cost;
measure production query plans and latency before broad rollout.

Missing v2 permits discovered semantically equivalent old paths for supported
questions; it does not permit comma lists in old single-value filters. Rollback
uses the saved actual Prompt/Skill; the additive graph may remain unused, or be
restored with its saved approved IDs/settings. See COMPOSABLE_ANALYSIS_3_4.md.

## Historical 3.3.7 procedure (not current rollout)

Status: **PENDING_LIVE_PILOT**. No offline tests, platform config-check or real
Agent acceptance executed for this correction. See RESULT_READER_FIX_3_3_7.md.

## Update

From an existing structured3.3 deployment:
1. Save the deployed System Prompt, complete installed Skill, graph/source bindings
   and actual environment version as an environment-specific rollback set.
2. Replace agent/SYSTEM_PROMPT.md and the ENTIRE skill/pastdue-analysis/ (or matching
   releases/pastdue-analysis-skill-v3.3.7.zip). Do not mix old readers/policy/templates.
3. Use a fresh conversation so old instructions/handles are not carried forward.
   This is operational hygiene, not a fix for an unconfirmed host association bug.
4. **Do not redeploy Atlas for this patch**: atlas/charts/pastdue.edn is unchanged
   publication3.3.0/revision4, metric3.1.0. No new tables, schema or SQL required.

For an older deployment without compatible structured monitor/history/report/explore,
first deploy the compatible combined graph, preserving approved IDs, source bindings
and external credentials; have its owner perform actual config-check. Report source
page default/max1000 still needs real verification; a larger page does not certify
complete retained reads. Source paging remains separate from retained paging.

Full delivery: releases/pastdue-agent-v3.3.7.zip. No notification/CSV/chat/scripts/
Runtime upload required. Contacts/Owner/PO remain optional/current/display-only;
no mail drafts/sending. Development packaging Python is not an Agent dependency.

## Staged live gate

1. Same host and connection: execute small monitor → actual fresh handle → SQL read
   with only result_handle, nonempty sql, analysis_version2. Inspect actual outbound
   arguments and corresponding original response. Optional supported discovery is
   allowed when columns are unknown; success there is not business row retrieval.
2. On exactly-one error, at most one changed justified correction; never repeat the
   same invalid object. If host cannot preserve the valid shape, stop dependent reads.
   Prompt/Skill/EDN contains no executable sanitizer. Component root cause remains
   UNCONFIRMED until actual schema/model/outbound/response evidence isolates it.
3. Pin one Country/P/T using actual monitor/history. Run its full report EQL from
   STRUCTURED_PILOT_3_3.eql with real dates. Use result-reader.md's three typed reads;
   first compare dedicated Country fields, not Vendor placeholder zeros.
4. Manually compare one Vendor's metrics, both full ETA tables/Other and signed
   totals to original rows, then all selected Vendors for that Country. Capture
   ordinal union, truncation flags, check results and actual delivered unit count.
5. Expand to US/CA, Chinese/English start test, normal daily, events-only/history
   and interactive questions. Real fixed-scope repetitions assess variability;
   do not substitute an LLM-issued PASS for external review.

No provenance: no dependent use based on plausible totals. A response naming the
wrong handle is association error; unknown UI/export cause stays unconfirmed.
If only final visible calls are accessible, record that limit, don't invent traces.
If every possible retained call is malformed, escalate the call-boundary issue
instead of changing source SQL, disabling identity checks or selecting old blocks.
A direct-execute alternative is allowed only after its live schema, pagination,
complete row/cell return path and provenance are separately established; not an
implemented or assumed fallback in this release.

Record each gate's actual outcome, environment/connection, available call IDs,
inputs, handles/captures, rows/checks, final text and human findings. Only those
records can justify live acceptance. A full result with an abbreviated report is
incomplete delivery, not proof that the source failed or the full test completed.

## Rollback

Use the saved deployed Prompt+complete Skill+compatible graph as one set. Do not
mix individual patch files. Keep environment IDs/secrets external. Unchanged Atlas
normally requires no redeployment on rollback. Repository older ZIPs are historical
artifacts, not certified working fallbacks; some contain known read/report defects.
Git's pre-change commit preserves local source, not proof of a working deployment.
No database or mail side effect needs rollback. Preserve the failed real evidence
before retrying another version; avoid untraceable prompt-only release loops.

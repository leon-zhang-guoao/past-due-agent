# Evidence record — Agent3.3.2 / Atlas3.3.0

Status: **PENDING_LIVE_PILOT**. User-provided observations are not this release's
executed end-to-end acceptance. No offline tests or production access performed.

| Evidence / area | Observed state | Limit |
|---|---|---|
| Supplied3.2 run | Long Markdown cells truncated despite complete row count | Not fixed by claiming rows complete |
| Supplied3.3.0 run | Monitor execute returned handle; all3 reads failed exactly-one validation | No monitor values or Vendor/ETA analysis read |
| Supplied3.3.1 run | New reader instructions loaded, mixed selectors still sent; retry changed analysis_version | Calls didn't perform documented correction |
|3.3.1 provenance | Visible execute handle differed from read handle; old capture/expiry metadata | Association/root cause UNCONFIRMED, not automatically caching or model fault |
| Supplied manual test, user-open_atlas graph13 | Schema has no required fields; handle-only discovery accepted, matching handle, row-count2, columns, materialized:false | Discovery only; not SQL rows or full report; connection differs from failed logs |
|3.3.2 local implementation | Two-step concrete recipe, actual-column monitor projection, same-connection handle binding, evidence-limited attribution | Prompt/Skill procedure, no machine gate |
| Atlas combined EDN / SQL | Unchanged from3.3.0 publication/revision4 | This patch cannot certify source performance/schema/Decimal behavior |
| Offline suites / SQL fixtures / config validation | NOT_RUN | User request |
|3.3.2 same-entry selector-only call | NOT_RUN | No actual Atlas tool available here |
| SQL monitor row read in supplied manual test | NOT_PROVIDED | Next actual live gate |
|3.3.2 complete Agent report / human comparison | NOT_RUN | Must validate after reading actual values |
| Packaging | Allowlist/hash/archive integrity only | Not metric/LLM/platform acceptance |

Manual schema source was the user-pasted tool definition/test record; some
property descriptions were abbreviated. Its explicit types/absence of required
and the successful call support omission IN THAT TEST. Graph version13 doesn't
establish matching host schemas/connections. Don't report a fixed adapter or full
source coverage from that test. No hardcoded test handles are included in recipes.

Only packaging command: `python3 -B scripts/build_release.py --package-only`.
No offline runners are executed by that command. Manifest validation states remain
NOT_RUN with packaging-only evidence. See RESULT_READER_FIX_3_3_2.md and
DEPLOYMENT_V3.md for staged real validation. No independent output evaluator is installed.

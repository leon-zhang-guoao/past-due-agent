# Acceptance — delegated HTML presentation

| Case | Required evidence |
|---|---|
| Default DAILY/TEST | Complete Markdown + one HTML offer; no child call/file/WRITTEN |
| Explicit HTML | Parent binds/verifies facts, omits Markdown body, calls business-storyteller |
| Confirm export | Same prior bindings; no default Atlas/Metabase re-query or repeated Markdown |
| Missing bindings | BINDING with exact missing scope, no invented payload |
| Historical/interactive | No default offer; explicit complete payload preserves actual mode; partial input fails GATE |
| Child unavailable/fails/times out | DELEGATE with actual error, no parent inline fallback |
| Child missing file/fake path | Parent readback fails VERIFY; no WRITTEN |
| Child changes payload or visible table | VERIFY, at most one targeted child correction; same fact snapshot |
| Success | Actual child write + parent readback; exact payload/table/narrative match, real path(s) |
| CAPACITY split | Every Country slice gated, original scope/order covered exactly once; all paths listed |
| Ops/Demo | All ranking/ETA/check/coverage/gaps retained; controls, t seek, print and reduced motion checked in browser, or explicitly NOT_RUN |
| Parent/child failure | Existing Markdown remains valid; no deletion of disclosed artifacts |

Local checks (synthetic fixtures; not a real sub-agent execution):

```bash
python3 -B -m unittest discover -s tests -p 'test_html*.py'
```

The suite includes payload gates, rendering, actual temporary-file readback,
changed embedded facts, altered visible values, missing Vendor table, missing gaps,
duplicate payload IDs and actual-mode preservation. Release-tooling tests check
that the handoff, child prompt and verification helper survive packaging.

Live acceptance requires the platform's actual child registration, call/wait,
write and parent-readback trace. A correct prompt or local renderer does not
prove that the deployed platform can invoke the child.

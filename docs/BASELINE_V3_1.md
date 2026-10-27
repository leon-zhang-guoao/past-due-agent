# v3.1 development baseline

This is local development evidence, **not** a verified online release or live
acceptance. No production access was used.

## Before implementation

- Repository: `/Users/ameng/Documents/pastdue/past-due-agent`.
- Clean HEAD observed before implementation writes:
  `997ad8e0d4a871188e140952b39d89280add8c32`.
- Complete tracked-file SHA-256 snapshot:
  `tests/fixtures/baseline-v3.1.json`. Hashes were obtained from immutable HEAD
  blobs after the clean observation, so simultaneous subsequent edits cannot
  contaminate the snapshot. Raw HTML/CSV contents are not copied into it.
- Original v3.0 release archive SHA-256:
  `e9ad4c44937a7bb86b6f018811c72fc1c77c6ca327c8e16b3f740e67388cfe54`.
- Original pre-redesign rollback SHA-256:
  `5e46a2b018c5859c8555712d2b730e9a5dbf24409df7257cf0582dc1d0e481a0`.

## Pre-existing drift (not newly caused by v3.1)

The existing v3.0 release manifest differed from clean HEAD on **11 paths**:
README; system prompt; authoritative Atlas graph; redesign doc; Skill
`agents/openai.yaml`; query playbook; metric definitions; policy; report format;
and both table-display contracts. Exact paths and baseline hashes are in the
snapshot. Therefore old release `--check` reported release-hash drift before
this implementation; a clean Git tree did not mean release closure.

The baseline generator was executed against **temporary HEAD copies**, never
against the authoritative graph. It completed but did not reproduce HEAD:
`pastdue.monitor-query/relative-change-threshold` and
`pastdue.history-query/relative-change-threshold` changed `attribute/nullable?`
from false to true and replaced their required-input descriptions. This was a
known generator/checked-in-contract mismatch, not a database finding. Generator
and SQL ownership remain with the main implementation task.

## Safe rollback

`build_release.py` creates `releases/pre-v3.1-rollback.zip` from the recorded
HEAD commit using the explicit deployment-text allowlist. It verifies every
blob against the snapshot, scans for secrets/endpoints, excludes old manifests,
CSV, chat, HTML, runtime/development scripts and unrelated files, and writes an
internal hash manifest. It is a sanitized local rollback, not a certified prior
online deployment. An existing different rollback is never overwritten.
Existing v3.0 and pre-redesign archives are retained byte-for-byte.

## Captured-export regression evidence

`tests/fixtures/report-export-v3.1.json` is a sanitized extraction from the
local Chinese HTML export: text nodes → decoded escaped JSON → column/row
association → numeric identity/count whitelist. Handles were replaced; names,
addresses, credentials, full HTML, and report prose are absent. Source export
hashes preserve provenance without redistributing the exports.

Verified source associations:

- US Vendor 3493: contribution rank **8**, PastDue **275 → 319**, delta **+44**.
- CA Vendor 12570: Code **SH 1 → 8**, delta **+7**.
- CA Vendor 37108: Source **BUYER 0 → 9**, delta **+9**.

The English export contains no expanded tool payloads. Its raw-fact evidence
is **UNVERIFIED**, not a second conflicting raw response. A report copy error,
a wrong/expired/foreign handle, and a genuine same-scope raw-fact conflict are
separate classifications. No server root cause is established by these files.

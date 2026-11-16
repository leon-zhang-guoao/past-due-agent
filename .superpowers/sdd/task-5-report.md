# Task 5 Report — Demo mode motion

Status: DONE (with noted concerns)

## Changes
- `skill/pastdue-html-report/scripts/render_ops_shell.py`
  - `data-beat` on `#run`, `#country-metrics`, each `#country-*` (section), per-country `summary` / `coverage` wrapper divs, `#cross-country`, `#scope-gaps`.
  - Self-contained JS master timeline (rAF, no GSAP): `window.__pastdueTimeline` (`duration/beats/time/paused/play/pause/seek`), `body[data-t]`.
  - `?mode=demo`, `?t=N` (pause + seek; implies demo unless `?mode=` set), play/pause/replay button `#demo-play` (Demo only), autoplay once, no loop.
  - Ops: all visible; light ~0.6 s count-up on `xx.xx%→xx.xx%` cells in `#country-metrics`, always ends on exact original text.
  - `prefers-reduced-motion`: CSS forces `[data-beat]` opacity 1; JS skips count-up, Demo jumps to final state, no autoplay; listens for runtime change.
  - Demo only changes inline opacity/translateY (cleared at end); no `display:none`, no data/text omitted. Print shows everything.
  - Removed placeholder `demo-note` / `demo_hint`; added `play/pause/replay` labels (zh/en).
  - No isometric.
- `references/html-shell.md`: documented params, harness API, Ops vs Demo, reduced motion, optional isometric hook (docs only).
- `tests/test_html_report_render.py`: added `test_demo_harness_present`, `test_demo_beats_in_order_and_data_not_omitted`, `test_reduced_motion_forces_final_state_without_looping`, `test_no_isometric_by_default`, `test_gate_fail_raises` (written first; 3 failed before implementation).

## Verification
- `python -m unittest tests.test_html_report_render tests.test_html_report_payload` → 24 tests OK.
- Re-rendered sample (`scripts/out/pastdue-TEST-US-2026-10-23.html`, untracked, not committed).
- Spot-check: headless Edge hung in this environment, so behavior was checked with a Node stub-DOM harness running the emitted JS:
  - `?mode=ops` → all opacity 1
  - `?mode=demo&t=0` → all 0, paused, button "Play"
  - `?t=1.0` → demo, partial reveal (1, 0.5, 0, …), paused
  - `?mode=demo&t=99` → all 1, "Replay"
  - reduced-motion demo → all 1, paused
  - autoplay → reaches end at 4.5 s (1 section/country), stops, "Replay"

## Concerns
- No real-browser visual check (Edge headless hang); recommend a quick manual open of `?mode=demo` and `?t=1`.
- Workspace has many unrelated uncommitted/untracked files; only Task 5 files were committed.
- `html-shell.md` line endings: git warns LF→CRLF (repo autocrlf); content unaffected.

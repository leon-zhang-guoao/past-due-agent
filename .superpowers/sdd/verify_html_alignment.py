"""Audit SYSTEM_PROMPT + skills + edn alignment for HTML companion."""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    checks: list[tuple[str, bool, str]] = []

    prompt = (ROOT / "agent/SYSTEM_PROMPT.md").read_text(encoding="utf-8")
    analysis = (ROOT / "skill/pastdue-analysis/SKILL.md").read_text(encoding="utf-8")
    html_skill = (ROOT / "skill/pastdue-html-report/SKILL.md").read_text(encoding="utf-8")
    policy = (ROOT / "skill/pastdue-analysis/references/policy.edn").read_text(encoding="utf-8")
    report_fmt = (ROOT / "skill/pastdue-analysis/references/report-format.md").read_text(
        encoding="utf-8"
    )
    table_edn = (
        ROOT / "skill/pastdue-analysis/references/table-display-contract.edn"
    ).read_text(encoding="utf-8")
    openai = (ROOT / "skill/pastdue-analysis/agents/openai.yaml").read_text(encoding="utf-8")
    manifest = (
        ROOT / "skill/pastdue-analysis/references/release-manifest.edn"
    ).read_text(encoding="utf-8")
    build_rel = (ROOT / "scripts/build_release.py").read_text(encoding="utf-8")

    def ok(name: str, cond: bool, detail: str = "") -> None:
        checks.append((name, cond, detail))

    ok("prompt names pastdue-html-report", "pastdue-html-report" in prompt)
    ok("prompt auto after DAILY/TEST", "DAILY or TEST" in prompt or "DAILY/TEST" in prompt)
    ok("prompt skip HISTORICAL/INTERACTIVE", "HISTORICAL" in prompt and "INTERACTIVE" in prompt)
    ok("prompt Markdown authoritative", "Markdown remains authoritative" in prompt)
    ok(
        "prompt clarifies no host Markdown renderer",
        "no host Markdown report renderer" in prompt,
    )
    ok(
        "prompt optional render_ops_shell",
        "render_ops_shell.py" in prompt and "verification" in prompt,
    )
    ok("prompt forbids render_standard_report", "render_standard_report.py" in prompt)

    ok("analysis has HTML companion section", "### HTML companion" in analysis)
    ok("analysis links pastdue-html-report", "pastdue-html-report" in analysis)
    ok("html skill exists", html_skill.startswith("---"))
    ok(
        "html skill no Atlas re-query",
        "re-query" in html_skill.lower() and "atlas" in html_skill.lower(),
    )

    ok("policy html-companion map", ":html-companion" in policy)
    ok("policy auto-after DAILY TEST", '"DAILY"' in policy and '"TEST"' in policy)
    ok("policy runtime-python false", ":runtime-python false" in policy)
    ok(
        "policy verification script not runtime service",
        ":html-companion-verification-script-is-runtime-service false" in policy,
    )
    ok("policy native-renderer false", ":native-renderer-installed false" in policy)
    ok(
        "policy companion not host renderer",
        ":not-a-host-markdown-renderer true" in policy,
    )

    ok("report-format HTML companion section", "## HTML companion" in report_fmt)
    ok(
        "table-edn companion not host renderer",
        ":html-companion-is-not-host-renderer true" in table_edn,
    )
    ok("openai mentions pastdue-html-report", "pastdue-html-report" in openai)

    ok("manifest lists companion skill", ":html-companion-skill" in manifest)
    ok("manifest includes html SKILL.md", "skill/pastdue-html-report/SKILL.md" in manifest)
    ok("manifest includes payload-gate", "payload_gate.py" in manifest)
    ok("manifest includes render_ops_shell", "render_ops_shell.py" in manifest)

    ok("build_release knows HTML_SKILL", "HTML_SKILL" in build_rel)
    ok("build_release packages html refs", "HTML_REF_NAMES" in build_rel)

    # Hash consistency for critical files
    critical = [
        "agent/SYSTEM_PROMPT.md",
        "skill/pastdue-analysis/SKILL.md",
        "skill/pastdue-analysis/agents/openai.yaml",
        "skill/pastdue-analysis/references/policy.edn",
        "skill/pastdue-html-report/SKILL.md",
        "skill/pastdue-html-report/scripts/payload_gate.py",
        "skill/pastdue-html-report/scripts/render_ops_shell.py",
    ]
    for rel in critical:
        h = sha(ROOT / rel)
        matches = re.findall(rf'"{re.escape(rel)}"\s+"([0-9a-f]{{64}})"', manifest)
        ok(
            f"manifest hash {rel}",
            bool(matches) and all(m == h for m in matches),
            f"file={h} manif={matches}",
        )

    # Cross-contract: modes
    for mode in ("DAILY", "TEST"):
        ok(f"mode {mode} in prompt+policy+html", mode in prompt and mode in policy and mode in html_skill)

    print("HTML companion alignment audit")
    print("=" * 60)
    failed = 0
    for name, cond, detail in checks:
        mark = "PASS" if cond else "FAIL"
        if not cond:
            failed += 1
        line = f"[{mark}] {name}"
        if detail and not cond:
            line += f" — {detail}"
        print(line)
    print("=" * 60)
    print(f"{len(checks) - failed}/{len(checks)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

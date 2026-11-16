from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
mp = root / "skill/pastdue-analysis/references/release-manifest.edn"
text = mp.read_text(encoding="utf-8")

updates = {
    "agent/SYSTEM_PROMPT.md": "bb9a227a4512ac1a19849e2ba115e524066210298a8a71eba142bac3df1f3423",
    "skill/pastdue-analysis/SKILL.md": "e432e2c0bcfdd42ddcb9390a0b8167d9e4724214db7eac9d1585cebb12a0313a",
    "skill/pastdue-analysis/agents/openai.yaml": "6222b3021793c039c48801e060f1a49d7d520c80d4cd2b22a190dbc97cf5d48f",
    "skill/pastdue-analysis/references/policy.edn": "fa1829b313a4ed1d573b918d88012a5353fb850d0d6b9aa611383947f64f318f",
    "skill/pastdue-analysis/references/report-format.md": "1398a4631e20b18d7887c37f050d8308c39b6c3701238f50119e32a76809fd30",
    "skill/pastdue-analysis/references/table-display-contract.edn": "2f5244ae4b2ae963b4d443a7f47710e63cb5920da52d58436c329759eb8c6edd",
    "scripts/build_release.py": "d1eac2c30d81ab71ba002e85f1e85790e5cfd26e2d0e660b211979b595802e9a",
}
html = {
    "skill/pastdue-html-report/SKILL.md": "b0474d65f55a84e673781ac7b9cdd51a46b3b637b9c88ab49b480df91438ff03",
    "skill/pastdue-html-report/references/payload-contract.md": "991243c10465dd855fe6c2f4bd00aa387ae875a267b832bb04d77dd7cf591047",
    "skill/pastdue-html-report/references/html-shell.md": "2b0299e400b96a400add88607c6a30296a40093dc275fec778cd20ed270f4989",
    "skill/pastdue-html-report/references/acceptance-checklist.md": "dac34ec3d9b5a7ad60854345a353e099abaa5ae4d754f9940c94c4fd971577a9",
    "skill/pastdue-html-report/references/sample-payload.json": "f1bccc4cf1a1f3f8fd06bee76bc1b0e8991b872767cbf6b34d7e89bd4f08ab80",
    "skill/pastdue-html-report/scripts/payload_gate.py": "44e2a06b5ffcb95361f49deecd99034728e0815642c98d54c8972555dc5f1c18",
    "skill/pastdue-html-report/scripts/render_ops_shell.py": "3f7f558292facbf6cf592eab4b3db291265821f6c12d8c39b85dad26e1f559ab",
}


def repl_hash(body: str, path: str, digest: str) -> str:
    pat = rf'("{re.escape(path)}"\s+)"[0-9a-f]{{64}}"'
    new, n = re.subn(pat, rf'\1"{digest}"', body)
    if n == 0:
        raise SystemExit(f"missing path in manifest: {path}")
    return new


for path, digest in updates.items():
    text = repl_hash(text, path, digest)


def inject_after(body: str, anchor_path: str, html_map: dict, which_occurrence: int) -> str:
    lines = body.splitlines(keepends=True)
    hits = [i for i, line in enumerate(lines) if f'"{anchor_path}"' in line]
    if len(hits) < which_occurrence:
        raise SystemExit(
            f"anchor {anchor_path} occurrence {which_occurrence} not found; hits={len(hits)}"
        )
    i = hits[which_occurrence - 1]
    window = "".join(lines[i : i + 12])
    if "pastdue-html-report/SKILL.md" in window:
        return body
    insert = "".join(f'    "{p}" "{d}"\n' for p, d in html_map.items())
    lines.insert(i + 1, insert)
    return "".join(lines)


if "pastdue-html-report/SKILL.md" not in text:
    text = inject_after(text, "skill/pastdue-analysis/references/table-display-contract.md", html, 1)
    text = inject_after(text, "skill/pastdue-analysis/references/table-display-contract.md", html, 2)
    text = inject_after(text, "skill/pastdue-analysis/references/table-display-contract.md", html, 3)

if ":html-companion-skill" not in text:
    text = text.replace(
        ':skill-structure "NOT_RUN"',
        ':skill-structure "NOT_RUN"\n'
        '  :html-companion-skill "pastdue-html-report"\n'
        '  :html-companion-auto-after ["DAILY" "TEST"]',
    )

mp.write_text(text, encoding="utf-8")
print("manifest updated")
print("html present", "pastdue-html-report/SKILL.md" in text)
print("companion key", ":html-companion-skill" in text)

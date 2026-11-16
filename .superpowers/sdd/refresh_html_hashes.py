from pathlib import Path
import hashlib
import re

root = Path(__file__).resolve().parents[2]
files = [
    "skill/pastdue-html-report/SKILL.md",
    "skill/pastdue-html-report/references/acceptance-checklist.md",
    "skill/pastdue-html-report/references/html-shell.md",
    "skill/pastdue-html-report/scripts/render_ops_shell.py",
]
mp = root / "skill/pastdue-analysis/references/release-manifest.edn"
text = mp.read_text(encoding="utf-8")
for rel in files:
    h = hashlib.sha256((root / rel).read_bytes()).hexdigest()
    pat = rf'("{re.escape(rel)}"\s+)"[0-9a-f]{{64}}"'
    text, n = re.subn(pat, rf'\1"{h}"', text)
    print(rel, "updated", n, h[:12])
mp.write_text(text, encoding="utf-8")

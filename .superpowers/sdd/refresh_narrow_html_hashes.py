from pathlib import Path
import hashlib
import re

root = Path(__file__).resolve().parents[2]
files = [
    "agent/SYSTEM_PROMPT.md",
    "skill/pastdue-analysis/SKILL.md",
    "skill/pastdue-html-report/SKILL.md",
    "skill/pastdue-html-report/references/html-shell.md",
]
mp = root / "skill/pastdue-analysis/references/release-manifest.edn"
text = mp.read_text(encoding="utf-8")
for rel in files:
    h = hashlib.sha256((root / rel).read_bytes()).hexdigest()
    pat = rf'("{re.escape(rel)}"\s+)"[0-9a-f]{{64}}"'
    text, n = re.subn(pat, rf'\1"{h}"', text)
    print(rel, n, h[:12])
mp.write_text(text, encoding="utf-8")

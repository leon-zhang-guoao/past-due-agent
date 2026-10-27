#!/usr/bin/env python3
"""Run current offline contract/semantic regressions; not an LLM/online eval."""
import subprocess
import sys
from pathlib import Path
r=Path(__file__).resolve().parent
for name in ['validate_bundle.py','test_query_contracts.py','test_top10_changes.py','test_report_contracts.py']:
    subprocess.run([sys.executable,'-B',str(r/name)],check=True)
print('OFFLINE PASS. Real Agent/Atlas acceptance remains NOT RUN.')

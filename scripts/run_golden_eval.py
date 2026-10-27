#!/usr/bin/env python3
"""Development-only offline runner with machine-readable, hash-bound receipts."""
import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKS = ['validate_bundle.py', 'test_query_contracts.py', 'test_top10_changes.py',
          'test_report_contracts.py']


def run_checks(root=ROOT):
    checks = []
    for name in CHECKS:
        path = root / 'scripts' / name
        proc = subprocess.run([sys.executable, '-B', str(path)], cwd=root,
                              capture_output=True, text=True)
        checks.append(dict(name=name, status='PASS' if proc.returncode == 0 else 'FAIL',
                           exit_code=proc.returncode,
                           script_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                           output=proc.stdout + proc.stderr))
    proc = subprocess.run([sys.executable, '-B', '-m', 'unittest', 'discover',
                           '-s', str(root / 'tests'), '-p', 'test_release_tooling.py'],
                          cwd=root, capture_output=True, text=True)
    checks.append(dict(name='test_release_tooling.py', status='PASS' if proc.returncode == 0 else 'FAIL',
                       exit_code=proc.returncode,
                       script_sha256=hashlib.sha256((root / 'tests/test_release_tooling.py').read_bytes()).hexdigest(),
                       output=proc.stdout + proc.stderr))
    return dict(kind='OFFLINE_CONTRACT_CHECKS_NOT_LLM_EVAL',
                status='PASS' if all(c['exit_code'] == 0 for c in checks) else 'FAIL',
                graph_sha256=hashlib.sha256((root / 'atlas/charts/pastdue.edn').read_bytes()).hexdigest(),
                checks=checks, platform_config_check='NOT_RUN', deployed_jdbc_sql='NOT_RUN',
                real_agent_eval='NOT_RUN')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', type=Path, help='write actual offline result receipt')
    args = parser.parse_args()
    receipt = run_checks()
    if args.json:
        args.json.write_text(json.dumps(receipt, indent=2) + '\n')
    for check in receipt['checks']:
        print(check['name'], check['status'])
        print(check['output'], end='')
    print('OFFLINE', receipt['status'], 'Real Agent/Atlas acceptance remains NOT_RUN.')
    return 0 if receipt['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())

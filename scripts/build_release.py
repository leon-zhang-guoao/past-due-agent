#!/usr/bin/env python3
"""Sanitized release packaging with explicit --package-only; never asserts live acceptance.

Builds full/Skill ZIPs; validated builds also maintain the sanitized baseline rollback. Existing v3.0
artifacts are not overwritten. Manifests derive evidence from actual commands.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath
from edn_io import read, loads, dumps, K

ROOT = Path(__file__).resolve().parents[1]
VERSION = '3.1.3'
SKILL = 'skill/pastdue-analysis/'
MANIFEST_PATH = SKILL + 'references/release-manifest.edn'
MANIFEST = ROOT / MANIFEST_PATH
ARCHIVE = ROOT / f'releases/pastdue-agent-v{VERSION}.zip'
SKILL_ARCHIVE = ROOT / f'releases/pastdue-analysis-skill-v{VERSION}.zip'
ROLLBACK = ROOT / 'releases/pre-v3.1-rollback.zip'
BASELINE = ROOT / 'tests/fixtures/baseline-v3.1.json'
ROOT_FILES = {'agent/SYSTEM_PROMPT.md', 'agent/INVOCATION_EXAMPLES.md',
              'atlas/charts/pastdue.edn', 'atlas/sources.edn', 'README.md',
              'ACCEPTANCE_TESTS.md', 'DESIGN.md', 'AGENT_SKILL_EDN_DESIGN.md',
              'IMPLEMENTATION_NOTES.md', 'docs/PASTDUE_AGENT_REDESIGN.md', 'docs/API_V3.md',
              'docs/DEPLOYMENT_V3.md', 'docs/VALIDATION_V3.md', 'docs/VALIDATION_V3_1_1.md',
              'docs/REPORT_RENDERING_3_1_2.md', 'docs/COUNTRY_VENDOR_SUMMARY_3_1_3.md', 'docs/BASELINE_V3_1.md', 'docs/REPORT_SQL_FIX_3_1_1.md'}
REF_NAMES = {'atlas-query-playbook.md', 'completion-contract.md', 'current-context.md',
             'data-contract.md', 'eta-diagnostic-rules.edn', 'metric-definitions.md',
             'policy.edn', 'report-format.md', 'table-display-contract.edn',
             'table-display-contract.md'}
SECRET = re.compile(r'oat_v1\.|Authorization\s*:\s*Bearer\s+\S+|'
                    r'jdbc:clickhouse://(?!\$)|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|'
                    r'\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,})|'
                    r'(?i:password|api[_-]?key|access[_-]?token)\s*[=:]\s*["\'](?!\$|<|REDACTED)[^"\']{8,}["\']')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def allowed(path):
    p = PurePosixPath(path)
    if p.is_absolute() or '..' in p.parts or '\\' in path:
        return False
    return (path in ROOT_FILES or path in {SKILL + 'SKILL.md', SKILL + 'agents/openai.yaml'}
            or path.startswith(SKILL + 'references/') and p.name in REF_NAMES
            and len(p.parts) == 4)


def files(root=ROOT, skill_only=False):
    candidates = ROOT_FILES | {SKILL + 'SKILL.md', SKILL + 'agents/openai.yaml'} | {
        SKILL + 'references/' + n for n in REF_NAMES}
    return sorted(root / p for p in candidates if (root / p).is_file()
                  and (not skill_only or p.startswith(SKILL)))


def digest(root=ROOT, skill_only=False):
    return {str(p.relative_to(root)): sha(p.read_bytes()) for p in files(root, skill_only)}


def inspect_payload(payload):
    errors = []
    for name, data in payload.items():
        if not allowed(name):
            errors.append('Disallowed path: ' + name)
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError:
            errors.append('Non-text payload: ' + name)
            continue
        if SECRET.search(text):
            errors.append('Potential secret/endpoint: ' + name)
    return errors


def inspect(root=ROOT):
    payload = {str(p.relative_to(root)): p.read_bytes() for p in files(root)}
    return inspect_payload(payload)


def source_hashes(root=ROOT):
    """Bind receipts to scripts AND all inputs, not just a successful log line."""
    paths = files(root)
    paths += sorted((root / 'scripts').glob('*.py'))
    paths += sorted((root / 'tests').rglob('*.py'))
    paths += sorted((root / 'tests/fixtures').glob('*.json'))
    return {str(p.relative_to(root)): sha(p.read_bytes()) for p in paths if not p.is_symlink()}


def record_sql_test(path, python, root=ROOT):
    script = root / 'scripts/test_sql_fixtures.py'
    before = sha((root / 'atlas/charts/pastdue.edn').read_bytes())
    proc = subprocess.run([python, '-B', str(script)], cwd=root, capture_output=True, text=True)
    output = proc.stdout + proc.stderr
    # A skipped/missing engine is NOT successful execution evidence.
    executed = re.search(r'Ran (\d+) tests?', output)
    passed = proc.returncode == 0 and executed and int(executed[1]) > 0 and not re.search(r'\bskipped\b', output, re.I)
    receipt = dict(kind='LOCAL_SYNTHETIC_SQL_NOT_DEPLOYED_JDBC', status='PASS' if passed else 'FAIL',
                   exit_code=proc.returncode, graph_sha256=before,
                   script_sha256=sha(script.read_bytes()), command=[python, '-B', 'scripts/test_sql_fixtures.py'],
                   output=output, output_sha256=sha(output.encode()),
                   tests_run=int(executed[1]) if executed else 0)
    if before != sha((root / 'atlas/charts/pastdue.edn').read_bytes()):
        receipt['status'] = 'FAIL'
    path.write_text(json.dumps(receipt, indent=2) + '\n')
    print(output, end='')
    return receipt


def validate_sql_receipt(receipt, root=ROOT):
    required = {'kind', 'status', 'exit_code', 'graph_sha256', 'script_sha256',
                'output', 'output_sha256', 'tests_run'}
    if not isinstance(receipt, dict) or not required <= receipt.keys():
        raise ValueError('Incomplete SQL receipt; use --record-sql-test')
    if receipt['kind'] != 'LOCAL_SYNTHETIC_SQL_NOT_DEPLOYED_JDBC':
        raise ValueError('Not a local synthetic SQL receipt')
    if receipt['graph_sha256'] != sha((root / 'atlas/charts/pastdue.edn').read_bytes()):
        raise ValueError('SQL receipt graph drift')
    if receipt['script_sha256'] != sha((root / 'scripts/test_sql_fixtures.py').read_bytes()):
        raise ValueError('SQL receipt script drift')
    if receipt['output_sha256'] != sha(receipt['output'].encode()):
        raise ValueError('SQL receipt log digest mismatch')
    ran = re.search(r'Ran (\d+) tests?', receipt['output'])
    if receipt['status'] == 'PASS' and (receipt['exit_code'] != 0 or not ran
            or int(ran[1]) != receipt['tests_run'] or receipt['tests_run'] <= 0
            or re.search(r'\b(?:skipped|FAILED|ERROR:)\b', receipt['output'])):
        raise ValueError('SQL receipt PASS contradicts actual execution')
    if receipt['status'] not in ('PASS', 'FAIL'):
        raise ValueError('SQL receipt invalid status')
    return receipt


def zip_write(path, payload):
    path.parent.mkdir(exist_ok=True, parents=True)
    tmp = path.with_suffix('.zip.tmp')
    try:
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, data)
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)


def rollback(root=ROOT):
    """Use recorded clean HEAD blobs, never the live dirty working tree."""
    baseline = json.loads((root / 'tests/fixtures/baseline-v3.1.json').read_text())
    payload = {}
    for name, expected in baseline['sha256'].items():
        if not allowed(name):
            continue
        data = subprocess.check_output(['git', 'show', baseline['commit'] + ':' + name], cwd=root)
        if sha(data) != expected:
            raise ValueError('Baseline blob digest mismatch: ' + name)
        payload[name] = data
    errors = inspect_payload(payload)
    if errors:
        raise ValueError('; '.join(errors))
    metadata = dict(kind='SANITIZED_LOCAL_BASELINE_NOT_VERIFIED_ONLINE_RELEASE',
                    commit=baseline['commit'], sha256={k: sha(v) for k,v in payload.items()})
    payload['rollback-manifest.json'] = (json.dumps(metadata, indent=2, sort_keys=True) + '\n').encode()
    target = root / 'releases/pre-v3.1-rollback.zip'
    if target.exists():
        with zipfile.ZipFile(target) as z:
            if len(z.namelist()) != len(payload) or set(z.namelist()) != set(payload) or any(z.read(k) != v for k,v in payload.items()):
                raise ValueError('Existing v3.1 rollback differs; refusing overwrite')
    else:
        zip_write(target, payload)
    return metadata


def _manifest(full, skill, offline, sql, inputs, doc, baseline):
    manifest = {
        ':release-version': VERSION, ':status': 'PENDING_LIVE_ACCEPTANCE',
        ':atlas-semantic-format': '2.0.0',
        ':required-resolvers': {rid: r[':input'] for rid,r in doc[':execution'][':resolvers'].items() if rid.startswith('pastdue_v3_')},
        ':offline-contracts': offline['status'], ':skill-structure': next((c['status'] for c in offline['checks'] if c['name'] == 'validate_bundle.py'), 'NOT_RUN'),
        ':local-clickhouse-sql': sql['status'] if sql else 'NOT_RUN',
        ':platform-config-check': 'NOT_RUN', ':deployed-jdbc-sql': 'NOT_RUN', ':real-agent-eval': 'NOT_RUN',
        ':evidence-kind': 'PACKAGING_ONLY_NO_VALIDATION' if offline['status'] == 'NOT_RUN' else 'OFFLINE_ONLY_NOT_LLM_OUTPUT_PROOF',
        ':validation-receipt-json': json.dumps(dict(offline=offline, local_sql=sql or {'status':'NOT_RUN'}), sort_keys=True),
        ':validation-input-sha256': {} if offline['status'] == 'NOT_RUN' else inputs,
        ':artifact-input-sha256': inputs,
        ':agent-contract-version': VERSION,
        ':required-atlas-publication': '3.1.1',
        ':compatible-metric-version': '3.1.0',
        ':manifest-self-hash': 'excluded-to-avoid-recursion',
        ':sha256': {k: sha(v) for k,v in full.items()},
        ':skill-sha256': {k: sha(v) for k,v in skill.items()},
        ':baseline-commit': baseline['commit'],
    }
    return {K(k): v for k,v in manifest.items()}


def check_archive(path, hashes, manifest_bytes):
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        expected = set(hashes) | {MANIFEST_PATH}
        if len(names) != len(set(names)) or set(names) != expected:
            raise ValueError(str(path) + ': archive closure/duplicate path failure')
        payload = {name: z.read(name) for name in hashes}
        errors = inspect_payload(payload)
        if errors:
            raise ValueError('; '.join(errors))
        for name, data in payload.items():
            if sha(data) != hashes[name]:
                raise ValueError(str(path) + ': archive digest mismatch ' + name)
        if z.read(MANIFEST_PATH) != manifest_bytes:
            raise ValueError(str(path) + ': manifest mismatch')


def check_release(root=ROOT):
    mpath = root / MANIFEST_PATH
    m = read(mpath)
    if m.get(':release-version') != VERSION:
        raise ValueError('Release version mismatch')
    if m[':sha256'] != digest(root) or m[':skill-sha256'] != digest(root, True):
        raise ValueError('Release source hashes drift')
    if m[':artifact-input-sha256'] != source_hashes(root):
        raise ValueError('Validation receipt inputs drift; rerun build')
    receipt = json.loads(m[':validation-receipt-json'])
    if m[':offline-contracts'] != receipt['offline']['status']:
        raise ValueError('Offline status disagrees with receipt')
    if m[':offline-contracts'] == 'NOT_RUN':
        if (m[':evidence-kind'] != 'PACKAGING_ONLY_NO_VALIDATION'
                or receipt['offline']['checks'] or m[':validation-input-sha256']
                or m[':skill-structure'] != 'NOT_RUN'):
            raise ValueError('Packaging-only receipt claims validation')
    elif m[':offline-contracts'] != 'PASS' or m[':validation-input-sha256'] != source_hashes(root):
        raise ValueError('Offline validation did not PASS or inputs drift')
    if receipt['local_sql']['status'] != 'NOT_RUN':
        validate_sql_receipt(receipt['local_sql'], root)
    if m[':local-clickhouse-sql'] != receipt['local_sql']['status']:
        raise ValueError('SQL status disagrees with receipt')
    for key in (':platform-config-check', ':deployed-jdbc-sql', ':real-agent-eval'):
        if m[key] != 'NOT_RUN':
            raise ValueError('Unsupported live acceptance claim')
    check_archive(root / f'releases/pastdue-agent-v{VERSION}.zip', m[':sha256'], mpath.read_bytes())
    check_archive(root / f'releases/pastdue-analysis-skill-v{VERSION}.zip', m[':skill-sha256'], mpath.read_bytes())
    return m


def build(root=ROOT, sql_receipt=None, package_only=False):
    if package_only and sql_receipt is not None:
        raise ValueError('--package-only cannot import a prior SQL validation receipt')
    before = source_hashes(root)
    payload = {str(p.relative_to(root)): p.read_bytes() for p in files(root)}
    errors = inspect_payload(payload)
    if errors:
        raise ValueError('; '.join(errors))
    if package_only:
        offline = dict(status='NOT_RUN', checks=[], reason='Explicit packaging-only request; no offline tests executed')
    else:
        from run_golden_eval import run_checks
        offline = run_checks(root)
    if not package_only and offline['status'] != 'PASS':
        for result in offline['checks']:
            print(result['name'], result['status'], result.get('output', ''))
        raise ValueError('Offline validation failed; no release emitted')
    if sql_receipt is not None:
        validate_sql_receipt(sql_receipt, root)
        if sql_receipt['status'] != 'PASS':
            raise ValueError('SQL receipt failed; no release emitted')
    if source_hashes(root) != before:
        raise ValueError('Inputs changed during validation; retry after edits settle')
    skill = {k:v for k,v in payload.items() if k.startswith(SKILL)}
    baseline = json.loads((root / 'tests/fixtures/baseline-v3.1.json').read_text()) if package_only else rollback(root)
    manifest = _manifest(payload, skill, offline, sql_receipt, before,
                         read(root / 'atlas/charts/pastdue.edn'), baseline)
    manifest_bytes = (dumps(manifest) + '\n').encode()
    if SECRET.search(manifest_bytes.decode()):
        raise ValueError('Potential secret in validation receipt; sanitize logs before packaging')
    (root / MANIFEST_PATH).write_bytes(manifest_bytes)
    for name, data in [(f'pastdue-agent-v{VERSION}.zip', payload),
                       (f'pastdue-analysis-skill-v{VERSION}.zip', skill)]:
        zip_write(root / 'releases' / name, dict(data, **{MANIFEST_PATH: manifest_bytes}))
    check_release(root)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='verify both ZIPs, receipts, hashes and allowlist')
    parser.add_argument('--package-only', action='store_true', help='package without executing offline tests; all unexecuted validation stays NOT_RUN')
    parser.add_argument('--sql-receipt', type=Path, help='validated graph-bound local SQL receipt (otherwise NOT_RUN)')
    parser.add_argument('--record-sql-test', type=Path, help='run synthetic SQL tests and save actual JSON receipt; no packaging')
    parser.add_argument('--sql-python', default=sys.executable, help='temporary dev interpreter with chdb, for --record-sql-test')
    args = parser.parse_args()
    if args.package_only and (args.check or args.sql_receipt or args.record_sql_test):
        parser.error('--package-only cannot be combined with validation/check/SQL receipt options')
    try:
        if args.record_sql_test:
            receipt = record_sql_test(args.record_sql_test, args.sql_python)
            return 0 if receipt['status'] == 'PASS' else 1
        if args.check:
            check_release()
            print('Archive integrity verified; this is not Agent/Atlas acceptance')
        else:
            receipt = json.loads(args.sql_receipt.read_text()) if args.sql_receipt else None
            result = build(sql_receipt=receipt, package_only=args.package_only)
            print('Built', ARCHIVE, 'and', SKILL_ARCHIVE)
            print('PENDING_LIVE_ACCEPTANCE; local SQL:', result[':local-clickhouse-sql'], '; live checks NOT_RUN')
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as exc:
        print('FAIL', exc)
        return 1


if __name__ == '__main__':
    sys.exit(main())

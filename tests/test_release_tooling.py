"""Adversarial packaging tests run in temporary roots, never touch old releases."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import build_release as release
from edn_io import read, write, K


class ReleaseTooling(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for path in release.files(ROOT) + list((ROOT/'scripts').glob('*.py')) + list((ROOT/'tests/fixtures').glob('*.json')):
            target = self.root / path.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
        self.offline = dict(status='PASS', checks=[dict(name='validate_bundle.py',status='PASS')])
        self.fake_baseline = dict(commit='clean-head')

    def build(self):
        with patch.object(release, 'run_checks', return_value=self.offline), patch.object(release,'rollback',return_value=self.fake_baseline):
            return release.build(self.root)

    def receipt(self):
        output = '.......\nRan 7 tests in 1.0s\n\nOK\n'
        return dict(kind='LOCAL_SYNTHETIC_SQL_NOT_DEPLOYED_JDBC',status='PASS',exit_code=0,
                    graph_sha256=release.sha((self.root/'atlas/charts/pastdue.edn').read_bytes()),
                    script_sha256=release.sha((self.root/'scripts/test_sql_fixtures.py').read_bytes()),
                    output=output,output_sha256=release.sha(output.encode()),tests_run=7)

    def test_both_archives_and_not_run_manifest(self):
        self.build()
        m=release.check_release(self.root)
        self.assertEqual(m[':local-clickhouse-sql'],'NOT_RUN')
        for name in ('pastdue-agent-v3.1.0.zip','pastdue-analysis-skill-v3.1.0.zip'):
            with zipfile.ZipFile(self.root/'releases'/name) as z:
                self.assertIn(release.MANIFEST_PATH,z.namelist())
                self.assertFalse(any(p.endswith(('.csv','.html','.py','.zip')) for p in z.namelist()))
                if 'skill-' in name:
                    self.assertTrue(all(p.startswith(release.SKILL) for p in z.namelist()))

    def test_allowlist_and_credentials(self):
        for name in ['chat.md','test-run/x.html','runtime/x.py','skill/pastdue-analysis/scripts/x.py',
                     'skill/pastdue-analysis/references/x.csv','../README.md','/README.md',
                     'skill/pastdue-analysis/references/nested/policy.edn','.env']:
            self.assertFalse(release.allowed(name),name)
        for secret in ['oat_v1.example','Authorization: Bearer abcdef',
                       'jdbc:clickhouse://production:8123','-----BEGIN PRIVATE KEY-----',
                       'api_key="123456789-secret"']:
            self.assertTrue(release.inspect_payload({'README.md':secret.encode()}))
        (self.root/'chat.md').write_text('not allowed')
        (self.root/'skill/pastdue-analysis/references/secret.md').write_text('not allowed')
        self.build()
        with zipfile.ZipFile(self.root/'releases/pastdue-agent-v3.1.0.zip') as z:
            self.assertNotIn('chat.md',z.namelist())
            self.assertNotIn('skill/pastdue-analysis/references/secret.md',z.namelist())

    def test_existing_v30_retained(self):
        directory=self.root/'releases';directory.mkdir(exist_ok=True)
        old=directory/'pastdue-agent-v3.0.0.zip';old.write_bytes(b'old immutable')
        self.build();self.assertEqual(old.read_bytes(),b'old immutable')

    def test_independent_skill_zip_tamper_rejected(self):
        self.build()
        path=self.root/'releases/pastdue-analysis-skill-v3.1.0.zip'
        with zipfile.ZipFile(path) as z:payload={n:z.read(n) for n in z.namelist()}
        payload[release.SKILL+'SKILL.md'] += b'\ndrift'
        release.zip_write(path,payload)
        with self.assertRaisesRegex(ValueError,'digest mismatch'):
            release.check_release(self.root)

    def test_extra_or_duplicate_archive_member_rejected(self):
        self.build();path=self.root/'releases/pastdue-agent-v3.1.0.zip'
        with zipfile.ZipFile(path,'a') as z:z.writestr('chat.md','bad')
        with self.assertRaisesRegex(ValueError,'closure'):
            release.check_release(self.root)

    def test_validation_input_and_payload_drift(self):
        self.build();p=self.root/'scripts/analysis_validation.py';p.write_text(p.read_text()+'\n# drift\n')
        with self.assertRaisesRegex(ValueError,'inputs drift'):
            release.check_release(self.root)
        self.build();p=self.root/'README.md';p.write_text(p.read_text()+'\n# drift\n')
        with self.assertRaisesRegex(ValueError,'source hashes drift'):
            release.check_release(self.root)

    def test_actual_failure_refuses_release(self):
        self.offline['status']='FAIL'
        with self.assertRaisesRegex(ValueError,'validation failed'):
            self.build()
        self.assertFalse((self.root/'releases/pastdue-agent-v3.1.0.zip').exists())

    def test_sql_receipt_bound_and_not_fabricated(self):
        receipt=self.receipt();self.assertEqual(release.validate_sql_receipt(receipt,self.root)['status'],'PASS')
        for field,value in [('graph_sha256','wrong'),('script_sha256','wrong'),
                            ('output_sha256','wrong'),('exit_code',1),('tests_run',0)]:
            bad=copy.deepcopy(receipt);bad[field]=value
            with self.assertRaises(ValueError):release.validate_sql_receipt(bad,self.root)
        bad=copy.deepcopy(receipt);bad['output']='Ran 7 tests in 1s\nOK (skipped=7)';bad['output_sha256']=release.sha(bad['output'].encode())
        with self.assertRaises(ValueError):release.validate_sql_receipt(bad,self.root)

    def test_sql_receipt_status_manifest(self):
        receipt=self.receipt()
        with patch.object(release,'run_checks',return_value=self.offline), patch.object(release,'rollback',return_value=self.fake_baseline):
            m=release.build(self.root,receipt)
        self.assertEqual(m[':local-clickhouse-sql'],'PASS')
        self.assertEqual(m[':deployed-jdbc-sql'],'NOT_RUN')

    def test_baseline_hashes_and_existing_rollback_safe(self):
        baseline=json.loads((ROOT/'tests/fixtures/baseline-v3.1.json').read_text())
        self.assertEqual(baseline['initial_git_status'],'clean')
        self.assertEqual(len(baseline['existing_release_drift']),11)
        # Real immutable baseline blobs, temporary output; no working-tree copy.
        shutil.copytree(ROOT/'.git',self.root/'.git')
        metadata=release.rollback(self.root)
        self.assertEqual(metadata['commit'],baseline['commit'])
        path=self.root/'releases/pre-v3.1-rollback.zip'
        with zipfile.ZipFile(path) as z:
            self.assertFalse(any(n.endswith(('.csv','.html','.py')) or 'runtime/' in n for n in z.namelist()))
        before=path.read_bytes();release.rollback(self.root)
        self.assertEqual(path.read_bytes(),before)
        path.write_bytes(b'not a zip')
        with self.assertRaises(zipfile.BadZipFile):release.rollback(self.root)


if __name__=='__main__':unittest.main()

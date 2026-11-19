#!/usr/bin/env python3
"""Run current-v2 semantic regressions on native ClickHouse22.8.21.38 local.

Development only, synthetic data, temporary directories. No server/credentials.
Set CLICKHOUSE22_BIN to an existing trusted binary; never downloads or installs it.
Missing binary is an explicit skip, not compatibility acceptance.
"""
import os
import re
import subprocess
import tempfile
import types
import unittest
from pathlib import Path
import test_sql_fixtures as fixtures
from test_current_v2 import CurrentV2

BINARY=os.environ.get('CLICKHOUSE22_BIN')

class NativeSession:
    def __init__(self,_path):self.setup=[]
    def query(self,query,fmt='CSV'):
        if not re.match(r'\s*(WITH|SELECT)\b',query,re.I):
            self.setup.append(query)
            return ''
        with tempfile.TemporaryDirectory(prefix='pastdue-ch22-query-') as directory:
            path=Path(directory)/'fixture.sql'
            path.write_text(';\n'.join(self.setup+[query])+';\n')
            proc=subprocess.run([BINARY,'local','--multiquery','--queries-file',str(path)],
                                cwd=directory,capture_output=True,text=True,timeout=60)
        if proc.returncode:raise RuntimeError(proc.stderr)
        return proc.stdout

@unittest.skipUnless(BINARY,'CLICKHOUSE22_BIN missing;22.8 compatibility NOT VERIFIED')
class ClickHouse22Current(CurrentV2):
    __unittest_skip__=not bool(BINARY)
    @classmethod
    def setUpClass(cls):
        proc=subprocess.run([BINARY,'local','--query','SELECT version()'],capture_output=True,text=True,check=True)
        if proc.stdout.strip()!='22.8.21.38':raise RuntimeError('Expected22.8.21.38, got '+proc.stdout.strip())
        original=fixtures.session
        try:
            fixtures.session=types.SimpleNamespace(Session=NativeSession)
            fixtures.SQLFixtures.setUpClass.__func__(cls)
        finally:fixtures.session=original
    def test_denominator_org_parity(self):
        # Independent fixture expectations, not explore-v3: its22.8 JOIN is invalid.
        from decimal import Decimal
        expected={
            '':{(1,'NR','EDI'):(1,1,2,1),(1,'SH','EDI'):(1,0,2,1),(2,'US','AutoUS'):(1,1,1,1)},
            'A':{(1,'NR','EDI'):(1,1,2,1),(1,'SH','EDI'):(1,0,2,1)},
            'MISSING':{},
        }
        for denominator in ('COUNTRY_ALL','FILTERED_POPULATION','WITHIN_VENDOR'):
            for org,facts in expected.items():
                with self.subTest(denominator=denominator,org=org):
                    rows=self.current(denominator_scope=denominator,master_primary_filter=org)
                    actual={(int(r['vendor_code']),r['eta_code'],r['eta_source']):r for r in rows if r['record_type']=='FACT'}
                    self.assertEqual(set(actual),set(facts))
                    filtered_pd=2 if not org else 1 if org=='A' else 0
                    for identity,(op,pd,vendor_op,vendor_pd) in facts.items():
                        row=actual[identity]
                        share_denominator=3 if denominator=='COUNTRY_ALL' else filtered_pd if denominator=='FILTERED_POPULATION' else vendor_pd
                        self.assertEqual((int(row['open_line_count']),int(row['past_due_count'])),(op,pd))
                        self.assertEqual((int(row['vendor_open_count']),int(row['vendor_past_due_count'])),(vendor_op,vendor_pd))
                        self.assertEqual(int(row['ratio_denominator']),vendor_op)
                        self.assertEqual(int(row['share_denominator']),share_denominator)
                        self.assertLess(abs(Decimal(str(row['vendor_past_due_ratio']))-Decimal(vendor_pd)/vendor_op),Decimal('0.000000000000001'))
                        self.assertLess(abs(Decimal(str(row['past_due_share']))-Decimal(pd)/share_denominator),Decimal('0.000000000000001'))
    def test_original_code49_repro_and_measured_single_vendor(self):
        rows=self.current(vendor_codes_json='[1]',page_size=100,_atlas_narrow=True)
        facts=[r for r in rows if r['record_type']=='FACT']
        self.assertEqual(sum(int(r['open_line_count']) for r in facts),2)
        self.assertEqual(sum(int(r['past_due_count']) for r in facts),1)
        self.assertEqual(len(rows),4)
        self.assertEqual({r['scope_status'] for r in rows if r['record_type']=='VENDOR_COVERAGE'},{'MATCHED'})

if __name__=='__main__':
    # Explicit suite avoids re-running imported modern CurrentV2 as evidence22.
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ClickHouse22Current))
    raise SystemExit(not result.wasSuccessful())

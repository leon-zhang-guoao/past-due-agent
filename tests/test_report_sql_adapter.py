"""Optional synthetic chdb integration of actual SQL output with offline checker.

No production/Atlas calls. Running this module includes the SQL owner's suite.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'scripts'))
import test_sql_fixtures as sql_fixture
from analysis_validation import validate_atlas_report_rows


@unittest.skipIf(sql_fixture.session is None, 'chdb not installed; SQL adapter NOT VERIFIED')
class ReportSQLAdapter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sql_fixture.SQLFixtures.setUpClass()
        cls.fixture = sql_fixture.SQLFixtures(methodName='test_v31_report')

    @classmethod
    def tearDownClass(cls):
        sql_fixture.SQLFixtures.tearDownClass()

    def test_server_summary_reconciles_default(self):
        rows = self.fixture.sql('pastdue_v3_report_query_rows')
        result = validate_atlas_report_rows(rows)
        self.assertEqual(result['status'], 'PASS', result)

    def test_single_vendor_preserves_global_rank(self):
        rows = self.fixture.sql('pastdue_v3_report_query_rows',
                                selection_mode='SINGLE_VENDOR', vendor_code=12, top_n=1)
        vendor = next(r for r in rows if r['record_type']=='VENDOR_METRICS')
        self.assertGreater(int(vendor['contribution_rank']), 1)
        result = validate_atlas_report_rows(rows)
        self.assertEqual(result['status'], 'PASS', result)

    def test_prior_only_vendor_and_empty_us_dimension(self):
        rows=self.fixture.sql('pastdue_v3_report_query_rows',country_code='GONE',
                              selection_mode='SINGLE_VENDOR',vendor_code=1,top_n=1)
        self.assertEqual(validate_atlas_report_rows(rows)['status'],'PASS')
        us=next(r for r in rows if r['record_type']=='VENDOR_CHECK' and r['dimension']=='us')
        self.assertIs(us['required'],False)
        self.assertEqual(int(us['expected_bucket_count']),0)

    def test_empty_controls_are_not_missing_facts(self):
        for cc in ['ZERO','EMPTY']:
            rows=self.fixture.sql('pastdue_v3_report_query_rows',country_code=cc)
            self.assertEqual(validate_atlas_report_rows(rows)['status'],'PASS')


def load_tests(loader, tests, pattern):
    suite = unittest.TestSuite()
    suite.addTests(loader.loadTestsFromTestCase(sql_fixture.SQLFixtures))
    suite.addTests(loader.loadTestsFromTestCase(ReportSQLAdapter))
    return suite


if __name__=='__main__':unittest.main()

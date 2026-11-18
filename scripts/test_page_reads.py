"""Execute shipped retained projections over real synthetic bound-SQL rows.

SQLite is a dev-only adapter for these simple SELECTs, not a deployed reader.
Does not simulate model decisions, host correlation or deployed DuckDB errors.
"""
import json
import re
import sqlite3
import unittest
from pathlib import Path
from edn_io import read
import test_sql_fixtures as fixtures

ROOT = Path(__file__).resolve().parents[1]
REFS = ROOT / 'skill/pastdue-analysis/references'


def retained_read(rows, kind=None, sql=None):
    """Read the actual SQL artifact, including every explicit field and order."""
    columns = list(rows[0])
    with sqlite3.connect(':memory:') as db:
        db.row_factory = sqlite3.Row
        db.execute('CREATE TABLE data (' + ','.join('"'+c+'"' for c in columns) + ')')
        db.executemany('INSERT INTO data VALUES ('+','.join('?' for _ in columns)+')',
                       [[row[c] for c in columns] for row in rows])
        result = [dict(row) for row in db.execute(sql if sql is not None else
                                                  (REFS/(kind+'-page-read.sql')).read_text())]
        # SQLite stores booleans as integers; restore the source-declared type
        # for this dev adapter instead of weakening production validation.
        for row in result:
            for key in ('counts_match','required','has_more'):
                if key in row and row[key] is not None:
                    row[key]=bool(row[key])
        return result


@unittest.skipIf(fixtures.session is None, 'chdb missing; source/retained projection NOT VERIFIED')
class PageReads(unittest.TestCase):
    setUpClass = classmethod(fixtures.SQLFixtures.setUpClass.__func__)
    tearDownClass = classmethod(fixtures.SQLFixtures.tearDownClass.__func__)
    sql = fixtures.SQLFixtures.sql

    def check_page(self, kind, rows):
        reread = retained_read(rows, kind)
        eql = read(REFS/(kind+'-v2-query.eql'))
        attrs = eql[':query'][0][':pastdue.'+kind+'-v2-query/rows']
        self.assertEqual(set(reread[0]), {a.rsplit('/',1)[-1].replace('-','_') for a in attrs})
        # No record-type filters that could silently lose a middle page's metrics.
        self.assertEqual([r['row_ordinal'] for r in reread], [r['row_ordinal'] for r in rows])
        for original, returned in zip(rows, reread):
            self.assertEqual(original, returned)
        return reread

    def original_typed_reads(self, rows):
        # Execute the deployed instructions, not a test-only projection copy.
        instructions = (REFS/'result-reader.md').read_text().split('### A. Country Control',1)[1]
        projections = re.findall(r'```sql\n(.*?)```', instructions, re.S)
        self.assertEqual(len(projections),2)
        projections.append((REFS/'report-eta-read.sql').read_text())
        parts = [retained_read(rows,sql=q) for q in projections]
        original = {int(r['row_ordinal']):r for r in rows}
        merged = {}
        for part in parts:
            for row in part:
                ordinal = int(row['row_ordinal'])
                self.assertNotIn(ordinal,merged, 'typed partitions must be disjoint')
                expected = {k:original[ordinal][k] for k in row}
                if row['record_type'] in ('ETA_CODE_SUMMARY','ETA_SOURCE_SUMMARY','US_SOURCE_SUMMARY'):
                    for field in ('previous_total','target_total','net_change','increase_total',
                                  'decrease_total','previous_expected_total','target_expected_total',
                                  'counts_match','required'):
                        expected[field]=None
                elif row['record_type']=='VENDOR_CHECK':
                    for field in ('previous_past_due_count','target_past_due_count',
                                  'past_due_count_change','past_due_count_relative_change',
                                  'delta_country_contribution'):
                        expected[field]=None
                self.assertEqual(row,expected)
                merged[ordinal]=row
        self.assertEqual(set(merged),set(original))
        self.assertEqual(len(merged),int(rows[0]['expected_record_count']))
        # The same validation used for business evidence must have all required
        # quantities after the real retained projections, not just every ID.
        from analysis_validation import validate_atlas_report_rows
        self.assertEqual(validate_atlas_report_rows(list(merged.values()))['status'],'PASS')
        return list(merged.values())

    def test_original_positive_report_typed_reads_preserve_both_countries(self):
        for country in ('US','CA'):
            with self.subTest(country=country):
                rows=self.sql('pastdue_v3_report_query_rows',country_code=country,
                              _atlas_narrow=True)
                bound=self.original_typed_reads(rows)
                self.assertEqual(sum(r['record_type']=='VENDOR_METRICS' for r in bound),10)
                self.assertEqual({r['country_code'] for r in bound},{country})

    def test_original_single_vendor_zero_and_improvement_without_alert(self):
        self.db.query("INSERT INTO past_due.vendor_past_due_daily VALUES "
                      "('REDUCE','2026-10-23',1,'Improving','NR','EDI',20,100),"
                      "('REDUCE','2026-10-26',1,'Improving','NR','EDI',10,100)")
        for country,delta in (('EMPTY',0),('ZERO',0),('REDUCE',-10)):
            with self.subTest(country=country):
                history=self.sql('pastdue_v3_history_query_comparisons',country_code=country)
                self.assertEqual({r['business_status'] for r in history
                                  if r['target_snapshot_date']=='2026-10-26'},{'NORMAL'})
                rows=self.sql('pastdue_v3_report_query_rows',country_code=country,
                              selection_mode='SINGLE_VENDOR',vendor_code=1,top_n=1,
                              _atlas_narrow=True)
                bound=self.original_typed_reads(rows)
                metrics=[r for r in bound if r['record_type']=='VENDOR_METRICS']
                self.assertEqual(len(metrics),1)
                self.assertEqual(int(metrics[0]['past_due_count_change']),delta)
                checks=[r for r in bound if r['record_type']=='VENDOR_CHECK'
                        and r['dimension'] in ('code','source')]
                self.assertEqual({int(r['net_change']) for r in checks},{delta})

    def test_original_typed_read_lost_metrics_or_foreign_check_is_rejected(self):
        from analysis_validation import validate_atlas_report_rows
        rows=self.sql('pastdue_v3_report_query_rows',_atlas_narrow=True)
        bound=self.original_typed_reads(rows)
        partial=[r for r in bound if not (r['record_type']=='VENDOR_METRICS'
                                          and int(r['vendor_code'])==6)]
        self.assertNotEqual(validate_atlas_report_rows(partial)['status'],'PASS')
        import copy
        mixed=copy.deepcopy(bound)
        victim=next(r for r in mixed if r['record_type']=='VENDOR_CHECK'
                    and int(r['vendor_code'])==6 and r['dimension']=='code')
        foreign=next(r for r in mixed if r['record_type']=='VENDOR_CHECK'
                     and int(r['vendor_code'])==1 and r['dimension']=='code')
        for field in ('previous_total','target_total','increase_total','decrease_total',
                      'net_change','previous_expected_total','target_expected_total','counts_match'):
            victim[field]=foreign[field]
        self.assertNotEqual(validate_atlas_report_rows(mixed)['status'],'PASS')

    def test_report_split_vendor_middle_page_is_lossless(self):
        from build_report_v2 import RID
        args = dict(selection_mode='POSITIVE_TOP_N',vendor_codes_json='[]',top_n=10,page_size=50)
        all_rows = []
        cursor = 0
        seen = set()
        while True:
            self.assertNotIn(cursor, seen)
            seen.add(cursor)
            rows = self.sql(RID,page_after_ordinal=cursor,**args)
            page = self.check_page('report', rows)
            self.assertEqual(int(page[0]['page_after_ordinal']),cursor)
            all_rows.extend(page if cursor == 0 else page[1:])
            if not page[0]['has_more']:
                self.assertEqual(int(page[0]['next_after_ordinal']),0)
                break
            self.assertGreater(int(page[0]['next_after_ordinal']),cursor)
            cursor = int(page[0]['next_after_ordinal'])
        self.assertEqual([int(r['row_ordinal']) for r in all_rows],list(range(len(all_rows))))
        self.assertEqual(len(all_rows),int(all_rows[0]['expected_record_count']))
        self.assertEqual(sum(r['record_type']=='VENDOR_METRICS' for r in all_rows),10)
        self.assertEqual(len(seen),(len(all_rows)-2)//50+1)

    def test_current_joint_facts_and_requested_coverage_survive_full_read(self):
        from build_current_v2 import RID
        rows = self.sql(RID,vendor_codes_json='[1,2,999]',page_size=100,
                        denominator_scope='COUNTRY_ALL')
        page = self.check_page('current', rows)
        self.assertEqual({int(r['vendor_code']):r['scope_status'] for r in page
                          if r['record_type']=='VENDOR_COVERAGE'},
                         {1:'MATCHED',2:'MATCHED',999:'NO_READABLE_ROWS'})


if __name__ == '__main__':
    unittest.main()

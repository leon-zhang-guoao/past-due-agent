"""Report set/trigger independence against synthetic ClickHouse, never live proof."""
import copy
import unittest
from edn_io import read, loads
from build_report_v2 import add_report_v2, INPUTS, FIELDS, RID
from validate_bundle import ROOT, validate
from analysis_validation import validate_atlas_report_rows
import test_sql_fixtures as fixtures
session = fixtures.session


class Contracts(unittest.TestCase):
    def test_additive_build_and_example(self):
        import subprocess
        baseline = loads(subprocess.check_output(['git','show','HEAD:atlas/charts/pastdue.edn'],cwd=ROOT,text=True))
        result = add_report_v2(copy.deepcopy(baseline))
        self.assertEqual(validate(result), [])
        for section in (':resolvers',':bindings',':selection',':sources'):
            for key, value in baseline[':execution'][section].items():
                self.assertEqual(result[':execution'][section][key], value, key)
        self.assertEqual(add_report_v2(copy.deepcopy(result)), result)
        example = loads((ROOT/'skill/pastdue-analysis/references/report-v2-query.eql').read_text())
        self.assertEqual(set(example[':entity']), {':pastdue.report-v2-query/'+n for n,_ in INPUTS})
        self.assertEqual(set(example[':query'][0][':pastdue.report-v2-query/rows']),
                         {':pastdue.report-v2-fact/'+n for n,_ in FIELDS})


@unittest.skipIf(session is None, 'chdb missing; SQL NOT VERIFIED')
class ReportV2(unittest.TestCase):
    # Reuse the source fixtures and real bound-SQL runner, not inherited tests.
    setUpClass = classmethod(fixtures.SQLFixtures.setUpClass.__func__)
    tearDownClass = classmethod(fixtures.SQLFixtures.tearDownClass.__func__)
    sql = fixtures.SQLFixtures.sql

    def report(self, **overrides):
        args = dict(selection_mode='VENDOR_SET',vendor_codes_json='[1,12]',top_n=0,
                    page_size=199)
        args.update(overrides)
        return self.sql(RID, **args)

    def metrics(self, rows):
        return [r for r in rows if r['record_type']=='VENDOR_METRICS']

    def test_nontrigger_report_and_negative_vendor(self):
        # An unchanged country has NORMAL status; a report is still complete.
        status = self.sql('pastdue_v3_history_query_comparisons',country_code='EMPTY')
        self.assertEqual({r['business_status'] for r in status if r['target_snapshot_date']=='2026-10-26'}, {'NORMAL'})
        rows = self.report(country_code='EMPTY',vendor_codes_json='[1]')
        self.assertEqual(len(self.metrics(rows)), 1)
        self.assertEqual(int(self.metrics(rows)[0]['past_due_count_change']), 0)
        self.assertEqual(next(r for r in rows if r['record_type']=='COUNTRY_CONTROL')['scope_status'], 'OK')
        self.db.query("INSERT INTO past_due.vendor_past_due_daily VALUES "
                      "('NEG','2026-10-23',1,'Improving','NR','EDI',20,100),"
                      "('NEG','2026-10-26',1,'Improving','NR','EDI',10,100)")
        rows = self.report(country_code='NEG',vendor_codes_json='[1]')
        self.assertEqual(int(self.metrics(rows)[0]['past_due_count_change']), -10)
        self.assertTrue(all(r['counts_match'] for r in rows if r['record_type']=='VENDOR_CHECK'))

    def test_set_parity_with_single_vendor_and_country_denominators(self):
        rows = self.report(vendor_codes_json='[12,1,1,999]')
        coverage = {int(r['vendor_code']): r['scope_status'] for r in rows if r['record_type']=='VENDOR_COVERAGE'}
        self.assertEqual(coverage, {1:'MATCHED',12:'MATCHED',999:'NO_DATA_IN_PAIR'})
        self.assertEqual({int(r['vendor_code']) for r in self.metrics(rows)}, {1,12})
        semantic = [n.replace('-','_') for n,_ in FIELDS if n not in
                    ('scope-key','selection-mode','selected-vendor-count','selected-positive-coverage',
                     'row-ordinal','expected-record-count','queried-at-local') and n not in
                    {x for x,_ in FIELDS if x in ('requested-vendor-count','page-after-ordinal','page-size',
                                                 'page-record-count','next-after-ordinal','has-more','result-fingerprint')}]
        for vendor in (1,12):
            old = self.sql('pastdue_v3_report_query_rows',selection_mode='SINGLE_VENDOR',vendor_code=vendor,top_n=1)
            new = [r for r in rows if int(r['vendor_code'])==vendor and r['record_type']!='VENDOR_COVERAGE']
            old = [r for r in old if r['record_type']!='COUNTRY_CONTROL']
            key = lambda r:(r['record_type'],r['dimension'],r['display_rank'],r['eta_value'])
            self.assertEqual([[r[k] for k in semantic] for r in sorted(new,key=key)],
                             [[r[k] for k in semantic] for r in sorted(old,key=key)])
        self.assertTrue(all(r['vendor_name'] for r in self.metrics(rows)))
        self.assertEqual(validate_atlas_report_rows(rows)['status'],'PASS')
        tampered = copy.deepcopy(rows)
        next(r for r in tampered if r['record_type']=='VENDOR_COVERAGE' and int(r['vendor_code'])==999)['scope_status']='MATCHED'
        self.assertIn('MATCHED_VENDOR_METRICS_MISSING', validate_atlas_report_rows(tampered)['missing'])

    def test_missing_and_zero_are_distinct_and_one_sided_exists(self):
        zero = self.report(country_code='ZERO',vendor_codes_json='[1,999]')
        self.assertEqual(int(self.metrics(zero)[0]['target_past_due_count']), 0)
        self.assertEqual({int(r['vendor_code']):r['scope_status'] for r in zero if r['record_type']=='VENDOR_COVERAGE'},
                         {1:'MATCHED',999:'NO_DATA_IN_PAIR'})
        gone = self.report(country_code='GONE',vendor_codes_json='[1]')
        self.assertEqual(int(self.metrics(gone)[0]['target_past_due_count']), 0)
        self.assertEqual(int(self.metrics(gone)[0]['previous_past_due_count']), 10)

    def test_invalid_scope_still_returns_control(self):
        cases = [(dict(vendor_codes_json=x),'INVALID_VENDOR_SELECTION')
                 for x in ('bad','{}','[true]','["1"]','[-1]','[9223372036854775808]')]
        cases += [(dict(vendor_codes_json='[]'),'INVALID_SELECTION'),
                  (dict(page_size=200),'INVALID_PAGE'),(dict(page_after_ordinal=-1),'INVALID_PAGE'),
                  (dict(target_snapshot_date='2026-10-25'),'INVALID_PAIR')]
        for args, expected in cases:
            with self.subTest(args=args):
                rows = self.report(**args)
                self.assertEqual(len(rows), 1)
                self.assertEqual(rows[0]['record_type'], 'COUNTRY_CONTROL')
                self.assertEqual(rows[0]['scope_status'], expected)

    def test_nonpositive_top_n_and_empty_positive(self):
        args = dict(country_code='EMPTY',vendor_codes_json='[]',top_n=10)
        all_signs = self.report(selection_mode='TOP_N',**args)
        self.assertEqual(len(self.metrics(all_signs)),1)
        positive = self.report(selection_mode='POSITIVE_TOP_N',**args)
        self.assertEqual(self.metrics(positive),[])
        self.assertEqual(len(positive),1)
        self.assertEqual(positive[0]['scope_status'],'OK')

    def test_top_n_and_positive_match_old_vendor_selection(self):
        for mode in ('TOP_N','POSITIVE_TOP_N'):
            rows=self.report(selection_mode=mode,vendor_codes_json='[]',top_n=3)
            expected=self.sql('pastdue_v3_report_query_rows',top_n=3)
            new_metrics=self.metrics(rows)
            old_metrics=self.metrics(expected)
            self.assertEqual([(r['vendor_code'],r['contribution_rank']) for r in new_metrics],
                             [(r['vendor_code'],r['contribution_rank']) for r in old_metrics])
            self.assertEqual(validate_atlas_report_rows(rows)['status'],'PASS')

    def test_example_page_size_avoids_known_fixture_byte_overflow(self):
        import json
        example=loads((ROOT/'skill/pastdue-analysis/references/report-v2-query.eql').read_text())
        size=example[':entity'][':pastdue.report-v2-query/page-size']
        rows=self.report(selection_mode='TOP_N',vendor_codes_json='[]',top_n=10,page_size=size)
        qualified=[{':pastdue.report-v2-fact/'+k.replace('_','-'):v for k,v in row.items()} for row in rows]
        # Estimate with the shipped field names, not proof of live wire encoding.
        self.assertLess(len(json.dumps(qualified,ensure_ascii=False).encode()),262144)

    def test_pages_and_large_explicit_set(self):
        self.db.query("INSERT INTO past_due.vendor_past_due_daily SELECT 'MANY',toDate(d),"
                      "toInt64(number+1),'Vendor','NR','EDI',toInt64(1),toInt64(10) "
                      "FROM numbers(13) AS n CROSS JOIN (SELECT arrayJoin(['2026-10-23','2026-10-26']) AS d) AS dates")
        import json
        args = dict(country_code='MANY',vendor_codes_json=json.dumps(list(range(1,14))))
        first = self.report(page_size=7, **args)
        rows = [first[0]]
        cursor = 0
        fingerprints = set()
        while True:
            page = first if cursor==0 else self.report(page_size=7,page_after_ordinal=cursor,**args)
            self.assertEqual(int(page[0]['page_record_count']),len(page))
            rows.extend(r for r in page if r['record_type']!='COUNTRY_CONTROL')
            fingerprints.add(page[0]['result_fingerprint'])
            cursor = int(page[0]['next_after_ordinal'])
            if not cursor: break
        self.assertEqual(len(self.metrics(rows)),13)
        self.assertEqual(len([r for r in rows if r['record_type']=='VENDOR_COVERAGE']),13)
        self.assertEqual(len(rows),int(rows[0]['expected_record_count']))
        self.assertEqual([int(r['row_ordinal']) for r in rows],list(range(len(rows))))
        self.assertEqual(len(fingerprints),1)


if __name__=='__main__': unittest.main()

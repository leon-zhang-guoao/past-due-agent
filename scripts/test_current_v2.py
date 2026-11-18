"""Synthetic CURRENT set semantics and differential checks against explore-v3."""
import copy
import json
import subprocess
import unittest
from edn_io import loads
from build_current_v2 import add_current_v2, INPUTS, FIELDS, RID
from validate_bundle import ROOT, validate
import test_sql_fixtures as fixtures


class Contracts(unittest.TestCase):
    def test_additive_idempotent_and_example(self):
        baseline=loads(subprocess.check_output(['git','show','HEAD:atlas/charts/pastdue.edn'],cwd=ROOT,text=True))
        result=add_current_v2(copy.deepcopy(baseline))
        self.assertEqual(validate(result),[])
        for section in (':resolvers',':bindings',':sources',':selection'):
            for key,value in baseline[':execution'][section].items():
                self.assertEqual(result[':execution'][section][key],value)
        self.assertEqual(add_current_v2(copy.deepcopy(result)),result)
        example=loads((ROOT/'skill/pastdue-analysis/references/current-v2-query.eql').read_text())
        self.assertEqual(set(example[':entity']),{':pastdue.current-v2-query/'+n for n,_ in INPUTS})
        self.assertEqual(set(example[':query'][0][':pastdue.current-v2-query/rows']),
                         {':pastdue.current-v2-fact/'+n for n,_ in FIELDS})


@unittest.skipIf(fixtures.session is None,'chdb missing; SQL NOT VERIFIED')
class CurrentV2(unittest.TestCase):
    setUpClass=classmethod(fixtures.SQLFixtures.setUpClass.__func__)
    tearDownClass=classmethod(fixtures.SQLFixtures.tearDownClass.__func__)
    sql=fixtures.SQLFixtures.sql

    def current(self,**overrides):
        args=dict(vendor_codes_json='[1,2,999]',denominator_scope='WITHIN_VENDOR',page_size=199)
        args.update(overrides)
        return self.sql(RID,**args)

    def test_denominator_org_parity(self):
        for denominator in ('COUNTRY_ALL','FILTERED_POPULATION','WITHIN_VENDOR'):
            for org in ('','A','MISSING'):
                with self.subTest(denominator=denominator,org=org):
                    args=dict(denominator_scope=denominator,master_primary_filter=org)
                    new=self.current(**args)
                    old=self.sql('pastdue_v3_explore_v3_query_rows',time_basis='CURRENT',grain='VENDOR_CODE_SOURCE',
                                 vendor_codes_json='[1,2,999]',top_n=0,**args)
                    key=lambda r:(int(r['vendor_code']),r['eta_code'],r['eta_source'])
                    facts={key(r):r for r in new if r['record_type']=='FACT'}
                    reference={key(r):r for r in old if r['record_type']=='FACT'}
                    self.assertEqual(set(facts),set(reference))
                    pairs=(('past_due_count','target_past_due_count'),('open_line_count','target_open_count'),
                           ('ratio_denominator','target_ratio_denominator'),('share_denominator','target_share_denominator'),
                           ('past_due_ratio','target_past_due_ratio'),('past_due_share','target_past_due_share'))
                    for identity,row in facts.items():
                        for a,b in pairs:self.assertEqual(str(row[a]),str(reference[identity][b]),(identity,a))

    def test_duplicate_mapping_missing_and_zero(self):
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',4,'Zero','NR','EDI',0,'OPEN')")
        rows=self.current(vendor_codes_json='[4,1,1,999]')
        self.assertEqual({int(r['vendor_code']):r['scope_status'] for r in rows if r['record_type']=='VENDOR_COVERAGE'},
                         {1:'MATCHED',4:'MATCHED',999:'NO_READABLE_ROWS'})
        facts=[r for r in rows if r['record_type']=='FACT']
        self.assertEqual(sum(int(r['open_line_count']) for r in facts),3)
        self.assertEqual(sum(int(r['past_due_count']) for r in facts),1)
        zero=next(r for r in facts if int(r['vendor_code'])==4)
        self.assertEqual(int(zero['vendor_open_count']),1)
        self.assertIsNone(zero['past_due_share'])
        org=self.current(vendor_codes_json='[1,2,3]',master_primary_filter='A')
        self.assertEqual({int(r['vendor_code']):r['scope_status'] for r in org if r['record_type']=='VENDOR_COVERAGE'},
                         {1:'MATCHED',2:'EXCLUDED_BY_ORG',3:'EXCLUDED_BY_ORG'})

    def test_invalid_explicit_control(self):
        for args,status in [(dict(vendor_codes_json=x),'INVALID_VENDOR_SELECTION') for x in
                            ('[]','bad','{}','[true]','["1"]','[-1]','[1.2]','[9223372036854775808]')]+[
                            (dict(country_code=''),'INVALID_COUNTRY'),(dict(page_size=200),'INVALID_PAGE'),
                            (dict(denominator_scope='OTHER'),'INVALID_DENOMINATOR')]:
            with self.subTest(args=args):
                rows=self.current(**args)
                self.assertEqual(len(rows),1)
                self.assertEqual(rows[0]['scope_status'],status)

    def test_paging_totals_joint_and_fingerprint(self):
        whole=self.current()
        rows=[];cursor=0
        while True:
            page=self.current(page_size=2,page_after_ordinal=cursor)
            self.assertEqual(int(page[0]['page_record_count']),len(page))
            self.assertEqual(page[0]['result_fingerprint'],whole[0]['result_fingerprint'])
            rows.extend(page if cursor==0 else page[1:])
            cursor=int(page[0]['next_after_ordinal'])
            if not cursor:break
        stable=lambda row:{k:v for k,v in row.items() if k not in
                           ('queried_at_local','page_size','page_record_count','page_after_ordinal','next_after_ordinal','has_more')}
        self.assertEqual([stable(r) for r in rows],[stable(r) for r in whole])
        self.assertEqual(len(rows),int(rows[0]['expected_record_count']))
        self.assertEqual([int(r['row_ordinal']) for r in rows],list(range(len(rows))))

    def test_null_normalization_and_thirteen_vendor_coverage(self):
        self.db.query('ALTER TABLE past_due.past_due_detail_view MODIFY COLUMN eta_code Nullable(String)')
        self.db.query('ALTER TABLE past_due.past_due_detail_view MODIFY COLUMN source Nullable(String)')
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('NULLS',1,'Null',NULL,NULL,1,'LATE')")
        rows=self.current(country_code='NULLS',vendor_codes_json=json.dumps(list(range(1,14))))
        facts=[r for r in rows if r['record_type']=='FACT']
        self.assertEqual([(r['eta_code'],r['eta_source']) for r in facts],[('UNKNOWN','UNKNOWN')])
        self.assertEqual(len([r for r in rows if r['record_type']=='VENDOR_COVERAGE']),13)
        self.assertEqual(str(facts[0]['vendor_past_due_ratio']),'1')


if __name__=='__main__':unittest.main()

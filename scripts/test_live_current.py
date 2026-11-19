"""Local synthetic tests: shipped live graph inputs, SQL and batch composition."""
import copy
import json
import subprocess
import unittest
from edn_io import loads, read
from validate_bundle import ROOT, validate
from build_live_current import (add_live_current, OVERVIEW, IDENTITY, OVERVIEW_INPUTS,
    OVERVIEW_FIELDS, IDENTITY_INPUTS, IDENTITY_FIELDS)
import test_sql_fixtures as fixtures

O='pastdue_v3_live_overview_query_rows'
I='pastdue_v3_vendor_identity_query_rows'

class Contract(unittest.TestCase):
    def test_additive_idempotent_and_examples(self):
        baseline=loads(subprocess.check_output(['git','show','HEAD:atlas/charts/pastdue.edn'],cwd=ROOT,text=True))
        result=add_live_current(copy.deepcopy(baseline))
        self.assertEqual(validate(result),[])
        for section in (':resolvers',':bindings',':sources',':selection'):
            for key,value in baseline[':execution'][section].items():
                self.assertEqual(result[':execution'][section][key],value)
        self.assertEqual(add_live_current(copy.deepcopy(result)),result)
        for parent,inputs,fields in ((OVERVIEW,OVERVIEW_INPUTS,OVERVIEW_FIELDS),(IDENTITY,IDENTITY_INPUTS,IDENTITY_FIELDS)):
            example=read(ROOT/('skill/pastdue-analysis/references/'+parent.split('.')[-1]+'.eql'))
            child=parent.removesuffix('-query')+'-fact'
            self.assertEqual(set(example[':entity']),{':'+parent+'/'+n for n,_ in inputs})
            self.assertEqual(set(example[':query'][0][':'+parent+'/rows']),{':'+child+'/'+n for n,_ in fields})

@unittest.skipIf(fixtures.session is None,'chdb missing; SQL NOT VERIFIED')
class Live(unittest.TestCase):
    setUpClass=classmethod(fixtures.SQLFixtures.setUpClass.__func__)
    tearDownClass=classmethod(fixtures.SQLFixtures.tearDownClass.__func__)
    sql=fixtures.SQLFixtures.sql
    def setUp(self):
        self.db.query('TRUNCATE TABLE past_due.past_due_detail_view')
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',1,'One','NR','EDI',1,'LATE'),('CA',1,'One','SH','EDI',0,'OPEN'),('CA',2,'Two','US','AutoUS',1,'LATE'),('CA',3,'Unmapped','NR','EDI',1,'LATE')")
    def overview(self,**args):
        return self.sql(O,_atlas_narrow=True,**args)
    def resolve(self,**args):
        params=dict(countries_json='["CA"]',vendor_codes_json='[]',vendor_names_json='["One"]',page_size=100)
        params.update(args)
        return self.sql(I,_atlas_narrow=True,**params)
    def test_typed_retained_reads_preserve_metrics_and_composition(self):
        import duckdb
        rows=self.sql('pastdue_v3_current_v2_query_rows',vendor_codes_json='[1,2,999]',denominator_scope='WITHIN_VENDOR',page_size=100)
        with duckdb.connect() as db:
            columns=list(rows[0])
            strings=[n for n,v in rows[0].items() if isinstance(v,str)]
            db.execute('CREATE TABLE data ('+','.join('"'+n+'" '+('VARCHAR' if n in strings else 'DOUBLE' if n.endswith(('ratio','share')) else 'BOOLEAN' if isinstance(rows[0][n],bool) else 'BIGINT') for n in columns)+')')
            db.executemany('INSERT INTO data VALUES ('+','.join('?' for _ in columns)+')',[[row[n] for n in columns] for row in rows])
            for name in ('current-metrics-read.sql','current-buckets-read.sql'):
                query=(ROOT/'skill/pastdue-analysis/references'/name).read_text()
                output=db.execute(query)
                names=[c[0] for c in output.description]
                actual=[dict(zip(names,r)) for r in output.fetchall()]
                self.assertEqual([int(r['row_ordinal']) for r in actual],[int(r['row_ordinal']) for r in rows])
                for original,returned in zip(rows,actual):
                    self.assertEqual(returned,{n:original[n] for n in names})
    def test_thirteen_vendor_input_coverage_without_top_n(self):
        rows=self.resolve(vendor_codes_json=json.dumps(list(range(1,14))),vendor_names_json='[]')
        self.assertEqual(len(rows),14)
        self.assertEqual(int(rows[0]['requested_input_count']),13)
        self.assertEqual(sum(r['scope_status']=='MATCHED' for r in rows),3)
        self.assertEqual(sum(r['scope_status']=='NO_READABLE_MATCH' for r in rows),10)
    def test_changed_identity_fingerprint_detects_source_drift(self):
        first=self.resolve(vendor_names_json='["New alias"]',page_size=1)
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',77,'New alias','NR','EDI',0,'OPEN')")
        second=self.resolve(vendor_names_json='["New alias"]',page_size=1)
        self.assertNotEqual(first[0]['result_fingerprint'],second[0]['result_fingerprint'])
        self.assertEqual(second[1]['scope_status'],'MATCHED')
    def test_overview_current_not_snapshot(self):
        rows=self.overview(top_n=2)
        self.assertEqual(len(rows),3)
        c=rows[0]
        self.assertEqual((c['record_type'],int(c['open_line_count']),int(c['past_due_count'])),('COUNTRY',4,3))
        self.assertEqual(str(c['past_due_ratio']),'0.75')
        self.assertEqual(int(c['readable_vendor_count']),3)
        self.assertEqual([(int(r['vendor_code']),int(r['vendor_rank'])) for r in rows[1:]],[(1,1),(2,2)])
        self.assertEqual({r['time_basis'] for r in rows},{'CURRENT'})
        self.assertEqual(int(c['expected_record_count']),3)
    def test_missing_and_invalid(self):
        for args,status in ((dict(country_code='US'),'NO_READABLE_ROWS'),(dict(country_code=''),'INVALID_COUNTRY'),(dict(top_n=21),'INVALID_TOP_N')):
            rows=self.overview(**args)
            self.assertEqual(len(rows),1)
            self.assertEqual(rows[0]['scope_status'],status)
            self.assertIsNone(rows[0]['past_due_ratio'])
    def test_zero_pd_is_measured_and_unattributed_rows(self):
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('LIVEZERO',11,'Zero','NR','EDI',0,'OPEN')")
        rows=self.overview(country_code='LIVEZERO')
        self.assertEqual(rows[0]['scope_status'],'OK')
        self.assertEqual(str(rows[0]['past_due_ratio']),'0')
        self.db.query('DROP TABLE past_due.past_due_detail_view')
        self.db.query('CREATE TABLE past_due.past_due_detail_view (country_code String,from_acct_no Nullable(Int64),vendor_name String,eta_code String,source String,is_pastdue Int64,alert_type String) ENGINE=Memory')
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('LIVEATTR',1,'Valid','NR','EDI',1,'LATE'),('LIVEATTR',NULL,'Missing','NR','EDI',1,'LATE'),('LIVEATTR',0,'Invalid','NR','EDI',0,'OPEN')")
        rows=self.overview(country_code='LIVEATTR')
        self.assertEqual(int(rows[0]['open_line_count']),3)
        self.assertEqual(int(rows[0]['past_due_count']),2)
        self.assertEqual(int(rows[0]['unattributed_open_count']),2)
        self.assertEqual(int(rows[0]['unattributed_past_due_count']),1)
        self.assertEqual([int(r['vendor_code']) for r in rows[1:]],[1])
    def test_mixed_exact_name_code_dedup_and_missing(self):
        rows=self.resolve(vendor_codes_json='[1,1,999]',vendor_names_json='[" one ","One","Absent"]')
        facts=[r for r in rows if r['record_type']=='RESOLUTION']
        self.assertEqual(len(facts),5) # two code tokens, three distinct trimmed name tokens
        by={(r['requested_kind'],r['requested_token']):r for r in facts}
        for key in [('CODE','1'),('NAME','one'),('NAME','One')]:
            self.assertEqual(by[key]['scope_status'],'MATCHED')
            self.assertEqual(int(by[key]['vendor_code']),1)
        for key in [('CODE','999'),('NAME','Absent')]:
            self.assertEqual(by[key]['scope_status'],'NO_READABLE_MATCH')
        self.assertEqual(int(rows[0]['requested_input_count']),5)
        self.assertEqual(self.resolve(vendor_names_json='["On"]')[1]['scope_status'],'NO_READABLE_MATCH')
    def test_ambiguity_country_and_same_country(self):
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',10,'Twin','NR','EDI',1,'LATE'),('CA',11,'TWIN','NR','EDI',0,'OPEN'),('US',10,'Twin','NR','EDI',1,'LATE')")
        rows=self.resolve(countries_json='["CA","US"]',vendor_names_json='["twin"]')
        self.assertEqual({r['scope_status'] for r in rows[1:]},{'AMBIGUOUS'})
        self.assertEqual({int(r['candidate_count']) for r in rows[1:]},{3})
        rows=self.resolve(countries_json='["US"]',vendor_names_json='["twin"]')
        self.assertEqual(rows[1]['scope_status'],'MATCHED')
        rows=self.resolve(countries_json='["US","CA"]',vendor_names_json='[]',vendor_codes_json='[10]')
        self.assertEqual({int(r['candidate_count']) for r in rows[1:]},{2})
    def test_invalid_json_or_tokens_never_all(self):
        for args in [dict(countries_json='[]'),dict(countries_json='{}'),dict(countries_json='[1]'),dict(vendor_names_json='[]'),dict(vendor_names_json='[null]'),dict(vendor_names_json='[" "]'),dict(vendor_codes_json='["1"]'),dict(vendor_codes_json='[0]'),dict(vendor_codes_json='[9223372036854775808]'),dict(vendor_names_json='bad'),dict(page_size=200)]:
            with self.subTest(args=args):
                rows=self.resolve(**args)
                self.assertEqual(len(rows),1)
                self.assertTrue(rows[0]['scope_status'].startswith('INVALID_'))
    def test_quoted_names_are_values(self):
        name="O'Reilly ; SELECT *"
        self.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',42,'O''Reilly ; SELECT *','NR','EDI',0,'OPEN')")
        rows=self.resolve(vendor_names_json=json.dumps([name]))
        self.assertEqual(rows[1]['scope_status'],'MATCHED')
        self.assertEqual(int(rows[1]['vendor_code']),42)
    def test_paging_drift_and_batch_composition(self):
        args=dict(vendor_codes_json='[1,2,999]',vendor_names_json='["Unmapped"]')
        all_rows=self.resolve(**args)
        seen=[];cursor=0;fingerprints=set()
        while True:
            rows=self.resolve(**args,page_size=1,page_after_ordinal=cursor)
            c=rows[0];self.assertEqual(int(c['page_record_count']),len(rows))
            fingerprints.add(c['result_fingerprint'])
            seen.extend(r for r in rows if r['record_type']=='RESOLUTION')
            if not c['has_more']:break
            next_cursor=int(c['next_after_ordinal']);self.assertGreater(next_cursor,cursor);cursor=next_cursor
        stable=lambda rows:[{k:v for k,v in r.items() if k not in ('queried_at_local','page_record_count','next_after_ordinal','has_more')} for r in rows]
        self.assertEqual(stable(seen),stable(all_rows[1:]))
        self.assertEqual(len(fingerprints),1)
        codes=sorted({int(r['vendor_code']) for r in seen if r['scope_status']=='MATCHED'})
        self.assertEqual(codes,[1,2,3])
        rows=self.sql('pastdue_v3_current_v2_query_rows',vendor_codes_json=json.dumps(codes),denominator_scope='WITHIN_VENDOR',page_size=100)
        totals={int(r['vendor_code']):(int(r['vendor_open_count']),int(r['vendor_past_due_count'])) for r in rows if r['record_type']=='FACT'}
        self.assertEqual(totals,{1:(2,1),2:(1,1),3:(1,1)})

if __name__=='__main__':unittest.main()

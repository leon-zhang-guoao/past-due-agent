#!/usr/bin/env python3
"""Pilot contract and synthetic SQL checks; no live acceptance claim."""
import copy
import json
import re
import unittest
from pathlib import Path
from build_current_counts_pilot import add_pilot, INPUTS, FIELDS, RID
from edn_io import read,loads,dumps
from validate_bundle import validate
try:
    from chdb import session
except ImportError:
    session=None
import tempfile

ROOT=Path(__file__).resolve().parents[1]

class Contracts(unittest.TestCase):
    def test_additive_valid_and_idempotent(self):
        baseline=read(ROOT/'atlas/charts/pastdue.edn')
        pilot=add_pilot(copy.deepcopy(baseline))
        self.assertEqual(validate(pilot),[])
        for section in [':resolvers',':bindings',':selection',':sources']:
            for key,value in baseline[':execution'][section].items():
                self.assertEqual(pilot[':execution'][section][key],value)
        self.assertEqual(add_pilot(copy.deepcopy(pilot)),pilot)
        self.assertEqual(loads(dumps(pilot)),pilot)

@unittest.skipIf(session is None,'chdb missing; SQL NOT VERIFIED')
class SQL(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='counts-pilot-')
        cls.db=session.Session(cls.tmp.name)
        cls.db.query('CREATE DATABASE past_due')
        # Deliberately no mapping/history tables: the counts route needs neither.
        cls.db.query('CREATE TABLE past_due.past_due_detail_view (country_code String,from_acct_no Nullable(Int64),vendor_name String,eta_code Nullable(String),source Nullable(String),is_pastdue Int64) ENGINE=Memory')
        cls.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('US',1,'One',' nr ',' EDI ',1),('US',1,'One','NR','EDI',1),('US',1,'One',NULL,NULL,0),('US',2,'Two','SH','EDI',0),('US',NULL,'No ID','NR','EDI',1),('US',99,'Outside','NR','EDI',1),('CA',1,'Other country','NR','EDI',1)")
        doc=add_pilot(read(ROOT/'atlas/charts/pastdue.edn'))
        cls.query=doc[':execution'][':bindings'][RID+'-sql'][':binding/implementation'][':query']
    @classmethod
    def tearDownClass(cls):
        del cls.db
        cls.tmp.cleanup()
    def run_query(self,**changes):
        args=dict(country_code='US',vendor_codes_json='[1,2,999]',page_after_ordinal=0,page_size=199)
        args.update(changes)
        def replace(m):
            if m.group(1) is None:return m.group(0)
            value=args[m.group(1)]
            return str(value) if isinstance(value,int) else "'"+value.replace("'","''")+"'"
        sql=re.sub(r"'(?:''|[^'])*'|:([a-zA-Z_][a-zA-Z0-9_]*)",replace,self.query)
        result=json.loads(str(self.db.query(sql+' FORMAT JSON','CSV')))['data']
        for row in result:self.assertEqual(set(row),{n.replace('-','_') for n,_ in FIELDS})
        return result
    def test_measurements_zero_missing_and_normalization(self):
        rows=self.run_query()
        self.assertEqual(len(rows),7)
        facts=[r for r in rows if r['record_type']=='FACT']
        by_key={(int(r['vendor_code']),r['eta_code'],r['eta_source']):r for r in facts}
        self.assertEqual(int(by_key[(1,'NR','EDI')]['past_due_count']),2)
        self.assertEqual(int(by_key[(1,'UNKNOWN','UNKNOWN')]['open_line_count']),1)
        self.assertEqual(int(by_key[(2,'SH','EDI')]['vendor_past_due_count']),0)
        coverage={int(r['vendor_code']):r['scope_status'] for r in rows if r['record_type']=='VENDOR_COVERAGE'}
        self.assertEqual(coverage,{1:'MATCHED',2:'MATCHED',999:'NO_READABLE_ROWS'})
        for r in facts:
            vendor=[x for x in facts if x['vendor_code']==r['vendor_code']]
            self.assertEqual(int(r['vendor_open_count']),sum(int(x['open_line_count']) for x in vendor))
            self.assertEqual(int(r['vendor_past_due_count']),sum(int(x['past_due_count']) for x in vendor))
        self.assertEqual({int(r['expected_bucket_count']) for r in rows},{3})
        self.assertEqual([int(r['row_ordinal']) for r in rows],list(range(7)))
    def test_invalid_inputs_explicit_not_empty_or_all(self):
        for args,status in [({'vendor_codes_json':x},'INVALID_VENDOR_SELECTION') for x in ['[]','bad','{}','[true]','["1"]','[-1]','[1.5]','[9223372036854775808]']]+[({'page_size':0},'INVALID_PAGE'),({'page_size':501},'INVALID_PAGE'),({'page_after_ordinal':-1},'INVALID_PAGE'),({'country_code':''},'INVALID_COUNTRY')]:
            with self.subTest(args=args):
                rows=self.run_query(**args)
                self.assertEqual(len(rows),1)
                self.assertEqual(rows[0]['record_type'],'CONTROL')
                self.assertEqual(rows[0]['scope_status'],status)
                self.assertFalse(rows[0]['has_more'])
    def test_pages_fingerprint_and_set_invariance(self):
        whole=self.run_query()
        first=self.run_query(page_size=2)
        self.assertEqual(len(first),3)
        self.assertTrue(first[0]['has_more'])
        self.assertEqual(int(first[0]['next_after_ordinal']),2)
        pages=list(first);cursor=2
        while cursor:
            rows=self.run_query(page_size=2,page_after_ordinal=cursor)
            self.assertEqual(len(rows),int(rows[0]['page_record_count']))
            pages.extend(rows[1:]);cursor=int(rows[0]['next_after_ordinal'])
        self.assertEqual([r['row_ordinal'] for r in pages],[r['row_ordinal'] for r in whole])
        self.assertEqual({r['result_fingerprint'] for r in pages},{whole[0]['result_fingerprint']})
        shuffled=self.run_query(vendor_codes_json='[999,2,1,1]')
        self.assertEqual(shuffled[0]['result_fingerprint'],whole[0]['result_fingerprint'])
        self.assertEqual(int(shuffled[0]['requested_vendor_count']),3)
        absent=self.run_query(country_code='ABSENT')
        self.assertEqual(len(absent),4)
        self.assertEqual({r['scope_status'] for r in absent[1:]},{'NO_READABLE_ROWS'})
        self.assertEqual(int(absent[0]['expected_bucket_count']),0)
    def test_thirteen_vendor_set(self):
        codes=[64342,75256,64351,19215,65505,75257,22344,85058,71886,48378,65885,75691,72478]
        values=','.join(f"('SET',{v},'Synthetic','{c}','{s}',{pd})" for v in codes for c,s,pd in [('NR','EDI',1),('US','AutoUS',1),('SH','EDI',0)])
        self.db.query('INSERT INTO past_due.past_due_detail_view VALUES '+values)
        rows=self.run_query(country_code='SET',vendor_codes_json=json.dumps(codes))
        facts=[r for r in rows if r['record_type']=='FACT']
        self.assertEqual(len(facts),39)
        self.assertEqual(len(rows),53)
        self.assertEqual({int(r['vendor_code']) for r in facts},set(codes))
        self.assertEqual({int(r['vendor_past_due_count']) for r in facts},{2})

if __name__=='__main__':unittest.main()

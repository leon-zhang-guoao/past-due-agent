#!/usr/bin/env python3
"""Execute shipped SQL against synthetic local ClickHouse (optional chdb).

No production credentials or network. This is not Atlas/JDBC/platform validation.
Uses a temporary local data directory and never changes business tables.
"""
import json
import re
import tempfile
import unittest
from pathlib import Path
from edn_io import read
try:
    from chdb import session
except ImportError:
    session=None

ROOT=Path(__file__).resolve().parents[1]


@unittest.skipIf(session is None,'chdb not installed; SQL execution NOT VERIFIED')
class SQLFixtures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='pastdue-local-fixture-')
        cls.db=session.Session(cls.tmp.name)
        cls.doc=read(ROOT/'atlas/charts/pastdue.edn')
        cls.db.query('CREATE DATABASE past_due')
        cls.db.query('CREATE TABLE past_due.vendor_past_due_daily (country_code String,snapshot_date Date,vendor_code Int64,vendor_name String,eta_code String,source String,past_due_count Int64,total_open_count Int64) ENGINE=MergeTree ORDER BY (country_code,snapshot_date,vendor_code,eta_code,source)')
        values=[]
        for cc in ['CA','US']:
            for dt in ['2026-10-22','2026-10-23','2026-10-24','2026-10-25','2026-10-26']:
                for v in range(1,13):
                    # Includes decreases, UNKNOWN, joint US sources and >5 buckets.
                    for i,(code,src) in enumerate([('NR','EDI870'),('SH','EDI856'),('US','AutoUS'),('US','Vendor Report'),('DD','Carrier'),('SC','EDI870'),('RQ',''),('','UNKNOWN')]):
                        p=20+i
                        count=p if dt<'2026-10-26' else p+(30-v if i<4 else -2)
                        values.append(f"('{cc}','{dt}',{v},'Vendor {v}','{code}','{src}',{count},100)")
        cls.db.query('INSERT INTO past_due.vendor_past_due_daily VALUES '+','.join(values))
        extra=[]
        for cc in ['ZERO','EMPTY','LAG','BAD','ONE']:
            dates=['2026-10-26'] if cc=='ONE' else ['2026-10-19','2026-10-20'] if cc=='LAG' else ['2026-10-23','2026-10-26']
            for dt in dates:
                count=0 if cc=='ZERO' else 20 if cc=='EMPTY' else 110 if cc=='BAD' else 10
                extra.append(f"('{cc}','{dt}',1,'Fixture','NR','EDI',{count},100)")
        cls.db.query('INSERT INTO past_due.vendor_past_due_daily VALUES '+','.join(extra))
        cls.db.query('CREATE TABLE past_due.past_due_detail_view (country_code String,from_acct_no Int64,vendor_name String,eta_code String,source String,is_pastdue Int64,alert_type String) ENGINE=MergeTree ORDER BY (country_code,from_acct_no)')
        cls.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',1,'One','NR','EDI',1,'LATE'),('CA',1,'One','SH','EDI',0,'OPEN'),('CA',2,'Two','US','AutoUS',1,'LATE'),('CA',3,'Unmapped','NR','EDI',1,'LATE')")
        cls.db.query('CREATE TABLE past_due.vendor_master_mapping_view (country_code String,vendor_no Int64,department_type String,master_primary String,master_manager String) ENGINE=MergeTree ORDER BY (country_code,vendor_no)')
        cls.db.query("INSERT INTO past_due.vendor_master_mapping_view VALUES ('CA',1,'ETAD','A','M'),('CA',1,'ETAD','A','M'),('CA',2,'ETAD','B','N')")
    @classmethod
    def tearDownClass(cls):
        del cls.db;cls.tmp.cleanup()
    def sql(self,rid,**overrides):
        page=overrides.pop('_page',None)
        b=self.doc[':execution'][':bindings'][rid+'-sql']
        params={'country_code':'CA','calendar_mode':'WEEKDAYS','target_snapshot_date':'2026-10-26','previous_snapshot_date':'2026-10-23','selection_mode':'POSITIVE_TOP_N','top_n':10,'vendor_code':0,'relative_change_threshold':'0.10','range_start_date':'2026-10-23','range_end_date':'2026-10-26','grain':'VENDOR','vendor_code_filter':'','department_type_filter':'','master_primary_filter':'','master_manager_filter':'','denominator_scope':'COUNTRY_ALL'}
        params.update(overrides)
        def replace(m):
            x=params[m.group(1)]
            if m.group(1) in ('top_n','vendor_code'):return str(x)
            if m.group(1)=='relative_change_threshold':return 'toDecimal128('+repr(str(x))+',18)'
            return "'"+str(x).replace("'","''")+"'"
        q=b[':binding/implementation'][':query']
        # Replace named params only outside quoted literals.
        q=re.sub(r"'(?:''|[^'])*'|:([a-zA-Z_][a-zA-Z0-9_]*)",lambda m:m.group(0) if m.group(1) is None else replace(m),q)
        q=q.replace("toDate(now('America/Los_Angeles'))","toDate('2026-10-27')")
        if page is not None:
            offset,limit=page
            q=f'SELECT * FROM ({q}) ORDER BY row_ordinal ASC LIMIT {limit} OFFSET {offset}'
        rows=json.loads(str(self.db.query(q+' FORMAT JSON','CSV')))['data']
        mapped={v for k,v in b[':binding/output-map'].items() if self.doc[':vocabulary'][':attributes'][k][':attribute/type']!=':ref'}
        for row in rows:self.assertEqual(set(row),mapped,'Actual SQL columns differ from mapped scalar outputs: '+rid)
        return rows
    def test_monitor_and_history(self):
        rows=self.sql('pastdue_v3_monitor_query_countries')
        for row in [r for r in rows if r['country_code'] in ['CA','US']]:
            self.assertEqual((row['target_snapshot_date'],row['previous_snapshot_date']),('2026-10-26','2026-10-23'))
            self.assertEqual(int(row['snapshot_gap_days']),3)
            self.assertEqual(int(row['target_lag_workdays']),0)
            self.assertEqual(int(row['missing_workdays_between']),0)
            self.assertEqual(row['business_status'],'TRIGGERED')
        h=self.sql('pastdue_v3_history_query_trigger_events')
        self.assertTrue(h);self.assertTrue(all(r['target_snapshot_date'] not in ['2026-10-24','2026-10-25'] for r in h))
    def test_all_batch_queries_and_reconciliation(self):
        v=self.sql('pastdue_v3_analysis_query_vendor_comparisons')
        self.assertEqual(len(v),10);self.assertEqual([int(r['vendor_code']) for r in v],list(range(1,11)))
        c=self.sql('pastdue_v3_analysis_query_eta_code_changes')
        s=self.sql('pastdue_v3_analysis_query_eta_source_changes')
        u=self.sql('pastdue_v3_analysis_query_us_source_changes')
        checks=self.sql('pastdue_v3_analysis_query_vendor_checks')
        self.assertEqual(len(checks),30)
        self.assertTrue(all(r['counts_match'] for r in checks));self.assertTrue(all(r['required'] for r in checks))
        self.assertEqual(len({r['row_ordinal'] for r in c}),len(c))
        for vendor in v:
            vc=vendor['vendor_code']
            for rows in [c,s]:
                rs=[r for r in rows if r['vendor_code']==vc]
                self.assertEqual(sum(int(r['target_past_due_count']) for r in rs),int(vendor['target_past_due_count']))
                self.assertEqual(sum(int(r['previous_past_due_count']) for r in rs),int(vendor['previous_past_due_count']))
                self.assertEqual(len(rs),int(rs[0]['expected_bucket_count']))
            us=[r for r in u if r['vendor_code']==vc];code=[r for r in c if r['vendor_code']==vc and r['eta_value']=='US'][0]
            self.assertEqual(sum(int(r['target_past_due_count']) for r in us),int(code['target_past_due_count']))
        self.assertTrue(any(int(r['past_due_count_change'])<0 for r in c))
        self.assertTrue(any(int(r['other_bucket_count'])>0 for r in c))
    def test_invalid_pair_and_single_vendor(self):
        bad=self.sql('pastdue_v3_analysis_query_scope_status',previous_snapshot_date='2026-10-24')
        self.assertEqual(bad[0]['scope_status'],'INVALID_PAIR')
        self.assertEqual(self.sql('pastdue_v3_analysis_query_vendor_comparisons',previous_snapshot_date='2026-10-24'),[])
        good=self.sql('pastdue_v3_analysis_query_vendor_comparisons',selection_mode='SINGLE_VENDOR',vendor_code=12,top_n=1)
        self.assertEqual([int(r['vendor_code']) for r in good],[12])
    def test_calendar_override(self):
        rows=self.sql('pastdue_v3_monitor_query_countries',calendar_mode='CALENDAR_DAYS')
        self.assertTrue(all(r['previous_snapshot_date']=='2026-10-25' for r in rows if r['country_code'] in ['CA','US']))
    def test_current_denominators_and_mapping(self):
        rows=self.sql('pastdue_v3_current_analysis_query_rows')
        self.assertEqual(len(rows),3) # includes unmapped Vendor
        one=[r for r in rows if int(r['vendor_code'])==1][0]
        self.assertAlmostEqual(float(one['past_due_ratio']),.5)
        self.assertEqual(int(one['denominator_past_due_count']),3)
        filtered=self.sql('pastdue_v3_current_analysis_query_rows',master_primary_filter='A')
        self.assertEqual(len(filtered),1);self.assertEqual(int(filtered[0]['open_line_count']),2)
        self.assertEqual(int(filtered[0]['denominator_past_due_count']),3)
        local=self.sql('pastdue_v3_current_analysis_query_rows',master_primary_filter='A',denominator_scope='FILTERED_POPULATION')
        self.assertEqual(int(local[0]['denominator_past_due_count']),1)
        for grain in ['VENDOR_CODE','VENDOR_SOURCE']:self.assertTrue(self.sql('pastdue_v3_current_analysis_query_rows',grain=grain))
        alerts=self.sql('pastdue_current_vendor_alert_type_counts')
        self.assertEqual({int(r['vendor_code']) for r in alerts},{1,2,3})
        filtered=self.sql('pastdue_current_vendor_alert_type_counts',master_primary_filter='A')
        self.assertEqual({int(r['vendor_code']) for r in filtered},{1})
    def test_zero_empty_invalid_and_stale_scopes(self):
        monitor={r['country_code']:r for r in self.sql('pastdue_v3_monitor_query_countries')}
        self.assertEqual(monitor['ZERO']['business_status'],'NORMAL')
        self.assertIsNone(monitor['ZERO']['relative_ratio_change'])
        self.assertEqual(monitor['BAD']['data_status'],'DATA_ERROR')
        self.assertEqual(monitor['ONE']['data_status'],'INSUFFICIENT')
        self.assertEqual(monitor['LAG']['data_status'],'STALE')
        empty=self.sql('pastdue_v3_analysis_query_scope_status',country_code='EMPTY')[0]
        self.assertEqual(empty['scope_status'],'OK');self.assertEqual(int(empty['selected_vendor_count']),0)
        self.assertEqual(self.sql('pastdue_v3_analysis_query_vendor_checks',country_code='EMPTY'),[])
        self.assertEqual(self.sql('pastdue_v3_analysis_query_scope_status',country_code='BAD')[0]['scope_status'],'DATA_ERROR')
        self.assertEqual(self.sql('pastdue_v3_analysis_query_scope_status',selection_mode='SINGLE_VENDOR',vendor_code=999)[0]['scope_status'],'VENDOR_NOT_FOUND')
    def test_server_other_and_page_counts(self):
        rows=self.sql('pastdue_v3_analysis_query_eta_code_changes')
        for vc in range(1,11):
            rs=[r for r in rows if int(r['vendor_code'])==vc]
            rest=[r for r in rs if int(r['display_rank'])>5]
            self.assertEqual(sum(int(r['target_past_due_count']) for r in rest),int(rs[0]['other_target_count']))
            self.assertEqual(sum(int(r['previous_past_due_count']) for r in rest),int(rs[0]['other_previous_count']))
        ordered=sorted(rows,key=lambda r:int(r['row_ordinal']))
        pages=[ordered[i:i+7] for i in range(0,len(rows),7)]
        self.assertEqual(len([r for page in pages for r in page]),sum(int(r['expected_bucket_count']) for r in self.sql('pastdue_v3_analysis_query_vendor_checks') if r['dimension']=='code'))
        actual=[]
        for offset in range(0,len(rows),7):
            actual+=self.sql('pastdue_v3_analysis_query_eta_code_changes',_page=(offset,7))
        self.assertEqual([r['row_ordinal'] for r in actual],[r['row_ordinal'] for r in ordered])


if __name__=='__main__':unittest.main()

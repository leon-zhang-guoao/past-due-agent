#!/usr/bin/env python3
"""Execute shipped SQL against synthetic local ClickHouse (optional chdb).

No production credentials or network. This is not Atlas/JDBC/platform validation.
Uses a temporary local data directory and never changes business tables.
"""
import json
from datetime import datetime
from zoneinfo import ZoneInfo
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
        for cc in ['ZERO','EMPTY','LAG','BAD','ONE','NOOPEN']:
            dates=['2026-10-26'] if cc=='ONE' else ['2026-10-19','2026-10-20'] if cc=='LAG' else ['2026-10-23','2026-10-26']
            for dt in dates:
                count=0 if cc in ['ZERO','NOOPEN'] else 20 if cc=='EMPTY' else 110 if cc=='BAD' else 10
                op=0 if cc=='NOOPEN' else 100
                extra.append(f"('{cc}','{dt}',1,'Fixture','NR','EDI',{count},{op})")
        cls.db.query('INSERT INTO past_due.vendor_past_due_daily VALUES '+','.join(extra))
        # Vendor 1 disappears at T, but Country retains a valid complete T.
        cls.db.query("INSERT INTO past_due.vendor_past_due_daily VALUES ('GONE','2026-10-23',1,'Gone','NR','EDI',10,100),('GONE','2026-10-26',2,'Other','SH','EDI',5,100)")
        cls.db.query('CREATE TABLE past_due.past_due_detail_view (country_code String,from_acct_no Int64,vendor_name String,eta_code String,source String,is_pastdue Int64,alert_type String) ENGINE=MergeTree ORDER BY (country_code,from_acct_no)')
        cls.db.query("INSERT INTO past_due.past_due_detail_view VALUES ('CA',1,'One','NR','EDI',1,'LATE'),('CA',1,'One','SH','EDI',0,'OPEN'),('CA',2,'Two','US','AutoUS',1,'LATE'),('CA',3,'Unmapped','NR','EDI',1,'LATE')")
        cls.db.query('CREATE TABLE past_due.vendor_master_mapping_view (country_code String,vendor_no Int64,department_type String,master_primary String,master_manager String) ENGINE=MergeTree ORDER BY (country_code,vendor_no)')
        cls.db.query("INSERT INTO past_due.vendor_master_mapping_view VALUES ('CA',1,'ETAD','A','M'),('CA',1,'ETAD','A','M'),('CA',2,'ETAD','B','N')")
        cls.db.query("INSERT INTO past_due.vendor_master_mapping_view VALUES ('GONE',1,'ETAD','GoneOwner','M')")
    @classmethod
    def tearDownClass(cls):
        del cls.db;cls.tmp.cleanup()
    def sql(self,rid,**overrides):
        page=overrides.pop('_page',None)
        b=self.doc[':execution'][':bindings'][rid+'-sql']
        params={'country_code':'CA','calendar_mode':'WEEKDAYS','target_snapshot_date':'2026-10-26','previous_snapshot_date':'2026-10-23','selection_mode':'POSITIVE_TOP_N','top_n':10,'vendor_code':0,'relative_change_threshold':'0.10','range_start_date':'2026-10-23','range_end_date':'2026-10-26','grain':'VENDOR','vendor_code_filter':'','department_type_filter':'','master_primary_filter':'','master_manager_filter':'','denominator_scope':'COUNTRY_ALL','time_basis':'PAIR','pair_policy':'EXPLICIT','eta_code_filter':'','source_filter':'','ranking_metric':'NONE','sort_direction':'DESC','change_selection':'ALL'}
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
    def test_v31_disappearing_selected_vendor(self):
        for selection in [dict(vendor_code=1),dict(master_primary_filter='GoneOwner')]:
            for grain in ['VENDOR','VENDOR_CODE','VENDOR_SOURCE','COUNTRY']:
                rows=self.sql('pastdue_v3_explore_query_rows',country_code='GONE',top_n=0,
                              grain=grain,denominator_scope='FILTERED_POPULATION',**selection)
                facts=[r for r in rows if r['record_type']=='FACT']
                self.assertEqual(len(facts),1,(grain,selection))
                row=facts[0]
                self.assertEqual((int(row['previous_past_due_count']),int(row['target_past_due_count'])),(10,0))
                self.assertEqual(int(row['past_due_count_change']),-10)
                self.assertEqual(int(row['target_share_denominator']),0)
                self.assertIsNone(row['target_past_due_share'])
                if grain!='COUNTRY':
                    self.assertEqual(int(row['target_ratio_denominator']),0)
                    self.assertIsNone(row['target_past_due_ratio'])

    def test_report_linear_pipeline_parity(self):
        # Independent legacy collections use the same business definitions but
        # a different SQL execution plan; compare every selected Vendor/bucket.
        rows=self.sql('pastdue_v3_report_query_rows')
        legacy=self.sql('pastdue_v3_analysis_query_vendor_comparisons')
        metrics={int(r['vendor_code']):r for r in rows if r['record_type']=='VENDOR_METRICS'}
        for v in legacy:
            current=metrics[int(v['vendor_code'])]
            for field in v:
                if field in current:self.assertEqual(current[field],v[field],field)
        for dim,rid,record in [
            ('code','eta_code_changes','ETA_CODE_SUMMARY'),
            ('source','eta_source_changes','ETA_SOURCE_SUMMARY'),
            ('us','us_source_changes','US_SOURCE_SUMMARY')]:
            buckets=self.sql('pastdue_v3_analysis_query_'+rid)
            for vendor in metrics:
                raw=[r for r in buckets if int(r['vendor_code'])==vendor]
                raw.sort(key=lambda r:(-abs(int(r['past_due_count_change'])),-int(r['target_past_due_count']),r['eta_value']))
                summaries=[r for r in rows if r['record_type']==record and int(r['vendor_code'])==vendor]
                summaries.sort(key=lambda r:int(r['display_rank']))
                for actual,expected in zip(summaries[:5],raw[:5]):
                    for field in ['eta_value','previous_past_due_count','target_past_due_count','past_due_count_change','delta_country_contribution']:
                        self.assertEqual(actual[field],expected[field],(vendor,dim,field))
                if len(raw)>5:
                    other=summaries[-1];self.assertEqual(other['row_kind'],'OTHER')
                    for period in ['previous','target']:
                        self.assertEqual(int(other[period+'_past_due_count']),sum(int(r[period+'_past_due_count']) for r in raw[5:]))
                check=next(r for r in rows if r['record_type']=='VENDOR_CHECK' and int(r['vendor_code'])==vendor and r['dimension']==dim)
                deltas=[int(r['past_due_count_change']) for r in raw]
                self.assertEqual(int(check['increase_total']),sum(max(d,0) for d in deltas))
                self.assertEqual(int(check['decrease_total']),sum(min(d,0) for d in deltas))

    def test_report_linear_control_paths(self):
        rid='pastdue_v3_report_query_rows'
        for args,status in [
            ({'country_code':'MISSING'},'INVALID_PAIR'),
            ({'target_snapshot_date':'2026-10-28'},'INVALID_PAIR'),
            ({'previous_snapshot_date':'2026-10-22'},'INVALID_PAIR'),
            ({'calendar_mode':'INVALID'},'INVALID_PAIR'),
            ({'top_n':0},'INVALID_SELECTION'),
            ({'vendor_code':1},'INVALID_SELECTION'),
            ({'selection_mode':'SINGLE_VENDOR','vendor_code':999,'top_n':1},'VENDOR_NOT_FOUND'),
            ({'selection_mode':'SINGLE_VENDOR','vendor_code':0,'top_n':1},'INVALID_SELECTION'),
            ({'country_code':'BAD'},'DATA_ERROR'),
            ({'country_code':'NOOPEN'},'INVALID_DENOMINATOR')]:
            rows=self.sql(rid,**args)
            self.assertEqual(len(rows),1,args);self.assertEqual(rows[0]['scope_status'],status,args)
        for cc in ['ZERO','EMPTY']:
            rows=self.sql(rid,country_code=cc)
            self.assertEqual(len(rows),1);self.assertEqual(rows[0]['scope_status'],'OK')
            self.assertEqual(int(rows[0]['selected_vendor_count']),0)
        # A prior-only Vendor must also survive the report's signed single mode.
        rows=self.sql(rid,country_code='GONE',selection_mode='SINGLE_VENDOR',vendor_code=1,top_n=1)
        vendor=next(r for r in rows if r['record_type']=='VENDOR_METRICS')
        self.assertEqual((int(vendor['previous_past_due_count']),int(vendor['target_past_due_count'])),(10,0))
        self.assertEqual(float(vendor['past_due_count_relative_change']),-1)
        self.assertTrue(all(r['counts_match'] for r in rows if r['record_type']=='VENDOR_CHECK'))
        self.assertIsNone(vendor['target_vendor_ratio'])

    def test_report_classic_analyzer_parity(self):
        # 24.8 classic analyzer is useful compatibility evidence, NOT a 22.8
        # engine test. Preserve this limitation in the release record.
        baseline=self.sql('pastdue_v3_report_query_rows')
        original=self.db
        class Classic:
            def query(self,q,*args):
                if q.endswith(' FORMAT JSON'):
                    q=q[:-12]+' SETTINGS allow_experimental_analyzer=0 FORMAT JSON'
                return original.query(q,*args)
        self.db=Classic()
        try:classic=self.sql('pastdue_v3_report_query_rows')
        finally:self.db=original
        for row in baseline+classic:row.pop('queried_at_local',None)
        self.assertEqual(classic,baseline)

    def test_report_plan_reads_fact_table_twice(self):
        # EXPLAIN uses exactly the query SQL and input binding substitution from
        # sql(); query timing is intentionally not asserted on tiny fixtures.
        original=self.db
        class Capture:
            def __init__(self):self.query_text=''
            def query(self,q,*args):
                self.query_text=q
                return original.query(q,*args)
        capture=Capture();self.db=capture
        try:self.sql('pastdue_v3_report_query_rows')
        finally:self.db=original
        q=capture.query_text.removesuffix(' FORMAT JSON')
        plan=str(original.query('EXPLAIN '+q,'CSV'))
        self.assertEqual(plan.count('ReadFromMergeTree'),2,plan)

    def test_v31_report(self):
        rows=self.sql('pastdue_v3_report_query_rows')
        self.assertEqual(len([r for r in rows if r['record_type']=='VENDOR_METRICS']),10)
        self.assertEqual(len([r for r in rows if r['record_type']=='COUNTRY_CONTROL']),1)
        self.assertTrue(all(int(r['expected_record_count'])==len(rows) for r in rows))
        checks=[r for r in rows if r['record_type']=='VENDOR_CHECK']
        self.assertEqual(len(checks),30)
        self.assertTrue(all(r['counts_match'] for r in checks))
        for c in checks:
            facts=[r for r in rows if r['vendor_code']==c['vendor_code'] and r['dimension']==c['dimension'] and r['record_type'].endswith('_SUMMARY')]
            self.assertLessEqual(len(facts),6)
            self.assertEqual(sum(int(r['previous_past_due_count']) for r in facts),int(c['previous_total']))
            self.assertEqual(sum(int(r['target_past_due_count']) for r in facts),int(c['target_total']))
        self.assertTrue(any(r['row_kind']=='OTHER' for r in rows))

    def test_v31_explore(self):
        rows=self.sql('pastdue_v3_explore_query_rows')
        self.assertEqual(rows[0]['scope_status'],'OK')
        facts=[r for r in rows if r['record_type']=='FACT']
        self.assertEqual(len(facts),10)
        current=self.sql('pastdue_v3_explore_query_rows',time_basis='CURRENT',grain='VENDOR_CODE',eta_code_filter='NR',top_n=0,ranking_metric='PAST_DUE_RATIO')
        facts=[r for r in current if r['record_type']=='FACT']
        one=next(r for r in facts if int(r['vendor_code'])==1)
        self.assertEqual(int(one['target_ratio_denominator']),2)
        self.assertAlmostEqual(float(one['target_past_due_ratio']),.5)
        self.assertEqual(int(one['target_share_denominator']),3)
        history=self.sql('pastdue_v3_explore_query_rows',time_basis='RANGE',top_n=0)
        self.assertEqual(len([r for r in history if r['record_type']=='FACT']),24)
        self.assertTrue(all(r['target_snapshot_date'] in ['2026-10-23','2026-10-26'] for r in history if r['record_type']=='FACT'))

    def test_v31_explore_shapes_and_invalid(self):
        for grain in ['COUNTRY','VENDOR','VENDOR_CODE','VENDOR_SOURCE','VENDOR_CODE_SOURCE']:
            rows=self.sql('pastdue_v3_explore_query_rows',grain=grain,top_n=0)
            self.assertTrue(any(r['record_type']=='FACT' for r in rows),grain)
        explicit=self.sql('pastdue_v3_explore_query_rows',previous_snapshot_date='2026-10-22',top_n=0)
        self.assertTrue(all(r['previous_snapshot_date']=='2026-10-22' for r in explicit if r['record_type']=='FACT'))
        nearest=self.sql('pastdue_v3_explore_query_rows',pair_policy='PREVIOUS_AVAILABLE',previous_snapshot_date='1970-01-01',top_n=0)
        self.assertTrue(all(r['previous_snapshot_date']=='2026-10-23' for r in nearest if r['record_type']=='FACT'))
        improve=self.sql('pastdue_v3_explore_query_rows',grain='VENDOR_CODE',ranking_metric='COUNT_CHANGE',sort_direction='ASC',change_selection='NEGATIVE',top_n=100)
        self.assertTrue(all(int(r['past_due_count_change'])<0 for r in improve if r['record_type']=='FACT'))
        values=[int(r['past_due_count_change']) for r in improve if r['record_type']=='FACT']
        self.assertEqual(values,sorted(values));self.assertTrue(values)
        joint=self.sql('pastdue_v3_explore_query_rows',grain='VENDOR_CODE_SOURCE',eta_code_filter='US',top_n=0)
        self.assertTrue(all(r['eta_code']=='US' for r in joint if r['record_type']=='FACT'))
        snap=self.sql('pastdue_v3_explore_query_rows',time_basis='SNAPSHOT',top_n=0)
        self.assertEqual(len([r for r in snap if r['record_type']=='FACT']),12)
        bad=self.sql('pastdue_v3_explore_query_rows',time_basis='CURRENT',ranking_metric='COUNT_CHANGE')
        self.assertEqual(len(bad),1);self.assertEqual(bad[0]['scope_status'],'UNSUPPORTED_COMBINATION')
        bad=self.sql('pastdue_v3_explore_query_rows',time_basis='RANGE',range_start_date='2020-01-01')
        self.assertEqual(bad[0]['scope_status'],'INVALID_RANGE')
        bad=self.sql('pastdue_v3_explore_query_rows',previous_snapshot_date='2026-10-24')
        self.assertEqual(bad[0]['scope_status'],'INVALID_PAIR')
        bad=self.sql('pastdue_v3_explore_query_rows',country_code='MISSING')
        self.assertNotEqual(bad[0]['scope_status'],'OK')
        empty=self.sql('pastdue_v3_explore_query_rows',vendor_code=999)
        self.assertEqual(len(empty),1);self.assertEqual(empty[0]['scope_status'],'OK')

    def test_v31_filter_population_and_paging(self):
        rows=self.sql('pastdue_v3_explore_query_rows',time_basis='CURRENT',top_n=0,master_primary_filter='A',denominator_scope='FILTERED_POPULATION',eta_code_filter='NR',grain='VENDOR_CODE')
        facts=[r for r in rows if r['record_type']=='FACT']
        self.assertEqual(len(facts),1)
        self.assertEqual(int(facts[0]['target_ratio_denominator']),2)
        self.assertEqual(int(facts[0]['target_share_denominator']),1)
        unfiltered=self.sql('pastdue_v3_explore_query_rows',time_basis='CURRENT',top_n=0)
        self.assertEqual({int(r['vendor_code']) for r in unfiltered if r['record_type']=='FACT'},{1,2,3})
        for rid in ['pastdue_v3_report_query_rows','pastdue_v3_explore_query_rows']:
            allrows=self.sql(rid)
            pages=[]
            for offset in range(0,len(allrows),17):pages+=self.sql(rid,_page=(offset,17))
            self.assertEqual(len(pages),len(allrows))
            self.assertEqual([int(r['row_ordinal']) for r in pages],list(range(1,len(allrows)+1)))
            self.assertTrue(all(int(r['expected_record_count'])==len(allrows) for r in pages))
        empty=self.sql('pastdue_v3_report_query_rows',country_code='EMPTY')
        self.assertEqual(len(empty),1);self.assertEqual(empty[0]['scope_status'],'OK')
        self.assertEqual(int(empty[0]['selected_vendor_count']),0)
        invalid=self.sql('pastdue_v3_report_query_rows',previous_snapshot_date='2026-10-24')
        self.assertEqual(len(invalid),1);self.assertEqual(invalid[0]['scope_status'],'INVALID_PAIR')

    def test_v31_dst_and_controls(self):
        zone=ZoneInfo('America/Los_Angeles')
        self.assertEqual(datetime(2026,1,15,tzinfo=zone).tzname(),'PST')
        self.assertEqual(datetime(2026,7,15,tzinfo=zone).tzname(),'PDT')
        self.assertEqual(datetime(2026,7,15,tzinfo=zone).utcoffset().total_seconds(),-7*3600)
        for basis in ['CURRENT','SNAPSHOT','PAIR','RANGE']:
            kwargs={'time_basis':basis,'top_n':0}
            if basis=='CURRENT':kwargs['country_code']='CA'
            rows=self.sql('pastdue_v3_explore_query_rows',**kwargs)
            self.assertEqual(rows[0]['scope_status'],'OK')
            self.assertEqual(len({r['scope_key'] for r in rows}),1)
            self.assertEqual(len({r['row_ordinal'] for r in rows}),len(rows))
        invalid=self.sql('pastdue_v3_report_query_rows',country_code='BAD')
        self.assertEqual(len(invalid),1);self.assertEqual(invalid[0]['scope_status'],'DATA_ERROR')
        good=self.sql('pastdue_v3_report_query_rows',selection_mode='SINGLE_VENDOR',vendor_code=12,top_n=1)
        self.assertEqual({int(r['vendor_code']) for r in good if r['record_type']=='VENDOR_METRICS'},{12})
        for cc in ['US','CA']:
            rows=self.sql('pastdue_v3_report_query_rows',country_code=cc)
            self.assertTrue(any(r['record_type']=='US_SOURCE_SUMMARY' for r in rows))
            ctl=next(r for r in rows if r['record_type']=='COUNTRY_CONTROL')
            self.assertGreater(int(ctl['target_country_past_due_count']),0)
            self.assertGreater(float(ctl['target_country_ratio']),0)

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

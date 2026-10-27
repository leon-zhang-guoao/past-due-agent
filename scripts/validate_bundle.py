#!/usr/bin/env python3
"""Validate the actual Atlas 2.0 artifact. Never read reference shards as truth.

Offline only: does not certify the platform schema, SQL execution or an Agent.
"""
import math
import re
import sys
from pathlib import Path
from edn_io import read, loads, K

ROOT=Path(__file__).resolve().parents[1]
CHART_DIR=ROOT/'atlas/charts'
SOURCE_FILE=ROOT/'atlas/sources.edn'
SKILL_DIR=ROOT/'skill/pastdue-analysis'
read_edn=read


def graph():
    return read(CHART_DIR/'pastdue.edn')


def flatten_output(x):
    if isinstance(x,str):return {x} if x.startswith(':') and '/' in x else set()
    if isinstance(x,dict):return set(x).union(*(flatten_output(v) for v in x.values()))
    if isinstance(x,list):return set().union(*(flatten_output(v) for v in x))
    return set()


def placeholders(sql):
    clean=re.sub(r"'(?:''|\\.|[^'])*'|--[^\n]*|/\*.*?\*/",' ',sql,flags=re.S)
    return set(re.findall(r'(?<!:):([a-zA-Z_][\w]*)',clean))


def validate(doc):
    errors=[]
    v=doc[':vocabulary']; e=doc[':execution']; g=doc[':graph']; own=doc[':ownership']
    entities=v[':entities']; attrs=v[':attributes']; resolvers=e[':resolvers']; bindings=e[':bindings']; selection=e[':selection']
    if set(entities)!=set(g[':graph/entities']):errors.append('graph/entities differs from vocabulary')
    if set(resolvers)!=set(g[':graph/resolvers']):errors.append('graph/resolvers differs from execution')
    if set(own[':entities'])!=set(entities):errors.append('entity ownership incomplete')
    if set(own[':resolvers'])!=set(resolvers):errors.append('resolver ownership incomplete')
    if set(selection)!={(K(':resolver'),rid) for rid in resolvers}:errors.append('selection does not exactly cover resolvers')
    for ns,d in entities.items():
        for a in d[':entity/identity']+d[':entity/attributes']:
            if a not in attrs:errors.append(f'{ns}: unknown attribute {a}')
        if not set(d[':entity/identity'])<=set(d[':entity/attributes']):errors.append(f'{ns}: identity absent from fields')
    for a,d in attrs.items():
        if d.get(':attribute/type')==':ref' and d.get(':attribute/target') not in entities:errors.append(f'{a}: missing target')
    for rid,r in resolvers.items():
        key=(K(':resolver'),rid);bid=selection.get(key);b=bindings.get(bid,{})
        if b.get(':binding/for')!=list(key):errors.append(f'{rid}: binding identity mismatch')
        if b.get(':binding/source') not in e[':sources']:errors.append(f'{rid}: unknown source slot')
        inputs=set(r.get(':input',[]));outputs=flatten_output(r.get(':output',[]))
        if not inputs|outputs <=set(attrs):errors.append(f'{rid}: undeclared input/output')
        im=b.get(':binding/input-map',{});om=b.get(':binding/output-map',{})
        if set(im)!=inputs:errors.append(f'{rid}: input mapping does not equal required inputs')
        if set(om)!=outputs:errors.append(f'{rid}: output mapping does not equal projection')
        sql=b.get(':binding/implementation',{}).get(':query','')
        if placeholders(sql)!=set(im.values()):errors.append(f'{rid}: SQL placeholders mismatch {placeholders(sql)^set(im.values())}')
        if not re.match(r'\s*(WITH|SELECT)\b',sql,re.I):errors.append(f'{rid}: not a read query')
        if re.search(r';\s*\S',re.sub(r"'(?:''|\\.|[^'])*'",'',sql)):errors.append(f'{rid}: multiple SQL statements')
        if re.search(r'\b(?:INSERT|DELETE|UPDATE|DROP|ALTER)\b',re.sub(r"'[^']*'",'',sql),re.I):errors.append(f'{rid}: non-read SQL')
        if re.search(r'snapshot_date\s+IN\s*\(\s*(?:p\.)?(?:latest|target)_snapshot_date',sql,re.I):errors.append(f'{rid}: unsupported pair join')
        if 'toUInt8(' in sql:errors.append(f'{rid}: unsafe JDBC UInt8 output')
        if rid.startswith('pastdue_v3_'):
            if r.get(':pagination')!={':default':200,':max':1000}:errors.append(f'{rid}: missing v3 page bounds')
            if r.get(':result/cardinality')!=':many':errors.append(f'{rid}: invalid cardinality')
            allowed=set(r.get(':sort',{}).get(':allowed',[]))
            if not allowed<=outputs or r[':sort'][':default'][0] not in allowed:errors.append(f'{rid}: invalid sort')
    # Every non-input attribute is reachable through some declared resolver output.
    reachable=set().union(*(flatten_output(r[':output']) for r in resolvers.values()))
    for ns,d in entities.items():
        for a in d[':entity/attributes']:
            if a not in reachable and a not in d[':entity/identity']:errors.append(f'{ns}: unreachable field {a}')
    return errors


def check_examples(doc):
    errors=[];attrs=doc[':vocabulary'][':attributes'];rs=list(doc[':execution'][':resolvers'].values())
    text=(SKILL_DIR/'references/atlas-query-playbook.md').read_text()
    for block in re.findall(r'```clojure\s*\n(.*?)```',text,re.S):
        try:x=loads(block)
        except Exception as exc:errors.append('EQL example parse: '+str(exc));continue
        if not isinstance(x,dict) or ':entity' not in x or ':query' not in x:errors.append('Example must be {:entity ... :query ...}');continue
        entity=x[':entity']
        for node in x[':query']:
            join=node[0] if isinstance(node,list) and not isinstance(node,dict) else node
            if not isinstance(join,dict):errors.append('Expected a collection join');continue
            for coll,fields in join.items():
                matches=[r for r in rs if any(isinstance(o,dict) and coll in o for o in r[':output'])]
                if not matches:errors.append('Unknown collection '+coll);continue
                valid=[r for r in matches if set(r.get(':input',[]))<=set(entity)]
                if not valid:errors.append('Missing required inputs for '+coll);continue
                output=set().union(*(flatten_output(r[':output']) for r in valid))
                if not set(fields)<=output:errors.append('Unknown projected fields for '+coll)
                if coll not in attrs:errors.append('Undeclared join '+coll)
    return errors


def check_skill():
    errors=[]
    s=(SKILL_DIR/'SKILL.md').read_text(encoding='utf-8')
    if not s.startswith('---\nname: pastdue-analysis\ndescription:'):errors.append('Invalid Skill frontmatter')
    for f in re.findall(r'`(references/[\w.\-/]+)`',s):
        if not (SKILL_DIR/f).exists():errors.append('Missing skill reference '+f)
    p=read(SKILL_DIR/'references/policy.edn')
    if p[':monitor-country-allowlist']!=['US','CA']:errors.append('Default allowlist changed')
    t=p[':country-trigger'][':relative-ratio-change-gte']
    if not isinstance(t,(float,int)) or not math.isfinite(t) or t<=0:errors.append('Invalid threshold')
    if p[':country-trigger'][':minimum-percentage-point-change'] is not None:errors.append('Unexpected pp threshold')
    if p[':snapshot-calendar'][':weekdays']!=[1,2,3,4,5]:errors.append('Invalid workweek')
    if p.get(':business-timezone')!='PST':errors.append('Business timezone must be PST')
    if p.get(':business-timezone-iana')!='America/Los_Angeles':errors.append('Business timezone IANA must be America/Los_Angeles')
    if "now('UTC')" in (CHART_DIR/'pastdue.edn').read_text(encoding='utf-8'):errors.append('Graph still uses UTC now()')
    display=read(SKILL_DIR/'references/table-display-contract.edn')
    guide=(SKILL_DIR/'references/table-display-contract.md').read_text(encoding='utf-8')
    for name,module in display[':modules'].items():
        module_id=name.lstrip(':')
        m=re.search(r'## module: '+module_id+r'\s*\n```fields\n(.*?)\n```',guide,re.S)
        if not m or m.group(1).splitlines()!=[c[':id'] for c in module[':columns']]:errors.append('Display guide drift: '+module_id)
    for x in (ROOT/'agent/SYSTEM_PROMPT.md', SKILL_DIR/'SKILL.md'):
        if re.search(r'(?<!No )machine-enforced Kernel|machine enforcement lives',x.read_text(encoding='utf-8'),re.I):errors.append('Unwired runtime claim')
    return errors


def main():
    try:
        d=graph();errors=validate(d)+check_examples(d)+check_skill()
        read(SOURCE_FILE)
    except Exception as exc:
        print('FAIL',type(exc).__name__,exc);return 1
    for err in errors:print('FAIL',err)
    if errors:return 1
    print(f"PASS authoritative pastdue.edn: {len(d[':execution'][':resolvers'])} resolvers, {len(d[':vocabulary'][':attributes'])} attributes")
    print('NOT RUN: Atlas config-check, deployed JDBC/SQL, live Agent acceptance. No production certification.')
    return 0


if __name__=='__main__':sys.exit(main())

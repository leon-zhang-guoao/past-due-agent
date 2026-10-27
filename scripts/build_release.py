#!/usr/bin/env python3
"""Development-only allowlist packaging with reproducible hashes and no secrets."""
import argparse
import hashlib
import re
import sys
import zipfile
from pathlib import Path
from edn_io import read,write,K

ROOT=Path(__file__).resolve().parents[1]
REF=ROOT/'skill/pastdue-analysis/references'
MANIFEST=REF/'release-manifest.edn'
ARCHIVE=ROOT/'releases/pastdue-agent-v3.0.0.zip'


def files():
    paths=[ROOT/'agent/SYSTEM_PROMPT.md',ROOT/'agent/INVOCATION_EXAMPLES.md',ROOT/'atlas/charts/pastdue.edn',ROOT/'atlas/sources.edn',ROOT/'README.md',ROOT/'ACCEPTANCE_TESTS.md']
    paths += [p for p in (ROOT/'skill/pastdue-analysis').rglob('*') if p.is_file() and p!=MANIFEST and 'scripts' not in p.relative_to(ROOT/'skill/pastdue-analysis').parts and '__pycache__' not in p.parts]
    paths += [ROOT/'docs'/n for n in ['PASTDUE_AGENT_REDESIGN.md','API_V3.md','DEPLOYMENT_V3.md','VALIDATION_V3.md']]
    return sorted(set(paths))


def digest():return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files()}


def inspect():
    errors=[]
    for p in files():
        text=p.read_text()
        if re.search(r'oat_v1\.|Authorization:\s*Bearer\s+\S+|jdbc:clickhouse://(?!\$)',text):errors.append('Potential credential/endpoint in '+str(p))
        if p.suffix.lower() in ['.csv','.py','.pyc'] or p.name in ['chat.md','cursor_report_template_standardization.md']:errors.append('Disallowed file '+str(p))
    return errors


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    errors=inspect()
    if errors:print('\n'.join(errors));return 1
    hashes=digest()
    if args.check:
        m=read(MANIFEST)
        if m[':sha256']!=hashes:print('FAIL release hashes drift');return 1
        with zipfile.ZipFile(ARCHIVE) as z:
            expected=set(hashes)|{str(MANIFEST.relative_to(ROOT))}
            if set(z.namelist())!=expected:print('FAIL archive file set');return 1
            for path,h in hashes.items():
                if hashlib.sha256(z.read(path)).hexdigest()!=h:print('FAIL archive digest',path);return 1
            if z.read(str(MANIFEST.relative_to(ROOT)))!=MANIFEST.read_bytes():print('FAIL manifest in zip');return 1
        print('PASS release hashes, archive closure and credential allowlist');return 0
    doc=read(ROOT/'atlas/charts/pastdue.edn')
    required={rid:r[':input'] for rid,r in doc[':execution'][':resolvers'].items() if rid.startswith('pastdue_v3_')}
    write(MANIFEST,{K(':release-version'):'3.0.0',K(':status'):'PENDING_LIVE_ACCEPTANCE',K(':atlas-semantic-format'):'2.0.0',K(':required-resolvers'):required,K(':offline-contracts'):'PASS',K(':local-clickhouse-sql'):'PASS_SYNTHETIC_CHDB_24.8.4.1',K(':skill-structure'):'PASS',K(':platform-config-check'):'NOT_RUN',K(':deployed-jdbc-sql'):'NOT_RUN',K(':real-agent-eval'):'NOT_RUN',K(':manifest-self-hash'):'excluded-to-avoid-recursion',K(':sha256'):hashes})
    ARCHIVE.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(ARCHIVE,'w',zipfile.ZIP_DEFLATED) as z:
        for p in files()+[MANIFEST]:
            info=zipfile.ZipInfo(str(p.relative_to(ROOT)),date_time=(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16
            z.writestr(info,p.read_bytes())
    print('Built',ARCHIVE,'PENDING_LIVE_ACCEPTANCE');return 0


if __name__=='__main__':sys.exit(main())

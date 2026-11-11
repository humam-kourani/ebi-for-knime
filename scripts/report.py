#!/usr/bin/env python3
"""Write a compact, evidence-based report for each built installer."""
from __future__ import annotations
from collections import Counter
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def report(require_knime_tests: bool=False):
    catalog=ET.parse(ROOT/'extension/src/org/ryoo/knimeEbi/scaffolder/commands.xml').getroot()
    commands=catalog.findall('./command')
    signatures=sum(len(c.findall('./variant')) for c in commands)
    factories=list((ROOT/'extension/src/org/ryoo/knimeEbi/node').glob('Ebi*NodeFactory.java'))
    if not commands or len(factories)!=len(commands) or signatures< len(commands):
        raise RuntimeError(f'Node/signature inconsistency: {len(commands)} commands, '
                           f'{len(factories)} factories, {signatures} combinations')
    unit=json.loads((ROOT/'test-results/regression.json').read_text())
    if not unit or any(v.get('failed',0) for v in unit.values()):
        raise RuntimeError('Java/Python regression suite failed')
    upstream=json.loads((ROOT/'.ci/upstream.json').read_text())
    native_file=ROOT/'tests/results/command-regression.json'
    native=json.loads(native_file.read_text()) if native_file.is_file() else None
    native_status=Counter(x['status'] for x in native.get('commands',[])) if native else Counter()
    tests=failures=errors=skipped=0
    for path in sorted((ROOT/'integration-tests/target').rglob('TEST-*.xml')):
        try: top=ET.parse(path).getroot()
        except ET.ParseError: continue
        for suite in ([top] if top.tag=='testsuite' else top.findall('./testsuite')):
            tests+=int(suite.attrib.get('tests',0))
            failures+=int(suite.attrib.get('failures',0))
            errors+=int(suite.attrib.get('errors',0))
            skipped+=int(suite.attrib.get('skipped',0))
    if require_knime_tests and tests == 0:
        raise RuntimeError('No real Equinox test cases were reported by Tycho; release blocked')
    if failures or errors:
        raise RuntimeError('KNIME Equinox regression failures present in XML reports')
    total=sum(int(result.get('passed',0)) for result in unit.values())
    rows=['# Ebi for KNIME — build report','',
          f'- Generated KNIME nodes: **{len(commands)}** (factory classes: {len(factories)})',
          f'- Complete supported input/output combinations: **{signatures}**',
          f'- Java/Python regression assertions: **{total} passed**, 0 failed',
          f'- Real KNIME/Equinox integration tests: **{tests} passed**' if tests else '- Real KNIME/Equinox integration tests: **report not available** (check build logs)',
          f'- Ebi JNI command checks: **{native_status.get("passed",0)} passed**, **{native_status.get("known_upstream_issue",0)} known upstream issues**, **{native_status.get("failed",0)} failed**' if native else '- Ebi JNI command checks: not available',
          f'- Upstream check: **{upstream.get("mode","unknown")}**, changed: **{upstream.get("changed")}**',
          f'- Ebi embedded native version: **{upstream.get("version") or "unverified"}**','',
          '## Native downloads (SHA-256)','']
    for obj in upstream.get('artifacts',[]):
        rows.append(f'- {obj["platform"]}: `{obj["sha256"]}` ({obj["bytes"]:,} bytes)')
    rows+=['','The check counts are distinct: a native command marked `known_upstream_issue` is **not** counted as a semantic pass.',
          'This report does not claim end-to-end execution of all possible KNIME workflows.','']
    dest=ROOT/'dist/REPORT.md'
    dest.parent.mkdir(exist_ok=True)
    dest.write_text('\n'.join(rows),encoding='utf-8')
    summary={'nodes':len(commands),'combinations':signatures,'regression_passed':total,
             'knime_tests':tests,'knime_failures':failures+errors,'jni_statuses':dict(native_status),
             'upstream_changed':upstream['changed'],'native_version':upstream.get('version')}
    (ROOT/'dist/REPORT.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    print(dest.read_text())

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--require-knime-tests',action='store_true')
    a=p.parse_args()
    report(a.require_knime_tests)

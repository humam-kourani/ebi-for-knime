#!/usr/bin/env python3
"""Collect and verify the actual Tycho-built p2 installer and native resources."""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def collect(commit: str):
    site = ROOT/'update-site/target/knime-ebi-update-site.zip'
    if not site.is_file(): raise RuntimeError('Tycho p2 ZIP not found; run mvn clean verify')
    with zipfile.ZipFile(site) as archive:
        names = set(archive.namelist())
        if not ({'content.jar','content.xml','content.xml.xz'} & names):
            raise RuntimeError('Missing p2 content metadata')
        if not ({'artifacts.jar','artifacts.xml','artifacts.xml.xz'} & names):
            raise RuntimeError('Missing p2 artifacts metadata')
        plugins = [n for n in names if re.fullmatch(r'plugins/org\.ryoo\.knimeEbi_[^/]+\.jar',n)]
        features = [n for n in names if re.fullmatch(r'features/org\.ryoo\.knimeEbi\.feature_[^/]+\.jar',n)]
        if len(plugins)!=1 or len(features)!=1:
            raise RuntimeError('Expected exactly one compatible Ebi plug-in and feature')
        plugin = archive.read(plugins[0])
        feature = archive.read(features[0])
    license_bytes = (ROOT/'LICENSE').read_bytes()
    if b'GNU AFFERO GENERAL PUBLIC LICENSE' not in license_bytes[:512]:
        raise RuntimeError('The root LICENSE must contain the supplied AGPL v3 text')
    with zipfile.ZipFile(io.BytesIO(feature)) as packaged_feature:
        if 'LICENSE' not in packaged_feature.namelist() or packaged_feature.read('LICENSE') != license_bytes:
            raise RuntimeError('Feature jar missing matching AGPL LICENSE; check feature/build.properties')
        if 'feature.xml' not in packaged_feature.namelist() or b'GNU AFFERO GENERAL PUBLIC LICENSE' not in packaged_feature.read('feature.xml'):
            raise RuntimeError('Feature installer metadata is missing AGPL license text')
    lock = json.loads((ROOT/'native-artifacts.json').read_text())
    with zipfile.ZipFile(io.BytesIO(plugin)) as bundle:
        names = set(bundle.namelist())
        for required in ['META-INF/MANIFEST.MF','plugin.xml','knimeebi.jar','icons/ebi.png','LICENSE',
                         'lib/native-artifacts.json','lib/LICENSE-Ebi-MIT.txt','lib/NOTICE-Ebi.txt']:
            if required not in names: raise RuntimeError('Missing packaged resource '+required)
        if bundle.read('LICENSE') != license_bytes:
            raise RuntimeError('Plug-in jar LICENSE differs from the root AGPL v3 file')
        if json.loads(bundle.read('lib/native-artifacts.json')) != lock:
            raise RuntimeError('Packaged native provenance differs from tracked lock')
        for entry in lock['artifacts']:
            name = entry['path'].removeprefix('extension/')
            if name not in names: raise RuntimeError('Missing native '+name)
            raw = bundle.read(name)
            if len(raw)!=entry['size'] or hashlib.sha256(raw).hexdigest()!=entry['sha256']:
                raise RuntimeError('Native file failed checksum inside p2: '+name)
        with zipfile.ZipFile(io.BytesIO(bundle.read('knimeebi.jar'))) as classes:
            required = {'org/ryoo/knimeEbi/scaffolder/commands.xml','org/ryoo/knimeEbi/node/default.png'}
            if not required.issubset(set(classes.namelist())):
                raise RuntimeError('Generated command catalogue/icons not packaged')
    dist = ROOT/'dist'
    dist.mkdir(exist_ok=True)
    shutil.copy2(site,dist/site.name)
    shutil.copy2(ROOT/'LICENSE',dist/'LICENSE')
    (dist/Path(plugins[0]).name).write_bytes(plugin)
    shutil.copy2(ROOT/'native-artifacts.json',dist/'native-artifacts.json')
    (dist/'INSTALL.txt').write_text('''Ebi for KNIME (KNIME 5.12 / PM4KNIME 3.0)

1. Install PM4KNIME in KNIME Analytics Platform 5.12.
2. Help > Install New Software > Add > Archive > knime-ebi-update-site.zip.
3. Select Ebi Process Mining for KNIME, install, and restart KNIME.

This installer includes the Ebi Windows/Linux x86-64 native JNI libraries.
Other native platforms are not supported. Read native-artifacts.json for exact
upstream binary provenance. Do not install the plug-in JAR by itself.

''',encoding='utf-8')
    (dist/'build-provenance.json').write_text(json.dumps({'commit_before_release':commit,
        'native_version':lock.get('version'),'knime':'5.12.0','java':'21',
        'pm4knime':'3.0.0.202610271214','native_compilation':False},indent=2)+'\n')
    digest = []
    for path in sorted(dist.iterdir()):
        if path.is_file() and path.name!='SHA256SUMS.txt':
            digest.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name)
    (dist/'SHA256SUMS.txt').write_text('\n'.join(digest)+'\n')
    print('Verified and collected installable update site:',dist/site.name)

if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--commit',default='local')
    args=p.parse_args()
    collect(args.commit)

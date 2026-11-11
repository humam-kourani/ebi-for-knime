#!/usr/bin/env python3
"""Keep the Tycho reactor, feature, and OSGi bundle versions in sync.

If current version has no git tag, release that version. Otherwise increment
patch; never rewrite historical tags or reuse an existing release version.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MODULES = ['pom.xml', 'extension/pom.xml', 'integration-tests/pom.xml',
           'feature/pom.xml', 'update-site/pom.xml']

def current():
    m = re.search(r'<version>(\d+\.\d+\.\d+)-SNAPSHOT</version>', (ROOT/'pom.xml').read_text())
    if not m: raise ValueError('Expected a single x.y.z-SNAPSHOT parent version')
    return m.group(1)

def tags():
    cmd = subprocess.run(['git', 'tag', '-l', 'v*'], cwd=ROOT, capture_output=True, text=True)
    return set(cmd.stdout.splitlines()) if cmd.returncode == 0 else set()

def change(old: str, new: str):
    for rel in MODULES:
        path = ROOT / rel
        text = path.read_text()
        needle = old+'-SNAPSHOT'
        if needle not in text: raise ValueError('Version missing from '+rel)
        path.write_text(text.replace(needle, new+'-SNAPSHOT'))
    for rel in ['extension/META-INF/MANIFEST.MF', 'feature/feature.xml']:
        path = ROOT / rel
        text = path.read_text()
        needle = old+'.qualifier'
        if needle not in text: raise ValueError('Version missing from '+rel)
        path.write_text(text.replace(needle, new+'.qualifier'))

def prepare():
    old = current()
    existing = tags()
    new = old
    while 'v'+new in existing:
        major, minor, patch = [int(part) for part in new.split('.')]
        new = f'{major}.{minor}.{patch+1}'
    if new != old:
        change(old, new)
    print(new)
    return new

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['current', 'prepare'])
    a = p.parse_args()
    print(current()) if a.command == 'current' else prepare()

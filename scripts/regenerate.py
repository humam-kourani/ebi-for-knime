#!/usr/bin/env python3
"""Compile and run the real JDK-only generator without Eclipse or Maven."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--ecj', type=Path)
p.add_argument('--check', action='store_true')
p.add_argument('--capture', action='store_true')
a = p.parse_args()
if a.check and a.capture:
    p.error('--check and --capture are separate actions')
r = Path(__file__).resolve().parents[1]
if not a.capture and not a.check:
    # Idempotence: start from the tracked plugin template.
    shutil.copyfile(r / 'extension/plugin.template.xml', r / 'extension/plugin.xml')
s = r / 'extension/src'
if a.ecj:
    compiler = ['java', '-jar', str(a.ecj.resolve()), '-21']
elif shutil.which('javac'):
    compiler = ['javac', '--release', '21']
else:
    p.error('JDK 21 is required, or supply --ecj /absolute/path/ecj.jar')
with tempfile.TemporaryDirectory(prefix='ebi-generation-', dir=r) as temp:
    classes = Path(temp) / 'classes'
    sources = sorted((s / 'org/ryoo/knimeEbi/scaffolder').glob('*.java'))
    sources += sorted((s / 'org/processmining/ebi').glob('*.java'))
    sources += sorted((s / 'org/ryoo/knimeEbi/entryPoint').glob('*.java'))
    subprocess.run(compiler + ['-d', str(classes)] + [str(f) for f in sources], check=True)
    import os
    cp = os.pathsep.join([str(classes), str(r / 'extension')])
    invocation = ['java', '-Djava.io.tmpdir=' + str(Path(temp).resolve()), '-cp', cp,
                  'org.ryoo.knimeEbi.entryPoint.Main', '--root', str(r)]
    if a.check: invocation.append('--check')
    if a.capture: invocation.append('--capture')
    subprocess.run(invocation, check=True, cwd=r)

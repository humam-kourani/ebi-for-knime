#!/usr/bin/env python3
"""Exercise real Equinox upgrades with persisted caches and production JNI resource loading.

This is an OSGi/extension-registry regression, not a KNIME UI or p2 installation test.
Requires JDK 21, or --ecj for JRE-only environments. Missing pinned test dependencies
are downloaded from Maven Central and always verified before execution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

DEPENDENCIES = (
    ('org.eclipse.osgi', '3.23.0', '1ac113541a19f0c72c0421fb24058defca7e3c6f282e5ee73f14d2768a9ae653'),
    ('org.eclipse.equinox.common', '3.20.0', '617c5d7e759276b7e9ed363c56a6714b7f21d4a812d533fcb90e48723cc4c001'),
    ('org.eclipse.equinox.registry', '3.12.300', 'e2145418ff639b44ff50e83b66848f40ae38c869db6b8f95044bbb5d0d652722'),
)


def run(command, **kwargs):
    subprocess.run([str(part) for part in command], check=True, timeout=120, **kwargs)


def dependencies(directory):
    directory.mkdir(parents=True, exist_ok=True)
    paths = []
    for artifact, version, expected in DEPENDENCIES:
        filename = f'{artifact}-{version}.jar'
        path = directory / filename
        if not path.is_file() and (directory / f'{artifact}.jar').is_file():
            path = directory / f'{artifact}.jar'
        if not path.is_file():
            url = f'https://repo.maven.apache.org/maven2/org/eclipse/platform/{artifact}/{version}/{filename}'
            with urllib.request.urlopen(url, timeout=45) as response:
                content = response.read()
            if hashlib.sha256(content).hexdigest() != expected:
                raise ValueError(f'Unexpected SHA-256 for {filename}')
            path.write_bytes(content)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Unexpected SHA-256 for {path}; use the exact pinned dependency')
        paths.append(path.resolve())
    return paths


def fixture(path, classes, version, marker):
    manifest = ('Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n'
                'Bundle-SymbolicName: test.ebi.upgrade;singleton:=true\n'
                f'Bundle-Version: {version}\nBundle-ClassPath: .\n'
                'Export-Package: org.processmining.ebi\nImport-Package: org.osgi.framework\n\n')
    nodes = f'<node id="existing-{marker}"/>' + ('<node id="new-node"/>' if marker == 'v2' else '')
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('META-INF/MANIFEST.MF', manifest)
        jar.writestr('plugin.xml', '<?xml version="1.0"?><plugin>'
                     '<extension-point id="nodes" name="Upgrade test nodes"/>'
                     f'<extension point="test.ebi.upgrade.nodes">{nodes}</extension></plugin>')
        jar.writestr('lib/shared-marker.txt', marker)
        if marker == 'v2':
            jar.writestr('lib/new-only-marker.txt', marker)
        for bytecode in sorted(classes.rglob('*.class')):
            jar.write(bytecode, bytecode.relative_to(classes).as_posix())


def assert_warm_cache(storage):
    for folder, pattern in [('org.eclipse.osgi', 'framework.info.*'),
                            ('org.eclipse.core.runtime', '.table.*')]:
        if not any(path.stat().st_size for path in (storage / folder).glob(pattern)):
            raise AssertionError(f'The previous JVM did not persist {folder} cache files')


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ecj', type=Path)
    parser.add_argument('--equinox-dir', type=Path, default=root / '.ci/upgrade-deps')
    parser.add_argument('--report', type=Path, default=root / 'test-results/upgrade.json')
    options = parser.parse_args()
    if options.ecj:
        compiler = ['java', '-jar', str(options.ecj.resolve()), '-21']
    elif shutil.which('javac'):
        compiler = ['javac', '--release', '21']
    else:
        parser.error('Install JDK 21 or supply --ecj /absolute/path/ecj.jar')
    jars = dependencies(options.equinox_dir)
    with tempfile.TemporaryDirectory(prefix='ebi-upgrade-tests-', dir=root) as directory:
        work = Path(directory).resolve()
        harness = work / 'harness'
        run(compiler + ['-cp', jars[0], '-d', harness, root / 'tests/upgrade/UpgradeProbe.java'])
        sources = sorted((root / 'extension/src/org/processmining/ebi').glob('*.java'))
        sources += sorted((root / 'extension/src/org/ebitools').glob('*.java'))
        access = (root / 'tests/upgrade/LoaderAccess.java').read_text(encoding='utf-8')
        for marker, version in [('v1', '1.0.0'), ('v2', '1.1.0')]:
            classes = work / marker
            classes.mkdir()
            source = classes / 'LoaderAccess.java'
            source.write_text(access.replace('CODE_MARKER', marker), encoding='utf-8')
            run(compiler + ['-d', classes, *sources, source])
            fixture(work / f'{marker}.jar', classes, version, marker)
        with zipfile.ZipFile(work / 'dependent.jar', 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n'
                         'Bundle-SymbolicName: test.ebi.dependent\nBundle-Version: 1.0.0\n'
                         'Import-Package: org.processmining.ebi\n\n')
        for phase in ['seed', 'upgrade', 'restart']:
            if phase != 'seed':
                assert_warm_cache(work / 'storage')
            # Keep the same storage and fixture files across all three separate JVMs.
            run(['java', '-cp', os.pathsep.join([str(jars[0]), str(harness)]),
                 'UpgradeProbe', phase, work, *jars[1:]], cwd=work)
    options.report.parent.mkdir(parents=True, exist_ok=True)
    options.report.write_text(json.dumps({'equinox_upgrade': {'passed': 3, 'failed': 0},
        'scope': 'Real Equinox, persistent extension registry and production native resource lookup; no KNIME UI/p2'},
        indent=2) + '\n', encoding='utf-8')
    print('Retained-cache Equinox upgrade tests passed: 3 JVM phases')


if __name__ == '__main__':
    main()

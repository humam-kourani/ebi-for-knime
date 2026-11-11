#!/usr/bin/env python3
"""Run production Java regression suites. Requires JDK 21, or --ecj for a JRE.

No mocked KNIME classes are used. KNIME/PDE integration is a separate build gate.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ecj', type=Path, help='Eclipse compiler JAR for JRE-only environments')
    parser.add_argument('--report', type=Path, default=Path('test-results/regression.json'))
    parser.add_argument('--core-only', action='store_true')
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    src = root / 'extension/src'
    if options.ecj:
        compiler = ['java', '-jar', str(options.ecj.resolve()), '-21']
    elif shutil.which('javac'):
        compiler = ['javac', '--release', '21']
    else:
        parser.error('Install JDK 21 or supply --ecj /absolute/path/ecj.jar')
    report = {}
    with tempfile.TemporaryDirectory(prefix='ebi-core-tests-', dir=root) as temp:
        classes = Path(temp) / 'classes'
        sources = sorted((src / 'org/ryoo/knimeEbi/scaffolder').glob('*.java'))
        sources += sorted((src / 'org/processmining/ebi').glob('*.java'))
        sources += sorted((root / 'tests/core').rglob('*.java'))
        run(compiler + ['-d', str(classes)] + sources, cwd=root)
        result = run(['java', '-Djava.io.tmpdir=' + str(Path(temp).resolve()), '-cp', str(classes), 'org.ryoo.knimeEbi.scaffolder.GeneratorRegressionTest', root],
                     cwd=root, capture_output=True, text=True, timeout=90)
        markers = [line.split('=', 1)[1] for line in result.stdout.splitlines() if line.startswith('CORE_TESTS_PASSED=')]
        if len(markers) != 1:
            raise RuntimeError('Missing core test result marker')
        report['core'] = {'passed': int(markers[0]), 'failed': 0}
    if not options.core_only:
        shared = ['--ecj', str(options.ecj.resolve())] if options.ecj else []
        native_report = root / 'test-results/native-unit.json'
        run([sys.executable, root / 'tests/run_native_tests.py', *shared, '--report', native_report], cwd=root)
        report['native_unit'] = json.loads(native_report.read_text())['unit']
        port_runner = root / 'tests/run_port_tests.py'
        if port_runner.is_file():
            ports = run([sys.executable, port_runner, *shared], cwd=root, capture_output=True, text=True, timeout=90)
            lines = [line for line in ports.stdout.splitlines() if line.startswith('Port/codec regression tests passed: ')]
            if len(lines) != 1:
                raise RuntimeError('Missing port regression result marker')
            report['ports_codecs'] = {'passed': int(lines[0].rsplit(': ', 1)[1]), 'failed': 0}
        else:
            raise RuntimeError('Required port regression runner is missing')
        migration_suite = unittest.defaultTestLoader.discover(
            str(root / 'tests'), pattern='test_workflow_migration.py')
        migration_result = unittest.TextTestRunner(verbosity=1).run(migration_suite)
        if migration_result.testsRun == 0 or not migration_result.wasSuccessful():
            raise RuntimeError('Workflow migration regressions failed')
        report['workflow_migration'] = {'passed': migration_result.testsRun, 'failed': 0}
        upstream_suite = unittest.defaultTestLoader.discover(
            str(root / 'tests'), pattern='test_upstream_releases.py')
        upstream_result = unittest.TextTestRunner(verbosity=1).run(upstream_suite)
        if upstream_result.testsRun == 0 or not upstream_result.wasSuccessful():
            raise RuntimeError('GitHub Releases JNI validation regressions failed')
        report['github_releases'] = {'passed': upstream_result.testsRun, 'failed': 0}
    path = options.report if options.report.is_absolute() else root / options.report
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as failure:
        if failure.stdout:
            print(failure.stdout)
        if failure.stderr:
            print(failure.stderr, file=sys.stderr)
        raise

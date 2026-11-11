#!/usr/bin/env python3
"""Compile and exercise production codecs/contracts without KNIME substitutes."""
import argparse
import pathlib
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ecj', type=pathlib.Path, help='Optional Eclipse compiler jar when javac is unavailable')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    src = root / 'extension/src/org/ryoo/knimeEbi'
    sources = [src / p for p in [
        'port/EbiDocument.java', 'util/DfgCodec.java', 'util/XmlText.java', 'util/PtmlCompatibilityUtil.java',
        'util/EbiPortContract.java', 'util/EbiScalarResult.java', 'util/PnmlWriter.java', 'util/EbiNativeOutputCompatibility.java', 'scaffolder/EbiCommandMetadata.java',
        'scaffolder/EbiCommandMetadataParameter.java', 'scaffolder/EbiCommandVariant.java',
        'scaffolder/EbiTableOutputType.java']]
    sources.append(root / 'tests/ports/org/ryoo/knimeEbi/util/PortRegressionTest.java')
    if args.ecj:
        compiler = ['java', '-jar', str(args.ecj.resolve()), '-21']
    elif shutil.which('javac'):
        compiler = ['javac', '--release', '21']
    else:
        parser.error('JDK 21 javac or --ecj is required; tests are not skipped.')
    with tempfile.TemporaryDirectory(prefix='knime-ebi-ports-') as tmp:
        subprocess.run(compiler + ['-encoding', 'UTF-8', '-d', tmp] + [str(p) for p in sources], check=True)
        subprocess.run(['java', '-cp', tmp, 'org.ryoo.knimeEbi.util.PortRegressionTest', str(root / 'tests/fixtures')], check=True)


if __name__ == '__main__':
    main()

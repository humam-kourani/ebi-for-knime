#!/usr/bin/env python3
"""Restore locked JNI artifacts, or inspect Ebi's official latest JNI builds.

Native binaries are never committed to Git. A *pinned* build requires either
an existing release's update-site ZIP or byte-identical official downloads.
A *latest* build creates a candidate lock; CI publishes only after its gates.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'native-artifacts.json'
EMBEDDED = ROOT / 'extension/lib/native-artifacts.json'
JNI = b'Java_org_processmining_ebi_CallEbi_call_1ebi_1internal'
MAX_SIZE = 120 * 1024 * 1024


def fatal(message: str):
    raise RuntimeError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def artifact_path(entry: dict) -> Path:
    result = (ROOT / entry['path']).resolve()
    if not result.is_relative_to(ROOT / 'extension/lib'):
        fatal('Unsafe binary destination: ' + str(result))
    return result


def version_of(data: bytes) -> str | None:
    # Best effort only: a native binary need not embed a visible version.
    choices = re.findall(rb'(?<!\d)0\.\d{1,3}\.\d{1,4}(?:-(?:alpha|beta|rc)\.\d+)?', data)
    hits = [item.decode('ascii') for item in choices]
    return Counter(hits).most_common(1)[0][0] if hits else None


def validate(data: bytes, platform: str):
    if len(data) < 128 or len(data) > MAX_SIZE or JNI not in data:
        fatal(f'{platform}: missing JNI symbol or impossible file size')
    if platform == 'linux-x86_64':
        if data[:4] != b'\x7fELF' or data[4:6] != b'\x02\x01':
            fatal('Linux native artifact is not a 64-bit little-endian ELF')
        e_type, e_machine = struct.unpack_from('<HH', data, 16)
        if e_type != 3 or e_machine != 62:
            fatal('Linux native artifact is not an x86-64 shared ELF object')
        phoff = struct.unpack_from('<Q', data, 32)[0]
        phentsize, phnum = struct.unpack_from('<HH', data, 54)
        if phnum < 1 or phnum > 4096 or phentsize < 56 or phoff + phentsize * phnum > len(data):
            fatal('Invalid ELF program header table')
        # A PIE executable can be ET_DYN too; PT_INTERP makes it unsuitable
        # as System.load JNI library. A broken Ebi release had this issue.
        for i in range(phnum):
            if struct.unpack_from('<I', data, phoff + phentsize * i)[0] == 3:
                fatal('Linux artifact has PT_INTERP (a PIE executable, not JNI libebi.so)')
    elif platform == 'windows-x86_64':
        if data[:2] != b'MZ':
            fatal('Windows native artifact is not a PE DLL')
        offset = struct.unpack_from('<I', data, 60)[0]
        if offset + 24 >= len(data) or data[offset:offset+4] != b'PE\x00\x00':
            fatal('Windows native artifact has invalid PE header')
        machine = struct.unpack_from('<H', data, offset+4)[0]
        characteristics = struct.unpack_from('<H', data, offset+22)[0]
        if machine != 0x8664 or not characteristics & 0x2000:
            fatal('Windows native artifact is not an x86-64 DLL')
    else:
        fatal('Unsupported platform: ' + platform)


def download(url: str) -> bytes:
    url_parts = urllib.parse.urlsplit(url)
    if url_parts.scheme != 'https' or url_parts.hostname != 'git.rwth-aachen.de':
        fatal('Unexpected upstream download host: ' + url)
    request = urllib.request.Request(url, headers={'User-Agent': 'ebi-for-knime-release-check/1.0'})
    with urllib.request.urlopen(request, timeout=150) as response:
        if response.headers.get('Content-Length') and int(response.headers['Content-Length']) > MAX_SIZE:
            fatal('Upstream binary is too large')
        data = response.read(MAX_SIZE + 1)
    if len(data) > MAX_SIZE:
        fatal('Upstream binary exceeded size limit')
    return data


def from_site(path: Path, entry: dict) -> bytes:
    # Extract the known-working embedded JNI bytes from a previously published
    # installer, NOT an arbitrary ZIP path from upstream.
    with zipfile.ZipFile(path) as site:
        plugins = [s for s in site.namelist() if re.fullmatch(r'plugins/org\.ryoo\.knimeEbi_[^/]+\.jar', s)]
        if len(plugins) != 1:
            fatal('Seed update site must contain exactly one original Ebi OSGi plug-in')
        with zipfile.ZipFile(io.BytesIO(site.read(plugins[0]))) as plugin:
            name = entry['path'].split('extension/', 1)[1]
            return plugin.read(name)


def atomic_write(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix='.ebi-', dir=path.parent, delete=False) as stream:
        temp = Path(stream.name)
        stream.write(data)
    try:
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def run(mode: str, site: Path | None, report: Path):
    manifest = json.loads(LOCK.read_text(encoding='utf-8'))
    if len(manifest.get('artifacts', [])) != 2:
        fatal('Exactly two pinned Ebi JNI artifacts (Linux and Windows) are required')
    prepared = []
    for entry in manifest['artifacts']:
        platform = entry['platform']
        binary = from_site(site, entry) if site else download(entry['url'])
        validate(binary, platform)
        before = entry['sha256']
        after = sha(binary)
        if mode == 'pinned' and (len(binary) != entry['size'] or before != after):
            fatal(f'{platform}: upstream nightly has changed from pinned 1.1.1 bytes; '
                  'provide --site /path/to/previous-release-update-site.zip, '
                  'or request a vetted upstream update instead')
        prepared.append((entry, binary, after))
    changed = any(entry['sha256'] != digest for entry, _, digest in prepared)
    discovered = [version_of(data) for _, data, _ in prepared]
    if mode == 'latest' and (not all(discovered) or discovered[0] != discovered[1]):
        fatal('Cannot establish the same embedded Ebi version for both JNI artifacts: ' + str(discovered))
    # Commit all files only after validating BOTH assets (fail closed).
    for entry, data, digest in prepared:
        atomic_write(artifact_path(entry), data)
        if mode == 'latest' and changed:
            entry['sha256'] = digest
            entry['size'] = len(data)
            entry['embedded_version'] = version_of(data)
            entry['runtime_verified'] = False  # set by CI tests, not by downloader
    if mode == 'latest' and changed:
        manifest['retrieved_utc'] = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        manifest['version'] = discovered[0]
        manifest['source_commit'] = None
        manifest['pin_type'] = 'Downloaded and SHA-256 locked from official upstream JNI nightlies; source commit not proven'
        # Do not retain historic old binary digests and rejected-assets notes as current facts.
        manifest.pop('rejected_assets', None)
        manifest.pop('original_supplied_windows_sha256', None)
        atomic_write(LOCK, (json.dumps(manifest, indent=2) + '\n').encode('utf-8'))
    atomic_write(EMBEDDED, LOCK.read_bytes())
    summary = {
        'mode': mode, 'changed': changed, 'version': manifest.get('version'),
        'artifacts': [{'platform': e['platform'], 'sha256': digest, 'bytes': len(data)}
                      for e, data, digest in prepared],
        'source': 'Ebi upstream JNI latest' if mode == 'latest' else ('previous release update site' if site else 'upstream locked hash'),
    }
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(f'Upstream {mode}: changed={changed}, version={summary["version"]}')
    return summary


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['pinned', 'latest'])
    p.add_argument('--site', type=Path, help='previous release update-site ZIP for pinned restore')
    p.add_argument('--report', type=Path, default=ROOT/'.ci/upstream.json')
    a = p.parse_args()
    if a.site and a.mode != 'pinned': p.error('--site is only valid with pinned')
    if a.site and not a.site.is_file(): p.error('Seed site does not exist')
    run(a.mode, a.site, a.report)


if __name__ == '__main__':
    main()

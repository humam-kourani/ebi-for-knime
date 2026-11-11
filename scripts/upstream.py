#!/usr/bin/env python3
"""Refresh native Ebi JNI from official GitHub releases, never RWTH nightlies.

The known-working baseline is recovered from an earlier *own* GitHub release
installer if upstream publishes no compatible JNI pair. Native binaries remain
excluded from Git; upstream candidates must pass binary and version checks.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import io
import json
import os
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
UPSTREAM = 'BPM-Research-Group/Ebi'
RELEASES_URL = f'https://github.com/{UPSTREAM}/releases'
API = 'https://api.github.com'
JNI = b'Java_org_processmining_ebi_CallEbi_call_1ebi_1internal'
MAX_SIZE = 120 * 1024 * 1024
ASSETS = {'linux-x86_64': 'libebi.so', 'windows-x86_64': 'ebi.dll'}


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
    # Best effort: version strings can be absent in the native library.
    choices = re.findall(rb'(?<!\d)0\.\d{1,3}\.\d{1,4}(?:-(?:alpha|beta|rc)\.\d+)?', data)
    hits = [item.decode('ascii') for item in choices]
    return Counter(hits).most_common(1)[0][0] if hits else None


def version_triplet(text: str | None) -> tuple[int, int, int] | None:
    match = re.match(r'^\s*v?(\d+)\.(\d+)\.(\d+)', text or '')
    return tuple(int(v) for v in match.groups()) if match else None


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
        # ET_DYN is also used by PIE *executables*. PT_INTERP rejects those.
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


def request_bytes(url: str, limit: int) -> bytes:
    headers = {'User-Agent': 'ebi-for-knime-release-sync/2.0',
               'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': '2022-11-28'}
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=150) as response:
        if response.headers.get('Content-Length') and int(response.headers['Content-Length']) > limit:
            fatal('GitHub download exceeded size limit: ' + url)
        data = response.read(limit + 1)
    if len(data) > limit:
        fatal('GitHub download exceeded size limit: ' + url)
    return data


def api_releases(repo: str) -> list[dict]:
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        fatal('Invalid GitHub repository identifier: ' + repo)
    url = f'{API}/repos/{repo}/releases?per_page=30'
    result = json.loads(request_bytes(url, 5 * 1024 * 1024).decode('utf-8'))
    if not isinstance(result, list):
        fatal('GitHub Releases API did not return a release list for ' + repo)
    return result


def asset_url(asset: dict, repo: str) -> str:
    url = asset.get('browser_download_url', '')
    parts = urllib.parse.urlsplit(url)
    prefix = f'/{repo}/releases/download/'
    if parts.scheme != 'https' or parts.hostname != 'github.com' or not parts.path.startswith(prefix):
        fatal('Refusing a non-GitHub release-asset URL: ' + url)
    return url


def download_asset(asset: dict, repo: str) -> bytes:
    return request_bytes(asset_url(asset, repo), MAX_SIZE)


def latest_compatible_release(releases: list[dict], current: str, fetch=None):
    """Find the newest *usable* release with both raw JNI assets; never downgrade.

    Generic CLI ELF/EXE assets are not interchangeable with shared JNI libraries.
    Refusing bad releases is intentional, not a reason to replace working binaries.
    """
    if fetch is None:
        fetch = download_asset
    warnings = []
    for release in releases:
        if release.get('draft'):
            continue
        tag = release.get('tag_name', '<untagged>')
        assets = {a.get('name'): a for a in release.get('assets', [])
                  if isinstance(a, dict)}
        if not all(name in assets for name in ASSETS.values()):
            continue
        prepared = []
        try:
            for platform, name in ASSETS.items():
                obj = assets[name]
                data = fetch(obj, UPSTREAM)
                validate(data, platform)
                prepared.append((platform, data, asset_url(obj, UPSTREAM)))
            versions = [version_of(data) for _, data, _ in prepared]
            if not all(versions) or len(set(versions)) != 1:
                fatal(f'Cannot prove matching Ebi versions for the two JNI assets: {versions}')
            before, after = version_triplet(current), version_triplet(versions[0])
            if before and after and after < before:
                warnings.append(f'{tag}: skipping older native version {versions[0]} (locked {current})')
                continue
            return release, prepared, versions[0], warnings
        except (RuntimeError, OSError, ValueError, KeyError, struct.error) as error:
            warnings.append(f'{tag}: skipped unusable JNI pair: {error}')
    return None, [], None, warnings


def from_site(path: Path, entry: dict) -> bytes:
    # Extract the exact pinned JNI bytes from a previously released Ebi update site.
    with zipfile.ZipFile(path) as site:
        plugins = [s for s in site.namelist()
                   if re.fullmatch(r'plugins/org\.ryoo\.knimeEbi_[^/]+\.jar', s)]
        if len(plugins) != 1:
            fatal('Seed update site must contain exactly one original Ebi OSGi plug-in')
        with zipfile.ZipFile(io.BytesIO(site.read(plugins[0]))) as plugin:
            return plugin.read(entry['path'].split('extension/', 1)[1])


def previous_release_site(manifest: dict) -> tuple[Path, str]:
    """Recover the locked JNI from our own GitHub release; no upstream fallback."""
    repo = os.environ.get('GITHUB_REPOSITORY', '')
    if not repo:
        fatal('No --site supplied and GITHUB_REPOSITORY is unset. '
              'Use --site with a previous Ebi-for-KNIME release ZIP.')
    for release in api_releases(repo):
        if release.get('draft'):
            continue
        for asset in release.get('assets', []):
            if asset.get('name') != 'knime-ebi-update-site.zip':
                continue
            path = ROOT / '.ci/previous-knime-ebi-update-site.zip'
            path.parent.mkdir(parents=True, exist_ok=True)
            payload = download_asset(asset, repo)
            with tempfile.NamedTemporaryFile(prefix='.site-', dir=path.parent, delete=False) as stream:
                temp = Path(stream.name)
                stream.write(payload)
            try:
                temp.replace(path)
            finally:
                temp.unlink(missing_ok=True)
            try:
                # Do not confuse a different release's binaries with this lock.
                for entry in manifest['artifacts']:
                    data = from_site(path, entry)
                    if len(data) != entry['size'] or sha(data) != entry['sha256']:
                        raise ValueError('SHA-256 mismatch')
                return path, release.get('tag_name', '<unknown>')
            except (KeyError, ValueError, RuntimeError, zipfile.BadZipFile):
                path.unlink(missing_ok=True)
    fatal('No previous Ebi-for-KNIME GitHub Release has the native binaries '
          'matching native-artifacts.json. Provide --site with your working '
          'update-site ZIP and publish it as a GitHub Release asset.')


def atomic_write(path: Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix='.ebi-', dir=path.parent, delete=False) as stream:
        temp = Path(stream.name)
        stream.write(data)
    try:
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def pinned_binaries(manifest: dict, site: Path | None):
    if site is None:
        # A new GitHub-sourced lock can be directly reconstructed by asset URL.
        urls = [e.get('url') for e in manifest['artifacts']]
        if all(urls):
            prepared = []
            for entry in manifest['artifacts']:
                obj = {'browser_download_url': entry['url']}
                data = download_asset(obj, UPSTREAM)
                validate(data, entry['platform'])
                if len(data) != entry['size'] or sha(data) != entry['sha256']:
                    fatal('Pinned GitHub release asset changed: ' + entry['platform'])
                prepared.append((entry, data, sha(data)))
            return prepared, 'locked upstream GitHub Release assets'
        site, seed_tag = previous_release_site(manifest)
        source = 'own GitHub Release ' + seed_tag
    else:
        source = 'provided previous KNIME update-site ZIP'
    prepared = []
    for entry in manifest['artifacts']:
        data = from_site(site, entry)
        validate(data, entry['platform'])
        if len(data) != entry['size'] or sha(data) != entry['sha256']:
            fatal('Pinned update-site binary differs from native-artifacts.json: ' + entry['platform'])
        prepared.append((entry, data, sha(data)))
    return prepared, source


def run(mode: str, site: Path | None, report: Path):
    manifest = json.loads(LOCK.read_text(encoding='utf-8'))
    entries = manifest.get('artifacts', [])
    if len(entries) != 2 or {e['platform'] for e in entries} != set(ASSETS):
        fatal('Exactly two pinned Ebi JNI artifacts (Linux and Windows) are required')
    prepared = []
    changed = False
    warnings = []
    release_tag = None
    if mode == 'latest':
        release, candidate, discovered_version, warnings = latest_compatible_release(
            api_releases(UPSTREAM), str(manifest.get('version', '')))
        if release is not None:
            release_tag = release['tag_name']
            paths = {platform: (data, url) for platform, data, url in candidate}
            for entry in entries:
                data, url = paths[entry['platform']]
                prepared.append((entry, data, sha(data)))
            changed = any(e['sha256'] != digest for e, _, digest in prepared)
            if changed:
                for entry, _, digest in prepared:
                    data, url = paths[entry['platform']]
                    entry.update(sha256=digest, size=len(data), url=url,
                                 embedded_version=discovered_version,
                                 source='Official BPM-Research-Group/Ebi GitHub Release JNI asset',
                                 runtime_verified=False)
                manifest['retrieved_utc'] = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
                manifest['version'] = discovered_version
                manifest['source_release_tag'] = release_tag
                manifest['source_release_url'] = f'{RELEASES_URL}/tag/{urllib.parse.quote(release_tag)}'
                manifest['pin_type'] = 'GitHub release tag + per-asset SHA-256; native JNI validation required'
                # target_commitish may be a branch, not a verified release commit.
                manifest['source_commit'] = None
                manifest.pop('rejected_assets', None)
                manifest.pop('original_supplied_windows_sha256', None)
        else:
            warnings.append('No compatible, validated Linux+Windows JNI pair in the GitHub Releases feed. Keeping the pinned native baseline.')
            prepared, source = pinned_binaries(manifest, site)
    else:
        prepared, source = pinned_binaries(manifest, site)
    # No writes until both artifacts have passed checks.
    for entry, data, digest in prepared:
        atomic_write(artifact_path(entry), data)
    if mode == 'latest' and changed:
        atomic_write(LOCK, (json.dumps(manifest, indent=2) + '\n').encode('utf-8'))
    atomic_write(EMBEDDED, LOCK.read_bytes())
    summary = {
        'mode': mode, 'changed': changed, 'version': manifest.get('version'),
        'source': (f'Ebi GitHub Release {release_tag}' if release_tag else
                   (source if mode == 'pinned' else 'pinned baseline; Ebi GitHub Releases checked')),
        'release_tag': release_tag,
        'releases_url': RELEASES_URL,
        'warnings': warnings,
        'artifacts': [{'platform': e['platform'], 'sha256': digest, 'bytes': len(data)}
                      for e, data, digest in prepared],
    }
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    for warning in warnings:
        print('NOTICE:', warning)
    print(f'Ebi GitHub {mode}: changed={changed}, version={summary["version"]}, source={summary["source"]}')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['pinned', 'latest'])
    parser.add_argument('--site', type=Path, help='previous release update-site ZIP (pinned or fallback)')
    parser.add_argument('--report', type=Path, default=ROOT/'.ci/upstream.json')
    args = parser.parse_args()
    if args.site and not args.site.is_file():
        parser.error('Seed site does not exist')
    run(args.mode, args.site, args.report)


if __name__ == '__main__':
    main()

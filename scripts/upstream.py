#!/usr/bin/env python3
"""Refresh native Ebi JNI from GitHub releases (native pair or bundled JAR).

A local --jar input supports testing an upstream JAR before it is published.
Its native binaries are validated and stored in our existing extension layout;
the upstream Java source is checked for ABI compatibility but never executed.

The known-working baseline is recovered from an earlier *own* GitHub release
installer only if no newer upstream release exists (or in explicit pinned mode).
A newer release with missing or invalid JNI artifacts is a build failure.
Native binaries remain excluded from Git; candidates must pass validation.
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
JNI_NEW = b'Java_org_ebitools_Ebi_callEbi'
MAX_SIZE = 120 * 1024 * 1024
MAX_JAR_SIZE = 150 * 1024 * 1024
JAR_SOURCE = 'src/surfaces/java/org/ebitools/Ebi.java'
RUNTIME_CONSTANTS = Path('extension/src/org/processmining/ebi/EbiNativeBuild.java')
ASSETS = {'linux-x86_64': 'libebi.so', 'windows-x86_64': 'ebi.dll'}


def fatal(message: str):
    raise RuntimeError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def artifact_path(entry: dict) -> Path:
    base = ROOT.resolve() / 'extension' / 'lib'
    result = (ROOT / entry['path']).resolve()
    if not result.is_relative_to(base):
        fatal('Unsafe binary destination: ' + str(result))
    return result

def version_of(data: bytes) -> str | None:
    # Ebi embeds its own version in the generated LaTeX manual. Many other
    # dependency versions appear in Rust binaries; never guess by frequency
    # when an authoritative Ebi version marker is present.
    patterns = [
        rb'\\def\\version\{(0\.\d{1,3}\.\d{1,4}(?:-[A-Za-z0-9.-]+)?)\}',
        rb'Ebi version[: ]+(0\.\d{1,3}\.\d{1,4}(?:-[A-Za-z0-9.-]+)?)',
    ]
    for pattern in patterns:
        versions = set(re.findall(pattern, data))
        if len(versions) == 1:
            return versions.pop().decode('ascii')
        if len(versions) > 1:
            fatal(f'Conflicting embedded Ebi versions: {versions}')
    # Legacy binaries may lack a labelled marker; this is only a fallback.
    choices = re.findall(rb'(?<!\d)0\.\d{1,3}\.\d{1,4}(?:-(?:alpha|beta|rc)\.\d+)?', data)
    hits = [item.decode('ascii') for item in choices]
    return Counter(hits).most_common(1)[0][0] if hits else None


def version_triplet(text: str | None) -> tuple[int, int, int] | None:
    match = re.match(r'^\s*v?(\d+)\.(\d+)\.(\d+)', text or '')
    return tuple(int(v) for v in match.groups()) if match else None


def validate(data: bytes, platform: str, api: str = 'legacy'):
    symbol = JNI_NEW if api == 'ebitools' else JNI if api == 'legacy' else None
    if symbol is None:
        fatal(f'Unknown Ebi JNI ABI: {api}')
    if len(data) < 128 or len(data) > MAX_SIZE or symbol not in data:
        fatal(f'{platform}: missing JNI symbol or impossible file size ({api})')
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


def jar_artifacts(payload: bytes, url: str | None = None):
    """Validate the upstream JAR; extract only the exact two expected native entries.

    Its Java source is not compiled or loaded: our worker uses a minimal,
    audited binding for the single callEbi JNI function.
    """
    if not (100 < len(payload) <= MAX_JAR_SIZE):
        fatal('Ebi JAR has an impossible size')
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as jar:
            files = jar.infolist()
            wanted = {JAR_SOURCE, *ASSETS.values()}
            for name in wanted:
                matches = [item for item in files if item.filename == name]
                if len(matches) != 1:
                    fatal(f'Ebi JAR must contain exactly one {name}')
                if matches[0].file_size > (1024 * 1024 if name == JAR_SOURCE else MAX_SIZE):
                    fatal(f'Oversized Ebi JAR entry: {name}')
            source = jar.read(JAR_SOURCE).decode('utf-8')
            if not re.search(r'\bpackage\s+org\.ebitools\s*;', source) or not re.search(
                r'\bpublic\s+class\s+Ebi\b', source) or not re.search(
                r'\bpublic\s+static\s+native\s+String\s+callEbi\s*\(\s*'
                r'String\s+\w+\s*,\s*String\s+\w+\s*,\s*'
                r'String\s*\[\s*\]\s*\w+\s*\)\s*;', source):
                fatal('Ebi JAR has an incompatible org.ebitools.Ebi.callEbi JNI declaration')
            prepared = []
            for platform, name in ASSETS.items():
                data = jar.read(name)
                validate(data, platform, 'ebitools')
                prepared.append((platform, data, url))
    except (zipfile.BadZipFile, UnicodeError, EOFError) as error:
        raise RuntimeError(f'Invalid Ebi JAR archive: {error}') from error
    versions = [version_of(data) for _, data, _ in prepared]
    if not all(versions) or len(set(versions)) != 1:
        fatal(f'Cannot prove matching Linux/Windows Ebi JAR versions: {versions}')
    return prepared, versions[0]


def runtime_java(manifest: dict) -> bytes:
    api = manifest.get('jni_api', 'legacy')
    if api not in ('legacy', 'ebitools'):
        fatal('Unrecognized JNI ABI in native-artifacts.json: ' + str(api))
    entries = {item['platform']: item for item in manifest['artifacts']}
    if set(entries) != set(ASSETS):
        fatal('Expected exactly one lock entry per supported platform')
    version = manifest['version']
    linux = entries['linux-x86_64']['sha256']
    windows = entries['windows-x86_64']['sha256']
    if not re.fullmatch(r'[0-9A-Za-z.+-]+', version) or any(
            not re.fullmatch(r'[0-9a-f]{64}', v) for v in (linux, windows)):
        fatal('Invalid version or SHA-256 in Ebi native lock')
    return (f'''package org.processmining.ebi;

/** Generated by scripts/upstream.py from native-artifacts.json. Do not edit manually. */
final class EbiNativeBuild {{
    static final String VERSION = "{version}";
    static final String JNI_API = "{api}";
    static final String LINUX_SHA256 = "{linux}";
    static final String WINDOWS_SHA256 = "{windows}";
    private EbiNativeBuild() {{ }}
}}
''').encode('utf-8')


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
    url = asset_url(asset, repo)
    limit = MAX_JAR_SIZE if urllib.parse.urlsplit(url).path.lower().endswith('.jar') else MAX_SIZE
    return request_bytes(url, limit)


def latest_compatible_release(releases: list[dict], current: str, fetch=None,
                              current_tag: str | None = None,
                              java_source: list[str] | None = None):
    """Validate the newest upstream release, or return none if no update exists.

    An unusable NEW release is an error, never an invitation to use an older
    release. GitHub's Releases API lists releases newest first.
    """
    if fetch is None:
        fetch = download_asset
    release = next((r for r in releases if not r.get('draft')), None)
    if release is None:
        return None, [], None, []

    tag = release.get('tag_name')
    if not tag:
        fatal('Newest upstream release has no tag; cannot determine update status')
    if current_tag == tag:
        # Already pinned to this release. pinned_binaries() checks exact hashes.
        return None, [], None, []

    before, advertised = version_triplet(current), version_triplet(tag)
    if before is None or advertised is None:
        fatal(f'Cannot compare upstream release {tag} to pinned native version {current}')
    if advertised < before:
        # Existing upstream releases are older than our pinned native version.
        return None, [], None, []

    # Equal numeric triplets still count as a candidate: Ebi uses suffixes such
    # as v0.3.14-e and v0.3.14p that version_triplet() deliberately ignores.
    assets = {a.get('name'): a for a in release.get('assets', [])
              if isinstance(a, dict)}
    jars = [a for name, a in assets.items() if isinstance(name, str) and
            re.fullmatch(r'(?i)Ebi(?:-[0-9][A-Za-z0-9.+-]*)?\.jar', name)]
    missing = [name for name in ASSETS.values() if name not in assets]
    if len(jars) > 1:
        fatal(f'New upstream release {tag} has ambiguous Ebi JAR assets')
    if not jars and missing:
        fatal(f'New upstream release {tag} lacks required JNI assets (Ebi.jar or native pair): '
              + ', '.join(missing))

    try:
        if jars:
            obj = jars[0]
            jar_url = asset_url(obj, UPSTREAM)
            payload = fetch(obj, UPSTREAM)
            prepared, discovered = jar_artifacts(payload, jar_url)
            if java_source is not None:
                with zipfile.ZipFile(io.BytesIO(payload)) as verified_jar:
                    java_source.append(verified_jar.read(JAR_SOURCE).decode('utf-8'))
        else:
            prepared = []
            for platform, name in ASSETS.items():
                obj = assets[name]
                data = fetch(obj, UPSTREAM)
                validate(data, platform)
                prepared.append((platform, data, asset_url(obj, UPSTREAM)))
            versions = [version_of(data) for _, data, _ in prepared]
            if not all(versions) or len(set(versions)) != 1:
                fatal(f'Cannot prove matching Ebi versions for the two JNI assets: {versions}')
            discovered = versions[0]
        embedded = version_triplet(discovered)
        if embedded is None or embedded < before:
            fatal(f'Native version {discovered} is older than pinned {current}')
        if embedded != advertised:
            fatal(f'Native version {discovered} does not match release tag {tag}')
    except (RuntimeError, OSError, ValueError, KeyError, struct.error) as error:
        raise RuntimeError(f'New upstream release {tag} failed JNI validation: {error}') from error
    return release, prepared, discovered, []


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
        # A jar-based lock points both platforms to the same verified archive.
        jar_url = manifest.get('source_jar_url')
        if jar_url:
            payload = download_asset({'browser_download_url': jar_url}, UPSTREAM)
            prepared, version = jar_artifacts(payload, jar_url)
            if version != manifest['version']:
                fatal('Pinned Ebi JAR embedded version changed')
            data_by_platform = {platform: data for platform, data, _ in prepared}
            restored = []
            for entry in manifest['artifacts']:
                data = data_by_platform[entry['platform']]
                if len(data) != entry['size'] or sha(data) != entry['sha256']:
                    fatal('Pinned GitHub JAR embedded library changed: ' + entry['platform'])
                restored.append((entry, data, sha(data)))
            return restored, 'locked upstream GitHub Release Ebi JAR'
        # A legacy GitHub-sourced lock can be reconstructed by native asset URL.
        urls = [e.get('url') for e in manifest['artifacts']]
        if all(urls):
            prepared = []
            for entry in manifest['artifacts']:
                obj = {'browser_download_url': entry['url']}
                data = download_asset(obj, UPSTREAM)
                validate(data, entry['platform'], manifest.get('jni_api', 'legacy'))
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
        validate(data, entry['platform'], manifest.get('jni_api', 'legacy'))
        if len(data) != entry['size'] or sha(data) != entry['sha256']:
            fatal('Pinned update-site binary differs from native-artifacts.json: ' + entry['platform'])
        prepared.append((entry, data, sha(data)))
    return prepared, source


def run(mode: str, site: Path | None, report: Path, jar: Path | None = None):
    manifest = json.loads(LOCK.read_text(encoding='utf-8'))
    entries = manifest.get('artifacts', [])
    if len(entries) != 2 or {e['platform'] for e in entries} != set(ASSETS):
        fatal('Exactly two pinned Ebi JNI artifacts (Linux and Windows) are required')
    prepared = []
    changed = False
    warnings = []
    release_tag = None
    candidate = []
    discovered_version = None
    java_source: list[str] = []
    if mode == 'jar':
        if jar is None:
            fatal('Local jar mode requires --jar /path/to/Ebi.jar')
        if jar.stat().st_size > MAX_JAR_SIZE:
            fatal('Local Ebi JAR exceeds size limit')
        candidate, discovered_version = jar_artifacts(jar.read_bytes())
        with zipfile.ZipFile(jar) as verified_jar:
            java_source.append(verified_jar.read(JAR_SOURCE).decode('utf-8'))
        if version_triplet(discovered_version) < version_triplet(manifest.get('version')):
            fatal('Refusing to replace pinned Ebi with an older local JAR')
    elif mode == 'latest':
        release, candidate, discovered_version, warnings = latest_compatible_release(
            api_releases(UPSTREAM), str(manifest.get('version', '')),
            current_tag=manifest.get('source_release_tag'), java_source=java_source)
        if release is not None:
            release_tag = release['tag_name']
        else:
            warnings.append('No newer Ebi GitHub release than the pinned native version; restoring the exact pinned baseline.')
            prepared, source = pinned_binaries(manifest, site)
    if candidate:
        paths = {platform: (data, url) for platform, data, url in candidate}
        api = 'ebitools' if JNI_NEW in paths['linux-x86_64'][0] else 'legacy'
        if any((JNI_NEW in data) != (api == 'ebitools') for data, url in paths.values()):
            fatal('Linux/Windows JNI ABIs do not match')
        for entry in entries:
            data, url = paths[entry['platform']]
            prepared.append((entry, data, sha(data)))
        changed = (any(e['sha256'] != digest for e, _, digest in prepared)
                   or manifest.get('jni_api', 'legacy') != api
                   or manifest.get('version') != discovered_version
                   or (release_tag is not None and
                       manifest.get('source_release_tag') != release_tag))
        if changed:
            is_jar = api == 'ebitools'
            for entry, _, digest in prepared:
                data, url = paths[entry['platform']]
                entry.update(sha256=digest, size=len(data), url=(None if is_jar else url),
                             embedded_version=discovered_version,
                             source=('Supplied Ebi Java JAR native binary' if mode == 'jar'
                                     else 'Official Ebi GitHub Release JAR native binary' if is_jar
                                     else 'Official Ebi GitHub Release JNI asset'),
                             runtime_verified=False)
            manifest.update(retrieved_utc=datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                            version=discovered_version, jni_api=api,
                            source_release_tag=release_tag,
                            source_release_url=(f'{RELEASES_URL}/tag/{urllib.parse.quote(release_tag)}'
                                                if release_tag else None),
                            source_jar_url=(paths['linux-x86_64'][1] if is_jar else None),
                            pin_type='GitHub tag or local JAR; SHA-256 native JNI validation required',
                            source_commit=None)
            if mode == 'jar':
                manifest['source_jar_sha256'] = sha(jar.read_bytes())
            else:
                manifest.pop('source_jar_sha256', None)
            manifest.pop('rejected_assets', None)
            manifest.pop('original_supplied_windows_sha256', None)
    elif mode == 'pinned':
        prepared, source = pinned_binaries(manifest, site)
    # No writes until both artifacts have passed checks.
    for entry, data, digest in prepared:
        atomic_write(artifact_path(entry), data)
    if changed:
        # The Java method declarations are a verified entry of THIS JAR, not
        # guessed from the old ProM metadata or a second upstream download.
        if manifest.get('jni_api') == 'ebitools':
            if len(java_source) != 1 or 'native String callEbi(' not in java_source[0]:
                fatal('Changed Ebi JAR is missing its validated Java command catalogue')
            atomic_write(ROOT / 'metadata/ebi-java.txt', java_source[0].encode('utf-8'))
        atomic_write(LOCK, (json.dumps(manifest, indent=2) + '\n').encode('utf-8'))
    atomic_write(EMBEDDED, LOCK.read_bytes())
    atomic_write(ROOT / RUNTIME_CONSTANTS, runtime_java(manifest))
    summary = {
        'mode': mode, 'changed': changed, 'version': manifest.get('version'),
        'source': (f'Ebi GitHub Release {release_tag}' if release_tag else
                   ('supplied local Ebi JAR' if mode == 'jar' else
                    source if mode == 'pinned' else 'pinned baseline; Ebi GitHub Releases checked')),
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
    parser.add_argument('mode', choices=['pinned', 'latest', 'jar'])
    parser.add_argument('--jar', type=Path, help='local upstream Ebi JAR (mode: jar)')
    parser.add_argument('--site', type=Path, help='previous release update-site ZIP (pinned or fallback)')
    parser.add_argument('--report', type=Path, default=ROOT/'.ci/upstream.json')
    args = parser.parse_args()
    if args.site and not args.site.is_file():
        parser.error('Seed site does not exist')
    if args.mode == 'jar' and (args.jar is None or not args.jar.is_file()):
        parser.error('Mode jar requires --jar /path/to/Ebi.jar')
    if args.jar and args.mode != 'jar':
        parser.error('--jar is only used in jar mode')
    run(args.mode, args.site, args.report, args.jar)


if __name__ == '__main__':
    main()

"""Offline regression tests for Ebi GitHub Releases and safe JNI fallback."""
from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zipfile

FILE = Path(__file__).resolve().parents[1] / 'scripts/upstream.py'
spec = importlib.util.spec_from_file_location('ebi_upstream_under_test', FILE)
up = importlib.util.module_from_spec(spec)
spec.loader.exec_module(up)


def library(platform: str, version: str = '0.3.16') -> bytes:
    buf = bytearray(2048)
    if platform == 'linux-x86_64':
        buf[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', buf, 16, 3, 62)
        struct.pack_into('<Q', buf, 32, 64)
        struct.pack_into('<HH', buf, 54, 56, 1)
        struct.pack_into('<I', buf, 64, 1)  # PT_LOAD, no PT_INTERP
    else:
        buf[:2] = b'MZ'
        struct.pack_into('<I', buf, 60, 128)
        buf[128:132] = b'PE\x00\x00'
        struct.pack_into('<H', buf, 132, 0x8664)
        struct.pack_into('<H', buf, 150, 0x2000)
    tail = up.JNI + b'  Ebi version: ' + version.encode() + b'\x00'
    buf[1000:1000+len(tail)] = tail
    return bytes(buf)


def release(tag: str, names=('libebi.so', 'ebi.dll')):
    return {'tag_name': tag, 'draft': False,
            'assets': [{'name': name,
                        'browser_download_url': f'https://github.com/{up.UPSTREAM}/releases/download/{tag}/{name}'}
                       for name in names]}


def manifest(linux: bytes, windows: bytes):
    return {'version': '0.3.14-alpha.1',
            'artifacts': [
                {'platform': platform,
                 'path': f'extension/lib/{platform}/{name}',
                 'size': len(data), 'sha256': up.sha(data), 'url': None}
                for platform, name, data in [('linux-x86_64', 'libebi.so', linux),
                                              ('windows-x86_64', 'ebi.dll', windows)]]}


class GitHubUpstreamTests(unittest.TestCase):
    def test_valid_release_pair(self):
        data = {'libebi.so': library('linux-x86_64'),
                'ebi.dll': library('windows-x86_64')}
        found, prepared, version, notes = up.latest_compatible_release(
            [release('v0.3.16')], '0.3.14-alpha.1',
            fetch=lambda asset, repo: data[asset['name']])
        self.assertEqual(found['tag_name'], 'v0.3.16')
        self.assertEqual(len(prepared), 2)
        self.assertEqual(version, '0.3.16')
        self.assertEqual(notes, [])

    def test_reject_pie_executable(self):
        data = bytearray(library('linux-x86_64'))
        struct.pack_into('<I', data, 64, 3)  # PT_INTERP
        files = {'libebi.so': bytes(data), 'ebi.dll': library('windows-x86_64')}
        found, prepared, version, notes = up.latest_compatible_release(
            [release('v0.3.16')], '0.3.14', fetch=lambda asset, repo: files[asset['name']])
        self.assertIsNone(found)
        self.assertFalse(prepared)
        self.assertIsNone(version)
        self.assertIn('PT_INTERP', notes[0])

    def test_skip_older_release(self):
        files = {'libebi.so': library('linux-x86_64', '0.3.12'),
                 'ebi.dll': library('windows-x86_64', '0.3.12')}
        found, _, _, notes = up.latest_compatible_release(
            [release('v0.3.12')], '0.3.14-alpha.1',
            fetch=lambda asset, repo: files[asset['name']])
        self.assertIsNone(found)
        self.assertIn('older native', notes[0])

    def test_do_not_treat_cli_as_jni(self):
        found, prepared, _, _ = up.latest_compatible_release(
            [release('v0.3.16', ('Ebi-x86_64-linux', 'Ebi-x86_64-windows.exe'))],
            '0.3.14', fetch=lambda a, r: self.fail('Should never download CLI'))
        self.assertIsNone(found)
        self.assertFalse(prepared)

    def test_wrong_download_origin(self):
        with self.assertRaisesRegex(RuntimeError, 'non-GitHub'):
            up.asset_url({'browser_download_url': 'https://git.rwth-aachen.de/a/b'}, up.UPSTREAM)

    def test_pinned_fallback_without_network(self):
        old_linux = library('linux-x86_64', '0.3.14-alpha.1')
        old_windows = library('windows-x86_64', '0.3.14-alpha.1')
        lock = manifest(old_linux, old_windows)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            inner = io.BytesIO()
            with zipfile.ZipFile(inner, 'w') as plugin:
                for entry, data in zip(lock['artifacts'], (old_linux, old_windows)):
                    plugin.writestr(entry['path'].removeprefix('extension/'), data)
            outer = io.BytesIO()
            with zipfile.ZipFile(outer, 'w') as site:
                site.writestr('plugins/org.ryoo.knimeEbi_1.1.1.jar', inner.getvalue())
            site_file = root / 'site.zip'
            site_file.write_bytes(outer.getvalue())
            lock_file = root / 'native-artifacts.json'
            lock_file.write_text(json.dumps(lock))
            with mock.patch.multiple(up, ROOT=root, LOCK=lock_file,
                                     EMBEDDED=root/'extension/lib/native-artifacts.json'):
                with mock.patch.object(up, 'api_releases', return_value=[release('v0.3.16', ('no-jni-assets.zip',))]):
                    summary = up.run('latest', site_file, root/'.ci/upstream.json')
                self.assertFalse(summary['changed'])
                self.assertTrue(summary['warnings'])
                for entry in lock['artifacts']:
                    self.assertEqual(up.artifact_path(entry).read_bytes(),
                                     old_linux if entry['platform'] == 'linux-x86_64' else old_windows)
                self.assertEqual(json.loads((root/'extension/lib/native-artifacts.json').read_text()), lock)

    def test_successful_update_writes_atomic_lock(self):
        old_linux = library('linux-x86_64', '0.3.14-alpha.1')
        old_windows = library('windows-x86_64', '0.3.14-alpha.1')
        new_data = {'libebi.so': library('linux-x86_64'),
                    'ebi.dll': library('windows-x86_64')}
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            lock_file=root/'native-artifacts.json'
            lock_file.write_text(json.dumps(manifest(old_linux,old_windows)))
            with mock.patch.multiple(up, ROOT=root, LOCK=lock_file,
                                     EMBEDDED=root/'extension/lib/native-artifacts.json'):
                with mock.patch.object(up, 'api_releases', return_value=[release('v0.3.16')]), \
                     mock.patch.object(up, 'download_asset', side_effect=lambda a, r: new_data[a['name']]):
                    summary=up.run('latest', None, root/'.ci/upstream.json')
                self.assertTrue(summary['changed'])
                self.assertEqual(summary['release_tag'], 'v0.3.16')
                changed=json.loads(lock_file.read_text())
                self.assertEqual(changed['source_release_tag'], 'v0.3.16')
                self.assertTrue(all(x['url'].startswith('https://github.com/BPM-Research-Group/Ebi/releases/download/')
                                    for x in changed['artifacts']))
                self.assertTrue(all(not x['runtime_verified'] for x in changed['artifacts']))


if __name__ == '__main__':
    unittest.main()

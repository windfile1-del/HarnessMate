"""Linux/Termux filesystem acceptance for the actual shipped trusted helper."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('mirror_helper', os.environ.get('VEGA_MIRROR_HELPER', str(Path(__file__).resolve().parents[1] / 'assets/mirror_helper.py')))
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


@unittest.skipUnless(os.name == 'posix', 'Requires actual POSIX dir_fd/O_NOFOLLOW; Windows mocks are not acceptance')
class MirrorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.payload = b''
        self.mirror = helper.Mirror(self.temp.name + '/workspace',
            {'ignoredSegments': ['build', '.gradle', 'node_modules'], 'ignoredSuffixes': ['.apk']},
            lambda route: io.BytesIO(self.payload))

    def put(self, path, text):
        data = text.encode()
        self.mirror.put(path, io.BytesIO(data), len(data))

    def test_stream_hash_and_persistent_files(self):
        self.put('source.txt', 'before')
        self.mirror.sync({'entries': [], 'deleted': []})
        self.assertEqual(self.mirror.text('source.txt'), 'before')
        self.assertEqual(self.mirror.manifest()[0]['sha256'], hashlib.sha256(b'before').hexdigest())

    def test_failed_stream_does_not_overwrite(self):
        self.put('source.txt', 'before')
        with self.assertRaises(ValueError):
            self.mirror.put('source.txt', io.BytesIO(b'after'), 5, '0' * 64)
        self.assertEqual(self.mirror.text('source.txt'), 'before')
        self.assertEqual(os.listdir(self.mirror.project), ['source.txt'])

    def test_incremental_sync_only_requests_changed_file(self):
        self.put('unchanged', 'keep')
        self.payload = b'new'
        result = self.mirror.sync({'deleted': [], 'entries': [dict(path='changed', directory=False, size=3,
            sha256=hashlib.sha256(b'new').hexdigest(), source=0)]})
        self.assertEqual(result['transferredFiles'], 1)
        self.assertEqual(self.mirror.text('unchanged'), 'keep')
        self.assertEqual(self.mirror.text('changed'), 'new')

    def test_paged_sync_plan_keeps_control_metadata_bounded(self):
        page = json.dumps({'entries': [dict(path='changed', directory=False, size=3,
            sha256=hashlib.sha256(b'new').hexdigest(), source=1)]}).encode()
        payloads = {0: page, 1: b'new'}
        self.mirror.fetch = lambda route: io.BytesIO(payloads[int(route.split('/', 1)[1])])
        result = self.mirror.sync({
            'planSources': [0],
            'planPageCount': 1,
            'expectedRemoteDigest': helper.manifest_digest([])
        })
        self.assertEqual(result['transferredFiles'], 1)
        self.assertEqual(self.mirror.text('changed'), 'new')

    def test_symlink_leaf_and_parent_blocked(self):
        outside = Path(self.temp.name, 'outside')
        outside.write_text('secret')
        os.symlink(outside, self.mirror.project + '/link')
        os.symlink(self.temp.name, self.mirror.project + '/parent')
        for path in ('link', 'parent/outside'):
            with self.assertRaises(OSError):
                self.mirror.text(path)
            with self.assertRaises((OSError, ValueError)):
                self.put(path, 'overwrite')
        self.assertEqual(outside.read_text(), 'secret')

    def test_traversal_and_special_file_rejected(self):
        for path in ('../escape', '/absolute', 'dir/../escape', 'dir\\escape', 'a\0b'):
            with self.assertRaises(ValueError):
                self.mirror.info(path)
        os.mkfifo(self.mirror.project + '/fifo')
        with self.assertRaises(ValueError):
            self.mirror.open('fifo')

    def test_cache_is_not_in_manifest(self):
        self.mirror.mkdir('build')
        self.put('build/artifact', 'retained')
        self.assertEqual(self.mirror.manifest(), [])
        self.assertEqual(self.mirror.text('build/artifact'), 'retained')

    def test_read_search_and_write_namespace(self):
        self.mirror.tool('execution_write_file', {'path': 'a.txt', 'content': 'hello\nworld\n'})
        result = self.mirror.tool('execution_read_file', {'path': 'a.txt', 'start_line': 2})
        self.assertIn('world', result['text'])
        self.assertEqual(self.mirror.tool('execution_search_files', {'query': 'world'})['matches'], ['a.txt'])
        self.assertEqual(self.mirror.tool('execution_glob', {'pattern': '*.txt'})['matches'], ['a.txt'])
        with self.assertRaises(ValueError):
            self.mirror.tool('execution_write_file', {'path': 'a.txt', 'content': 'overwrite'})

    def archive(self, names):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            for name, text in names:
                archive.writestr(name, text)
        self.payload = buffer.getvalue()
        return {'size': len(self.payload), 'format': 'zip'}

    def test_archive_visible_and_source_untouched(self):
        args = self.archive([('src/main.py', 'print(1)')])
        self.mirror.import_archive(args)
        self.assertEqual(self.mirror.text('src/main.py'), 'print(1)')

    def test_archive_traversal_rejected_before_extraction(self):
        args = self.archive([('safe.txt', 'safe'), ('../evil', 'evil')])
        with self.assertRaises(ValueError):
            self.mirror.import_archive(args)
        self.assertEqual(os.listdir(self.mirror.project), [])

    def test_archive_link_rejected(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            entry = zipfile.ZipInfo('link')
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, '../escape')
        self.payload = buffer.getvalue()
        with self.assertRaises(ValueError):
            self.mirror.import_archive({'size': len(self.payload), 'format': 'zip'})

    def test_tar_hardlink_rejected(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            entry = tarfile.TarInfo('hardlink')
            entry.type, entry.linkname = tarfile.LNKTYPE, '../escape'
            archive.addfile(entry)
        self.payload = buffer.getvalue()
        with self.assertRaises(ValueError):
            self.mirror.import_archive({'size': len(self.payload), 'format': 'tar'})

    def test_storage_reserve(self):
        with self.assertRaises(ValueError):
            self.mirror.reserve(10**18)

    def test_real_command_changes_persistent_project(self):
        shell = '/data/data/com.termux/files/usr/bin/bash' if Path('/data/data/com.termux/files/usr/bin/bash').exists() else '/bin/sh'
        self.put('run', '#!' + shell + '\nprintf changed > result.txt\nprintf success\n')
        self.payload = b'0'
        result = self.mirror.execute({'executable': './run', 'args': [], 'timeoutMs': 5000})
        self.assertEqual(result['exitCode'], 0)
        self.assertEqual(result['stdout'], 'success')
        self.assertEqual(self.mirror.text('result.txt'), 'changed')

    def test_real_command_timeout(self):
        shell = '/data/data/com.termux/files/usr/bin/bash' if Path('/data/data/com.termux/files/usr/bin/bash').exists() else '/bin/sh'
        self.put('run', '#!' + shell + '\nsleep 20\n')
        self.payload = b'0'
        result = self.mirror.execute({'executable': './run', 'args': [], 'timeoutMs': 50})
        self.assertTrue(result['timedOut'])
        self.assertLess(result['elapsedMs'], 3000)


if __name__ == '__main__':
    unittest.main(verbosity=2)

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'inventory-runtime.py'


class InventoryTests(unittest.TestCase):
    def archive(self, entries):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'fixture.tar.gz'
        with tarfile.open(path, 'w:gz') as archive:
            for name, data, kind in entries:
                info = tarfile.TarInfo(name)
                info.type = kind
                info.size = len(data) if kind == tarfile.REGTYPE else 0
                archive.addfile(info, io.BytesIO(data) if info.size else None)
        return path

    def run_inventory(self, entries):
        path = self.archive(entries)
        result = subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)
        return path, result

    def test_selected_metadata_sorted_and_path_distinct(self):
        entries = [('lib/apk/db/installed', b'P:z\nV:2\nL:MIT\n\nP:a\nV:1\n\n', tarfile.REGTYPE),
                   ('vendor/z.dist-info/METADATA', b'Name: Z\nVersion: 2\nLicense: old\nLicense-Expression: MIT\n\nsecret body', tarfile.REGTYPE)]
        for name in ('usr/node_modules/z/examples/package.json', 'usr/node_modules/z/package.json'):
            entries.append((name, b'{"name":"z","version":"1"}', tarfile.REGTYPE))
        entries += [('usr/node_modules/bad/package.json', b'{', tarfile.REGTYPE),
                    ('usr/node_modules/unversioned/package.json', b'{"name":"x"}', tarfile.REGTYPE)]
        path, result = self.run_inventory(entries)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data['source_sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual([x['name'] for x in data['alpine']], ['a', 'z'])
        self.assertEqual(data['python'][0]['declared_license'], 'MIT')
        self.assertEqual(len(data['npm']), 2)
        self.assertIsNone(data['npm'][0]['declared_license'])
        self.assertEqual(data['npm'], sorted(data['npm'], key=lambda x: (x['name'], x['metadata_path'], x['version'])))

    def test_unsafe_and_symlink_metadata_ignored(self):
        _, result = self.run_inventory([(p, b'{"name":"secret","version":"1"}', kind) for p, kind in [('/node_modules/x/package.json', tarfile.REGTYPE), ('../node_modules/x/package.json', tarfile.REGTYPE), ('usr/node_modules/x/package.json', tarfile.SYMTYPE)]])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['npm'], [])

    def test_oversize_target_fails_redacted(self):
        _, result = self.run_inventory([('usr/node_modules/SECRET/package.json', b'x' * (2 * 1024 * 1024 + 1), tarfile.REGTYPE)])
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('SECRET', result.stderr)
        self.assertEqual(result.stdout, '')

    def test_invalid_utf8_fails_redacted(self):
        _, result = self.run_inventory([('lib/apk/db/installed', b'PRIVATE\xff', tarfile.REGTYPE)])
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('PRIVATE', result.stderr)
        self.assertNotIn('Traceback', result.stderr)

    def load_module(self):
        self.assertTrue(SCRIPT.exists(), 'inventory implementation missing')
        spec = importlib.util.spec_from_file_location('inventory', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_never_reads_unselected_entries(self):
        module = self.load_module()
        path = self.archive([('root/.config/auth.json', b'PRIVATE', tarfile.REGTYPE), ('data/photos.db', b'PRIVATE', tarfile.REGTYPE)])
        with patch.object(tarfile.TarFile, 'extractfile', side_effect=AssertionError('private file read')):
            self.assertEqual(module.inventory(str(path))['npm'], [])

    def test_archive_entry_and_total_byte_budgets(self):
        module = self.load_module()
        path = self.archive([('other', b'x', tarfile.REGTYPE), ('usr/node_modules/x/package.json', b'{}', tarfile.REGTYPE)])
        with patch.object(module, 'MAX_ENTRIES', 1):
            with self.assertRaises(ValueError):
                module.inventory(str(path))
        with patch.object(module, 'MAX_TOTAL_BYTES', 1):
            with self.assertRaises(ValueError):
                module.inventory(str(path))

    def test_record_budget(self):
        module = self.load_module()
        path = self.archive([('lib/apk/db/installed', b'P:a\nV:1\n\nP:b\nV:2\n', tarfile.REGTYPE)])
        with patch.object(module, 'MAX_RECORDS', 1):
            with self.assertRaises(ValueError):
                module.inventory(str(path))

    def test_python_license_is_bounded_and_missing_is_none(self):
        _, result = self.run_inventory([
            ('a.dist-info/METADATA', ('Name: a\nVersion: 1\nLicense: ' + 'x' * 600 + '\n').encode(), tarfile.REGTYPE),
            ('b.dist-info/METADATA', b'Name: b\nVersion: 2\n', tarfile.REGTYPE)])
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads(result.stdout)['python']
        self.assertEqual(rows[0]['declared_license'], 'x' * 512)
        self.assertIsNone(rows[1]['declared_license'])

    def test_invalid_archive_safe_exit(self):
        path = self.archive([])
        path.write_bytes(b'PRIVATE invalid gzip')
        result = subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('PRIVATE', result.stderr)
        self.assertNotIn('Traceback', result.stderr)
        self.assertEqual(result.stdout, '')

    def test_corrupt_deflate_safe_exit(self):
        path = self.archive([])
        path.write_bytes(bytes.fromhex('1f8b0800000000000003') + b'\x07PRIVATE' + bytes(8))
        self.assert_safe_failure(path)

    def test_deep_json_safe_exit(self):
        path = self.archive([('usr/node_modules/PRIVATE/package.json', b'[' * 50000 + b'0' + b']' * 50000, tarfile.REGTYPE)])
        self.assert_safe_failure(path)

    def assert_safe_failure(self, path):
        result = subprocess.run([sys.executable, str(SCRIPT), str(path)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertNotIn('Traceback', result.stderr)
        self.assertNotIn('PRIVATE', result.stderr)

    def test_path_replacement_keeps_hashed_snapshot(self):
        module = self.load_module()
        path = self.archive([('lib/apk/db/installed', b'P:original\nV:1\n', tarfile.REGTYPE)])
        old_bytes = path.read_bytes()
        replacement = self.archive([])
        digest = module.hashlib.file_digest

        def replace_after_hash(stream, algorithm):
            value = digest(stream, algorithm)
            replacement.replace(path)
            return value

        with patch.object(module.hashlib, 'file_digest', side_effect=replace_after_hash):
            result = module.inventory(str(path))
        self.assertEqual(result['source_sha256'], hashlib.sha256(old_bytes).hexdigest())
        self.assertEqual(result['source_size_bytes'], len(old_bytes))
        self.assertEqual(result['alpine'][0]['name'], 'original')

    def test_in_place_mutation_fails(self):
        module = self.load_module()
        path = self.archive([])
        digest = module.hashlib.file_digest

        def mutate_after_hash(stream, algorithm):
            value = digest(stream, algorithm)
            with path.open('ab') as output:
                output.write(b'changed')
            return value

        with patch.object(module.hashlib, 'file_digest', side_effect=mutate_after_hash):
            with self.assertRaisesRegex(ValueError, 'source changed'):
                module.inventory(str(path))

    def test_unselected_members_not_retained(self):
        module = self.load_module()
        path = self.archive([(str(i), b'x', tarfile.REGTYPE) for i in range(100)])
        original_next = tarfile.TarFile.next
        retained = []

        def observe_next(archive):
            retained.append(len(archive.members))
            return original_next(archive)

        with patch.object(tarfile.TarFile, 'next', observe_next):
            module.inventory(str(path))
        self.assertLessEqual(max(retained), 1)

    def test_atomic_output_failure_preserves_report(self):
        module = self.load_module()
        path = self.archive([])
        output = path.parent / 'report.json'
        output.write_text('old report')
        self.assertTrue(hasattr(module, 'os'), 'atomic output implementation missing')
        with patch.object(sys, 'argv', [str(SCRIPT), str(path), '--output', str(output)]), \
                patch.object(module.os, 'replace', side_effect=OSError('PRIVATE')), \
                patch.object(sys, 'stderr', io.StringIO()) as stderr:
            self.assertEqual(module.main(), 1)
        self.assertEqual(output.read_text(), 'old report')
        self.assertNotIn('PRIVATE', stderr.getvalue())
        self.assertEqual(sorted(p.name for p in path.parent.iterdir()), ['fixture.tar.gz', 'report.json'])

    def test_output_file_matches_stdout(self):
        path = self.archive([])
        output = path.parent / 'output.json'
        result = subprocess.run([sys.executable, str(SCRIPT), str(path), '--output', str(output)], capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, b'')
        self.assertEqual(json.loads(output.read_text())['source'], str(path))

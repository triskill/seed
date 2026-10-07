"""Small synthetic ZIP/ELF fixtures; no SDK, extraction, or user data."""
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / 'check-play-artifact.py'
LIBS = ('libproot.so', 'libproot-loader.so', 'libtalloc.so', 'libandroid-shmem.so')


def elf(machine=183, align=0x4000, offset=0, vaddr=0, count=1):
    ident = b'\x7fELF' + bytes((2, 1, 1)) + bytes(9)
    header = struct.pack('<HHIQQQIHHHHHH', 3, machine, 1, 0, 64, 0, 0, 64, 56, count, 0, 0, 0)
    ph = struct.pack('<IIQQQQQQ', 1, 5, offset, vaddr, 0, 0, 0, align)
    return ident + header + ph * count


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'fixture.zip'

    def entries(self, prefix='', abi='arm64-v8a'):
        metadata = dict(seed_version='test', build_id='test', runtime_format='native',
                        runtime_format_version=3, native_arch='arm64' if abi == 'arm64-v8a' else 'x86_64')
        return [(prefix + 'lib/' + abi + '/' + name, elf(183 if abi == 'arm64-v8a' else 62)) for name in LIBS] + [
            (prefix + 'assets/linux/rootfs.tar', b'not opened by checker'),
            (prefix + 'assets/linux/seed_version.json', json.dumps(metadata).encode())]

    def run_check(self, entries=None, abi='arm64-v8a', compression=zipfile.ZIP_STORED):
        if entries is not None:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(self.path, 'w', compression=compression) as z:
                    for name, data in entries:
                        z.writestr(name, data)
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.path), '--expected-abi', abi, '--json'], capture_output=True, text=True)
        self.assertNotIn('Traceback', result.stderr)
        self.assertNotIn('PRIVATE_SECRET', result.stdout + result.stderr)
        return result

    def rejected(self, entries, **kwargs):
        result = self.run_check(entries, **kwargs)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertTrue(json.loads(result.stdout)['errors'])

    def test_valid_apk_and_aab_both_architectures(self):
        for prefix in ('', 'base/'):
            for abi in ('arm64-v8a', 'x86_64'):
                with self.subTest(prefix=prefix, abi=abi):
                    result = self.run_check(self.entries(prefix, abi), abi=abi)
                    self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
                    report = json.loads(result.stdout)
                    self.assertEqual(report['artifact_bytes'], self.path.stat().st_size)
                    self.assertEqual(report['rootfs']['compression_method'], 0)

    def test_capped_findings_do_not_overwrite_previous_diagnostics(self):
        entries = [(f'assets/private{i}/auth.json', b'PRIVATE_SECRET') for i in range(128)]
        entries += [(n, elf(align=4096) if n.endswith('/libproot.so') else d)
                    for n, d in self.entries()]
        result = self.run_check(entries)
        self.assertEqual(result.returncode, 1)
        errors = json.loads(result.stdout)['errors']
        self.assertEqual(len(errors), 128)
        self.assertTrue(all(e['code'] == 'credential_path' and 'reason' not in e for e in errors))

    def test_foreign_and_unknown_abi(self):
        for abi in ('x86_64', 'mystery'):
            self.rejected(self.entries() + [('lib/' + abi + '/libextra.so', elf())])

    def test_missing_required_library(self):
        self.rejected(self.entries()[1:])

    def test_all_native_libraries_checked(self):
        self.rejected(self.entries() + [('lib/arm64-v8a/libtermux.so', elf(align=4096))])

    def test_invalid_elf_variants(self):
        variants = [b'bad', elf(machine=62), elf(align=4096), elf(vaddr=1), elf(count=0), elf()[:70],
                    elf()[:4] + bytes((1,)) + elf()[5:], elf()[:5] + bytes((2,)) + elf()[6:]]
        bad = bytearray(elf()); struct.pack_into('<Q', bad, 32, 100000)
        variants.append(bytes(bad))
        no_load = bytearray(elf()); struct.pack_into('<I', no_load, 64, 0)
        segment_bounds = bytearray(elf()); struct.pack_into('<Q', segment_bounds, 96, 100000)
        variants.extend((bytes(no_load), bytes(segment_bounds), elf(machine=999)))
        for value in variants:
            with self.subTest(value=value[:20]):
                self.rejected([(n, value if n.endswith('/libproot.so') else d) for n, d in self.entries()])

    def test_bad_metadata(self):
        for data in (b'PRIVATE_SECRET invalid json', b'[]', b'{}', json.dumps(dict(runtime_format='source', runtime_format_version=3, native_arch='arm64')).encode(),
                     json.dumps(dict(seed_version='x', build_id='x', runtime_format='native', runtime_format_version=3, native_arch='x86_64')).encode(), b' ' * 65537):
            self.rejected([(n, data if n.endswith('seed_version.json') else d) for n, d in self.entries()])

    def test_legacy_runtime_and_debris(self):
        for name in ('assets/linux/rootfs.tar.gz', 'assets/linux/proot', '.git/config', '__MACOSX/file'):
            self.rejected(self.entries() + [(name, b'PRIVATE_SECRET')])

    def test_compressed_apk_assets_rejected(self):
        for suffix in ('rootfs.tar', 'seed_version.json'):
            with self.subTest(asset=suffix):
                entries = []
                for name, data in self.entries():
                    info = zipfile.ZipInfo(name)
                    info.compress_type = zipfile.ZIP_DEFLATED if name.endswith(suffix) else zipfile.ZIP_STORED
                    entries.append((info, data))
                self.rejected(entries)

    def test_aab_deflated_assets_allowed_with_delivery_limitation(self):
        result = self.run_check(self.entries('base/'), compression=zipfile.ZIP_DEFLATED)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report['rootfs']['compression_method'], 8)
        self.assertEqual(report['runtime_metadata']['compression_method'], 8)
        self.assertTrue(any('BundleConfig.pb' in text and 'bundletool' in text for text in report['limitations']))

    def test_elf_segment_bounds_and_alignment(self):
        variants = []
        for offset, filesz, memsz, align in ((119, 2, 2, 0x4000), (2 ** 64 - 1, 1, 1, 0x4000),
                                           (0, 0, 0, 0x6000), (0, 2, 1, 0x4000)):
            data = bytearray(elf())
            struct.pack_into('<Q', data, 72, offset)
            struct.pack_into('<QQQ', data, 96, filesz, memsz, align)
            variants.append(bytes(data))
        for data in variants:
            with self.subTest(header=data[64:]):
                self.rejected([(n, data if n.endswith('/libproot.so') else d) for n, d in self.entries()])

    def test_missing_assets(self):
        for suffix in ('rootfs.tar', 'seed_version.json'):
            self.rejected([(n, d) for n, d in self.entries() if not n.endswith(suffix)])

    def test_duplicate_traversal_and_secret_paths(self):
        for name in ('../escape', '/absolute', 'a/../b', 'a\\b', 'assets/.env', 'assets/auth.json', 'keys/release.jks', 'keys/release.keystore', 'keys/release.p12', 'lib/arm64-v8a/libproot.so'):
            self.rejected(self.entries() + [(name, b'PRIVATE_SECRET')])

    def test_symlink(self):
        info = zipfile.ZipInfo('assets/link'); info.create_system = 3; info.external_attr = 0o120777 << 16
        self.rejected(self.entries() + [(info, b'target')])

    def test_benign_settings_and_config_example(self):
        result = self.run_check(self.entries() + [('assets/settings.json', b'{}'), ('assets/backend/config.example.json', b'{}')])
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_oversized_native(self):
        self.rejected(self.entries() + [('lib/arm64-v8a/libhuge.so', bytes(64 * 1024 * 1024 + 1))], compression=zipfile.ZIP_DEFLATED)

    def test_corrupt_or_missing_zip(self):
        self.path.write_bytes(b'PRIVATE_SECRET not zip')
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.path.unlink()
        self.assertEqual(self.run_check().returncode, 1)

    def test_corrupt_deflate_entries(self):
        for suffix in ('seed_version.json', 'libproot.so'):
            self.run_check(self.entries(), compression=zipfile.ZIP_DEFLATED)
            with zipfile.ZipFile(self.path) as z:
                info = next(i for i in z.infolist() if i.filename.endswith(suffix))
            with self.path.open('r+b') as f:
                f.seek(info.header_offset + 26)
                name_length, extra_length = struct.unpack('<HH', f.read(4))
                f.seek(info.header_offset + 30 + name_length + extra_length)
                f.write(b'\x07' * info.compress_size)
            result = self.run_check()
            self.assertEqual(result.returncode, 1)
            self.assertFalse(json.loads(result.stdout)['ok'])

    def test_deep_metadata(self):
        self.rejected([(n, b'[' * 2000 + b'0' + b']' * 2000 if n.endswith('seed_version.json') else d)
                       for n, d in self.entries()])

    def test_asset_lib_paths_not_native(self):
        for prefix in ('', 'base/'):
            result = self.run_check(self.entries(prefix) + [
                (prefix + 'assets/vendor/lib/python3.12/config.json', b'{}'),
                (prefix + 'assets/vendor/lib/mystery/libfake.so', b'not ELF')])
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertEqual(len(json.loads(result.stdout)['native_libraries']), 4)

    def test_feature_foreign_abi(self):
        self.rejected(self.entries('base/') + [('feature/lib/mystery/libextra.so', elf())])

    def test_empty_rootfs(self):
        self.rejected([(n, b'' if n.endswith('rootfs.tar') else d) for n, d in self.entries()])

    def test_archive_and_directory_guardrails(self):
        with self.path.open('wb') as f:
            f.truncate(2 * 1024 ** 3 + 1)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)['errors'][0]['code'], 'archive_size_guardrail')
        for field_offset, fmt, value, code in (
                (12, '<I', 32 * 1024 ** 2 + 1, 'central_directory_guardrail'),
                (8, '<HH', (20001, 20001), 'central_directory_guardrail'),
                (8, '<HH', (65535, 65535), 'zip64_unsupported'),
                (16, '<I', 0xffffffff, 'zip64_unsupported'),
                (4, '<H', 1, 'multidisk_unsupported'),
                (16, '<I', 123456789, 'invalid_central_directory_bounds')):
            self.run_check(self.entries())
            raw = bytearray(self.path.read_bytes())
            end = raw.rfind(b'PK\x05\x06')
            struct.pack_into(fmt, raw, end + field_offset, *(value if isinstance(value, tuple) else (value,)))
            self.path.write_bytes(raw)
            result = self.run_check()
            self.assertEqual(result.returncode, 1)
            report = json.loads(result.stdout)
            self.assertEqual(report['errors'][0]['code'], code)
            self.assertLess(len(result.stdout), 5000)

    def test_native_global_guardrails(self):
        self.run_check(self.entries() + [('lib/arm64-v8a/libextra%d.so' % i, elf()) for i in range(125)])
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)['errors'][0]['code'], 'native_count_guardrail')
        self.run_check(self.entries() + [('lib/arm64-v8a/libextra.so', elf())])
        raw = bytearray(self.path.read_bytes())
        pos = 0
        while True:
            pos = raw.find(b'PK\x01\x02', pos)
            if pos < 0:
                break
            length = struct.unpack_from('<H', raw, pos + 28)[0]
            name = raw[pos + 46:pos + 46 + length]
            if name.endswith(b'.so'):
                struct.pack_into('<I', raw, pos + 24, 64 * 1024 ** 2)
            pos += 46 + length
        self.path.write_bytes(raw)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)['errors'][0]['code'], 'native_read_budget_guardrail')
        self.assertLess(len(result.stdout), 5000)

    def test_cli_usage_error(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True)
        self.assertEqual(result.returncode, 2)


if __name__ == '__main__':
    unittest.main()

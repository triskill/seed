#!/usr/bin/env python3
"""Read-only APK/AAB ZIP inventory and bounded native ELF inspection.

This is preparation, not Google Play approval. No extraction or rootfs reads.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import stat
import struct
import zipfile
import zlib
import re
from contextlib import ExitStack

# Local inspector resource guardrails, NOT Google Play size/packaging limits.
ARTIFACT_LIMIT = 2 * 1024 ** 3
DIRECTORY_LIMIT = 32 * 1024 ** 2
ENTRY_LIMIT = 20000
NATIVE_COUNT_LIMIT = 128
NATIVE_TOTAL_LIMIT = 256 * 1024 ** 2
ERROR_LIMIT = 128


class GuardrailError(ValueError):
    pass


def preflight(source, size):
    """Bound central-directory allocation before constructing ZipFile; classic ZIP only."""
    if size > ARTIFACT_LIMIT:
        raise GuardrailError('archive_size_guardrail')
    source.seek(max(0, size - 65557))
    footer = source.read(65557)
    position = footer.rfind(b'PK\x05\x06')
    if position < 0 or position + 22 > len(footer):
        raise GuardrailError('invalid_zip_footer')
    _, disk, directory_disk, disk_count, count, directory_size, offset, comment = struct.unpack_from('<4s4H2IH', footer, position)
    end = size - len(footer) + position
    if position + 22 + comment != len(footer):
        raise GuardrailError('invalid_zip_footer')
    if disk_count == 0xffff or count == 0xffff or directory_size == 0xffffffff or offset == 0xffffffff or footer[max(0, position - 20):position - 16] == b'PK\x06\x07':
        raise GuardrailError('zip64_unsupported')
    if disk or directory_disk or disk_count != count:
        raise GuardrailError('multidisk_unsupported')
    if directory_size > DIRECTORY_LIMIT or count > ENTRY_LIMIT:
        raise GuardrailError('central_directory_guardrail')
    if offset + directory_size != end or directory_size < count * 46:
        raise GuardrailError('invalid_central_directory_bounds')
    source.seek(offset)
    actual_count = 0
    while source.tell() < end:
        if actual_count >= ENTRY_LIMIT:
            raise GuardrailError('central_directory_guardrail')
        header = source.read(46)
        if len(header) != 46 or header[:4] != b'PK\x01\x02':
            raise GuardrailError('invalid_central_directory_record')
        compressed, uncompressed = struct.unpack_from('<II', header, 20)
        name_size, extra_size, comment_size, start_disk = struct.unpack_from('<4H', header, 28)
        local_offset = struct.unpack_from('<I', header, 42)[0]
        if 0xffffffff in (compressed, uncompressed, local_offset) or start_disk == 0xffff:
            raise GuardrailError('zip64_unsupported')
        if start_disk:
            raise GuardrailError('multidisk_unsupported')
        next_record = source.tell() + name_size + extra_size + comment_size
        if next_record > end or local_offset >= offset:
            raise GuardrailError('invalid_central_directory_bounds')
        source.seek(next_record)
        actual_count += 1
    if actual_count != count:
        raise GuardrailError('central_directory_entry_count_mismatch')
    source.seek(0)
    return count


def native_placement(name, is_aab):
    parts = name.split('/')
    if is_aab:
        if len(parts) != 4 or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', parts[0]):
            return None
        parts = parts[1:]
    if len(parts) == 3 and parts[0] == 'lib' and parts[1] and parts[2]:
        return parts[1]
    return None


NATIVE_LIMIT = 64 * 1024 * 1024
METADATA_LIMIT = 64 * 1024
REQUIRED = ('libproot.so', 'libproot-loader.so', 'libtalloc.so', 'libandroid-shmem.so')
ARCH = {'arm64-v8a': ('arm64', 183), 'x86_64': ('x86_64', 62)}
LIMITATIONS = [
    'Local resource guardrails (2 GiB artifact, 32 MiB directory, 20000 entries, 128 native files, 256 MiB native reads) are not Play limits; ZIP64/multidisk archives are unsupported.',
    'Path-only credential/debris scan: not a complete credential scan or provenance audit; rootfs contents are not read.',
    'No manifest identity/protobuf validation, signing validation, or Play approval determination.',
    'Raw ZIP sizes are not Play download/delivery sizes; AAB delivery requires bundletool/Play Console validation.',
    'AAB container asset compression is independent of delivered APK compression: BundleConfig.pb compression globs and delivered APK openFd assets must be verified using bundletool; this checker does not parse BundleConfig.pb.',
    'ELF segment checks do not establish delivered APK ZIP alignment or physical 16 KiB device runtime compatibility.',
]


def inspect_elf(data, machine):
    """Return segment measurements, rejecting malformed or incompatible ELF64."""
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('not a complete little-endian ELF64 header')
    header = struct.unpack_from('<HHIQQQIHHHHHH', data, 16)
    kind, actual_machine, version, _, phoff, _, _, ehsize, phsize, phnum, _, _, _ = header
    if actual_machine != machine or version != 1 or kind not in (2, 3):
        raise ValueError('incompatible ELF architecture/type/version')
    if ehsize != 64 or phsize != 56 or phnum == 0 or phnum == 0xffff or phoff < 64 or phoff + phnum * phsize > len(data):
        raise ValueError('invalid or truncated ELF program header table')
    loads = []
    for index in range(phnum):
        ptype, _, offset, vaddr, _, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, phoff + index * phsize)
        if offset > len(data) or filesz > len(data) - offset:
            raise ValueError('ELF segment exceeds file bounds')
        if ptype == 1:
            if filesz > memsz or vaddr + memsz > 2 ** 64:
                raise ValueError('invalid ELF load segment size')
            if align < 0x4000 or align & (align - 1) or offset % 0x4000 != vaddr % 0x4000 or offset % align != vaddr % align:
                raise ValueError('PT_LOAD not compatible with 16 KiB alignment')
            loads.append({'offset': offset, 'virtual_address': vaddr, 'alignment': align})
    if not loads:
        raise ValueError('ELF has no PT_LOAD segments')
    return loads


def inspect(path, abi):
    report = {'ok': False, 'artifact_bytes': None, 'format': None, 'expected_abi': abi,
              'abis': [], 'rootfs': None, 'runtime_metadata': None, 'native_libraries': [], 'errors': [], 'limitations': LIMITATIONS}

    def fail(code, entry=None):
        item = {'code': code}
        if entry is not None:
            item['entry'] = entry[:512]
        if len(report['errors']) < ERROR_LIMIT:
            report['errors'].append(item)
            return item
        return None

    def asset_measurement(info):
        return {'bytes': info.file_size, 'compressed_bytes': info.compress_size,
                'compression_method': info.compress_type,
                'compression_ratio': info.file_size / info.compress_size if info.compress_size else None}

    def check_asset_compression(info, is_aab):
        if info.flag_bits & 1:
            fail('runtime_asset_must_be_unencrypted', info.filename)
        if is_aab:
            if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                fail('unsupported_aab_asset_compression', info.filename)
        elif info.compress_type != zipfile.ZIP_STORED or info.file_size != info.compress_size:
            fail('apk_runtime_asset_must_be_zip_stored_for_openFd', info.filename)

    def bounded_read(z, info, limit):
        if info.file_size > limit:
            raise ValueError('entry exceeds bounded read limit')
        with z.open(info) as source:
            data = source.read(limit + 1)
        if len(data) > limit or len(data) != info.file_size:
            raise ValueError('invalid entry size')
        return data

    try:
        with ExitStack() as stack:
            source = stack.enter_context(path.open('rb'))
            source.seek(0, 2)
            report['artifact_bytes'] = source.tell()
            expected_entries = preflight(source, report['artifact_bytes'])
            z = stack.enter_context(zipfile.ZipFile(source))
            infos = z.infolist()
            if len(infos) != expected_entries or len(infos) > ENTRY_LIMIT:
                raise GuardrailError('central_directory_entry_count_mismatch')
            names = [i.filename for i in infos]
            prefix = 'base/' if any(n.startswith('base/') for n in names) else ''
            report['format'] = 'AAB' if prefix else 'APK'
            native_infos = [i for i in infos if native_placement(i.filename, bool(prefix)) is not None]
            if len(native_infos) > NATIVE_COUNT_LIMIT:
                raise GuardrailError('native_count_guardrail')
            if sum(i.file_size for i in native_infos) > NATIVE_TOTAL_LIMIT:
                raise GuardrailError('native_read_budget_guardrail')
            by_name = {}
            abis = set()
            for info in infos:
                name = info.filename
                parts = name.rstrip('/').split('/')
                if name in by_name:
                    fail('duplicate_entry', name)
                by_name[name] = info
                if '\x00' in info.orig_filename or name.startswith('/') or '\\' in name or any(p in ('', '.', '..') for p in parts) or (parts and ':' in parts[0]):
                    fail('unsafe_archive_path', name)
                if info.create_system == 3 and stat.S_ISLNK(info.external_attr >> 16):
                    fail('symlink_entry', name)
                leaf = PurePosixPath(name).name.lower()
                if leaf in ('.env', 'auth.json') or leaf.endswith(('.keystore', '.jks', '.p12')):
                    fail('credential_path', name)
                if leaf in ('rootfs.tar.gz', 'proot', 'qemu-aarch64', 'qemu-x86_64') and '/assets/linux/' in '/' + name:
                    fail('legacy_runtime_asset', name)
                if leaf in ('.ds_store',) or '__MACOSX' in parts or '.git' in parts:
                    fail('build_debris_path', name)
                native_abi = native_placement(name, bool(prefix))
                if native_abi is not None:
                    abis.add(native_abi)
                if native_abi is not None:
                    measurement = {'entry': name[:512], 'bytes': info.file_size}
                    report['native_libraries'].append(measurement)
                    try:
                        measurement['load_segments'] = inspect_elf(bounded_read(z, info, NATIVE_LIMIT), ARCH[abi][1])
                    except ValueError as error:
                        finding = fail('invalid_native_elf_or_alignment', name)
                        if finding is not None:
                            finding['reason'] = str(error)
                    except (OSError, RuntimeError, zipfile.BadZipFile, EOFError, NotImplementedError, zlib.error):
                        fail('unreadable_native_entry', name)
            report['abis'] = sorted(abis)
            if abis != {abi}:
                fail('expected_exactly_one_abi')
            for library in REQUIRED:
                name = prefix + 'lib/' + abi + '/' + library
                if name not in by_name:
                    fail('missing_required_library', name)
            root_name = prefix + 'assets/linux/rootfs.tar'
            root = by_name.get(root_name)
            if root is None:
                fail('missing_rootfs_asset')
            else:
                report['rootfs'] = asset_measurement(root)
                mode = root.external_attr >> 16
                if root.file_size <= 0 or root.is_dir() or (stat.S_IFMT(mode) and not stat.S_ISREG(mode)):
                    fail('rootfs_must_be_nonempty_regular_file', root_name)
                check_asset_compression(root, bool(prefix))
            metadata_name = prefix + 'assets/linux/seed_version.json'
            metadata = by_name.get(metadata_name)
            if metadata is None:
                fail('missing_runtime_metadata')
            else:
                report['runtime_metadata'] = asset_measurement(metadata)
                check_asset_compression(metadata, bool(prefix))
                try:
                    value = json.loads(bounded_read(z, metadata, METADATA_LIMIT))
                    if not isinstance(value, dict) or value.get('runtime_format') != 'native' or type(value.get('runtime_format_version')) is not int or value['runtime_format_version'] != 3 or value.get('native_arch') != ARCH[abi][0] or 'guest_arch' in value or not all(isinstance(value.get(k), str) and value[k] for k in ('seed_version', 'build_id')):
                        raise ValueError('incompatible metadata')
                except (ValueError, UnicodeError, OSError, RuntimeError, zipfile.BadZipFile, EOFError, NotImplementedError, zlib.error):
                    fail('invalid_runtime_metadata', metadata_name)
    except GuardrailError as error:
        fail(str(error))
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, EOFError, NotImplementedError, zlib.error):
        fail('unreadable_or_corrupt_archive')
    report['ok'] = not report['errors']
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=' '.join(LIMITATIONS))
    parser.add_argument('path', type=Path)
    parser.add_argument('--expected-abi', choices=tuple(ARCH), required=True)
    parser.add_argument('--json', action='store_true', help='emit structured measurements and findings')
    args = parser.parse_args()
    report = inspect(args.path, args.expected_abi)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print('Native inventory check: ' + ('PASS' if report['ok'] else 'FAIL'))
        print('Raw artifact bytes: ' + str(report['artifact_bytes']))
        for error in report['errors']:
            print(json.dumps(error))
        for limitation in LIMITATIONS:
            print(limitation)
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

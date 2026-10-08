#!/usr/bin/env python3
"""Inventory selected public package metadata in a gzip rootfs; never extract."""
import argparse
from email.parser import Parser
import hashlib
import json
import os
from pathlib import Path
import tempfile
import zlib
import sys
import tarfile

SCOPE = ('Installed package metadata only; not a complete SBOM or license clearance. '
         'Vendored files/binaries and exact license texts require additional review. '
         'Includes manifests in examples where versioned.')
MAX_ENTRY_BYTES = 2 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_ENTRIES = 200_000
MAX_RECORDS = 20_000


def selected_kind(name):
    parts = name.split('/')
    if name.startswith('/') or '..' in parts:
        return None
    parts = [part for part in parts if part not in ('', '.')]
    normalized = '/'.join(parts)
    if normalized == 'lib/apk/db/installed':
        return 'alpine'
    if len(parts) >= 2 and parts[-1] == 'METADATA' and parts[-2].endswith('.dist-info'):
        return 'python'
    if 'node_modules' in parts and parts[-1] == 'package.json':
        return 'npm'
    return None


def records(kind, text, path):
    if kind == 'alpine':
        result = []
        for block in text.split('\n\n'):
            headers = dict(line.split(':', 1) for line in block.splitlines() if line[:2] in ('P:', 'V:', 'L:'))
            if headers.get('P') and headers.get('V'):
                result.append(dict(name=headers['P'], version=headers['V'], declared_license=headers.get('L')))
        return result
    if kind == 'python':
        headers = Parser().parsestr(text, headersonly=True)
        name, version = headers.get('Name'), headers.get('Version')
        license_value = headers.get('License-Expression') or headers.get('License')
        license_value = license_value[:512] if license_value else None
    else:
        try:
            headers = json.loads(text)
        except json.JSONDecodeError:
            return []
        if not isinstance(headers, dict):
            return []
        name, version = headers.get('name'), headers.get('version')
        license_value = headers.get('license')
    if not isinstance(name, str) or not name or not isinstance(version, str) or not version:
        return []
    return [dict(name=name, version=version, declared_license=license_value, metadata_path=path)]


def inventory(source):
    with open(source, 'rb') as source_stream:
        before = os.fstat(source_stream.fileno())
        digest = hashlib.file_digest(source_stream, 'sha256').hexdigest()
        source_stream.seek(0)
        result = dict(scope=SCOPE, source=source, source_sha256=digest,
                      source_size_bytes=before.st_size, alpine=[], python=[], npm=[])
        total_bytes = total_records = 0
        with tarfile.open(fileobj=source_stream, mode='r|gz') as archive:
            for count, entry in enumerate(archive, 1):
                try:
                    if count > MAX_ENTRIES:
                        raise ValueError('archive entry budget exceeded')
                    kind = selected_kind(entry.name)
                    if kind is None or not entry.isfile():
                        continue
                    if entry.size > MAX_ENTRY_BYTES or entry.size < 0:
                        raise ValueError('metadata entry budget exceeded')
                    total_bytes += entry.size
                    if total_bytes > MAX_TOTAL_BYTES:
                        raise ValueError('metadata byte budget exceeded')
                    with archive.extractfile(entry) as stream:
                        text = stream.read(MAX_ENTRY_BYTES + 1).decode('utf-8')
                    rows = records(kind, text, entry.name)
                    total_records += len(rows)
                    if total_records > MAX_RECORDS:
                        raise ValueError('metadata record budget exceeded')
                    result[kind].extend(rows)
                finally:
                    # Older Python stream mode retains even unselected TarInfo objects.
                    archive.members.clear()
        after = os.fstat(source_stream.fileno())
        stable_fields = ('st_dev', 'st_ino', 'st_size', 'st_mtime_ns')
        # Replacing the pathname unlinks the open snapshot, changing only its
        # link count and ctime. This is not a content mutation.
        ctime_changed = before.st_ctime_ns != after.st_ctime_ns
        unlinked_snapshot = after.st_nlink < before.st_nlink
        if (any(getattr(before, field) != getattr(after, field) for field in stable_fields)
                or (ctime_changed and not unlinked_snapshot)):
            raise ValueError('source changed')
    for kind in ('alpine', 'python', 'npm'):
        result[kind].sort(key=lambda row: (row['name'], row.get('metadata_path', ''), row['version']))
    return result


def atomic_write(destination, output):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8',
                                         dir=destination.parent,
                                         prefix='.' + destination.name + '.',
                                         delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(output)
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        output = json.dumps(inventory(args.source), indent=2, ensure_ascii=False) + '\n'
        if args.output:
            atomic_write(args.output, output)
        else:
            sys.stdout.write(output)
    except (OSError, ValueError, tarfile.TarError, EOFError, zlib.error, RecursionError):
        print('runtime inventory failed: invalid archive, metadata, or resource limit; details redacted', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

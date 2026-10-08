#!/usr/bin/env python3
"""Rebuild only pinned Termux JNI; preserve every original non-JNI entry."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path, PurePosixPath
import shutil
import stat
import struct
import subprocess
import zipfile

ABIS = {'arm64-v8a': 183, 'x86_64': 62}
NDK = '28.2.13676358'
AAR_HASH = 'cbb915c4d7c51883f85397b46b74bf4b82fd32a8bbf0baeba1e0bc6d990d3e8a'
PINS = {'termux.c': 'af9485e2f170eb91b5c5594063190727fd5d4b7932a30e32349c6fdea0b7eee5', 'Android.mk': '118312b11e9d9a3458e7139af9f56d487b4f792ad7ba3b0c423867923db55fa1', 'LICENSE.md': 'a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304', 'Apache-2.0.txt': 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'}

def digest(data): return hashlib.sha256(data).hexdigest()

def check_hash(path, expected):
    if path.is_symlink() or digest(path.read_bytes()) != expected:
        raise ValueError(f'Unpinned input: {path}')

def safe_name(name):
    if not name or '\\' in name or name.startswith('/') or '..' in name.split('/'):
        raise ValueError(f'Unsafe ZIP name: {name}')

def repack(original, output, libs, notices):
    entries = {}
    with zipfile.ZipFile(original) as z:
        if len(z.infolist()) > 10000 or sum(i.file_size for i in z.infolist()) > 100*1024*1024:
            raise ValueError('AAR budget exceeded')
        for i in z.infolist():
            safe_name(i.filename)
            if i.filename in entries or stat.S_ISLNK(i.external_attr >> 16):
                raise ValueError('Duplicate or symlink ZIP entry')
            entries[i.filename] = z.read(i)
    entries = {n: b for n, b in entries.items() if not (n == 'jni/' or n.startswith('jni/'))}
    entries.update({f'jni/{abi}/libtermux.so': data for abi, data in libs.items()})
    for n, b in notices.items():
        safe_name(n)
        if n in entries: raise ValueError('Notice overwrites original')
        entries[n] = b
    tmp = output.with_suffix('.tmp')
    try:
        with zipfile.ZipFile(tmp, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for n, b in sorted(entries.items()):
                i = zipfile.ZipInfo(n, (1980, 1, 1, 0, 0, 0))
                i.create_system = 3
                i.external_attr = (stat.S_IFREG | 0o644) << 16
                i.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(i, b)
        os.replace(tmp, output)
    finally:
        tmp.unlink(missing_ok=True)

def validate_elf(data, machine):
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', data, 18)[0] != machine:
        raise ValueError('Wrong ELF machine/class')
    off = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    loads = []
    for idx in range(count):
        kind, flags, offset, addr, _, _, _, align = struct.unpack_from('<IIQQQQQQ', data, off+idx*size)
        if kind == 1:
            if align < 16384 or offset % 16384 != addr % 16384: raise ValueError('Unaligned PT_LOAD')
            loads.append({'offset': offset, 'vaddr': addr, 'align': align})
        if kind == 0x6474e551 and flags & 1: raise ValueError('Executable stack')
    if not loads: raise ValueError('Missing PT_LOAD')
    return loads

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input-aar', type=Path, required=True)
    ap.add_argument('--output-dir', type=Path, required=True)
    ap.add_argument('--ndk-dir', type=Path, required=True)
    args = ap.parse_args()
    source = Path(__file__).resolve().parents[1]/'third_party/termux-native'
    check_hash(args.input_aar, AAR_HASH)
    for n, h in PINS.items(): check_hash(source/n, h)
    props = (args.ndk_dir/'source.properties').read_text()
    if f'Pkg.Revision = {NDK}' not in props: raise ValueError('Required NDK r28c missing; install explicitly')
    out = args.output_dir.resolve()
    if args.output_dir.is_symlink() or 'build' not in out.parts: raise ValueError('Output must be isolated under build')
    out.mkdir(parents=True, exist_ok=True)
    for n in ('obj', 'libs', 'source'):
        p = out/n
        if p.is_symlink(): raise ValueError('Symlink output')
        if p.exists(): shutil.rmtree(p)
    staged = out/'source'; staged.mkdir()
    for n in ('termux.c', 'Android.mk'): shutil.copyfile(source/n, staged/n)
    subprocess.run([str(args.ndk_dir/'ndk-build'), 'NDK_PROJECT_PATH=null', f'APP_BUILD_SCRIPT={staged}/Android.mk', f'NDK_OUT={out}/obj', f'NDK_LIBS_OUT={out}/libs', 'APP_ABI=arm64-v8a x86_64', 'APP_PLATFORM=android-24', 'APP_OPTIM=release', 'APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true', 'APP_CFLAGS=-std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector', 'APP_LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'], check=True)
    tools = args.ndk_dir/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    libs = {}; reports = {}
    for abi, machine in ABIS.items():
        p = out/'libs'/abi/'libtermux.so'; data = p.read_bytes()
        loads = validate_elf(data, machine)
        dynamic = subprocess.check_output([str(tools/'llvm-readelf'), '-d', str(p)], text=True)
        symbols = subprocess.check_output([str(tools/'llvm-nm'), '-D', '--defined-only', str(p)], text=True)
        for method in ('createSubprocess', 'setPtyWindowSize', 'waitFor', 'close', 'setPtyUTF8Mode'):
            if f'Java_com_termux_terminal_JNI_{method}' not in symbols: raise ValueError('Missing JNI export')
        if any(s in dynamic for s in ('RPATH', 'RUNPATH', 'TEXTREL')) or '[libtermux.so]' not in dynamic:
            raise ValueError('Unsafe ELF dynamic contract')
        needed = set(re.findall(r'\(NEEDED\).*?\[([^]]+)\]', dynamic))
        if needed != {'libc.so', 'libm.so', 'libdl.so'}:
            raise ValueError(f'Unexpected DT_NEEDED: {needed}')
        exports = set(re.findall(r'Java_com_termux_terminal_JNI_\w+', symbols))
        if len(exports) != 5: raise ValueError('Unexpected JNI exports')
        libs[abi] = data
        reports[abi] = {'sha256': digest(data), 'loads': loads, 'dynamic': dynamic, 'symbols': symbols}
    notices = {f'META-INF/termux-native/{n}': (source/n).read_bytes() for n in PINS}
    repack(args.input_aar, out/'terminal-emulator-0.118.3.aar', libs, notices)
    (out/'provenance.json').write_text(json.dumps({'ndk': NDK, 'ndk_properties_sha256': digest(props.encode()), 'original_aar': AAR_HASH, 'sources': PINS, 'outputs': reports, 'aar_sha256': digest((out/'terminal-emulator-0.118.3.aar').read_bytes())}, indent=2)+'\n')

if __name__ == '__main__': main()

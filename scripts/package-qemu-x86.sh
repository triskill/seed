#!/usr/bin/env bash
# Package the Android/ARM64 QEMU user-mode binary used to execute an x86_64
# guest rootfs under PRoot.  Android only permits executable files installed as
# native libraries, so every file is staged as an unversioned lib*.so in the
# selected jniLibs ABI directory.  Versioned Termux DT_NEEDED names are
# rewritten to those names before publication.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$REPO_ROOT/android/app/src/main/jniLibs}"
ABI_DIR="$JNI_LIBS_DIR/arm64-v8a"
TERMUX_BASE="https://packages.termux.dev/apt/termux-main"
BUILD_DIR="$(mktemp -d -t seed-qemu-x86.XXXXXXXXXX)"
STAGE_DIR=""

cleanup() {
    rm -rf "$BUILD_DIR"
    [[ -z "$STAGE_DIR" ]] || rm -rf "$STAGE_DIR"
}
trap cleanup EXIT

command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }
command -v ar >/dev/null || { echo "ar required (install binutils)" >&2; exit 1; }
command -v tar >/dev/null || { echo "tar required" >&2; exit 1; }
command -v readelf >/dev/null || { echo "readelf required (install binutils)" >&2; exit 1; }
command -v file >/dev/null || { echo "file required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }

# name | Termux repository path | SHA256.  These are the exact ARM64 Android
# QEMU 11.0.3 runtime closure, determined from recursively following DT_NEEDED.
PACKAGES=(
'argp|pool/main/a/argp/argp_1.5.0-1_aarch64.deb|4096bfb8cba379efc3699e94e1cee400e72c274d734b5c1ed5f274ec42003405'
'glib|pool/main/g/glib/glib_2.88.3_aarch64.deb|b1bfc40c4cfde83470de9efac1e546fdf643b77a966c20a8612bddc7c59cd3dc'
'libandroid-shmem|pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb|0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6'
'libandroid-support|pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb|f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517'
'libbz2|pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb|4335d7f060650b0aabef545d1334c2f9f280223d5962e13c24a00ec934b794ba'
'libdw|pool/main/libd/libdw/libdw_0.196_aarch64.deb|2486ab488174ac4a1520f9d5e3e7dbf41a9c54733489e10c2ca24e2de26508f8'
'libelf|pool/main/libe/libelf/libelf_0.196_aarch64.deb|cbb0be8f278ae737fdb1e00ffee29444833fe90a051d485d262bcf5b4403a713'
'libffi|pool/main/libf/libffi/libffi_3.8.0_aarch64.deb|4f255badf74cd31f6a2801c17fa1444199c84c834b517b7c843e2fe9ebe91d77'
'libgmp|pool/main/libg/libgmp/libgmp_6.3.0-2_aarch64.deb|5a3c1325638946ca212ddcb89bffb2c4459b4c90757d6e69f820e176534037fa'
'libgnutls|pool/main/libg/libgnutls/libgnutls_3.8.13-1_aarch64.deb|eb12f2cf82923caf288edd99bc4c19a9dd0cf1de4a03855ff4457a1bbdae146d'
'libiconv|pool/main/libi/libiconv/libiconv_1.18-1_aarch64.deb|b19e6f348034bb48d2a5590b5cb242769f682c476717374d134d004cc663dc84'
'libidn2|pool/main/libi/libidn2/libidn2_2.3.8-1_aarch64.deb|a450a1ba25759ebf78738484a3efee316c51a1fe7bafb0b01a68e2c058a91020'
'liblzma|pool/main/libl/liblzma/liblzma_5.8.3_aarch64.deb|594925a313879f590fbd24050305551a78eadd9a9319f6e612389b1a521113c6'
'libnettle|pool/main/libn/libnettle/libnettle_4.0+really3.10.2_aarch64.deb|f2d2a84083e66beab642e88496eb16976ccdb12d1278466e687f01431db8a5fb'
'libpixman|pool/main/libp/libpixman/libpixman_0.46.4-1_aarch64.deb|364b8c82ff68f8513930e5d413459fb7c52eccc7ba75a97a8c5a38760eee8d33'
'libtasn1|pool/main/libt/libtasn1/libtasn1_4.21.0_aarch64.deb|1d02de3a7e5ef4ff7d893597091176b8ddf135f1ddfacff951f76c18711a7342'
'libunistring|pool/main/libu/libunistring/libunistring_1.4.2_aarch64.deb|5ff75cdf3ddd4ddf5dc9705050f270c3422820295a4b26f93496d3e8e9060122'
'p11-kit|pool/main/p/p11-kit/p11-kit_0.26.5_aarch64.deb|7911d7427e3c7182cf364e56a45a92307e5e7fddc105dc468f25ae39aa3e0ad7'
'pcre2|pool/main/p/pcre2/pcre2_10.47_aarch64.deb|51f915d22de639bfca6ec029ae613987bbe3bc73626eede13319fd2e95f50b63'
'qemu-user-x86-64|pool/main/q/qemu-user-x86-64/qemu-user-x86-64_1:11.0.3_aarch64.deb|92623b49e6c470fe59f54ab3d1ed876378c59f21f5b2a6a77ba0e59639a5ada4'
'zlib|pool/main/z/zlib/zlib_1.3.2_aarch64.deb|75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9'
'zstd|pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb|e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3'
)

mkdir -p "$BUILD_DIR/packages" "$BUILD_DIR/extracted" "$ABI_DIR"
for entry in "${PACKAGES[@]}"; do
    IFS='|' read -r name package_path sha <<<"$entry"
    archive="$BUILD_DIR/packages/$name.deb"
    echo "→ fetching pinned Termux $name"
    curl -fsSL -o "$archive" "$TERMUX_BASE/$package_path"
    printf '%s  %s\n' "$sha" "$archive" | sha256sum -c - >/dev/null
    mkdir -p "$BUILD_DIR/extracted/$name"
    ar p "$archive" data.tar.xz | tar -xJf - -C "$BUILD_DIR/extracted/$name"
done

# Use one staging directory inside jniLibs so publishing is a sequence of
# atomic renames.  The exact real files (not symlinks) are copied below.
STAGE_DIR="$(mktemp -d "$JNI_LIBS_DIR/.seed-qemu-stage.XXXXXXXXXX")"
TERMUX_LIB='data/data/com.termux/files/usr/lib'
TERMUX_BIN='data/data/com.termux/files/usr/bin'
copy_file() {
    local package="$1" source="$2" destination="$3"
    [[ -f "$BUILD_DIR/extracted/$package/$source" && ! -L "$BUILD_DIR/extracted/$package/$source" ]] \
        || { echo "missing regular Termux file: $package/$source" >&2; exit 1; }
    cp "$BUILD_DIR/extracted/$package/$source" "$STAGE_DIR/$destination"
}

copy_file qemu-user-x86-64 "$TERMUX_BIN/qemu-x86_64" libqemu-x86-64.so
copy_file libandroid-shmem "$TERMUX_LIB/libandroid-shmem.so" libandroid-shmem.so
copy_file libandroid-support "$TERMUX_LIB/libandroid-support.so" libandroid-support.so
copy_file argp "$TERMUX_LIB/libargp.so" libargp.so
copy_file libbz2 "$TERMUX_LIB/libbz2.so.1.0.8" libbz2.so
copy_file libdw "$TERMUX_LIB/libdw-0.196.so" libdw.so
copy_file libelf "$TERMUX_LIB/libelf-0.196.so" libelf.so
copy_file libffi "$TERMUX_LIB/libffi.so" libffi.so
copy_file glib "$TERMUX_LIB/libglib-2.0.so.0.8800.3" libglib-2.0.so
copy_file glib "$TERMUX_LIB/libgmodule-2.0.so.0.8800.3" libgmodule-2.0.so
copy_file libgmp "$TERMUX_LIB/libgmp.so" libgmp.so
copy_file libgnutls "$TERMUX_LIB/libgnutls.so" libgnutls.so
copy_file libiconv "$TERMUX_LIB/libiconv.so" libiconv.so
copy_file libidn2 "$TERMUX_LIB/libidn2.so" libidn2.so
copy_file liblzma "$TERMUX_LIB/liblzma.so.5.8.3" liblzma.so
copy_file libnettle "$TERMUX_LIB/libhogweed.so.6.11" libhogweed.so
copy_file libnettle "$TERMUX_LIB/libnettle.so.8.11" libnettle.so
copy_file p11-kit "$TERMUX_LIB/libp11-kit.so" libp11-kit.so
copy_file pcre2 "$TERMUX_LIB/libpcre2-8.so" libpcre2-8.so
copy_file libpixman "$TERMUX_LIB/libpixman-1.so" libpixman-1.so
copy_file libtasn1 "$TERMUX_LIB/libtasn1.so" libtasn1.so
copy_file libunistring "$TERMUX_LIB/libunistring.so" libunistring.so
copy_file zlib "$TERMUX_LIB/libz.so.1.3.2" libz.so
copy_file zstd "$TERMUX_LIB/libzstd.so.1.5.7" libzstd.so

# Android's native-library installer ignores libfoo.so.N.  Rewrite every
# dependency name to the unversioned file name we package.  Replacements only
# shrink strings, so padding maintains the ELF string-table layout.
python3 - "$STAGE_DIR" <<'PY'
from pathlib import Path
import sys

mapping = {
    b"libbz2.so.1.0": b"libbz2.so",
    b"libdw.so.1": b"libdw.so",
    b"libelf.so.1": b"libelf.so",
    b"libglib-2.0.so.0": b"libglib-2.0.so",
    b"libgmodule-2.0.so.0": b"libgmodule-2.0.so",
    b"libhogweed.so.6": b"libhogweed.so",
    b"liblzma.so.5": b"liblzma.so",
    b"libnettle.so.8": b"libnettle.so",
    b"libz.so.1": b"libz.so",
    b"libzstd.so.1": b"libzstd.so",
}
for path in Path(sys.argv[1]).iterdir():
    data = path.read_bytes()
    for old, new in mapping.items():
        source = old + b"\0"
        replacement = new + b"\0" * (len(source) - len(new))
        data = data.replace(source, replacement)
    path.write_bytes(data)
PY

for library in "$STAGE_DIR"/*.so; do
    LC_ALL=C file -Lb "$library" | grep -q 'ELF 64-bit.*ARM aarch64' \
        || { echo "not an ARM64 Android library: $library" >&2; exit 1; }
done
LC_ALL=C file -Lb "$STAGE_DIR/libqemu-x86-64.so" | grep -q '/system/bin/linker64' \
    || { echo "QEMU is not linked for Android's linker64" >&2; exit 1; }

# Ensure all non-Bionic dependencies resolve to the staged unversioned closure.
python3 - "$STAGE_DIR" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

stage = Path(sys.argv[1])
provided = {p.name for p in stage.glob("*.so")}
# Android's bionic / framework libraries are supplied by the device, not APK.
system = {"libc.so", "libdl.so", "libm.so", "liblog.so"}
for path in stage.glob("*.so"):
    output = subprocess.check_output(["readelf", "-d", str(path)], text=True)
    needed = re.findall(r"Shared library: \[([^]]+)\]", output)
    missing = [name for name in needed if name not in provided | system]
    if missing:
        raise SystemExit(f"{path.name} has unstaged dependencies: {', '.join(missing)}")
    versioned = [name for name in needed if ".so." in name]
    if versioned:
        raise SystemExit(f"{path.name} retains versioned Android-unpackable dependencies: {', '.join(versioned)}")
PY

# Publish only QEMU-related files.  Preserve the ARM PRoot/loader/talloc that
# are already in this ABI directory.
for staged in "$STAGE_DIR"/*.so; do
    mv "$staged" "$ABI_DIR/$(basename "$staged")"
done
rm -rf "$STAGE_DIR"
STAGE_DIR=""

echo "✓ packaged ARM64 Android qemu-x86_64 + dependency closure"
ls -lh "$ABI_DIR/libqemu-x86-64.so"

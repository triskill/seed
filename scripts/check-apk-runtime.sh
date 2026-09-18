#!/usr/bin/env bash
# Verify that an APK contains exactly one direct-native Seed runtime bundle.
# Usage: scripts/check-apk-runtime.sh path/to/app.apk <arm64-v8a|x86_64>
set -euo pipefail
if [[ $# -ne 2 ]]; then echo "usage: $0 <apk> <arm64-v8a|x86_64>" >&2; exit 2; fi
apk=$1
expected_abi=$2
case "$expected_abi" in arm64-v8a|x86_64) ;; *) echo "unsupported ABI: $expected_abi" >&2; exit 2 ;; esac
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip required" >&2; exit 1; }
listing=$(unzip -Z1 "$apk")
# No foreign ABI and no removed QEMU compatibility closure may ship.
if grep -Eq "(^|/)lib/(arm64-v8a|x86_64)/" <<<"$listing" &&    grep -Eq "(^|/)lib/(arm64-v8a|x86_64)/" <<<"$listing" | grep -Fv "/$expected_abi/" >/dev/null; then
    echo "APK contains a runtime ABI other than $expected_abi" >&2
    grep -E "(^|/)lib/(arm64-v8a|x86_64)/" <<<"$listing" >&2 || true
    exit 1
fi
if grep -Eq '(^|/)libqemu-x86-64\.so$|(^|/)libandroid-support\.so$|(^|/)lib(argp|bz2|dw|elf|ffi|glib|gmodule|gmp|gnutls|hogweed|iconv|idn2|lzma|nettle|p11-kit|pcre2-8|pixman-1|tasn1|unistring|z|zstd)\.so$' <<<"$listing"; then
    echo "APK contains removed QEMU runtime files" >&2
    exit 1
fi
for lib in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
    grep -Fqx "lib/$expected_abi/$lib" <<<"$listing" || {
        echo "APK is missing $expected_abi runtime file: $lib" >&2; exit 1;
    }
done
echo "APK runtime inventory matches $expected_abi"

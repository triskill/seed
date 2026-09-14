#!/usr/bin/env bash
# Check that an APK contains only Seed's native ARM64 runtime bundle.
# Usage: scripts/check-apk-runtime.sh path/to/app.apk
set -euo pipefail
if [[ $# -ne 1 ]]; then echo "usage: $0 <apk>" >&2; exit 2; fi
apk=$1
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip required" >&2; exit 1; }
listing=$(unzip -Z1 "$apk")
# No alternate ABI and no QEMU/dependency closure can ship in a native APK.
if grep -Eq '(^|/)lib/x86_64/|(^|/)libqemu-x86-64\.so$|(^|/)libandroid-support\.so$|(^|/)lib(argp|bz2|dw|elf|ffi|glib|gmodule|gmp|gnutls|hogweed|iconv|idn2|lzma|nettle|p11-kit|pcre2-8|pixman-1|tasn1|unistring|z|zstd)\.so$' <<<"$listing"; then
    echo "APK contains a foreign ABI or QEMU runtime file" >&2
    grep -E 'x86_64|qemu|libandroid-support|lib(argp|bz2|dw|elf|ffi|glib|gmodule|gmp|gnutls|hogweed|iconv|idn2|lzma|nettle|p11-kit|pcre2-8|pixman-1|tasn1|unistring|z|zstd)\.so' <<<"$listing" >&2 || true
    exit 1
fi
for lib in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
    grep -Fqx "lib/arm64-v8a/$lib" <<<"$listing" || {
        echo "APK is missing native ARM64 runtime file: $lib" >&2; exit 1;
    }
done
echo "APK runtime inventory is native ARM64 only"

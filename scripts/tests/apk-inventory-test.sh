#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
for abi in arm64-v8a x86_64; do
  mkdir -p "$tmp/$abi/lib/$abi"
  touch "$tmp/$abi/lib/$abi/libproot.so" "$tmp/$abi/lib/$abi/libproot-loader.so"     "$tmp/$abi/lib/$abi/libtalloc.so" "$tmp/$abi/lib/$abi/libandroid-shmem.so"
  (cd "$tmp/$abi" && zip -q "$tmp/$abi.apk" lib/$abi/*.so)
  "$ROOT/scripts/check-apk-runtime.sh" "$tmp/$abi.apk" "$abi" >/dev/null
done
mkdir -p "$tmp/mixed/lib/x86_64" "$tmp/mixed/lib/arm64-v8a"
touch "$tmp/mixed/lib/x86_64/libproot.so" "$tmp/mixed/lib/arm64-v8a/libproot.so"
(cd "$tmp/mixed" && zip -q "$tmp/mixed.apk" lib/*/*.so)
if "$ROOT/scripts/check-apk-runtime.sh" "$tmp/mixed.apk" x86_64 >/dev/null 2>&1; then
  echo 'FAIL: mixed ABI APK accepted' >&2; exit 1
fi
echo 'PASS: direct-native APK inventory'

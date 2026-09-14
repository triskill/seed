#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/lib/arm64-v8a"
touch "$tmp/lib/arm64-v8a/libproot.so" "$tmp/lib/arm64-v8a/libproot-loader.so" "$tmp/lib/arm64-v8a/libtalloc.so" "$tmp/lib/arm64-v8a/libandroid-shmem.so"
(cd "$tmp" && zip -q "$tmp/native.apk" lib/arm64-v8a/*.so)
"$ROOT/scripts/check-apk-runtime.sh" "$tmp/native.apk" >/dev/null
mkdir -p "$tmp/lib/x86_64"; touch "$tmp/lib/x86_64/libproot.so"
(cd "$tmp" && zip -q "$tmp/foreign.apk" lib/x86_64/libproot.so)
if "$ROOT/scripts/check-apk-runtime.sh" "$tmp/foreign.apk" >/dev/null 2>&1; then
  echo 'FAIL: foreign ABI APK accepted' >&2; exit 1
fi
echo 'PASS: APK native ARM64 inventory'

#!/usr/bin/env bash
# Build Seed's QEMU compatibility runtime for an ARM64 Android phone:
# native ARM64 PRoot + native ARM64 qemu-x86_64 + x86_64 Alpine rootfs.
#
# build-runtime.sh intentionally couples its selected native ABI and rootfs.
# QEMU mode must decouple them, so we build/export the x86_64 rootfs first,
# rebuild the ARM64 native PRoot bundle, then publish the saved x86 rootfs and
# its guest_arch marker.  The ARM64 QEMU binary is finally added to jniLibs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="${ASSETS_DIR:-$REPO_ROOT/android/app/src/main/assets/linux}"
BUILD_DIR="$(mktemp -d -t seed-qemu-x86-runtime.XXXXXXXXXX)"
X86_ASSETS="$BUILD_DIR/x86-assets"

cleanup() { rm -rf "$BUILD_DIR"; }
trap cleanup EXIT

[[ -x "$REPO_ROOT/scripts/build-runtime.sh" ]] || {
    echo "missing build-runtime.sh" >&2; exit 1;
}
[[ -x "$REPO_ROOT/scripts/package-qemu-x86.sh" ]] || {
    echo "missing package-qemu-x86.sh" >&2; exit 1;
}

mkdir -p "$X86_ASSETS"
echo "→ building x86_64 Alpine guest rootfs"
RUNTIME_ARCH=x86_64 "$REPO_ROOT/scripts/build-runtime.sh"
cp "$ASSETS_DIR/rootfs.tar.gz" "$X86_ASSETS/rootfs.tar.gz"
cp "$ASSETS_DIR/seed_version.json" "$X86_ASSETS/seed_version.json"

echo "→ restoring ARM64 native PRoot host bundle"
RUNTIME_ARCH=arm64 "$REPO_ROOT/scripts/build-runtime.sh"

# Preserve the x86 build id but make its architecture explicit.  RootfsVersion
# uses this as part of its equality check, forcing fresh extraction after a
# native <-> QEMU runtime mode switch.
python3 - "$X86_ASSETS/seed_version.json" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = json.loads(path.read_text())
data["guest_arch"] = "x86_64"
path.write_text(json.dumps(data, indent=2) + "\n")
PY

# Publish data assets last, after all generated native files validate.
mv "$X86_ASSETS/rootfs.tar.gz" "$ASSETS_DIR/rootfs.tar.gz"
mv "$X86_ASSETS/seed_version.json" "$ASSETS_DIR/seed_version.json"

echo "→ packaging ARM64 Android qemu-x86_64"
"$REPO_ROOT/scripts/package-qemu-x86.sh"

echo ""
echo "✓ QEMU x86_64 phone runtime built"
echo "  host: ARM64 PRoot + qemu-x86_64"
echo "  guest: x86_64 Alpine rootfs"
cat "$ASSETS_DIR/seed_version.json"

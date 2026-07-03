#!/usr/bin/env bash
# scripts/build-runtime.sh — Build the Seed Android runtime.
#
# Produces:
#   android/app/src/main/assets/linux/proot         (~2 MB, arm64)
#   android/app/src/main/assets/linux/rootfs.tar.gz  (~150 MB, Alpine + python + node + pi + backend + webapp)
#   android/app/src/main/assets/linux/seed_version.json
#
# Requires: docker, curl, tar. On x86_64 hosts, arm64 emulation
# (QEMU + binfmt_misc) must be available — see docs/build-runtime.md.
# Idempotent: re-running rebuilds from scratch in runtime-build/.
# Net effect on the assets dir: proot + rootfs.tar.gz are overwritten;
# seed_version.json is overwritten with a fresh build_id.
#
# Run from repo root: ./scripts/build-runtime.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/android/app/src/main/assets/linux"
BUILD_DIR="$REPO_ROOT/runtime-build"
ROOTFS_DIR="$BUILD_DIR/rootfs"
ROOTFS_TAR_IN="$BUILD_DIR/rootfs-in.tar.gz"
ROOTFS_TAR_OUT="$BUILD_DIR/rootfs-out.tar.gz"

PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
ALPINE_SHA="041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de"

# ---- Preflight ----
command -v docker >/dev/null || { echo "docker required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }
mkdir -p "$ASSETS_DIR" "$BUILD_DIR"

# ---- Compute build_id (timestamp + script hash) ----
BUILD_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(sha256sum "$0" | cut -c1-8)"

# ---- Step A: fetch proot (arm64 static) ----
if [ ! -f "$ASSETS_DIR/proot" ]; then
    echo "→ fetching proot"
    curl -fsSL -o "$ASSETS_DIR/proot" "$PROOT_URL"
    chmod +x "$ASSETS_DIR/proot"
fi
# Sanity: must be an aarch64 ELF
file "$ASSETS_DIR/proot" | grep -q "aarch64" || { echo "proot is not aarch64" >&2; exit 1; }

# ---- Step B: fetch + verify Alpine minirootfs (aarch64) ----
echo "→ fetching Alpine minirootfs"
curl -fsSL -o "$ROOTFS_TAR_IN" "$ALPINE_URL"
echo "$ALPINE_SHA  $ROOTFS_TAR_IN" | sha256sum -c - || { echo "Alpine sha256 mismatch" >&2; exit 1; }

# ---- Step C: build the expanded rootfs in a Docker arm64 container ----
echo "→ expanding rootfs in arm64 container (this takes a few minutes on first run)"
rm -rf "$ROOTFS_DIR"
mkdir -p "$ROOTFS_DIR"
tar -xzf "$ROOTFS_TAR_IN" -C "$ROOTFS_DIR"

# Run all the package installs inside a one-shot arm64 container.
# Bind-mount the expanded rootfs, run apk + npm inside, but target
# the bind mount with apk --root and npm --prefix so the installs
# actually land in $ROOTFS_DIR (the container is --rm'd when done,
# so anything installed to /usr inside the container is discarded).
docker run --rm \
    --platform linux/arm64 \
    -v "$ROOTFS_DIR":/rootfs \
    arm64v8/alpine:3.20 \
    sh -euxc '
        apk add --root=/rootfs --no-cache --update python3 py3-pip nodejs npm git tmux
        npm install -g --prefix=/rootfs/usr/local @earendil-works/pi-coding-agent
    '

# Sanity: verify the runtime deps actually landed in the rootfs.
# (Catches bind-mount typos, apk index drift, npm registry hiccups.)
test -x "$ROOTFS_DIR/usr/bin/python3" || { echo "python3 not installed in rootfs" >&2; exit 1; }
test -x "$ROOTFS_DIR/usr/bin/node"    || { echo "node not installed in rootfs" >&2; exit 1; }
test -x "$ROOTFS_DIR/usr/local/bin/pi" || { echo "pi not installed in rootfs" >&2; exit 1; }

# ---- Step D: copy backend + webapp into rootfs ----
# We do this outside Docker to avoid bind-mounting the whole repo.
cp -r "$REPO_ROOT/backend" "$ROOTFS_DIR/home/seed/backend"
cp -r "$REPO_ROOT/webapp" "$ROOTFS_DIR/home/seed/app"
(cd "$ROOTFS_DIR/home/seed/app" && git init -q)
# Seed backend config.json with the in-runtime defaults.
cat > "$ROOTFS_DIR/home/seed/backend/config.json" <<'JSON'
{
  "model": "deepseek-v4-flash",
  "provider": "opencode-go",
  "ports": {"backend": 7777, "flask": 7778},
  "logLevel": "info"
}
JSON

# ---- Step E: re-tar the rootfs ----
echo "→ re-tarring rootfs"
tar -czf "$ROOTFS_TAR_OUT" -C "$ROOTFS_DIR" .
mv "$ROOTFS_TAR_OUT" "$ASSETS_DIR/rootfs.tar.gz"
rm -rf "$ROOTFS_DIR" "$ROOTFS_TAR_IN"

# ---- Step F: write seed_version.json ----
cat > "$ASSETS_DIR/seed_version.json" <<JSON
{
  "seed_version": "0.1.0",
  "build_id": "$BUILD_ID"
}
JSON

# ---- Step G: clean up ----
rm -rf "$BUILD_DIR"

echo ""
echo "✓ runtime built → $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
echo "  build_id = $BUILD_ID"

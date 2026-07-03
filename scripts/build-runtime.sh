#!/usr/bin/env bash
# scripts/build-runtime.sh — Build the Seed Android runtime.
#
# Produces:
#   android/app/src/main/assets/linux/proot         (~2 MB, arm64)
#   android/app/src/main/assets/linux/rootfs.tar.gz  (~150 MB, Alpine + python + node + pi + backend + webapp)
#   android/app/src/main/assets/linux/seed_version.json
#
# Requires: docker (with buildx / arm64 support), curl, tar.
# Idempotent: re-running rebuilds from scratch in a fresh mktemp dir.
# Net effect on the assets dir: proot + rootfs.tar.gz are overwritten;
# seed_version.json is overwritten with a fresh build_id.
#
# Run from repo root: ./scripts/build-runtime.sh
#
# Why a Dockerfile + docker buildx + docker export: an earlier version
# of this script tried to build the rootfs by running apk + npm inside
# a one-shot `docker run` with a bind-mount of the host's rootfs dir
# (`apk add --root=/rootfs ...`). That failed because the dynamically-
# linked `node` binary couldn't find libicu at runtime — the bind mount
# only exposes /rootfs at one path, but the dynamic linker uses absolute
# RUNPATHs that don't go through the bind. The Dockerfile approach
# installs everything into the container's own filesystem (where
# shared-library resolution Just Works), then exports the container's
# root filesystem as a tarball.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/android/app/src/main/assets/linux"
# Fresh temp dir each run (so we never have to rm -rf a dir containing
# root-owned files left by a previous interrupted docker invocation).
BUILD_DIR="$(mktemp -d -t seed-runtime-build.XXXXXXXXXX)"
ROOTFS_TAR_OUT="$BUILD_DIR/rootfs-out.tar.gz"

PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
ALPINE_SHA="041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de"
DOCKER_IMAGE_TAG="seed-runtime:$(date +%s)"
DOCKER_CONTAINER_NAME="seed-runtime-export-$$"

# ---- Preflight ----
command -v docker >/dev/null || { echo "docker required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }
mkdir -p "$ASSETS_DIR"

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
ROOTFS_TAR_IN="$BUILD_DIR/rootfs-in.tar.gz"
curl -fsSL -o "$ROOTFS_TAR_IN" "$ALPINE_URL"
echo "$ALPINE_SHA  $ROOTFS_TAR_IN" | sha256sum -c - || { echo "Alpine sha256 mismatch" >&2; exit 1; }

# ---- Step C: build the runtime in a Dockerfile (arm64) ----
# Layout: copy backend + webapp into the build context so the
# Dockerfile can COPY them in (no bind-mount games needed).
echo "→ preparing build context (backend + webapp)"
mkdir -p "$BUILD_DIR/ctx"
cp -r "$REPO_ROOT/backend" "$BUILD_DIR/ctx/backend"
cp -r "$REPO_ROOT/webapp" "$BUILD_DIR/ctx/webapp"

cat > "$BUILD_DIR/ctx/Dockerfile" <<'DOCKERFILE'
# Pinned for reproducible builds. Update both pins together when bumping:
#   - base image: arm64v8/alpine 3.20.x (must match ALPINE_URL below)
#   - pi: @earendil-works/pi-coding-agent <exact version> (run `npm view` to check)
# Last bumped: 2026-07-03, pi 0.80.3, alpine 3.20.3.
# Full digest pinning is a v0.2 follow-up.
FROM arm64v8/alpine:3.20.3
RUN apk add --no-cache --update python3 py3-pip nodejs npm git tmux
RUN npm install -g @earendil-works/pi-coding-agent@0.80.3
COPY backend /home/seed/backend
COPY webapp /home/seed/app
RUN cd /home/seed/app && git init -q
RUN printf '%s' '{"model":"deepseek-v4-flash","provider":"opencode-go","ports":{"backend":7777,"flask":7778},"logLevel":"info"}' > /home/seed/backend/config.json
DOCKERFILE

echo "→ building runtime image in arm64 container (this takes a few minutes on first run)"
docker buildx build --platform linux/arm64 --load --tag "$DOCKER_IMAGE_TAG" "$BUILD_DIR/ctx"

# Sanity: verify the image was actually built for arm64.
docker image inspect "$DOCKER_IMAGE_TAG" --format '{{.Architecture}}' | grep -q "arm64" \
    || { echo "image is not arm64" >&2; exit 1; }

# ---- Step D: export the container's filesystem to a tarball ----
echo "→ exporting rootfs from container"
docker create --platform linux/arm64 --name "$DOCKER_CONTAINER_NAME" "$DOCKER_IMAGE_TAG"
# `docker export` writes an uncompressed tar stream (not gzipped).
# We pipe through gzip to produce the .tar.gz the app expects.
docker export "$DOCKER_CONTAINER_NAME" | gzip > "$ROOTFS_TAR_OUT"
docker rm "$DOCKER_CONTAINER_NAME" >/dev/null
docker rmi "$DOCKER_IMAGE_TAG" >/dev/null

# Sanity: verify the runtime deps actually landed in the tarball.
# We dump the full listing to a file first, then grep that file.
# Why not pipe `tar | grep -q`? With `set -o pipefail`, grep -q
# exits as soon as it matches, which closes the pipe and gives
# tar a SIGPIPE — the pipeline then fails even though the match
# was found. Using a listing file avoids the pipe entirely.
echo "→ verifying runtime deps in tarball"
TAR_LISTING="$BUILD_DIR/listing.txt"
tar -tzf "$ROOTFS_TAR_OUT" > "$TAR_LISTING"
grep -qE '^(\./)?usr/bin/python3(\.[0-9]+)?$' "$TAR_LISTING" \
    || { echo "python3 not in rootfs; first 5 entries:" >&2; head -5 "$TAR_LISTING" >&2; exit 1; }
grep -qE '^(\./)?usr/bin/node$' "$TAR_LISTING" \
    || { echo "node not in rootfs" >&2; exit 1; }
grep -qE '^(\./)?usr/local/bin/pi$' "$TAR_LISTING" \
    || { echo "pi not in rootfs" >&2; exit 1; }
grep -qE '^(\./)?home/seed/backend/seed_backend/' "$TAR_LISTING" \
    || { echo "backend not in rootfs" >&2; exit 1; }
grep -qE '^(\./)?home/seed/app/seed_app/' "$TAR_LISTING" \
    || { echo "webapp not in rootfs" >&2; exit 1; }

# ---- Step E: move tarball into the assets dir ----
mv "$ROOTFS_TAR_OUT" "$ASSETS_DIR/rootfs.tar.gz"

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

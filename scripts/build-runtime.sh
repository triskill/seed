#!/usr/bin/env bash
# scripts/build-runtime.sh — Build the Seed Android runtime.
#
# Produces:
#   android/app/src/main/jniLibs/<abi>/libproot.so
#   android/app/src/main/jniLibs/<abi>/libproot-loader.so
#   android/app/src/main/assets/linux/rootfs.tar.gz   (~150 MB, Alpine + python + node + pi + backend + webapp)
#   android/app/src/main/assets/linux/seed_version.json
#
# Set RUNTIME_ARCH=arm64 (default) or RUNTIME_ARCH=x86_64.
# Requires: docker (with buildx support for the selected target), curl, dd,
# file, sha256sum, tar.
# Idempotent: re-running rebuilds from scratch in fresh temporary dirs.
# Native and asset outputs use separate staging directories inside their
# destination roots, so each publication rename is atomic even when a root is a
# mount point or symlink. The multi-file publication is not globally atomic;
# seed_version.json is published last as its completion marker.
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
# shellcheck source=runtime-target.sh
source "$REPO_ROOT/scripts/runtime-target.sh"
configure_runtime_target "${RUNTIME_ARCH:-arm64}"

ASSETS_DIR="${ASSETS_DIR:-$REPO_ROOT/android/app/src/main/assets/linux}"
JNI_LIBS_DIR="${JNI_LIBS_DIR:-$REPO_ROOT/android/app/src/main/jniLibs}"
SELECTED_PROOT="$JNI_LIBS_DIR/$PROOT_JNI_RELATIVE_PATH"
SELECTED_PROOT_LOADER="$JNI_LIBS_DIR/$PROOT_LOADER_JNI_RELATIVE_PATH"
case "$ANDROID_ABI" in
    arm64-v8a) OPPOSITE_ANDROID_ABI="x86_64" ;;
    x86_64) OPPOSITE_ANDROID_ABI="arm64-v8a" ;;
esac
OPPOSITE_PROOT="$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI/libproot.so"
OPPOSITE_PROOT_LOADER="$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI/libproot-loader.so"
DOCKER_IMAGE_TAG="seed-runtime:$(date +%s)"
DOCKER_CONTAINER_NAME="seed-runtime-export-$$"
BUILD_DIR=""
STAGING_DIR=""
PROOT_STAGING_DIR=""
CREATED_JNI_LIBS_DIR=0

# Always clean up — even on error or signal. Variables and commands are
# guarded so validation and preflight failures are safe before Docker or
# the temporary build directory are available.
cleanup() {
    if command -v docker >/dev/null 2>&1; then
        docker rm -f "$DOCKER_CONTAINER_NAME" >/dev/null 2>&1 || true
        docker rmi -f "$DOCKER_IMAGE_TAG" >/dev/null 2>&1 || true
    fi
    if [[ -n "$BUILD_DIR" ]]; then
        rm -rf "$BUILD_DIR"
    fi
    if [[ -n "$STAGING_DIR" ]]; then
        rm -rf "$STAGING_DIR"
    fi
    if [[ -n "$PROOT_STAGING_DIR" ]]; then
        rm -rf "$PROOT_STAGING_DIR"
    fi
    # If this run created the destination root, remove it only when it is still
    # empty. rmdir never removes unrelated or concurrently-created content.
    if [[ "$CREATED_JNI_LIBS_DIR" -eq 1 ]]; then
        rmdir "$JNI_LIBS_DIR" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Fresh temp dir each run (so we never have to rm -rf a dir containing
# root-owned files left by a previous interrupted docker invocation).
BUILD_DIR="$(mktemp -d -t seed-runtime-build.XXXXXXXXXX)"

# ---- Preflight ----
command -v docker >/dev/null || { echo "docker required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }
command -v dd >/dev/null || { echo "dd required" >&2; exit 1; }
command -v file >/dev/null || { echo "file required" >&2; exit 1; }
mkdir -p "$ASSETS_DIR"
# This directory must share a filesystem with ASSETS_DIR so publishing each
# completed asset is an atomic rename rather than a cross-filesystem copy.
STAGING_DIR="$(mktemp -d "$ASSETS_DIR/.seed-runtime-stage.XXXXXXXXXX")"
ROOTFS_TAR_OUT="$STAGING_DIR/rootfs.tar.gz"

# ---- Compute build_id (timestamp + script hash) ----
BUILD_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(sha256sum "$0" | cut -c1-8)"

# ---- Step A: stage pinned proot and its embedded loader ----
native_executable_matches_target() {
    local description
    [[ -f "$1" ]] || return 1
    description="$(LC_ALL=C file -Lb "$1" 2>/dev/null)" || return 1
    [[ "$description" == "ELF 64-bit"* && "$description" == *"$PROOT_FILE_MARKER"* ]]
}

sha256_matches() {
    local path="$1"
    local expected="$2"
    printf '%s  %s\n' "$expected" "$path" | sha256sum -c - >/dev/null 2>&1
}

proot_matches_target() {
    native_executable_matches_target "$1" && sha256_matches "$1" "$PROOT_SHA"
}

proot_loader_matches_target() {
    native_executable_matches_target "$1" && sha256_matches "$1" "$PROOT_LOADER_SHA"
}

# Offsets are safe only after the containing release binary has passed its
# pinned SHA-256 check. Reuse a valid pair; otherwise stage replacements inside
# JNI_LIBS_DIR so publication renames stay on the destination filesystem even
# when JNI_LIBS_DIR is a mount point or symlink.
PROOT_IS_VALID=0
PROOT_LOADER_IS_VALID=0
PUBLISH_PROOT=0
PUBLISH_PROOT_LOADER=0
PROOT_DOWNLOAD=""
PROOT_LOADER_STAGED=""
if proot_matches_target "$SELECTED_PROOT"; then
    PROOT_IS_VALID=1
fi
if proot_loader_matches_target "$SELECTED_PROOT_LOADER"; then
    PROOT_LOADER_IS_VALID=1
fi

if [[ "$PROOT_IS_VALID" -eq 0 || "$PROOT_LOADER_IS_VALID" -eq 0 ]]; then
    if [[ ! -d "$JNI_LIBS_DIR" ]]; then
        mkdir -p "$JNI_LIBS_DIR"
        CREATED_JNI_LIBS_DIR=1
    fi
    PROOT_STAGING_DIR="$(mktemp -d "$JNI_LIBS_DIR/.seed-runtime-proot-stage.XXXXXXXXXX")"
    PROOT_SOURCE="$SELECTED_PROOT"

    if [[ "$PROOT_IS_VALID" -eq 0 ]]; then
        echo "→ fetching pinned proot for $RUNTIME_ARCH ($PROOT_JNI_RELATIVE_PATH)"
        PROOT_DOWNLOAD="$PROOT_STAGING_DIR/libproot.so"
        curl -fsSL -o "$PROOT_DOWNLOAD" "$PROOT_URL"
        chmod +x "$PROOT_DOWNLOAD"
        native_executable_matches_target "$PROOT_DOWNLOAD" \
            || { echo "downloaded proot is not $RUNTIME_ARCH (expected file marker: $PROOT_FILE_MARKER)" >&2; exit 1; }
        sha256_matches "$PROOT_DOWNLOAD" "$PROOT_SHA" \
            || { echo "downloaded proot sha256 mismatch" >&2; exit 1; }
        PROOT_SOURCE="$PROOT_DOWNLOAD"
        PUBLISH_PROOT=1
    fi

    if [[ "$PROOT_IS_VALID" -eq 0 || "$PROOT_LOADER_IS_VALID" -eq 0 ]]; then
        echo "→ extracting pinned proot loader ($PROOT_LOADER_JNI_RELATIVE_PATH)"
        PROOT_LOADER_STAGED="$PROOT_STAGING_DIR/libproot-loader.so"
        dd if="$PROOT_SOURCE" of="$PROOT_LOADER_STAGED" bs=1 \
            skip="$PROOT_LOADER_OFFSET" count="$PROOT_LOADER_SIZE"
        chmod +x "$PROOT_LOADER_STAGED"
        native_executable_matches_target "$PROOT_LOADER_STAGED" \
            || { echo "extracted proot loader is not $RUNTIME_ARCH (expected file marker: $PROOT_FILE_MARKER)" >&2; exit 1; }
        sha256_matches "$PROOT_LOADER_STAGED" "$PROOT_LOADER_SHA" \
            || { echo "proot loader sha256 mismatch" >&2; exit 1; }
        PUBLISH_PROOT_LOADER=1
    fi
fi

# ---- Step B: fetch + verify Alpine minirootfs (selected architecture) ----
echo "→ fetching Alpine minirootfs"
ROOTFS_TAR_IN="$BUILD_DIR/rootfs-in.tar.gz"
curl -fsSL -o "$ROOTFS_TAR_IN" "$ALPINE_URL"
echo "$ALPINE_SHA  $ROOTFS_TAR_IN" | sha256sum -c - || { echo "Alpine sha256 mismatch" >&2; exit 1; }

# ---- Step C: build the runtime in a Dockerfile (selected architecture) ----
# Layout: copy backend + webapp into the build context so the
# Dockerfile can COPY them in (no bind-mount games needed).
#
# Two subtle traps to avoid here:
#   (1) The host's `backend/.venv` may target a different architecture;
#       copying it into the image can bake 50 MB of unusable binaries
#       into the rootfs AND (worse) `pip install -e` paths inside the
#       container that don't exist there. The `.dockerignore`
#       below excludes `**/.venv/**` (and friends) so the COPY step
#       only sees the source.
#   (2) The Dockerfile needs a `requirements.txt` to install the
#       backend's Python deps into the system Python. We generate
#       it from `backend/pyproject.toml` via `uv pip compile` (the
#       source of truth is the pyproject, not a lockfile that we'd
#       have to keep in sync). The result is a flat, version-pinned
#       list of prod-only deps (no pytest/httpx dev extras).
echo "→ preparing build context (backend + webapp + requirements.txt)"
mkdir -p "$BUILD_DIR/ctx"
cp -r "$REPO_ROOT/backend" "$BUILD_DIR/ctx/backend"
cp -r "$REPO_ROOT/webapp" "$BUILD_DIR/ctx/webapp"

# Generate the requirements list. `uv pip compile` reads the
# pyproject (not the .venv), so the resolved versions are
# reproducible from a clean checkout even on a machine that has
# never created a venv. `--no-header --no-annotate` keeps the
# output in the format `pip install -r` expects.
if ! command -v uv >/dev/null 2>&1; then
    echo "uv is required to generate backend/requirements.txt (see https://docs.astral.sh/uv/)" >&2
    exit 1
fi
uv pip compile --quiet --no-header --no-annotate \
    "$REPO_ROOT/backend/pyproject.toml" \
    > "$BUILD_DIR/ctx/backend/requirements.txt" \
    || { echo "uv pip compile failed" >&2; exit 1; }
echo "  requirements.txt: $(wc -l < "$BUILD_DIR/ctx/backend/requirements.txt") packages"

# .dockerignore in the build context excludes the host venv and
# other dev-only crud from the COPY step. Keep this list narrow
# — every entry is a tradeoff against "will the runtime image
# still build?". v0.1's minimum is .venv/** (the bug fix); the
# rest is hygiene.
cat > "$BUILD_DIR/ctx/.dockerignore" <<'DI'
# Host venvs may be wrong-arch and would silently bake dead weight
# into the image. This was the root cause of the 'ModuleNotFoundError:
# fastapi' smoke-test failure on 2026-07-03.
**/.venv/**
**/venv/**
# Standard Python dev crud.
**/__pycache__/**
**/*.pyc
**/*.pyo
**/*.egg-info/**
**/.pytest_cache/**
**/htmlcov/**
# Test / build outputs we don't need in the runtime.
**/build/**
**/node_modules/**
**/.git/**
**/.DS_Store
**/*.log
DI

cat > "$BUILD_DIR/ctx/Dockerfile" <<'DOCKERFILE'
# Pinned for reproducible builds. Update both pins together when bumping:
#   - base image: selected by ALPINE_BASE_IMAGE (must match ALPINE_URL)
#   - pi: @earendil-works/pi-coding-agent <exact version> (run `npm view` to check)
# Last bumped: 2026-07-03, pi 0.80.3, alpine 3.20.3.
# Full digest pinning is a v0.2 follow-up.
ARG ALPINE_BASE_IMAGE=alpine:3.20.3
FROM ${ALPINE_BASE_IMAGE}
RUN apk add --no-cache --update python3 py3-pip nodejs npm git tmux
RUN npm install -g @earendil-works/pi-coding-agent@0.80.3
COPY backend /home/seed/backend
COPY webapp /home/seed/app
RUN cd /home/seed/app && git init -q
# Install the backend's Python deps into the system Python. The
# requirements.txt is generated by build-runtime.sh from the host
# pyproject via `uv pip compile` (see the .dockerignore above for
# the .venv trap this avoids). `--break-system-packages` is needed
# because Alpine's py3-pip refuses to install into the system
# Python by default (PEP 668); the container IS the system, so
# it's safe to override. `--no-cache-dir` keeps the layer small.
RUN pip install --no-cache-dir --break-system-packages -r /home/seed/backend/requirements.txt
RUN printf '%s' '{"model":"deepseek-v4-flash","provider":"opencode-go","ports":{"backend":7777,"flask":7778},"logLevel":"info"}' > /home/seed/backend/config.json
DOCKERFILE

echo "→ building runtime image for $RUNTIME_ARCH (this takes a few minutes on first run)"
docker buildx build \
    --platform "$DOCKER_PLATFORM" \
    --build-arg "ALPINE_BASE_IMAGE=$ALPINE_BASE_IMAGE" \
    --load --tag "$DOCKER_IMAGE_TAG" "$BUILD_DIR/ctx"

# Sanity: verify the image was actually built for the selected target.
ACTUAL_IMAGE_ARCH="$(docker image inspect "$DOCKER_IMAGE_TAG" --format '{{.Architecture}}')"
[[ "$ACTUAL_IMAGE_ARCH" == "$DOCKER_IMAGE_ARCH" ]] \
    || { echo "image architecture is $ACTUAL_IMAGE_ARCH, expected $DOCKER_IMAGE_ARCH" >&2; exit 1; }

# ---- Step D: export the container's filesystem to a tarball ----
echo "→ exporting rootfs from container"
docker create --platform "$DOCKER_PLATFORM" --name "$DOCKER_CONTAINER_NAME" "$DOCKER_IMAGE_TAG"
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

# ---- Step E: stage seed_version.json ----
cat > "$STAGING_DIR/seed_version.json" <<JSON
{
  "seed_version": "0.1.0",
  "build_id": "$BUILD_ID"
}
JSON

# ---- Step F: publish completed runtime files ----
# All downloads, builds, exports, and validations have succeeded before this
# point. Each mv is atomic on its destination filesystem, but this multi-file
# group is not globally atomic. seed_version.json is therefore published last.
mkdir -p "$JNI_LIBS_DIR/$ANDROID_ABI"
if [[ "$PUBLISH_PROOT" -eq 1 ]]; then
    mv "$PROOT_DOWNLOAD" "$SELECTED_PROOT"
fi
if [[ "$PUBLISH_PROOT_LOADER" -eq 1 ]]; then
    mv "$PROOT_LOADER_STAGED" "$SELECTED_PROOT_LOADER"
fi
rm -f "$OPPOSITE_PROOT" "$OPPOSITE_PROOT_LOADER"
# Remove only an empty generated ABI directory; preserve any future native files.
rmdir "$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI" 2>/dev/null || true
rm -f "$ASSETS_DIR/proot"
mv "$ROOTFS_TAR_OUT" "$ASSETS_DIR/rootfs.tar.gz"
mv "$STAGING_DIR/seed_version.json" "$ASSETS_DIR/seed_version.json"

# ---- Step G: clean up ----
rm -rf "$BUILD_DIR" "$STAGING_DIR"
if [[ -n "$PROOT_STAGING_DIR" ]]; then
    rm -rf "$PROOT_STAGING_DIR"
fi
BUILD_DIR=""
STAGING_DIR=""
PROOT_STAGING_DIR=""
CREATED_JNI_LIBS_DIR=0

echo ""
echo "✓ runtime built for $RUNTIME_ARCH"
echo "  native proot → $SELECTED_PROOT"
ls -lh "$SELECTED_PROOT"
echo "  native proot loader → $SELECTED_PROOT_LOADER"
ls -lh "$SELECTED_PROOT_LOADER"
echo "  assets → $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
echo "  build_id = $BUILD_ID"

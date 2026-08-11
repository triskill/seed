#!/usr/bin/env bash
# scripts/build-runtime.sh — Build the Seed Android runtime.
#
# Produces:
#   android/app/src/main/jniLibs/<abi>/libproot.so
#   android/app/src/main/jniLibs/<abi>/libproot-loader.so
#   android/app/src/main/jniLibs/<abi>/libtalloc.so
#   android/app/src/main/jniLibs/<abi>/libandroid-shmem.so
#   android/app/src/main/assets/linux/rootfs.tar.gz   (~150 MB, Alpine + python + node + pi + backend + webapp)
#   android/app/src/main/assets/linux/seed_version.json
#
# Set RUNTIME_ARCH=arm64 (default) or RUNTIME_ARCH=x86_64.
# Requires: docker (with buildx support for the selected target), curl, ar,
# file, readelf, python3, sha256sum, tar with xz support.
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
SELECTED_TALLOC="$JNI_LIBS_DIR/$TALLOC_JNI_RELATIVE_PATH"
SELECTED_ANDROID_SHMEM="$JNI_LIBS_DIR/$ANDROID_SHMEM_JNI_RELATIVE_PATH"
case "$ANDROID_ABI" in
    arm64-v8a) OPPOSITE_ANDROID_ABI="x86_64" ;;
    x86_64) OPPOSITE_ANDROID_ABI="arm64-v8a" ;;
esac
OPPOSITE_PROOT="$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI/libproot.so"
OPPOSITE_PROOT_LOADER="$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI/libproot-loader.so"
OPPOSITE_TALLOC="$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI/libtalloc.so"
OPPOSITE_ANDROID_SHMEM="$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI/libandroid-shmem.so"
DOCKER_IMAGE_TAG="seed-runtime:$(date +%s)"
DOCKER_CONTAINER_NAME="seed-runtime-export-$$"
BUILD_DIR=""
STAGING_DIR=""
NATIVE_STAGING_DIR=""
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
    if [[ -n "$NATIVE_STAGING_DIR" ]]; then
        rm -rf "$NATIVE_STAGING_DIR"
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
command -v ar >/dev/null || { echo "ar required (install binutils)" >&2; exit 1; }
command -v file >/dev/null || { echo "file required" >&2; exit 1; }
command -v readelf >/dev/null || { echo "readelf required (install binutils)" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }
mkdir -p "$ASSETS_DIR"
# This directory must share a filesystem with ASSETS_DIR so publishing each
# completed asset is an atomic rename rather than a cross-filesystem copy.
STAGING_DIR="$(mktemp -d "$ASSETS_DIR/.seed-runtime-stage.XXXXXXXXXX")"
ROOTFS_TAR_OUT="$STAGING_DIR/rootfs.tar.gz"

# ---- Compute build_id (timestamp + script hash) ----
BUILD_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(sha256sum "$0" | cut -c1-8)"

# ---- Step A: stage the pinned Android-native proot bundle ----
native_elf_matches_target() {
    local description
    [[ -f "$1" && ! -L "$1" ]] || return 1
    description="$(LC_ALL=C file -Lb "$1" 2>/dev/null)" || return 1
    [[ "$description" == "ELF 64-bit"* && "$description" == *"$PROOT_FILE_MARKER"* ]]
}

sha256_matches() {
    local path="$1"
    local expected="$2"
    printf '%s  %s\n' "$expected" "$path" | sha256sum -c - >/dev/null 2>&1
}

proot_dependencies_are_android_native() {
    local dependencies
    dependencies="$(LC_ALL=C readelf -d "$1" 2>/dev/null)" || return 1
    grep -Fq 'Shared library: [libtalloc.so]' <<<"$dependencies" \
        && grep -Fq 'Shared library: [libandroid-shmem.so]' <<<"$dependencies" \
        && ! grep -Fq 'Shared library: [libtalloc.so.2]' <<<"$dependencies"
}

native_bundle_matches_target() {
    native_elf_matches_target "$SELECTED_PROOT" \
        && sha256_matches "$SELECTED_PROOT" "$PROOT_SHA" \
        && proot_dependencies_are_android_native "$SELECTED_PROOT" \
        && native_elf_matches_target "$SELECTED_PROOT_LOADER" \
        && sha256_matches "$SELECTED_PROOT_LOADER" "$PROOT_LOADER_SHA" \
        && native_elf_matches_target "$SELECTED_TALLOC" \
        && sha256_matches "$SELECTED_TALLOC" "$TALLOC_SHA" \
        && native_elf_matches_target "$SELECTED_ANDROID_SHMEM" \
        && sha256_matches "$SELECTED_ANDROID_SHMEM" "$ANDROID_SHMEM_SHA"
}

download_package() {
    local label="$1"
    local url="$2"
    local expected_sha="$3"
    local output="$4"
    echo "→ fetching pinned Termux $label package for $RUNTIME_ARCH"
    curl -fsSL -o "$output" "$url"
    sha256_matches "$output" "$expected_sha" \
        || { echo "$label package sha256 mismatch" >&2; return 1; }
}

extract_deb_data() {
    local package="$1"
    local destination="$2"
    local member
    member="$(ar t "$package" | grep -x 'data.tar.xz')" \
        || { echo "Termux package has no data.tar.xz: $package" >&2; return 1; }
    mkdir -p "$destination"
    ar p "$package" "$member" | tar -xJf - -C "$destination"
}

copy_package_file() {
    local source="$1"
    local destination="$2"
    [[ -f "$source" && ! -L "$source" ]] \
        || { echo "expected regular package file: $source" >&2; return 1; }
    cp "$source" "$destination"
}

rewrite_talloc_dependency() {
    python3 - "$1" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
old = b"libtalloc.so.2\0"
new = b"libtalloc.so\0\0\0"
if data.count(old) != 1:
    raise SystemExit("expected exactly one libtalloc.so.2 dependency")
path.write_bytes(data.replace(old, new))
PY
}

PUBLISH_NATIVE_BUNDLE=0
if ! native_bundle_matches_target; then
    if [[ ! -d "$JNI_LIBS_DIR" ]]; then
        mkdir -p "$JNI_LIBS_DIR"
        CREATED_JNI_LIBS_DIR=1
    fi
    NATIVE_STAGING_DIR="$(mktemp -d "$JNI_LIBS_DIR/.seed-runtime-native-stage.XXXXXXXXXX")"
    PACKAGE_DIR="$BUILD_DIR/termux-packages"
    EXTRACT_DIR="$BUILD_DIR/termux-extracted"
    mkdir -p "$PACKAGE_DIR" "$EXTRACT_DIR"

    PROOT_PACKAGE="$PACKAGE_DIR/proot.deb"
    TALLOC_PACKAGE="$PACKAGE_DIR/libtalloc.deb"
    ANDROID_SHMEM_PACKAGE="$PACKAGE_DIR/libandroid-shmem.deb"
    download_package proot "$PROOT_PACKAGE_URL" "$PROOT_PACKAGE_SHA" "$PROOT_PACKAGE"
    download_package libtalloc "$TALLOC_PACKAGE_URL" "$TALLOC_PACKAGE_SHA" "$TALLOC_PACKAGE"
    download_package libandroid-shmem "$ANDROID_SHMEM_PACKAGE_URL" \
        "$ANDROID_SHMEM_PACKAGE_SHA" "$ANDROID_SHMEM_PACKAGE"

    extract_deb_data "$PROOT_PACKAGE" "$EXTRACT_DIR/proot"
    extract_deb_data "$TALLOC_PACKAGE" "$EXTRACT_DIR/talloc"
    extract_deb_data "$ANDROID_SHMEM_PACKAGE" "$EXTRACT_DIR/android-shmem"

    copy_package_file \
        "$EXTRACT_DIR/proot/data/data/com.termux/files/usr/bin/proot" \
        "$NATIVE_STAGING_DIR/libproot.so"
    copy_package_file \
        "$EXTRACT_DIR/proot/data/data/com.termux/files/usr/libexec/proot/loader" \
        "$NATIVE_STAGING_DIR/libproot-loader.so"
    copy_package_file \
        "$EXTRACT_DIR/talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3" \
        "$NATIVE_STAGING_DIR/libtalloc.so"
    copy_package_file \
        "$EXTRACT_DIR/android-shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so" \
        "$NATIVE_STAGING_DIR/libandroid-shmem.so"

    rewrite_talloc_dependency "$NATIVE_STAGING_DIR/libproot.so"
    chmod +x "$NATIVE_STAGING_DIR/libproot.so" "$NATIVE_STAGING_DIR/libproot-loader.so"

    native_elf_matches_target "$NATIVE_STAGING_DIR/libproot.so" \
        || { echo "Termux proot is not $RUNTIME_ARCH" >&2; exit 1; }
    proot_dependencies_are_android_native "$NATIVE_STAGING_DIR/libproot.so" \
        || { echo "Termux proot has unexpected native dependencies" >&2; exit 1; }
    sha256_matches "$NATIVE_STAGING_DIR/libproot.so" "$PROOT_SHA" \
        || { echo "rewritten proot sha256 mismatch" >&2; exit 1; }
    native_elf_matches_target "$NATIVE_STAGING_DIR/libproot-loader.so" \
        && sha256_matches "$NATIVE_STAGING_DIR/libproot-loader.so" "$PROOT_LOADER_SHA" \
        || { echo "proot loader validation failed" >&2; exit 1; }
    native_elf_matches_target "$NATIVE_STAGING_DIR/libtalloc.so" \
        && sha256_matches "$NATIVE_STAGING_DIR/libtalloc.so" "$TALLOC_SHA" \
        || { echo "libtalloc validation failed" >&2; exit 1; }
    native_elf_matches_target "$NATIVE_STAGING_DIR/libandroid-shmem.so" \
        && sha256_matches "$NATIVE_STAGING_DIR/libandroid-shmem.so" "$ANDROID_SHMEM_SHA" \
        || { echo "libandroid-shmem validation failed" >&2; exit 1; }
    PUBLISH_NATIVE_BUNDLE=1
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
#       list of prod-only deps (no dev-only pytest/test tooling).
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
if [[ "$PUBLISH_NATIVE_BUNDLE" -eq 1 ]]; then
    mv "$NATIVE_STAGING_DIR/libproot.so" "$SELECTED_PROOT"
    mv "$NATIVE_STAGING_DIR/libproot-loader.so" "$SELECTED_PROOT_LOADER"
    mv "$NATIVE_STAGING_DIR/libtalloc.so" "$SELECTED_TALLOC"
    mv "$NATIVE_STAGING_DIR/libandroid-shmem.so" "$SELECTED_ANDROID_SHMEM"
fi
rm -f "$OPPOSITE_PROOT" "$OPPOSITE_PROOT_LOADER" \
    "$OPPOSITE_TALLOC" "$OPPOSITE_ANDROID_SHMEM"
# Remove only an empty generated ABI directory; preserve any future native files.
rmdir "$JNI_LIBS_DIR/$OPPOSITE_ANDROID_ABI" 2>/dev/null || true
rm -f "$ASSETS_DIR/proot"
mv "$ROOTFS_TAR_OUT" "$ASSETS_DIR/rootfs.tar.gz"
mv "$STAGING_DIR/seed_version.json" "$ASSETS_DIR/seed_version.json"

# ---- Step G: clean up ----
rm -rf "$BUILD_DIR" "$STAGING_DIR"
if [[ -n "$NATIVE_STAGING_DIR" ]]; then
    rm -rf "$NATIVE_STAGING_DIR"
fi
BUILD_DIR=""
STAGING_DIR=""
NATIVE_STAGING_DIR=""
CREATED_JNI_LIBS_DIR=0

echo ""
echo "✓ runtime built for $RUNTIME_ARCH"
echo "  native proot → $SELECTED_PROOT"
ls -lh "$SELECTED_PROOT"
echo "  native proot loader → $SELECTED_PROOT_LOADER"
ls -lh "$SELECTED_PROOT_LOADER"
echo "  native libtalloc → $SELECTED_TALLOC"
ls -lh "$SELECTED_TALLOC"
echo "  native libandroid-shmem → $SELECTED_ANDROID_SHMEM"
ls -lh "$SELECTED_ANDROID_SHMEM"
echo "  assets → $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
echo "  build_id = $BUILD_ID"

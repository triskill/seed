#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HELPER="$REPO_ROOT/scripts/runtime-target.sh"
BUILD_SCRIPT="$REPO_ROOT/scripts/build-runtime.sh"
TMP_DIR="$(mktemp -d -t seed-runtime-tools-test.XXXXXXXXXX)"
TESTS_RUN=0

cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_eq() {
    local expected="$1"
    local actual="$2"
    local label="$3"
    if [[ "$actual" != "$expected" ]]; then
        fail "$label: expected '$expected', got '$actual'"
    fi
}

assert_contains() {
    local file="$1"
    local expected="$2"
    local label="$3"
    if ! grep -Fq -- "$expected" "$file"; then
        echo "--- $label output ---" >&2
        cat "$file" >&2
        fail "$label: expected output to contain '$expected'"
    fi
}

assert_same_file() {
    local expected="$1"
    local actual="$2"
    local label="$3"
    if ! cmp -s "$expected" "$actual"; then
        fail "$label changed unexpectedly"
    fi
}

assert_exported() {
    local name
    for name in \
        RUNTIME_ARCH PROOT_URL ALPINE_URL ALPINE_SHA DOCKER_PLATFORM \
        DOCKER_IMAGE_ARCH ALPINE_BASE_IMAGE PROOT_FILE_MARKER; do
        if [[ "$(printenv "$name")" != "${!name}" ]]; then
            fail "$name must be exported"
        fi
    done
}

run_test() {
    local name="$1"
    shift
    "$@"
    TESTS_RUN=$((TESTS_RUN + 1))
    echo "PASS: $name"
}

# Source only declares configure_runtime_target; it must not configure a target.
unset RUNTIME_ARCH PROOT_URL ALPINE_URL ALPINE_SHA DOCKER_PLATFORM \
    DOCKER_IMAGE_ARCH ALPINE_BASE_IMAGE PROOT_FILE_MARKER
# shellcheck source=../runtime-target.sh
source "$HELPER"
if [[ -n "${PROOT_URL+x}" ]]; then
    fail "sourcing runtime-target.sh must not configure a target"
fi

test_arm64_target() (
    configure_runtime_target arm64
    assert_eq "arm64" "$RUNTIME_ARCH" "arm64 runtime architecture"
    assert_eq "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static" "$PROOT_URL" "arm64 proot URL"
    assert_eq "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz" "$ALPINE_URL" "arm64 Alpine URL"
    assert_eq "041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de" "$ALPINE_SHA" "arm64 Alpine SHA"
    assert_eq "linux/arm64" "$DOCKER_PLATFORM" "arm64 Docker platform"
    assert_eq "arm64" "$DOCKER_IMAGE_ARCH" "arm64 Docker image architecture"
    assert_eq "alpine:3.20.3" "$ALPINE_BASE_IMAGE" "arm64 multi-platform Alpine base image"
    assert_eq "ARM aarch64" "$PROOT_FILE_MARKER" "arm64 proot file marker"
    assert_exported
)

test_x86_64_target() (
    configure_runtime_target x86_64
    assert_eq "x86_64" "$RUNTIME_ARCH" "x86_64 runtime architecture"
    assert_eq "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static" "$PROOT_URL" "x86_64 proot URL"
    assert_eq "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz" "$ALPINE_URL" "x86_64 Alpine URL"
    assert_eq "d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c" "$ALPINE_SHA" "x86_64 Alpine SHA"
    assert_eq "linux/amd64" "$DOCKER_PLATFORM" "x86_64 Docker platform"
    assert_eq "amd64" "$DOCKER_IMAGE_ARCH" "x86_64 Docker image architecture"
    assert_eq "alpine:3.20.3" "$ALPINE_BASE_IMAGE" "x86_64 multi-platform Alpine base image"
    assert_eq "x86-64" "$PROOT_FILE_MARKER" "x86_64 proot file marker"
    assert_exported
)

test_default_target() (
    unset RUNTIME_ARCH
    configure_runtime_target "${RUNTIME_ARCH:-arm64}"
    assert_eq "arm64" "$RUNTIME_ARCH" "default runtime architecture"
)

test_target_can_be_reconfigured() (
    configure_runtime_target arm64
    configure_runtime_target arm64
    configure_runtime_target x86_64

    assert_eq "x86_64" "$RUNTIME_ARCH" "reconfigured runtime architecture"
    assert_eq "linux/amd64" "$DOCKER_PLATFORM" "reconfigured Docker platform"
    assert_eq "amd64" "$DOCKER_IMAGE_ARCH" "reconfigured Docker image architecture"
    assert_eq "x86-64" "$PROOT_FILE_MARKER" "reconfigured proot file marker"
    assert_exported
)

test_dockerfile_base_image_default() {
    assert_contains "$BUILD_SCRIPT" \
        "ARG ALPINE_BASE_IMAGE=alpine:3.20.3" \
        "Dockerfile Alpine base image default"
}

test_unsupported_target() {
    local stderr_file="$TMP_DIR/unsupported.stderr"
    if (configure_runtime_target riscv64) >"$TMP_DIR/unsupported.stdout" 2>"$stderr_file"; then
        fail "unsupported runtime architecture must be rejected"
    fi
    assert_contains "$stderr_file" "unsupported runtime architecture: riscv64" "unsupported architecture"
    assert_contains "$stderr_file" "arm64" "unsupported architecture"
    assert_contains "$stderr_file" "x86_64" "unsupported architecture"
}

test_failed_build_preserves_assets() {
    local case_dir="$TMP_DIR/failed-build"
    local assets_dir="$case_dir/assets"
    local originals_dir="$case_dir/originals"
    local fake_bin="$case_dir/bin"
    local stderr_file="$case_dir/build.stderr"
    local file_lc_log="$case_dir/file-lc.log"
    mkdir -p "$assets_dir" "$originals_dir" "$fake_bin"

    printf '%s' 'existing-wrong-arch-proot' > "$assets_dir/proot"
    printf '%s' 'existing-rootfs' > "$assets_dir/rootfs.tar.gz"
    printf '%s' 'existing-version' > "$assets_dir/seed_version.json"
    cp "$assets_dir/proot" "$originals_dir/proot"
    cp "$assets_dir/rootfs.tar.gz" "$originals_dir/rootfs.tar.gz"
    cp "$assets_dir/seed_version.json" "$originals_dir/seed_version.json"

    cat > "$fake_bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
output=""
url=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -o)
            output="$2"
            shift 2
            ;;
        *)
            url="$1"
            shift
            ;;
    esac
done
case "$url" in
    *proot*) printf '%s' 'downloaded-arm64-proot' > "$output" ;;
    *alpine-minirootfs*) printf '%s' 'bad-alpine-archive' > "$output" ;;
    *) exit 1 ;;
esac
FAKE_CURL

    cat > "$fake_bin/file" <<'FAKE_FILE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${LC_ALL:-<unset>}" >> "$FILE_LC_LOG"
target="${!#}"
if [[ "$(<"$target")" == "downloaded-arm64-proot" ]]; then
    printf '%s\n' 'ELF 64-bit LSB executable, ARM aarch64'
else
    printf '%s\n' 'ELF 64-bit LSB executable, x86-64'
fi
FAKE_FILE

    cat > "$fake_bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
exit 0
FAKE_DOCKER
    chmod +x "$fake_bin/curl" "$fake_bin/file" "$fake_bin/docker"

    if ASSETS_DIR="$assets_dir" FILE_LC_LOG="$file_lc_log" \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$stderr_file"; then
        fail "build with invalid Alpine checksum must fail"
    fi

    assert_contains "$stderr_file" "Alpine sha256 mismatch" "failed build"
    assert_same_file "$originals_dir/proot" "$assets_dir/proot" "existing proot"
    assert_same_file "$originals_dir/rootfs.tar.gz" "$assets_dir/rootfs.tar.gz" "existing rootfs"
    assert_same_file "$originals_dir/seed_version.json" "$assets_dir/seed_version.json" "existing version marker"
    if compgen -G "$assets_dir/.seed-runtime-stage.*" >/dev/null; then
        fail "failed build left a staging directory in the assets directory"
    fi
    if grep -Fvxq 'C' "$file_lc_log"; then
        fail "file parsing must run with LC_ALL=C"
    fi
}

run_test "arm64 target configuration" test_arm64_target
run_test "x86_64 target configuration" test_x86_64_target
run_test "arm64 default target" test_default_target
run_test "target configuration can be repeated and changed" test_target_can_be_reconfigured
run_test "Dockerfile has valid multi-platform Alpine default" test_dockerfile_base_image_default
run_test "failed build preserves existing assets" test_failed_build_preserves_assets
run_test "unsupported target rejection" test_unsupported_target

echo "All $TESTS_RUN runtime tool tests passed."

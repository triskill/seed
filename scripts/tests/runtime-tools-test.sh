#!/usr/bin/env bash
# Unit tests for direct-native ARM64 and x86_64 runtime tooling.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HELPER="$REPO_ROOT/scripts/runtime-target.sh"
CHECKER="$REPO_ROOT/scripts/check-runtime-arch.sh"
MAKEFILE="$REPO_ROOT/Makefile"
TMP_DIR="$(mktemp -d -t seed-runtime-tools-test.XXXXXXXXXX)"
trap 'rm -rf "$TMP_DIR"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$3: missing '$2'"; }

# Sourcing only declares the function.
unset RUNTIME_ARCH PROOT_PACKAGE_URL || true
source "$HELPER"
[[ -z "${PROOT_PACKAGE_URL+x}" ]] || fail 'source configured a target'
configure_runtime_target arm64
[[ "$RUNTIME_ARCH" == arm64 && "$ANDROID_ABI" == arm64-v8a ]] || fail 'bad ARM64 target'
[[ "$DOCKER_PLATFORM" == linux/arm64 && "$DOCKER_IMAGE_ARCH" == arm64 ]] || fail 'bad ARM64 Docker target'
[[ "$ALPINE_URL" == *aarch64* && "$PROOT_FILE_MARKER" == 'ARM aarch64' ]] || fail 'bad ARM64 target data'
configure_runtime_target x86_64
[[ "$RUNTIME_ARCH" == x86_64 && "$ANDROID_ABI" == x86_64 ]] || fail 'bad x86_64 target'
[[ "$DOCKER_PLATFORM" == linux/amd64 && "$DOCKER_IMAGE_ARCH" == amd64 ]] || fail 'bad x86_64 Docker target'
[[ "$ALPINE_URL" == *x86_64* && "$PROOT_FILE_MARKER" == 'x86-64' ]] || fail 'bad x86_64 target data'
if configure_runtime_target riscv64 2>"$TMP_DIR/err"; then fail 'unsupported target accepted'; fi
assert_contains "$TMP_DIR/err" 'supported: arm64, x86_64' 'unsupported target'
assert_contains "$MAKEFILE" 'default;x86_64' 'local x86 image'
if grep -Fq 'runtime-qemu-x86' "$MAKEFILE"; then fail 'QEMU target restored'; fi

# Checker accepts both direct-native ELF formats and reports the right repair command.
cat > "$TMP_DIR/file" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *x86*) printf '%s\n' 'ELF 64-bit LSB executable, x86-64, statically linked' ;;
  *) printf '%s\n' 'ELF 64-bit LSB executable, ARM aarch64, statically linked' ;;
esac
EOF
chmod +x "$TMP_DIR/file"
printf x > "$TMP_DIR/arm64"
printf x > "$TMP_DIR/x86_64"
PATH="$TMP_DIR:$PATH" "$CHECKER" arm64 "$TMP_DIR/arm64" >/dev/null || fail 'checker rejected ARM64'
PATH="$TMP_DIR:$PATH" "$CHECKER" x86_64 "$TMP_DIR/x86_64" >/dev/null || fail 'checker rejected x86_64'
if PATH="$TMP_DIR:$PATH" "$CHECKER" x86_64 "$TMP_DIR/arm64" >/dev/null 2>"$TMP_DIR/mismatch.err"; then fail 'checker accepted mismatch'; fi
assert_contains "$TMP_DIR/mismatch.err" 'make runtime RUNTIME_ARCH=x86_64' 'x86 repair command'

echo 'PASS: direct-native runtime tooling'

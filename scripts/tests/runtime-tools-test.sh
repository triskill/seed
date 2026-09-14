#!/usr/bin/env bash
# Unit tests for the native ARM64 runtime tooling. These tests never download
# packages or require Docker; they validate target selection and preflights.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HELPER="$REPO_ROOT/scripts/runtime-target.sh"
CHECKER="$REPO_ROOT/scripts/check-runtime-arch.sh"
MAKEFILE="$REPO_ROOT/Makefile"
GITIGNORE="$REPO_ROOT/.gitignore"
TMP_DIR="$(mktemp -d -t seed-runtime-tools-test.XXXXXXXXXX)"
trap 'rm -rf "$TMP_DIR"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$3: missing '$2'"; }
assert_not_contains() { ! grep -Fq -- "$2" "$1" || fail "$3: found '$2'"; }

# Sourcing only declares the function.
unset RUNTIME_ARCH PROOT_PACKAGE_URL || true
source "$HELPER"
[[ -z "${PROOT_PACKAGE_URL+x}" ]] || fail 'source configured a target'
configure_runtime_target arm64
[[ "$RUNTIME_ARCH" == arm64 && "$ANDROID_ABI" == arm64-v8a ]] || fail 'bad ARM64 target'
[[ "$DOCKER_PLATFORM" == linux/arm64 && "$DOCKER_IMAGE_ARCH" == arm64 ]] || fail 'bad Docker target'
[[ "$ALPINE_URL" == *aarch64* ]] || fail 'bad Alpine URL'
[[ "$PROOT_FILE_MARKER" == 'ARM aarch64' ]] || fail 'bad PRoot marker'
[[ "$PROOT_JNI_RELATIVE_PATH" == arm64-v8a/libproot.so ]] || fail 'bad JNI path'
if configure_runtime_target x86_64 2>"$TMP_DIR/err"; then fail 'x86 target accepted'; fi
assert_contains "$TMP_DIR/err" 'only native arm64 is supported' 'unsupported target'
assert_not_contains "$HELPER" 'x86_64)' 'x86 target case removed'
assert_not_contains "$HELPER" 'DOCKER_PLATFORM=linux/amd64' 'x86 Docker target removed'
assert_not_contains "$MAKEFILE" 'runtime-qemu-x86' 'QEMU Make target removed'
assert_not_contains "$MAKEFILE" 'run-phone-x86-test' 'QEMU phone target removed'
assert_not_contains "$GITIGNORE" 'libqemu-x86-64.so' 'QEMU ignore removed'
assert_not_contains "$GITIGNORE" 'jniLibs/x86_64' 'x86 ABI ignore removed'

# Checker accepts ARM aliases and rejects foreign expected architectures.
cat > "$TMP_DIR/file" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' 'ELF 64-bit LSB executable, ARM aarch64, statically linked'
EOF
chmod +x "$TMP_DIR/file"
printf x > "$TMP_DIR/proot"
for expected in arm64 aarch64 arm64-v8a; do
 PATH="$TMP_DIR:$PATH" "$CHECKER" "$expected" "$TMP_DIR/proot" >/dev/null || fail "checker rejected $expected"
done
if PATH="$TMP_DIR:$PATH" "$CHECKER" x86_64 "$TMP_DIR/proot" >/dev/null 2>"$TMP_DIR/checker.err"; then fail 'checker accepted x86'; fi
assert_contains "$TMP_DIR/checker.err" 'unsupported expected architecture' 'checker architecture rejection'
if "$CHECKER" arm64 "$TMP_DIR/missing" >/dev/null 2>"$TMP_DIR/missing.err"; then fail 'checker accepted missing file'; fi
assert_contains "$TMP_DIR/missing.err" 'make runtime RUNTIME_ARCH=arm64' 'checker repair command'

echo 'PASS: native ARM64 runtime tooling'

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HELPER="$REPO_ROOT/scripts/runtime-target.sh"
BUILD_SCRIPT="$REPO_ROOT/scripts/build-runtime.sh"
ARCH_CHECKER="$REPO_ROOT/scripts/check-runtime-arch.sh"
MAKEFILE="$REPO_ROOT/Makefile"
GITIGNORE="$REPO_ROOT/.gitignore"
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

assert_not_contains() {
    local file="$1"
    local unexpected="$2"
    local label="$3"
    if grep -Fq -- "$unexpected" "$file"; then
        echo "--- $label output ---" >&2
        cat "$file" >&2
        fail "$label: expected output not to contain '$unexpected'"
    fi
}

assert_line_count() {
    local expected="$1"
    local file="$2"
    local label="$3"
    local actual
    actual="$(wc -l < "$file")"
    assert_eq "$expected" "$actual" "$label"
}

assert_before() {
    local file="$1"
    local first="$2"
    local second="$3"
    local label="$4"
    local first_line second_line
    first_line="$(grep -Fn -m1 -- "$first" "$file" | cut -d: -f1 || true)"
    second_line="$(grep -Fn -m1 -- "$second" "$file" | cut -d: -f1 || true)"
    if [[ -z "$first_line" || -z "$second_line" || "$first_line" -ge "$second_line" ]]; then
        echo "--- $label output ---" >&2
        cat "$file" >&2
        fail "$label: expected '$first' before '$second'"
    fi
}

write_fake_file_tool() {
    local fake_bin="$1"
    mkdir -p "$fake_bin"
    cat > "$fake_bin/file" <<'FAKE_FILE'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${FILE_CALL_LOG:-}" ]]; then
    printf '%s|%s\n' "${LC_ALL:-<unset>}" "$*" > "$FILE_CALL_LOG"
fi
printf '%s\n' "$FAKE_FILE_DESCRIPTION"
FAKE_FILE
    chmod +x "$fake_bin/file"
}

assert_same_file() {
    local expected="$1"
    local actual="$2"
    local label="$3"
    if ! cmp -s "$expected" "$actual"; then
        fail "$label changed unexpectedly"
    fi
}

assert_repo_gitignore_rule() {
    local path="$1"
    local label="$2"
    local match
    if ! match="$(git -C "$REPO_ROOT" -c core.excludesFile=/dev/null \
        check-ignore -v --no-index "$path")"; then
        fail "$label must be ignored"
    fi
    if [[ "${match%%:*}" != ".gitignore" ]]; then
        fail "$label matched a non-repository ignore rule: $match"
    fi
}

assert_exact_repo_gitignore_rule() {
    local path="$1"
    local label="$2"
    local count
    assert_repo_gitignore_rule "$path" "$label"
    count="$(grep -Fxc -- "$path" "$GITIGNORE")"
    assert_eq "1" "$count" "$label exact .gitignore rule count"
}

assert_exported() {
    local name
    for name in \
        RUNTIME_ARCH PROOT_PACKAGE_URL PROOT_PACKAGE_SHA TALLOC_PACKAGE_URL \
        TALLOC_PACKAGE_SHA ANDROID_SHMEM_PACKAGE_URL ANDROID_SHMEM_PACKAGE_SHA \
        PROOT_SHA PROOT_LOADER_SHA TALLOC_SHA ANDROID_SHMEM_SHA ALPINE_URL \
        ALPINE_SHA DOCKER_PLATFORM DOCKER_IMAGE_ARCH ALPINE_BASE_IMAGE \
        PROOT_FILE_MARKER ANDROID_ABI PROOT_JNI_RELATIVE_PATH \
        PROOT_LOADER_JNI_RELATIVE_PATH TALLOC_JNI_RELATIVE_PATH \
        ANDROID_SHMEM_JNI_RELATIVE_PATH; do
        if [[ "$(printenv "$name")" != "${!name}" ]]; then
            fail "$name must be exported"
        fi
    done
}

run_test() {
    local name="$1"
    shift
    if [[ -n "${RUNTIME_TOOLS_TEST_FILTER:-}" && "$name" != *"$RUNTIME_TOOLS_TEST_FILTER"* ]]; then
        return
    fi
    "$@"
    TESTS_RUN=$((TESTS_RUN + 1))
    echo "PASS: $name"
}

# Source only declares configure_runtime_target; it must not configure a target.
unset RUNTIME_ARCH PROOT_PACKAGE_URL PROOT_PACKAGE_SHA TALLOC_PACKAGE_URL \
    TALLOC_PACKAGE_SHA ANDROID_SHMEM_PACKAGE_URL ANDROID_SHMEM_PACKAGE_SHA \
    PROOT_SHA PROOT_LOADER_SHA TALLOC_SHA ANDROID_SHMEM_SHA ALPINE_URL \
    ALPINE_SHA DOCKER_PLATFORM DOCKER_IMAGE_ARCH ALPINE_BASE_IMAGE \
    PROOT_FILE_MARKER ANDROID_ABI PROOT_JNI_RELATIVE_PATH \
    PROOT_LOADER_JNI_RELATIVE_PATH TALLOC_JNI_RELATIVE_PATH \
    ANDROID_SHMEM_JNI_RELATIVE_PATH
# shellcheck source=../runtime-target.sh
source "$HELPER"
if [[ -n "${PROOT_PACKAGE_URL+x}" ]]; then
    fail "sourcing runtime-target.sh must not configure a target"
fi

test_arm64_target() (
    configure_runtime_target arm64
    assert_eq "arm64" "$RUNTIME_ARCH" "arm64 runtime architecture"
    assert_eq "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.89_aarch64.deb" "$PROOT_PACKAGE_URL" "arm64 proot package URL"
    assert_eq "ec9fe38c50cfd49dd31fe360ffbcc3124a945dc1ea16293a8a769303dd724f46" "$PROOT_PACKAGE_SHA" "arm64 proot package SHA"
    assert_eq "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb" "$TALLOC_PACKAGE_URL" "arm64 talloc package URL"
    assert_eq "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da" "$TALLOC_PACKAGE_SHA" "arm64 talloc package SHA"
    assert_eq "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb" "$ANDROID_SHMEM_PACKAGE_URL" "arm64 shmem package URL"
    assert_eq "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6" "$ANDROID_SHMEM_PACKAGE_SHA" "arm64 shmem package SHA"
    assert_eq "7da118895e971ea9fba4bb250b28af0f8db2edcbfdbaa8075cc645a0d7cf16fe" "$PROOT_SHA" "arm64 proot SHA"
    assert_eq "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04" "$PROOT_LOADER_SHA" "arm64 loader SHA"
    assert_eq "3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb" "$TALLOC_SHA" "arm64 talloc SHA"
    assert_eq "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731" "$ANDROID_SHMEM_SHA" "arm64 shmem SHA"
    assert_eq "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.5-aarch64.tar.gz" "$ALPINE_URL" "arm64 Alpine URL"
    assert_eq "3fbc6285032ed46821b511292633d7b2a6306a2e254f590e92bdafff56cf2f70" "$ALPINE_SHA" "arm64 Alpine SHA"
    assert_eq "linux/arm64" "$DOCKER_PLATFORM" "arm64 Docker platform"
    assert_eq "arm64" "$DOCKER_IMAGE_ARCH" "arm64 Docker image architecture"
    assert_eq "alpine:3.22.5" "$ALPINE_BASE_IMAGE" "arm64 multi-platform Alpine base image"
    assert_eq "ARM aarch64" "$PROOT_FILE_MARKER" "arm64 proot file marker"
    assert_eq "arm64-v8a" "$ANDROID_ABI" "arm64 Android ABI"
    assert_eq "arm64-v8a/libproot.so" "$PROOT_JNI_RELATIVE_PATH" "arm64 native proot path"
    assert_eq "arm64-v8a/libproot-loader.so" "$PROOT_LOADER_JNI_RELATIVE_PATH" "arm64 native proot loader path"
    assert_eq "arm64-v8a/libtalloc.so" "$TALLOC_JNI_RELATIVE_PATH" "arm64 talloc path"
    assert_eq "arm64-v8a/libandroid-shmem.so" "$ANDROID_SHMEM_JNI_RELATIVE_PATH" "arm64 shmem path"
    assert_exported
)

test_x86_64_target() (
    configure_runtime_target x86_64
    assert_eq "x86_64" "$RUNTIME_ARCH" "x86_64 runtime architecture"
    assert_eq "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.89_x86_64.deb" "$PROOT_PACKAGE_URL" "x86_64 proot package URL"
    assert_eq "0d76da0515f38dfb2217f647b0d79fcd61b38f80e25cbf2d39237697b02dd016" "$PROOT_PACKAGE_SHA" "x86_64 proot package SHA"
    assert_eq "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb" "$TALLOC_PACKAGE_URL" "x86_64 talloc package URL"
    assert_eq "7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628" "$TALLOC_PACKAGE_SHA" "x86_64 talloc package SHA"
    assert_eq "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb" "$ANDROID_SHMEM_PACKAGE_URL" "x86_64 shmem package URL"
    assert_eq "ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797" "$ANDROID_SHMEM_PACKAGE_SHA" "x86_64 shmem package SHA"
    assert_eq "d87c0bd62dfbd456826e8c3f968d4e9b264e6a912417e40a883900142d867051" "$PROOT_SHA" "x86_64 proot SHA"
    assert_eq "914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3" "$PROOT_LOADER_SHA" "x86_64 loader SHA"
    assert_eq "77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da" "$TALLOC_SHA" "x86_64 talloc SHA"
    assert_eq "092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f" "$ANDROID_SHMEM_SHA" "x86_64 shmem SHA"
    assert_eq "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/x86_64/alpine-minirootfs-3.22.5-x86_64.tar.gz" "$ALPINE_URL" "x86_64 Alpine URL"
    assert_eq "4b4daa9fe2fc696c4919c4412a4c3d3e770d8fb70292a004a2c72f5096175282" "$ALPINE_SHA" "x86_64 Alpine SHA"
    assert_eq "linux/amd64" "$DOCKER_PLATFORM" "x86_64 Docker platform"
    assert_eq "amd64" "$DOCKER_IMAGE_ARCH" "x86_64 Docker image architecture"
    assert_eq "alpine:3.22.5" "$ALPINE_BASE_IMAGE" "x86_64 multi-platform Alpine base image"
    assert_eq "x86-64" "$PROOT_FILE_MARKER" "x86_64 proot file marker"
    assert_eq "x86_64" "$ANDROID_ABI" "x86_64 Android ABI"
    assert_eq "x86_64/libproot.so" "$PROOT_JNI_RELATIVE_PATH" "x86_64 native proot path"
    assert_eq "x86_64/libproot-loader.so" "$PROOT_LOADER_JNI_RELATIVE_PATH" "x86_64 native proot loader path"
    assert_eq "x86_64/libtalloc.so" "$TALLOC_JNI_RELATIVE_PATH" "x86_64 talloc path"
    assert_eq "x86_64/libandroid-shmem.so" "$ANDROID_SHMEM_JNI_RELATIVE_PATH" "x86_64 shmem path"
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
    assert_eq "x86_64" "$ANDROID_ABI" "reconfigured Android ABI"
    assert_eq "x86_64/libproot.so" "$PROOT_JNI_RELATIVE_PATH" "reconfigured native proot path"
    assert_eq "x86_64/libproot-loader.so" "$PROOT_LOADER_JNI_RELATIVE_PATH" "reconfigured native loader path"
    assert_eq "x86_64/libtalloc.so" "$TALLOC_JNI_RELATIVE_PATH" "reconfigured talloc path"
    assert_eq "x86_64/libandroid-shmem.so" "$ANDROID_SHMEM_JNI_RELATIVE_PATH" "reconfigured shmem path"
    assert_eq "d87c0bd62dfbd456826e8c3f968d4e9b264e6a912417e40a883900142d867051" "$PROOT_SHA" "reconfigured proot SHA"
    assert_eq "914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3" "$PROOT_LOADER_SHA" "reconfigured loader SHA"
    assert_exported
)

test_dockerfile_base_image_default() {
    assert_contains "$BUILD_SCRIPT" \
        "ARG ALPINE_BASE_IMAGE=alpine:3.22.5" \
        "Dockerfile Alpine base image default"
}

test_dockerfile_validates_pi_node_runtime() {
    assert_contains "$BUILD_SCRIPT" \
        "if (major < 22) process.exit(1)" \
        "Dockerfile minimum Node version check"
    assert_contains "$BUILD_SCRIPT" \
        "&& pi --version" \
        "Dockerfile pi startup smoke check"
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

test_checker_accepts_x86_64_aliases() {
    local expected
    local binary="$TMP_DIR/x86-proot"
    local fake_bin="$TMP_DIR/x86-checker-bin"
    local output_file="$TMP_DIR/x86-match.stdout"
    printf '%s' 'controlled-x86-binary' > "$binary"
    write_fake_file_tool "$fake_bin"

    for expected in x86_64 amd64; do
        if ! FAKE_FILE_DESCRIPTION='ELF 64-bit LSB executable, x86-64, statically linked' \
            PATH="$fake_bin:$PATH" "$ARCH_CHECKER" "$expected" "$binary" \
            >"$output_file" 2>"$TMP_DIR/x86-match.stderr"; then
            cat "$TMP_DIR/x86-match.stderr" >&2
            fail "x86_64 checker must accept expected architecture '$expected'"
        fi
        assert_contains "$output_file" "runtime architecture matches: x86_64" "x86_64 match"
    done
}

test_checker_accepts_arm64_aliases() {
    local expected
    local arm_binary="$TMP_DIR/arm64-proot"
    local fake_bin="$TMP_DIR/checker-bin"
    local output_file="$TMP_DIR/arm-match.stdout"
    local file_call_log="$TMP_DIR/arm-file-call.log"
    printf '%s' 'controlled-arm64-binary' > "$arm_binary"
    write_fake_file_tool "$fake_bin"

    for expected in arm64 aarch64 arm64-v8a; do
        if ! FILE_CALL_LOG="$file_call_log" \
            FAKE_FILE_DESCRIPTION='ELF 64-bit LSB executable, ARM aarch64, statically linked' \
            PATH="$fake_bin:$PATH" \
            "$ARCH_CHECKER" "$expected" "$arm_binary" \
            >"$output_file" 2>"$TMP_DIR/arm-match.stderr"; then
            cat "$TMP_DIR/arm-match.stderr" >&2
            fail "arm64 checker must accept expected architecture '$expected'"
        fi
        assert_contains "$output_file" "runtime architecture matches: arm64" "arm64 match"
        assert_contains "$file_call_log" "C|-Lb $arm_binary" "file invocation"
    done
}

test_checker_reports_arm64_mismatch_command() {
    local binary="$TMP_DIR/mismatched-x86-proot"
    local fake_bin="$TMP_DIR/arm-mismatch-bin"
    local stderr_file="$TMP_DIR/arm-mismatch.stderr"
    printf '%s' 'controlled-x86-binary' > "$binary"
    write_fake_file_tool "$fake_bin"

    if FAKE_FILE_DESCRIPTION='ELF 64-bit LSB executable, x86-64, statically linked' \
        PATH="$fake_bin:$PATH" "$ARCH_CHECKER" arm64-v8a "$binary" \
        >"$TMP_DIR/arm-mismatch.stdout" 2>"$stderr_file"; then
        fail "arm64-v8a expectation against x86_64 runtime must fail"
    fi
    assert_contains "$stderr_file" "runtime architecture mismatch" "arm64 mismatch"
    assert_contains "$stderr_file" "make runtime RUNTIME_ARCH=arm64" "arm64 mismatch command"
}

test_checker_reports_x86_64_mismatch_command() {
    local arm_binary="$TMP_DIR/mismatched-arm64-proot"
    local fake_bin="$TMP_DIR/mismatch-bin"
    local stderr_file="$TMP_DIR/x86-mismatch.stderr"
    printf '%s' 'controlled-arm64-binary' > "$arm_binary"
    write_fake_file_tool "$fake_bin"

    if FAKE_FILE_DESCRIPTION='ELF 64-bit LSB executable, ARM aarch64, statically linked' \
        PATH="$fake_bin:$PATH" "$ARCH_CHECKER" x86_64 "$arm_binary" \
        >"$TMP_DIR/x86-mismatch.stdout" 2>"$stderr_file"; then
        fail "x86_64 expectation against arm64 runtime must fail"
    fi
    assert_contains "$stderr_file" "runtime architecture mismatch" "x86_64 mismatch"
    assert_contains "$stderr_file" "make runtime RUNTIME_ARCH=x86_64" "x86_64 mismatch command"
}

test_checker_requires_explicit_binary_path() {
    local stderr_file="$TMP_DIR/checker-arity.stderr"

    if "$ARCH_CHECKER" x86_64 >"$TMP_DIR/checker-arity.stdout" 2>"$stderr_file"; then
        fail "checker without an explicit proot path must fail"
    fi
    assert_contains "$stderr_file" \
        "usage: $ARCH_CHECKER <x86_64|amd64|arm64|aarch64|arm64-v8a> <proot-path>" \
        "checker missing path usage"

    if "$ARCH_CHECKER" x86_64 one two >"$TMP_DIR/checker-arity.stdout" 2>"$stderr_file"; then
        fail "checker with more than two arguments must fail"
    fi
    assert_contains "$stderr_file" "<proot-path>" "checker extra argument usage"
    assert_not_contains "$ARCH_CHECKER" "android/app/src/main/assets/linux/proot" \
        "checker obsolete default path"
}

test_checker_rejects_missing_binary() {
    local missing="$TMP_DIR/does-not-exist"
    local stderr_file="$TMP_DIR/missing.stderr"
    if "$ARCH_CHECKER" x86_64 "$missing" >"$TMP_DIR/missing.stdout" 2>"$stderr_file"; then
        fail "missing runtime binary must fail"
    fi
    assert_contains "$stderr_file" "runtime binary not found: $missing" "missing runtime"
    assert_contains "$stderr_file" \
        "Build compatible assets explicitly: make runtime RUNTIME_ARCH=x86_64" \
        "missing runtime build command"
}

test_checker_rejects_non_elf_binary() {
    local text_file="$TMP_DIR/not-elf.txt"
    local stderr_file="$TMP_DIR/non-elf.stderr"
    printf '%s\n' 'plain text' > "$text_file"
    if "$ARCH_CHECKER" x86_64 "$text_file" >"$TMP_DIR/non-elf.stdout" 2>"$stderr_file"; then
        fail "non-ELF runtime binary must fail"
    fi
    assert_contains "$stderr_file" "runtime binary is not a 64-bit ELF" "non-ELF runtime"
}

test_checker_rejects_unrecognized_elf_architecture() {
    local binary="$TMP_DIR/riscv-proot"
    local fake_bin="$TMP_DIR/unrecognized-bin"
    local stderr_file="$TMP_DIR/unrecognized.stderr"
    mkdir -p "$fake_bin"
    printf '%s' 'controlled-riscv-binary' > "$binary"

    cat > "$fake_bin/file" <<'FAKE_FILE'
#!/usr/bin/env bash
printf '%s\n' 'ELF 64-bit LSB executable, UCB RISC-V, statically linked'
FAKE_FILE
    chmod +x "$fake_bin/file"

    if PATH="$fake_bin:$PATH" "$ARCH_CHECKER" x86_64 "$binary" \
        >"$TMP_DIR/unrecognized.stdout" 2>"$stderr_file"; then
        fail "unrecognized ELF architecture must fail"
    fi
    assert_contains "$stderr_file" "unrecognized runtime architecture" "unrecognized runtime"
}

test_checker_rejects_unsupported_expected_architecture() {
    local binary="$TMP_DIR/unsupported-expected-proot"
    local stderr_file="$TMP_DIR/unsupported-expected.stderr"
    printf '%s' 'controlled-binary' > "$binary"
    if "$ARCH_CHECKER" riscv64 "$binary" >"$TMP_DIR/unsupported-expected.stdout" 2>"$stderr_file"; then
        fail "unsupported expected architecture must fail"
    fi
    assert_contains "$stderr_file" "unsupported expected architecture: riscv64" "unsupported expected architecture"
    assert_contains "$stderr_file" "x86_64/amd64" "unsupported expected architecture"
    assert_contains "$stderr_file" "arm64/aarch64/arm64-v8a" "unsupported expected architecture"
}

prepare_make_workflow_fixture() {
    local case_dir="$1"
    local android_home="$case_dir/android-home"
    local fake_bin="$case_dir/bin"
    local apk="$case_dir/android/app/build/outputs/apk/debug/app-debug.apk"
    local source_asset="$case_dir/android/app/src/main/assets/linux/proot"
    local source_file="$case_dir/android/app/src/main/java/com/seed/app/MainActivity.kt"

    mkdir -p \
        "$case_dir/android" \
        "$(dirname "$apk")" \
        "$(dirname "$source_asset")" \
        "$(dirname "$source_file")" \
        "$case_dir/scripts" \
        "$android_home/avd/seed_dev.avd" \
        "$android_home/emulator" \
        "$android_home/platform-tools" \
        "$fake_bin"
    cp "$MAKEFILE" "$case_dir/Makefile"
    printf '%s\n' 'controlled runtime asset' > "$source_asset"
    printf '%s\n' 'controlled Kotlin source' > "$source_file"
    printf '%s\n' 'pre-existing stale APK' > "$apk"
    touch -d '2 minutes ago' "$source_asset" "$source_file"
    touch -d '1 minute ago' "$apk"
    : > "$case_dir/gradle.log"

    cat > "$case_dir/android/gradlew" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$GRADLE_LOG"
FAKE_GRADLE

    cat > "$android_home/emulator/emulator" <<'FAKE_EMULATOR'
#!/usr/bin/env bash
exec /bin/sleep 30
FAKE_EMULATOR

    cat > "$case_dir/scripts/check-runtime-arch.sh" <<'FAKE_ARCH_CHECKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s|%s|%s\n' "${SYSTEM_IMAGE:-<unset>}" \
    "${1:-<missing>}" "${2:-<missing>}" >> "${CHECKER_CALL_LOG:?}"
FAKE_ARCH_CHECKER

    cat > "$android_home/platform-tools/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == "shell getprop sys.boot_completed" ]]; then
    printf '1\n'
fi
exit 0
FAKE_ADB

    cat > "$fake_bin/sleep" <<'FAKE_SLEEP'
#!/usr/bin/env bash
exit 0
FAKE_SLEEP

    cat > "$case_dir/preflight-overrides.mk" <<'MAKE_FIXTURE'
.PHONY: check-deps check-runtime-arch
check-deps:
	@:
check-runtime-arch:
	@:
MAKE_FIXTURE

    chmod +x \
        "$case_dir/android/gradlew" \
        "$case_dir/scripts/check-runtime-arch.sh" \
        "$android_home/emulator/emulator" \
        "$android_home/platform-tools/adb" \
        "$fake_bin/sleep"
}

test_make_build_assembles_when_apk_is_newer_than_assets() {
    local case_dir="$TMP_DIR/make-build-existing-apk"
    local gradle_log="$case_dir/gradle.log"
    prepare_make_workflow_fixture "$case_dir"

    GRADLE_LOG="$gradle_log" make -C "$case_dir" --no-print-directory build \
        >"$case_dir/build.stdout" 2>"$case_dir/build.stderr"

    assert_contains "$gradle_log" ":app:assembleDebug" \
        "make build with a newer existing APK"
}

test_successful_make_run_assembles_when_apk_is_newer_than_assets() {
    local case_dir="$TMP_DIR/make-run-existing-apk"
    local android_home="$case_dir/android-home"
    local gradle_log="$case_dir/gradle.log"
    local emulator_pid_file="$case_dir/emulator.pid"
    prepare_make_workflow_fixture "$case_dir"

    if ! GRADLE_LOG="$gradle_log" PATH="$case_dir/bin:$PATH" \
        make -C "$case_dir" --no-print-directory \
        -f Makefile -f preflight-overrides.mk run \
        ANDROID_HOME="$android_home" EMULATOR_PID="$emulator_pid_file" \
        >"$case_dir/run.stdout" 2>"$case_dir/run.stderr"; then
        [[ ! -f "$emulator_pid_file" ]] || kill "$(<"$emulator_pid_file")" 2>/dev/null || true
        cat "$case_dir/run.stdout" "$case_dir/run.stderr" >&2
        fail "controlled make run fixture must succeed"
    fi
    [[ ! -f "$emulator_pid_file" ]] || kill "$(<"$emulator_pid_file")" 2>/dev/null || true

    assert_contains "$gradle_log" ":app:assembleDebug" \
        "successful make run with a newer existing APK"
}

test_makefile_runtime_arch_wiring() {
    local default_runtime="$TMP_DIR/make-runtime-default.stdout"
    local x86_runtime="$TMP_DIR/make-runtime-x86.stdout"
    local x86_arch_check="$TMP_DIR/make-check-runtime-x86.stdout"
    local arm_arch_check="$TMP_DIR/make-check-runtime-arm.stdout"
    local run_dry="$TMP_DIR/make-run.stdout"

    make -C "$REPO_ROOT" --no-print-directory -n runtime > "$default_runtime"
    assert_contains "$MAKEFILE" 'runtime: override export RUNTIME_ARCH' "runtime target export"
    assert_contains "$default_runtime" 'case "$RUNTIME_ARCH" in' "default runtime validation"
    assert_contains "$default_runtime" './scripts/build-runtime.sh' "default runtime target"

    make -C "$REPO_ROOT" --no-print-directory -n runtime RUNTIME_ARCH=x86_64 > "$x86_runtime"
    assert_contains "$x86_runtime" 'case "$RUNTIME_ARCH" in' "x86_64 runtime validation"
    assert_contains "$x86_runtime" './scripts/build-runtime.sh' "x86_64 runtime target"
    assert_not_contains "$MAKEFILE" 'RUNTIME_ARCH=$(RUNTIME_ARCH)' "runtime shell interpolation"

    make -C "$REPO_ROOT" --no-print-directory -n check-runtime-arch \
        'SYSTEM_IMAGE=system-images;android-34;default;x86_64' > "$x86_arch_check"
    assert_contains "$MAKEFILE" \
        'check-runtime-arch: override export SYSTEM_IMAGE := $(value SYSTEM_IMAGE)' \
        "SYSTEM_IMAGE target export"
    assert_contains "$x86_arch_check" 'emulator_abi="${SYSTEM_IMAGE##*;}"' \
        "x86_64 shell ABI derivation"
    assert_contains "$x86_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libproot.so"' \
        "x86_64 packaged proot derivation"
    assert_contains "$x86_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libproot-loader.so"' \
        "x86_64 packaged proot loader derivation"
    assert_contains "$x86_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libtalloc.so"' \
        "x86_64 packaged talloc derivation"
    assert_contains "$x86_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libandroid-shmem.so"' \
        "x86_64 packaged shmem derivation"
    assert_not_contains "$x86_arch_check" "android/app/src/main/assets/linux/proot" \
        "x86_64 obsolete proot asset"

    make -C "$REPO_ROOT" --no-print-directory -n check-runtime-arch \
        'SYSTEM_IMAGE=system-images;android-34;default;arm64-v8a' > "$arm_arch_check"
    assert_contains "$arm_arch_check" 'emulator_abi="${SYSTEM_IMAGE##*;}"' \
        "arm64-v8a shell ABI derivation"
    assert_contains "$arm_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libproot.so"' \
        "arm64-v8a packaged proot derivation"
    assert_contains "$arm_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libproot-loader.so"' \
        "arm64-v8a packaged proot loader derivation"
    assert_contains "$arm_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libtalloc.so"' \
        "arm64-v8a packaged talloc derivation"
    assert_contains "$arm_arch_check" \
        './scripts/check-runtime-arch.sh "$emulator_abi" "android/app/src/main/jniLibs/$emulator_abi/libandroid-shmem.so"' \
        "arm64-v8a packaged shmem derivation"
    assert_not_contains "$arm_arch_check" "android/app/src/main/assets/linux/proot" \
        "arm64-v8a obsolete proot asset"
    assert_not_contains "$MAKEFILE" 'EMULATOR_ABI' "unsafe Make ABI derivation"
    assert_not_contains "$MAKEFILE" 'PACKAGED_PROOT' "unsafe Make proot path derivation"

    make -C "$REPO_ROOT" --no-print-directory -n run > "$run_dry"
    assert_contains "$MAKEFILE" 'run: check-deps check-runtime-arch' "run preflight prerequisites"
    assert_contains "$run_dry" "./scripts/check-runtime-arch.sh" "run dry-run preflight"
    assert_contains "$run_dry" 'make --no-print-directory build' "run APK build"
    assert_before "$run_dry" "./scripts/check-runtime-arch.sh" \
        'make --no-print-directory build' \
        "architecture check before APK build"
    assert_before "$run_dry" \
        'make --no-print-directory build' \
        "AVD 'seed_dev' not found" "APK build before AVD launch"
    assert_not_contains "$run_dry" "scripts/build-runtime.sh" "run dry-run"
    assert_contains "$MAKEFILE" 'command -v file >/dev/null' "file dependency check"
    assert_contains "$MAKEFILE" 'sudo apt install file' "file dependency diagnostic"
}

test_parallel_run_failure_does_not_build_or_launch() {
    local case_dir="$TMP_DIR/parallel-run-failure"
    local android_home="$case_dir/android-home"
    local gradle_log="$case_dir/gradle.log"
    local emulator_log="$case_dir/emulator.log"
    prepare_make_workflow_fixture "$case_dir"

    cat > "$case_dir/preflight-overrides.mk" <<'MAKE_FIXTURE'
.PHONY: check-deps check-runtime-arch
check-deps:
	@:
check-runtime-arch:
	@/bin/sleep 0.2; echo "controlled architecture failure"; exit 1
MAKE_FIXTURE

    if GRADLE_LOG="$gradle_log" PATH="$case_dir/bin:$PATH" \
        make -C "$case_dir" --no-print-directory -j4 \
        -f Makefile -f preflight-overrides.mk run ANDROID_HOME="$android_home" \
        >"$case_dir/run.stdout" 2>&1; then
        fail "run must fail when architecture preflight fails"
    fi
    assert_contains "$case_dir/run.stdout" "controlled architecture failure" \
        "parallel run failure"
    if [[ -s "$gradle_log" ]]; then
        fail "parallel run invoked Gradle before architecture preflight succeeded"
    fi
    if [[ -e "$emulator_log" ]]; then
        fail "parallel run launched the emulator before architecture preflight succeeded"
    fi
}

test_system_image_wiring_maps_supported_abis() {
    local case_dir="$TMP_DIR/system-image-wiring"
    local checker_log="$case_dir/checker.log"
    local expected image
    prepare_make_workflow_fixture "$case_dir"
    : > "$checker_log"

    for expected in x86_64 arm64-v8a; do
        image="system-images;android-34;default;$expected"
        CHECKER_CALL_LOG="$checker_log" make -C "$case_dir" --no-print-directory \
            check-runtime-arch SYSTEM_IMAGE="$image" \
            > "$case_dir/$expected.stdout" 2> "$case_dir/$expected.stderr"
    done

    assert_line_count "8" "$checker_log" "packaged native bundle checker call count"
    assert_contains "$checker_log" \
        'system-images;android-34;default;x86_64|x86_64|android/app/src/main/jniLibs/x86_64/libproot.so' \
        "x86_64 proot checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;x86_64|x86_64|android/app/src/main/jniLibs/x86_64/libproot-loader.so' \
        "x86_64 proot loader checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;x86_64|x86_64|android/app/src/main/jniLibs/x86_64/libtalloc.so' \
        "x86_64 talloc checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;x86_64|x86_64|android/app/src/main/jniLibs/x86_64/libandroid-shmem.so' \
        "x86_64 shmem checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;arm64-v8a|arm64-v8a|android/app/src/main/jniLibs/arm64-v8a/libproot.so' \
        "arm64-v8a proot checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;arm64-v8a|arm64-v8a|android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so' \
        "arm64-v8a proot loader checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;arm64-v8a|arm64-v8a|android/app/src/main/jniLibs/arm64-v8a/libtalloc.so' \
        "arm64-v8a talloc checker wiring"
    assert_contains "$checker_log" \
        'system-images;android-34;default;arm64-v8a|arm64-v8a|android/app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so' \
        "arm64-v8a shmem checker wiring"
}

assert_loader_preflight_failure_stops_run() {
    local failure_mode="$1"
    local case_dir="$TMP_DIR/loader-preflight-$failure_mode"
    local android_home="$case_dir/android-home"
    local native_dir="$case_dir/android/app/src/main/jniLibs/x86_64"
    local gradle_log="$case_dir/gradle.log"
    local emulator_log="$case_dir/emulator.log"
    local emulator_pid_file="$case_dir/emulator.pid"
    prepare_make_workflow_fixture "$case_dir"
    mkdir -p "$native_dir"
    printf '%s' 'controlled proot' > "$native_dir/libproot.so"
    cp "$ARCH_CHECKER" "$case_dir/scripts/check-runtime-arch.sh"

    cat > "$case_dir/bin/file" <<'FAKE_FILE'
#!/usr/bin/env bash
set -euo pipefail
case "${!#}" in
    *libproot-loader.so)
        printf '%s\n' 'ELF 64-bit LSB executable, ARM aarch64, statically linked'
        ;;
    *)
        printf '%s\n' 'ELF 64-bit LSB executable, x86-64, statically linked'
        ;;
esac
FAKE_FILE
    chmod +x "$case_dir/bin/file"

    if [[ "$failure_mode" == "mismatched" ]]; then
        printf '%s' 'controlled mismatched loader' > "$native_dir/libproot-loader.so"
    fi

    cat > "$case_dir/deps-override.mk" <<'MAKE_FIXTURE'
.PHONY: check-deps
check-deps:
	@:
MAKE_FIXTURE

    if GRADLE_LOG="$gradle_log" PATH="$case_dir/bin:$PATH" \
        make -C "$case_dir" --no-print-directory \
        -f Makefile -f deps-override.mk run \
        ANDROID_HOME="$android_home" EMULATOR_PID="$emulator_pid_file" \
        'SYSTEM_IMAGE=system-images;android-34;default;x86_64' \
        > "$case_dir/run.stdout" 2>&1; then
        [[ ! -f "$emulator_pid_file" ]] \
            || kill "$(<"$emulator_pid_file")" 2>/dev/null || true
        fail "$failure_mode loader must fail make run"
    fi
    [[ ! -f "$emulator_pid_file" ]] \
        || kill "$(<"$emulator_pid_file")" 2>/dev/null || true

    assert_contains "$case_dir/run.stdout" \
        "android/app/src/main/jniLibs/x86_64/libproot-loader.so" \
        "$failure_mode loader diagnostic path"
    assert_contains "$case_dir/run.stdout" \
        "Build compatible assets explicitly: make runtime RUNTIME_ARCH=x86_64" \
        "$failure_mode loader repair command"
    if [[ -s "$gradle_log" ]]; then
        fail "$failure_mode loader invoked Gradle before preflight succeeded"
    fi
    if [[ -e "$emulator_log" ]]; then
        fail "$failure_mode loader launched the emulator before preflight succeeded"
    fi
}

test_missing_loader_fails_before_build_or_launch() {
    assert_loader_preflight_failure_stops_run missing
}

test_mismatched_loader_fails_before_build_or_launch() {
    assert_loader_preflight_failure_stops_run mismatched
}

assert_dependency_preflight_failure_stops_run() {
    local artifact="$1"
    local failure_mode="$2"
    local case_dir="$TMP_DIR/dependency-preflight-$artifact-$failure_mode"
    local android_home="$case_dir/android-home"
    local native_dir="$case_dir/android/app/src/main/jniLibs/x86_64"
    local gradle_log="$case_dir/gradle.log"
    local emulator_pid_file="$case_dir/emulator.pid"
    prepare_make_workflow_fixture "$case_dir"
    mkdir -p "$native_dir"
    printf '%s' 'controlled proot' > "$native_dir/libproot.so"
    printf '%s' 'controlled loader' > "$native_dir/libproot-loader.so"
    if [[ "$artifact" == "libandroid-shmem.so" ]]; then
        printf '%s' 'controlled talloc' > "$native_dir/libtalloc.so"
    fi
    if [[ "$failure_mode" == "mismatched" ]]; then
        printf '%s' 'controlled mismatch' > "$native_dir/$artifact"
    fi
    cp "$ARCH_CHECKER" "$case_dir/scripts/check-runtime-arch.sh"

    cat > "$case_dir/bin/file" <<'FAKE_FILE'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${!#}" == *"/${MISMATCHED_BASENAME:-<none>}" ]]; then
    printf '%s\n' 'ELF 64-bit LSB shared object, ARM aarch64, dynamically linked'
else
    printf '%s\n' 'ELF 64-bit LSB shared object, x86-64, dynamically linked'
fi
FAKE_FILE
    chmod +x "$case_dir/bin/file"

    cat > "$case_dir/deps-override.mk" <<'MAKE_FIXTURE'
.PHONY: check-deps
check-deps:
	@:
MAKE_FIXTURE

    if GRADLE_LOG="$gradle_log" MISMATCHED_BASENAME="$artifact" \
        PATH="$case_dir/bin:$PATH" make -C "$case_dir" --no-print-directory \
        -f Makefile -f deps-override.mk run ANDROID_HOME="$android_home" \
        EMULATOR_PID="$emulator_pid_file" \
        'SYSTEM_IMAGE=system-images;android-34;default;x86_64' \
        > "$case_dir/run.stdout" 2>&1; then
        [[ ! -f "$emulator_pid_file" ]] \
            || kill "$(<"$emulator_pid_file")" 2>/dev/null || true
        fail "$failure_mode $artifact must fail make run"
    fi

    assert_contains "$case_dir/run.stdout" \
        "android/app/src/main/jniLibs/x86_64/$artifact" \
        "$failure_mode dependency diagnostic path"
    assert_contains "$case_dir/run.stdout" \
        "make runtime RUNTIME_ARCH=x86_64" \
        "$failure_mode dependency repair command"
    if [[ -s "$gradle_log" ]]; then
        fail "$failure_mode $artifact invoked Gradle"
    fi
}

test_missing_talloc_fails_preflight_before_build_or_launch() {
    assert_dependency_preflight_failure_stops_run libtalloc.so missing
}

test_mismatched_shmem_fails_preflight_before_build_or_launch() {
    assert_dependency_preflight_failure_stops_run libandroid-shmem.so mismatched
}

test_system_image_rejects_shell_injection() {
    local case_dir="$TMP_DIR/system-image-injection"
    local android_home="$case_dir/android-home"
    local checker_log="$case_dir/checker.log"
    local gradle_log="$case_dir/gradle.log"
    local emulator_log="$case_dir/emulator.log"
    local emulator_pid_file="$case_dir/emulator.pid"
    local marker payload label make_succeeded
    local index=0
    local -a payloads
    prepare_make_workflow_fixture "$case_dir"

    cat > "$case_dir/deps-override.mk" <<'MAKE_FIXTURE'
.PHONY: check-deps
check-deps:
	@:
MAKE_FIXTURE

    payloads=(
        'system-images;android-34;default;x86_64$(touch${IFS}MARKER)'
        'system-images;android-34;default;x86_64";touch${IFS}MARKER;#'
    )

    for payload in "${payloads[@]}"; do
        index=$((index + 1))
        label="SYSTEM_IMAGE injection payload $index"
        marker="$case_dir/injected-$index"
        payload="${payload/MARKER/$marker}"
        : > "$checker_log"
        : > "$gradle_log"
        rm -f "$marker" "$emulator_log" "$emulator_pid_file"

        if CHECKER_CALL_LOG="$checker_log" GRADLE_LOG="$gradle_log" \
            PATH="$case_dir/bin:$PATH" make -C "$case_dir" --no-print-directory \
            -f Makefile -f deps-override.mk run \
            ANDROID_HOME="$android_home" EMULATOR_PID="$emulator_pid_file" \
            SYSTEM_IMAGE="$payload" > "$case_dir/injection-$index.stdout" 2>&1; then
            make_succeeded=true
        else
            make_succeeded=false
        fi
        [[ ! -f "$emulator_pid_file" ]] \
            || kill "$(<"$emulator_pid_file")" 2>/dev/null || true

        if [[ "$make_succeeded" == true ]]; then
            fail "$label must fail make run"
        fi
        if [[ -e "$marker" ]]; then
            fail "$label created a shell marker"
        fi
        if [[ -s "$checker_log" ]]; then
            fail "$label reached the architecture checker"
        fi
        if [[ -s "$gradle_log" ]]; then
            fail "$label bypassed preflight and invoked Gradle"
        fi
        if [[ -e "$emulator_log" ]]; then
            fail "$label bypassed preflight and launched the emulator"
        fi
        assert_contains "$case_dir/injection-$index.stdout" \
            "unsupported emulator ABI:" "$label rejection"
    done
}

test_runtime_arch_rejects_shell_injection() {
    local shell_marker="$TMP_DIR/runtime-arch-shell-injected"
    local make_marker="$TMP_DIR/runtime-arch-make-injected"
    local output_file="$TMP_DIR/runtime-arch-injection.stdout"
    local shell_malicious="arm64; touch $shell_marker; false"
    local make_malicious="invalid\$(shell touch $make_marker)"

    if make -C "$REPO_ROOT" --no-print-directory runtime \
        RUNTIME_ARCH="$shell_malicious" >"$output_file" 2>&1; then
        fail "invalid runtime architecture must fail"
    fi
    assert_contains "$output_file" "unsupported runtime architecture:" "invalid runtime architecture"
    if [[ -e "$shell_marker" ]]; then
        fail "RUNTIME_ARCH shell metacharacters caused an injected side effect"
    fi

    if make -C "$REPO_ROOT" --no-print-directory runtime \
        RUNTIME_ARCH="$make_malicious" >"$output_file" 2>&1; then
        fail "make expression runtime architecture must fail"
    fi
    assert_contains "$output_file" "unsupported runtime architecture:" "make expression runtime architecture"
    if [[ -e "$make_marker" ]]; then
        fail "RUNTIME_ARCH make expansion caused an injected side effect"
    fi
}

test_native_bundle_builder_declares_prerequisites() {
    assert_contains "$BUILD_SCRIPT" "command -v ar >/dev/null" "ar prerequisite"
    assert_contains "$BUILD_SCRIPT" "command -v readelf >/dev/null" "readelf prerequisite"
    assert_contains "$BUILD_SCRIPT" "command -v python3 >/dev/null" "python prerequisite"
    assert_not_contains "$BUILD_SCRIPT" "command -v dd >/dev/null" "obsolete dd prerequisite"
}

test_native_bundle_builder_extracts_known_termux_files() {
    assert_contains "$BUILD_SCRIPT" \
        'data/data/com.termux/files/usr/bin/proot' "Termux proot package path"
    assert_contains "$BUILD_SCRIPT" \
        'data/data/com.termux/files/usr/libexec/proot/loader' "Termux loader package path"
    assert_contains "$BUILD_SCRIPT" \
        'data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3' "Termux talloc package path"
    assert_contains "$BUILD_SCRIPT" \
        'data/data/com.termux/files/usr/lib/libandroid-shmem.so' "Termux shmem package path"
    assert_contains "$BUILD_SCRIPT" 'libtalloc.so.2' "old talloc dependency rewrite input"
    assert_contains "$BUILD_SCRIPT" 'libtalloc.so' "Android-packageable talloc dependency"
    assert_contains "$BUILD_SCRIPT" "expected exactly one libtalloc.so.2 dependency" \
        "single dependency rewrite guard"
}

test_native_bundle_builder_validates_and_publishes_complete_unit() {
    assert_contains "$BUILD_SCRIPT" 'native_bundle_matches_target' "complete bundle validator"
    assert_contains "$BUILD_SCRIPT" 'SELECTED_TALLOC' "selected talloc artifact"
    assert_contains "$BUILD_SCRIPT" 'SELECTED_ANDROID_SHMEM' "selected shmem artifact"
    assert_contains "$BUILD_SCRIPT" 'OPPOSITE_TALLOC' "opposite talloc cleanup"
    assert_contains "$BUILD_SCRIPT" 'OPPOSITE_ANDROID_SHMEM' "opposite shmem cleanup"
    assert_contains "$BUILD_SCRIPT" 'PUBLISH_NATIVE_BUNDLE' "unit publication gate"
    assert_contains "$BUILD_SCRIPT" 'readelf -d' "dynamic dependency validation"
    assert_contains "$BUILD_SCRIPT" 'libandroid-shmem.so' "shmem dependency validation"
    assert_contains "$BUILD_SCRIPT" 'libtalloc.so.2' "versioned talloc dependency rejection"
}

test_dd_extracts_fixture_bytes() {
    local fixture="$TMP_DIR/dd-fixture.bin"
    local extracted="$TMP_DIR/dd-extracted.bin"
    printf '%s' 'prefix-loader-data-suffix' > "$fixture"

    dd if="$fixture" of="$extracted" bs=1 skip=7 count=11 2>/dev/null

    assert_eq "loader-data" "$(<"$extracted")" "standard dd fixture extraction"
}

test_builder_declares_dd_prerequisite() {
    assert_contains "$BUILD_SCRIPT" "command -v dd >/dev/null" "dd prerequisite"
}

test_generated_native_proot_ignore_rules() {
    local arm64_path="android/app/src/main/jniLibs/arm64-v8a/libproot.so"
    local arm64_loader_path="android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
    local x86_path="android/app/src/main/jniLibs/x86_64/libproot.so"
    local x86_loader_path="android/app/src/main/jniLibs/x86_64/libproot-loader.so"
    local arm64_talloc_path="android/app/src/main/jniLibs/arm64-v8a/libtalloc.so"
    local arm64_shmem_path="android/app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so"
    local x86_talloc_path="android/app/src/main/jniLibs/x86_64/libtalloc.so"
    local x86_shmem_path="android/app/src/main/jniLibs/x86_64/libandroid-shmem.so"
    local future_source="android/app/src/main/jniLibs/arm64-v8a/proot-loader.c"
    local unrelated_library="android/app/src/main/jniLibs/arm64-v8a/libhelper.so"

    assert_exact_repo_gitignore_rule "$arm64_path" "generated arm64 native proot"
    assert_exact_repo_gitignore_rule "$arm64_loader_path" "generated arm64 native proot loader"
    assert_exact_repo_gitignore_rule "$x86_path" "generated x86_64 native proot"
    assert_exact_repo_gitignore_rule "$x86_loader_path" "generated x86_64 native proot loader"
    assert_exact_repo_gitignore_rule "$arm64_talloc_path" "generated arm64 native talloc"
    assert_exact_repo_gitignore_rule "$arm64_shmem_path" "generated arm64 native shmem"
    assert_exact_repo_gitignore_rule "$x86_talloc_path" "generated x86_64 native talloc"
    assert_exact_repo_gitignore_rule "$x86_shmem_path" "generated x86_64 native shmem"
    if git -C "$REPO_ROOT" -c core.excludesFile=/dev/null \
        check-ignore -v --no-index "$future_source" >/dev/null; then
        fail "future native source files must not be ignored"
    fi
    if git -C "$REPO_ROOT" -c core.excludesFile=/dev/null \
        check-ignore -v --no-index "$unrelated_library" >/dev/null; then
        fail "unrelated native libraries must not be ignored"
    fi
}

prepare_successful_runtime_build_tools() {
    local case_dir="$1"
    local fake_bin="$case_dir/bin"
    mkdir -p "$fake_bin"

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
if [[ -n "${CURL_CALL_LOG:-}" ]]; then
    printf '%s|%s\n' "$url" "$output" >> "$CURL_CALL_LOG"
fi
case "$url" in
    *proot-v5.3.0*) printf '%s' "${FAKE_PROOT_CONTENT:-downloaded-x86_64-proot}" > "$output" ;;
    *alpine-minirootfs*) printf '%s' 'controlled-alpine-archive' > "$output" ;;
    *) exit 1 ;;
esac
FAKE_CURL

    cat > "$fake_bin/file" <<'FAKE_FILE'
#!/usr/bin/env bash
set -euo pipefail
target="${!#}"
case "$(<"$target")" in
    *x86_64*|bad-x86-loader|same-arch-bad-sha)
        printf '%s\n' 'ELF 64-bit LSB executable, x86-64, statically linked'
        ;;
    *)
        printf '%s\n' 'ELF 64-bit LSB executable, ARM aarch64, statically linked'
        ;;
esac
FAKE_FILE

    cat > "$fake_bin/dd" <<'FAKE_DD'
#!/usr/bin/env bash
set -euo pipefail
input=""
output=""
block_size=""
skip_bytes=""
count_bytes=""
for argument in "$@"; do
    case "$argument" in
        if=*) input="${argument#if=}" ;;
        of=*) output="${argument#of=}" ;;
        bs=*) block_size="${argument#bs=}" ;;
        skip=*) skip_bytes="${argument#skip=}" ;;
        count=*) count_bytes="${argument#count=}" ;;
    esac
done
[[ -f "$input" && -n "$output" && "$block_size" == "1" ]]
if [[ -n "${DD_CALL_LOG:-}" ]]; then
    printf '%s|%s|%s|%s|%s\n' \
        "$input" "$output" "$block_size" "$skip_bytes" "$count_bytes" >> "$DD_CALL_LOG"
fi
printf '%s' "${FAKE_DD_CONTENT:-downloaded-x86_64-loader}" > "$output"
FAKE_DD

    cat > "$fake_bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-} ${2:-}" == "image inspect" ]]; then
    printf '%s\n' 'amd64'
elif [[ "${1:-}" == "export" ]]; then
    printf '%s' 'controlled-rootfs-tar-stream'
fi
FAKE_DOCKER

    cat > "$fake_bin/uv" <<'FAKE_UV'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'fastapi==0.0.0'
FAKE_UV

    cat > "$fake_bin/sha256sum" <<'FAKE_SHA256SUM'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != "-c" ]]; then
    printf '%064d  %s\n' 0 "${1:-}"
    exit 0
fi
read -r expected target
case "$(<"$target")" in
    downloaded-x86_64-proot|valid-x86_64-proot)
        actual='d1eb20cb201e6df08d707023efb000623ff7c10d6574839d7bb42d0adba6b4da'
        ;;
    downloaded-x86_64-loader|valid-x86_64-loader)
        actual='ca5279447ed4693b5e66e6eb1228da65a7c9c3b2fe23953c143216b55b7b9839'
        ;;
    controlled-alpine-archive)
        actual='4b4daa9fe2fc696c4919c4412a4c3d3e770d8fb70292a004a2c72f5096175282'
        ;;
    *) actual='invalid' ;;
esac
[[ "$expected" == "$actual" ]]
FAKE_SHA256SUM

    cat > "$fake_bin/tar" <<'FAKE_TAR'
#!/usr/bin/env bash
set -euo pipefail
cat <<'LISTING'
usr/bin/python3
usr/bin/node
usr/local/bin/pi
home/seed/backend/seed_backend/__init__.py
home/seed/app/seed_app/__init__.py
LISTING
FAKE_TAR

    cat > "$fake_bin/mv" <<'FAKE_MV'
#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${MV_CALL_LOG:-}" ]]; then
    printf '%s|%s\n' "$1" "$2" >> "$MV_CALL_LOG"
fi
exec /bin/mv "$@"
FAKE_MV

    chmod +x "$fake_bin/curl" "$fake_bin/file" "$fake_bin/dd" \
        "$fake_bin/docker" "$fake_bin/uv" "$fake_bin/sha256sum" \
        "$fake_bin/tar" "$fake_bin/mv"
}

test_successful_architecture_switch_publication() {
    local case_dir="$TMP_DIR/successful-architecture-switch"
    local assets_dir="$case_dir/assets"
    local jni_libs_dir="$case_dir/jniLibs"
    local jni_libs_target="$case_dir/native-volume/jniLibs"
    local fake_bin="$case_dir/bin"
    local mv_log="$case_dir/mv.log"
    local dd_log="$case_dir/dd.log"
    mkdir -p "$assets_dir" "$jni_libs_target/arm64-v8a" "$jni_libs_target/x86_64"
    ln -s "$jni_libs_target" "$jni_libs_dir"

    printf '%s' 'legacy-proot' > "$assets_dir/proot"
    printf '%s' 'old-rootfs' > "$assets_dir/rootfs.tar.gz"
    printf '%s' 'old-version' > "$assets_dir/seed_version.json"
    printf '%s' 'opposite-arm64-proot' > "$jni_libs_dir/arm64-v8a/libproot.so"
    printf '%s' 'opposite-arm64-loader' > "$jni_libs_dir/arm64-v8a/libproot-loader.so"
    printf '%s' 'same-arch-bad-sha' > "$jni_libs_dir/x86_64/libproot.so"
    printf '%s' 'bad-x86-loader' > "$jni_libs_dir/x86_64/libproot-loader.so"
    : > "$mv_log"
    : > "$dd_log"
    prepare_successful_runtime_build_tools "$case_dir"

    if ! ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        RUNTIME_ARCH=x86_64 MV_CALL_LOG="$mv_log" DD_CALL_LOG="$dd_log" \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$case_dir/build.stderr"; then
        cat "$case_dir/build.stdout" "$case_dir/build.stderr" >&2
        fail "controlled successful runtime build must succeed"
    fi

    assert_eq "downloaded-x86_64-proot" \
        "$(<"$jni_libs_dir/x86_64/libproot.so")" "published x86_64 proot"
    assert_eq "downloaded-x86_64-loader" \
        "$(<"$jni_libs_dir/x86_64/libproot-loader.so")" "published x86_64 proot loader"
    if [[ -e "$jni_libs_dir/arm64-v8a/libproot.so" \
        || -e "$jni_libs_dir/arm64-v8a/libproot-loader.so" ]]; then
        fail "successful architecture switch left the opposite proot pair"
    fi
    if [[ -d "$jni_libs_dir/arm64-v8a" ]]; then
        fail "successful architecture switch left an empty opposite ABI directory"
    fi
    if [[ -e "$assets_dir/proot" ]]; then
        fail "successful publication left the legacy proot asset"
    fi
    assert_eq "controlled-rootfs-tar-stream" \
        "$(gzip -dc "$assets_dir/rootfs.tar.gz")" "published rootfs"
    assert_contains "$assets_dir/seed_version.json" '"build_id":' "published version marker"
    assert_contains "$dd_log" "|1|1067536|8872" "x86_64 loader dd extraction range"
    assert_contains "$mv_log" "$jni_libs_dir/.seed-runtime-proot-stage." \
        "native pair staging inside JNI_LIBS_DIR"
    assert_contains "$mv_log" "|$jni_libs_dir/x86_64/libproot.so" \
        "native proot publication"
    assert_contains "$mv_log" "|$jni_libs_dir/x86_64/libproot-loader.so" \
        "native proot loader publication"
    assert_not_contains "$mv_log" "$case_dir/.seed-runtime-proot-stage." \
        "native pair staging beside JNI_LIBS_DIR"
    assert_before "$mv_log" "|$jni_libs_dir/x86_64/libproot-loader.so" \
        "$assets_dir/rootfs.tar.gz" "native pair before rootfs"
    assert_before "$mv_log" "$assets_dir/rootfs.tar.gz" \
        "$assets_dir/seed_version.json" "rootfs before completion marker"
    if compgen -G "$assets_dir/.seed-runtime-stage.*" >/dev/null \
        || compgen -G "$jni_libs_dir/.seed-runtime-proot-stage.*" >/dev/null; then
        fail "successful build left a staging directory"
    fi
}

test_valid_native_pair_is_reused() {
    local case_dir="$TMP_DIR/valid-native-pair"
    local assets_dir="$case_dir/assets"
    local jni_libs_dir="$case_dir/jniLibs"
    local fake_bin="$case_dir/bin"
    local curl_log="$case_dir/curl.log"
    local dd_log="$case_dir/dd.log"
    mkdir -p "$assets_dir" "$jni_libs_dir/x86_64"
    printf '%s' 'valid-x86_64-proot' > "$jni_libs_dir/x86_64/libproot.so"
    printf '%s' 'valid-x86_64-loader' > "$jni_libs_dir/x86_64/libproot-loader.so"
    : > "$curl_log"
    : > "$dd_log"
    prepare_successful_runtime_build_tools "$case_dir"

    if ! ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        RUNTIME_ARCH=x86_64 CURL_CALL_LOG="$curl_log" DD_CALL_LOG="$dd_log" \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$case_dir/build.stderr"; then
        cat "$case_dir/build.stdout" "$case_dir/build.stderr" >&2
        fail "build with a valid native pair must succeed"
    fi

    assert_eq "valid-x86_64-proot" \
        "$(<"$jni_libs_dir/x86_64/libproot.so")" "reused native proot"
    assert_eq "valid-x86_64-loader" \
        "$(<"$jni_libs_dir/x86_64/libproot-loader.so")" "reused native loader"
    assert_not_contains "$curl_log" "proot-v5.3.0" "valid native pair proot download"
    if [[ -s "$dd_log" ]]; then
        fail "valid native pair must not be re-extracted"
    fi
}

test_valid_proot_repairs_loader_without_download() {
    local case_dir="$TMP_DIR/repair-native-loader"
    local assets_dir="$case_dir/assets"
    local jni_libs_dir="$case_dir/jniLibs"
    local fake_bin="$case_dir/bin"
    local curl_log="$case_dir/curl.log"
    local dd_log="$case_dir/dd.log"
    mkdir -p "$assets_dir" "$jni_libs_dir/x86_64"
    printf '%s' 'valid-x86_64-proot' > "$jni_libs_dir/x86_64/libproot.so"
    printf '%s' 'bad-x86-loader' > "$jni_libs_dir/x86_64/libproot-loader.so"
    : > "$curl_log"
    : > "$dd_log"
    prepare_successful_runtime_build_tools "$case_dir"

    if ! ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        RUNTIME_ARCH=x86_64 CURL_CALL_LOG="$curl_log" DD_CALL_LOG="$dd_log" \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$case_dir/build.stderr"; then
        cat "$case_dir/build.stdout" "$case_dir/build.stderr" >&2
        fail "build that repairs only the loader must succeed"
    fi

    assert_eq "valid-x86_64-proot" \
        "$(<"$jni_libs_dir/x86_64/libproot.so")" "preserved valid native proot"
    assert_eq "downloaded-x86_64-loader" \
        "$(<"$jni_libs_dir/x86_64/libproot-loader.so")" "repaired native loader"
    assert_not_contains "$curl_log" "proot-v5.3.0" "loader-only repair proot download"
    assert_contains "$dd_log" "|1|1067536|8872" "loader-only dd extraction range"
}

test_invalid_extracted_loader_preserves_native_pair() {
    local case_dir="$TMP_DIR/invalid-extracted-loader"
    local assets_dir="$case_dir/assets"
    local jni_libs_dir="$case_dir/jniLibs"
    local originals_dir="$case_dir/originals"
    local fake_bin="$case_dir/bin"
    mkdir -p "$assets_dir" "$jni_libs_dir/x86_64" "$originals_dir"
    printf '%s' 'valid-x86_64-proot' > "$jni_libs_dir/x86_64/libproot.so"
    printf '%s' 'bad-x86-loader' > "$jni_libs_dir/x86_64/libproot-loader.so"
    cp -a "$jni_libs_dir/." "$originals_dir/"
    prepare_successful_runtime_build_tools "$case_dir"

    if ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        RUNTIME_ARCH=x86_64 FAKE_DD_CONTENT=bad-x86-loader \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$case_dir/build.stderr"; then
        fail "build with an invalid extracted loader must fail"
    fi

    assert_contains "$case_dir/build.stderr" "proot loader sha256 mismatch" \
        "invalid extracted loader"
    if ! diff -r "$originals_dir" "$jni_libs_dir" >/dev/null; then
        fail "invalid extracted loader changed the selected native pair"
    fi
}

test_invalid_downloaded_proot_preserves_native_pairs() {
    local case_dir="$TMP_DIR/invalid-downloaded-proot"
    local assets_dir="$case_dir/assets"
    local jni_libs_dir="$case_dir/jniLibs"
    local originals_dir="$case_dir/originals"
    local fake_bin="$case_dir/bin"
    mkdir -p "$assets_dir" "$jni_libs_dir/arm64-v8a" \
        "$jni_libs_dir/x86_64" "$originals_dir"
    printf '%s' 'old-arm64-proot' > "$jni_libs_dir/arm64-v8a/libproot.so"
    printf '%s' 'old-arm64-loader' > "$jni_libs_dir/arm64-v8a/libproot-loader.so"
    printf '%s' 'same-arch-bad-sha' > "$jni_libs_dir/x86_64/libproot.so"
    printf '%s' 'bad-x86-loader' > "$jni_libs_dir/x86_64/libproot-loader.so"
    cp -a "$jni_libs_dir/." "$originals_dir/"
    prepare_successful_runtime_build_tools "$case_dir"

    if ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        RUNTIME_ARCH=x86_64 FAKE_PROOT_CONTENT=same-arch-bad-sha \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$case_dir/build.stderr"; then
        fail "build with an invalid downloaded proot checksum must fail"
    fi

    assert_contains "$case_dir/build.stderr" "downloaded proot sha256 mismatch" \
        "invalid downloaded proot"
    if ! diff -r "$originals_dir" "$jni_libs_dir" >/dev/null; then
        fail "invalid downloaded proot changed native pairs"
    fi
}

prepare_checksum_failure_runtime_build_tools() {
    local case_dir="$1"
    local fake_bin="$case_dir/bin"
    mkdir -p "$fake_bin"

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
if [[ -n "${FILE_LC_LOG:-}" ]]; then
    printf '%s\n' "${LC_ALL:-<unset>}" >> "$FILE_LC_LOG"
fi
target="${!#}"
case "$(<"$target")" in
    downloaded-arm64-proot|downloaded-arm64-loader)
        printf '%s\n' 'ELF 64-bit LSB executable, ARM aarch64'
        ;;
    *)
        printf '%s\n' 'ELF 64-bit LSB executable, x86-64'
        ;;
esac
FAKE_FILE

    cat > "$fake_bin/dd" <<'FAKE_DD'
#!/usr/bin/env bash
set -euo pipefail
output=""
for argument in "$@"; do
    case "$argument" in
        of=*) output="${argument#of=}" ;;
    esac
done
[[ -n "$output" ]]
printf '%s' 'downloaded-arm64-loader' > "$output"
FAKE_DD

    cat > "$fake_bin/sha256sum" <<'FAKE_SHA256SUM'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != "-c" ]]; then
    printf '%064d  %s\n' 0 "${1:-}"
    exit 0
fi
read -r expected target
case "$(<"$target")" in
    downloaded-arm64-proot)
        actual='fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45'
        ;;
    downloaded-arm64-loader)
        actual='51c3427b112edc70d1979b48209c41f332616758138de3be659cc79e50436450'
        ;;
    *) actual='invalid' ;;
esac
[[ "$expected" == "$actual" ]]
FAKE_SHA256SUM

    cat > "$fake_bin/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
exit 0
FAKE_DOCKER
    chmod +x "$fake_bin/curl" "$fake_bin/file" "$fake_bin/dd" \
        "$fake_bin/sha256sum" "$fake_bin/docker"
}

test_failed_build_preserves_assets() {
    local case_dir="$TMP_DIR/failed-build"
    local assets_dir="$case_dir/assets"
    local originals_dir="$case_dir/originals"
    local jni_libs_dir="$case_dir/jniLibs"
    local original_jni_libs_dir="$case_dir/original-jniLibs"
    local fake_bin="$case_dir/bin"
    local stderr_file="$case_dir/build.stderr"
    local file_lc_log="$case_dir/file-lc.log"
    mkdir -p "$assets_dir" "$originals_dir" "$jni_libs_dir/arm64-v8a" \
        "$jni_libs_dir/x86_64" "$fake_bin"

    printf '%s' 'existing-legacy-proot' > "$assets_dir/proot"
    printf '%s' 'existing-rootfs' > "$assets_dir/rootfs.tar.gz"
    printf '%s' 'existing-version' > "$assets_dir/seed_version.json"
    cp "$assets_dir/proot" "$originals_dir/proot"
    cp "$assets_dir/rootfs.tar.gz" "$originals_dir/rootfs.tar.gz"
    cp "$assets_dir/seed_version.json" "$originals_dir/seed_version.json"
    printf '%s' 'existing-wrong-selected-proot' > "$jni_libs_dir/arm64-v8a/libproot.so"
    printf '%s' 'existing-wrong-selected-loader' > "$jni_libs_dir/arm64-v8a/libproot-loader.so"
    printf '%s' 'existing-opposite-proot' > "$jni_libs_dir/x86_64/libproot.so"
    printf '%s' 'existing-opposite-loader' > "$jni_libs_dir/x86_64/libproot-loader.so"
    cp -a "$jni_libs_dir" "$original_jni_libs_dir"

    prepare_checksum_failure_runtime_build_tools "$case_dir"

    if ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        FILE_LC_LOG="$file_lc_log" PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$stderr_file"; then
        fail "build with invalid Alpine checksum must fail"
    fi

    assert_contains "$stderr_file" "Alpine sha256 mismatch" "failed build"
    assert_same_file "$originals_dir/proot" "$assets_dir/proot" "existing proot"
    assert_same_file "$originals_dir/rootfs.tar.gz" "$assets_dir/rootfs.tar.gz" "existing rootfs"
    assert_same_file "$originals_dir/seed_version.json" "$assets_dir/seed_version.json" "existing version marker"
    if ! diff -r "$original_jni_libs_dir" "$jni_libs_dir" >/dev/null; then
        diff -r "$original_jni_libs_dir" "$jni_libs_dir" >&2 || true
        fail "failed build changed native proot or loader files"
    fi
    if compgen -G "$assets_dir/.seed-runtime-stage.*" >/dev/null \
        || compgen -G "$jni_libs_dir/.seed-runtime-proot-stage.*" >/dev/null; then
        fail "failed build left a staging directory"
    fi
    if grep -Fvxq 'C' "$file_lc_log"; then
        fail "file parsing must run with LC_ALL=C"
    fi
}

test_failed_build_removes_new_empty_jni_libs_dir() {
    local case_dir="$TMP_DIR/failed-build-new-jni-libs"
    local assets_dir="$case_dir/assets"
    local native_parent="$case_dir/native-parent"
    local jni_libs_dir="$native_parent/jniLibs"
    local unrelated_file="$native_parent/unrelated.txt"
    local fake_bin="$case_dir/bin"
    mkdir -p "$assets_dir" "$native_parent"
    printf '%s' 'preserve me' > "$unrelated_file"
    prepare_checksum_failure_runtime_build_tools "$case_dir"

    if ASSETS_DIR="$assets_dir" JNI_LIBS_DIR="$jni_libs_dir" \
        PATH="$fake_bin:$PATH" "$BUILD_SCRIPT" \
        > "$case_dir/build.stdout" 2> "$case_dir/build.stderr"; then
        fail "build with invalid Alpine checksum must fail"
    fi

    assert_contains "$case_dir/build.stderr" "Alpine sha256 mismatch" \
        "failed build with new JNI_LIBS_DIR"
    if [[ -e "$jni_libs_dir" ]]; then
        fail "failed build left a newly-created empty JNI_LIBS_DIR"
    fi
    assert_eq "preserve me" "$(<"$unrelated_file")" \
        "unrelated native-parent content"
}

run_test "arm64 target configuration" test_arm64_target
run_test "x86_64 target configuration" test_x86_64_target
run_test "arm64 default target" test_default_target
run_test "target configuration can be repeated and changed" test_target_can_be_reconfigured
run_test "Dockerfile has valid multi-platform Alpine default" test_dockerfile_base_image_default
run_test "Dockerfile validates pi-compatible Node" test_dockerfile_validates_pi_node_runtime
run_test "native bundle builder declares prerequisites" test_native_bundle_builder_declares_prerequisites
run_test "native bundle builder extracts known Termux files" test_native_bundle_builder_extracts_known_termux_files
run_test "native bundle builder validates and publishes complete unit" test_native_bundle_builder_validates_and_publishes_complete_unit
run_test "generated native proot ignore rules are exact" test_generated_native_proot_ignore_rules
run_test "checker accepts x86_64 aliases" test_checker_accepts_x86_64_aliases
run_test "checker accepts arm64 aliases" test_checker_accepts_arm64_aliases
run_test "checker reports arm64 mismatch command" test_checker_reports_arm64_mismatch_command
run_test "checker reports x86_64 mismatch command" test_checker_reports_x86_64_mismatch_command
run_test "checker requires explicit binary path" test_checker_requires_explicit_binary_path
run_test "checker rejects missing binary" test_checker_rejects_missing_binary
run_test "checker rejects non-ELF binary" test_checker_rejects_non_elf_binary
run_test "checker rejects unrecognized ELF architecture" test_checker_rejects_unrecognized_elf_architecture
run_test "checker rejects unsupported expected architecture" test_checker_rejects_unsupported_expected_architecture
run_test "make build assembles with a newer existing APK" test_make_build_assembles_when_apk_is_newer_than_assets
run_test "successful make run assembles with a newer existing APK" test_successful_make_run_assembles_when_apk_is_newer_than_assets
run_test "Makefile runtime architecture wiring" test_makefile_runtime_arch_wiring
run_test "SYSTEM_IMAGE wiring maps supported ABIs" test_system_image_wiring_maps_supported_abis
run_test "missing loader fails before build or launch" test_missing_loader_fails_before_build_or_launch
run_test "mismatched loader fails before build or launch" test_mismatched_loader_fails_before_build_or_launch
run_test "missing talloc fails preflight before build or launch" test_missing_talloc_fails_preflight_before_build_or_launch
run_test "mismatched shmem fails preflight before build or launch" test_mismatched_shmem_fails_preflight_before_build_or_launch
run_test "parallel run failure does not build or launch" test_parallel_run_failure_does_not_build_or_launch
run_test "SYSTEM_IMAGE rejects shell injection" test_system_image_rejects_shell_injection
run_test "runtime architecture rejects shell injection" test_runtime_arch_rejects_shell_injection
run_test "unsupported target rejection" test_unsupported_target

if [[ "$TESTS_RUN" -eq 0 ]]; then
    fail "no runtime tool tests matched filter '${RUNTIME_TOOLS_TEST_FILTER:-}'"
fi
echo "All $TESTS_RUN runtime tool tests passed."

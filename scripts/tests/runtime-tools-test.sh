#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
HELPER="$REPO_ROOT/scripts/runtime-target.sh"
BUILD_SCRIPT="$REPO_ROOT/scripts/build-runtime.sh"
ARCH_CHECKER="$REPO_ROOT/scripts/check-runtime-arch.sh"
MAKEFILE="$REPO_ROOT/Makefile"
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
        PATH="$fake_bin:$PATH" "$ARCH_CHECKER" arm64 "$binary" \
        >"$TMP_DIR/arm-mismatch.stdout" 2>"$stderr_file"; then
        fail "arm64 expectation against x86_64 runtime must fail"
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

test_checker_rejects_missing_binary() {
    local missing="$TMP_DIR/does-not-exist"
    local stderr_file="$TMP_DIR/missing.stderr"
    if "$ARCH_CHECKER" x86_64 "$missing" >"$TMP_DIR/missing.stdout" 2>"$stderr_file"; then
        fail "missing runtime binary must fail"
    fi
    assert_contains "$stderr_file" "runtime binary not found: $missing" "missing runtime"
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
    local arch_check="$TMP_DIR/make-check-runtime.stdout"
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
        'SYSTEM_IMAGE=system-images;android-34;default;amd64' > "$arch_check"
    assert_contains "$arch_check" \
        './scripts/check-runtime-arch.sh "amd64" "android/app/src/main/assets/linux/proot"' \
        "emulator architecture derivation"

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

test_parallel_run_failure_does_not_build_apk() {
    local fixture="$TMP_DIR/run-order.mk"
    local output_file="$TMP_DIR/run-order.stdout"
    local build_marker="$TMP_DIR/apk-build-ran"
    cat > "$fixture" <<'MAKE_FIXTURE'
.PHONY: check-deps check-runtime-arch build
check-deps:
	@:
check-runtime-arch:
	@sleep 0.2; echo "controlled architecture failure"; exit 1
build:
	@touch "$(BUILD_MARKER)"
MAKE_FIXTURE

    if make -C "$REPO_ROOT" --no-print-directory -j4 \
        -f "$MAKEFILE" -f "$fixture" run BUILD_MARKER="$build_marker" \
        >"$output_file" 2>&1; then
        fail "run must fail when architecture preflight fails"
    fi
    assert_contains "$output_file" "controlled architecture failure" "parallel run failure"
    if [[ -e "$build_marker" ]]; then
        fail "parallel run built the APK before architecture preflight succeeded"
    fi
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
run_test "checker accepts x86_64 aliases" test_checker_accepts_x86_64_aliases
run_test "checker accepts arm64 aliases" test_checker_accepts_arm64_aliases
run_test "checker reports arm64 mismatch command" test_checker_reports_arm64_mismatch_command
run_test "checker reports x86_64 mismatch command" test_checker_reports_x86_64_mismatch_command
run_test "checker rejects missing binary" test_checker_rejects_missing_binary
run_test "checker rejects non-ELF binary" test_checker_rejects_non_elf_binary
run_test "checker rejects unrecognized ELF architecture" test_checker_rejects_unrecognized_elf_architecture
run_test "checker rejects unsupported expected architecture" test_checker_rejects_unsupported_expected_architecture
run_test "make build assembles with a newer existing APK" test_make_build_assembles_when_apk_is_newer_than_assets
run_test "successful make run assembles with a newer existing APK" test_successful_make_run_assembles_when_apk_is_newer_than_assets
run_test "Makefile runtime architecture wiring" test_makefile_runtime_arch_wiring
run_test "parallel run failure does not build APK" test_parallel_run_failure_does_not_build_apk
run_test "runtime architecture rejects shell injection" test_runtime_arch_rejects_shell_injection
run_test "failed build preserves existing assets" test_failed_build_preserves_assets
run_test "unsupported target rejection" test_unsupported_target

echo "All $TESTS_RUN runtime tool tests passed."

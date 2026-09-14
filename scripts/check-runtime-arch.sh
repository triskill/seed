#!/usr/bin/env bash
# Verify that a packaged PRoot binary is native ARM64.
# Usage: ./scripts/check-runtime-arch.sh <arm64|aarch64|arm64-v8a> <proot-path>
set -euo pipefail

usage() {
    echo "usage: $0 <arm64|aarch64|arm64-v8a> <proot-path>" >&2
}

if [[ $# -ne 2 ]]; then
    usage
    exit 2
fi

case "${1,,}" in
    arm64|aarch64|arm64-v8a)
        expected_arch="arm64"
        ;;
    *)
        echo "unsupported expected architecture: $1" >&2
        echo "supported architecture: arm64/aarch64/arm64-v8a" >&2
        exit 2
        ;;
esac

runtime_path="$2"
explicit_build_command="make runtime RUNTIME_ARCH=$expected_arch"

if [[ ! -f "$runtime_path" ]]; then
    echo "runtime binary not found: $runtime_path" >&2
    echo "Build compatible assets explicitly: $explicit_build_command" >&2
    exit 1
fi

if ! description="$(LC_ALL=C file -Lb "$runtime_path" 2>&1)"; then
    echo "could not inspect runtime binary: $runtime_path" >&2
    echo "file output: $description" >&2
    echo "Build compatible assets explicitly: $explicit_build_command" >&2
    exit 1
fi

if [[ "$description" != ELF\ 64-bit* ]]; then
    echo "runtime binary is not a 64-bit ELF: $runtime_path" >&2
    echo "file output: $description" >&2
    echo "Build compatible assets explicitly: $explicit_build_command" >&2
    exit 1
fi

case "$description" in
    *"ARM aarch64"*)
        actual_arch="arm64"
        ;;
    *)
        echo "unrecognized runtime architecture: $runtime_path" >&2
        echo "file output: $description" >&2
        echo "Build compatible assets explicitly: $explicit_build_command" >&2
        exit 1
        ;;
esac

if [[ "$actual_arch" != "$expected_arch" ]]; then
    echo "runtime architecture mismatch: emulator expects $expected_arch, but $runtime_path is $actual_arch" >&2
    echo "Build compatible assets explicitly: $explicit_build_command" >&2
    exit 1
fi

echo "runtime architecture matches: $expected_arch"

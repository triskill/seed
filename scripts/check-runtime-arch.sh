#!/usr/bin/env bash
# Verify that the packaged proot binary can run on the configured emulator.
# Usage: ./scripts/check-runtime-arch.sh <expected-arch> [proot-path]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_PROOT="$SCRIPT_DIR/../android/app/src/main/assets/linux/proot"

usage() {
    echo "usage: $0 <x86_64|amd64|arm64|aarch64|arm64-v8a> [proot-path]" >&2
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
    usage
    exit 2
fi

case "${1,,}" in
    x86_64|amd64)
        expected_arch="x86_64"
        ;;
    arm64|aarch64|arm64-v8a)
        expected_arch="arm64"
        ;;
    *)
        echo "unsupported expected architecture: $1" >&2
        echo "supported architectures: x86_64/amd64, arm64/aarch64/arm64-v8a" >&2
        exit 2
        ;;
esac

runtime_path="${2:-$DEFAULT_PROOT}"
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
    *x86-64*)
        actual_arch="x86_64"
        ;;
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

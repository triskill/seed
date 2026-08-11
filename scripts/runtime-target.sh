#!/usr/bin/env bash
# Runtime build target configuration. Sourcing this file only declares
# configure_runtime_target; callers explicitly choose and configure a target.

configure_runtime_target() {
    local target="${1:-}"

    case "$target" in
        arm64)
            RUNTIME_ARCH="arm64"
            PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
            ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
            ALPINE_SHA="041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de"
            DOCKER_PLATFORM="linux/arm64"
            DOCKER_IMAGE_ARCH="arm64"
            ALPINE_BASE_IMAGE="alpine:3.20.3"
            PROOT_FILE_MARKER="ARM aarch64"
            ANDROID_ABI="arm64-v8a"
            PROOT_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot.so"
            ;;
        x86_64)
            RUNTIME_ARCH="x86_64"
            PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static"
            ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/x86_64/alpine-minirootfs-3.20.3-x86_64.tar.gz"
            ALPINE_SHA="d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c"
            DOCKER_PLATFORM="linux/amd64"
            DOCKER_IMAGE_ARCH="amd64"
            ALPINE_BASE_IMAGE="alpine:3.20.3"
            PROOT_FILE_MARKER="x86-64"
            ANDROID_ABI="x86_64"
            PROOT_JNI_RELATIVE_PATH="$ANDROID_ABI/libproot.so"
            ;;
        *)
            echo "unsupported runtime architecture: ${target:-<empty>} (supported: arm64, x86_64)" >&2
            return 1
            ;;
    esac

    export RUNTIME_ARCH PROOT_URL ALPINE_URL ALPINE_SHA DOCKER_PLATFORM
    export DOCKER_IMAGE_ARCH ALPINE_BASE_IMAGE PROOT_FILE_MARKER ANDROID_ABI
    export PROOT_JNI_RELATIVE_PATH
}
